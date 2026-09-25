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
    // contains form listed elements that were linked to this form during the parse because they were not inserted as
    // descendants of it (e.g. foster-parented out of a table), or that were explicitly added via addElement()
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
     * Get the list of form control elements associated with this form. The association is evaluated on each call, so
     * controls added, moved, or re-attributed (including via the {@code form} attribute) after the parse are reflected.
     * @return form controls associated with this element, in document order.
     */
    public Elements elements() {
        Document doc = ownerDocument();
        if (doc == null) { // not in a document; only descendant and explicitly linked controls can be associated
            Elements els = select(listed);
            for (Element linkedEl : linkedEls) {
                if (!els.contains(linkedEl)) els.add(linkedEl);
            }
            return els;
        }

        Elements els = new Elements();
        for (Element el : doc.select(listed)) { // document tree order
            if (owns(el)) els.add(el);
        }
        return els;
    }

    /**
     Checks if the given form listed element is associated with (owned by) this form. A control with a {@code form}
     attribute belongs only to the first form in its document with that ID (even if not a descendant of it); an empty
     or missing ID means no owner (there is no fallback to an ancestor form). Otherwise, the control belongs to its
     nearest ancestor form, or, if it has none, to a form it was linked with during the parse (e.g. a control
     foster-parented out of a table) or via {@link #addElement(Element)}.
     */
    private boolean owns(Element el) {
        if (el.hasAttr("form")) {
            String id = el.attr("form");
            if (id.isEmpty()) return false;
            Document doc = el.ownerDocument();
            if (doc == null) return false;
            for (Element candidate : doc.getElementsByAttributeValue("id", id)) {
                if (candidate.nameIs("form"))
                    return candidate == this; // first form in the document with this ID
            }
            return false;
        }

        // no form attribute: the control belongs to its nearest ancestor form, if any
        Node parent = el.parent();
        while (parent != null) {
            if (parent.nameIs("form"))
                return parent == this;
            parent = parent.parent();
        }

        // not currently within any form; may have been linked during the parse or explicitly added
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
     * list will not be reflected in the DOM. Only successful controls are included: those with a name, that are not
     * disabled (directly or via an ancestor fieldset), and that hold a submittable value.
     * @return a list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        for (Element el : elements()) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listed, superset of submittable
            String name = el.attr("name");
            if (name.length() == 0) continue;
            if (isDisabled(el)) continue; // skip disabled controls, including those within a disabled fieldset
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                appendSelectData(data, el, name);
            } else if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                // only add checkbox or radio if they have the checked attribute
                if (el.hasAttr("checked")) {
                    final String val = el.val().length() > 0 ? el.val() : "on";
                    data.add(HttpConnection.KeyVal.create(name, val));
                }
            } else {
                data.add(HttpConnection.KeyVal.create(name, el.val()));
            }
        }
        return data;
    }

    /**
     A control is disabled if it has a {@code disabled} attribute, or is a descendant of a {@code disabled} fieldset
     (at any nesting level), unless it is within that fieldset's first {@code legend} element child.
     */
    private static boolean isDisabled(Element el) {
        if (el.hasAttr("disabled")) return true;
        Element parent = el.parent();
        while (parent != null) {
            if (parent.nameIs("fieldset") && parent.hasAttr("disabled") && !isInFirstLegend(el, parent))
                return true;
            parent = parent.parent();
        }
        return false;
    }

    /** Checks if el is the first legend child of the fieldset, or a descendant of it. */
    private static boolean isInFirstLegend(Element el, Element fieldset) {
        Element legend = null;
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) {
                legend = child;
                break;
            }
        }
        if (legend == null) return false;
        Node node = el;
        while (node != null) {
            if (node == legend) return true;
            node = node.parent();
        }
        return false;
    }

    private static void appendSelectData(ArrayList<Connection.KeyVal> data, Element select, String name) {
        Elements options = select.select("option");
        boolean anySelected = false;
        for (Element option : options) { // selected options, excluding disabled ones, in document order
            if (option.hasAttr("selected")) {
                anySelected = true;
                if (!isDisabledOption(option))
                    data.add(HttpConnection.KeyVal.create(name, optionValue(option)));
            }
        }
        if (!anySelected && !select.hasAttr("multiple")) {
            // no option was preselected; submit the first available option (a multiple select submits no values)
            for (Element option : options) {
                if (!isDisabledOption(option)) {
                    data.add(HttpConnection.KeyVal.create(name, optionValue(option)));
                    break;
                }
            }
        }
    }

    /** An option is disabled if it has a {@code disabled} attribute, or is the child of a {@code disabled} optgroup. */
    private static boolean isDisabledOption(Element option) {
        if (option.hasAttr("disabled")) return true;
        Element parent = option.parent();
        return parent != null && parent.nameIs("optgroup") && parent.hasAttr("disabled");
    }

    /** The option's submitted value: its {@code value} attribute, or its text if no value attribute is set. */
    private static String optionValue(Element option) {
        return option.hasAttr("value") ? option.attr("value") : option.text();
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
