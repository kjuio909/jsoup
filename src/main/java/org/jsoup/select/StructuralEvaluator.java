package org.jsoup.select;

import org.jsoup.internal.SoftPool;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.LeafNode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.NodeIterator;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

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
        private final boolean[] selfScope;  // descendant-axis branches whose :scope may match the anchor itself

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
            selfScope = new boolean[branches.length];
            for (int i = 0; i < branches.length; i++) {
                axes[i] = siblingAxis(branches[i]);
                deep[i] = axes[i] != 0 && hasChainedCombinator(branches[i]);
                // a sibling branch can never match the anchor itself; a descendant branch may when its relationship
                // starts (or stays) at the :scope anchor, as in :has(:scope), :has(:scope.x), or :has(p:scope)
                selfScope[i] = axes[i] == 0 && ScopeSelector.usesScope(branches[i]);
            }
        }

        @Override public boolean matches(Element root, Element element) {
            for (int i = 0; i < branches.length; i++) {
                Evaluator branch = branches[i];
                if (axes[i] == 0) {
                    // a :scope at this level binds to the anchor itself, so a scope-headed branch with no leading
                    // combinator may match the anchor (e.g. :has(:scope.x)); every other branch stays descendant-only
                    if (selfScope[i] && branch.matches(element, element)) return true;
                    if (matchesDescendants(element, branch)) return true;
                } else {
                    if (matchesFollowingSiblings(element, axes[i], deep[i], branch)) return true;
                }
            }
            return false;
        }

        /** Match a descendant-only branch (bare or {@code >}) within the anchor, never against the anchor itself. */
        private static boolean matchesDescendants(Element anchor, Evaluator branch) {
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
            Evaluator edge = branch;
            while (true) {
                if (edge instanceof CombiningEvaluator.And) {
                    // the parser places the left-hand structural wrapper (Ancestor/sibling) first in the And
                    edge = ((CombiningEvaluator.And) edge).evaluators.get(0);
                } else if (edge instanceof ImmediateParentRun) {
                    edge = ((ImmediateParentRun) edge).evaluators.get(0);
                } else if (edge instanceof Ancestor) {
                    edge = ((Ancestor) edge).evaluator;
                } else if (edge instanceof ImmediatePreviousSibling) {
                    Evaluator left = ((StructuralEvaluator) edge).evaluator;
                    if (left instanceof Root || ScopeSelector.isScopeAnchor(left)) return '+';
                    edge = left; // a chained sibling combinator; continue to the left-most edge
                } else if (edge instanceof PreviousSibling) {
                    Evaluator left = ((StructuralEvaluator) edge).evaluator;
                    if (left instanceof Root || ScopeSelector.isScopeAnchor(left)) return '~';
                    edge = left;
                } else {
                    return 0; // bare branch, or a leading '>' (a Root)
                }
            }
        }

        /**
         * Whether a sibling branch chains a further combinator after its leading {@code +}/{@code ~}, such that the
         * match target may lie within a sibling's subtree (e.g. {@code + article > img}) rather than being the sibling
         * element itself (e.g. {@code + p.selected}). The leading sibling-of-{@link Root} edge is the axis edge and is
         * not counted; {@link Is}, {@link Not} and nested {@link Has} evaluate their own contents and are self-contained.
         */
        private static boolean hasChainedCombinator(Evaluator eval) {
            if (eval instanceof Ancestor) return true;
            if (eval instanceof ImmediateParentRun) return ((ImmediateParentRun) eval).evaluators.size() > 1;
            if (eval instanceof ImmediatePreviousSibling || eval instanceof PreviousSibling) {
                Evaluator left = ((StructuralEvaluator) eval).evaluator;
                return !(left instanceof Root || ScopeSelector.isScopeAnchor(left)); // leading axis edge wraps the anchor
            }
            if (eval instanceof CombiningEvaluator) {
                for (Evaluator child : ((CombiningEvaluator) eval).evaluators) {
                    if (hasChainedCombinator(child)) return true;
                }
            }
            return false; // a plain simple sequence, or a self-contained evaluator (Is, Not, nested Has, etc.)
        }

        @Override protected int cost() {
            return 10 * evaluator.cost();
        }

        @Override
        public String toString() {
            return String.format(":has(%s)", evaluator);
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
