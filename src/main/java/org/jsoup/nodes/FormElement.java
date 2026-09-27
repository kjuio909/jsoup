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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private final Elements linkedEls = new Elements();
    // contains form submittable elements that were linked during the parse (and due to parse rules, may no longer be a child of this form)

    /** HTML form-listed elements (those that can be associated with a form), sorted for binary search. */
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
     * Get the list of form control elements associated with this form. Controls are associated by ancestry, by a
     * {@code form} attribute pointing to this form's ID, or by a parse-time link (retained for controls that the
     * parser moved out of the form, e.g. by foster parenting). The association is evaluated against the current tree,
     * so controls that have been removed, detached, or reassigned to another form no longer appear.
     * @return form controls associated with this element, each once, in document order.
     */
    public Elements elements() {
        Elements els = new Elements();
        Document doc = ownerDocument();
        Set<Element> linked = linkedEls.isEmpty() ? null : new HashSet<>(linkedEls);

        Node root = root();
        if (!(root instanceof Element)) return els;
        for (Element el : ((Element) root).getAllElements()) {
            if (!StringUtil.inSorted(el.normalName(), FormListedTags)) continue;
            if (isAssociated(el, doc, linked)) els.add(el);
        }
        return els;
    }

    /**
     Tests if {@code el} is currently associated with this form. A {@code form} attribute takes precedence and must
     resolve to this form; otherwise the nearest ancestor form owns the control; otherwise a parse-time link holds
     while the control remains in the same tree.
     */
    private boolean isAssociated(Element el, @Nullable Document doc, @Nullable Set<Element> linked) {
        if (doc != null && el.hasAttr("form")) {
            String formId = el.attr("form");
            return !formId.isEmpty() && doc.getElementById(formId) == this;
        }
        for (Element parent = el.parent(); parent != null; parent = parent.parent()) {
            if (parent instanceof FormElement) return parent == this;
        }
        return linked != null && linked.contains(el);
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
            if (isDisabled(el)) continue; // skip disabled controls (incl. those under a disabled fieldset)
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                addSelectData(data, el, name);
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
     Accumulates the submittable value(s) of a select element. A single select submits the first enabled selected
     option, falling back to the first enabled option; a multiple select submits every enabled selected option (or
     nothing, if there are none).
     */
    private static void addSelectData(ArrayList<Connection.KeyVal> data, Element select, String name) {
        Elements options = select.select("option"); // in document order
        if (select.hasAttr("multiple")) {
            for (Element option : options) {
                if (option.hasAttr("selected") && !isDisabledOption(option))
                    data.add(HttpConnection.KeyVal.create(name, option.val()));
            }
        } else {
            Element firstEnabled = null, selected = null;
            for (Element option : options) {
                if (isDisabledOption(option)) continue;
                if (firstEnabled == null) firstEnabled = option;
                if (option.hasAttr("selected")) {
                    selected = option;
                    break;
                }
            }
            Element chosen = selected != null ? selected : firstEnabled;
            if (chosen != null)
                data.add(HttpConnection.KeyVal.create(name, chosen.val()));
        }
    }

    /**
     A control is disabled if it has the disabled attribute, or is a descendant of a disabled fieldset — unless it is
     within that fieldset's first legend element child.
     */
    private static boolean isDisabled(Element el) {
        if (el.hasAttr("disabled")) return true;
        for (Element ancestor = el.parent(); ancestor != null; ancestor = ancestor.parent()) {
            if (ancestor.nameIs("fieldset") && ancestor.hasAttr("disabled") && !inFirstLegend(el, ancestor))
                return true;
        }
        return false;
    }

    /** Tests if {@code el} is contained within the first legend element child of {@code fieldset}. */
    private static boolean inFirstLegend(Element el, Element fieldset) {
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

    /** An option is disabled if it has the disabled attribute, or sits under a disabled optgroup. */
    private static boolean isDisabledOption(Element option) {
        if (option.hasAttr("disabled")) return true;
        for (Element parent = option.parent(); parent != null && !parent.nameIs("select"); parent = parent.parent()) {
            if (parent.nameIs("optgroup") && parent.hasAttr("disabled")) return true;
        }
        return false;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
