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
import java.util.HashSet;
import java.util.List;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private final Elements linkedEls = new Elements();
    // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)
    private static final Evaluator listed = Selector.evaluatorOf(StringUtil.join(SharedConstants.FormListedTags, ", "));

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
     * Get the list of form control elements associated with this form. Controls may be associated as descendants of
     * this form, by a {@code form} attribute referencing this form's ID, or by a parse-time association (which may
     * have been displaced when the parser repaired the HTML). The returned list is in document order.
     * @return form controls associated with this element.
     */
    public Elements elements() {
        // As elements may have been added or removed from the DOM after parse, prepare a new list that unions the
        // different association sources, and filter to those currently associated with this form:
        Elements candidates = select(listed); // current form descendants
        for (Element linkedEl : linkedEls) {
            if (linkedEl.ownerDocument() != null && !candidates.contains(linkedEl)) {
                candidates.add(linkedEl); // adds previously linked elements, that weren't previously removed from the DOM
            }
        }
        Document doc = ownerDocument();
        String id = id();
        if (doc != null && !id.isEmpty()) {
            for (Element el : doc.getElementsByAttributeValue("form", id)) { // associated via the form attribute
                if (el.is(listed) && !candidates.contains(el))
                    candidates.add(el);
            }
        }

        HashSet<Element> associated = new HashSet<>();
        for (Element el : candidates) {
            if (formOwner(el) == this)
                associated.add(el);
        }

        // merge into document order with a single tree walk, so each control appears once at its final position
        Elements els = new Elements();
        if (associated.isEmpty()) return els;
        Node root = root();
        if (root instanceof Element) { // a Document, or a detached element
            for (Element el : ((Element) root).getAllElements()) {
                if (associated.contains(el))
                    els.add(el);
            }
        }
        return els;
    }

    /**
     Gets the form that currently owns (is associated with) the given control, per the HTML form-owner rules: an
     explicit {@code form} attribute takes precedence; otherwise the nearest ancestor form; otherwise a parse-time
     association, if the control is still in this form's tree.
     */
    private @Nullable FormElement formOwner(Element el) {
        if (el.hasAttr("form")) {
            String formId = el.attr("form");
            Document doc = el.ownerDocument();
            if (!formId.isEmpty() && doc != null) {
                Element owner = doc.getElementById(formId);
                if (owner instanceof FormElement) return (FormElement) owner;
            }
            return null; // an explicit but unresolvable form reference associates the control with no form
        }
        Element parent = el.parent();
        while (parent != null) {
            if (parent instanceof FormElement) return (FormElement) parent;
            parent = parent.parent();
        }
        if (linkedEls.contains(el) && el.root() == root()) return this;
        return null;
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
     * Get the data that this form submits, using the browser rules for successful controls. The returned list is a
     * copy of the data, and changes to the contents of the list will not be reflected in the DOM.
     * @return a list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        for (Element el : elements()) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            if (isDisabled(el)) continue; // skip disabled controls (including those in a disabled fieldset)
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                boolean multiple = el.hasAttr("multiple");
                Elements options = el.select("option");
                boolean set = false;
                for (Element option : options) {
                    if (!option.hasAttr("selected") || option.hasAttr("disabled")) continue; // disabled options are not submittable
                    data.add(HttpConnection.KeyVal.create(name, option.val()));
                    set = true;
                    if (!multiple) break; // a single-select submits only one option
                }
                if (!set && !multiple) {
                    // no usable selected option; submit the first enabled option instead
                    for (Element option : options) {
                        if (option.hasAttr("disabled")) continue;
                        data.add(HttpConnection.KeyVal.create(name, option.val()));
                        break;
                    }
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
     Tests if a form control is disabled: either it has a {@code disabled} attribute itself, or it is a descendant of a
     disabled {@code fieldset} (unless within that fieldset's first {@code legend} element).
     */
    private static boolean isDisabled(Element el) {
        if (el.hasAttr("disabled")) return true;
        Element parent = el.parent();
        while (parent != null) {
            if (parent.nameIs("fieldset") && parent.hasAttr("disabled") && !inFirstLegend(parent, el))
                return true;
            parent = parent.parent();
        }
        return false;
    }

    /** Tests if el is within the first legend element child of the (disabled) fieldset. */
    private static boolean inFirstLegend(Element fieldset, Element el) {
        for (Element child : fieldset.children()) {
            if (!child.nameIs("legend")) continue;
            Element parent = el.parent();
            while (parent != null) {
                if (parent == child) return true;
                parent = parent.parent();
            }
            return false; // only the first legend excepts controls from the fieldset's disabled state
        }
        return false;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
