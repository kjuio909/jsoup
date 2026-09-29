package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seeded fuzz regression for stylesheet cleaning invariants: no exceptions on degenerate CSS, the output is a fixed
 * point of re-cleaning, rejected references never appear, surviving elements keep order, the input document is never
 * modified, and a document the caller parsed (TextNode stylesheet text) is handled like an HTML-parsed one.
 */
public class StyleFuzzTest {
    private static final String Base = "https://example.com/a/b";
    private static final String MARK = "zzxmark"; // an unconfigured scheme -> always rejected as a real reference

    private static Safelist withStyle() {
        return Safelist.relaxed().addTags("style").addAttributes(":all", "style").addAttributes(":all", "id");
    }

    private static final String[] atoms = {
        "p", "div", "a:hover", "*", ".c", "#id", "b > i", " ", "  ", "\t", "\n",
        ";", ";;", "{", "}", "{}", "(", ")", "()",
        "'", "\"", "'abc'", "\"x;y\"", "'unclosed", "\"unterm",
        "/**/", "/*x*/", "/* never closed",
        "color:red", "color : red ;", "margin:0", "font:12px 'Arial'",
        "background:url(https://example.com/a.png)", "background:url(/x.png)",
        "background:url(x.png)", "background:url(MARK:1)",
        "background:url(javascript:alert(1))", "background:url(//cdn/x.png)",
        "background:url(\\6d ark:1)", "background:url(&#106;avascript:1)",
        "background:url(&#109;ark:7)", "background:url(%6d%61rk:8)", "background:url(&#1;mark:9)",
        "background:var(--x, url(MARK:2))",
        "url(https://example.com/b.png)", "url(MARK:3)",
        "expression(alert(1))", "EXPRESSION(1)",
        "@import url(https://example.com/x.css)", "@import 'MARK:4'", "@import",
        "@import url(MARK:10) layer;", "@import url(https://example.com/z.css) supports(not MARK:11);",
        "@media screen", "@supports (display:grid)", "@font-face", "@charset \"utf-8\"",
        "--v: 1", "--u: url(MARK:5)", "--safe: https://example.com/y.png",
        "\\", "\\\\", "\\75 ", "x\\;", "red\\", "/*a*/",
        "behavior:url(https://example.com/x.htc)", "url(data:image/png;base64,iVBORw0KGgo=)",
        "calc(1px + 2px)", "blur(url(MARK:6))", "@", "@x", "@}@",
        "{{{", "}}}", "} {", "url(oops", "url(oops;x",
        "background:url(\u0000javascript:1)", "background:url(java­script:1)",
        "background:url(JAVAİSCRIPT:1)", "background:url(\tjavascript:1)",
        "background:url(\\/\\/cdn.example.com/x.png)", "background:url(%2f%2fcdn/x.png)",
        "background:url(%5c%5ccdn/x.png)", "background:url(javascript%3a1)",
        "@import \"MARK:12\"", "@import url(MARK:13) screen and (min-width:1px);",
        "p{color:red}\r\nb{color:blue}", "p{font-family:'a\r\nb'}",
    };

    @Test void fuzzCallerBuiltDocuments() {
        // documents the caller parsed with the XML parser (stylesheet text arrives as TextNodes, entities decoded)
        Random rnd = new Random(2024);
        Safelist sl = withStyle();
        Cleaner cleaner = new Cleaner(sl);
        Cleaner strict = new Cleaner(Safelist.relaxed());
        for (int iter = 0; iter < 8000; iter++) {
            String css = newBuilder(rnd).toString();
            String html = "<html><body><style>" + css + "</style><p>n</p></body></html>";
            Document xml = Jsoup.parse(html, Base, org.jsoup.parser.Parser.xmlParser());
            String before = xml.html();
            Document clean = cleaner.clean(xml);
            assertEquals(before, xml.html(), "xml input mutated: " + html);
            String out = clean.body().html();
            assertFalse(out.contains(MARK), "marker leaked via xml: " + html + " -> " + out);
            assertEquals(out, cleaner.clean(clean).body().html(), "xml-derived output not stable: " + html + " -> " + out);
            for (Element s : clean.select("style"))
                assertFalse(s.data().trim().isEmpty(), "placeholder from xml: " + html);

            // disallowed policy: whole element gone, no text transferred to the parent
            Document gone = strict.clean(Jsoup.parse(html, Base, org.jsoup.parser.Parser.xmlParser()));
            assertTrue(gone.select("style").isEmpty(), "style survived disallowed: " + html);
            assertFalse(gone.body().html().contains(MARK), "marker leaked after removal: " + html);
            assertTrue(gone.body().html().contains("n"), "sibling lost for " + html);
        }
    }


    @Test void fuzz() {
        Random rnd = new Random(Long.parseLong(System.getProperty("seed","42")));
        Safelist sl = withStyle();
        Cleaner cleaner = new Cleaner(sl);
        for (int iter = 0; iter < 20000; iter++) {
            StringBuilder css = newBuilder(rnd);
            String html;
            if ((iter & 3) == 0) { // interleave sibling elements to test stability
                int chunks = 1 + rnd.nextInt(4);
                StringBuilder h = new StringBuilder();
                int styleId = 0;
                for (int c = 0; c < chunks; c++) {
                    if (rnd.nextBoolean()) {
                        h.append("<style id=s").append(styleId++).append(">").append(newBuilder(rnd)).append("</style>");
                    }
                    h.append("<p>").append(c).append("</p>");
                }
                html = h.toString();
            } else {
                html = "<style>" + css + "</style><p>z</p>";
            }

            Document dirty = Jsoup.parseBodyFragment(html, Base);
            String before = dirty.body().html();
            Document clean;
            try {
                clean = cleaner.clean(dirty);
            } catch (RuntimeException e) {
                throw new AssertionError("threw on input: " + html, e);
            }
            // input isolation
            assertEquals(before, dirty.body().html(), "input mutated by " + html);

            String out = clean.body().html();
            // the cleaned document must validate under the same policy (parsed again against the same base URI)
            assertTrue(cleaner.isValid(Jsoup.parseBodyFragment(out, Base)),
                "cleaned output does not validate: " + html + " -> " + out);

            // fixed point: re-cleaning the cleaned document is stable
            Document twice = cleaner.clean(clean);
            assertEquals(out, twice.body().html(), "not idempotent: " + html + " -> " + out);
            Document thrice = cleaner.clean(twice);
            assertEquals(out, thrice.body().html(), "not idempotent(2): " + html);

            // every surviving style element's text must itself be a fixed point of the sheet cleaner and never empty
            for (Element style : clean.select("style")) {
                String data = style.data();
                assertFalse(data.trim().isEmpty(), "empty/whitespace-only placeholder style from " + html);
                Document rec = cleaner.clean(Jsoup.parseBodyFragment("<style>" + data + "</style>", Base));
                Element rs = rec.selectFirst("style");
                if (rs == null) fail("surviving sheet disappears on re-clean: [" + data + "] from " + html);
                else assertEquals(data, rs.data(), "sheet text not stable: [" + data + "] from " + html);
            }

            if (html.contains("id=s")) {
                // interleaved case: p nodes keep order; surviving style elements keep their input id order
                List<String> gotP = new ArrayList<>();
                List<Integer> gotStyleIds = new ArrayList<>();
                for (Element el : clean.body().children()) {
                    if (el.normalName().equals("style")) gotStyleIds.add(Integer.parseInt(el.id().substring(1)));
                    else if (el.normalName().equals("p")) gotP.add(el.text());
                }
                List<String> wantP = new ArrayList<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<p>(\\d+)</p>").matcher(html);
                while (m.find()) wantP.add(m.group(1));
                assertEquals(wantP, gotP, "sibling order broken for " + html);
                for (int k = 1; k < gotStyleIds.size(); k++)
                    assertTrue(gotStyleIds.get(k - 1) < gotStyleIds.get(k),
                        "style element order changed for " + html + " -> " + gotStyleIds);
            }
        }
    }

    private static StringBuilder newBuilder(Random rnd) {
        int n = rnd.nextInt(14);
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < n; i++) css.append(atoms[rnd.nextInt(atoms.length)]);
        return css;
    }

    @Test void disallowedPolicyRemovesEveryStyleElement() {
        Random rnd = new Random(7);
        Cleaner cleaner = new Cleaner(Safelist.relaxed());
        for (int iter = 0; iter < 5000; iter++) {
            String html = "<div><style>" + newBuilder(rnd) + "</style><p>x</p></div>";
            Document clean = cleaner.clean(Jsoup.parseBodyFragment(html, Base));
            assertTrue(clean.select("style").isEmpty(), "style survived: " + html);
            String out = clean.body().html();
            assertFalse(out.contains(MARK), "marker leaked: " + html + " -> " + out);
            // no orphan data text: the p node is the only content
            assertEquals("x", clean.body().selectFirst("p").text());
        }
    }
}
