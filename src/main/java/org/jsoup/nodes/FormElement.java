package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
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
    // Listed controls whose form= attribute names their owning form, per the HTML spec
    private static final String[] FormOwnerTags =
        {"input", "keygen", "object", "select", "textarea"};
    private static final Evaluator formOwnerControl =
        Selector.evaluatorOf(StringUtil.join(FormOwnerTags, ", "));
    // All parser-listed form controls (the five above plus button, fieldset, output), considered for association
    private static final String[] ListedControlTags =
        {"button", "fieldset", "input", "keygen", "object", "output", "select", "textarea"};
    private static final Evaluator listedControl =
        Selector.evaluatorOf(StringUtil.join(ListedControlTags, ", "));

    // controls linked to this form by the parser; due to parse recovery, they may not be descendants of this form
    private final Elements linkedEls = new Elements();

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
     * <p>Association follows the HTML form ownership rules and is recomputed on every call, so moving nodes, or
     * changing a control's {@code form} attribute or a form's {@code id}, is reflected immediately:</p>
     * <ul>
     * <li>a listed control ({@code input}, {@code keygen}, {@code object}, {@code select}, {@code textarea}) that has a
     * {@code form} attribute is owned by the element in the same document whose {@code id} equals that value, but only
     * when that element is itself a {@code FormElement}; this lets the control sit outside this form, or even inside
     * another form, as the explicit owner takes precedence over an ancestor form;</li>
     * <li>a control without a {@code form} attribute is owned by its nearest ancestor {@code FormElement};</li>
     * <li>a control with no ancestor form that was linked to this form by the parser (for example, an input hoisted out
     * of a form during table recovery) stays associated with it;</li>
     * <li>a {@code form} attribute that is empty, names a missing element, or names an element that is not a form,
     * leaves the control unassociated — there is no fall back to an ancestor form.</li>
     * </ul>
     * The returned controls are in document order and de-duplicated.
     * @return form controls associated with this element
     */
    public Elements elements() {
        Elements associated = new Elements();
        Document owner = ownerDocument();
        if (owner == null) {
            // a detached form: consider its own descendants and any controls linked directly to it
            Elements candidates = select(listedControl);
            for (Element linkedEl : linkedEls) {
                if (linkedEl.ownerDocument() == null && !candidates.contains(linkedEl))
                    candidates.add(linkedEl);
            }
            for (Element el : candidates) {
                if (isAssociatedDetached(el)) associated.add(el);
            }
            return associated;
        }

        // A single document-order traversal: document order and de-duplication both come for free.
        for (Element el : owner.select(listedControl)) {
            if (isAssociated(el, owner)) associated.add(el);
        }
        return associated;
    }

    /**
     * Ownership rules for a detached form: a {@code form} attribute cannot resolve to an owner document, so it leaves
     * the control unowned; otherwise ancestry applies, falling back to a parser-established link.
     */
    private boolean isAssociatedDetached(Element el) {
        if (formOwnerControl.matches(this, el) && el.hasAttr("form")) return false;
        FormElement ancestor = nearestAncestorForm(el);
        if (ancestor != null) return ancestor == this;
        return linkedEls.contains(el);
    }

    /**
     * Apply the form ownership rules to decide whether {@code el} belongs to this form.
     */
    private boolean isAssociated(Element el, Document doc) {
        if (formOwnerControl.matches(doc, el) && el.hasAttr("form")) {
            // an explicit owner overrides ancestry outright; a dangling target means no owner at all
            String formId = el.attr("form");
            if (formId.isEmpty()) return false;
            return doc.getElementById(formId) == this;
        }

        FormElement ancestor = nearestAncestorForm(el);
        if (ancestor != null) return ancestor == this;

        // no form attribute and no ancestor: retain the parser-established association, if any
        return linkedEls.contains(el);
    }

    /**
     * Find the nearest {@code FormElement} ancestor of {@code el}, if any.
     */
    private static @Nullable FormElement nearestAncestorForm(Element el) {
        for (Element parent = el.parent(); parent != null && !parent.nameIs("#root"); parent = parent.parent()) {
            if (parent instanceof FormElement) return (FormElement) parent;
        }
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
     @throws IllegalArgumentException if the form's action URL is missing its base URI, or an absolute action URL is
     present but cannot be resolved. Make sure you pass the document's base URI when parsing.
     */
    public Connection submit() {
        String action = hasAttr("action") ? requireAbsUrl("action") : requireBaseUri();
        Connection.Method method = attr("method").equalsIgnoreCase("POST") ?
            Connection.Method.POST : Connection.Method.GET;

        Document owner = ownerDocument();
        Connection connection = owner != null? owner.connection().newRequest() : Jsoup.newSession();
        return connection.url(action)
            .data(formData())
            .method(method);
    }

    /**
     Prepare to submit this form, as if the given submit control was clicked. As {@link #submit()}, but the form data
     is collected by {@link #formData(Element, int, int)} with the given submitter and click coordinates.

     @param submitter the submit control that triggered the submission; must be a {@code button} or an
     {@code input[type=submit|image]} associated with this form (present in {@link #elements()})
     @param x the x-coordinate of the click, used when the submitter is an {@code input[type=image]}
     @param y the y-coordinate of the click, used when the submitter is an {@code input[type=image]}
     @return a connection prepared from the values of this form, in the same session as the one used to request it
     @throws IllegalArgumentException if the submitter is not a usable submit control associated with this form, or if
     the form's action URL is missing its base URI, or an absolute action URL is present but cannot be resolved
     */
    public Connection submit(Element submitter, int x, int y) {
        String action = hasAttr("action") ? requireAbsUrl("action") : requireBaseUri();
        Connection.Method method = attr("method").equalsIgnoreCase("POST") ?
            Connection.Method.POST : Connection.Method.GET;

        Document owner = ownerDocument();
        Connection connection = owner != null? owner.connection().newRequest() : Jsoup.newSession();
        return connection.url(action)
            .data(formData(submitter, x, y))
            .method(method);
    }

    private String requireBaseUri() {
        String baseUri = baseUri();
        Validate.notEmpty(baseUri, "Could not determine a form action URL for submit. Ensure you set a base URI when parsing.");
        return baseUri;
    }

    private String requireAbsUrl(String attrKey) {
        String action = absUrl(attrKey);
        Validate.notEmpty(action, String.format("Could not resolve the form's absolute %s URL for submit. Ensure you set a base URI when parsing.", attrKey));
        return action;
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
     * <li>a single-selection {@code <select>} submits the first enabled selected option only; if every selected option
     * is disabled it submits nothing (it does not fall back to an unselected option); when nothing is selected, it
     * submits the first enabled option;</li>
     * <li>a {@code multiple} {@code <select>} submits every enabled selected option in order, and nothing if there are
     * none;</li>
     * <li>{@code <option>}s that are disabled, or that are within a disabled {@code <optgroup>}, are not eligible;</li>
     * <li>checkboxes and radio buttons are only included when checked;</li>
     * <li>{@code <input>} elements of type {@code button} and {@code image}, and {@code <button>} elements, are not
     * submitted; other input types (including {@code submit} and {@code reset}) are included as before.</li>
     * </ul>
     @return a fresh, independent list of key vals
     */
    public List<Connection.KeyVal> formData() {
        return collectFormData(null, 0, 0);
    }

    /**
     Get the data that this form submits, as if the given submit control was clicked. Filtering and list semantics
     follow {@link #formData()}; the returned list is likewise computed fresh on every call and is independent of the
     DOM.
     <p>Submit controls are only counted via the {@code submitter} argument: a {@code button} or an
     {@code input[type=submit]} submitter contributes {@code name=value} only when it has a name; an
     {@code input[type=image]} submitter contributes {@code name.x=x} and {@code name.y=y} when named, or
     {@code x=x} and {@code y=y} when not. Other submit controls in the form (including named
     {@code input[type=submit]} elements, which {@link #formData()} would otherwise include) are not serialized. The
     submitter's entries appear at its position in document order.</p>
     @param submitter the submit control that triggered the submission; must be a {@code button} or an
     {@code input[type=submit|image]} associated with this form (present in {@link #elements()})
     @param x the x-coordinate of the click, used when the submitter is an {@code input[type=image]}
     @param y the y-coordinate of the click, used when the submitter is an {@code input[type=image]}
     @return a fresh, independent list of key vals
     @throws IllegalArgumentException if the submitter is not a usable submit control associated with this form
     */
    public List<Connection.KeyVal> formData(Element submitter, int x, int y) {
        requireSubmitter(submitter);
        return collectFormData(submitter, x, y);
    }

    /**
     Check that {@code submitter} is a {@code button} or an {@code input[type=submit|image]} associated with this
     form.
     */
    private void requireSubmitter(Element submitter) {
        Validate.notNull(submitter, "The submitter must be a button or an input of type submit or image, associated with this form.");
        String type = submitter.attr("type");
        boolean usable = submitter.nameIs("button") ||
            submitter.nameIs("input") && (type.equalsIgnoreCase("submit") || type.equalsIgnoreCase("image"));
        Validate.isTrue(usable, "The submitter must be a button or an input of type submit or image.");
        Validate.isTrue(elements().contains(submitter), "The submitter must be associated with this form.");
    }

    private List<Connection.KeyVal> collectFormData(@Nullable Element submitter, int x, int y) {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the form control elements and accumulate their values
        for (Element el: elements()) {
            if (el == submitter) {
                // the submitter contributes at its own position in document order, per the submit control rules
                if (!el.hasAttr("disabled") && !inDisabledFieldset(el))
                    addSubmitterData(data, el, x, y);
                continue;
            }
            if (!el.tag().isFormSubmittable()) continue; // contents are form listable, superset of submitable
            if (el.nameIs("button")) continue; // <button> elements are never serialized by jsoup
            if (el.hasAttr("disabled")) continue; // skip disabled form inputs
            if (inDisabledFieldset(el)) continue; // skip controls in a disabled fieldset (first legend excepted)
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these
            if (submitter != null && type.equalsIgnoreCase("submit")) continue; // only the submitter's submit control counts

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
                        // single select: only the first enabled selected option counts; if none are enabled, submit nothing
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
     Add the submitter's own contribution to the form data: a named {@code button} or {@code input[type=submit]}
     contributes {@code name=value}; an {@code input[type=image]} contributes {@code name.x} / {@code name.y} with the
     click coordinates, or {@code x} / {@code y} when it has no name.
     */
    private static void addSubmitterData(ArrayList<Connection.KeyVal> data, Element submitter, int x, int y) {
        String name = submitter.attr("name");
        if (submitter.nameIs("input") && submitter.attr("type").equalsIgnoreCase("image")) {
            String prefix = name.isEmpty() ? "" : name + ".";
            data.add(HttpConnection.KeyVal.create(prefix + "x", String.valueOf(x)));
            data.add(HttpConnection.KeyVal.create(prefix + "y", String.valueOf(y)));
        } else if (!name.isEmpty()) { // a button or submit input only contributes when named
            data.add(HttpConnection.KeyVal.create(name, submitter.val()));
        }
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
