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
	public void nthChildOfSelector() {
		// :nth-child(An+B of S) - only siblings matching S are candidates, counted in document order
		Document doc = Jsoup.parse(
			"<div id=s><b id=x>a</b><i id=y>b</i><b id=z>c</b><em>d</em><b id=w>e</b></div>");

		assertEquals("z", doc.selectFirst("#s>:nth-child(2 of b)").id());
		assertEquals("w", doc.selectFirst("#s>:nth-last-child(1 of b,i)").id());

		// only matching siblings are counted
		assertEquals("x", doc.selectFirst("#s>:nth-child(1 of b)").id());
		assertEquals("z", doc.selectFirst("#s>:nth-last-child(2 of b)").id());
		assertEquals("w", doc.selectFirst("#s>:nth-last-child(1 of b)").id());
		assertEquals(ids(doc, "#s>:nth-child(odd of b)"), "x,w");
		assertEquals(ids(doc, "#s>:nth-child(even of b)"), "z");
		assertEquals(ids(doc, "#s>:nth-child(-n+2 of b)"), "x,z");
		assertEquals(ids(doc, "#s>:nth-child(2 of b, i)"), "y");
		// whitespace inside the parens is fine
		assertEquals("z", doc.selectFirst("#s>:nth-child( 2 of b )").id());
		assertEquals("w", doc.selectFirst("#s>:nth-last-child(1 of b , i)").id());
		// "of" inside an attribute value is not the keyword
		assertEquals("x", doc.selectFirst("#s>:nth-child(1 of [id], [data-x='of b'])").id());
		// y is globally the 2nd child but the 1st (and only) i: filtering changes the position
		assertEquals("y", doc.selectFirst("#s>:nth-child(1 of i)").id());
		assertTrue(doc.select("#s>:nth-child(2 of i)").isEmpty());
		assertTrue(doc.select("#s>:nth-child(2 of em)").isEmpty()); // em is 4th of all, no 2nd em
	}

	@Test
	public void nthChildOfSelectorPerParent() {
		// counting restarts for each parent
		Document doc = Jsoup.parse(
			"<div id=p1><b id=a1></b><i></i><b id=a2></b><b id=a3></b></div>" +
			"<div id=p2><b id=b1></b><b id=b2></b></div>");

		assertEquals("a1,b1", ids(doc, ":nth-child(1 of b)"));
		assertEquals("a3,b2", ids(doc, ":nth-last-child(1 of b)"));
		assertEquals("a2,b2", ids(doc, "div>:nth-child(2 of b)"));
	}

	@Test
	public void nthChildOfSelectorDetails() {
		// combinators and nested pseudos inside S, counted per parent against parsed DOM values
		Document doc = Jsoup.parse(
			"<body><div id=p1><b id=a1><span></span></b><i></i><b id=a2></b></div>" +
			"<div id=p2><b id=b1></b><b id=b2><span></span></b></div></body>");

		assertEquals("a1,b2", ids(doc, ":nth-child(1 of b:has(span))"));
		assertEquals("a2", ids(doc, ":nth-child(1 of i + b)"));
		assertEquals("a2", ids(doc, ":nth-child(1 of i ~ b)"));
		assertEquals("a1,b1", ids(doc, "div>:nth-child(1 of div > b)"));
	}

	@Test
	public void nthChildOfSelectorFiltersAndCounts() {
		Document doc = Jsoup.parse(
			"<div id=s><b id=x class='c1'>a</b><i id=y>b</i><b id=z class='c2'>c</b><em>d</em><b id=w>e</b></div>");

		// a candidate must itself match S
		assertEquals("y", ids(doc, "#s>:nth-child(1 of i)"));
		assertTrue(doc.select("#s>:nth-child(2 of i)").isEmpty());
		// class / attribute / :not / :is / nested nth in S
		assertEquals("x", ids(doc, "#s>:nth-child(1 of b.c1)"));
		assertEquals("w", ids(doc, "#s>:nth-child(2 of b:not(.c2))")); // matching b's: x(c1), w(no class)
		assertEquals("z", ids(doc, "#s>:nth-child(2 of [class])")); // x and z carry a class
		assertEquals("x", ids(doc, "#s>:nth-child(1 of b:is(.c1))"));
		// b's at odd global child positions are x(1), z(3), w(5); filtered order x, z, w
		assertEquals("z", ids(doc, "#s>:nth-child(2 of b:nth-child(odd))"));
		// comma inside a nested :is() must not split the S list
		assertEquals("y", ids(doc, "#s>:nth-child(2 of :is(b, i))"));
		// duplicate S branches count each element only once
		assertEquals("z", ids(doc, "#s>:nth-child(2 of b,b)"));
		assertEquals("w", ids(doc, "#s>:nth-child(3 of b, b)"));
		// nth-last counts from the end but results come back in document order
		assertEquals("x,w", ids(doc, "#s>:nth-last-child(odd of b)")); // from end: w=1, z=2, x=3
		// the universal selector as S is equivalent to no filter
		assertEquals(ids(doc, "#s>:nth-child(2)"), ids(doc, "#s>:nth-child(2 of *)"));
	}

	@Test
	public void nthChildOfSelectorDedupAndOrder() {
		// overlapping outer branches hit elements at most once, in document order
		Document doc = Jsoup.parse(
			"<div id=s><b id=x>a</b><i id=y>b</i><b id=z>c</b><em>d</em><b id=w>e</b></div>");

		assertEquals("x,z,w", ids(doc, "#s>:nth-child(1 of b), #s>b"));
		assertEquals("x,z,w", ids(doc, "#s>b, #s>:nth-child(2 of b)"));
		assertEquals("x,y", ids(doc, "#s>:nth-child(1 of b), #s>i"));
	}

	@Test
	public void nthChildOfSelectorReuseAcrossDocuments() {
		// a compiled evaluator holds no per-document state; positions are recomputed against each document's DOM
		Evaluator eval = QueryParser.parse(":nth-child(1 of b)");
		Document docA = Jsoup.parse("<div><b id=a1></b><b id=a2></b></div>");
		Document docB = Jsoup.parse("<div><i></i><b id=b1></b><b id=b2></b></div>");

		assertEquals("a1", String.join(",", docA.select(eval).eachAttr("id")));
		assertEquals("b1", String.join(",", docB.select(eval).eachAttr("id")));
		assertEquals("a1", String.join(",", docA.select(eval).eachAttr("id")));
		assertEquals("b1", String.join(",", docB.select(eval).eachAttr("id")));
	}

	@Test
	public void nthChildOfSelectorStableAcrossSerialize() {
		// serializing and re-parsing, then running the same selector, yields the same elements, text, order, and dedup
		String css = "#s>:nth-child(odd of b), #s>:nth-last-child(2 of b)";
		Document doc = Jsoup.parse(
			"<div id=s><b id=x>a</b><i id=y>b</b><b id=z>c</b><em>d</em><b id=w>e</b></div>");
		Document reparsed = Jsoup.parse(doc.html());

		assertEquals(signature(doc, css), signature(reparsed, css));
	}

	private static java.util.List<String> signature(Document doc, String css) {
		java.util.ArrayList<String> out = new java.util.ArrayList<>();
		for (org.jsoup.nodes.Element el : doc.select(css))
			out.add(el.tagName() + "#" + el.id() + ":" + el.text() + ":" + el.parent().id());
		return out;
	}

	@Test
	public void nthChildOfSelectorEmptyAndNoMatch() {
		Document empty = Jsoup.parse("");
		assertTrue(empty.select(":nth-child(1 of b)").isEmpty());
		assertNull(empty.selectFirst(":nth-child(1 of b)"));

		Document doc = Jsoup.parse("<div id=s><b id=x></b><b id=z></b></div>");
		assertTrue(doc.select(":nth-child(99 of b)").isEmpty());
		assertTrue(doc.select("table>:nth-child(1 of tr)").isEmpty());
		// queries never create or delete nodes
		String html = doc.html();
		doc.select(":nth-child(1 of b)");
		doc.select(":nth-last-child(2 of b), b");
		assertEquals(html, doc.html());
	}

	private static String ids(Document doc, String css) {
		return String.join(",", doc.select(css).eachAttr("id"));
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

}
