package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 Regression tests for URL protocol enforcement in the {@link Cleaner} / {@link Safelist}: executable schemes must be
 blocked in every common disguise, per-attribute protocol sets must not leak into each other, multi-URL attributes
 such as {@code srcset} must have every candidate checked, and ordinary relative links must keep working.
 */
public class UrlProtocolCleaningTest {
    private static final String Base = "https://example.com/page/index.html";

    private static Safelist linkSafelist() {
        return Safelist.none()
            .addTags("a", "p")
            .addAttributes("a", "href", "title", "src");
    }

    private static Safelist httpHttps() {
        return linkSafelist().addProtocols("a", "href", "http", "https");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)",
        "JavaScript:alert(1)",
        "JAVASCRIPT:1",
        "JaVaScRiPt:alert(1)",
        "  javascript:1",            // leading SP
        "javascript:1  ",            // trailing SP
        "\tjavascript:1",            // leading TAB
        "\njavascript:1",            // leading LF
        "\rjavascript:1",            // leading CR
        "\fjavascript:1",            // leading FF
        "javascript:1\r\n",          // trailing CRLF
        " &#x09;javascript:1",       // entity-encoded TAB
        "java&#x09;script:1",        // control char inside the scheme
        "java&#x01;script:1",        // SOH inside the scheme
        "java&#x00;script:1",        // NUL inside the scheme
        "&#0013;ja&Tab;va&Tab;script&#0010;:alert(1)",
        "ja&Tab;script&colon;1",     // entity-encoded colon
        "javascript\\:1",            // backslash
        "javascript:\\\\alert(1)",
        "java%73cript:1",            // percent-encoded 's'
        "%6Aavascript:1",            // percent-encoded 'j'
        "java%6asCript:1",
        "java\u007fscript:1",        // DEL inside the scheme
        "java script:1",             // space inside the scheme
        "java\u00a0script:1",      // non-breaking space inside the scheme
        "java\u200bscript:1",      // zero-width space inside the scheme
        "java\u202escript:1",      // right-to-left override inside the scheme
        "data:text/html,evil",
        "DATA:text/html,evil",
        "vbscript:msgbox(1)",
        "file:///etc/passwd",
    })
    void dropsDangerousHref(String href) {
        String html = "<a href=\"" + href + "\">x</a>";
        assertEquals("<a>x</a>", Jsoup.clean(html, Base, httpHttps()));
        assertEquals("<a>x</a>", Jsoup.clean(html, httpHttps().preserveRelativeLinks(true)));
        assertFalse(Jsoup.isValid(html, httpHttps()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/a?b=1#c",
        "http://example.com/path",
        "https://example.com/:javascript:x",   // dangerous word later in the path is harmless
        "http://evil.com/a:b:c",
    })
    void keepsAllowedHrefWithPathQueryFragment(String href) {
        String out = Jsoup.clean("<a href=\"" + href + "\">x</a>", Base, httpHttps());
        assertEquals("<a href=\"" + href + "\">x</a>", out);
        assertTrue(Jsoup.isValid("<a href=\"" + href + "\">x</a>", httpHttps()));
    }

    @Test void comparesSchemeAsciiCaseInsensitively() {
        assertEquals("<a href=\"https://Example.COM/x\">x</a>",
            Jsoup.clean("<a href=HTTPS://Example.COM/x>x</a>", Base, httpHttps()));
    }

    @Test void keepsFullHrefSemantics() {
        assertEquals("<a href=\"https://example.com/a?b=1#c\">x</a>",
            Jsoup.clean("<a href=\"https://example.com/a?b=1#c\">x</a>", Base, httpHttps()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo/bar.html",
        "/foo/bar",
        "#section",
        "foo?a=1#b",
        "//cdn.example.com/x",       // protocol-relative
        "a/b:c",                     // colon in a later path segment
        "/foo:bar",
        "search?q=a:b",
        "123:45",                    // not a scheme (does not start with a letter) -> relative
    })
    void keepsRelativeLinksWhenConfigured(String href) {
        String out = Jsoup.clean("<a href=\"" + href + "\">x</a>", httpHttps().preserveRelativeLinks(true));
        assertEquals("<a href=\"" + href + "\">x</a>", out);
        // cleaning again must not further modify the retained value
        assertEquals(out, Jsoup.clean(out, httpHttps().preserveRelativeLinks(true)));
    }

    @Test void resolvesRelativeLinksAgainstBase() {
        Safelist sl = httpHttps();
        assertEquals("<a href=\"https://example.com/page/foo/bar.html\">x</a>",
            Jsoup.clean("<a href=foo/bar.html>x</a>", Base, sl));
        assertEquals("<a href=\"https://example.com/foo\">x</a>",
            Jsoup.clean("<a href=/foo>x</a>", Base, sl));
        assertEquals("<a href=\"https://cdn.example.com/x\">x</a>",
            Jsoup.clean("<a href=//cdn.example.com/x>x</a>", Base, sl));
    }

    @Test void dropsRelativeLinksWithNoBaseAndNoPreserve() {
        assertEquals("<a>x</a>", Jsoup.clean("<a href=/foo>x</a>", httpHttps()));
    }

    @Test void emptyAndMissingAndPlainValuesFollowExistingSemantics() {
        Safelist sl = httpHttps();
        assertEquals("<a>x</a>", Jsoup.clean("<a href=>x</a>", Base, sl));       // empty value
        assertEquals("<a>x</a>", Jsoup.clean("<a href>x</a>", Base, sl));        // boolean attribute
        assertEquals("<a href=\"https://example.com/page/plain\">x</a>",
            Jsoup.clean("<a href=plain>x</a>", Base, sl));                       // resolvable plain value
        assertEquals("<a href=\"plain\">x</a>",
            Jsoup.clean("<a href=plain>x</a>", sl.preserveRelativeLinks(true)));
    }

    @Test void onlyTheDangerousAttributeIsRemoved() {
        // sibling attributes, the element and its text all survive; only href goes
        assertEquals("<a title=\"hi\">x</a>",
            Jsoup.clean("<a href=javascript:1 title=hi>x</a>", Base, httpHttps()));
        assertEquals("<p>before<a title=\"hi\">x</a>after</p>",
            Jsoup.clean("<p>before<a href=javascript:1 title=hi>x</a>after</p>", Base, httpHttps()));
    }

    @Test void protocolSetsDoNotLeakBetweenAttributes() {
        Safelist sl = Safelist.none().addTags("a")
            .addAttributes("a", "href", "src")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("a", "src", "ftp");

        // ftp is allowed on src but not href; javascript is allowed nowhere
        assertEquals("<a src=\"ftp://files.example.com/f\">z</a>",
            Jsoup.clean("<a href=javascript:1 src=ftp://files.example.com/f>z</a>", Base, sl));
        // http is allowed on href but not src, so src alone is dropped
        assertEquals("<a href=\"https://example.com/\">z</a>",
            Jsoup.clean("<a href=https://example.com/ src=http://notallowed.example.com/a>z</a>", Base, sl));
        // each independently allowed: both kept
        assertEquals("<a href=\"https://example.com/\" src=\"ftp://files.example.com/f\">z</a>",
            Jsoup.clean("<a href=https://example.com/ src=ftp://files.example.com/f>z</a>", Base, sl));
        // a dangerous value on src does not pull the safe href down with it
        assertEquals("<a href=\"https://example.com/\">z</a>",
            Jsoup.clean("<a href=https://example.com/ src=javascript:1>z</a>", Base, sl));
    }

    @Test void independentAttributesOnDifferentElements() {
        Safelist sl = Safelist.none().addTags("a", "img", "form")
            .addAttributes("a", "href")
            .addAttributes("img", "src", "alt")
            .addAttributes("form", "action")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https")
            .addProtocols("form", "action", "https");

        assertEquals("<a>x</a>", Jsoup.clean("<a href=JAVASCRIPT:1>x</a>", Base, sl));
        assertEquals("<img alt=\"a\">", Jsoup.clean("<img src=javascript:1 alt=a>", Base, sl));
        assertEquals("<img src=\"https://example.com/a.png\" alt=\"a\">",
            Jsoup.clean("<img src=https://example.com/a.png alt=a>", Base, sl));
        assertEquals("<form></form>", Jsoup.clean("<form action=javascript:1></form>", Base, sl));
        assertEquals("<form></form>", Jsoup.clean("<form action=http://example.com/></form>", Base, sl));
        assertEquals("<form action=\"https://example.com/\"></form>",
            Jsoup.clean("<form action=https://example.com/></form>", Base, sl));
    }

    @Test void explicitlyAllowedNonHttpSchemesStillWork() {
        Safelist sl = Safelist.basicWithImages().addProtocols("img", "src", "cid", "data");
        assertEquals("<img src=\"cid:12345\"> <img src=\"data:image/png;base64,xx\">",
            Jsoup.clean("<img src='cid:12345'> <img src='data:image/png;base64,xx'>", sl));
        // but javascript is still not on the allow list
        assertEquals("<img>", Jsoup.clean("<img src=javascript:1>", sl));
    }

    @Test void anchorScheme() {
        Safelist sl = Safelist.relaxed().addProtocols("a", "href", "#");
        assertEquals("<a href=\"#valid\">Valid</a>",
            Jsoup.clean("<a href=#valid>Valid</a>", sl));
        assertEquals("<a>Invalid</a>",
            Jsoup.clean("<a href=\"#anchor with spaces\">Invalid</a>", sl));
    }

    private static Safelist srcsetSafelist() {
        return Safelist.none().addTags("img")
            .addAttributes("img", "srcset", "alt")
            .addProtocols("img", "srcset", "http", "https");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://ok.com/b.jpg 2x, javascript:alert(1) 1x",   // dangerous candidate after a safe one
        "javascript:alert(1) 1x, https://ok.com/b.jpg 2x",   // dangerous candidate first
        "/safe.jpg 1x, javascript:alert(1) 2x",
        "a.jpg 1x, javascript:x 2x, https://ok.com/c.jpg 3x",// dangerous candidate in the middle
        "https://ok.com/b.jpg,data:text/html,evil",
        "https://ok.com/b.jpg, JaVaScRiPt:x",
        "https://ok.com/b.jpg,javascript:1",                 // no descriptor
        "https://ok.com/b.jpg, java%73cript:1",
        "https://ok.com/b.jpg, java\u007fscript:1",
    })
    void anyDangerousSrcsetCandidateDropsTheWholeAttribute(String srcset) {
        String html = "<img srcset=\"" + srcset + "\" alt=\"x\">";
        assertEquals("<img alt=\"x\">", Jsoup.clean(html, Base, srcsetSafelist()));
        assertEquals("<img alt=\"x\">",
            Jsoup.clean(html, srcsetSafelist().preserveRelativeLinks(true)));
    }

    @Test void disallowedSchemeCandidateDropsWholeSrcset() {
        Safelist httpsOnly = Safelist.none().addTags("img")
            .addAttributes("img", "srcset", "alt").addProtocols("img", "srcset", "https");
        String html = "<img srcset=\"https://ok.com/b.jpg, http://insecure.com/a.jpg\" alt=\"x\">";
        assertEquals("<img alt=\"x\">", Jsoup.clean(html, Base, httpsOnly));
    }

    @Test void safeSrcsetCandidatesAreResolvedIndependently() {
        String out = Jsoup.clean("<img srcset=\"a.jpg 1x, https://ok.com/c.jpg 2x\">", Base, srcsetSafelist());
        assertEquals("<img srcset=\"https://example.com/page/a.jpg 1x, https://ok.com/c.jpg 2x\">", out);
        // descriptors and commas survive; root-relative and protocol-relative resolve too
        String out2 = Jsoup.clean("<img srcset=\"/s.jpg 1x, //cdn.com/c 2x\">", Base, srcsetSafelist());
        assertEquals("<img srcset=\"https://example.com/s.jpg 1x, https://cdn.com/c 2x\">", out2);
    }

    @Test void srcsetRelativeCandidatesArePreservedWhenConfigured() {
        String out = Jsoup.clean("<img srcset=\"a.jpg 1x, /b.jpg 2x, //cdn.com/c 3x\">",
            srcsetSafelist().preserveRelativeLinks(true));
        assertEquals("<img srcset=\"a.jpg 1x, /b.jpg 2x, //cdn.com/c 3x\">", out);
        assertEquals(out, Jsoup.clean(out, srcsetSafelist().preserveRelativeLinks(true)));
    }

    @Test void srcsetCheckedPerElement() {
        String out = Jsoup.clean(
            "<img srcset=\"javascript:x\"><img srcset=\"https://ok.com/b.jpg 2x\">", Base, srcsetSafelist());
        assertEquals("<img><img srcset=\"https://ok.com/b.jpg 2x\">", out);
    }

    @Test void multiValueUrlAttributeIsCaseInsensitiveOnKey() {
        Safelist sl = Safelist.none().addTags("img")
            .addAttributes("img", "SRCSET")
            .addProtocols("img", "srcset", "http", "https");
        assertEquals("<img>",
            Jsoup.clean("<img SRCSET=\"https://ok.com/b.jpg, javascript:1\">", Base, sl));
    }

    @Test void cleanedOutputIsReparseableSelectableAndStable() {
        Safelist sl = Safelist.relaxed().addAttributes("a", "id");
        String html = "<p>before<a id=l href=https://example.com/ title=t>x</a>after</p>";
        String once = Jsoup.clean(html, Base, sl);
        Document doc = Jsoup.parse(once);
        assertEquals("x", doc.select("a#l").text());
        assertEquals("beforexafter", doc.body().text());

        // a second pass is equivalent: safe content retained on the first pass must not be edited again
        String twice = Jsoup.clean(once, Base, sl);
        assertEquals(once, twice);
    }

    @Test void dangerousHrefCleaningIsIdempotent() {
        String once = Jsoup.clean("<a href=JavaScript:alert(1)>x</a>", Base, httpHttps());
        String twice = Jsoup.clean(once, Base, httpHttps());
        assertEquals(once, twice);
        assertEquals("<a>x</a>", once);
    }
}
