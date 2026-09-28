package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for declaration-by-declaration sanitization of the inline {@code style} attribute by the {@link Cleaner} and
 {@link StyleCleaner}.
 */
public class StyleCleanerTest {
    private static final String Base = "https://example.com/a/b";

    private static Safelist styleSafelist() {
        return Safelist.relaxed().addAttributes(":all", "style");
    }

    private static String clean(String html) {
        return clean(html, Base, styleSafelist());
    }

    private static String clean(String html, String baseUri, Safelist safelist) {
        Document dirty = Jsoup.parseBodyFragment(html, baseUri);
        return new Cleaner(safelist).clean(dirty).body().html();
    }

    @Test void keepsOrdinaryDeclarationsInOrder() {
        String html = "<p style=\"color: red; FONT-SIZE :14px ;margin:0\">x</p>";
        // leading whitespace and declaration order are preserved; trailing padding before ';' is trimmed
        assertEquals("<p style=\"color: red; FONT-SIZE :14px;margin:0\">x</p>", clean(html));
    }

    @Test void keepsSemicolonInsideQuotedString() {
        String html = "<div style=\"font-family: 'Calibri;'\">Will (not) fail</div>";
        assertEquals(html, org.jsoup.TextUtil.stripNewlines(clean(html)));
    }

    @Test void keepsSemicolonInsideBalancedFunction() {
        String html = "<div style=\"cursor: url(foo;bar.cur), auto\">x</div>";
        assertEquals("<div style=\"cursor: url(foo;bar.cur), auto\">x</div>",
            clean(html, Base, styleSafelist().addProtocols(":all", "style", "https")));
    }

    @Test void dropsOnlyDangerousDeclaration() {
        String html = "<div style=\"color:red;background:url(javascript:alert(1));color:blue\">x</div>";
        assertEquals("<div style=\"color:red;color:blue\">x</div>", clean(html));
    }

    @Test void removesAttributeWhenAllDeclarationsRejected() {
        assertEquals("<div>x</div>", clean("<div style=\"background:url(javascript:alert(1))\">x</div>"));
        assertEquals("<div>x</div>", clean("<div style=\"@import 'javascript:alert(1)'\">x</div>"));
    }

    @Test void doesNotRemoveStyleWhenPolicyAllowsItButItHasNoReferences() {
        assertEquals("<div style=\"color:red\"></div>", clean("<div style=\"color:red\"></div>"));
    }

    @Test void policyDisallowingStyleStillRemovesWholeAttribute() {
        String html = "<div style=\"color:red\">x</div>";
        assertEquals("<div>x</div>", Jsoup.clean(html, Safelist.relaxed()));
        assertFalse(Jsoup.isValid(html, Safelist.relaxed()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "url(javascript:alert(1))",
        "URL(JAVASCRIPT:alert(1))",
        "url(&#1;javascript:alert(1))", // HTML entity decoded to a control character by the parser
        "url(java%73cript:alert(1))",
        "url(%6Aavascript:alert(1))",
        "url(java\\73 cript:alert(1))",
        "url(\\6a avascript:alert(1))",
        "url(  javascript:alert(1)  )",
        "url(&quot;javascript:alert(1)&quot;)",
        "url(&#39;javascript:alert(1)&#39;)",
        "url(vbscript:msgbox(1))",
        "url(VBSCRIPT:msgbox(1))",
        "expression(alert(1))",
        "EXPRESSION/**/(alert(1))",
    })
    void rejectsDangerousReferences(String value) {
        String html = "<div style=\"background:" + value + ";color:red\">x</div>";
        assertEquals("<div style=\"color:red\">x</div>", clean(html), value);
    }

    @Test void dangerousSchemeAsOnlyDeclarationRemovesAttribute() {
        assertEquals("<div>x</div>", clean("<div style=\"background:url(javascript:alert(1))\">x</div>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "url(javascript:1)",
        "\\75 rl(javascript:1)", // hex escape with its whitespace delimiter
        "\\75rl(javascript:1)", // hex escape glued to the rest of the name
        "u\\72 l(javascript:1)",
        "ur\\6c (javascript:1)",
        "\\75\\72\\6c(javascript:1)", // every letter hex escaped, no delimiters
        "\\000075 rl(javascript:1)", // six-digit hex run
        "\\75\trl(javascript:1)", // a tab is a valid hex-escape delimiter
        "\\75\nrl(javascript:1)", // a newline is a valid hex-escape delimiter (normalized CRLF by the parser)
        "\\u\\r\\l(javascript:1)", // single-character escapes
        "\\uRL(javascript:1)", // character escape plus an upper-case remainder
        "\\55 RL(JAVASCRIPT:1)", // hex + case variant
        "\\55\\52\\4c(javascript:1)", // URL fully hex escaped
        "\\\\75rl(javascript:1)", // an escaped backslash glued into the run makes a name containing '\', rejected
        "\\\\\\75 rl(javascript:1)", // escaped backslash followed by a real escaped "url" call is still one call
        "var(--x, \\75 rl(javascript:1))", // nested inside another function's arguments
        "calc(1px + \\75 rl(javascript:1))", // nested after operators
    })
    void rejectsEscapedUrlFunctionNames(String fn) {
        String html = "<div style=\"background:" + fn + ";color:red\">x</div>";
        assertEquals("<div style=\"color:red\">x</div>", clean(html), fn);
        // and when it is the only declaration, the whole attribute goes
        assertEquals("<div>x</div>", clean("<div style=\"background:" + fn + "\">x</div>"), fn);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\\65 xpression(alert(1))",
        "expr\\65 ssion(alert(1))",
        "\\65\\78\\70\\72\\65\\73\\73\\69\\6f\\6e(alert(1))",
        "\\65XPRESSION(alert(1))",
        "var(--x, \\65 xpression(alert(1)))",
    })
    void rejectsEscapedExpressionFunctionNames(String fn) {
        String html = "<div style=\"width:" + fn + ";color:red\">x</div>";
        assertEquals("<div style=\"color:red\">x</div>", clean(html), fn);
    }

    @Test void escapedNameOfASafeReferenceHasTheSameOutcomeAsPlainSpelling() {
        // an escaped url() whose reference passes the URL rules is kept verbatim, just like the plain spelling
        assertEquals("<div style=\"background:\\75 rl(https://example.com/x.png);color:red\">x</div>",
            clean("<div style=\"background:\\75 rl(https://example.com/x.png);color:red\">x</div>"));
        assertEquals("<div style=\"background:u\\72 l(https://example.com/x.png);color:red\">x</div>",
            clean("<div style=\"background:u\\72 l(https://example.com/x.png);color:red\">x</div>"));
        // a protocol-relative reference cannot inherit the base scheme, even behind an escaped function name
        assertEquals("<div>x</div>",
            clean("<div style=\"background:\\55 RL(//cdn.example.com/x.png)\">x</div>"));
        // a safe relative reference still resolves against the element's document base
        assertEquals("<div style=\"background:\\75 rl(img/x.png)\">x</div>",
            clean("<div style=\"background:\\75 rl(img/x.png)\">x</div>"));
        // and fails, just like plain url(), when there is no base to resolve against
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:\\75 rl(img/x.png);color:red\">x</div>", "", styleSafelist()));
        // a hex escape inside the URL argument decodes the same way before the protocol check
        assertEquals("<div style=\"background:url(\\68 ttps://example.com/x.png)\">x</div>",
            clean("<div style=\"background:url(\\68 ttps://example.com/x.png)\">x</div>"));
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(\\6a avascript:1)\">x</div>"));
    }

    @Test void escapedImportKeywordIsRecognized() {
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"@\\69 mport 'javascript:1';color:red\">x</div>"));
        assertEquals("<div>x</div>",
            clean("<div style=\"@\\69 mport \\75 rl(javascript:1)\">x</div>"));
        assertEquals("<div style=\"@\\69 mport 'https://example.com/x.css';color:red\">x</div>",
            clean("<div style=\"@\\69 mport 'https://example.com/x.css';color:red\">x</div>"));
    }

    @Test void escapedNamesInCustomPropertyValuesAreChecked() {
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"--x: \\75 rl(javascript:1);color:red\">x</div>"));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"--x: \\65 xpression(1);color:red\">x</div>"));
        // a fake call fully inside an ordinary string is inert and stays verbatim
        assertEquals("<div style=\"--x: 'url(javascript:1)';color:red\">x</div>",
            clean("<div style=\"--x: 'url(javascript:1)';color:red\">x</div>"));
    }

    @Test void fakeReferencesInsideOrdinaryStringsDoNotTriggerUrlChecks() {
        // a browser never tokenizes a function call out of a quoted string, so these are inert declarations
        assertEquals("<div style=\"content:'url(javascript:1)'\">x</div>",
            clean("<div style=\"content:'url(javascript:1)'\">x</div>"));
        assertEquals("<div style=\"content:&quot;expression(1)&quot;\">x</div>",
            clean("<div style='content:\"expression(1)\"'>x</div>"));
        // a real url() call whose argument is quoted is still checked, in both outcomes
        assertEquals("<div>x</div>", clean("<div style=\"background:url('javascript:1')\">x</div>"));
        assertEquals("<div style=\"background:url(&quot;https://example.com/x.png&quot;)\">x</div>",
            clean("<div style='background:url(\"https://example.com/x.png\")'>x</div>"));
        // an identifier glued to a string and followed by a call is unparsable ambiguity and is rejected
        assertEquals("<div>x</div>", clean("<div style=\"background:u&quot;rl&quot;(javascript:1)\">x</div>"));
        assertEquals("<div>x</div>", clean("<div style='background:u\"rl\"(https://example.com/x.png)'>x</div>"));
    }

    @Test void hexEscapeDelimiterBoundaryIsUnambiguous() {
        // two spaces: only the first is the hex-escape delimiter, the second is a real token separator, so "rl(...)"
        // is an ordinary function name and the escaped "u" is a separate value token — no url() call exists
        assertEquals("<div style=\"background:\\75  rl(https://example.com/x.png)\">x</div>",
            clean("<div style=\"background:\\75  rl(https://example.com/x.png)\">x</div>"));
        // the same split spelling carrying a script URL is equally inert
        assertEquals("<div style=\"background:\\75  rl(javascript:1)\">x</div>",
            clean("<div style=\"background:\\75  rl(javascript:1)\">x</div>"));
    }

    @Test void escapedSafeOutputIsStableAcrossRepeatedCleaning() {
        String html = "<div style=\"background:\\75 rl(https://example.com/x.png);color:red;"
            + "content:'url(javascript:1)';color:red/* url(javascript:1) */\">x</div>";
        String once = clean(html);
        assertEquals(once, clean(once));
        assertTrue(Cleaner.isValid(once, styleSafelist()));
    }

    @Test void allowsSafeHttpUrlsVerbatim() {
        String html = "<div style=\"background:url(https://example.com/x.png);color:red\">x</div>";
        assertEquals(html, clean(html));
    }

    @Test void resolvesSafeRelativeReferenceAgainstBaseAndKeepsSourceSpelling() {
        String html = "<div style=\"background:url(img/x.png);color:red\">x</div>";
        assertEquals(html, clean(html, Base, styleSafelist()));
        Document dirty = Jsoup.parseBodyFragment("<div style=\"background:url(img/x.png)\">x</div>", Base);
        assertTrue(new Cleaner(styleSafelist()).isValid(dirty));
    }

    @Test void protocolRelativeReferenceNeverInheritsBaseScheme() {
        // a protocol-relative reference carries no scheme of its own; it must not be allowed by inheriting the base
        // document's scheme during resolution
        assertEquals("<div>x</div>", clean("<div style=\"background:url(//cdn.example.com/x.png)\">x</div>"));
        // a safe declaration in the same attribute survives; the protocol-relative one alone is removed
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:url(//cdn.example.com/x.png);color:red\">x</div>"));
        // the same goes for an @import reference
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"@import '//cdn.example.com/x.css';color:red\">x</div>"));
        // a root-relative reference (a single slash) is not protocol-relative and stays resolvable against the base
        assertEquals("<div style=\"background:url(/x.png)\">x</div>",
            clean("<div style=\"background:url(/x.png)\">x</div>"));
        // a slash followed by a (CSS-escaped) backslash decodes to a single-slash root-relative path, not an authority
        assertEquals("<div style=\"background:url(/\\x.png)\">x</div>",
            clean("<div style=\"background:url(/\\x.png)\">x</div>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "//cdn.example.com/x.png",
        "\\/\\/cdn.example.com/x.png", // CSS character escapes of both slashes decode to "//"
        "\\\\\\\\cdn.example.com/x.png", // four source backslashes decode to the "\\" authority spelling
        "%2f%2fcdn.example.com/x.png", // percent-encoded slashes
        "%2F%2Fcdn.example.com/x.png",
        "%5c%5ccdn.example.com/x.png", // percent-encoded backslashes
        "  //cdn.example.com/x.png  ", // surrounding whitespace is trimmed by url()
    })
    void rejectsObfuscatedProtocolRelativeReferences(String ref) {
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(" + ref + ")\">x</div>"), ref);
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:url(" + ref + ");color:red\">x</div>"), ref);
    }

    @Test void relativeReferenceWithoutBaseIsRejected() {
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:url(img/x.png);color:red\">x</div>", "", styleSafelist()));
    }

    @Test void relativeReferenceIsKeptWhenPreservingRelativeLinks() {
        Safelist safelist = new Safelist().addTags("div").addAttributes("div", "style").preserveRelativeLinks(true);
        String html = "<div style=\"background:url(img/x.png);color:red\">x</div>";
        assertEquals(html, Jsoup.clean(html, safelist));
    }

    @Test void unknownProtocolAlwaysRejected() {
        assertEquals("<div>x</div>", clean("<div style=\"background:url(foo:bar)\">x</div>"));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:url(foo:bar);color:red\">x</div>"));
    }

    @Test void dataUrlRejectedByDefault() {
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(data:image/png;base64,iVBORw0KGgo=)\">x</div>"));
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(data:text/html,<script>alert(1)</script>)\">x</div>"));
    }

    @Test void safeDataUrlKeptWhenDataProtocolConfigured() {
        Safelist safelist = styleSafelist().addProtocols(":all", "style", "data");
        assertEquals("<div style=\"background:url(data:image/png;base64,iVBORw0KGgo=)\">x</div>",
            clean("<div style=\"background:url(data:image/png;base64,iVBORw0KGgo=)\">x</div>", Base, safelist));
        assertEquals("<div style=\"background:url(data:text/plain;base64,aGVsbG8=)\">x</div>",
            clean("<div style=\"background:url(data:text/plain;base64,aGVsbG8=)\">x</div>", Base, safelist));
    }

    @Test void activeDataUrlRejectedEvenWhenDataProtocolConfigured() {
        Safelist safelist = styleSafelist().addProtocols(":all", "style", "data");
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(data:image/svg+xml;base64,PHNjcmlwdD4=)\">x</div>", Base, safelist));
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(data:text/html,<script>)\" >x</div>", Base, safelist));
    }

    @Test void malformedSyntaxOnlyDropsItsDeclaration() {
        assertEquals("<div style=\"color:red;color:blue\">x</div>",
            clean("<div style=\"color:red;font-family:'oops;color:blue\">x</div>")); // unclosed quote, recovers at ';'
        assertEquals("<div style=\"color:red;color:blue\">x</div>",
            clean("<div style=\"color:red;background:url(x.png;color:blue\">x</div>")); // unclosed function
        assertEquals("<div style=\"color:red;color:blue\">x</div>",
            clean("<div style=\"color:red;;;color:blue\">x</div>")); // repeated separators
        assertEquals("<div style=\"color:blue\">x</div>",
            clean("<div style=\"color:red);color:blue\">x</div>")); // stray close parenthesis
    }

    @Test void unterminatedCommentDropsOnlyItsFragmentAndRecoversAtSemicolon() {
        // with a later ';', the unbounded comment is only its own fragment; following declarations survive
        assertEquals("<div style=\"color:red;color:blue\">x</div>",
            clean("<div style=\"color:red;/* never closed;color:blue\">x</div>"));
        assertEquals("<div style=\"color:red;color:blue;margin:0\">x</div>",
            clean("<div style=\"color:red;/* never closed;color:blue;margin:0\">x</div>"));
        // a dangerous URL trapped in the unclosed fragment goes with it, and the next declaration still parses
        assertEquals("<div style=\"color:red;color:blue\">x</div>",
            clean("<div style=\"color:red;/* url(javascript:1);color:blue\">x</div>"));
        // with no ';' left there is no recoverable boundary, so the tail is discarded
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"color:red;/* never closed color:blue\">x</div>"));
    }

    @Test void fakeReferenceInACommentNeverTriggersAUrlCheck() {
        // comment text is removed by a browser before tokenization, so a url(...)-shaped fragment inside a comment
        // is never fetched and must not by itself reject (or check) its declaration; the declaration keeps its text
        assertEquals("<div style=\"/* url(javascript:x) */background:red\">x</div>",
            clean("<div style=\"/* url(javascript:x) */background:red\">x</div>"));
        assertEquals("<div style=\"/* url(javascript:x) */background:red;color:blue\">x</div>",
            clean("<div style=\"/* url(javascript:x) */background:red;color:blue\">x</div>"));
        // the same goes for a comment between the property name and the ':'
        assertEquals("<div style=\"color/* url(javascript:x) */:red;color:blue\">x</div>",
            clean("<div style=\"color/* url(javascript:x) */:red;color:blue\">x</div>"));
        // and in a custom property, whose value is scanned as free text
        assertEquals("<div style=\"--foo/* url(javascript:x) */:red;color:blue\">x</div>",
            clean("<div style=\"--foo/* url(javascript:x) */:red;color:blue\">x</div>"));
        // a genuinely named reference outside the comment is still checked, of course
        assertEquals("<div>x</div>",
            clean("<div style=\"/* keep */background:url(javascript:x)\">x</div>"));
        // an unterminated comment is still an unbounded fragment: only its own fragment is lost, and later
        // declarations stay independently parseable (recovery is at the next ';')
        assertEquals("<div style=\"color:red;color:blue\">x</div>",
            clean("<div style=\"/* url(javascript:x);color:red;color:blue\">x</div>"));
    }

    @Test void dangerousPropertiesAreNotMaskedByAnInsertedComment() {
        assertEquals("<div>x</div>",
            clean("<div style=\"behavior/**/:url(https://example.com/x.htc)\">x</div>"));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"behavior /* */ : url(https://example.com/x.htc);color:red\">x</div>"));
        assertEquals("<div>x</div>",
            clean("<div style=\"-moz-binding/**/:url(https://example.com/b.xml)\">x</div>"));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"-moz-binding /* */: url(https://example.com/b.xml);color:red\">x</div>"));
    }

    @Test void functionNameSplitByACommentIsRejected() {
        // a comment cannot smuggle a split url(...) or expression(...) past name recognition
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:ur/**/l(javascript:1);color:red\">x</div>"));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:ur/**/l(https://example.com/x.png);color:red\">x</div>"));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"width:expr/**/ession(1);color:red\">x</div>"));
        // two bare identifier runs glued by a comment but not followed by a call are still harmless and preserved
        assertEquals("<div style=\"--x: a/**/b;color:red\">x</div>",
            clean("<div style=\"--x: a/**/b;color:red\">x</div>"));
    }

    @Test void commentInsideAFunctionDoesNotEndTheFunctionEarly() {
        // the ')' is comment text, not the function close; the real ')' balances calc() and the declaration survives
        assertEquals("<div style=\"width:calc(1px /* ) */ + 1px);color:red\">x</div>",
            clean("<div style=\"width:calc(1px /* ) */ + 1px);color:red\">x</div>"));
        // a balanced, safe function followed by a comment stays intact
        assertEquals("<div style=\"cursor:url(https://example.com/x.cur)/* x */,auto;color:red\">x</div>",
            clean("<div style=\"cursor:url(https://example.com/x.cur)/* x */,auto;color:red\">x</div>"));
    }

    @Test void commentsAreNotParsedAsDeclarationsAndDoNotTriggerUrlChecks() {
        // a safe comment stays in place
        assertEquals("<div style=\"color:red/* a;b */;color:blue\">x</div>",
            clean("<div style=\"color:red/* a;b */;color:blue\">x</div>"));
        // a url(...)-shaped fragment inside a comment is removed by the browser before tokenization and so is never
        // a reference: it neither checks nor rejects the declaration that carries it
        assertEquals("<div style=\"color:red/* url(javascript:x) */;color:blue\">x</div>",
            clean("<div style=\"color:red/* url(javascript:x) */;color:blue\">x</div>"));
        // comment text is never treated as a declaration; a leading comment is preserved verbatim
        assertEquals("<div style=\"/* color: red */color:blue\">x</div>",
            clean("<div style=\"/* color: red */color:blue\">x</div>"));
    }

    @Test void stringContentsAreCheckedForReferences() {
        // an ordinary quoted string without a reference is inert
        assertEquals("<div style=\"font-family:'Arial Black'\">x</div>",
            clean("<div style=\"font-family:'Arial Black'\">x</div>"));
        // a reference concealed in a string still rejects the declaration
        assertEquals("<div style=\"color:blue\">x</div>",
            clean("<div style=\"content:'x' url(javascript:1);color:blue\">x</div>"));
    }

    @Test void customPropertiesAreScannedLikeOtherValues() {
        assertEquals("<div style=\"--foo: bar; color:red\">x</div>",
            clean("<div style=\"--foo: bar; color:red\">x</div>"));
        // the rejected custom property takes only its own declaration; the survivor keeps its original spacing
        assertEquals("<div style=\" color:red\">x</div>",
            clean("<div style=\"--foo: url(javascript:alert(1)); color:red\">x</div>"));
    }

    @Test void customPropertyValueColonsArePreserved() {
        // a custom property holds arbitrary text, split from its name at only the first top-level colon: colons in
        // the value (times, URL-looking text, function arguments) are never a reason to drop the declaration
        assertEquals("<div style=\"--foo: a:b; color:red\">x</div>",
            clean("<div style=\"--foo: a:b; color:red\">x</div>"));
        assertEquals("<div style=\"--foo: https://example.com/path; color:red\">x</div>",
            clean("<div style=\"--foo: https://example.com/path; color:red\">x</div>"));
        assertEquals("<div style=\"--foo: var(--a, red:blue); color:red\">x</div>",
            clean("<div style=\"--foo: var(--a, red:blue); color:red\">x</div>"));
        // a non-URL custom property value that merely mentions a colon is kept even with no base URI
        assertEquals("<div style=\"--foo: 12:00\">x</div>",
            clean("<div style=\"--foo: 12:00\">x</div>", "", styleSafelist()));
        // but a genuine reference inside the custom value is still resolved and checked
        assertEquals("<div style=\" color:red\">x</div>",
            clean("<div style=\"--foo: url(img/x.png); color:red\">x</div>", "", styleSafelist()));
    }

    @Test void ordinaryValueProtocolAndTimeTextIsNotTruncated() {
        // a bare colon outside a url(...) reference cannot fetch anything, so protocol- or time-looking text survives
        assertEquals("<div style=\"content: http://example.com/not-a-reference;color:red\">x</div>",
            clean("<div style=\"content: http://example.com/not-a-reference;color:red\">x</div>"));
        assertEquals("<div style=\"content: 12:00;color:red\">x</div>",
            clean("<div style=\"content: 12:00;color:red\">x</div>"));
        // an actual reference in the same declaration is still governed by the URL rules
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"content: http://x url(javascript:1);color:red\">x</div>"));
    }

    @Test void importReferenceIsChecked() {
        assertEquals("<div style=\"@import url(https://example.com/x.css);color:red\">x</div>",
            clean("<div style=\"@import url(https://example.com/x.css);color:red\">x</div>"));
        assertEquals("<div style=\"@import &quot;https://example.com/x.css&quot;;color:red\">x</div>",
            clean("<div style='@import \"https://example.com/x.css\";color:red'>x</div>"));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"@import 'javascript:alert(1)';color:red\">x</div>"));
        assertEquals("<div>x</div>",
            clean("<div style=\"@import url(javascript:alert(1))\">x</div>"));
    }

    @Test void dangerousPropertiesAreAlwaysRejected() {
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"-moz-binding:url(https://example.com/bind.xml#x);color:red\">x</div>"));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"behavior:url(https://example.com/x.htc);color:red\">x</div>"));
    }

    @Test void elementsAreCleanedIndependentlyAndOrderStaysStable() {
        String html = "<div style=\"color:red\">a</div>"
            + "<p style=\"background:url(javascript:1);font-size:10px\">b</p>"
            + "<span style=\"margin:0\">c</span>";
        assertEquals("<div style=\"color:red\">a</div><p style=\"font-size:10px\">b</p><span style=\"margin:0\">c</span>",
            org.jsoup.TextUtil.stripNewlines(clean(html)));
    }

    @Test void cleaningIsIdempotent() {
        String html = "<div style=\"color:red;background:url(javascript:1);width:1px\">x</div>";
        String once = clean(html);
        String twice = clean(once);
        assertEquals(once, twice);
        assertTrue(Cleaner.isValid(once, styleSafelist()));
    }

    @Test void cleaningDoesNotModifyInputDocument() {
        Document dirty = Jsoup.parseBodyFragment(
            "<div style=\"color:red;background:url(javascript:1)\">x</div>", Base);
        String before = dirty.body().html();
        new Cleaner(styleSafelist()).clean(dirty);
        assertEquals(before, dirty.body().html());
    }

    @Test void rejectedUrlNeverAppearsInOutputOrTree() {
        String html = "<div style=\"color:red;background:url(javascript:alert(1))\">x</div>";
        Document cleanDoc = new Cleaner(styleSafelist()).clean(Jsoup.parseBodyFragment(html, Base));
        assertFalse(cleanDoc.toString().contains("javascript"));
        cleanDoc.getAllElements().forEach(el -> assertFalse(el.hasAttr("style") && el.attr("style").contains("javascript")));
    }

    @Test void isValidTracksStyleRewrites() {
        assertTrue(Cleaner.isValid("<div style=\"color:red\">x</div>", styleSafelist()));
        assertFalse(Cleaner.isValid("<div style=\"color:red;background:url(javascript:1)\">x</div>", styleSafelist()));
        assertFalse(Cleaner.isValid("<div style=\"width:expression(1)\">x</div>", styleSafelist()));
    }

    @Test void sameDocumentFragmentReferencesAreAllowed() {
        assertEquals("<div style=\"clip-path:url(#clip)\">x</div>",
            clean("<div style=\"clip-path:url(#clip)\">x</div>"));
        // work without a base URI too
        assertEquals("<div style=\"clip-path:url(#clip)\">x</div>",
            clean("<div style=\"clip-path:url(#clip)\">x</div>", "", styleSafelist()));
    }

    @Test void scriptAndUnknownSchemesRejectedEvenWhenConfiguredProtocolsAreRemoved() {
        // unlike a href/srcset, style does not open up to "any protocol" once protocols are removed: the safe
        // default set (http/https/#) and the hard denial of javascript/vbscript still apply
        Safelist safelist = new Safelist().addTags("div").addAttributes("div", "style")
            .addProtocols("div", "style", "http", "https").removeProtocols("div", "style", "http", "https");
        assertEquals("<div style=\"background:url(http://example.com/x.png);color:red\">x</div>",
            clean("<div style=\"background:url(http://example.com/x.png);color:red\">x</div>", Base, safelist));
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:url(ftp://example.com/x.png);color:red\">x</div>", Base, safelist));
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(javascript:alert(1))\">x</div>", Base, safelist));
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(vbscript:msgbox(1))\">x</div>", Base, safelist));
    }

    @Test void percentEncodedScriptSchemeIsRejected() {
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(j%61vascript:alert(1))\">x</div>"));
        assertEquals("<div>x</div>",
            clean("<div style=\"background:url(%6Aavascript:1)\">x</div>"));
    }

    @Test void referencesNestedInsideOtherFunctionsAreChecked() {
        // a generic function cannot smuggle a nested url()/expression() past the scan
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:var(--x, url(javascript:1));color:red\">x</div>"));
        assertEquals("<div>x</div>",
            clean("<div style=\"background:blur(url(javascript:1))\">x</div>"));
    }

    @Test void atRuleBuriedInAValueIsRejected() {
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"color: @import 'javascript:1';color:red\">x</div>"));
    }

    @Test void oversizedCssEscapeDoesNotThrow() {
        // six hex digits above the Unicode range become U+FFFD; the declaration is still validated, never thrown on.
        // the delimiter space is part of the hex escape, so the token decodes to a harmless relative reference that
        // resolves against the https base and is kept verbatim
        assertEquals("<div style=\"background:url(http\\af0aab x);color:red\">x</div>",
            clean("<div style=\"background:url(http\\af0aab x);color:red\">x</div>"));
        // the same overlong escape inside a function name decodes to U+FFFD, which is not a plain identifier, so the
        // ambiguous call is rejected rather than read as two tokens
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:\\af0aab url(https://example.com/x.png);color:red\">x</div>"));
    }

    @Test void srcsetAndHrefBehaviorIsUnaffected() {
        Safelist safelist = Safelist.basicWithImages();
        assertEquals("<img src=\"https://example.com/x.png\">",
            org.jsoup.TextUtil.stripNewlines(clean("<img src='/x.png'>", Base, safelist)));
        assertEquals("<img>",
            org.jsoup.TextUtil.stripNewlines(clean("<img src='javascript:alert(1)'>", Base, safelist)));
    }
}
