package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@code :scope} pseudo-class: it binds a selector to the element on which
 * {@link Element#select(String)} was invoked, supports descendant/child/sibling relationships, re-anchors inside each
 * nested {@code :has()}, fails as a whole on invalid fragments, and is always re-evaluated against the current tree.
 */
public class ScopeSelectorTest {

    private static String ids(Elements els) {
        StringBuilder sb = new StringBuilder();
        for (Element el : els) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(el.id());
        }
        return sb.toString();
    }

    private static String tags(Elements els) {
        StringBuilder sb = new StringBuilder();
        for (Element el : els) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(el.tagName());
        }
        return sb.toString();
    }

    private static Document sample() {
        return Jsoup.parse(
            "<section id=sec>" +
                "<p id=p1>one<span id=s1>x</span></p>" +
                "text<!--c1-->" +
                "<p id=p2 class=hit>two</p>" +
                "<em id=e1></em>" +
                "<article id=art><h2 id=h2></h2></article>" +
            "</section>" +
            "<p id=p3>three</p>" +
            "<div id=out></div>");
    }

    @Test void scopeAloneMatchesOnlyTheContextElement() {
        Document doc = sample();
        Element sec = doc.expectFirst("#sec");
        Elements found = sec.select(":scope");
        assertEquals(1, found.size());
        assertSame(sec, found.first());
        assertSame(doc, doc.select(":scope").first());
    }

    @Test void scopeDoesNotMatchAncestorOrUnrelated() {
        Document doc = sample();
        Element p1 = doc.expectFirst("#p1");
        Elements found = p1.select(":scope");
        assertEquals(1, found.size());
        found.forEach(el -> assertSame(p1, el));
        // a child cannot reach its own ancestor through :scope
        assertEquals(0, doc.expectFirst("#s1").select(":scope > section").size());
        assertEquals(0, doc.expectFirst("#s1").select(":scope section").size());
    }

    @Test void scopeDescendantAndChild() {
        Document doc = sample();
        Element sec = doc.expectFirst("#sec");
        assertEquals("p1 s1 p2 e1 art h2", ids(sec.select(":scope *")));
        assertEquals("p1 p2 e1 art", ids(sec.select(":scope > *")));
        assertEquals("s1", ids(doc.expectFirst("#p1").select(":scope span")));
        assertEquals("s1", ids(doc.expectFirst("#p1").select(":scope > span")));
        assertEquals("", ids(doc.expectFirst("#p2").select(":scope span")));
        assertEquals("s1", ids(sec.select(":scope > p span")));
    }

    @Test void scopeAdjacentSibling() {
        Document doc = sample();
        assertEquals("p3", ids(doc.expectFirst("#sec").select(":scope + p")));
        assertEquals("p2", ids(doc.expectFirst("#p1").select(":scope + p")));
        assertEquals("", ids(doc.expectFirst("#p2").select(":scope + p"))); // immediate sibling is em, a non-p
        assertEquals("", ids(doc.expectFirst("#art").select(":scope + p")));
        assertEquals("art", ids(doc.expectFirst("#e1").select(":scope + article")));
        assertEquals("", ids(doc.expectFirst("#out").select(":scope + p")));
    }

    @Test void scopeGeneralSibling() {
        Document doc = sample();
        assertEquals("p2", ids(doc.expectFirst("#p1").select(":scope ~ p")));
        assertEquals("p2 e1 art", ids(doc.expectFirst("#p1").select(":scope ~ *")));
        assertEquals("p3", ids(doc.expectFirst("#sec").select(":scope ~ p")));
        assertEquals("", ids(doc.expectFirst("#art").select(":scope ~ p")));
        assertEquals("", ids(doc.expectFirst("#out").select(":scope ~ p")));
    }

    @Test void siblingAxisSkipsNonElementNodes() {
        Document doc = sample();
        // text and a comment sit between p1 and p2: '+' is the immediate ELEMENT sibling, not the immediate node
        assertEquals("p2", ids(doc.expectFirst("#p1").select(":scope + p")));
        // the general axis reaches the later element sibling
        assertEquals("p2", ids(doc.expectFirst("#p1").select(":scope ~ p.hit")));
    }

    @Test void siblingChainsSearchSiblingSubtrees() {
        Document doc = sample();
        assertEquals("h2", ids(doc.expectFirst("#e1").select(":scope + article > h2")));
        assertEquals("h2", ids(doc.expectFirst("#p1").select(":scope ~ article h2")));
        // sec's immediate sibling p3 contains no span, so nothing matches despite the chained descendant
        assertEquals("", ids(doc.expectFirst("#sec").select(":scope + p span")));
        assertEquals("h2", ids(doc.expectFirst("#p2").select(":scope ~ article > h2")));
    }

    @Test void scopeCombinesWithTypeClassAttributeAndPseudo() {
        Document doc = Jsoup.parse(
            "<div id=outer class=box data-k=v><p id=a class=x></p><p id=b></p><i id=i></i></div>" +
            "<b id=next></b>");
        Element outer = doc.expectFirst("#outer");
        assertEquals("outer", ids(outer.select("div:scope")));
        assertEquals("outer", ids(outer.select(":scope.box[data-k=v]")));
        assertEquals("a", ids(outer.select(":scope > p.x:first-child")));
        assertEquals("a b", ids(outer.select(":scope > p:lt(2)")));
        assertEquals("next", ids(outer.select(":scope + b")));
        assertEquals("", ids(outer.select(":scope.nope")));
        assertEquals("", ids(outer.select("span:scope"))); // the context is a div, not a span
    }

    @Test void scopeInsideIs() {
        Document doc = sample();
        Element sec = doc.expectFirst("#sec");
        assertEquals("sec", ids(sec.select(":is(:scope)")));
        assertEquals("p1 p2 e1 art", ids(sec.select(":is(:scope) > *")));
        assertEquals("p2", ids(doc.expectFirst("#p1").select(":is(div, :scope) ~ p")));
    }

    @Test void nestedHasReanchorsScopePerLayer() {
        Document doc = Jsoup.parse(
            "<section id=s1><article><h2>in</h2></article></section>" +
            "<section id=s2><article></article><h2>adj</h2></section>" +
            "<section id=s3><article></article><p></p><h2>late</h2></section>");
        // the inner :scope is the article candidate, never the section that owns the outer :has()
        Elements found = doc.select("section:has(article:has(:scope + h2))");
        assertEquals(1, found.size());
        assertEquals("s2", found.get(0).id());
    }

    @Test void nestedHasScopeDoesNotDriftToOuterAnchor() {
        Document doc = Jsoup.parse("<div><section id=s1><article></article></section><h2>x</h2></div>");
        // s1 itself is followed by h2, but the article candidate is not; must not match
        assertEquals(0, doc.select("section:has(article:has(:scope + h2))").size());
    }

    @Test void scopeAsHasAnchorSelfMatch() {
        Document doc = Jsoup.parse("<div id=a class=x><p id=a1></p></div><div id=b><span id=b1></span></div>");
        assertEquals("a b", ids(doc.select("div:has(:scope)")));
        assertEquals("a", ids(doc.select("div:has(:scope.x)")));
        assertEquals("a b", ids(doc.select("div:has(:scope, p)")));
        assertEquals("a", ids(doc.select("div:has(:scope > p)")));
        assertEquals("b", ids(doc.select("div:has(:scope span)")));
    }

    @Test void scopeSiblingBranchInsideHas() {
        Document doc = Jsoup.parse("<div id=d1><p></p></div><div id=d2></div><div id=d3></div>");
        assertEquals("d1 d2", ids(doc.select("div:has(:scope + div)")));
        assertEquals("d1 d2", ids(doc.select("div:has(:scope ~ div)")));
        assertEquals("", ids(doc.select("#d3:has(:scope + div)")));
    }

    @Test void scopeFromDetachedElementUsesCurrentRelationships() {
        Element holder = new Element("holder");
        Element section = new Element("section").id("d");
        section.appendElement("p").id("d1");
        Element sibling = new Element("p").id("d2");

        // before attachment: no parent, no siblings
        assertEquals("d", ids(section.select(":scope")));
        assertEquals("d1", ids(section.select(":scope p")));
        assertEquals("", ids(section.select(":scope + p")));
        assertEquals("", ids(section.select(":scope ~ p")));

        holder.appendChild(section);
        holder.appendChild(sibling);
        assertEquals("d2", ids(section.select(":scope + p")));
        assertEquals("d2", ids(section.select(":scope ~ p")));
        assertEquals("d1", ids(section.select(":scope p")));
    }

    @Test void scopeAtDocumentRoot() {
        Document doc = Jsoup.parse("<html><head><title>x</title></head><body><p id=p></p></body></html>");
        assertSame(doc, doc.select(":scope").first());
        assertEquals("html", tags(doc.select(":scope > html")));
        assertEquals("p", ids(doc.select(":scope > html > body > p")));
    }

    @Test void commaBranchesDeduplicatedInDocumentOrder() {
        Document doc = sample();
        Element sec = doc.expectFirst("#sec");
        // a scope-headed sibling branch plus an ordinary descendant branch in the same query
        Elements both = sec.select(":scope ~ p, p");
        assertEquals("p1 p2 p3", ids(both));
        assertEquals(both.size(), both.stream().distinct().count());

        // overlapping descendant matches are returned once, in document order
        Elements overlaps = sec.select(":scope > p, p");
        assertEquals("p1 p2", ids(overlaps));
        assertEquals(overlaps.size(), overlaps.stream().distinct().count());
    }

    @Test void branchWithoutScopeStaysWithinContext() {
        Document doc = sample();
        Element sec = doc.expectFirst("#sec");
        // p3 is a following sibling of sec; the plain branch must never surface it
        assertEquals(0, sec.select("p").stream().filter(el -> el.id().equals("p3")).count());
        // only the scope-headed sibling branch can leave the context subtree
        Elements both = sec.select(":scope ~ p, p");
        assertTrue(both.stream().anyMatch(el -> el.id().equals("p3")));
        assertTrue(both.stream().filter(el -> el.id().equals("p3")).count() <= 1);
    }

    @Test void selectorsWithoutScopeKeepDescendantsOnlyBehavior() {
        Document doc = sample();
        Element sec = doc.expectFirst("#sec");
        // "*" matches every element including the context itself; all are within the context subtree
        assertEquals("sec p1 s1 p2 e1 art h2", ids(sec.select("*")));
        assertEquals("p1 p2", ids(sec.select("p")));
        assertEquals("p1 p2", ids(sec.select("> p")));
        assertEquals(0, sec.select("p").stream().filter(el -> el.id().equals("p3")).count());
    }

    @Test void multipleScopeFailsWholeQuery() {
        Document doc = sample();
        String[] bad = {
            ":scope :scope",
            ":scope, :scope",
            ":scope > :scope",
            ":scope + :scope",
            "div:has(:scope :scope)",
            "div:has(:scope, :scope)",
            "div:has(:scope > :scope)",
            "div:has(a:has(:scope :scope))",
            ":scope ~ p, :scope ~ em",
        };
        for (String q : bad) {
            assertThrows(Selector.SelectorParseException.class, () -> doc.select(q), q);
        }
    }

    @Test void unparseableFragmentsFailWholeQuery() {
        Document doc = sample();
        String[] bad = {
            ":scope >", ":scope +", ":scope ~", ":scope,,p", ",p", "p,",
            "div:has(", "div:has(:scope", ":bogus", ":scope(", ":scope > > p", "p >> :scope",
        };
        for (String q : bad) {
            assertThrows(Selector.SelectorParseException.class, () -> doc.select(q), q);
        }
        // no partial results leak from a later failing branch
        assertThrows(Selector.SelectorParseException.class, () -> doc.select("p, :scope :scope"));
    }

    @Test void scopeRecomputedAfterDomMutation() {
        Document doc = Jsoup.parse("<div id=a><p id=pa></p></div><div id=b><i id=ib></i></div>");
        Element a = doc.expectFirst("#a");
        assertEquals("pa", ids(a.select(":scope > p")));

        Element pa = doc.expectFirst("#pa");
        doc.expectFirst("#b").appendChild(pa); // move pa under b
        assertEquals("", ids(a.select(":scope > p")));
        assertEquals("pa", ids(doc.expectFirst("#b").select(":scope > p")));

        // structural type change reflected immediately (b now holds pa and ib, both <p>, in child order)
        doc.expectFirst("#ib").tagName("p");
        assertEquals("ib pa", ids(doc.expectFirst("#b").select(":scope > p")));

        // id/class/attribute changes reflected immediately
        Element ib = doc.expectFirst("#ib");
        ib.attr("id", "ib2").addClass("z").attr("data-k", "v");
        assertEquals("ib2", ids(doc.expectFirst("#b").select(":scope > p.z[data-k=v]")));

        // sibling order change changes adjacency
        Document d2 = Jsoup.parse("<div id=x><b id=b1></b><b id=b2></b></div>");
        Element b1 = d2.expectFirst("#b1");
        assertEquals("b2", ids(b1.select(":scope + b")));
        b1.parent().appendChild(b1); // move b1 to the end; it has no following sibling now
        assertEquals("", ids(b1.select(":scope + b")));
    }

    @Test void repeatedCallsStableWhenTreeUnchanged() {
        Document doc = sample();
        Element sec = doc.expectFirst("#sec");
        String q = ":scope > p";
        Elements first = sec.select(q);
        Elements second = sec.select(q);
        assertEquals(ids(first), ids(second));
        assertSame(first.first(), second.first());
    }

    @Test void compiledEvaluatorReusedAcrossRoots() {
        Document doc = sample();
        Evaluator eval = Selector.evaluatorOf(":scope + p");
        assertEquals("p2", ids(doc.expectFirst("#p1").select(eval)));
        assertEquals("p3", ids(doc.expectFirst("#sec").select(eval)));
        assertEquals("", ids(doc.expectFirst("#art").select(eval)));
    }

    @Test void selectFirstHonorsScope() {
        Document doc = sample();
        assertSame(doc.expectFirst("#p2"), doc.expectFirst("#p1").selectFirst(":scope + p"));
        assertNull(doc.expectFirst("#art").selectFirst(":scope + p"));
        assertSame(doc.expectFirst("#sec"), doc.expectFirst("#sec").selectFirst(":scope"));
    }

    @Test void queryDoesNotMutateTree() {
        Document doc = sample();
        String before = doc.outerHtml();
        Element sec = doc.expectFirst("#sec");
        int childNodes = sec.childNodeSize();
        sec.select(":scope");
        sec.select(":scope > p");
        sec.select(":scope + p");
        sec.select(":scope ~ p");
        sec.select(":scope p");
        sec.selectNodes(":scope + ::comment", Comment.class);
        assertEquals(before, doc.outerHtml());
        assertEquals(childNodes, sec.childNodeSize());
    }

    @Test void scopeWithNodeSiblingSelectors() {
        Document doc = Jsoup.parse("<div id=sec><p id=p1>one</p>text<!--c1--><em id=e1></em></div><!--after-->");
        Element p1 = doc.expectFirst("#p1");
        Nodes<TextNode> texts = p1.selectNodes(":scope + ::text", TextNode.class);
        assertEquals(1, texts.size());
        assertEquals("text", texts.first().text());

        Nodes<Comment> comments = p1.selectNodes(":scope ~ ::comment", Comment.class);
        assertEquals("c1", comments.first().getData());

        // element sibling semantics still skip the intervening text/comment
        assertSame(doc.expectFirst("#e1"), p1.selectNodes(":scope + em", Element.class).first());
    }

    @Test void scopeToStringRoundTrips() {
        assertEquals(":scope > p", QueryParser.parse(":scope > p").toString());
        assertEquals(":scope", QueryParser.parse(":scope").toString());
    }

    @Test void scopeAfterCombinatorNeverMatchesButIsParsed() {
        Document doc = sample();
        // syntactically valid (as in browser scoped queries) but the only node equal to the scope is the context
        // element itself, which structural edges never treat as a descendant/child/sibling target
        assertEquals(0, doc.expectFirst("#p1").select("p + :scope").size());
        assertEquals(0, doc.expectFirst("#sec").select("> :scope").size());
        assertEquals(0, doc.expectFirst("#sec").select("p :scope").size());
    }
}
