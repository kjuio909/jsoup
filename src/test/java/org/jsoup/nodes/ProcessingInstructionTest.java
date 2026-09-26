package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.jsoup.nodes.Document.OutputSettings.Syntax.xml;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingInstructionTest {
    @Test void exposesTargetAndData() {
        ProcessingInstruction instruction = new ProcessingInstruction("target", "some data");

        assertEquals("#processing-instruction", instruction.nodeName());
        assertEquals("target", instruction.target());
        assertEquals("some data", instruction.data());
        assertEquals("some data", instruction.nodeValue());
    }

    @Test void serializesForHtmlAndXml() {
        ProcessingInstruction empty = new ProcessingInstruction("target", "");
        ProcessingInstruction data = new ProcessingInstruction("target", "some data");

        assertEquals("<?target ?>", empty.outerHtml());
        assertEquals("<?target some data?>", data.outerHtml());

        Document xmlDocument = new Document("");
        xmlDocument.outputSettings().syntax(xml).prettyPrint(false);
        xmlDocument.appendChild(empty).appendChild(data);
        assertEquals("<?target?><?target some data?>", xmlDocument.outerHtml());
    }

    @Test void parsesDataWhenAttributesAreAccessed() {
        ProcessingInstruction instruction = new ProcessingInstruction("target", "one='1' two = unquoted");

        assertEquals("one='1' two = unquoted", instruction.data());
        assertEquals("1", instruction.attr("one"));
        assertEquals("one=\"1\" two=\"unquoted\"", instruction.data());

        instruction.attr("three", "3").attr("empty", "");
        assertEquals("one=\"1\" two=\"unquoted\" three=\"3\" empty=\"\"", instruction.data());
    }

    @Test void stopsParsingAttributesAtCloser() {
        ProcessingInstruction instruction = new ProcessingInstruction("target", "foo=bar><a href>text");

        assertEquals("bar", instruction.attr("foo"));
        assertEquals("foo=\"bar\"", instruction.data());

        instruction.attr("added", "value");
        assertEquals("foo=\"bar\" added=\"value\"", instruction.data());
    }

    @Test void dataSetterUpdatesParsedAttributes() {
        ProcessingInstruction instruction = new ProcessingInstruction("target", "one='1'");
        Attributes attributes = instruction.attributes();

        instruction.data("Two='2'");

        assertSame(attributes, instruction.attributes());
        assertFalse(attributes.hasKey("one"));
        assertEquals("2", attributes.get("two"));
        assertEquals("two=\"2\"", instruction.data());

        attributes.put("three", "3");
        assertEquals("two=\"2\" three=\"3\"", instruction.data());
    }

    @Test void attributeNamedLikeNodePreservesInstructionData() {
        ProcessingInstruction instruction = new ProcessingInstruction("target", "one='1'");

        instruction.attr(instruction.nodeName(), "value");

        assertEquals("1", instruction.attr("one"));
        assertEquals("value", instruction.attr(instruction.nodeName()));
    }

    @Test void parsesAttributesWithDocumentParser() {
        ProcessingInstruction parsedXml = (ProcessingInstruction) Jsoup.parse(
            "<root><?target Mixed='1'?></root>",
            Parser.xmlParser()
        ).expectFirst("root").childNode(0);
        ProcessingInstruction detached = new ProcessingInstruction("target", "Mixed='1'");

        parsedXml.attributes();
        detached.attributes();

        assertEquals("Mixed=\"1\"", parsedXml.data());
        assertEquals("1", parsedXml.attr("Mixed"));
        // a detached instruction uses the HTML parser settings (case folding) by default
        assertEquals("mixed=\"1\"", detached.data());
        assertEquals("1", detached.attr("mixed"));
    }

    @Test void parsingAttributesDoesNotChangeDocumentParserErrors() {
        Parser parser = Parser.xmlParser().setTrackErrors(10);
        Document document = parser.parseInput("<?pi one='1'?><r", "");
        int errors = parser.getErrors().size();
        ProcessingInstruction instruction = document.nodeStream(ProcessingInstruction.class).findFirst()
            .orElseThrow(() -> new AssertionError("processing instruction missing"));

        assertTrue(errors > 0);
        instruction.attributes();
        assertEquals(errors, parser.getErrors().size());
    }

    @Test void serializesAttributesWithDocumentSettings() {
        Document xmlDoc = Jsoup.parse("<root/>", Parser.xmlParser());
        ProcessingInstruction xmlInstruction = new ProcessingInstruction("target", "checked=''");
        xmlDoc.expectFirst("root").appendChild(xmlInstruction);
        xmlInstruction.attributes().put("added", "1");

        assertEquals("checked=\"\" added=\"1\"", xmlInstruction.data());
        assertEquals("<?target checked=\"\" added=\"1\"?>", xmlInstruction.outerHtml());
    }

    @Test void serializesAttributesWithUniqueRepairedNames() {
        ProcessingInstruction instruction = new ProcessingInstruction("target", "");
        instruction.attributes()
            .put("a b", "1")
            .put("a_b", "2");

        assertEquals("_a_b=\"1\" a_b=\"2\"", instruction.data());
    }

    @Test void cloneIsIndependent() {
        ProcessingInstruction original = new ProcessingInstruction("target", "one='1'");
        original.attr("metadata", "original");
        ProcessingInstruction clone = original.clone();

        clone.data("two='2'").attr("metadata", "clone");

        assertEquals("one=\"1\" metadata=\"original\"", original.data());
        assertEquals("original", original.attr("metadata"));
        assertEquals("two=\"2\" metadata=\"clone\"", clone.data());
        assertEquals("clone", clone.attr("metadata"));
    }

    @Test void xmlParserCreatesProcessingInstructions() {
        ProcessingInstruction parsedXml = (ProcessingInstruction) Jsoup.parse(
            "<root><?target one='1'?></root>",
            Parser.xmlParser()
        ).expectFirst("root").childNode(0);

        assertEquals("one='1'", parsedXml.data());
    }

    @Test void keepsOrderOfDeclarationsPisCommentsAndElements() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<?a 1?><?a 2?>\n" +
            "<!DOCTYPE root SYSTEM \"root.dtd\">\n" +
            "<!--lead--><root><?pi inside?><![CDATA[c > data]]>text<!--inner--></root><?tail fin?>";
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());

        assertEquals(xml, doc.html());
        // repeated nodes of the same kind are each retained, in order
        assertEquals(10, doc.childNodes().size());
        assertTrue(doc.childNode(0) instanceof XmlDeclaration);
        assertTrue(doc.childNode(1) instanceof TextNode);
        assertTrue(doc.childNode(2) instanceof ProcessingInstruction);
        assertTrue(doc.childNode(3) instanceof ProcessingInstruction);
        assertEquals("a", ((ProcessingInstruction) doc.childNode(2)).target());
        assertEquals("a", ((ProcessingInstruction) doc.childNode(3)).target());
        assertTrue(doc.childNode(5) instanceof DocumentType);
        assertTrue(doc.childNode(7) instanceof Comment);
        assertTrue(doc.childNode(8) instanceof Element);
        assertTrue(doc.childNode(9) instanceof ProcessingInstruction);

        Element root = (Element) doc.childNode(8);
        assertTrue(root.childNode(0) instanceof ProcessingInstruction);
        assertTrue(root.childNode(1) instanceof CDataNode);
        assertTrue(root.childNode(2) instanceof TextNode);
        assertTrue(root.childNode(3) instanceof Comment);
    }

    @Test void emptyDeclarationAndPiInputsAreOutputtable() {
        assertEquals("", Jsoup.parse("", "", Parser.xmlParser()).html());
        assertEquals("<?xml version=\"1.0\"?>",
            Jsoup.parse("<?xml version=\"1.0\"?>", "", Parser.xmlParser()).html());
        assertEquals("<?pi data?>",
            Jsoup.parse("<?pi data?>", "", Parser.xmlParser()).html());
    }

    @Test void mutationsAreReflectedImmediatelyAndOutputIsStable() {
        Document doc = Jsoup.parse("<?pi one?><root>text</root><?pi two?>", Parser.xmlParser());
        ProcessingInstruction one = (ProcessingInstruction) doc.childNode(0);
        ProcessingInstruction two = (ProcessingInstruction) doc.childNode(2);
        Element root = (Element) doc.childNode(1);

        // modify data, move nodes, and delete one: the tree output must follow immediately
        one.data("updated");
        root.appendChild(two);
        assertEquals("<?pi updated?><root>text<?pi two?></root>", doc.html());

        one.remove();
        assertEquals("<root>text<?pi two?></root>", doc.html());
        assertEquals(doc.html(), doc.html()); // unchanged tree -> identical repeated output
    }

    @Test void detachedAndMultiDocumentNodesAreIndependent() {
        Document doc1 = Jsoup.parse("<?pi first?><r>one</r>", Parser.xmlParser());
        Document doc2 = Jsoup.parse("<?pi second?><r>two</r>", Parser.xmlParser());
        ProcessingInstruction moved = (ProcessingInstruction) doc1.childNode(0);

        moved.remove();
        assertEquals("<?pi first?>", moved.outerHtml()); // standalone output keeps existing defaults
        doc2.expectFirst("r").appendChild(moved);

        assertEquals("<r>one</r>", doc1.html());
        assertEquals("<?pi second?><r>two<?pi first?></r>", doc2.html());

        moved.data("changed");
        assertEquals("<r>one</r>", doc1.html()); // modifying one document cannot affect another
        assertEquals("<?pi second?><r>two<?pi changed?></r>", doc2.html());
    }

    @Test void survivesBoundariesAndMalformedFragments() {
        // whitespace, quotes, non-ASCII in declaration / PI / data
        String xml = "<?xml\n version='1.0'\n standalone='no' ?>" +
            "<?pi \"quotes '内'容\"  ?><r>é</r>";
        Document doc = Jsoup.parse(xml, Parser.xmlParser());
        ProcessingInstruction pi = (ProcessingInstruction) doc.childNode(1);
        assertEquals("pi", pi.target());
        assertEquals("\"quotes '内'容\"  ", pi.data()); // boundary whitespace in data is preserved

        // PI data may contain '>' and stray '<'; only ?> closes it, and following nodes are not swallowed
        Document free = Jsoup.parse("<?pi a > b ?><next/>", Parser.xmlParser());
        ProcessingInstruction freePi = (ProcessingInstruction) free.childNode(0);
        assertEquals("a > b ", freePi.data());
        assertEquals("next", free.childNode(1).nodeName());

        // unterminated PI is dropped cleanly; neighbors are not merged, no exception
        Document unterminated = Jsoup.parse("<?pi data", Parser.xmlParser());
        assertTrue(unterminated.childNodes().isEmpty());
    }
}
