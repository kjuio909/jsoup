package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
import org.jsoup.internal.SharedConstants;
import org.jsoup.internal.StringUtil;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.jsoup.select.Evaluator;
import org.jsoup.select.Selector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private final Elements linkedEls = new Elements();
    // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)
    private static final Evaluator submittable = Selector.evaluatorOf(StringUtil.join(SharedConstants.FormSubmitTags, ", "));
    // the form-listed elements (HTML spec) that can be associated with a form, either as a descendant or via a form attribute
    private static final String[] FormListedTags = {
        "button", "fieldset", "input", "keygen", "object", "output", "select", "textarea"
    };
    private static final Evaluator listed = Selector.evaluatorOf(StringUtil.join(FormListedTags, ", "));
    // form-listed elements, anywhere in the document, that carry a form attribute (potential form-owner override)
    private static final Evaluator listedWithFormAttr = Selector.evaluatorOf(formListedWithFormAttrQuery());

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

    private static String formListedWithFormAttrQuery() {
        StringBuilder query = new StringBuilder();
        for (String tag : FormListedTags) {
            if (query.length() > 0) query.append(", ");
            query.append(tag).append("[form]");
        }
        return query.toString();
    }

    /**
     * Get the list of form control elements associated with this form.
     * @return form controls associated with this element.
     */
    public Elements elements() {
        // As elements may have been added or removed from the DOM after parse, prepare a new list that unions them:
        Elements els = select(submittable); // current form children
        for (Element linkedEl : linkedEls) {
            if (linkedEl.ownerDocument() != null && !els.contains(linkedEl)) {
                els.add(linkedEl); // adds previously linked elements, that weren't previously removed from the DOM
            }
        }

        return els;
    }

    /**
     * Get the form-listed controls currently associated with this form, in final document order. A control is
     * associated if it is a descendant of this form, if it was linked to this form by the parser and is not nested in
     * another form, or if it carries a {@code form} attribute whose value resolves to this form's id. Controls with a
     * {@code form} attribute pointing at another (or no) form, controls nested in another form, detached controls, and
     * duplicates are excluded.
     */
    private Elements associatedControls() {
        Document doc = ownerDocument();
        Elements controls = new Elements();

        // current form descendants (in document order)
        for (Element el : select(listed)) {
            if (belongsToThisForm(el, doc)) controls.add(el);
        }

        // parser-linked controls that may live outside of the form (e.g. fostered out of table markup)
        if (doc != null) {
            for (Element el : linkedEls) {
                if (el.ownerDocument() == doc && !controls.contains(el) && belongsToThisForm(el, doc))
                    controls.add(el);
            }
        }

        // controls outside of the form, associated by a form="id" attribute
        if (doc != null && !id().isEmpty()) {
            for (Element el : doc.select(listedWithFormAttr)) {
                if (!controls.contains(el) && belongsToThisForm(el, doc))
                    controls.add(el);
            }
        }

        // the descendant scan is already in document order; re-sort when out-of-form controls may have been appended
        if (doc != null) controls.sort(DocumentOrder);
        return controls;
    }

    /** A listed control belongs to this form if its form attribute resolves to this form; an empty or unresolvable
     * form attribute means no owner. Without a form attribute, a nested control belongs to its nearest form ancestor,
     * and an out-of-form control belongs only if it was parser-linked to this form. */
    private boolean belongsToThisForm(Element el, @Nullable Document doc) {
        if (el.hasAttr("form")) {
            String formOwner = el.attr("form");
            return doc != null && !formOwner.isEmpty() && doc.getElementById(formOwner) == this;
        }
        FormElement formAncestor = nearestFormAncestor(el);
        if (formAncestor != null) return formAncestor == this;
        return linkedEls.contains(el);
    }

    private static @Nullable FormElement nearestFormAncestor(Element el) {
        Node parent = el.parentNode;
        while (parent != null) {
            if (parent instanceof FormElement) return (FormElement) parent;
            parent = parent.parentNode;
        }
        return null;
    }

    // compares attached nodes by their position in a pre-order document traversal
    private static final Comparator<Element> DocumentOrder = (a, b) -> {
        if (a == b) return 0;
        ArrayList<Node> aPath = ancestorPath(a);
        ArrayList<Node> bPath = ancestorPath(b);
        int depth = Math.min(aPath.size(), bPath.size());
        for (int i = 0; i < depth; i++) {
            Node aAncestor = aPath.get(i);
            Node bAncestor = bPath.get(i);
            if (aAncestor != bAncestor)
                return Integer.compare(aAncestor.siblingIndex(), bAncestor.siblingIndex());
        }
        return Integer.compare(aPath.size(), bPath.size()); // one is an ancestor of the other
    };

    private static ArrayList<Node> ancestorPath(Node node) {
        ArrayList<Node> path = new ArrayList<>();
        Node current = node;
        while (current != null) {
            path.add(current);
            current = current.parentNode;
        }
        java.util.Collections.reverse(path);
        return path;
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
     * Get the data that this form submits. The returned list is a fresh copy on each call, and changes to the
     * returned list will not be reflected in the DOM or in subsequent calls.
     * <p>The controls are handled consistently with browser successful-control construction: disabled controls are
     * skipped, as are controls trapped in a disabled {@code fieldset} (apart from those in that fieldset's first
     * {@code legend}); single {@code select}s contribute their first enabled selected option, falling back to the first
     * enabled option; multiple {@code select}s contribute all enabled selected options in option order; checkboxes and
     * radio buttons contribute only when checked and default to {@code on} without a value.
     * @return a list of key vals, in the order the controls appear in the final document
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the associated form controls in final document order, and accumulate their values
        for (Element el: associatedControls()) {
            if (!el.tag().isFormSubmittable()) continue; // fieldset, button, output etc are listed, but not submittable
            if (el.hasAttr("disabled")) continue; // skip disabled form controls
            if (isDisabledFieldsetControl(el)) continue; // skip controls trapped by a disabled fieldset
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                addSelectData(el, name, data);
            } else if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                // only add checkbox or radio if checked; value defaults to "on"
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

    private static void addSelectData(Element select, String name, ArrayList<Connection.KeyVal> data) {
        boolean multiple = select.hasAttr("multiple");
        Elements selected = new Elements();
        Elements enabled = new Elements();
        // options are descendants of the select; an option is unavailable if disabled itself, or if its nearest
        // optgroup ancestor within the select is disabled
        for (Element option : select.select("option")) {
            if (option.hasAttr("disabled")) continue; // disabled options can never be successful
            Element optgroup = nearestOptGroup(option, select);
            if (optgroup != null && optgroup.hasAttr("disabled")) continue;
            enabled.add(option);
            if (option.hasAttr("selected")) selected.add(option);
        }

        if (multiple) {
            // submit every enabled selected option, in option order; nothing at all if there are none
            for (Element option : selected) {
                data.add(HttpConnection.KeyVal.create(name, optionValue(option)));
            }
        } else {
            // single select: the first enabled selected option wins, else the first enabled option
            Element option = !selected.isEmpty() ? selected.first() :
                (!enabled.isEmpty() ? enabled.first() : null);
            if (option != null)
                data.add(HttpConnection.KeyVal.create(name, optionValue(option)));
        }
    }

    private static @Nullable Element nearestOptGroup(Element option, Element select) {
        Node parent = option.parentNode;
        while (parent != null && parent != select) {
            if (parent instanceof Element && ((Element) parent).nameIs("optgroup"))
                return (Element) parent;
            parent = parent.parentNode;
        }
        return null;
    }

    // an option's value is its value attribute (an empty attribute still submits ""), or its text content if absent
    private static String optionValue(Element option) {
        return option.hasAttr("value") ? option.attr("value") : option.text();
    }

    /**
     A control is trapped by a disabled fieldset if it is a descendant of a disabled {@code fieldset} whose form owner
     is this form, except when it lies within that fieldset's first direct {@code legend} child's subtree. The
     exception applies only to that first legend: other sibling legends and nested fieldsets are still enforced, as is
     any outer disabled fieldset.
     */
    private boolean isDisabledFieldsetControl(Element el) {
        Document doc = ownerDocument();
        Element ancestor = el.parent();
        while (ancestor != null) {
            if (ancestor.nameIs("fieldset") && ancestor.hasAttr("disabled") && formOwner(ancestor, doc) == this) {
                Element legend = firstLegendChild(ancestor);
                if (legend == null || !isDescendant(el, legend))
                    return true; // not exempt via this fieldset's first legend
            }
            ancestor = ancestor.parent();
        }
        return false;
    }

    /** Resolves the form owner of a listed element: the element its form attribute points at (an empty or unresolvable
     * value means no owner), else its nearest form element ancestor. */
    private static @Nullable Element formOwner(Element listed, @Nullable Document doc) {
        if (listed.hasAttr("form")) {
            String ownerId = listed.attr("form");
            return doc != null && !ownerId.isEmpty() ? doc.getElementById(ownerId) : null;
        }
        Node parent = listed.parentNode;
        while (parent != null) {
            if (parent instanceof FormElement) return (Element) parent;
            parent = parent.parentNode;
        }
        return null;
    }

    private static @Nullable Element firstLegendChild(Element fieldset) {
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) return child;
        }
        return null;
    }

    private static boolean isDescendant(Element descendant, Element ancestor) {
        Node parent = descendant.parentNode;
        while (parent != null) {
            if (parent == ancestor) return true;
            parent = parent.parentNode;
        }
        return false;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
