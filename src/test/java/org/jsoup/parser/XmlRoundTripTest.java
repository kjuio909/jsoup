package org.jsoup.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.XmlDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the semantic fidelity of XML documents across parse, edit, and republish, when parsed with
 * {@code Jsoup.parse(String, String, Parser)} in XML mode: declarations, doctypes, processing instructions, comments,
 * CDATA, text, and elements are preserved in order, and no HTML structure is synthesized.
 *
 * <p>Note jsoup serializes empty elements in the legal XML self-closing form {@code <tag />}.</p>
 */
public class XmlRoundTripTest {
    private static Document xml(String input) {
        return Jsoup.parse(input, "https://example.com/", Parser.xmlParser());
    }

    @Test
    public void preservesAllNodeTypesInOrder() {
        Document doc = xml("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<!DOCTYPE root PUBLIC \"pub-id\" \"sys-id\">\n" +
            "<?pi-target some data?>\n" +
            "<!-- a comment -->\n" +
            "<root><a><![CDATA[some <cdata>]]></a>text<empty/></root>");
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<!DOCTYPE root PUBLIC \"pub-id\" \"sys-id\">\n" +
            "<?pi-target some data?>\n" +
            "<!-- a comment -->\n" +
            "<root><a><![CDATA[some <cdata>]]></a>text<empty /></root>", doc.html());

        // no html/head/body containers were synthesized
        assertNull(doc.selectFirst("html"));
        assertNull(doc.selectFirst("head"));
        assertNull(doc.selectFirst("body"));

        // node types in original order (interleaved TextNodes hold the newline separators)
        assertInstanceOf(XmlDeclaration.class, doc.childNode(0));
        assertInstanceOf(DocumentType.class, doc.childNode(2));
        assertInstanceOf(XmlDeclaration.class, doc.childNode(4)); // the processing instruction
        assertInstanceOf(Comment.class, doc.childNode(6));
        Element root = (Element) doc.childNode(8);
        assertEquals("root", root.tagName());
        assertInstanceOf(CDataNode.class, root.selectFirst("a").childNode(0));
        assertInstanceOf(TextNode.class, root.childNode(1));
    }

    @Test
    public void preservesDeclarationInfo() {
        Document doc = xml("<?xml version=\"1.1\" encoding=\"Shift_JIS\" standalone=\"no\"?><root/>");
        XmlDeclaration decl = (XmlDeclaration) doc.childNode(0);
        assertEquals("1.1", decl.attr("version"));
        assertEquals("Shift_JIS", decl.attr("encoding"));
        assertEquals("no", decl.attr("standalone"));
        assertEquals("<?xml version=\"1.1\" encoding=\"Shift_JIS\" standalone=\"no\"?><root />", doc.html());
    }

    @Test
    public void preservesDoctypeIds() {
        Document doc = xml("<!DOCTYPE root PUBLIC \"pub\" \"sys\"><root/>");
        DocumentType doctype = (DocumentType) doc.childNode(0);
        assertEquals("root", doctype.name());
        assertEquals("pub", doctype.publicId());
        assertEquals("sys", doctype.systemId());
        assertEquals("<!DOCTYPE root PUBLIC \"pub\" \"sys\"><root />", doc.html());

        assertEquals("<!DOCTYPE root SYSTEM \"sys\"><root />",
            xml("<!DOCTYPE root SYSTEM \"sys\"><root/>").html());
        assertEquals("<!DOCTYPE root PUBLIC \"pub\"><root />",
            xml("<!DOCTYPE root PUBLIC \"pub\"><root/>").html());
    }

    @Test
    public void preservesDoctypeInternalSubset() {
        String in = "<!DOCTYPE root [ <!ENTITY x \"y\"> <!ELEMENT root (#PCDATA)> ]><root/>";
        assertEquals("<!DOCTYPE root [ <!ENTITY x \"y\"> <!ELEMENT root (#PCDATA)> ]><root />", xml(in).html());
    }

    @Test
    public void preservesProcessingInstructionTargetAndData() {
        Document doc = xml("<?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?><root/>");
        XmlDeclaration pi = (XmlDeclaration) doc.childNode(0);
        assertEquals("xml-stylesheet", pi.name());
        assertEquals("text/xsl", pi.attr("type"));
        assertEquals("style.xsl", pi.attr("href"));
        assertEquals("<?xml-stylesheet type=\"text/xsl\" href=\"style.xsl\"?><root />", doc.html());
    }

    @Test
    public void preservesRepeatedNodesIndividually() {
        assertEquals("<?one a?><?two b?><root />", xml("<?one a?><?two b?><root/>").html());
        assertEquals("<!DOCTYPE a><!DOCTYPE b><root />", xml("<!DOCTYPE a><!DOCTYPE b><root/>").html());
        assertEquals("<!--c1--><!--c2--><root /><!--c3-->", xml("<!--c1--><!--c2--><root/><!--c3-->").html());
        assertEquals("<root><![CDATA[one]]><![CDATA[two]]></root>",
            xml("<root><![CDATA[one]]><![CDATA[two]]></root>").html());
    }

    @Test
    public void emptyElementsSelfClose() {
        assertEquals("<root><a /><b></b></root>", xml("<root><a/><b></b></root>").html());
        assertEquals("<root><e a=\"1\" b=\"2\" /></root>", xml("<root><e a=\"1\" b=\"2\"/>").html());
    }

    @Test
    public void preservesCaseAndNamespaceSeparators() {
        assertEquals("<Root Xml:Attr=\"v\"><Child /></Root>", xml("<Root Xml:Attr=\"v\"><Child/></Root>").html());
        assertEquals("<Root Attr=\"V\" xml:lang=\"en\" NS:x=\"y\" />",
            xml("<Root Attr=\"V\" xml:lang=\"en\" NS:x=\"y\"/>").html());
    }

    @Test
    public void domMutationsAreImmediatelyVisible() {
        Document doc = xml("<?xml version=\"1.0\"?><root><a>1</a><b>2</b></root>");
        doc.selectFirst("a").text("one");
        doc.selectFirst("b").remove();
        Element c = doc.selectFirst("root").appendElement("c").text("3");
        doc.selectFirst("root").prependChild(c); // move
        assertEquals("<?xml version=\"1.0\"?><root><c>3</c><a>one</a></root>", doc.html());

        // text and comment content edits
        Document doc2 = xml("<root>before<!--old--></root>");
        ((TextNode) doc2.selectFirst("root").childNode(0)).text("after");
        ((Comment) doc2.selectFirst("root").childNode(1)).setData("new");
        assertEquals("<root>after<!--new--></root>", doc2.html());
    }

    @Test
    public void unchangedDocumentSerializesConsistently() {
        Document doc = xml("<?xml version=\"1.0\"?><!DOCTYPE r><r><a/><![CDATA[x]]><!--c--></r>");
        String first = doc.html();
        assertEquals(first, doc.html());
        assertEquals(first, doc.outerHtml());
    }

    @Test
    public void documentsAreIndependent() {
        Document d1 = xml("<root><x>1</x></root>");
        Document d2 = xml("<root><x>2</x></root>");
        d1.selectFirst("x").text("CHANGED");
        assertEquals("<root><x>CHANGED</x></root>", d1.html());
        assertEquals("<root><x>2</x></root>", d2.html());
    }

    @Test
    public void detachedNodesSerializeIndependently() {
        Document doc = xml("<?xml version=\"1.0\"?><root><keep/><move/></root>");
        Element move = doc.selectFirst("move");
        move.remove();
        assertEquals("<move />", move.outerHtml());
        assertEquals("<?xml version=\"1.0\"?><root><keep /></root>", doc.html());
    }

    @Test
    public void minimalInputsProduceOutput() {
        assertEquals("", xml("").html());
        assertEquals("<?xml version=\"1.0\"?>", xml("<?xml version=\"1.0\"?>").html());
        assertEquals("<?php echo 'x';?>", xml("<?php echo 'x'; ?>").html());
        assertEquals("<!-- just a comment -->", xml("<!-- just a comment -->").html());
        assertEquals("hello world", xml("hello world").html());
    }

    @Test
    public void boundaryContentDoesNotSwallowFollowingNodes() {
        // boundary whitespace in the declaration
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root />",
            xml("<?xml  version = \"1.0\"   encoding = 'UTF-8' ?>\n<root/>").html());
        // quotes inside processing instruction data
        assertEquals("<?pi data with \"quotes\" and 'apos'?><root />",
            xml("<?pi data with \"quotes\" and 'apos'?><root/>").html());
        // non-ASCII in processing instruction data
        assertEquals("<?pi 日本語 データ?><root />", xml("<?pi 日本語 データ?><root/>").html());
        // quoted > and comments inside a doctype internal subset
        assertEquals("<!DOCTYPE root [ <!ENTITY x \">\"> ]><root />",
            xml("<!DOCTYPE root [ <!ENTITY x \">\"> ]><root/>").html());
        assertEquals("<!DOCTYPE root [ <!-- c --> <!ELEMENT root ANY> ]><root />",
            xml("<!DOCTYPE root [ <!-- c --> <!ELEMENT root ANY> ]><root/>").html());
        // non-ASCII text content
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><root>日本語</root>",
            xml("<?xml version=\"1.0\" encoding=\"UTF-8\"?><root>日本語</root>").html());
    }

    @Test
    public void malformedFragmentsDoNotThrow() {
        assertDoesNotThrow(() -> xml("<?xml version=\"1.0\"").html());
        assertDoesNotThrow(() -> xml("<?pi data").html());
        assertDoesNotThrow(() -> xml("<!DOCTYPE root PUBLIC \"p\"").html());
        assertDoesNotThrow(() -> xml("<root><unclosed>").html());
        assertEquals("<!-- comment-->", xml("<!-- comment").html());
        assertEquals("<root><![CDATA[abc</root>]]></root>", xml("<root><![CDATA[abc</root>").html());
        assertEquals("<root><unclosed></unclosed></root>", xml("<root><unclosed>").html());
    }

    @Test
    public void htmlModeIsUnaffected() {
        // the same input parsed in HTML mode keeps its existing, structure-normalizing behavior
        Document doc = Jsoup.parse("<p>One</p><p>Two</p>", "https://example.com/", Parser.htmlParser());
        assertNotNull(doc.selectFirst("html"));
        assertNotNull(doc.selectFirst("head"));
        assertNotNull(doc.selectFirst("body"));
        assertEquals(2, doc.select("body > p").size());
    }
}
