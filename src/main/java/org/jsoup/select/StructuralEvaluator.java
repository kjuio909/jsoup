package org.jsoup.select;

import org.jsoup.internal.SoftPool;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.LeafNode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.NodeIterator;
import org.jsoup.nodes.TextNode;

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
        private final boolean[] nodeChains; // per branch: if the chain matches leaf nodes (::comment, ::text etc)

        public Has(Evaluator evaluator) {
            super(evaluator);
            // Split the selector group into its top-level branches, so that each can be tested against the correct
            // candidate set (the scope element's descendants, or its following siblings)
            if (evaluator instanceof CombiningEvaluator.Or)
                branches = ((CombiningEvaluator.Or) evaluator).evaluators;
            else
                branches = Collections.singletonList(evaluator);
            wantsSiblings = new boolean[branches.size()];
            nodeChains = new boolean[branches.size()];
            for (int i = 0; i < branches.size(); i++) {
                Evaluator branch = branches.get(i);
                wantsSiblings[i] = anchoredToFollowingSiblings(branch);
                nodeChains[i] = chainContainsLeaf(branch);
                if (nodeChains[i])
                    forceNodeAdjacency(branch); // a leaf in the chain makes every + step compare the full node sequence
            }
        }

        @Override public boolean matches(Element root, Element element) {
            // we want to minimize GCs so reusing the Iterator obj
            NodeIterator<Node> it = NodeIterPool.borrow();
            try {
                for (int i = 0; i < branches.size(); i++) {
                    Evaluator branch = branches.get(i);
                    if (wantsSiblings[i]) {
                        // a leading + or ~ combinator anchors the branch to the element's following siblings (and their
                        // descendants); only these branches may see outside the element's own subtree
                        if (nodeChains[i]) {
                            // the chain matches leaf nodes (text, comments, etc), so those are candidates too and the
                            // siblings are walked as the complete node sequence rather than the element sequence
                            for (Node sib = element.nextSibling(); sib != null; sib = sib.nextSibling()) {
                                it.restart(sib);
                                while (it.hasNext()) {
                                    if (branch.matches(element, it.next()))
                                        return true;
                                }
                            }
                        } else {
                            for (Element sib = element.nextElementSibling(); sib != null; sib = sib.nextElementSibling()) {
                                it.restart(sib);
                                while (it.hasNext()) {
                                    if (branch.matches(element, it.next()))
                                        return true;
                                }
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

        /* Test if a :has branch is anchored to the scope root by a leading + or ~ combinator (a PreviousSibling or
         ImmediatePreviousSibling whose left side is the Root / :scope anchor). Only such a branch is evaluated against
         the scope element's siblings; others see its descendants only, so that e.g. :has(h1 ~ h2) cannot match elements
         outside the scope's subtree. The :is(), :not(), and nested :has() containers establish their own anchor and do
         not borrow the outer sibling relation, so traversal stops at them; a later + or ~ within the branch, such as
         "> p + x", is an internal combinator and is not mistaken for the anchor either. */
        private static boolean anchoredToFollowingSiblings(Evaluator eval) {
            if (eval instanceof PreviousSibling || eval instanceof ImmediatePreviousSibling) {
                Evaluator left = ((StructuralEvaluator) eval).evaluator;
                if (isScopeAnchor(left))
                    return true;
                return anchoredToFollowingSiblings(left);
            }
            if (eval instanceof CombiningEvaluator) {
                for (Evaluator inner : ((CombiningEvaluator) eval).evaluators) {
                    if (anchoredToFollowingSiblings(inner))
                        return true;
                }
            } else if (eval instanceof ImmediateParentRun) {
                for (Evaluator inner : ((ImmediateParentRun) eval).evaluators) {
                    if (anchoredToFollowingSiblings(inner))
                        return true;
                }
            } else if (eval instanceof Ancestor) {
                return anchoredToFollowingSiblings(((StructuralEvaluator) eval).evaluator);
            }
            return false; // Is, Not, Has (own anchors), and leaf / simple evaluators
        }

        /* The implicit Root of a leading combinator, or an explicit :scope, possibly compounded with simple filters
         (e.g. :scope:not(.x)). Must not descend into structural evaluators, where the scope is only a deeper operand. */
        private static boolean isScopeAnchor(Evaluator eval) {
            if (eval instanceof Root || eval instanceof Scope)
                return true;
            if (eval instanceof CombiningEvaluator) {
                for (Evaluator inner : ((CombiningEvaluator) eval).evaluators) {
                    if (isScopeAnchor(inner))
                        return true;
                }
            }
            return false;
        }

        /* Test if the branch's chain contains a leaf-node selector (::comment, ::text, etc). A nested :has() is its own
         chain anchored to its own scope, so it is not considered; :is() and :not() contents still qualify candidates of
         this chain, and are descended into. */
        private static boolean chainContainsLeaf(Evaluator eval) {
            if (eval instanceof Has)
                return false; // a nested :has() runs its own chain
            if (eval instanceof NodeEvaluator)
                return true;
            if (eval instanceof CombiningEvaluator) {
                for (Evaluator inner : ((CombiningEvaluator) eval).evaluators) {
                    if (chainContainsLeaf(inner))
                        return true;
                }
            } else if (eval instanceof ImmediateParentRun) {
                for (Evaluator inner : ((ImmediateParentRun) eval).evaluators) {
                    if (chainContainsLeaf(inner))
                        return true;
                }
            } else if (eval instanceof StructuralEvaluator) {
                return chainContainsLeaf(((StructuralEvaluator) eval).evaluator);
            }
            return false;
        }

        /* Once a chain contains a leaf-node selector, every adjacent-sibling (+) step in that chain compares against the
         complete sibling sequence -- text, comment, and data leaves count as intervening nodes instead of being
         skipped. A nested :has() runs its own chain (prepared by its own constructor); :is() and :not() are chains of
         their own and determine their mode from their own contents. */
        private static void forceNodeAdjacency(Evaluator eval) {
            applyNodeAdjacency(eval, true);
        }

        private static void applyNodeAdjacency(Evaluator eval, boolean chainHasLeaf) {
            if (eval instanceof Has)
                return; // a nested :has() runs its own chain
            if (eval instanceof Is || eval instanceof Not) {
                // the container's contents are their own selector list / chain
                Evaluator inner = ((StructuralEvaluator) eval).evaluator;
                if (inner instanceof CombiningEvaluator.Or) {
                    for (Evaluator branch : ((CombiningEvaluator.Or) inner).evaluators)
                        applyNodeAdjacency(branch, chainContainsLeaf(branch));
                } else {
                    applyNodeAdjacency(inner, chainContainsLeaf(inner));
                }
                return;
            }
            if (eval instanceof ImmediatePreviousSibling) {
                StructuralEvaluator ips = (StructuralEvaluator) eval;
                if (chainHasLeaf)
                    ips.wantsNodes = true;
                applyNodeAdjacency(ips.evaluator, chainHasLeaf);
                return;
            }
            if (eval instanceof CombiningEvaluator) {
                for (Evaluator inner : ((CombiningEvaluator) eval).evaluators)
                    applyNodeAdjacency(inner, chainHasLeaf);
            } else if (eval instanceof ImmediateParentRun) {
                for (Evaluator inner : ((ImmediateParentRun) eval).evaluators)
                    applyNodeAdjacency(inner, chainHasLeaf);
            } else if (eval instanceof StructuralEvaluator) {
                applyNodeAdjacency(((StructuralEvaluator) eval).evaluator, chainHasLeaf); // Ancestor, PreviousSibling
            }
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
