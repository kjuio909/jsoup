package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
import org.jsoup.internal.StringUtil;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
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

    // form listed element tags; kept in sync with HtmlTreeBuilder.TagFormListed (sorted for StringUtil.inSorted)
    private static final String[] FormListedTags = {
        "button", "fieldset", "input", "keygen", "object", "output", "select", "textarea"
    };

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
     * Get the list of form control elements associated with this form. Controls are associated by descendant
     * containment, by a {@code form} attribute matching this form's {@code id}, or by parse-time linkage (when parse
     * rules moved the control out of this form's subtree). The list is in document order, reflecting the current
     * state of the DOM; controls that have been removed, detached, or reassociated with another form are not
     * included.
     * @return form controls associated with this element.
     */
    public Elements elements() {
        // Collect associated controls in a single document-order walk from the root, so that controls appear exactly
        // once and in their final document positions, however they were associated.
        final Elements els = new Elements();
        final @Nullable String formId = hasAttr("id") ? attr("id") : null;
        root().forEachNode(node -> {
            if (node == this || !(node instanceof Element)) return;
            Element el = (Element) node;
            if (isAssociated(el, formId)) els.add(el);
        });
        return els;
    }

    /** Tests if the element is a form-listed control currently associated with this form. */
    private boolean isAssociated(Element el, @Nullable String formId) {
        if (!StringUtil.inSorted(el.normalName(), FormListedTags)) return false;

        // an explicit form attribute overrides ancestor and parse-time association
        if (el.hasAttr("form"))
            return formId != null && !formId.isEmpty() && el.attr("form").equals(formId);

        for (Element parent = el.parent(); parent != null; parent = parent.parent()) {
            if (parent == this) return true; // a descendant of this form
            if (parent instanceof FormElement) return false; // a nearer form ancestor claims it
        }
        // not currently within any form; retain the parse-time linkage (e.g. foster-parented out of a table), which
        // is only valid while the control remains in the same tree as this form (guaranteed by the root walk)
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
     * list will not be reflected in the DOM.
     * @return a list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        for (Element el : elements()) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            if (el.hasAttr("disabled")) continue; // skip disabled form inputs
            if (isDisabledByFieldset(el)) continue; // skip controls in a disabled fieldset, excepting its first legend
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                Elements options = el.select("option");
                if (el.hasAttr("multiple")) {
                    // submit every selected, enabled option, in document order
                    for (Element option : options) {
                        if (option.hasAttr("selected") && isEnabledOption(option))
                            data.add(HttpConnection.KeyVal.create(name, option.val()));
                    }
                } else {
                    // submit the first selected, enabled option; if none, fall back to the first enabled option
                    Element selected = null;
                    for (Element option : options) {
                        if (option.hasAttr("selected") && isEnabledOption(option)) {
                            selected = option;
                            break;
                        }
                    }
                    if (selected == null) {
                        for (Element option : options) {
                            if (isEnabledOption(option)) {
                                selected = option;
                                break;
                            }
                        }
                    }
                    if (selected != null)
                        data.add(HttpConnection.KeyVal.create(name, selected.val()));
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

    /** Tests if the element is disabled by a disabled fieldset ancestor. Controls within the fieldset's first legend
     element child (and its subtree) are not disabled by that fieldset. */
    private static boolean isDisabledByFieldset(Element el) {
        for (Element ancestor = el.parent(); ancestor != null; ancestor = ancestor.parent()) {
            if (ancestor.nameIs("fieldset") && ancestor.hasAttr("disabled") && !inFirstLegend(ancestor, el))
                return true;
        }
        return false;
    }

    /** Tests if the element is within the first legend element child of the (disabled) fieldset. */
    private static boolean inFirstLegend(Element fieldset, Element el) {
        Element legend = null;
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) {
                legend = child;
                break;
            }
        }
        if (legend == null) return false;
        for (Element node = el; node != null && node != fieldset; node = node.parent()) {
            if (node == legend) return true;
        }
        return false;
    }

    /** Tests if an option is enabled; i.e. not itself disabled, and not within a disabled optgroup. */
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
