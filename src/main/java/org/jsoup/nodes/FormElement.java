package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
import org.jsoup.internal.SharedConstants;
import org.jsoup.internal.StringUtil;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
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
    private static final String FormAttr = "form";

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
     * Get the list of form control elements associated with this form, in document order. Includes controls that are
     * descendants of this form, controls that were linked to this form during the parse (and due to parse rules, may
     * no longer be children of this form), and elements elsewhere in the document that reference this form's ID with a
     * {@code form} attribute. Controls that reference a different (or an unknown) form, or that have been removed
     * from the document, are not included.
     * @return form controls associated with this element.
     */
    public Elements elements() {
        // As elements may have been added or removed from the DOM after parse, prepare a new list of the associated
        // controls, merged in document order:
        Elements els = new Elements();
        Document doc = ownerDocument();
        Node root = doc != null ? doc : this;
        String id = id();

        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof Element) {
                Element el = (Element) node;
                if (StringUtil.inSorted(el.normalName(), SharedConstants.FormListedTags) && isAssociated(el, id))
                    els.add(el);
            }
        }, root);

        return els;
    }

    /**
     Tests if the element is associated with this form: by a {@code form} attribute referencing this form's ID, by
     being a descendant of this form, or by having been linked to this form during the parse.
     */
    private boolean isAssociated(Element el, String id) {
        if (el.hasAttr(FormAttr)) // an explicit form attribute overrides ancestor or parser association
            return !id.isEmpty() && el.attr(FormAttr).equals(id);

        Element parent = el.parent();
        while (parent != null) {
            if (parent == this) return true;
            parent = parent.parent();
        }

        // linked during the parse (error recovery may have moved it out of this form); still associated while it
        // remains in the same document
        return linkedEls.contains(el) && el.ownerDocument() == ownerDocument();
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
        Elements formEls = elements();
        for (Element el: formEls) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            if (isDisabled(el)) continue; // skip disabled form inputs
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                boolean multiple = el.hasAttr("multiple");
                boolean set = false;
                Elements options = el.select("option");
                for (Element option : options) {
                    if (!option.hasAttr("selected") || isDisabledOption(option)) continue;
                    data.add(HttpConnection.KeyVal.create(name, option.val()));
                    set = true;
                    if (!multiple) break; // a single-value select submits only one option
                }
                if (!set && !multiple) {
                    // no selected enabled option; fall back to the first enabled option
                    for (Element option : options) {
                        if (isDisabledOption(option)) continue;
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
     Tests if a form control is disabled: either directly, or as a descendant of a disabled fieldset (unless it is
     within that fieldset's first legend element).
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

    /** Tests if el is within the first legend child of a fieldset. */
    private static boolean inFirstLegend(Element fieldset, Element el) {
        for (Element child : fieldset.children()) {
            if (child.nameIs("legend")) {
                Element node = el;
                while (node != null && node != fieldset) {
                    if (node == child) return true;
                    node = node.parent();
                }
                return false; // only the first legend child is excepted
            }
        }
        return false;
    }

    /** Tests if an option is disabled, either directly or via a disabled optgroup. */
    private static boolean isDisabledOption(Element option) {
        if (option.hasAttr("disabled")) return true;
        Element parent = option.parent();
        return parent != null && parent.nameIs("optgroup") && parent.hasAttr("disabled");
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
