package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 Verifies the {@code :scope} pseudo-class on {@link Element#select(String)}: it anchors a query to the element on which
 select was invoked, alone and combined with descendants, children, siblings, other pseudo-classes, nested {@code :has()},
 and comma branches, while keeping parse errors atomic and re-evaluating against the current tree.
 */
public class ScopeSelectorTest {

    private static final String Html =
        "<div id=container>" +
            "<p id=p1 class=lead>One<span id=s1></span></p>" +
            "text<!-- c -->" +
            "<p id=p2>Two</p>" +
            "<section id=sec><article id=art><h2 id=h2>x</h2></article></section>" +
        "</div>" +
        "<div id=other><b id=b></b></div>" +
        "<p id=p3></p>";

    @Test void scopeAloneMatchesCallElement() {
        Document doc = Jsoup.parse(Html);
        assertSame(doc, doc.select(":scope").first()); // the document root element itself

        Element container = doc.selectFirst("#container");
        Elements found = container.select(":scope");
        assertEquals(1, found.size());
        assertSame(container, found.first());
    }

    @Test void scopeOnDetachedElement() {
        Element container = Jsoup.parse(Html).selectFirst("#container");
        assertNotNull(container);
        container.remove(); // now detached, but retains its children and their order

        Elements self = container.select(":scope");
        assertEquals(1, self.size());
        assertSame(container, self.first());

        assertEquals("p1 p2", ids(container.select(":scope p")));
        assertEquals("p1 p2", ids(container.select(":scope > p")));
        assertTrue(container.select(":scope + div").isEmpty()); // no parent, so no siblings
        assertTrue(container.select(":scope ~ p").isEmpty());

        Element fresh = new Element("div");
        assertSame(fresh, fresh.select(":scope").first());
        assertTrue(fresh.select(":scope *").isEmpty());
    }

    @Test void scopeCombinesWithTypeClassAttributeAndPseudo() {
        Document doc = Jsoup.parse(Html);
        Element p1 = doc.selectFirst("#p1");
        assertNotNull(p1);

        assertSame(p1, p1.select(":scope#p1").first());
        assertSame(p1, p1.select(":scope.lead").first());
        assertSame(p1, p1.select(":scope[id=p1]").first());
        assertSame(p1, p1.select(":scope:first-child").first());
        assertSame(p1, p1.select(":scope[id].lead").first()); // multiple simple selectors refine the same anchor

        assertTrue(p1.select(":scope#p2").isEmpty()); // the anchor is p1, never another element
        assertTrue(p1.select(":scope:not(#p1)").isEmpty());
        assertTrue(p1.select(":scope:has(span)").size() == 1);
        assertTrue(p1.select(":scope:has(div)").isEmpty());
    }

    @Test void scopeDescendantAndChild() {
        Document doc = Jsoup.parse(Html);
        Element container = doc.selectFirst("#container");
        assertNotNull(container);

        assertEquals("p1 s1 p2 sec art h2", ids(container.select(":scope *")));
        assertEquals("p1 p2", ids(container.select(":scope p")));
        assertEquals("p1 p2", ids(container.select(":scope > p")));
        assertEquals("s1", ids(container.select(":scope p span")));
        assertEquals("s1", ids(container.select(":scope > p > span")));
        assertEquals("h2", ids(container.select(":scope section article h2")));

        // the anchor itself is not a descendant/child result
        assertFalse(container.select(":scope p").contains(container));
        assertFalse(container.select(":scope > *").contains(container));
        // nothing outside the anchor subtree leaks in
        assertTrue(container.select(":scope b").isEmpty());
        assertTrue(container.select(":scope > div").isEmpty());
    }

    @Test void scopeSiblingCombinators() {
        Document doc = Jsoup.parse(Html);
        Element container = doc.selectFirst("#container");
        Element p1 = doc.selectFirst("#p1");
        Element p2 = doc.selectFirst("#p2");
        assertNotNull(container);

        // adjacent sibling: text and comment nodes do not count; p1's next element sibling is p2
        assertSame(p2, p1.select(":scope + p").first());
        assertEquals("p2", ids(p1.select(":scope + *")));

        // general sibling
        assertEquals("p2", ids(p1.select(":scope ~ p")));
        assertSame(doc.selectFirst("#other"), container.select(":scope + div").first());
        assertEquals("other p3", ids(container.select(":scope ~ *")));
        assertEquals("other", ids(container.select(":scope ~ div")));
        assertEquals("b", ids(container.select(":scope ~ div b"))); // chained into a sibling's subtree

        // preceding siblings and the anchor's ancestors are never candidates
        assertTrue(p2.select(":scope + p").isEmpty());
        assertTrue(p2.select(":scope ~ p").isEmpty());
        assertTrue(container.select(":scope + head").isEmpty());
    }

    @Test void nestedHasReanchorsScopePerLayer() {
        Document doc = Jsoup.parse(
            "<section id=s1><article><h2>in</h2></article></section>" +
            "<section id=s2><article></article><h2>adj</h2></section>" +
            "<section id=s3><article></article><p></p><h2>late</h2></section>");

        // inner :scope is the article (the :has candidate), not the section nor the document/call element
        Elements found = doc.select("section:has(article:has(:scope + h2))");
        assertEquals(1, found.size());
        assertEquals("s2", found.first().id());

        assertEquals(1, doc.select("section:has(article:has(:scope h2))")
            .stream().filter(e -> e.id().equals("s1")).count());
    }

    @Test void innerScopeDoesNotDriftToCallElement() {
        Document doc = Jsoup.parse("<div id=outer><section><article></article></section><h2>x</h2></div>");
        Element outer = doc.selectFirst("#outer");
        assertNotNull(outer);
        // the section's article has no adjacent h2; the h2 follows the section - must not match
        assertTrue(outer.select("section:has(article:has(:scope + h2))").isEmpty());
        assertTrue(doc.select("section:has(article:has(:scope + h2))").isEmpty());
    }

    @Test void scopeWithinHasMatchesAnchorItself() {
        Document doc = Jsoup.parse("<div id=a><p></p></div><div id=b></div>");
        // :scope inside :has resolves to the candidate, so :has(:scope) is satisfied by each matching candidate
        assertEquals("a b", ids(doc.select("div:has(:scope)")));
        assertEquals("a", ids(doc.select("div:has(:scope p)")));
        assertEquals("a", ids(doc.select("div:has(:scope > p)")));
    }

    @Test void commaBranchesDedupedInDocumentOrder() {
        Document doc = Jsoup.parse(Html);
        Element p1 = doc.selectFirst("#p1");
        assertNotNull(p1);

        // the same node reached by two branches is returned once, in document order
        Elements both = p1.select(":scope, :scope + p");
        assertEquals("p1 p2", ids(both));
        assertEquals(1, p1.select(":scope, :scope").size());

        // subtree branch and sibling branch together
        Element container = doc.selectFirst("#container");
        assertNotNull(container);
        assertEquals("p1 s1 p2 sec art h2 b", ids(container.select(":scope *, :scope ~ div *")));
    }

    @Test void parseErrorsFailTheWholeQuery() {
        String[] bad = {
            ":scope > :scope",       // more than one :scope in one selector
            ":scope:is(:scope)",     // the second :scope compounds the same selector
            ":scope.lead:scope",
            "div:has(:scope :scope)",
            ":scope >",              // dangling combinator
            ":scope,",               // empty branch
            ", :scope",
            ":scope ,",
            "div:has(:scope, )",
            ":scope[",               // unclosed bracket
            "div:has(:scope",        // unclosed paren
            ":scope:bogus",          // unknown pseudo
            ":scope +",
            ":scope ~",
        };
        for (String q : bad) {
            assertThrows(Selector.SelectorParseException.class, () -> Jsoup.parse(Html).select(q), q);
        }
    }

    @Test void validMultipleScopesOnePerBranch() {
        Document doc = Jsoup.parse(Html);
        Element p1 = doc.selectFirst("#p1");
        assertNotNull(p1);
        assertEquals("p1 p2", ids(p1.select(":scope, :scope + p"))); // one :scope per comma branch
    }

    @Test void domMutationsAreReflectedOnRepeatedCalls() {
        Document doc = Jsoup.parse("<div id=a><p id=p></p></div><div id=b></div>");
        String query = ":scope > p";
        Element a = doc.selectFirst("#a");
        Element b = doc.selectFirst("#b");
        assertNotNull(a);
        assertNotNull(b);

        assertEquals("p", ids(a.select(query)));
        assertTrue(b.select(query).isEmpty());

        Element p = doc.selectFirst("#p");
        assertNotNull(p);
        b.appendChild(p); // move p from a to b
        assertTrue(a.select(query).isEmpty());
        assertEquals("p", ids(b.select(query)));

        // attribute / class changes affect later matches
        Evaluator compiled = Selector.evaluatorOf(":scope > p.hot");
        assertTrue(a.select(compiled).isEmpty() && b.select(compiled).isEmpty());
        p.addClass("hot");
        assertEquals("p", ids(b.select(compiled))); // reused evaluator must not return stale results

        // sibling reordering
        Document d2 = Jsoup.parse("<ul><li id=one>1</li><li id=two>2</li></ul>");
        Element one = d2.selectFirst("#one");
        assertNotNull(one);
        assertEquals("two", ids(one.select(":scope + li")));
        Element ul = d2.selectFirst("ul");
        assertNotNull(ul);
        ul.appendChild(one); // move #one to the end
        assertEquals(0, one.select(":scope + li").size());
    }

    @Test void repeatedCallsStableAndNeverEscapeScope() {
        Document doc = Jsoup.parse(Html);
        Element container = doc.selectFirst("#container");
        assertNotNull(container);
        String query = ":scope > p, :scope ~ div";
        Elements first = container.select(query);
        Elements second = container.select(query);
        assertEquals(ids(first), ids(second));

        Element other = doc.selectFirst("#other");
        assertNotNull(other);
        for (Element el : first) {
            assertTrue(el == other || el.parent() == container || el == container,
                "result outside the scope subtree/anchor siblings");
        }
        assertFalse(first.stream().anyMatch(el -> el.parent() == doc.head()));
    }

    @Test void queryDoesNotMutateTree() {
        Document doc = Jsoup.parse(Html);
        Element container = doc.selectFirst("#container");
        assertNotNull(container);
        String before = doc.html();
        container.select(":scope, :scope p, :scope > section, :scope ~ div, :scope + div");
        doc.select("section:has(article:has(:scope + h2, :scope h2))");
        assertEquals(before, doc.html());
    }

    @Test void selectFirstAndCompiledEvaluatorHonorScope() {
        Document doc = Jsoup.parse(Html);
        Element p1 = doc.selectFirst("#p1");
        assertNotNull(p1);
        assertSame(p1, p1.selectFirst(":scope"));
        assertSame(doc.selectFirst("#p2"), p1.selectFirst(":scope + p"));
        assertNull(p1.selectFirst(":scope + section"));

        Evaluator eval = Selector.evaluatorOf(":scope span");
        assertEquals("s1", ids(p1.select(eval)));
        assertNull(doc.selectFirst("#other").selectFirst(eval));
    }

    @Test void scopeAnchorsAtDocumentRootAndHtml() {
        Document doc = Jsoup.parse("<div id=a></div><div id=b></div>");
        // :scope on the document matches the document element itself; its one direct element child is <html>
        assertEquals(1, doc.select(":scope").size());
        assertSame(doc, doc.select(":scope").first());
        assertSame(doc.selectFirst("html"), doc.selectFirst(":scope > html"));

        Element html = doc.selectFirst("html");
        assertNotNull(html);
        assertSame(html, html.select(":scope").first());
        assertSame(doc.head(), html.selectFirst(":scope > head"));
        assertSame(doc.body(), html.selectFirst(":scope > body"));
    }

    @Test void scopeSeenThroughIsPseudo() {
        Document doc = Jsoup.parse("<div id=c><b></b></div><p id=p1></p><p id=p2></p>");
        Element container = doc.selectFirst("#c");
        assertNotNull(container);
        assertEquals("p1", ids(container.select(":is(:scope + p)")));
        assertEquals("p1 p2", ids(container.select(":is(:scope ~ p)")));
        assertEquals("p1 p2", ids(container.select(":is(:scope + b, :scope ~ p)")));
        // chained combinator inside :is may reach into a following sibling's subtree
        Document d2 = Jsoup.parse("<div id=c></div><div id=d><span id=sp></span></div>");
        Element c2 = d2.selectFirst("#c");
        assertNotNull(c2);
        assertEquals("sp", ids(c2.select(":is(:scope ~ div span)")));
    }

    @Test void selectorsWithoutScopeKeepDescendantOnlyBehavior() {        Document doc = Jsoup.parse(Html);
        Element container = doc.selectFirst("#container");
        assertNotNull(container);
        // no :scope: the calling element's own siblings are not searched, only its descendants
        assertEquals("p1 p2", ids(container.select("p")));
        assertTrue(container.select("head").isEmpty());
        assertEquals("p1 p2", ids(container.select("> p")));
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
