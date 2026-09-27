package org.jsoup.select;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Internal support for the {@code :scope} pseudo-class.
 * <p>{@code :scope} binds to the {@code root} element passed into an Evaluator's {@link Evaluator#matches matches()},
 * which is the element on which {@link Element#select(String)} was invoked. It holds no element itself, so a compiled
 * Evaluator can be reused against different roots, and a {@code :scope} nested in a {@code :has()} binds to that
 * {@code :has()} candidate, because {@link StructuralEvaluator.Has} supplies the candidate as the matching root for its
 * inner branches.</p>
 * <p>This class centralizes the two pieces of bookkeeping the engine needs:</p>
 * <ul>
 *   <li>{@link #validate} enforces that at most one {@code :scope} appears per scope level. The top-level selector is
 *   one level; each {@code :has()} argument establishes a new level (its anchor is the owning candidate). {@code :is()},
 *   {@code :not()} and the {@code of S} clause of {@code :nth-child()} establish no level and bind to the enclosing
 *   root;</li>
 *   <li>{@link #siblingBranches} isolates the top-level branches that lead with a scope-headed {@code +} or {@code ~}
 *   edge, so the {@link Collector} can additionally test just those branches against the anchor's following element
 *   siblings (required by {@code :scope + ...} and {@code :scope ~ ...}); the structural evaluators' own root guards
 *   ensure nothing above the anchor can ever match.</li>
 * </ul>
 */
final class ScopeSelector {
    private ScopeSelector() {}

    /**
     * Validate that {@code :scope} occurs at most once per scope level: once in the top-level selector list, and once in
     * each nested {@code :has()} argument. {@code :is()}, {@code :not()} and the {@code of S} clause of
     * {@code :nth-child()} are transparent (they bind to the enclosing root). A {@code :scope} after a combinator (e.g.
     * {@code p + :scope}) is syntactically valid but can match nothing, as in a browser scoped query: the only candidate
     * equal to the root is the root itself, and structural edges never walk to or above it.
     * @param group the fully parsed evaluator
     * @param query the original selector text, for error messages
     * @throws Selector.SelectorParseException if a level contains more than one {@code :scope}
     */
    static void validate(Evaluator group, String query) {
        if (countAtLevel(group, query) > 1)
            throw new Selector.SelectorParseException(
                "Could not parse query '%s': the :scope pseudo-class may appear only once per selector level", query);
    }

    /**
     * Count {@code :scope} tokens bound to the enclosing level's root, and recursively validate each {@code :has()}
     * subtree as a new level.
     */
    private static int countAtLevel(Evaluator eval, String query) {
        if (eval instanceof Evaluator.IsScope)
            return 1;
        if (eval instanceof StructuralEvaluator.Has) {
            if (countAtLevel(((StructuralEvaluator) eval).evaluator, query) > 1)
                throw new Selector.SelectorParseException(
                    "Could not parse query '%s': the :scope pseudo-class may appear only once per selector level", query);
            return 0; // its :scope tokens bind to the :has candidate, a different level
        }
        if (eval instanceof CombiningEvaluator) {
            int count = 0;
            for (Evaluator child : ((CombiningEvaluator) eval).evaluators)
                count += countAtLevel(child, query);
            return count;
        }
        if (eval instanceof StructuralEvaluator.ImmediateParentRun) {
            int count = 0;
            for (Evaluator child : ((StructuralEvaluator.ImmediateParentRun) eval).evaluators)
                count += countAtLevel(child, query);
            return count;
        }
        if (eval instanceof StructuralEvaluator) { // Ancestor, sibling edges, Is, Not: same level
            return countAtLevel(((StructuralEvaluator) eval).evaluator, query);
        }
        if (eval instanceof Evaluator.CssNthEvaluator) {
            Evaluator filter = ((Evaluator.CssNthEvaluator) eval).filter;
            return filter == null ? 0 : countAtLevel(filter, query);
        }
        return 0;
    }

    /**
     * Whether the given compound selector is anchored by {@code :scope}: a single simple sequence that contains
     * {@code :scope} alongside type/id/class/attribute/pseudo refinements (as in {@code div:scope.x} or
     * {@code *:scope}), or is wrapped in a transparent {@code :is(...)}. A structural edge (descendant/child/sibling)
     * is never nested inside a single compound, and a {@code :has()} subtree delimits a different level, so neither
     * qualifies. The synthetic {@link StructuralEvaluator.Root} produced by a leading-combinator query (e.g.
     * {@code > p}) is not {@code :scope}.
     */
    static boolean isScopeAnchor(Evaluator edge) {
        if (edge instanceof Evaluator.IsScope)
            return true;
        if (edge instanceof CombiningEvaluator.Or) { // e.g. :is(:scope, p): any scope-headed branch anchors
            for (Evaluator branch : ((CombiningEvaluator.Or) edge).evaluators)
                if (isScopeAnchor(branch)) return true;
            return false;
        }
        if (edge instanceof CombiningEvaluator.And) {
            for (Evaluator member : ((CombiningEvaluator.And) edge).evaluators) {
                if (member instanceof StructuralEvaluator.Is) {
                    if (isScopeAnchor(((StructuralEvaluator.Is) member).evaluator)) return true;
                } else if (member instanceof Evaluator.IsScope) {
                    return true;
                }
                // any other member (tag, id, class, attribute, other pseudo, :has/:not) does not anchor the compound
            }
            return false;
        }
        if (edge instanceof StructuralEvaluator.Is)
            return isScopeAnchor(((StructuralEvaluator.Is) edge).evaluator);
        return false;
    }

    /**
     * Whether the given evaluator references its matching root through {@code :scope} at its own level (a
     * {@code :scope} inside a nested {@code :has()} binds to that {@code :has()} candidate and is resolved there, so it
     * does not count).
     */
    static boolean usesScope(Evaluator eval) {
        if (eval instanceof Evaluator.IsScope)
            return true;
        if (eval instanceof StructuralEvaluator.Has)
            return false;
        if (eval instanceof CombiningEvaluator) {
            for (Evaluator child : ((CombiningEvaluator) eval).evaluators)
                if (usesScope(child)) return true;
            return false;
        }
        if (eval instanceof StructuralEvaluator.ImmediateParentRun) {
            for (Evaluator child : ((StructuralEvaluator.ImmediateParentRun) eval).evaluators)
                if (usesScope(child)) return true;
            return false;
        }
        if (eval instanceof StructuralEvaluator)
            return usesScope(((StructuralEvaluator) eval).evaluator);
        if (eval instanceof Evaluator.CssNthEvaluator) {
            Evaluator filter = ((Evaluator.CssNthEvaluator) eval).filter;
            return filter != null && usesScope(filter);
        }
        return false;
    }

    /**
     * Trace a branch's left-most combinator chain and report its leading sibling axis when that axis starts at
     * {@code :scope}. This mirrors {@link StructuralEvaluator.Has#siblingAxis branch axis detection} but recognizes a
     * {@code :scope} terminus (possibly classified or wrapped in {@code :is()}) rather than only the synthetic
     * {@link StructuralEvaluator.Root}.
     * @return {@code '+'}, {@code '~'}, or {@code 0} when the branch is not a scope-headed sibling branch
     */
    private static char scopeSiblingAxis(Evaluator branch) {
        Evaluator edge = branch;
        while (true) {
            if (edge instanceof CombiningEvaluator.And) {
                edge = ((CombiningEvaluator.And) edge).evaluators.get(0);
            } else if (edge instanceof StructuralEvaluator.ImmediateParentRun) {
                edge = ((StructuralEvaluator.ImmediateParentRun) edge).evaluators.get(0);
            } else if (edge instanceof StructuralEvaluator.Ancestor) {
                edge = ((StructuralEvaluator.Ancestor) edge).evaluator;
            } else if (edge instanceof StructuralEvaluator.ImmediatePreviousSibling) {
                Evaluator left = ((StructuralEvaluator) edge).evaluator;
                if (isScopeAnchor(left)) return '+';
                edge = left; // a chained sibling combinator; continue to the left-most edge
            } else if (edge instanceof StructuralEvaluator.PreviousSibling) {
                Evaluator left = ((StructuralEvaluator) edge).evaluator;
                if (isScopeAnchor(left)) return '~';
                edge = left;
            } else if (edge instanceof StructuralEvaluator.Is) {
                edge = ((StructuralEvaluator.Is) edge).evaluator;
            } else {
                return 0;
            }
        }
    }

    /**
     * Build an evaluator containing only the top-level branches that lead with a {@code :scope}-headed {@code +} or
     * {@code ~} sibling edge. Those are the only branches allowed to match among the root's following siblings and
     * their subtrees; every other branch stays confined to the root and its descendants, preserving the historical
     * descendants-only behavior of queries that do not use {@code :scope}. Returns {@code null} when there is no such
     * branch.
     */
    static @Nullable Evaluator siblingBranches(Evaluator eval) {
        List<Evaluator> matches = new ArrayList<>();
        if (eval instanceof CombiningEvaluator.Or) {
            for (Evaluator branch : ((CombiningEvaluator.Or) eval).evaluators) {
                if (scopeSiblingAxis(branch) != 0)
                    matches.add(branch);
            }
        } else if (scopeSiblingAxis(eval) != 0) {
            matches.add(eval);
        }
        if (matches.isEmpty())
            return null;
        if (matches.size() == 1)
            return matches.get(0);
        return new CombiningEvaluator.Or(matches);
    }

    /** How much of the root's following-sibling axis a set of scope-headed sibling branches must search. */
    private enum Breadth {
        /** only the root's immediate following sibling. */
        Immediate,
        /** every following sibling, but not their descendants. */
        Siblings,
        /** every following sibling and the full subtree beneath each. */
        Subtrees
    }

    /**
     * The narrowest candidate breadth covering all scope-headed sibling branches: a non-deep {@code +} branch needs
     * only the immediate sibling, a non-deep {@code ~} branch the following siblings themselves, and a branch that
     * chains a further combinator must search sibling subtrees.
     */
    private static Breadth siblingBreadth(Evaluator siblingEval) {
        List<Evaluator> branches = new ArrayList<>();
        if (siblingEval instanceof CombiningEvaluator.Or)
            branches.addAll(((CombiningEvaluator.Or) siblingEval).evaluators);
        else
            branches.add(siblingEval);

        Breadth breadth = Breadth.Immediate;
        for (Evaluator branch : branches) {
            Breadth needed = deepSiblingBranch(branch) ? Breadth.Subtrees
                : scopeSiblingAxis(branch) == '+' ? Breadth.Immediate : Breadth.Siblings;
            if (needed.ordinal() > breadth.ordinal())
                breadth = needed;
        }
        return breadth;
    }

    /**
     * Whether a scope-headed sibling branch chains another combinator after its leading edge (e.g.
     * {@code :scope + article > img}), so its target may lie within a sibling's subtree. Mirrors
     * {@link StructuralEvaluator.Has}'s chain detection, treating {@code :scope} as the anchor terminus.
     */
    private static boolean deepSiblingBranch(Evaluator eval) {
        if (eval instanceof StructuralEvaluator.Ancestor)
            return true;
        if (eval instanceof StructuralEvaluator.ImmediateParentRun)
            return ((StructuralEvaluator.ImmediateParentRun) eval).evaluators.size() > 1;
        if (eval instanceof StructuralEvaluator.ImmediatePreviousSibling
            || eval instanceof StructuralEvaluator.PreviousSibling) {
            Evaluator left = ((StructuralEvaluator) eval).evaluator;
            return !(left instanceof StructuralEvaluator.Root || isScopeAnchor(left));
        }
        if (eval instanceof CombiningEvaluator) {
            for (Evaluator child : ((CombiningEvaluator) eval).evaluators)
                if (deepSiblingBranch(child)) return true;
        }
        return false; // a plain compound, or a self-contained evaluator (Is, Not, nested Has)
    }

    /**
     * The root's following element siblings required by the scope-headed sibling branches, in document order and
     * including each sibling's own subtree only when necessary. Non-element siblings are skipped: element
     * relationships never count them. The tree is only read.
     */
    static Stream<Element> siblingElements(Evaluator siblingEval, Element root) {
        Breadth breadth = siblingBreadth(siblingEval);
        if (breadth == Breadth.Immediate) {
            Element sibling = root.nextElementSibling();
            return sibling == null ? Stream.empty() : Stream.of(sibling);
        }
        Stream.Builder<Stream<Element>> streams = Stream.builder();
        for (Element sibling = root.nextElementSibling(); sibling != null; sibling = sibling.nextElementSibling())
            streams.add(breadth == Breadth.Subtrees ? sibling.stream() : Stream.of(sibling));
        return streams.build().flatMap(stream -> stream);
    }

    /**
     * Node-typed variant of {@link #siblingElements}. A branch that uses only element selectors keeps element
     * relationship semantics (non-element siblings are skipped, as in {@code :scope + p}); a branch that requests nodes
     * (e.g. {@code :scope + ::comment}) walks raw node siblings, and an element sibling in a subtree search carries its
     * descendant nodes.
     */
    static <T extends Node> Stream<T> siblingNodes(Evaluator siblingEval, Element root, Class<T> type) {
        if (!siblingEval.wantsNodes())
            return siblingElements(siblingEval, root).filter(type::isInstance).map(type::cast);

        Breadth breadth = siblingBreadth(siblingEval);
        Stream.Builder<Node> siblings = Stream.builder();
        if (breadth == Breadth.Immediate) {
            Node next = root.nextSibling();
            if (next != null) siblings.add(next);
        } else {
            for (Node sibling = root.nextSibling(); sibling != null; sibling = sibling.nextSibling())
                siblings.add(sibling);
        }
        return siblings.build().flatMap(node -> {
            Stream<? extends Node> nodes = breadth == Breadth.Subtrees && node instanceof Element
                ? ((Element) node).nodeStream(Node.class)
                : Stream.of(node);
            return nodes.filter(type::isInstance).map(type::cast);
        });
    }
}
