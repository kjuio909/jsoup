package org.jsoup.safety;

import org.jsoup.helper.ValidationException;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SafelistTest {
    private static final String TEST_TAG = "testTag";
    private static final String TEST_ATTRIBUTE = "testAttribute";
    private static final String TEST_SCHEME = "valid-scheme";
    private static final String TEST_VALUE = TEST_SCHEME + "://testValue";

    @Test
    public void testCopyConstructor_noSideEffectOnTags() {
        Safelist safelist1 = Safelist.none().addTags(TEST_TAG);
        Safelist safelist2 = new Safelist(safelist1);
        safelist1.addTags("invalidTag");

        assertFalse(safelist2.isSafeTag("invalidTag"));
    }

    @Test
    public void testCopyConstructor_noSideEffectOnAttributes() {
        Safelist safelist1 = Safelist.none().addAttributes(TEST_TAG, TEST_ATTRIBUTE);
        Safelist safelist2 = new Safelist(safelist1);
        safelist1.addAttributes(TEST_TAG, "invalidAttribute");

        assertFalse(safelist2.isSafeAttribute(TEST_TAG, null, new Attribute("invalidAttribute", TEST_VALUE)));
    }

    @Test
    public void testCopyConstructor_noSideEffectOnEnforcedAttributes() {
        Safelist safelist1 = Safelist.none().addEnforcedAttribute(TEST_TAG, TEST_ATTRIBUTE, TEST_VALUE);
        Safelist safelist2 = new Safelist(safelist1);
        safelist1.addEnforcedAttribute(TEST_TAG, TEST_ATTRIBUTE, "invalidValue");

        for (Attribute enforcedAttribute : safelist2.getEnforcedAttributes(TEST_TAG)) {
            assertNotEquals("invalidValue", enforcedAttribute.getValue());
        }
    }

    @Test
    public void testCopyConstructor_noSideEffectOnProtocols() {
        final String invalidScheme = "invalid-scheme";
        Safelist safelist1 = Safelist.none()
                .addAttributes(TEST_TAG, TEST_ATTRIBUTE)
                .addProtocols(TEST_TAG, TEST_ATTRIBUTE, TEST_SCHEME);
        Safelist safelist2 = new Safelist(safelist1);
        safelist1.addProtocols(TEST_TAG, TEST_ATTRIBUTE, invalidScheme);

        Attributes attributes = new Attributes();
        Attribute invalidAttribute = new Attribute(TEST_ATTRIBUTE, invalidScheme + "://someValue");
        attributes.put(invalidAttribute);
        Element invalidElement = new Element(Tag.valueOf(TEST_TAG), "", attributes);

        assertFalse(safelist2.isSafeAttribute(TEST_TAG, invalidElement, invalidAttribute));
    }

    @Test
    void isSafeAttributeDoesNotModifyLiveAttribute() {
        Attributes attributes = new Attributes().put("href", "/foo");
        Element link = new Element(Tag.valueOf("a"), "https://example.com/", attributes);
        Attribute href = link.attributes().attribute("href");
        assertNotNull(href);

        assertTrue(Safelist.basic().isSafeAttribute("a", link, href));
        assertEquals("/foo", href.getValue());
        assertEquals("/foo", link.attr("href"));
    }

    @Test
    void enforcedAttributeMatchesInputKeyCaseInsensitively() {
        Attribute rel = new Attribute("REL", "nofollow");
        Attributes attributes = new Attributes().put(rel);
        Element link = new Element(Tag.valueOf("a"), "", attributes);

        assertTrue(Safelist.basic().isSafeAttribute("a", link, rel));
    }

    @Test
    void noscriptIsBlocked() {
        boolean threw = false;
        Safelist safelist = null;
        try {
            safelist = Safelist.none().addTags("NOSCRIPT");
        } catch (ValidationException validationException) {
            threw = true;
            assertTrue(validationException.getMessage().contains("unsupported"));
        }
        assertTrue(threw);
        assertNull(safelist);
    }

    @Test
    void schemeOfDetectsObfuscatedSchemes() {
        // declared schemes are read case-insensitively, skipping controls, whitespace, backslashes, and Unicode padding
        assertEquals("javascript", Safelist.schemeOf("javascript:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("JAVASCRIPT:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf(" JaVa\tScript\n\r\f:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("\u0000javascript:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("\u200bjavascript:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("\u3000javascript:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("\u202fjavascript:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("javascript :alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("javascript\u0000:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("javascript\\:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("java\\script:alert(1)"));
        assertEquals("javascript", Safelist.schemeOf("\\javascript:alert(1)"));
        assertEquals("http", Safelist.schemeOf("http://example.com/a:b?c=1#d:e"));
        assertEquals("data", Safelist.schemeOf("data:text/plain,hi"));
        assertEquals("custom-scheme+1.x", Safelist.schemeOf("Custom-Scheme+1.x:v"));
    }

    @Test
    void schemeOfReturnsNullForRelativeAndMalformedValues() {
        String[] relativeOrMalformed = {
            null, "", ":", "/root/rel", "path/rel", "?q=1", "#frag-1", "//cdn.example.com/x",
            "javascript/:not-a-scheme",   // a slash before the colon makes it a relative reference
            "1http:x", "+http:x", "-http:x",
        };
        for (String value : relativeOrMalformed) {
            assertNull(Safelist.schemeOf(value), String.valueOf(value));
        }
        // syntactically a scheme (dots are legal), so declared and therefore checked against the allowed set
        assertEquals("example.com", Safelist.schemeOf("example.com:8080"));
    }

}
