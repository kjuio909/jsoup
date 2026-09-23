package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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

	private static final String nthOfHtml =
		"<div id=s><b id=x>a</b><i id=y>b</i><b id=z>c</b><em>d</em><b id=w>e</b></div>";

	@Test
	public void nthChildOfSelector_examples() {
		Document doc = Jsoup.parse(nthOfHtml);

		Elements secondB = doc.select("#s>:nth-child(2 of b)");
		assertEquals(1, secondB.size());
		assertEquals("z", secondB.get(0).id());

		Elements lastBOrI = doc.select("#s>:nth-last-child(1 of b,i)");
		assertEquals(1, lastBOrI.size());
		assertEquals("w", lastBOrI.get(0).id());
	}

	@Test
	public void nthChildOfSelector_indexesOnlyMatchingSiblings() {
		Document doc = Jsoup.parse(nthOfHtml);

		assertEquals(Arrays.asList("x"), ids(doc, "#s>:nth-child(1 of b)"));
		assertEquals(Arrays.asList("z"), ids(doc, "#s>:nth-child(2 of b)"));
		assertEquals(Arrays.asList("w"), ids(doc, "#s>:nth-child(3 of b)"));
		assertEquals(Arrays.asList("x", "w"), ids(doc, "#s>:nth-child(odd of b)"));
		assertEquals(Arrays.asList("z"), ids(doc, "#s>:nth-child(even of b)"));
		assertEquals(Arrays.asList("x", "w"), ids(doc, "#s>:nth-child(2n+1 of b)"));

		assertEquals(Arrays.asList("w"), ids(doc, "#s>:nth-last-child(1 of b)"));
		assertEquals(Arrays.asList("z"), ids(doc, "#s>:nth-last-child(2 of b)"));
		assertEquals(Arrays.asList("x"), ids(doc, "#s>:nth-last-child(3 of b)"));
		assertEquals(Arrays.asList("x", "w"), ids(doc, "#s>:nth-last-child(odd of b)"));

		// selector list: b and i interleaved; em is skipped
		assertEquals(Arrays.asList("y"), ids(doc, "#s>:nth-child(2 of b,i)"));
		assertEquals(Arrays.asList("z"), ids(doc, "#s>:nth-last-child(2 of b,i)"));
		assertEquals(Arrays.asList("y", "w"), ids(doc, "#s>:nth-child(even of b,i)"));

		// non-matching candidate is never returned, regardless of raw sibling position
		assertTrue(ids(doc, "#s em:nth-child(4 of b)").isEmpty());
	}

	@Test
	public void nthChildOfSelector_compoundAndComplexS() {
		Document doc = Jsoup.parse(
			"<div id=s><b class=hit id=x>a</b><i>b</i><b id=z>c</b><b class=hit id=w>e</b></div>");

		assertEquals(Arrays.asList("w"), ids(doc, "#s>:nth-child(2 of b.hit)"));
		assertEquals(Arrays.asList("w"), ids(doc, "#s>:nth-last-child(1 of b.hit)"));

		// complex selector in S: candidate b must be a direct child of a section
		Document doc2 = Jsoup.parse(
			"<div><section><b id=x>1</b></section><section><b id=y>2</b><b id=z>3</b></section></div>");
		assertEquals(Arrays.asList("z"), ids(doc2, "div :nth-child(2 of section>b)"));
	}

	@Test
	public void nthChildOfSelector_resetsPerParent() {
		Document doc = Jsoup.parse(
			"<div><p><b id=a1>1</b><i>x</i><b id=a2>2</b></p>"
				+ "<p><b id=b1>3</b><b id=b2>4</b></p></div>");
		assertEquals(Arrays.asList("a2", "b2"), ids(doc, "div p>:nth-child(2 of b)"));
		assertEquals(Arrays.asList("a2", "b2"), ids(doc, "div p>:nth-last-child(1 of b)"));
	}

	@Test
	public void nthChildOfSelector_whitespace() {
		Document doc = Jsoup.parse(nthOfHtml);
		assertEquals(Arrays.asList("z"), ids(doc, "#s>:nth-child( 2 of b )"));
		assertEquals(Arrays.asList("y"), ids(doc, "#s>:nth-child(2  of  b , i )"));
	}

	@Test
	public void nthChildOfSelector_plainFormsUnchanged() {
		// no "of" clause: all element siblings are counted, as before
		assertEquals(Arrays.asList("y"), ids(Jsoup.parse(nthOfHtml), "#s>:nth-child(2)"));
		check(html.select("#pseudo :nth-child(odd)"), "1", "3", "5", "7", "9");
		check(html.select("#pseudo :nth-last-child(even)"), "1", "3", "5", "7", "9");
		check(html.select("#type p:nth-of-type(2n)"), "2", "4", "6", "8", "10");
		check(html.select("#type p:nth-last-of-type(2n)"), "1", "3", "5", "7", "9");
	}

	@Test
	public void nthChildOfSelector_parseErrors() {
		String[] invalid = {
			":nth-child(2 of )",     // empty S
			":nth-child(",           // unclosed parens
			":nth-child(2 of",       // unclosed after of
			":nth-child(2 of b",     // unclosed after selector
			":nth-child(2 of b,",    // truncated selector list
			":nth-child(2 of ,b)",   // empty first list item
			":nth-child(of b)",      // missing An+B
			":nth-child(2of b)",     // of must be whitespace delimited
			":nth-of-type(2 of b)",       // of not allowed for nth-of-type
			":nth-last-of-type(2 of b)",  // nor for nth-last-of-type
		};
		for (String q : invalid) {
			assertThrows(Selector.SelectorParseException.class, () -> QueryParser.parse(q),
				() -> "expected parse exception for " + q);
		}
	}

	@Test
	public void nthChildOfSelector_failureDoesNotChangeDom() {
		Document doc = Jsoup.parse(nthOfHtml);
		String before = doc.html();
		assertThrows(Selector.SelectorParseException.class, () -> doc.select("#s>:nth-child(2 of )"));
		assertEquals(before, doc.html());
	}

	private static java.util.List<String> ids(Document doc, String query) {
		return doc.select(query).eachAttr("id");
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
