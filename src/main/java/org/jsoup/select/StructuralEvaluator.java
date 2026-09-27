package org.jsoup.select;

import org.jsoup.internal.SoftPool;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.LeafNode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.NodeIterator;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.WeakHashMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/**
 * Base structural evaluator.
 */
abstract class StructuralEvaluator extends Evaluator {
    final Evaluator evaluator;
    boolean wantsNodes; // if the evaluator requested nodes, not just elements

    public StructuralEvaluator(Evaluator evaluator) {
        this.evaluator = evaluator;
        wantsNodes = evaluator.wantsNodes();
    }

    @Override
    boolean wantsNodes() {
        return wantsNodes;
    }

    // Memoize inner matches, to save repeated re-evaluations of parent, sibling etc.
    // root + element: Boolean matches. ThreadLocal in case the Evaluator is compiled then reused across multi threads
    final ThreadLocal<Map<Node, Map<Node, Boolean>>> threadMemo = ThreadLocal.withInitial(WeakHashMap::new);

    boolean memoMatches(final Element root, final Node node) {
        Map<Node, Map<Node, Boolean>> rootMemo = threadMemo.get();
        Map<Node, Boolean> memo = rootMemo.computeIfAbsent(root, r -> new WeakHashMap<>());
        return memo.computeIfAbsent(node, test -> evaluator.matches(root, test));
    }

    @Override protected void reset() {
        threadMemo.remove();
        evaluator.reset();
        super.reset();
    }

    @Override
    public boolean matches(Element root, Element element) {
        return evaluateMatch(root, element);
    }

    @Override
    boolean matches(Element root, LeafNode leafNode) {
        return evaluateMatch(root, leafNode);
    }

    abstract boolean evaluateMatch(Element root, Node node);

    static class Root extends Evaluator {
        @Override
        public boolean matches(Element root, Element element) {
            return root == element;
        }

        @Override protected int cost() {
            return 1;
        }

        @Override public String toString() {
            return ">";
        }
    }

    /**
     * Implements the {@code :has(relative-selector-list)} structural pseudo-class. The current candidate element is the
     * <b>anchor</b>; each comma-separated branch is evaluated <i>relative to that anchor</i>, and the anchor never
     * drifts to its own ancestors or siblings while a branch is tested:
     * <ul>
     *   <li>a branch with no leading combinator matches only within the anchor's descendants;</li>
     *   <li>a branch beginning with {@code >} (encoded as an {@link ImmediateParentRun} from the synthetic {@link Root})
     *       is confined to direct element children and below;</li>
     *   <li>a branch beginning with {@code +} or {@code ~} (an immediate/general sibling evaluator whose left side is
     *       the synthetic {@link Root}) is evaluated against the anchor's following element siblings and their
     *       descendants only; non-element nodes never act as siblings.</li>
     * </ul>
     * The parsed evaluator tree is left intact; the axis of each branch is derived from whether its leading combinator
     * references the synthetic {@link Root}. Branches are ORed here, but the collector de-duplicates and orders results.
     */
    static class Has extends StructuralEvaluator {
        static final SoftPool<NodeIterator<Node>> NodeIterPool =
            new SoftPool<>(() -> new NodeIterator<>(new TextNode(""), Node.class));
        // the element here is just a placeholder so this can be final - gets set in restart()

        private final Evaluator[] branches; // each comma-separated relative branch
        private final char[] axes;          // axis per branch: 0 = descendants, '+' / '~' = following element siblings
        private final boolean[] deep;       // sibling branches that chain a further combinator and so must search subtrees
        private final boolean[] self;       // branches whose :scope may match the anchor element itself

        public Has(Evaluator evaluator) {
            super(evaluator);

            ArrayList<Evaluator> clauses = new ArrayList<>();
            if (evaluator instanceof CombiningEvaluator.Or) {
                clauses.addAll(((CombiningEvaluator.Or) evaluator).evaluators);
            } else {
                clauses.add(evaluator);
            }
            branches = clauses.toArray(new Evaluator[0]);
            axes = new char[branches.length];
            deep = new boolean[branches.length];
            self = new boolean[branches.length];
            for (int i = 0; i < branches.length; i++) {
                axes[i] = siblingAxis(branches[i]);
                deep[i] = axes[i] != 0 && hasChainedCombinator(branches[i]);
                self[i] = axes[i] == 0 && referencesScope(branches[i]);
            }
        }

        @Override public boolean matches(Element root, Element element) {
            for (int i = 0; i < branches.length; i++) {
                Evaluator branch = branches[i];
                if (axes[i] == 0) {
                    if (matchesDescendants(element, branch, self[i])) return true;
                } else {
                    if (matchesFollowingSiblings(element, axes[i], deep[i], branch)) return true;
                }
            }
            return false;
        }

        /**
         * Match a descendant-only branch (bare or {@code >}) within the anchor. The anchor itself is excluded, so that
         * e.g. {@code div:has(div)} does not match merely for being a {@code div}; but a branch that explicitly names
         * {@code :scope} is tested against the anchor too, as that is the element the scope resolves to.
         */
        private static boolean matchesDescendants(Element anchor, Evaluator branch, boolean includeSelf) {
            if (includeSelf && branch.matches(anchor, anchor)) return true;
            NodeIterator<Node> it = NodeIterPool.borrow();
            it.restart(anchor);
            try {
                while (it.hasNext()) {
                    Node node = it.next();
                    if (node == anchor) continue; // don't match self, only descendants
                    if (branch.matches(anchor, node)) return true;
                }
            } finally {
                NodeIterPool.release(it);
            }
            return false;
        }

        /**
         * Match a {@code +}/{@code ~} branch against following element siblings (element nodes only). The anchor is
         * supplied as the matching root, so the branch's leading sibling edge (whose left side is the synthetic Root)
         * resolves to the anchor: {@code +} is satisfied only by the immediate sibling, {@code ~} by any later one.
         * A simple branch ({@code + p.selected}) tests the sibling elements directly, with {@code +} stopping after the
         * first. A branch that chains further combinators ({@code + article > img}, {@code + p + img}, {@code ~ p span})
         * may match within a sibling's subtree, or -- with chained sibling combinators -- in a later sibling, so every
         * following sibling and its subtree is searched; the root-scoped structural evaluators enforce the exact
         * adjacency and never walk above the anchor, so the broader search domain cannot mis-match.
         */
        private static boolean matchesFollowingSiblings(Element anchor, char axis, boolean deep, Evaluator branch) {
            Element sibling = anchor.nextElementSibling();
            if (!deep) {
                while (sibling != null) {
                    if (branch.matches(anchor, sibling)) return true;
                    if (axis == '+') break; // '+' is the immediate sibling only; '~' may reach a later one
                    sibling = sibling.nextElementSibling();
                }
                return false;
            }

            NodeIterator<Node> it = NodeIterPool.borrow();
            try {
                while (sibling != null) {
                    it.restart(sibling);
                    while (it.hasNext()) {
                        Node node = it.next();
                        if (branch.matches(anchor, node)) return true;
                    }
                    sibling = sibling.nextElementSibling();
                }
            } finally {
                NodeIterPool.release(it);
            }
            return false;
        }

        @Override
        boolean evaluateMatch(Element root, Node node) {
            return false; // unused; :has(::comment)) goes via implicit root combinator
        }

        /**
         * Determine a branch's leading sibling axis, if any, by tracing its left-most combinator edge. Only a leading
         * {@code +} or {@code ~} is compiled against the synthetic {@link Root} (the anchor): it appears as an
         * immediate/general sibling evaluator wrapping {@link Root} at the start of the chain (possibly beneath
         * descendant and child edges). An internal sibling combinator references a real selector, not {@link Root},
         * and so is scoped to the anchor's descendants rather than the anchor's own siblings.
         * @return {@code '+'}, {@code '~'}, or {@code 0} for a descendant-only branch
         */
        private static char siblingAxis(Evaluator branch) {
            return leadingSiblingAxis(branch);
        }

        /**
         * Whether a sibling branch chains a further combinator after its leading {@code +}/{@code ~}, such that the
         * match target may lie within a sibling's subtree (e.g. {@code + article > img}) rather than being the sibling
         * element itself (e.g. {@code + p.selected}). The leading sibling-of-{@link Root} edge is the axis edge and is
         * not counted; {@link Is}, {@link Not} and nested {@link Has} evaluate their own contents and are self-contained.
         */
        private static boolean hasChainedCombinator(Evaluator eval) {
            return chainsCombinator(eval);
        }

        @Override protected int cost() {
            return 10 * evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format(":has(%s)", evaluator);
        }
    }

    /**
     * Implements the {@code :scope} pseudo-class at the top level of a query: matches exactly the element on which
     * {@link Element#select(String) select()} was invoked. Traversal of the whole tree already supplies that element
     * first, so this only needs an identity check; it never matches the anchor's ancestors or a re-anchored candidate.
     */
    static final class ScopeRef extends Evaluator {
        @Override
        public boolean matches(Element root, Element element) {
            return root == element;
        }

        @Override protected int cost() {
            return 1;
        }

        @Override public String toString() {
            return ":scope";
        }
    }

    /**
     * Trace a relative selector's left-most combinator edge to find whether it leads with a sibling combinator
     * compiled against the synthetic {@link Root} (the anchor). A leading {@code +} or {@code ~} appears as an
     * immediate/general sibling evaluator wrapping {@link Root} at the start of the chain (possibly beneath descendant
     * and child edges); an internal sibling combinator references a real selector, not {@link Root}, and so is scoped
     * to the anchor's descendants rather than the anchor's own siblings.
     * @return {@code '+'}, {@code '~'}, or {@code 0} for a descendant-only branch
     */
    static char leadingSiblingAxis(Evaluator edge) {
        while (true) {
            if (edge instanceof CombiningEvaluator.And) {
                // the parser places the left-hand structural wrapper (Ancestor/sibling) first in the And
                edge = ((CombiningEvaluator.And) edge).evaluators.get(0);
            } else if (edge instanceof ImmediateParentRun) {
                edge = ((ImmediateParentRun) edge).evaluators.get(0);
            } else if (edge instanceof Is) {
                // :is() is transparent: look inside it (a :is(...) list only widens the domain if a branch leads there)
                Evaluator inner = ((Is) edge).evaluator;
                if (inner instanceof CombiningEvaluator.Or) {
                    char axis = 0;
                    for (Evaluator clause : ((CombiningEvaluator.Or) inner).evaluators) {
                        char clauseAxis = leadingSiblingAxis(clause);
                        if (clauseAxis != 0) { axis = clauseAxis; break; }
                    }
                    return axis;
                }
                edge = inner;
            } else if (edge instanceof Ancestor) {
                edge = ((Ancestor) edge).evaluator;
            } else if (edge instanceof ImmediatePreviousSibling) {
                Evaluator left = ((StructuralEvaluator) edge).evaluator;
                if (left instanceof Root || left instanceof ScopeRef) return '+';
                edge = left; // a chained sibling combinator; continue to the left-most edge
            } else if (edge instanceof PreviousSibling) {
                Evaluator left = ((StructuralEvaluator) edge).evaluator;
                if (left instanceof Root || left instanceof ScopeRef) return '~';
                edge = left;
            } else {
                return 0; // bare branch, or a leading '>' (a Root)
            }
        }
    }

    /**
     * Whether a sibling branch chains a further combinator after its leading {@code +}/{@code ~}, such that the match
     * target may lie within a sibling's subtree (e.g. {@code + article > img}) rather than being the sibling element
     * itself (e.g. {@code + p.selected}). The leading sibling-of-{@link Root} edge is the axis edge and is not counted;
     * {@link Is} is transparent (its contents are inspected), while {@link Not} and a nested {@link Has} evaluate their
     * own contents and are self-contained.
     */
    static boolean chainsCombinator(Evaluator eval) {
        if (eval instanceof Ancestor) return true;
        if (eval instanceof ImmediateParentRun) return ((ImmediateParentRun) eval).evaluators.size() > 1;
        if (eval instanceof ImmediatePreviousSibling || eval instanceof PreviousSibling) {
            Evaluator left = ((StructuralEvaluator) eval).evaluator;
            return !(left instanceof Root || left instanceof ScopeRef); // the leading axis edge wraps the anchor
        }
        if (eval instanceof Is) // :is() is transparent: look inside for a chained combinator
            return chainsCombinator(((Is) eval).evaluator);
        if (eval instanceof CombiningEvaluator) {
            for (Evaluator child : ((CombiningEvaluator) eval).evaluators) {
                if (chainsCombinator(child)) return true;
            }
        }
        return false; // a plain simple sequence, or a self-contained evaluator (Not, nested Has, etc.)
    }

    /**
     * Whether the evaluator references {@code :scope} at its <i>current</i> anchor layer. Combining evaluators and
     * transparent structural wrappers ({@link Is}, {@link Not}, {@link Ancestor}, sibling edges,
     * {@link ImmediateParentRun}) are traversed, but a {@link Has} is a layer boundary: a {@code :scope} inside a
     * nested {@code :has()} anchors to that layer's candidate, not the enclosing one, so it is not counted here.
     */
    static boolean referencesScope(Evaluator eval) {
        if (eval instanceof ScopeRef) return true;
        if (eval instanceof CombiningEvaluator) {
            for (Evaluator child : ((CombiningEvaluator) eval).evaluators) {
                if (referencesScope(child)) return true;
            }
        } else if (eval instanceof Has) {
            return false; // nested :has() establishes its own anchor layer
        } else if (eval instanceof ImmediateParentRun) {
            for (Evaluator child : ((ImmediateParentRun) eval).evaluators) {
                if (referencesScope(child)) return true;
            }
        } else if (eval instanceof StructuralEvaluator) {
            return referencesScope(((StructuralEvaluator) eval).evaluator); // Is / Not / Ancestor / sibling edges
        } else if (eval instanceof Evaluator.CssNthEvaluator) {
            Evaluator filter = ((Evaluator.CssNthEvaluator) eval).filter;
            return filter != null && referencesScope(filter);
        }
        return false;
    }

    /**
     * Validate that no selector references {@code :scope} more than once. Each comma-separated selector (including the
     * selector lists inside {@code :is()}, {@code :not()} and {@code :has()}) is counted independently; compounds that
     * join the same selector (and transparent wrappers such as {@code :is()}) add their counts together. A query like
     * {@code :scope > :scope} or {@code :scope:is(:scope)} therefore fails to parse, while {@code :scope, :scope + p}
     * (one per branch) is accepted.
     * @throws Selector.SelectorParseException if a single selector contains more than one {@code :scope}
     */
    static void validateScopeUsage(Evaluator eval) {
        for (int count : scopeCounts(eval)) {
            if (count > 1)
                throw new Selector.SelectorParseException(":scope may only appear once per selector");
        }
    }

    /**
     * Number of {@code :scope} references on each selector path represented by this evaluator, at the current anchor
     * layer. Comma-separated lists (Or) branch into separate paths; compounds (And, {@link ImmediateParentRun}) join
     * paths and so add their counts. A {@link Has} starts a new anchor layer: its inner paths are validated on their own
     * and contribute nothing to the enclosing path.
     */
    private static List<Integer> scopeCounts(Evaluator eval) {
        List<Integer> counts = new ArrayList<>();
        if (eval instanceof ScopeRef) {
            counts.add(1);
        } else if (eval instanceof CombiningEvaluator.Or) {
            for (Evaluator child : ((CombiningEvaluator.Or) eval).evaluators)
                counts.addAll(scopeCounts(child)); // each comma branch is its own selector
        } else if (eval instanceof CombiningEvaluator.And) {
            counts.add(0);
            for (Evaluator child : ((CombiningEvaluator.And) eval).evaluators)
                counts = joinCounts(counts, scopeCounts(child));
        } else if (eval instanceof ImmediateParentRun) {
            counts.add(0);
            for (Evaluator child : ((ImmediateParentRun) eval).evaluators)
                counts = joinCounts(counts, scopeCounts(child));
        } else if (eval instanceof Has) {
            validateScopeUsage(((Has) eval).evaluator); // the nested :has() is its own anchor layer
            counts.add(0);
        } else if (eval instanceof Evaluator.CssNthEvaluator) {
            Evaluator filter = ((Evaluator.CssNthEvaluator) eval).filter;
            if (filter != null) counts = joinCounts(counts, scopeCounts(filter)); // 'of S' compounds with this selector
            else counts.add(0);
        } else if (eval instanceof StructuralEvaluator) {
            counts.addAll(scopeCounts(((StructuralEvaluator) eval).evaluator)); // Is / Not / Ancestor / sibling
        } else {
            counts.add(0);
        }
        return counts;
    }

    /** Cartesian-combine the accumulated per-path counts with those of an evaluator joined into the same selector. */
    private static List<Integer> joinCounts(List<Integer> left, List<Integer> right) {
        List<Integer> joined = new ArrayList<>(left.size() * Math.max(1, right.size()));
        for (int l : left)
            for (int r : right)
                joined.add(l + r);
        return joined;
    }

    /**
     * Root of a query whose selector list references {@code :scope}. It evaluates the parsed selector exactly as an
     * ordinary query would, but widens the search domain beyond the anchor's subtree when needed: each comma-separated
     * branch is classified by its leading combinator, and candidate nodes are drawn from the union of
     * <ul>
     *   <li>the anchor element itself and its descendants (always), and</li>
     *   <li>the anchor's following element siblings and their subtrees, but only when some branch leads with
     *       {@code +} or {@code ~}.</li>
     * </ul>
     * The parsed structural evaluators enforce the precise relationship from the anchor (self, descendant, child,
     * adjacent or general sibling) and never walk above it, so the broadened candidate domain cannot mis-match nodes
     * outside the intended scope. Branches without a leading sibling axis never match a sibling, and a sibling branch
     * never matches the subtree. Candidates are visited in document order and de-duplicated by the collector; text and
     * comment nodes are never offered as siblings. A detached anchor (no parent) simply has no siblings.
     */
    static final class ScopedRoot extends Evaluator {
        private final Evaluator evaluator;
        private final boolean searchSiblings; // some comma branch leads with a '+' / '~' combinator

        ScopedRoot(Evaluator evaluator) {
            this.evaluator = evaluator;
            ArrayList<Evaluator> clauses = new ArrayList<>();
            if (evaluator instanceof CombiningEvaluator.Or) {
                clauses.addAll(((CombiningEvaluator.Or) evaluator).evaluators);
            } else {
                clauses.add(evaluator);
            }
            boolean siblings = false;
            for (Evaluator clause : clauses) {
                if (leadingSiblingAxis(clause) != 0) {
                    siblings = true;
                    break;
                }
            }
            searchSiblings = siblings;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return evaluator.matches(root, element);
        }

        @Override
        boolean matches(Element root, LeafNode leafNode) {
            return evaluator.matches(root, leafNode);
        }

        @Override
        boolean wantsNodes() {
            return evaluator.wantsNodes();
        }

        @Override
        protected void reset() {
            evaluator.reset();
            super.reset();
        }

        @Override
        protected int cost() {
            return evaluator.cost();
        }

        /**
         * Stream the candidate domain for this scoped query, in document order: the anchor and its subtree, followed by
         * each following element sibling and its subtree when any branch leads with a sibling combinator. The anchor's
         * preceding siblings, ancestors, and unrelated parts of the owning document are never visited.
         */
        Stream<Node> scopedStream(Element anchor) {
            Stream<Node> domain = anchor.nodeStream();
            if (searchSiblings) {
                Iterator<Element> siblings = followingElementSiblings(anchor);
                Stream<Element> siblingStream = StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(siblings, Spliterator.ORDERED | Spliterator.NONNULL), false);
                domain = Stream.concat(domain, siblingStream.flatMap(Element::nodeStream));
            }
            return domain;
        }

        /** Iterate the anchor's following siblings that are elements, skipping text and comment nodes. */
        private static Iterator<Element> followingElementSiblings(Element anchor) {
            return new Iterator<Element>() {
                private @Nullable Element next = anchor.nextElementSibling();

                @Override public boolean hasNext() {
                    return next != null;
                }

                @Override public Element next() {
                    Element current = next;
                    if (current == null) throw new NoSuchElementException();
                    next = current.nextElementSibling();
                    return current;
                }
            };
        }

        @Override
        public String toString() {
            return evaluator.toString();
        }
    }

    /** Implements the :is(sub-query) pseudo-selector */
    static class Is extends StructuralEvaluator {
        public Is(Evaluator evaluator) {
            super(evaluator);
        }

        @Override
        boolean evaluateMatch(Element root, Node node) {
            return evaluator.matches(root, node);
        }

        @Override protected int cost() {
            return 2 + evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format(":is(%s)", evaluator);
        }
    }

    static class Not extends StructuralEvaluator {
        public Not(Evaluator evaluator) {
            super(evaluator);
        }

        @Override
        boolean evaluateMatch(Element root, Node node) {
            return !memoMatches(root, node);
        }

        @Override protected int cost() {
            return 2 + evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format(":not(%s)", evaluator);
        }
    }

    /**
     Any Ancestor (i.e., ascending parent chain.).
     */
    static class Ancestor extends StructuralEvaluator {
        public Ancestor(Evaluator evaluator) {
            super(evaluator);
        }

        @Override
        boolean evaluateMatch(Element root, Node node) {
            if (root == node)
                return false;

            for (Node parent = node.parent(); parent != null; parent = parent.parent()) {
                if (memoMatches(root, parent))
                    return true;
                if (parent == root)
                    break;
            }
            return false;
        }

        @Override
        protected int cost() {
            return 8 * evaluator.cost(); // probably lower than has(), but still significant, depending on doc and el depth.
        }

        @Override
        public String toString() {
            return String.format("%s ", evaluator);
        }
    }

    /**
     Holds a list of evaluators for one > two > three immediate parent matches, and the final direct evaluator under
     test. To match, these are effectively ANDed together, starting from the last, matching up to the first.
     */
    static class ImmediateParentRun extends StructuralEvaluator {
        final ArrayList<Evaluator> evaluators = new ArrayList<>();
        int cost = 2;

        public ImmediateParentRun(Evaluator evaluator) {
            super(evaluator);
            evaluators.add(evaluator);
            cost += evaluator.cost();
        }

        void add(Evaluator evaluator) {
            evaluators.add(evaluator);
            cost += evaluator.cost();
            wantsNodes |= evaluator.wantsNodes();
        }

        @Override boolean evaluateMatch(Element root, Node node) {
            if (node == root)
                return false; // cannot match as the second eval (first parent test) would be above the root

            for (int i = evaluators.size() -1; i >= 0; --i) {
                if (node == null)
                    return false;
                Evaluator eval = evaluators.get(i);
                if (!eval.matches(root, node))
                    return false;
                node = node.parent();
            }
            return true;
        }

        @Override protected int cost() {
            return cost;
        }

        @Override
        protected void reset() {
            for (Evaluator evaluator : evaluators) {
                evaluator.reset();
            }
            super.reset();
        }

        @Override
        public String toString() {
            return StringUtil.join(evaluators, " > ");
        }
    }

    static class PreviousSibling extends StructuralEvaluator {
        public PreviousSibling(Evaluator evaluator) {
            super(evaluator);
        }

        // matches any previous sibling, so can be same in Element only or wantsNodes context
        @Override boolean evaluateMatch(Element root, Node node) {
            if (root == node) return false;

            for (Node sib = node.firstSibling(); sib != null; sib = sib.nextSibling()) {
                if (sib == node) break;
                if (memoMatches(root, sib)) return true;
            }

            return false;
        }

        @Override protected int cost() {
            return 3 * evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format("%s ~ ", evaluator);
        }
    }

    static class ImmediatePreviousSibling extends StructuralEvaluator {
        public ImmediatePreviousSibling(Evaluator evaluator) {
            super(evaluator);
        }

        @Override boolean evaluateMatch(Element root, Node node) {
            if (root == node) return false;

            Node prev = wantsNodes ? node.previousSibling() : node.previousElementSibling();
            return prev != null && memoMatches(root, prev);
        }

        @Override protected int cost() {
            return 2 + evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format("%s + ", evaluator);
        }
    }
}
