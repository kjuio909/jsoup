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

    @Test void protocolRelativeReferenceInheritsBaseScheme() {
        String html = "<div style=\"background:url(//cdn.example.com/x.png)\">x</div>";
        assertEquals(html, clean(html, Base, styleSafelist()));
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

    @Test void unterminatedCommentSwallowsOnlyItsTail() {
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"color:red;/* never closed color:blue\">x</div>"));
    }

    @Test void commentsAreNotParsedAsDeclarationsButTheirUrlsAreStillChecked() {
        // a safe comment stays in place
        assertEquals("<div style=\"color:red/* a;b */;color:blue\">x</div>",
            clean("<div style=\"color:red/* a;b */;color:blue\">x</div>"));
        // a dangerous URL hidden in a comment rejects the declaration that contains the comment
        assertEquals("<div style=\"color:blue\">x</div>",
            clean("<div style=\"color:red/* url(javascript:x) */;color:blue\">x</div>"));
        // comment text is never treated as a declaration; a safe leading comment is preserved verbatim
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
        // six hex digits above the Unicode range become U+FFFD; the declaration is still validated, never thrown on
        assertEquals("<div style=\"color:red\">x</div>",
            clean("<div style=\"background:url(http\\af0aab x);color:red\">x</div>"));
    }

    @Test void srcsetAndHrefBehaviorIsUnaffected() {
        Safelist safelist = Safelist.basicWithImages();
        assertEquals("<img src=\"https://example.com/x.png\">",
            org.jsoup.TextUtil.stripNewlines(clean("<img src='/x.png'>", Base, safelist)));
        assertEquals("<img>",
            org.jsoup.TextUtil.stripNewlines(clean("<img src='javascript:alert(1)'>", Base, safelist)));
    }
}
