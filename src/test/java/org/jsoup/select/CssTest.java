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
	public void nthChildOfComplexSelector() {
		// S may use types, classes, attributes, negation, combinators and nested pseudos; positions are computed
		// against the parsed DOM
		Document doc = Jsoup.parse(
			"<div id=s>" +
			"<b id=a class=x title=q>a</b>" +
			"<i id=b>b</i>" +
			"<b id=c>c</b>" +
			"<b id=d class=x title=r>d</b>" +
			"<i id=e class=z>e</i>" +
			"<b id=f>f</b>" +
			"</div>");

		assertEquals("a", doc.selectFirst("#s>:nth-child(odd of b.x)").id()); // class filter, 1st of 2
		assertEquals("d", doc.selectFirst("#s>:nth-child(2 of b[title])").id()); // attribute presence
		assertEquals("b", doc.selectFirst("#s>:nth-child(2 of b, i)").id()); // selector list counted in doc order
		assertEquals("c", doc.selectFirst("#s>:nth-child(1 of b:not(.x))").id()); // negation
		assertEquals("f", doc.selectFirst("#s>:nth-child(1 of div b:last-of-type)").id()); // combinator + nested
		// duplicate S branches count an element only once
		assertEquals("a", doc.selectFirst("#s>:nth-child(1 of b, b, b)").id());
		// a candidate is returned only when it itself matches S
		assertTrue(doc.select("#s>:nth-child(1 of i)").stream().noneMatch(el -> el.normalName().equals("b")));
	}

	@Test
	public void nthChildOfCommasAreLayered() {
		// commas nested inside S (quoted attribute values, functional pseudos, :is()) must not terminate S;
		// only the outer comma splits the query
		Document doc = Jsoup.parse(
			"<div id=s><b id=x title='p,q'>1</b><i id=y>2</i><b id=z title='p,q'>3</b></div>");

		assertEquals("z", doc.selectFirst("#s>:nth-child(2 of b[title='p,q'])").id());
		assertEquals("z", doc.selectFirst("#s>:nth-child(2 of b:is([title], .x))").id());
		// S's two top-level branches are b:is(.a,.b) and i; the comma inside :is() is not an S separator
		Document isDoc = Jsoup.parse(
			"<div id=s><b id=x class=a>1</b><i id=y>2</i><b id=z class=b>3</b></div>");
		assertEquals("z", isDoc.selectFirst("#s>:nth-child(3 of b:is(.a,.b), i)").id());
		// an outer comma after the of clause starts a fresh outer branch
		assertEquals("y,z", ids(doc, ":nth-child(2 of b), i"));
	}

	@Test
	public void nthChildOfOuterBranchesDedupAndOrder() {
		// multiple outer branches hitting the same element return it once, in document order
		Document doc = Jsoup.parse("<div id=s><b id=x>1</b><i id=y>2</i><b id=z>3</b></div>");

		assertEquals("x,y,z", ids(doc, "#s>:nth-child(odd of b), b, #s>*"));
		assertEquals("x,y", ids(doc, "i, :nth-child(1 of b)"));
	}

	@Test
	public void nthChildOfRoundTripAndIsolation() {
		// serialize + reparse, then run the same selector: equivalent elements, order and dedup
		String css = ":nth-child(2 of b, i)";
		Document doc = Jsoup.parse("<div><b>1</b><i>2</i><b>3</b><i>4</i><b>5</b></div>");
		Document reparse = Jsoup.parse(doc.html());
		assertEquals(doc.select(css).eachText(), reparse.select(css).eachText());
		assertEquals("2", String.join(",", doc.select(css).eachText()));

		// consecutive calls on different documents do not share parsing or counting state
		Document d1 = Jsoup.parse("<div><b>1</b><b>2</b></div>");
		Document d2 = Jsoup.parse("<div><b>a</b><b>b</b><b>c</b></div>");
		assertEquals("2", String.join(",", d1.select(":nth-child(2 of b)").eachText()));
		assertEquals("b", String.join(",", d2.select(":nth-child(2 of b)").eachText()));
		assertEquals("2", String.join(",", d1.select(":nth-child(2 of b)").eachText()));

		// a parsed evaluator reused across documents is likewise isolated
		Evaluator eval = QueryParser.parse(":nth-child(2 of b)");
		assertEquals("2", String.join(",", d1.select(eval).eachText()));
		assertEquals("b", String.join(",", d2.select(eval).eachText()));
		assertEquals("2", String.join(",", d1.select(eval).eachText()));

		// empty documents and no-match queries give an empty result rather than throwing
		assertTrue(Jsoup.parse("").select(":nth-child(1 of b)").isEmpty());
		assertTrue(Jsoup.parse("<p>x</p>").select(":nth-child(1 of b)").isEmpty());
	}

	@Test
	public void nthChildOfQueryDoesNotMutateDom() {
		Document doc = Jsoup.parse("<div id=s><b>1</b><i>2</i><b>3</b></div>");
		String before = doc.toString();
		doc.select(":nth-child(2 of b, i):nth-last-child(-n+2 of b)");
		assertEquals(before, doc.toString()); // parent-child and sibling order are unchanged
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
