package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for rule-by-rule sanitization of an allowed {@code <style>} element's stylesheet text by the {@link Cleaner}
 and {@link StyleSheetCleaner}.
 */
public class StyleSheetCleanerTest {
    private static final String Base = "https://example.com/a/b";

    private static Safelist sheetSafelist() {
        return Safelist.relaxed().addTags("style").addAttributes(":all", "style");
    }

    private static Document cleanDoc(String html, String baseUri, Safelist safelist) {
        return new Cleaner(safelist).clean(Jsoup.parseBodyFragment(html, baseUri));
    }

    private static String sheet(String css) {
        return sheet(css, Base, sheetSafelist());
    }

    private static String sheet(String css, String baseUri, Safelist safelist) {
        Element style = cleanDoc("<style>" + css + "</style>", baseUri, safelist).selectFirst("style");
        return style == null ? null : style.data();
    }

    private static String body(String css) {
        return cleanDoc("<style>" + css + "</style><p>x</p>", Base, sheetSafelist()).body().html();
    }

    @Test void keepsOrdinaryRulesAndDeclarationsInOrder() {
        assertEquals("a { color: red; FONT-SIZE :14px;margin:0} b{color:#fff}",
            sheet("a { color: red; FONT-SIZE :14px ;margin:0} b{color:#fff}"));
        // selectors, colors, numbers, fonts, and custom properties keep their source spelling
        assertEquals("*[data-x]:hover > .a#b + p { --v: calc(1px + var(--y)); font: 12px 'Arial'}",
            sheet("*[data-x]:hover > .a#b + p { --v: calc(1px + var(--y)); font: 12px 'Arial' }"));
    }

    @Test void duplicateRulesAndDeclarationsAreStable() {
        assertEquals("p{color:red} p{color:red}", sheet("p{color:red} p{color:red}"));
        assertEquals("p{color:red;color:red}", sheet("p{color:red;color:red}"));
    }

    @Test void dropsOnlyTheDangerousDeclaration() {
        assertEquals("a { color: red; margin: 0} b{font:12px 'x;y'}",
            sheet("a { color: red; background: url(javascript:1); margin: 0 } b{font:12px 'x;y'}"));
        assertEquals("p{color:red;color:blue}",
            sheet("p{background:url(javascript:alert(1));color:red;color:blue}"));
    }

    @Test void removesStyleElementWhenEveryRuleIsRejected() {
        assertNull(sheet("p{background:url(javascript:1)}"));
        assertNull(sheet("hello")); // non-CSS text is not a rule
        assertEquals("<p>x</p>", org.jsoup.TextUtil.stripNewlines(body("p{background:url(javascript:1)}")));
    }

    @Test void removesWhitespaceCommentOrEmptyOnlyStylesheet() {
        // whitespace, comments, empty rules, or a wholly empty element carry no complete safe rule or declaration,
        // so the whole <style> element (its text and attributes) is removed, leaving no empty placeholder
        assertNull(sheet("  \n "));
        assertNull(sheet(""));
        assertNull(sheet("/* only a comment */"));
        assertNull(sheet("p{}"));
        assertEquals("<p>x</p>", org.jsoup.TextUtil.stripNewlines(body("  \n ")));
        assertEquals("<p>x</p>", org.jsoup.TextUtil.stripNewlines(body("/* c */")));
        Document empty = cleanDoc("<style></style><p>x</p>", Base, sheetSafelist());
        assertNull(empty.selectFirst("style"));
        assertEquals("<p>x</p>", org.jsoup.TextUtil.stripNewlines(empty.body().html()));
    }

    @Test void policyDisallowingStyleStillRemovesWholeElement() {
        String html = "<style>p{color:red}</style><p>x</p>";
        Document clean = new Cleaner(Safelist.relaxed()).clean(Jsoup.parseBodyFragment(html, Base));
        assertNull(clean.selectFirst("style"));
        assertFalse(Jsoup.isValid(html, Safelist.relaxed()));
    }

    @Test void importReferencesAreChecked() {
        assertEquals("@import url(https://example.com/x.css); p{color:red}",
            sheet("@import url(https://example.com/x.css); p{color:red}"));
        assertEquals("@import \"https://example.com/x.css\";p{color:red}",
            sheet("@import \"https://example.com/x.css\";p{color:red}"));
        // the rejected import alone is removed; the following rule (with its source spacing) is untouched
        assertEquals(" p{color:red}", sheet("@import 'javascript:alert(1)'; p{color:red}"));
        assertNull(sheet("@import url(javascript:alert(1))"));
        // a truncated or reference-less import is dropped, not kept
        assertNull(sheet("@import"));
        assertEquals("p{color:red}", sheet("@import;p{color:red}"));
    }

    @Test void otherAtRulesKeepSafePreludes() {
        assertEquals("@charset \"utf-8\";p{color:red}", sheet("@charset \"utf-8\";p{color:red}"));
        assertEquals("@namespace url(https://www.w3.org/1999/xhtml);p{color:red}",
            sheet("@namespace url(https://www.w3.org/1999/xhtml);p{color:red}"));
        // a namespace carrying a script reference is dropped alone
        assertEquals(" p{color:red}", sheet("@namespace url(javascript:1); p{color:red}"));
    }

    @Test void nestedBlocksAreHandledIndependently() {
        assertEquals("@media screen { p { color:red;} img { width: 1px}}",
            sheet("@media screen { p { color:red; background: url(javascript:1) } img { width: 1px } }"));
        assertEquals("@supports (display: grid) { p { color:red;}}",
            sheet("@supports (display: grid) { p { color:red; background: url(javascript:1) } }"));
        // an empty at-rule block after all its rules are dropped leaves no empty rule
        assertEquals(" p{color:red}",
            sheet("@media screen { p { background: url(javascript:1) } } p{color:red}"));
        // at-rule declarations (@font-face) validate declaration by declaration
        assertEquals("@font-face { font-family: x;} p{color:red}",
            sheet("@font-face { font-family: x; src: url(javascript:1); } p{color:red}"));
        assertEquals("@font-face { font-family: x; src: url(https://example.com/f.woff)}",
            sheet("@font-face { font-family: x; src: url(https://example.com/f.woff) }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "url(javascript:alert(1))",
        "URL(JAVASCRIPT:alert(1))",
        "url(java%73cript:alert(1))",
        "url(%6Aavascript:alert(1))",
        "url(&#1;javascript:alert(1))", // HTML entity decoded to a control character for the check only
        "url(java\\73 cript:alert(1))",
        "url(\\6a avascript:alert(1))",
        "url(vbscript:msgbox(1))",
        "\\75 rl(javascript:alert(1))", // escaped url() function name
        "expression(alert(1))",
        "EXPRESSION/**/(alert(1))",
        "var(--x, url(javascript:1))", // nested inside another function
        "blur(url(javascript:1))",
    })
    void rejectsDangerousReferences(String value) {
        assertEquals("p{color:red}", sheet("p{background:" + value + ";color:red}"), value);
        assertNull(sheet("p{background:" + value + "}"), value);
    }

    @Test void rejectsDangerousPropertiesEverywhere() {
        assertEquals("p{color:red}", sheet("p{behavior:url(https://example.com/x.htc);color:red}"));
        assertEquals("p{color:red}", sheet("p{-moz-binding:url(https://example.com/x.xml);color:red}"));
        assertEquals("@font-face { font-family: x;}",
            sheet("@font-face { font-family: x; behavior: url(https://example.com/x.htc) }"));
    }

    @Test void entityObfuscationDecodesForTheCheckButKeepsSourceSpelling() {
        // RAWTEXT keeps the entity raw; the check decodes it, so the concealed script scheme is still rejected
        assertEquals("p{color:red}", sheet("p{background:url(&#106;avascript:1);color:red}"));
        assertEquals("@media screen{p{color:red}}",
            sheet("@import url(&#106;avascript:1);@media screen{p{color:red}}"));
        // an entity cannot forge a block boundary: &#123; is not a real '{'
        assertEquals("p{color:red}", sheet("p{color:red}&#123;@import url(javascript:1)"));
        // a surviving reference keeps its original (entity-bearing) spelling verbatim
        assertEquals("p{background:url(https://example.com/x.png?a=1&amp;b=2)}",
            sheet("p{background:url(https://example.com/x.png?a=1&amp;b=2)}"));
    }

    @Test void fakeReferencesInStringsAndCommentsAreInert() {
        assertEquals("p{content:'url(javascript:1)'}", sheet("p{content:'url(javascript:1)'}"));
        assertEquals("p{color:red/* url(javascript:1) */;color:blue}",
            sheet("p{color:red/* url(javascript:1) */;color:blue}"));
        // a real url() call with a quoted argument is still checked
        assertNull(sheet("p{background:url('javascript:1')}"));
        assertEquals("p{background:url(\"https://example.com/x.png\")}",
            sheet("p{background:url(\"https://example.com/x.png\")}"));
    }

    @Test void safeAbsoluteAndRelativeReferencesResolveAgainstBase() {
        assertEquals("p{background:url(https://example.com/x.png)}",
            sheet("p{background:url(https://example.com/x.png)}"));
        assertEquals("p{background:url(img/x.png)}", sheet("p{background:url(img/x.png)}"));
        // same-document fragment references work without a base URI too
        assertEquals("p{clip-path:url(#clip)}", sheet("p{clip-path:url(#clip)}", "", sheetSafelist()));
        // a relative reference with no base URI is unresolvable and rejected; the safe declaration stays
        assertEquals("p{color:red}", sheet("p{background:url(img/x.png);color:red}", "", sheetSafelist()));
    }

    @Test void protocolRelativeReferencesAreRejected() {
        assertNull(sheet("p{background:url(//cdn.example.com/x.png)}"));
        assertEquals("p{color:red}", sheet("p{background:url(//cdn.example.com/x.png);color:red}"));
        assertEquals("p{color:red}", sheet("@import '//cdn.example.com/x.css';p{color:red}"));
        // a single leading slash stays a root-relative, same-origin reference
        assertEquals("p{background:url(/x.png)}", sheet("p{background:url(/x.png)}"));
    }

    @Test void unknownAndDataProtocolsFollowThePolicy() {
        assertEquals("p{color:red}", sheet("p{background:url(foo:bar);color:red}"));
        assertNull(sheet("p{background:url(data:image/png;base64,iVBORw0KGgo=)}"));
        Safelist withData = sheetSafelist().addProtocols(":all", "style", "data");
        assertEquals("p{background:url(data:image/png;base64,iVBORw0KGgo=)}",
            sheet("p{background:url(data:image/png;base64,iVBORw0KGgo=)}", Base, withData));
        assertNull(sheet("p{background:url(data:image/svg+xml;base64,PHNjcmlwdD4=)}", Base, withData));
    }

    @Test void semicolonInsideAFunctionStaysPartOfItsDeclaration() {
        // an unquoted data: URL carries a ';' that must not split the declaration
        Safelist withData = sheetSafelist().addProtocols(":all", "style", "data");
        assertEquals("p{background:url(data:text/plain;base64,aGVsbG8=);color:red}",
            sheet("p{background:url(data:text/plain;base64,aGVsbG8=);color:red}", Base, withData));
        assertEquals("p{cursor:url(foo;bar.cur), auto}",
            sheet("p{cursor:url(foo;bar.cur), auto}", Base, sheetSafelist().addProtocols(":all", "style", "https")));
    }

    @Test void malformedInputNeverThrowsAndBoundedRulesStillProcess() {
        // repeated separators only drop their empty fragments
        assertEquals("p { color:red; margin:0}", sheet("p { color:red;;; ; margin:0 }"));
        // an unclosed function invalidates only its declaration
        assertEquals("p{color:red} b{color:blue}", sheet("p{background:url(oops;color:red} b{color:blue}"));
        // an unclosed string recovers at the next ';' and later declarations survive
        assertEquals("p {color:red} b{color:blue}", sheet("p { font-family:'oops;color:red } b{color:blue}"));
        // a stray close parenthesis drops only its declaration
        assertEquals("p{color:blue}", sheet("p{color:red);color:blue}"));
        // a stray close brace is ordinary junk and does not disturb the next rule
        assertEquals("p{color:red} b{color:blue}", sheet("p{color:red}} b{color:blue}"));
        // a complete rule before an unbounded tail still survives; the tail is discarded
        assertEquals("p{color:red}", sheet("p{color:red} /* never closed"));
        // a complete declaration before an unterminated comment still has a determinable boundary and survives; the
        // comment and the rules swallowed inside it (no real '}' follows) are discarded and the block is closed
        assertEquals("p { color:red}", sheet("p { color:red /* never closed; div { color:blue }"));
        // no exceptions on deeply degenerate input
        assertNull(sheet(";;;"));
        assertNull(sheet("{{{}}}"));
    }

    @Test void strayBraceDoesNotGlueStatements() {
        // a stray top-level '}' is a parse error: the unfinished statement before it is dropped, never glued onto
        // the next surviving statement (which would change its meaning and fail to re-clean)
        assertEquals("p{color:red}", sheet("@media screen}p{color:red}"));
        assertNull(sheet("@media screen}"));
        assertNull(sheet("@}@")); // a bare '@' on either side of the stray brace is not an at-rule
        assertEquals("@y", sheet("@x}@y")); // the well-formed at-keyword after the stray brace survives
        // the later bounded rule still parses independently, and an empty prelude/rule leaves nothing behind
        assertEquals("p{color:red}", sheet("@media screen}}p{color:red}"));
        assertEquals("q{color:blue}", sheet("a{}q{color:blue}"));
    }

    @Test void separatorAfterTrailingBackslashStaysSeparated() {
        // a surviving statement ending in an unescaped run of backslashes gets a space before the added ';', so the
        // semicolon cannot be CSS-escaped and glue this statement to the next one on a re-clean
        String once = sheet("p{color:red} x\\;@media screen { q{color:blue} }");
        assertEquals(once, sheet(once));
        // same guard for a rule whose lost closing brace had to be synthesized after a trailing backslash
        String unclosed = sheet("p { color:red\\");
        assertNotNull(unclosed);
        assertEquals(unclosed, sheet(unclosed));
        assertTrue(unclosed.endsWith("}}"));
    }

    @Test void multipleStyleSheetsAreIndependent() {
        String html = "<style>p{color:red;background:url(javascript:1)}</style>"
            + "<style>b{color:blue}</style><p>x</p>";
        Document clean = cleanDoc(html, Base, sheetSafelist());
        assertEquals(2, clean.select("style").size());
        assertEquals("p{color:red;}", clean.select("style").get(0).data());
        assertEquals("b{color:blue}", clean.select("style").get(1).data());
    }

    @Test void inlineStylesLinksAndResourcesKeepTheirExistingBehavior() {
        String html = "<style>p{color:red}</style>"
            + "<div style=\"color:red;background:url(javascript:1)\">d</div>"
            + "<img src=\"https://example.com/x.png\" alt=\"x\">";
        Document clean = cleanDoc(html, Base, sheetSafelist());
        assertEquals("p{color:red}", clean.selectFirst("style").data());
        assertEquals("color:red", clean.selectFirst("div").attr("style"));
        assertEquals("https://example.com/x.png", clean.selectFirst("img").attr("src"));
    }

    @Test void cleaningIsIdempotentAndStable() {
        Document dirty = Jsoup.parseBodyFragment(
            "<style>@import url(https://example.com/x.css); p { color:red; background: url(javascript:1); } "
                + "@media screen { b { width:1px; background: url(//cdn/x.png); } }</style>", Base);
        Cleaner cleaner = new Cleaner(sheetSafelist());
        Document once = cleaner.clean(dirty);
        Document twice = cleaner.clean(once);
        assertEquals(once.body().html(), twice.body().html());
        assertEquals(once.body().html(), cleaner.clean(twice).body().html());
        assertTrue(Cleaner.isValid(once.body().html(), sheetSafelist()));
    }

    @Test void aFullySafeStylesheetIsNotRewritten() {
        // a compact safe sheet passes through unchanged, so validation and cleaning agree
        String css = "p{color:red}@media screen{b{width:1px}}";
        assertEquals(css, sheet(css));
        assertTrue(Cleaner.isValid("<style>" + css + "</style>", sheetSafelist()));
    }

    @Test void cleaningDoesNotModifyInputDocument() {
        Document dirty = Jsoup.parseBodyFragment(
            "<style>p{color:red;background:url(javascript:1)}</style>"
                + "<div style=\"background:url(javascript:1)\">x</div>", Base);
        String before = dirty.body().html();
        String beforeData = dirty.selectFirst("style").data();
        String beforeStyle = dirty.selectFirst("div").attr("style");
        new Cleaner(sheetSafelist()).clean(dirty);
        new Cleaner(sheetSafelist()).clean(dirty);
        assertEquals(before, dirty.body().html());
        assertEquals(beforeData, dirty.selectFirst("style").data());
        assertEquals(beforeStyle, dirty.selectFirst("div").attr("style"));
    }

    @Test void rejectedUrlNeverAppearsInOutputOrTree() {
        Document clean = cleanDoc("<style>p{background:url(javascript:alert(1))}</style>", Base, sheetSafelist());
        assertFalse(clean.toString().contains("javascript"));
        clean.getAllElements().forEach(el -> assertFalse(el.data().contains("javascript")));
    }

    @Test void isValidTracksStylesheetRewrites() {
        assertTrue(Cleaner.isValid("<style>p{color:red}</style><p>x</p>", sheetSafelist()));
        assertFalse(Cleaner.isValid("<style>p{background:url(javascript:1)}</style>", sheetSafelist()));
        assertFalse(Cleaner.isValid("<style>@import url(javascript:1)</style>", sheetSafelist()));
    }

    @Test void nestingBeyondTheDepthBoundNeverThrows() {
        // pathological nesting is bounded rather than overflowing a small stack; the result is safe either way
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < 600; i++) css.append("@media screen{");
        css.append("p{color:red}");
        for (int i = 0; i < 600; i++) css.append("}");
        String out = sheet(css.toString());
        if (out != null) assertFalse(out.contains("javascript"));
    }

    @Test void cleansThroughPublicDocumentEntryPoint() {
        Document dirty = Jsoup.parseBodyFragment(
            "<div><style>p{color:red;background:url(javascript:1)}</style><p>x</p></div>", Base);
        Document clean = new Cleaner(sheetSafelist()).clean(dirty);
        Element style = clean.body().selectFirst("style");
        assertNotNull(style);
        assertEquals("p{color:red;}", style.data());
        // the cleaned document is fully isolated from the parsed input
        assertNotSame(dirty.body().selectFirst("style"), style);
    }

    @Test void callerParsedTextNodeStyleContentIsFilteredOrRemoved() {
        // a document the caller parsed (e.g. the XML parser) or built by hand carries stylesheet text as an ordinary
        // TextNode rather than the HTML parser's DataNode; it must be filtered just the same when style is allowed,
        // and never transferred to the parent when style is disallowed
        String html = "<html><body><style>p{color:red;background:url(javascript:1)}</style><p>x</p></body></html>";
        Document xml = Jsoup.parse(html, Base, org.jsoup.parser.Parser.xmlParser());
        String before = xml.body().html();
        Document clean = new Cleaner(sheetSafelist()).clean(xml);
        // the caller's parsed document is untouched
        assertEquals(before, xml.body().html());
        Element style = clean.selectFirst("style");
        assertNotNull(style);
        assertEquals("p{color:red;}", style.data());
        assertFalse(clean.body().html().contains("javascript"));

        Document gone = new Cleaner(Safelist.relaxed())
            .clean(Jsoup.parse(html, Base, org.jsoup.parser.Parser.xmlParser()));
        assertNull(gone.selectFirst("style"));
        assertEquals("x", gone.body().selectFirst("p").text());
        assertFalse(gone.body().html().contains("color:red"));
    }
}
