package org.jsoup.select;

import org.jsoup.internal.SoftPool;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.LeafNode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.NodeIterator;
import org.jsoup.nodes.TextNode;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
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

    /** The explicit :scope pseudo-class; matches the element the (sub-)query is anchored to. Equivalent to the
     implicit Root that a leading combinator applies to. */
    static class Scope extends Evaluator {
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

    static class Has extends StructuralEvaluator {
        static final SoftPool<NodeIterator<Node>> NodeIterPool =
            new SoftPool<>(() -> new NodeIterator<>(new TextNode(""), Node.class));
        // the element here is just a placeholder so this can be final - gets set in restart()

        private final List<Evaluator> branches; // the top-level (comma separated) relative selectors
        private final boolean[] wantsSiblings; // per branch: if anchored to the scope by a leading + or ~ combinator

        public Has(Evaluator evaluator) {
            super(evaluator);
            // Split the selector group into its top-level branches, so that each can be tested against the correct
            // candidate set (the scope element's descendants, or its following siblings)
            if (evaluator instanceof CombiningEvaluator.Or)
                branches = ((CombiningEvaluator.Or) evaluator).evaluators;
            else
                branches = Collections.singletonList(evaluator);
            wantsSiblings = new boolean[branches.size()];
            for (int i = 0; i < branches.size(); i++)
                wantsSiblings[i] = evalWantsSiblings(branches.get(i));
        }

        @Override public boolean matches(Element root, Element element) {
            // we want to minimize GCs so reusing the Iterator obj
            NodeIterator<Node> it = NodeIterPool.borrow();
            try {
                for (int i = 0; i < branches.size(); i++) {
                    Evaluator branch = branches.get(i);
                    if (wantsSiblings[i]) {
                        // a leading + or ~ combinator anchors the branch to the element's following siblings (and their
                        // descendants); only these branches may see outside the element's own subtree. If the branch
                        // tests leaf nodes (e.g. ::comment), step through the complete sibling node sequence;
                        // otherwise only element siblings are considered
                        boolean nodeSteps = branch.wantsNodes();
                        for (Node sib = nodeStep(element, nodeSteps); sib != null; sib = nodeStep(sib, nodeSteps)) {
                            it.restart(sib);
                            while (it.hasNext()) {
                                if (branch.matches(element, it.next()))
                                    return true;
                            }
                        }
                    } else {
                        // otherwise we only want to match children (or below), and not the input element
                        it.restart(element);
                        while (it.hasNext()) {
                            Node node = it.next();
                            if (node == element) continue; // don't match self, only descendants
                            if (branch.matches(element, node))
                                return true;
                        }
                    }
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

        /** The next sibling to test in a sibling-anchored :has branch: every node in node mode, else elements only. */
        private static @Nullable Node nodeStep(Node node, boolean nodeSteps) {
            return nodeSteps ? node.nextSibling() : node.nextElementSibling();
        }

        /* Test if a :has branch is anchored to the scope root by a leading + or ~ combinator (a PreviousSibling or
         ImmediatePreviousSibling directly on the Root / :scope anchor). Only those branches are evaluated against the
         scope element's siblings; others see its descendants only, so that e.g. :has(h1 ~ h2) cannot match elements
         outside the scope's subtree. A nested :has() re-anchors to its own scope element, so is not descended into. */
        private static boolean evalWantsSiblings(Evaluator eval) {
            if (eval instanceof PreviousSibling || eval instanceof ImmediatePreviousSibling) {
                Evaluator inner = ((StructuralEvaluator) eval).evaluator;
                if (inner instanceof Root || inner instanceof Scope)
                    return true;
            }
            if (eval instanceof CombiningEvaluator) {
                for (Evaluator inner : ((CombiningEvaluator) eval).evaluators) {
                    if (evalWantsSiblings(inner))
                        return true;
                }
            } else if (eval instanceof ImmediateParentRun) {
                for (Evaluator inner : ((ImmediateParentRun) eval).evaluators) {
                    if (evalWantsSiblings(inner))
                        return true;
                }
            } else if (eval instanceof StructuralEvaluator && !(eval instanceof Has)) {
                return evalWantsSiblings(((StructuralEvaluator) eval).evaluator);
            }
            return false;
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
