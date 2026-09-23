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
    private static final Evaluator submittable = Selector.evaluatorOf(StringUtil.join(SharedConstants.FormSubmitTags, ", "));

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
     Get the data that this form submits. The returned list is computed fresh from the form's associated controls in
     document order, on every call, and is independent of the DOM: modifying the list does not change the document, and
     controls sharing a name each contribute their own entry.
     <p>The controls are filtered following the HTML form submission rules:</p>
     <ul>
     <li>controls that are themselves {@code disabled}, or that are inside a {@code disabled} {@code <fieldset>}, are
     skipped — except for controls inside that fieldset's first {@code <legend>}; a nested disabled fieldset is not
     re-enabled by an outer fieldset's legend;</li>
     <li>a single-selection {@code <select>} submits the first enabled selected option, or, when nothing is selected, the
     first enabled option; if there is no enabled option, it submits nothing;</li>
     <li>a {@code multiple} {@code <select>} submits every enabled selected option in order, and nothing if there are
     none;</li>
     <li>{@code <option>}s that are disabled, or that are within a disabled {@code <optgroup>}, are not eligible;</li>
     <li>checkboxes and radio buttons are only included when checked;</li>
     <li>{@code <input>} elements of type {@code button} and {@code image}, and {@code <button>} elements, are not
     submitted; other input types (including {@code submit} and {@code reset}) are included as before.</li>
     </ul>
     @return a fresh, independent list of key vals
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        for (Element el: elements()) {
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            if (el.nameIs("button")) continue; // <button> elements are never serialized by jsoup
            if (el.hasAttr("disabled")) continue; // skip disabled form inputs
            if (inDisabledFieldset(el)) continue; // skip controls in a disabled fieldset (first legend excepted)
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (el.nameIs("select")) {
                boolean multiple = el.hasAttr("multiple");
                Elements selected = el.select("option[selected]");
                if (!selected.isEmpty()) {
                    if (multiple) {
                        for (Element option : selected) {
                            if (isEnabledOption(option))
                                data.add(HttpConnection.KeyVal.create(name, option.val()));
                        }
                    } else {
                        // single select: only the first enabled selected option counts
                        for (Element option : selected) {
                            if (isEnabledOption(option)) {
                                data.add(HttpConnection.KeyVal.create(name, option.val()));
                                break;
                            }
                        }
                    }
                } else if (!multiple) {
                    // nothing marked selected: a single select defaults to its first enabled option
                    for (Element option : el.select("option")) {
                        if (isEnabledOption(option)) {
                            data.add(HttpConnection.KeyVal.create(name, option.val()));
                            break;
                        }
                    }
                }
                // a multiple select with no enabled selected option submits nothing
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
     Check if a control is excluded by a disabled ancestor fieldset. A disabled fieldset disables its descendants,
     except for those inside its first {@code legend} child's subtree. A nested disabled fieldset stays disabled even
     when it sits within an outer fieldset's exempt legend.
     @param el the control to check
     @return true if the control is within a disabled fieldset without an exempting legend
     */
    private static boolean inDisabledFieldset(Element el) {
        Element child = el;
        for (Element parent = el.parent(); parent != null && !parent.nameIs("#root"); child = parent, parent = parent.parent()) {
            if (parent.nameIs("fieldset") && parent.hasAttr("disabled") && !isFirstLegendChild(child, parent))
                return true;
            // if exempted by this fieldset's first legend, keep climbing — an outer disabled fieldset may still apply
        }
        return false;
    }

    /**
     Check if {@code child} is the first {@code legend} child of {@code fieldset}; the subtree of that legend is
     exempted from the fieldset's disabled state.
     */
    private static boolean isFirstLegendChild(Element child, Element fieldset) {
        if (!child.nameIs("legend")) return false;
        for (Element fieldsetChild : fieldset.children()) {
            if (fieldsetChild.nameIs("legend")) return fieldsetChild == child; // first legend encountered in tree order
        }
        return false;
    }

    /**
     Check if an option is eligible for submission: not itself disabled, and not inside a disabled optgroup.
     @param option the option to check
     @return true if the option may be submitted
     */
    private static boolean isEnabledOption(Element option) {
        if (!option.nameIs("option") || option.hasAttr("disabled")) return false;
        Element parent = option.parent();
        if (parent != null && parent.nameIs("optgroup") && parent.hasAttr("disabled")) return false;
        return true;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
