package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.jsoup.select.Evaluator;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.Selector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private final Elements linkedEls = new Elements();
    // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)
    private static final Evaluator formListed = Selector.evaluatorOf("button, fieldset, input, keygen, object, output, select, textarea");

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
     * Get the list of form control elements associated with this form. This includes controls that are descendants of
     * the form, controls that were linked to the form during the parse (and which parse error recovery may have moved
     * out of the form), and controls elsewhere in the document with a {@code form} attribute naming this form's ID.
     * Controls that have been removed from the document, or reassociated to another form, are not included.
     * @return form controls associated with this element, in document order, without duplicates.
     */
    public Elements elements() {
        // As elements may have been added, moved, or removed from the DOM after parse, prepare a new list on each call,
        // collected in document order:
        final Elements els = new Elements();
        final Document doc = ownerDocument();
        final Node root = doc != null ? doc : this;
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof Element) {
                Element el = (Element) node;
                if (isAssociatedControl(el)) els.add(el);
            }
        }, root);
        return els;
    }

    /**
     * Tests if the element is a form-listed control that is currently associated with this form: either it names this
     * form in its {@code form} attribute, or (absent that attribute) this form is its nearest ancestor form, or it was
     * linked to this form during the parse and remains outside any other form.
     */
    private boolean isAssociatedControl(Element el) {
        if (!el.is(formListed)) return false;
        if (el.hasAttr("form")) { // an explicit form attribute overrides ancestor association
            String formId = el.attr("form");
            return !formId.isEmpty() && formId.equals(attr("id"));
        }
        for (Element ancestor = el.parent(); ancestor != null; ancestor = ancestor.parent()) {
            if (ancestor.nameIs("form")) return ancestor == this;
        }
        return linkedEls.contains(el); // linked during the parse and moved out of the form (e.g. foster-parented)
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
     Prepare to submit this form. A Connection object is created with the request set up from the form values. This
     Connection will inherit the settings and the cookies (etc) of the connection/session used to request this Document
     (if any), as available in {@link Document#connection()}
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
     * list will not be reflected in the DOM. Repeated calls reflect the current state of the document, and do not
     * modify it.
     * @return a list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        for (Element el : elements()) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listed, superset of submittable
            if (isDisabled(el)) continue; // skip disabled controls, including those in a disabled fieldset
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                Elements options = el.select("option");
                if (el.hasAttr("multiple")) {
                    // add every selected, enabled option, in document order
                    for (Element option : options) {
                        if (option.hasAttr("selected") && isEnabledOption(option))
                            data.add(HttpConnection.KeyVal.create(name, option.val()));
                    }
                } else {
                    // a single-value select submits at most one value: the first selected enabled option, or if there
                    // is none, the first enabled option
                    Element selected = null, firstEnabled = null;
                    for (Element option : options) {
                        if (!isEnabledOption(option)) continue;
                        if (firstEnabled == null) firstEnabled = option;
                        if (option.hasAttr("selected")) { selected = option; break; }
                    }
                    Element option = selected != null ? selected : firstEnabled;
                    if (option != null)
                        data.add(HttpConnection.KeyVal.create(name, option.val()));
                }
            } else if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                // only add checkbox or radio if they have the checked attribute
                if (el.hasAttr("checked")) {
                    final String val = el.val().length() >  0 ? el.val() : "on";
                    data.add(HttpConnection.KeyVal.create(name, val));
                }
            } else {
                data.add(HttpConnection.KeyVal.create(name, el.val()));
            }
        }
        return data;
    }

    /**
     * Tests if a control is disabled: either it has a {@code disabled} attribute itself, or it is within a disabled
     * {@code fieldset} (other than within that fieldset's first {@code legend} element child, whose controls remain
     * enabled).
     */
    private static boolean isDisabled(Element el) {
        if (el.hasAttr("disabled")) return true;
        for (Element ancestor = el.parent(); ancestor != null; ancestor = ancestor.parent()) {
            if (ancestor.nameIs("fieldset") && ancestor.hasAttr("disabled") && !inFirstLegend(ancestor, el))
                return true;
        }
        return false;
    }

    /** Tests if el is within the first {@code legend} element child of the fieldset. */
    private static boolean inFirstLegend(Element fieldset, Element el) {
        Element legend = null;
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) { legend = child; break; }
        }
        if (legend == null) return false;
        for (Element p = el.parent(); p != null && p != fieldset; p = p.parent()) {
            if (p == legend) return true;
        }
        return false;
    }

    /** Tests if an option is usable: not disabled itself, and not within a disabled {@code optgroup}. */
    private static boolean isEnabledOption(Element option) {
        if (option.hasAttr("disabled")) return false;
        Element parent = option.parent();
        return parent == null || !parent.nameIs("optgroup") || !parent.hasAttr("disabled");
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
