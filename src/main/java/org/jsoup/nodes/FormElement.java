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
import java.util.List;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private final Elements linkedEls = new Elements();
    // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)
    private static final Evaluator formListed = Selector.evaluatorOf(StringUtil.join(SharedConstants.FormListedTags, ", "));

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
     * Get the list of form control elements associated with this form. Controls are returned in document order, and
     * may be descendants of this form, linked to it during parse error recovery, or associated via their
     * {@code form} attribute.
     * @return form controls associated with this element.
     */
    public Elements elements() {
        // As elements may have been added or removed from the DOM after parse, prepare a new list that unions them:
        Document doc = ownerDocument();
        Elements candidates;
        if (doc != null) {
            // scan the document so that controls moved by parse error recovery, and controls associated via their form
            // attribute, are all found in document order
            candidates = doc.select(formListed);
        } else {
            candidates = select(formListed); // current form children
            for (Element linkedEl : linkedEls) {
                if (linkedEl.ownerDocument() != null && !candidates.contains(linkedEl)) {
                    candidates.add(linkedEl); // adds previously linked elements, that weren't previously removed from the DOM
                }
            }
        }

        Elements els = new Elements();
        for (Element el : candidates) {
            if (isAssociatedControl(el)) els.add(el);
        }
        return els;
    }

    /**
     Tests if the element is a form control currently associated with this form: it is a descendant of the form; it was
     linked to the form during the parse, is still in the document, and has not been re-parented into another form; or
     its {@code form} attribute points to this form's ID. An explicit {@code form} attribute takes precedence over
     ancestor and parse-time association.
     */
    private boolean isAssociatedControl(Element el) {
        String formId = el.attr("form");
        if (!formId.isEmpty())
            return formId.equals(attr("id"));

        boolean linked = linkedEls.contains(el);
        Element parent = el.parent();
        while (parent != null) {
            if (parent == this) return true; // simple child of this form
            if (parent.nameIs("form")) return false; // re-parented into a different form
            parent = parent.parent();
        }
        return linked && el.ownerDocument() != null;
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
            if (isDisabled(el)) continue; // skip disabled form inputs
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                Elements options = el.select("option");
                boolean multiple = el.hasAttr("multiple");
                boolean set = false;
                for (Element option : options) {
                    if (!isEnabledOption(option)) continue; // skip disabled options and optgroups
                    if (option.hasAttr("selected")) {
                        data.add(HttpConnection.KeyVal.create(name, option.val()));
                        set = true;
                        if (!multiple) break; // a single select submits only its first selected option
                    }
                }
                if (!set && !multiple) {
                    // with nothing selected, a single select submits its first enabled option
                    for (Element option : options) {
                        if (!isEnabledOption(option)) continue;
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
     A control is disabled if it has the {@code disabled} attribute, or is a descendant of a disabled {@code fieldset}
     (unless it is within that fieldset's first {@code legend} element).
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

    /** Tests if the element is a descendant of the first {@code legend} child of the {@code fieldset}. */
    private static boolean inFirstLegend(Element fieldset, Element el) {
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) {
                Element parent = el.parent();
                while (parent != null) {
                    if (parent == child) return true;
                    parent = parent.parent();
                }
                return false; // only the first legend provides an exception
            }
        }
        return false;
    }

    /** An option is enabled if it does not have the {@code disabled} attribute and is not in a disabled {@code optgroup}. */
    private static boolean isEnabledOption(Element option) {
        if (option.hasAttr("disabled")) return false;
        Element parent = option.parent();
        while (parent != null && !parent.nameIs("select")) {
            if (parent.nameIs("optgroup") && parent.hasAttr("disabled")) return false;
            parent = parent.parent();
        }
        return true;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
