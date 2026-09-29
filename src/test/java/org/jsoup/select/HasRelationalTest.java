package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Selector.SelectorParseException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Determinism, isolation, and error-boundary regression tests for the relational {@code :has()} pseudo-class:
 * independent per-branch scoping, union/dedup/document-order, strict scope exclusion, parse-failure atomicity,
 * no mutation, round-trip equivalence, and cross-document reuse of a compiled evaluator.
 */
class HasRelationalTest {

    private static void ids(String html, String query, String... expected) {
        Document doc = Jsoup.parse(html);
        List<String> ids = doc.select(query).eachAttr("id");
        assertIterableEquals(Arrays.asList(expected), ids, () -> "query: " + query + " got " + ids);
    }

    private static String html() {
        return "<article id=a><h1></h1><h2></h2><p id=p1><b></b><i></i></p></article>"
            + "<article id=z><h1></h1><p></p><b></b><i></i></article>"
            + "<article id=b><h1></h1></article>";
    }

    @Test void eachBranchEvaluatedIndependentlyAndUnioned() {
        String html = html();
        ids(html, "article:has(> h1, + article)", "a", "z", "b");
        // a branch with no matches must not invalidate the other branches
        ids(html, "article:has(> missing, > h1)", "a", "z", "b");
        ids(html, "article:has(> h1, > missing)", "a", "z", "b");
        // result is the same regardless of branch order, and always in document order
        ids(html, "article:has(+ article, > h1)", "a", "z", "b");
        // all branches empty -> normal empty result, not an error and not "select all"
        ids(html, "article:has(nope, nada, + missing)");
    }

    @Test void candidateMatchedBySeveralBranchesReturnedOnce() {
        String html = "<div id=d><p id=p1><a></a></p><p id=p2><a></a></p></div>";
        Elements got = Jsoup.parse(html).select("div:has(p a, p:has(a), p > a)");
        assertEquals(1, got.size());
        assertEquals("d", got.first().id());
    }

    @Test void parentAndChildMatchesStayInDocumentOrder() {
        String html = "<section id=s><div id=d1><p></p></div><div id=d2></div></section>";
        ids(html, "section:has(p), div:has(p)", "s", "d1");
        ids(html, "div:has(p), section:has(p)", "s", "d1");
    }

    @Test void candidateIsNeverItsOwnDescendant() {
        // :scope inside :has only matches the anchor itself, which is excluded; so nothing matches
        Document doc = Jsoup.parse("<div id=d><p>hi</p></div>");
        assertEquals(0, doc.select(":has(:scope)").size());
        // a strict descendant chain cannot start on the tested element
        String html = "<div id=A><p><a></a></p></div><div id=B><a></a></div>";
        ids(html, "div:has(* > a)", "A");
        ids(html, "div:has(> a)", "B");
    }

    @Test void scopeIsolationDoesNotReachOutsideCandidateSubtree() {
        // an internal sibling combinator is resolved inside the candidate; only a leading +/~ may see outside
        String html = "<article id=a><p></p></article><x id=out></x>";
        ids(html, "article:has(> p + x)");
        ids(html, "article:has(> p ~ x)");
        // a leading + does reach the following sibling
        ids("<article id=a></article><article id=b></article>", "article:has(+ article)", "a");
    }

    @Test void emptyOrMalformedBranchFailsWholeSelector() {
        for (String q : new String[]{
            "div:has()", "div:has( )", "div:has(,)", "div:has(p,)", "div:has(,p)", "div:has(p,,q)",
            "div:has(p >)", "div:has(> )", "div:has(p +)", "div:has(p ~)", "div:has(p:has(q)", "div:has(p"
        }) {
            assertThrows(SelectorParseException.class, () -> QueryParser.parse(q), () -> "accepted: " + q);
        }
    }

    @Test void documentStillUsableAfterFailedSelector() {
        Document doc = Jsoup.parse(html());
        String serialized = doc.html();
        assertThrows(SelectorParseException.class, () -> doc.select("article:has(> h1"));
        // ordinary tag/attribute/relational selections behave exactly as before the failure
        assertEquals(0, doc.select("article#b > section").size());
        assertNull(doc.selectFirst("a[href]"));
        assertEquals(3, doc.select("article").size());
        assertIterableEquals(Arrays.asList("a", "z", "b"), doc.select("article:has(> h1)").eachAttr("id"));
        // and the document was not mutated by the attempted parse/match
        assertEquals(serialized, doc.html());
    }

    @Test void selectionDoesNotMutateDocument() {
        Document doc = Jsoup.parse(html());
        String before = doc.html();
        for (int i = 0; i < 50; i++) {
            doc.select("article:has(> h1 + h2, ~ article, p:has(b + i))");
            doc.select("article:has(> missing, > h1, ::comment)");
            doc.selectFirst("article:has(+ article)");
        }
        assertEquals(before, doc.html());
    }

    @Test void serializeReparseIsEquivalent() {
        Document doc = Jsoup.parse(html());
        doc.outputSettings().prettyPrint(false);
        Document reparsed = Jsoup.parse(doc.html());
        String[] queries = {
            "article:has(> h1, + article)", "article:has(p:has(b + i))", "*:has(*)",
            "article:has(:scope + article)", "div:has(article)", "h1:has(+ h2)"
        };
        for (String q : queries) {
            List<String> a = doc.select(q).eachAttr("id");
            List<String> b = reparsed.select(q).eachAttr("id");
            assertEquals(a, b, () -> "roundtrip query: " + q);
        }
    }

    @Test void compiledEvaluatorReusableAcrossDocumentsAndRepeatedCalls() {
        Evaluator eval = Selector.evaluatorOf("article:has(> h1, + article)");
        Document d1 = Jsoup.parse(html());
        Document d2 = Jsoup.parse("<article id=c><h1></h1></article><article id=q></article>");
        List<String> r1 = d1.select(eval).eachAttr("id");
        List<String> r2 = d2.select(eval).eachAttr("id");
        List<String> r1b = d1.select(eval).eachAttr("id");
        List<String> r2b = d2.select(eval).eachAttr("id");
        assertIterableEquals(Arrays.asList("a", "z", "b"), r1);
        assertIterableEquals(Arrays.asList("c"), r2);
        assertEquals(r1, r1b);
        assertEquals(r2, r2b);
        // a fresh document still works after the evaluator was used elsewhere
        Document d3 = Jsoup.parse(html());
        assertEquals(r1, d3.select(eval).eachAttr("id"));
    }

    @Test void emptyAndNoMatchDocumentsReturnEmptyCollections() {
        Document empty = Jsoup.parse("");
        assertTrue(empty.body().select("div:has(p, a)").isEmpty());
        assertTrue(empty.body().select(":has(*)").isEmpty());
        Document noMatch = Jsoup.parse("<div></div><span></span>");
        assertTrue(noMatch.select("div:has(p, a)").isEmpty());
    }

    @Test void parsedDomValuesUsedForMatching() {
        String html = "<div id=x><span class='a.b'></span>"
            + "<a title='say &quot;hi&quot; &amp; bye'></a><p>a &amp; b</p></div>";
        // escaped class name
        ids(html, "div:has(.a\\.b)", "x");
        // quoted attribute value compared against the entity-decoded DOM value
        ids(html, "div:has(a[title=\"say \\\"hi\\\" & bye\"])", "x");
        // entity-decoded text
        ids(html, "div:has(p:contains(a & b))", "x");
        // universal selector inside a branch
        ids(html, "div:has(*)", "x");
    }
}
