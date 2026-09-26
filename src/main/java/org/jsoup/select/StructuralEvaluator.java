package org.jsoup.select;

import org.jsoup.internal.SoftPool;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.LeafNode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.NodeIterator;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
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
     * Implements the {@code :has(relative-selector-list)} structural pseudo-class. Each branch of the selector list is
     * evaluated independently against the candidate element acting as the anchor:
     * <ul>
     *   <li>a branch without a leading combinator, or starting with {@code >}, matches only within the anchor's
     *       descendants (with the {@link Root} pin enforcing direct-child semantics for {@code >});</li>
     *   <li>a branch starting with {@code +} checks only the anchor's immediately following element sibling;</li>
     *   <li>a branch starting with {@code ~} checks any following element sibling.</li>
     * </ul>
     * For sibling branches, the selector chain may continue below the matched sibling (e.g. {@code :has(~ p span)}),
     * so that sibling's descendants are searched when the chain requires it. Non-element nodes do not participate in
     * sibling relationships.
     */
    static class Has extends StructuralEvaluator {
        static final SoftPool<NodeIterator<Node>> NodeIterPool =
            new SoftPool<>(() -> new NodeIterator<>(new TextNode(""), Node.class));
        // the element here is just a placeholder so this can be final - gets set in restart()

        private final List<Branch> branches;

        public Has(Evaluator evaluator) {
            super(evaluator);
            branches = resolveBranches(evaluator);
        }

        @Override public boolean matches(Element root, Element element) {
            for (Branch branch : branches) {
                if (branch.axis == 0) {
                    if (matchesDescendant(element, branch.eval)) return true;
                } else if (matchesSibling(element, branch)) {
                    return true;
                }
            }
            return false;
        }

        // match within the anchor's subtree only (not the anchor itself)
        private boolean matchesDescendant(Element anchor, Evaluator eval) {
            NodeIterator<Node> it = NodeIterPool.borrow();
            it.restart(anchor);
            try {
                while (it.hasNext()) {
                    Node node = it.next();
                    if (node == anchor) continue; // don't match self, only descendants
                    if (eval.matches(anchor, node)) return true;
                }
            } finally {
                NodeIterPool.release(it);
            }
            return false;
        }

        // match against following element siblings, and (when the chain descends) within each sibling's subtree.
        // a lone leading '+' needs only the immediate next sibling; for a chained query such as :has(+ p ~ span), the
        // match can be on a later sibling (the compiled evaluator's Root pin still enforces the anchor adjacency)
        private boolean matchesSibling(Element anchor, Branch branch) {
            NodeIterator<Node> it = branch.descends ? NodeIterPool.borrow() : null;
            try {
                for (Element sib = anchor.nextElementSibling(); sib != null; sib = sib.nextElementSibling()) {
                    if (branch.eval.matches(anchor, sib)) return true;

                    if (branch.descends) { // the selector chain continues below the sibling
                        it.restart(sib);
                        while (it.hasNext()) {
                            Node node = it.next();
                            if (node == sib) continue;
                            if (branch.eval.matches(anchor, node)) return true;
                        }
                    }

                    if (branch.adjacentOnly) return false;
                }
            } finally {
                if (it != null) NodeIterPool.release(it);
            }
            return false;
        }

        @Override
        boolean evaluateMatch(Element root, Node node) {
            return false; // a leaf node cannot be a :has() anchor
        }

        private static final class Branch {
            final char axis; // 0 = within anchor subtree (leading none or '>'), '+' = next sibling, '~' = later siblings
            final Evaluator eval;
            final boolean descends; // a sibling chain that continues below the matched sibling
            final boolean adjacentOnly; // a lone leading '+': only the immediate next sibling need be checked

            Branch(char axis, Evaluator eval, boolean descends, boolean adjacentOnly) {
                this.axis = axis;
                this.eval = eval;
                this.descends = descends;
                this.adjacentOnly = adjacentOnly;
            }
        }

        /** Split the selector list into its independent branches (the top-level Or from the commas). */
        private static List<Branch> resolveBranches(Evaluator eval) {
            ArrayList<Evaluator> clauses = new ArrayList<>();
            collectOrClauses(eval, clauses);
            ArrayList<Branch> branches = new ArrayList<>(clauses.size());
            for (Evaluator clause : clauses) {
                // walk the combinator chain from right to left; it is a relative sibling branch only if its left
                // end is the implicit Root (i.e. the branch started with '+' or '~')
                char lastCombinator = 0;
                int combinatorCount = 0;
                boolean seenSibling = false;
                boolean descends = false; // a down-step to the right of the leading sibling combinator
                Evaluator cur = clause;
                char combinator;
                while ((combinator = rightmostCombinator(cur)) != 0) {
                    lastCombinator = combinator;
                    combinatorCount++;
                    if (combinator == '+' || combinator == '~') seenSibling = true;
                    else if (!seenSibling) descends = true;
                    cur = leftSide(cur, combinator);
                }
                boolean relative = cur instanceof StructuralEvaluator.Root
                    && (lastCombinator == '+' || lastCombinator == '~');
                boolean adjacentOnly = relative && combinatorCount == 1 && lastCombinator == '+';
                char axis = relative ? lastCombinator : 0;
                branches.add(new Branch(axis, clause, relative && descends, adjacentOnly));
            }
            return branches;
        }

        private static void collectOrClauses(Evaluator eval, List<Evaluator> clauses) {
            if (eval instanceof CombiningEvaluator.Or) {
                for (Evaluator clause : ((CombiningEvaluator.Or) eval).evaluators)
                    collectOrClauses(clause, clauses);
            } else {
                clauses.add(eval);
            }
        }

        /**
         * The rightmost (last parsed) combinator of a linear selector chain. A chain combinator is held by an
         * ImmediateParentRun ({@code >}), or directly by an And ({@code } descendant, {@code +} / {@code ~} sibling);
         * when an And carries several, the last one appended is the rightmost. Nested :has(), :is(), and :not() scopes
         * never appear on this spine and so are naturally opaque.
         * @return {@code ' '}, {@code '>'}, {@code '+'}, {@code '~'}, or {@code 0} if there is no combinator
         */
        private static char rightmostCombinator(Evaluator eval) {
            if (eval instanceof StructuralEvaluator.ImmediateParentRun) return '>';
            if (eval instanceof CombiningEvaluator.And) {
                char combinator = 0;
                for (Evaluator child : ((CombiningEvaluator.And) eval).evaluators) {
                    char c = combinatorOf(child);
                    if (c != 0) combinator = c; // later append = further right
                }
                return combinator;
            }
            return 0;
        }

        private static char combinatorOf(Evaluator eval) {
            if (eval instanceof StructuralEvaluator.Ancestor) return ' ';
            if (eval instanceof StructuralEvaluator.ImmediateParentRun) return '>';
            if (eval instanceof StructuralEvaluator.ImmediatePreviousSibling) return '+';
            if (eval instanceof StructuralEvaluator.PreviousSibling) return '~';
            return 0;
        }

        /** The left-hand evaluator wrapped by the combinator reported by {@link #rightmostCombinator(Evaluator)}. */
        private static Evaluator leftSide(Evaluator eval, char combinator) {
            if (eval instanceof StructuralEvaluator.ImmediateParentRun)
                return ((StructuralEvaluator.ImmediateParentRun) eval).evaluators.get(0);
            StructuralEvaluator rightmost = null;
            for (Evaluator child : ((CombiningEvaluator.And) eval).evaluators) {
                if (combinatorOf(child) == combinator) rightmost = (StructuralEvaluator) child;
            }
            if (rightmost instanceof StructuralEvaluator.ImmediateParentRun)
                return ((StructuralEvaluator.ImmediateParentRun) rightmost).evaluators.get(0);
            if (rightmost != null) return rightmost.evaluator;
            throw new IllegalStateException("Undeclared combinator " + combinator); // should not happen
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
