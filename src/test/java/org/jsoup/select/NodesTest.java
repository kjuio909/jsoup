package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.SerializationException;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NodesTest {
    @Test void before() {
        Document doc = Jsoup.parse("<span>One</span> <span>Two</span> <span>Three</span>");
        Nodes<TextNode> nodes = doc.selectNodes("::text:contains(o)", TextNode.class);
        nodes.before("<wbr>");
        assertEquals("<span><wbr>One</span> <span><wbr>Two</span> <span>Three</span>", doc.body().html());
    }

    @Test void after() {
        Document doc = Jsoup.parse("<span>One</span> <span>Two</span> <span>Three</span>");
        Nodes<TextNode> nodes = doc.selectNodes("::text:contains(o)", TextNode.class);
        nodes.after("<wbr>");
        assertEquals("<span>One<wbr></span> <span>Two<wbr></span> <span>Three</span>", doc.body().html());
    }

    @Test void wrap() {
        Document doc = Jsoup.parse("<span>One</span> <span>Two</span> <span>Three</span>");
        Nodes<TextNode> nodes = doc.selectNodes("::text:contains(o)", TextNode.class);
        nodes.wrap("<b></b>");
        assertEquals("<span><b>One</b></span> <span><b>Two</b></span> <span>Three</span>", doc.body().html());
    }

    @Test void outerHtmlAppendableMatchesString() {
        Document doc = Jsoup.parse("<p>One</p><p>Two</p>");
        Nodes<Node> nodes = new Nodes<>(doc.body().childNodes());

        String expected = nodes.outerHtml();
        StringBuilder sb = new StringBuilder();
        assertSame(sb, nodes.outerHtml(sb));
        assertEquals(expected, sb.toString());
        assertEquals(expected, nodes.toString());
    }

    @Test void outerHtmlAppendableToWriter() {
        Document doc = Jsoup.parse("<div>One</div><div>Two</div>");
        Nodes<Element> divs = doc.select("div");

        String expected = divs.get(0).outerHtml() + "\n" + divs.get(1).outerHtml();
        StringWriter writer = new StringWriter();
        assertSame(writer, divs.outerHtml(writer));
        assertEquals(expected, writer.toString());
        assertEquals(expected, divs.outerHtml());
    }

    @Test void outerHtmlAppendablePreservesExistingContentAndHasNoLeadingNewline() {
        Document doc = Jsoup.parse("<p>One</p><p>Two</p>");
        Nodes<Node> nodes = new Nodes<>(doc.body().childNodes());

        StringBuilder sb = new StringBuilder("Prefix:");
        nodes.outerHtml(sb);
        assertEquals("Prefix:" + nodes.get(0).outerHtml() + "\n" + nodes.get(1).outerHtml(), sb.toString());
        assertFalse(sb.toString().startsWith("Prefix:\n"));
    }

    @Test void emptyNodesWritesNothing() {
        Nodes<Node> nodes = new Nodes<>();
        assertEquals("", nodes.outerHtml());

        StringBuilder sb = new StringBuilder("Untouched");
        nodes.outerHtml(sb);
        assertEquals("Untouched", sb.toString());

        StringWriter writer = new StringWriter();
        nodes.outerHtml(writer);
        assertEquals("", writer.toString());
    }

    @Test void mixedNodeTypesEachSerializedInOrder() {
        DocumentType doctype = new DocumentType("html", "", "");
        Comment comment = new Comment(" a comment ");
        Element p = new Element("p").text("One");
        Document doc = Jsoup.parse("<span>Two</span>");
        Nodes<Node> nodes = new Nodes<>(doctype, comment, p, doc);

        String expected = String.join("\n",
            doctype.outerHtml(), comment.outerHtml(), p.outerHtml(), doc.outerHtml());
        assertEquals(expected, nodes.outerHtml());
        assertEquals(expected, nodes.outerHtml(new StringBuilder()).toString());
    }

    @Test void duplicateReferencesAreNotDeduplicated() {
        Element p = new Element("p").text("One");
        Nodes<Node> nodes = new Nodes<>(p, p, p);
        assertEquals("<p>One</p>\n<p>One</p>\n<p>One</p>", nodes.outerHtml());
    }

    @Test void nodesFromDifferentDocumentsKeepTheirOwnSettings() {
        Document pretty = Jsoup.parse("<div><p>One</p></div>");
        Document flat = Jsoup.parse("<div><p>Two</p></div>");
        flat.outputSettings().prettyPrint(false);
        assertNotEquals(pretty.outerHtml(), flat.outerHtml()); // sanity

        Nodes<Node> nodes = new Nodes<>(pretty, flat, new Element("p").text("Three"));
        String expected = String.join("\n",
            pretty.outerHtml(), flat.outerHtml(), "<p>Three</p>");
        assertEquals(expected, nodes.outerHtml(new StringBuilder()).toString());
    }

    @Test void charsetSettingsArePerDocument() {
        Document utf8 = Jsoup.parse("<p>One x</p>");
        Document ascii = Jsoup.parse("<p>Two y</p>");
        ascii.outputSettings().charset("US-ASCII");

        Nodes<Node> nodes = new Nodes<>(utf8.selectFirst("p"), ascii.selectFirst("p"));
        String expected = utf8.selectFirst("p").outerHtml() + "\n" + ascii.selectFirst("p").outerHtml();
        assertEquals(expected, nodes.outerHtml());
        assertTrue(nodes.outerHtml().contains("Two&nbsp;y"));
    }

    @Test void elementsInheritsStreamingOuterHtml() {
        Document doc = Jsoup.parse("<p>One</p><p>Two</p>");
        Elements els = doc.select("p");

        assertEquals(els.outerHtml(), els.outerHtml(new StringBuilder()).toString());
        assertEquals(els.outerHtml(), els.outerHtml(new StringWriter()).toString());
    }

    @Test void outputIsRepeatableAndReflectsChangedSettings() {
        Document doc = Jsoup.parse("<div><p>One</p></div>");
        Nodes<Node> nodes = new Nodes<>(doc.selectFirst("div"));

        String first = nodes.outerHtml(new StringBuilder()).toString();
        String second = nodes.outerHtml(new StringBuilder()).toString();
        assertEquals(first, second);
        assertEquals(first, nodes.outerHtml());

        doc.outputSettings().prettyPrint(false);
        String third = nodes.outerHtml(new StringBuilder()).toString();
        assertNotEquals(first, third);
        assertEquals(nodes.outerHtml(), third);
    }

    @Test void doesNotModifyCollectionOrNodes() {
        Document doc = Jsoup.parse("<p>One</p><p>Two</p>");
        Nodes<Node> nodes = new Nodes<>(doc.body().childNodes());
        String beforeHtml = doc.body().html();

        nodes.outerHtml(new StringBuilder());
        nodes.outerHtml(new StringWriter());

        assertEquals(2, nodes.size());
        assertEquals(beforeHtml, doc.body().html());
    }

    @Test void separatorIsWrittenOnlyAfterPreviousNodeFullyWritten() {
        Element one = new Element("p").text("One");
        Element two = new Element("p").text("Two");
        Nodes<Node> nodes = new Nodes<>(one, two);

        RecordingAppendable recorder = new RecordingAppendable();
        nodes.outerHtml(recorder);

        int separator = recorder.events.indexOf("\n");
        assertTrue(separator > 0);
        StringBuilder beforeSeparator = new StringBuilder();
        for (int i = 0; i < separator; i++) beforeSeparator.append(recorder.events.get(i));
        assertEquals(one.outerHtml(), beforeSeparator.toString());
        assertEquals(one.outerHtml() + "\n" + two.outerHtml(), recorder.toString());
    }

    @Test void ioExceptionMidNodeWrapsWithCauseStopsAndKeepsPrefix() {
        Element one = new Element("p").text("One");
        Element two = new Element("p").text("Two");
        Nodes<Node> nodes = new Nodes<>(one, two);

        FlakyAppendable flaky = new FlakyAppendable("PRE:", 3); // writes prefix + 3 chars of first node, then fails
        SerializationException e = assertThrows(SerializationException.class, () -> nodes.outerHtml(flaky));
        assertNotNull(e.getCause());
        assertTrue(e.getCause() instanceof IOException);

        String prefix = flaky.toString();
        assertEquals("PRE:<p>", prefix);
        assertFalse(prefix.contains("\n"));
        assertFalse(prefix.contains("Two")); // never attempted the second node
    }

    @Test void ioExceptionWritingSeparatorStopsBeforeNextNode() {
        Element one = new Element("p").text("One");
        Element two = new Element("p").text("Two");
        Nodes<Node> nodes = new Nodes<>(one, two);

        // Records everything written before an IOException; refuses newlines.
        StringBuilder seen = new StringBuilder();
        Appendable noNewlines = new Appendable() {
            @Override public Appendable append(CharSequence csq, int start, int end) throws IOException {
                for (int i = start; i < end; i++) append(csq.charAt(i));
                return this;
            }
            @Override public Appendable append(CharSequence csq) throws IOException {
                return append(csq, 0, csq.length());
            }
            @Override public Appendable append(char c) throws IOException {
                if (c == '\n') throw new IOException("no newlines");
                seen.append(c);
                return this;
            }
        };

        SerializationException e = assertThrows(SerializationException.class, () -> nodes.outerHtml(noNewlines));
        assertTrue(e.getCause() instanceof IOException);
        // First node was fully written; the separator failed, so the second node was never attempted.
        assertEquals(one.outerHtml(), seen.toString());
        assertNotEquals(-1, seen.indexOf("</p>"));
        assertFalse(seen.toString().contains("Two"));
    }

    /** Records every append call individually, so the write ordering can be inspected. */
    static final class RecordingAppendable implements Appendable {
        final List<String> events = new ArrayList<>();

        @Override public Appendable append(CharSequence csq) {
            events.add(csq.toString());
            return this;
        }
        @Override public Appendable append(CharSequence csq, int start, int end) {
            events.add(csq.subSequence(start, end).toString());
            return this;
        }
        @Override public Appendable append(char c) {
            events.add(String.valueOf(c));
            return this;
        }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            for (String event : events) sb.append(event);
            return sb.toString();
        }
    }

    /** Accepts an initial prefix and then a fixed number of further characters, then always throws. */
    static final class FlakyAppendable implements Appendable {
        private final StringBuilder sb = new StringBuilder();
        private int remaining;

        FlakyAppendable(String prefix, int succeedChars) {
            sb.append(prefix);
            this.remaining = succeedChars;
        }

        @Override public Appendable append(CharSequence csq) throws IOException {
            return append(csq, 0, csq.length());
        }
        @Override public Appendable append(CharSequence csq, int start, int end) throws IOException {
            for (int i = start; i < end; i++) append(csq.charAt(i));
            return this;
        }
        @Override public Appendable append(char c) throws IOException {
            if (remaining-- <= 0) throw new IOException("boom");
            sb.append(c);
            return this;
        }
        @Override public String toString() {
            return sb.toString();
        }
    }
}
