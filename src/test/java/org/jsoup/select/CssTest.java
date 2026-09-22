package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


public class CssTest {

	private Document html = null;
	private static String htmlString;

	@BeforeAll
	public static void initClass() {
		StringBuilder sb = new StringBuilder("<html><head></head><body>");

		sb.append("<div id='pseudo'>");
		for (int i = 1; i <= 10; i++) {
			sb.append(String.format("<p>%d</p>",i));
		}
		sb.append("</div>");

		sb.append("<div id='type'>");
		for (int i = 1; i <= 10; i++) {
			sb.append(String.format("<p>%d</p>",i));
			sb.append(String.format("<span>%d</span>",i));
			sb.append(String.format("<em>%d</em>",i));
            sb.append(String.format("<svg>%d</svg>",i));
		}
		sb.append("</div>");

		sb.append("<span id='onlySpan'><br /></span>");
		sb.append("<p class='empty'><!-- Comment only is still empty! --></p>");

		sb.append("<div id='only'>");
		sb.append("Some text before the <em>only</em> child in this div");
		sb.append("</div>");

		sb.append("</body></html>");
		htmlString = sb.toString();
	}

	@BeforeEach
	public void init() {
		html  = Jsoup.parse(htmlString);
	}

	@Test
	public void firstChild() {
		check(html.select("#pseudo :first-child"), "1");
		check(html.select("html:first-child"));
	}

	@Test
	public void lastChild() {
		check(html.select("#pseudo :last-child"), "10");
		check(html.select("html:last-child"));
	}

	@Test
	public void nthChild_simple() {
		for(int i = 1; i <=10; i++) {
			check(html.select(String.format("#pseudo :nth-child(%d)", i)), String.valueOf(i));
		}
	}

    @Test
    public void nthOfType_unknownTag() {
        for(int i = 1; i <=10; i++) {
            check(html.select(String.format("#type svg:nth-of-type(%d)", i)), String.valueOf(i));
        }
    }

	@Test
	public void nthLastChild_simple() {
		for(int i = 1; i <=10; i++) {
			check(html.select(String.format("#pseudo :nth-last-child(%d)", i)), String.valueOf(11-i));
		}
	}

	@Test
	public void nthOfType_simple() {
		for(int i = 1; i <=10; i++) {
			check(html.select(String.format("#type p:nth-of-type(%d)", i)), String.valueOf(i));
		}
	}

	@Test
	public void nthLastOfType_simple() {
		for(int i = 1; i <=10; i++) {
			check(html.select(String.format("#type :nth-last-of-type(%d)", i)), String.valueOf(11-i),String.valueOf(11-i),String.valueOf(11-i),String.valueOf(11-i));
		}
	}

	@Test
	public void nthChild_advanced() {
		check(html.select("#pseudo :nth-child(-5)"));
		check(html.select("#pseudo :nth-child(odd)"), "1", "3", "5", "7", "9");
		check(html.select("#pseudo :nth-child(2n-1)"), "1", "3", "5", "7", "9");
		check(html.select("#pseudo :nth-child(2n+1)"), "1", "3", "5", "7", "9");
		check(html.select("#pseudo :nth-child(2n+3)"), "3", "5", "7", "9");
		check(html.select("#pseudo :nth-child(even)"), "2", "4", "6", "8", "10");
		check(html.select("#pseudo :nth-child(2n)"), "2", "4", "6", "8", "10");
		check(html.select("#pseudo :nth-child(3n-1)"), "2", "5", "8");
		check(html.select("#pseudo :nth-child(-2n+5)"), "1", "3", "5");
		check(html.select("#pseudo :nth-child(+5)"), "5");
	}

	@Test
	public void nthOfType_advanced() {
		check(html.select("#type :nth-of-type(-5)"));
		check(html.select("#type p:nth-of-type(odd)"), "1", "3", "5", "7", "9");
		check(html.select("#type em:nth-of-type(2n-1)"), "1", "3", "5", "7", "9");
		check(html.select("#type p:nth-of-type(2n+1)"), "1", "3", "5", "7", "9");
		check(html.select("#type span:nth-of-type(2n+3)"), "3", "5", "7", "9");
		check(html.select("#type p:nth-of-type(even)"), "2", "4", "6", "8", "10");
		check(html.select("#type p:nth-of-type(2n)"), "2", "4", "6", "8", "10");
		check(html.select("#type p:nth-of-type(3n-1)"), "2", "5", "8");
		check(html.select("#type p:nth-of-type(-2n+5)"), "1", "3", "5");
		check(html.select("#type :nth-of-type(+5)"), "5", "5", "5", "5");
	}


	@Test
	public void nthLastChild_advanced() {
		check(html.select("#pseudo :nth-last-child(-5)"));
		check(html.select("#pseudo :nth-last-child(odd)"), "2", "4", "6", "8", "10");
		check(html.select("#pseudo :nth-last-child(2n-1)"), "2", "4", "6", "8", "10");
		check(html.select("#pseudo :nth-last-child(2n+1)"), "2", "4", "6", "8", "10");
		check(html.select("#pseudo :nth-last-child(2n+3)"), "2", "4", "6", "8");
		check(html.select("#pseudo :nth-last-child(even)"), "1", "3", "5", "7", "9");
		check(html.select("#pseudo :nth-last-child(2n)"), "1", "3", "5", "7", "9");
		check(html.select("#pseudo :nth-last-child(3n-1)"), "3", "6", "9");

		check(html.select("#pseudo :nth-last-child(-2n+5)"), "6", "8", "10");
		check(html.select("#pseudo :nth-last-child(+5)"), "6");
	}

	@Test
	public void nthLastOfType_advanced() {
		check(html.select("#type :nth-last-of-type(-5)"));
		check(html.select("#type p:nth-last-of-type(odd)"), "2", "4", "6", "8", "10");
		check(html.select("#type em:nth-last-of-type(2n-1)"), "2", "4", "6", "8", "10");
		check(html.select("#type p:nth-last-of-type(2n+1)"), "2", "4", "6", "8", "10");
		check(html.select("#type span:nth-last-of-type(2n+3)"), "2", "4", "6", "8");
		check(html.select("#type p:nth-last-of-type(even)"), "1", "3", "5", "7", "9");
		check(html.select("#type p:nth-last-of-type(2n)"), "1", "3", "5", "7", "9");
		check(html.select("#type p:nth-last-of-type(3n-1)"), "3", "6", "9");

		check(html.select("#type span:nth-last-of-type(-2n+5)"), "6", "8", "10");
		check(html.select("#type :nth-last-of-type(+5)"), "6", "6", "6", "6");
	}

	@Test
	public void firstOfType() {
		check(html.select("div:not(#only) :first-of-type"), "1", "1", "1", "1", "1");
	}

	@Test
	public void lastOfType() {
		check(html.select("div:not(#only) :last-of-type"), "10", "10", "10", "10", "10");
	}

	@Test
	public void empty() {
		final Elements sel = html.select(":empty");
		assertEquals(3, sel.size());
		assertEquals("head", sel.get(0).tagName());
		assertEquals("br", sel.get(1).tagName());
		assertEquals("p", sel.get(2).tagName());
	}

	@Test
	public void onlyChild() {
		final Elements sel = html.select("span :only-child");
		assertEquals(1, sel.size());
		assertEquals("br", sel.get(0).tagName());

		check(html.select("#only :only-child"), "only");
	}

	@Test
	public void onlyOfType() {
		final Elements sel = html.select(":only-of-type");
		assertEquals(6, sel.size());
		assertEquals("head", sel.get(0).tagName());
		assertEquals("body", sel.get(1).tagName());
		assertEquals("span", sel.get(2).tagName());
		assertEquals("br", sel.get(3).tagName());
		assertEquals("p", sel.get(4).tagName());
		assertTrue(sel.get(4).hasClass("empty"));
		assertEquals("em", sel.get(5).tagName());
	}

	protected void check(Elements result, String...expectedContent ) {
		assertEquals(expectedContent.length, result.size(), "Number of elements");
		for (int i = 0; i < expectedContent.length; i++) {
			assertNotNull(result.get(i));
			assertEquals(expectedContent[i], result.get(i).ownText(), "Expected element");
		}
	}

	@Test
	public void root() {
		Elements sel = html.select(":root");
		assertEquals(1, sel.size());
		assertNotNull(sel.get(0));
		assertEquals(Tag.valueOf("html"), sel.get(0).tag());

		Elements sel2 = html.select("body").select(":root");
		assertEquals(1, sel2.size());
		assertNotNull(sel2.get(0));
		assertEquals(Tag.valueOf("body"), sel2.get(0).tag());
	}

	// https://www.w3.org/TR/selectors-4/#nth-child-pseudo :nth-child(An+B of S)
	private static final String OfFixture =
		"<i id=\"a\" class=\"x\"></i><i id=\"b\"></i><i id=\"c\" class=\"x\"></i>"
		+ "<i id=\"d\" class=\"x\"></i><i id=\"e\"></i><i id=\"f\" class=\"x\"></i>";

	private static Document ofDoc() {
		return Jsoup.parse(OfFixture);
	}

	private static void checkIds(Elements result, String... expectedIds) {
		assertEquals(expectedIds.length, result.size(), "Number of elements");
		for (int i = 0; i < expectedIds.length; i++)
			assertEquals(expectedIds[i], result.get(i).id(), "Expected element");
	}

	@Test
	public void nthChildOf_forward() {
		Document doc = ofDoc();
		// among the .x siblings (a=1, c=2, d=3, f=4), the even positions are c and f
		checkIds(doc.select("i:nth-child(2n of .x)"), "c", "f");
		checkIds(doc.select("i:nth-child(even of .x)"), "c", "f");
		checkIds(doc.select("i:nth-child(odd of .x)"), "a", "d");
		checkIds(doc.select("i:nth-child(1 of .x)"), "a");
		checkIds(doc.select("i:nth-child(-n+2 of .x)"), "a", "c");
	}

	@Test
	public void nthLastChildOf_reverse() {
		Document doc = ofDoc();
		// counted from the end of the .x siblings (f=1, d=2, c=3, a=4), the even positions are a and d
		checkIds(doc.select("i:nth-last-child(2n of .x)"), "a", "d");
		checkIds(doc.select("i:nth-last-child(even of .x)"), "a", "d");
		// odd positions are f and c; results are still collected in document order
		checkIds(doc.select("i:nth-last-child(odd of .x)"), "c", "f");
		checkIds(doc.select("i:nth-last-child(1 of .x)"), "f");
		checkIds(doc.select("i:nth-last-child(-n+2 of .x)"), "d", "f");
	}

	@Test
	public void nthChildOf_isConsistent() {
		Document doc = ofDoc();
		for (org.jsoup.nodes.Element el : doc.select("i")) {
			boolean expectedForward = el.id().equals("c") || el.id().equals("f");
			assertEquals(expectedForward, el.is("i:nth-child(2n of .x)"), el.id());

			boolean expectedReverse = el.id().equals("a") || el.id().equals("d");
			assertEquals(expectedReverse, el.is("i:nth-last-child(2n of .x)"), el.id());
		}
	}

	@Test
	public void nthChildOf_nonMatchingSiblingsHoldNoPosition() {
		// b and e do not match .x, and must not occupy a position; they themselves never match
		Document doc = ofDoc();
		for (String id : new String[]{"b", "e"}) {
			org.jsoup.nodes.Element el = doc.getElementById(id);
			assertFalse(el.is("i:nth-child(1n of .x)"));
			assertFalse(el.is("i:nth-last-child(1n of .x)"));
		}
		// without a filter, all 6 siblings keep their natural positions
		checkIds(doc.select("i:nth-child(2n)"), "b", "d", "f");
	}

	@Test
	public void nthChildOf_selectorList() {
		Document doc = ofDoc();
		// comma-separated list: .x or #e => a, c, d, e, f; even positions are c and e
		checkIds(doc.select("i:nth-child(2n of .x, #e)"), "c", "e");
		// from the end, the even filtered positions are also c and e; results are still in document order
		checkIds(doc.select("i:nth-last-child(2n of .x, #e)"), "c", "e");
	}

	@Test
	public void nthChildOf_nestedPseudos() {
		Document doc = ofDoc();
		// :is() inside the selector list
		checkIds(doc.select("i:nth-child(2n of :is(.x))"), "c", "f");
		// :not() inside the selector list => a, c, f; even filtered position is c
		checkIds(doc.select("i:nth-child(2n of .x:not(#d))"), "c");
		// nested nth-child inside the of list: .x that is an odd natural child => a, d; first filtered position is a
		checkIds(doc.select("i:nth-child(1 of .x:nth-child(odd))"), "a");
	}

	@Test
	public void nthChildOf_ofNotSplitInsideParensQuotesOrEscapes() {
		// the "of" inside quotes must not be treated as the clause delimiter; only a and f carry the attribute
		Document quoted = Jsoup.parse(
			"<i id=\"a\" class=\"x\" data-v=\"x of y\"></i><i id=\"b\"></i>"
			+ "<i id=\"c\" class=\"x\"></i><i id=\"f\" class=\"x\" data-v=\"x of y\"></i>");
		checkIds(quoted.select("i:nth-child(2n of .x[data-v=\"x of y\"])"), "f");

		// the "of" inside nested parens (:is) must not split the clause
		Document doc = ofDoc();
		checkIds(doc.select("i:nth-child(2n of .x:is(.x, .y))"), "c", "f");

		// a nested :nth-child(... of ...) inside the of list puts "of" inside parens - must not be
		// mistaken for a second top-level clause (nothing carries class y, so the result is empty)
		checkIds(doc.select("i:nth-child(1n of .x:nth-child(1n of .y))"));

		// an escaped class identifier that decodes to "of" must not be mistaken for the keyword
		Document escaped = Jsoup.parse(OfFixture + "<i id=\"g\" class=\"of\"></i>");
		checkIds(escaped.select("i:nth-child(1n of .\\6f f)"), "g");
	}

	@Test
	public void nthChildOf_perParent() {
		// positions are computed among siblings of the same parent, independently
		Document doc = Jsoup.parse(
			"<div><i id=\"a\" class=\"x\"></i><i id=\"b\"></i></div>"
			+ "<div><i id=\"c\" class=\"x\"></i><i id=\"d\" class=\"x\"></i></div>");
		checkIds(doc.select("i:nth-child(1 of .x)"), "a", "c");
	}

	@Test
	public void nthChildOf_compiledEvaluatorToString() {
		Evaluator eval = QueryParser.parse("i:nth-child(2n of .x)");
		assertTrue(eval.toString().contains(":nth-child(2n of .x)"));
	}

	@Test
	public void nthChildOf_errors() {
		Document doc = ofDoc();
		// empty selector list
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-child(2n of )"));
		// missing formula
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-child(of .x)"));
		// invalid formula
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-child(banana of .x)"));
		// invalid selector list
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-child(2n of ..x)"));
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-child(2n of .x >)"));
		// more than one top-level of clause
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-child(2n of .x of .y)"));
		// trailing tokens after the pseudo are not ignored
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-child(2n of .x) ?"));
		// nth-of-type / nth-last-of-type do not accept an of clause
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-of-type(2n of .x)"));
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("i:nth-last-of-type(2n of .x)"));
	}

}
