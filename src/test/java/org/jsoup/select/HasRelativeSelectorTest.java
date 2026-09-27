package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies :has() relative-selector anchoring, nesting, error, and re-evaluation semantics. */
public class HasRelativeSelectorTest {

    @Test void nestedHasKeepsAnchorPerLayer() {
        Document doc = Jsoup.parse(
            "<section id=s1><article><h2>in</h2></article></section>" + // article contains h2, not adjacent
            "<section id=s2><article></article><h2>adj</h2></section>" + // article immediately followed by h2
            "<section id=s3><article></article><p></p><h2>late</h2></section>"); // h2 not immediate
        Elements found = doc.select("section:has(article:has(+ h2))");
        assertEquals(1, found.size());
        assertEquals("s2", found.get(0).id());
    }

    @Test void innerHasDoesNotDriftToOuterSiblings() {
        // section s1's article has no adjacent h2, but the section itself is followed by h2 - must not match
        Document doc = Jsoup.parse(
            "<div><section id=s1><article></article></section><h2>x</h2></div>");
        assertEquals(0, doc.select("section:has(article:has(+ h2))").size());
    }

    @Test void leadingCombinators() {
        Document doc = Jsoup.parse(
            "<div id=d1><p></p><span></span></div>" +
            "<div id=d2><p></p></div>" +
            "<div id=d3><em><p></p></em></div>");
        assertEquals("d1 d2 d3", ids(doc.select("div:has(p)")));
        assertEquals("d1 d2", ids(doc.select("div:has(> p)")));
        assertEquals("d1 d2", ids(doc.select("div:has(+ div p)"))); // immediate next sibling div contains p
        assertEquals("d1 d2", ids(doc.select("div:has(~ div)")));
    }

    @Test void adjacentSiblingImmediateOnly() {
        Document doc = Jsoup.parse("<div><p id=a></p><em></em><h2></h2></div><div><p id=b></p><h2></h2></div>");
        Elements found = doc.select("p:has(+ h2)");
        assertEquals(1, found.size());
        assertEquals("b", found.get(0).id());
    }

    @Test void commaBranchesDedupeAndOrder() {
        Document doc = Jsoup.parse("<div id=a><p></p></div><div id=b><span></span></div><div id=c><p></p><span></span></div>");
        Elements found = doc.select("div:has(p, span)");
        assertEquals("a b c", ids(found));
    }

    @Test void nonElementNodesSkippedInSiblingAxis() {
        Document doc = Jsoup.parse("<div><p id=t></p>text<!--c--><h2></h2></div>");
        assertEquals(1, doc.select("p:has(+ h2)").size());
    }

    @Test void parseErrorsFailWholeQuery() {
        String[] bad = {
            "div:has()",
            "div:has(>)",
            "div:has(p, )",
            "div:has(p, +)",
            "div:has(p",
            "div:has(:bogus)",
            "div:has(p) , span:has()",
            "div >",
        };
        for (String q : bad) {
            assertThrows(Selector.SelectorParseException.class, () -> doc().select(q), q);
        }
    }

    @Test void domMutationReflectedImmediately() {
        Document doc = Jsoup.parse("<div id=a><p></p></div><div id=b></div>");
        String q = "div:has(p)";
        assertEquals("a", ids(doc.select(q)));
        Element p = doc.selectFirst("p");
        assertNotNull(p);
        doc.selectFirst("#b").appendChild(p);
        assertEquals("b", ids(doc.select(q)));
        // attribute change affecting inner selector
        doc.selectFirst("#b").appendElement("i");
        assertEquals(0, doc.select("div:has(> i.x)").size());
        doc.selectFirst("i").addClass("x");
        assertEquals("b", ids(doc.select("div:has(> i.x)")));
    }

    @Test void repeatedCallsStable() {
        Document doc = Jsoup.parse("<div id=a><p></p></div><div id=b></div>");
        String q = "div:has(p)";
        Elements first = doc.select(q);
        Elements second = doc.select(q);
        assertEquals(ids(first), ids(second));
    }

    @Test void emptyResultWhenNoMatch() {
        Document doc = Jsoup.parse("<div><p></p></div>");
        assertTrue(doc.select("div:has(table)").isEmpty());
    }

    @Test void selectFromSubtreeRoot() {
        Document doc = Jsoup.parse(
            "<div id=outer><section id=s1><article></article><h2>x</h2></section></div>" +
            "<section id=s2><article></article></section>");
        Element outer = doc.selectFirst("#outer");
        assertNotNull(outer);
        Elements found = outer.select("section:has(article + h2)");
        assertEquals(1, found.size());
        assertEquals("s1", found.get(0).id());
    }

    @Test void branchCombinesTypeClassAttributePseudo() {
        Document doc = Jsoup.parse(
            "<div id=a><p class=x data-k=v></p></div>" +
            "<div id=b><p class=x></p></div>" +
            "<div id=c><span class=x data-k=v></span></div>");
        assertEquals("a", ids(doc.select("div:has(> p.x[data-k=v]:first-child)")));
    }

    @Test void threeLevelNesting() {
        Document doc = Jsoup.parse(
            "<main id=m1><section><article></article><h2>x</h2></section></main>" +
            "<main id=m2><section><article></article></section><h2>outer</h2></main>");
        Elements found = doc.select("main:has(section:has(article:has(+ h2)))");
        assertEquals(1, found.size());
        assertEquals("m1", found.get(0).id());
    }

    @Test void generalSiblingAxis() {
        Document doc = Jsoup.parse(
            "<div><p id=a></p><em></em><h2></h2></div>" +
            "<ul><li id=b></li></ul>");
        assertEquals("a", ids(doc.select("p:has(~ h2)")));
        assertEquals(0, doc.select("li:has(~ h2)").size());
    }

    private static Document doc() {
        return Jsoup.parse("<div><p></p></div>");
    }

    private static String ids(Elements els) {
        StringBuilder sb = new StringBuilder();
        for (Element el : els) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(el.id());
        }
        return sb.toString();
    }
}
