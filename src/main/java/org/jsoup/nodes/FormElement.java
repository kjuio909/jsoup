package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.Validate;
import org.jsoup.internal.SharedConstants;
import org.jsoup.internal.StringUtil;
import org.jsoup.parser.Tag;
import org.jsoup.select.Collector;
import org.jsoup.select.Elements;
import org.jsoup.select.Evaluator;
import org.jsoup.select.Selector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An HTML Form Element provides ready access to the form fields/controls that are associated with it. It also allows a
 * form to easily be submitted.
 */
public class FormElement extends Element {
    private final Elements linkedEls = new Elements();
    // contains form-listed elements that were linked during the parse (and due to parse rules, e.g. foster parenting in
    // tables, may no longer be a descendant of this form)
    private static final String[] FormListedTags = {
        "button", "fieldset", "input", "keygen", "object", "output", "select", "textarea"
    };
    private static final Evaluator listed = Selector.evaluatorOf(StringUtil.join(FormListedTags, ", "));

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
     Get the list of form control elements associated with this form.
     <p>Association follows the current state of the DOM, not just the state captured during the parse:</p>
     <ol>
     <li>A control with a {@code form} attribute is associated only with the first {@code <form>} in the same document
     whose {@code id} matches. An attribute that is empty, or that points to an unknown id, associates the control with
     no form &mdash; it does not fall back to an ancestor form.</li>
     <li>Otherwise, a control is associated with its nearest ancestor {@code <form>}.</li>
     <li>Controls that the parser associated with this form but which the parser placed outside of any form subtree
     (such as controls fostered out of a form in a table) remain associated while they are untouched.</li>
     </ol>
     <p>Changes made after parsing &mdash; moving controls, adding or removing a {@code form} attribute, or changing a
     form's {@code id} &mdash; are reflected on the next call. Moving a control drops its parser-time association.</p>
     @return form controls associated with this element, in document order
     */
    public Elements elements() {
        Elements members = new Elements();
        Document doc = ownerDocument();

        // all candidate controls: the whole document when attached (to catch form=attr controls outside the subtree),
        // otherwise just this form and its descendants
        Elements candidates = doc != null ? Collector.collect(listed, doc) : select(listed);

        @Nullable Elements docForms = doc != null ? doc.getElementsByTag("form") : null;
        for (Element el : candidates) {
            if (isAssociated(el, doc, docForms))
                members.add(el);
        }
        // retain parser-linked controls (e.g. fostered out of tables, or added to a detached form) not in candidate scope
        for (Element linkedEl : linkedEls) {
            if (!members.contains(linkedEl) && (doc == null || linkedEl.ownerDocument() == doc)
                && isAssociated(linkedEl, doc, docForms)) {
                members.add(linkedEl);
            }
        }
        members.sort(FormElement::documentOrder);
        return members;
    }

    /** Determine if the control's current form owner is this form, per the association rules in {@link #elements()}. */
    private boolean isAssociated(Element el, @Nullable Document doc, @Nullable Elements docForms) {
        if (el.hasAttr("form")) {
            String id = el.attr("form");
            if (id.length() == 0 || doc == null || docForms == null) return false; // explicit but unresolvable: no owner, no fallback
            for (Element form : docForms) {
                if (form instanceof FormElement && id.equals(form.id()))
                    return form == this; // first form with a matching id wins; duplicate ids are ignored
            }
            return false;
        }

        for (Element parent = el.parent(); parent != null; parent = parent.parent()) {
            if (parent instanceof FormElement)
                return parent == this; // nearest ancestor form
        }

        // no form attribute and no ancestor form: a parser-linked orphan still counts, but only while it has not been
        // moved away from the parent it had when the parser placed it (e.g. foster-parented out of a table)
        if (!linkedEls.contains(el)) return false;
        Object orphanParent = el.attributes().userData(SharedConstants.FormOrphanParentKey);
        if (orphanParent == null) return false;
        return orphanParent == ORPHAN_DETACHED ? el.parent() == null : orphanParent == el.parent();
    }

    // sentinel for a control explicitly linked while detached, before it has a parent
    private static final Object ORPHAN_DETACHED = new Object();

    /**
     * Add a form control element to this form.
     * @param element form control to add
     * @return this form element, for chaining
     */
    public FormElement addElement(Element element) {
        linkElement(element);
        markOrphanControl(element);
        return this;
    }

    /** Link a control to this form, without recording its placement (the parser marks placement after insertion). */
    void linkElement(Element element) {
        linkedEls.add(element);
    }

    static boolean isDescendant(Node node, Element ancestor) {
        for (Node parent = node.parent(); parent != null; parent = parent.parent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    /**
     Record the current parent of a linked control that is outside this form's subtree (or detached), so the orphan
     fallback holds only while it stays in that placement. Controls inside the form's subtree are governed by the
     ancestor rule and need no marker.
     */
    void markOrphanControl(Element element) {
        if (isDescendant(element, this))
            element.attributes().userData(SharedConstants.FormOrphanParentKey, null); // clear any stale marker
        else
            element.attributes().userData(SharedConstants.FormOrphanParentKey,
                element.parent() != null ? element.parent() : ORPHAN_DETACHED);
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
     Get the data that this form submits. The returned list is a copy of the data, and changes to the contents of the
     list will not be reflected in the DOM.
     <p>Only successful controls are included, matching browser form submission rules: controls without a
     {@code name}, disabled controls, and controls disabled through a {@code <fieldset disabled>} ancestor are skipped
     (controls in the fieldset's first {@code <legend>} subtree are exempt). For {@code <select>}, disabled options and
     options in a disabled {@code <optgroup>} are skipped; a non-multiple select with no selected option contributes its
     first available option. Checkboxes and radios are only included when checked, defaulting to the value
     {@code on}; {@code <input type=button>} and {@code type=image>} are never included.</p>
     @return a list of key vals, in the order the controls appear in the document; empty if none apply
     */
    public List<Connection.KeyVal> formData() {
        ArrayList<Connection.KeyVal> data = new ArrayList<>();

        // iterate the associated form controls and accumulate their values
        for (Element el : elements()) {
            if (!el.tag().isFormSubmittable()) continue; // listed elements are a superset of submittable ones
            if (el.hasAttr("disabled")) continue; // skip disabled form inputs
            String name = el.attr("name");
            if (name.length() == 0) continue;
            String type = el.attr("type");

            if (type.equalsIgnoreCase("button") || type.equalsIgnoreCase("image")) continue; // browsers don't submit these

            if (isInDisabledFieldset(el)) continue; // first legend subtree of a disabled fieldset is exempt

            if (el.nameIs("select")) {
                addSelectData(el, name, data);
            } else if ("checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type)) {
                // only add checkbox or radio if they have the checked attribute
                if (el.hasAttr("checked")) {
                    final String val = el.hasAttr("value") ? el.attr("value") : "on";
                    data.add(HttpConnection.KeyVal.create(name, val));
                }
            } else {
                // textarea#val() returns its text; other controls use their value attribute (incl. input type=submit)
                data.add(HttpConnection.KeyVal.create(name, el.val()));
            }
        }
        return data;
    }

    private static void addSelectData(Element select, String name, ArrayList<Connection.KeyVal> data) {
        boolean multiple = select.hasAttr("multiple");
        boolean added = false;

        // selected options, in document order, skipping disabled options and options in disabled optgroups
        for (Element option : select.select("option[selected]")) {
            if (!optionIsDisabled(select, option)) {
                data.add(HttpConnection.KeyVal.create(name, optionValue(option)));
                added = true;
            }
        }

        if (!added && !multiple) {
            // a single select with no valid selection contributes the first available option, if any
            for (Element option : select.select("option")) {
                if (!optionIsDisabled(select, option)) {
                    data.add(HttpConnection.KeyVal.create(name, optionValue(option)));
                    break;
                }
            }
        }
        // a multiple select with no valid selection contributes nothing
    }

    /** An option is unavailable if it is itself disabled, or if it sits in a disabled optgroup within the select. */
    private static boolean optionIsDisabled(Element select, Element option) {
        if (option.hasAttr("disabled")) return true;
        for (Element parent = option.parent(); parent != null && parent != select; parent = parent.parent()) {
            if (parent.nameIs("optgroup") && parent.hasAttr("disabled")) return true;
        }
        return false;
    }

    /** Options without a value attribute submit their text content. */
    private static String optionValue(Element option) {
        return option.hasAttr("value") ? option.attr("value") : option.text();
    }

    /**
     A control barred by a disabled fieldset ancestor. Any disabled fieldset on the ancestor chain bars the control,
     unless the control is a descendant of that fieldset's first {@code <legend>} child.
     */
    private static boolean isInDisabledFieldset(Element control) {
        for (Element parent = control.parent(); parent != null; parent = parent.parent()) {
            if (parent.nameIs("fieldset") && parent.hasAttr("disabled")) {
                Element firstLegend = null;
                for (Element child : parent.children()) {
                    if (child.nameIs("legend")) {
                        firstLegend = child;
                        break;
                    }
                }
                boolean inFirstLegend = false;
                if (firstLegend != null) {
                    for (Element p = control.parent(); p != null && p != parent; p = p.parent()) {
                        if (p == firstLegend) {
                            inFirstLegend = true;
                            break;
                        }
                    }
                }
                if (!inFirstLegend) return true;
            }
        }
        return false;
    }

    /** Compare two nodes by their position in the document tree; nodes in different trees order by tree identity. */
    private static int documentOrder(Node a, Node b) {
        if (a == b) return 0;
        ArrayList<Node> chainA = ancestorChain(a);
        ArrayList<Node> chainB = ancestorChain(b);
        Node rootA = chainA.get(0);
        Node rootB = chainB.get(0);
        if (rootA != rootB) // detached trees: keep a deterministic, transitive order
            return Integer.compare(System.identityHashCode(rootA), System.identityHashCode(rootB));
        int shared = Math.min(chainA.size(), chainB.size());
        for (int i = 1; i < shared; i++) {
            Node na = chainA.get(i);
            Node nb = chainB.get(i);
            if (na != nb) {
                // chains diverge at this level; compare sibling position under the common parent
                return Integer.compare(na.siblingIndex(), nb.siblingIndex());
            }
        }
        // one node is an ancestor of the other; the ancestor (shorter chain) comes first
        return Integer.compare(chainA.size(), chainB.size());
    }

    private static ArrayList<Node> ancestorChain(Node node) {
        ArrayList<Node> chain = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent())
            chain.add(n);
        Collections.reverse(chain);
        return chain;
    }

    @Override
    public FormElement clone() {
        return (FormElement) super.clone();
    }
}
