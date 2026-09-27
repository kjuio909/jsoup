package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.jsoup.select.Evaluator;
import org.jsoup.select.Selector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private Elements linkedEls = new Elements();
    // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)
    // all form-listed elements, per the HTML spec: button, fieldset, input, keygen, object, output, select, textarea
    private static final Evaluator formListed = Selector.evaluatorOf(
        "button, fieldset, input, keygen, object, output, select, textarea");

    /**
     * Create a new, standalone form element.
     *
     * @param tag        tag of this element
     * @param baseUri    the base URI
     * @param attributes initial attributes
     */
    public FormElement(Tag tag, @Nullable String baseUri, @Nullable Attributes attributes) {
        super(tag, baseUri, attributes);
    }

    /**
     * Get the list of form control elements associated with this form. A control is associated either through a
     * matching {@code form="<id>"} attribute (resolved to the first form in the document with that id), or, when no
     * such attribute is present, through its nearest ancestor {@code <form>}; controls linked to this form by the
     * parser but relocated by parse rules (such as those fostered out of a form inside a table) are included too.
     * The list is freshly computed in document order and reflects the current state of the DOM.
     * @return form controls associated with this element.
     */
    public Elements elements() {
        Elements els = new Elements();
        Document owner = ownerDocument();
        if (owner == null) {
            // an orphaned, stand-alone form: fall back to its current descendants and parser-linked controls
            els.addAll(select(formListed));
            for (Element linkedEl : linkedEls) {
                if (!els.contains(linkedEl)) els.add(linkedEl);
            }
            return els;
        }

        // a form="<id>" reference resolves, in tree order, to the first <form> with that id
        String formId = id();
        boolean referencedFormsResolveHere = formId.length() == 0 || firstFormWithId(owner, formId) == this;

        for (Element el : owner.select(formListed)) {
            if (isFormOwner(el, formId, referencedFormsResolveHere))
                els.add(el);
        }
        return els; // owner.select yields elements in document order, and each is visited at most once
    }

    private static @Nullable Element firstFormWithId(Document owner, String formId) {
        for (Element form : owner.select("form")) {
            if (formId.equals(form.id())) return form;
        }
        return null;
    }

    /**
     * Determine whether this form is the form owner of the given control, following the HTML association rules:
     * an explicit {@code form} attribute wins and resolves to the first form with that id; otherwise the nearest
     * form ancestor owns it, falling back to the parser-established link for controls displaced by parse rules.
     */
    private boolean isFormOwner(Element el, String formId, boolean referencedFormsResolveHere) {
        String referenced = el.attr("form");
        if (referenced.length() > 0)
            return referencedFormsResolveHere && referenced.equals(formId);

        for (Element ancestor = el.parentElement(); ancestor != null; ancestor = ancestor.parentElement()) {
            if (ancestor.nameIs("form")) return ancestor == this;
        }
        // no form ancestor: retain the association established while parsing (e.g. fostered out of a table form)
        return linkedEls.contains(el);
    }

    /**
     * Add a form control element to this form.
     * @param element form control to add
     * @return this form element, for chaining
     */
    public FormElement addElement(Element element) {
        linkedEls.add(element);
        return this;
    }

    @Override
    protected void removeChild(Node out) {
        super.removeChild(out);
        linkedEls.remove(out);
    }

    /**
     * Prepare to submit this form. A Connection object is created with the request set up from the form values. This
     * Connection will inherit the settings and the cookies (etc) of the connection/session used to request this Document
     * (if any), as available in {@link Document#connection()}
     <p>You can then set up other options (like user-agent, timeout, cookies), then execute it.</p>

     @return a connection prepared from the values of this form, in the same session as the one used to request it
     @throws IllegalArgumentException if the form's absolute action URL cannot be determined. Make sure you pass the
     document's base URI when parsing.
     */
    public Connection submit() {
        String action = hasAttr("action") ? absUrl("action") : baseUri();
        Validate.notEmpty(action, "Could not determine a form action URL for submit. Ensure you set a base URI when parsing.");
        Connection.Method method = attr("method").equalsIgnoreCase("POST") ?
                Connection.Method.POST : Connection.Method.GET;

        Document owner = ownerDocument();
        Connection connection = owner != null? owner.connection().newRequest() : Jsoup.newSession();
        return connection.url(action)
                .data(formData())
                .method(method);
    }

    /**
     * Get the data that this form submits. The returned list is a copy of the data, and changes to the contents of the
     * list will not be reflected in the DOM.
     * @return a list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        for (Element el: elements()) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            String type = el.attr("type");
            // the disabled attribute does not apply to an input in the hidden state; browsers still submit it
            if (el.hasAttr("disabled") && !(el.nameIs("input") && "hidden".equalsIgnoreCase(type))) continue;
            String name = el.attr("name");
            if (name.length() == 0) continue;

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                addSelectData(name, el, data);
            } else if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                // only add checkbox or radio if they have the checked attribute
                if (el.hasAttr("checked") && !isDisabledByFieldset(el, type)) {
                    final String val = el.val().length() >  0 ? el.val() : "on";
                    data.add(HttpConnection.KeyVal.create(name, val));
                }
            } else {
                if (isDisabledByFieldset(el, type)) continue;
                data.add(HttpConnection.KeyVal.create(name, el.val()));
            }
        }
        return data;
    }

    private void addSelectData(String name, Element select, ArrayList<Connection.KeyVal> data) {
        if (isDisabledByFieldset(select, "")) return;

        boolean multiple = select.hasAttr("multiple");
        Elements options = select.select("option");
        Element firstEnabled = null;
        boolean submitted = false;

        for (Element option : options) {
            if (owningSelect(option) != select) continue; // an option belongs to its nearest enclosing <select>
            if (!optionEnabled(option)) continue;
            if (firstEnabled == null) firstEnabled = option;
            if (option.hasAttr("selected")) {
                data.add(HttpConnection.KeyVal.create(name, optionValue(option)));
                submitted = true;
                if (!multiple) return; // single-select submits the first selected enabled option, in final order
            }
        }

        if (!submitted && !multiple && firstEnabled != null) {
            // single-select with no enabled selected option falls back to the first enabled option
            data.add(HttpConnection.KeyVal.create(name, optionValue(firstEnabled)));
        }
        // multi-select with no enabled selections, or a single-select with no enabled options, submits nothing
    }

    /**
     * The value a browser submits for an {@code <option>}: its {@code value} attribute when present (including an
     * empty one), otherwise its text content.
     */
    private String optionValue(Element option) {
        return option.hasAttr("value") ? option.attr("value") : option.wholeText();
    }

    private @Nullable Element owningSelect(Element option) {
        for (Element ancestor = option.parentElement(); ancestor != null; ancestor = ancestor.parentElement()) {
            if (ancestor.nameIs("select")) return ancestor;
        }
        return null;
    }

    private boolean optionEnabled(Element option) {
        if (option.hasAttr("disabled")) return false;
        // an option is also unavailable when any enclosing <optgroup> ancestor (up to the <select>) is disabled
        for (Element ancestor = option.parentElement(); ancestor != null && !ancestor.nameIs("select");
             ancestor = ancestor.parentElement()) {
            if (ancestor.nameIs("optgroup") && ancestor.hasAttr("disabled")) return false;
        }
        return true;
    }

    /**
     * Per the HTML spec, a disabled fieldset excludes all of its descendant controls except those within the subtree
     * of its first direct {@code <legend>} child. The exception does not extend to siblings or nested fieldsets outside
     * that legend. An {@code <input type="hidden">} is not disabled by a fieldset.
     */
    private boolean isDisabledByFieldset(Element control, String type) {
        if ("hidden".equalsIgnoreCase(type)) return false;
        Element ancestor = control.parent();
        while (ancestor != null) {
            if (ancestor.nameIs("fieldset") && ancestor.hasAttr("disabled")) {
                Element legend = firstLegendChild(ancestor);
                if (legend == null || !isDescendant(control, legend)) return true;
                // control is inside the exempt first legend; continue checking ancestors outside of this fieldset
                ancestor = ancestor.parent();
            } else {
                ancestor = ancestor.parent();
            }
        }
        return false;
    }

    private @Nullable Element firstLegendChild(Element fieldset) {
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) return child;
        }
        return null;
    }

    private boolean isDescendant(Element node, Element ancestor) {
        Node parent = node.parentNode();
        while (parent != null) {
            if (parent == ancestor) return true;
            parent = parent.parentNode();
        }
        return false;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }

    @Override
    protected FormElement doClone(@Nullable Node parent) {
        FormElement clone = (FormElement) super.doClone(parent);
        // don't share the parser-linked list; its members belong to the source tree, and the clone's descendants stay
        // associated through their form ancestry
        clone.linkedEls = new Elements();
        return clone;
    }
}
