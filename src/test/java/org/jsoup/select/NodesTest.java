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

    @Test void streamOuterHtml() {
        Document doc = Jsoup.parse("<div>One</div><div>Two</div>");
        Nodes<Element> divs = doc.select("div");

        StringWriter writer = new StringWriter();
        assertSame(writer, divs.outerHtml(writer));
        assertEquals(divs.outerHtml(), writer.toString());
        assertEquals(divs.toString(), writer.toString());
        assertEquals("<div>One</div>\n<div>Two</div>", writer.toString());
    }

    @Test void streamEmptyWritesNothing() {
        Nodes<Node> nodes = new Nodes<>();
        StringWriter writer = new StringWriter();
        nodes.outerHtml(writer);
        assertEquals(0, writer.getBuffer().length());
        assertEquals("", nodes.outerHtml());
    }

    @Test void streamPreservesExistingContents() {
        Document doc = Jsoup.parse("<p>One</p><p>Two</p>");
        Nodes<Element> ps = doc.select("p");
        StringWriter writer = new StringWriter();
        writer.write("PREFIX-");
        ps.outerHtml(writer);
        assertEquals("PREFIX-<p>One</p>\n<p>Two</p>", writer.toString());
    }

    @Test void streamNoLeadingOrTrailingNewline() {
        Document doc = Jsoup.parse("<p>Only</p>");
        Nodes<Element> ps = doc.select("p");
        StringWriter writer = new StringWriter();
        ps.outerHtml(writer);
        String out = writer.toString();
        assertFalse(out.startsWith("\n"));
        assertFalse(out.endsWith("\n"));
    }

    @Test void streamMixedNodeTypes() {
        Document doc = Jsoup.parse("<!DOCTYPE html><p>Hello</p>");
        Comment comment = new Comment("a comment");
        DocumentType doctype = doc.documentType();
        Element p = doc.expectFirst("p");
        Nodes<Node> mixed = new Nodes<>(doctype, p, comment);

        StringWriter writer = new StringWriter();
        mixed.outerHtml(writer);
        assertEquals(doctype.outerHtml() + "\n" + p.outerHtml() + "\n" + comment.outerHtml(), writer.toString());
        assertEquals(mixed.outerHtml(), writer.toString());
        assertTrue(writer.toString().startsWith("<!doctype html>"));
        assertTrue(writer.toString().endsWith("<!--a comment-->"));
    }

    @Test void streamRepeatedReferencesNotDeduped() {
        Document doc = Jsoup.parse("<p>X</p>");
        Element p = doc.expectFirst("p");
        @SuppressWarnings("unchecked")
        Nodes<Node> nodes = new Nodes<>(p, p, p);

        StringWriter writer = new StringWriter();
        nodes.outerHtml(writer);
        assertEquals("<p>X</p>\n<p>X</p>\n<p>X</p>", writer.toString());
        assertEquals(3, nodes.size());
    }

    @Test void streamInheritedByElements() {
        Document doc = Jsoup.parse("<a>1</a><a>2</a>");
        Elements els = doc.select("a");
        StringWriter writer = new StringWriter();
        els.outerHtml(writer);
        assertEquals("<a>1</a>\n<a>2</a>", writer.toString());
    }

    @Test void streamHonorsPerDocumentSettings() {
        Document pretty = Jsoup.parse("<div><p>Pretty</p></div>");
        Document flat = Jsoup.parse("<div><p>Flat</p></div>");
        flat.outputSettings().prettyPrint(false);

        Document unicode = Jsoup.parse("<p>x</p>");
        unicode.outputSettings().prettyPrint(false);
        unicode.expectFirst("p").text("100 — dash");
        unicode.outputSettings().charset("US-ASCII");

        Nodes<Node> nodes = new Nodes<>(
            pretty.expectFirst("div"),
            flat.expectFirst("div"),
            unicode.expectFirst("p"));

        StringWriter writer = new StringWriter();
        nodes.outerHtml(writer);
        assertEquals(
            pretty.expectFirst("div").outerHtml() + "\n" +
            flat.expectFirst("div").outerHtml() + "\n" +
            unicode.expectFirst("p").outerHtml(),
            writer.toString());
        // each section rendered with its own settings
        assertTrue(writer.toString().contains("<div>\n <p>Pretty</p>\n</div>")); // pretty printed
        assertTrue(writer.toString().contains("<div><p>Flat</p></div>"));       // not pretty printed
        assertTrue(writer.toString().contains("&#x2014;"));                    // ASCII escaped
        assertFalse(pretty.outputSettings().prettyPrint() == flat.outputSettings().prettyPrint());
    }

    @Test void streamDetachedNodeUsesDefaults() {
        Element detached = new Element("p").text("A & B");
        Nodes<Node> nodes = new Nodes<>(detached);
        StringWriter writer = new StringWriter();
        nodes.outerHtml(writer);
        assertEquals(detached.outerHtml(), writer.toString());
        assertEquals("<p>A &amp; B</p>", writer.toString());
    }

    @Test void streamDoesNotModifyCollection() {
        Document doc = Jsoup.parse("<p>One</p><p>Two</p>");
        Nodes<Element> ps = doc.select("p");
        ps.outerHtml(new StringWriter());
        ps.outerHtml(new StringWriter());
        assertEquals(2, ps.size());
        assertEquals("<p>One</p><p>Two</p>", doc.body().html().replace("\n", ""));
    }

    @Test void streamReflectsChangedSettingsAndIsRepeatable() {
        Document doc = Jsoup.parse("<div><p>Hi</p></div>");
        Nodes<Element> nodes = doc.select("div");

        String first = nodes.outerHtml(new StringWriter()).toString();
        String again = nodes.outerHtml(new StringWriter()).toString();
        assertEquals(first, again);

        doc.outputSettings().prettyPrint(false);
        String flat = nodes.outerHtml(new StringWriter()).toString();
        assertNotEquals(first, flat);
        assertEquals("<div><p>Hi</p></div>", flat);
    }

    @Test void streamIOExceptionOnSeparatorStopsWithPrefixVisible() {
        Document doc = Jsoup.parse("<p>One</p><p>Two</p>");
        Nodes<Element> ps = doc.select("p");

        // fails the moment the separator after the first node is written
        Appendable flaky = new FlakyAppendable(Integer.MAX_VALUE, true);
        SerializationException e = assertThrows(SerializationException.class, () -> ps.outerHtml(flaky));
        assertTrue(e.getCause() instanceof IOException);
        // first node fully written, separator failed, second node never attempted
        assertEquals("<p>One</p>", flaky.toString());
    }

    @Test void streamIOExceptionInNodeStopsAndKeepsPrefix() {
        Document doc = Jsoup.parse("<p>One</p><p>TwoXYZ</p>");
        Nodes<Element> ps = doc.select("p");

        // allows the first node plus its separator, then fails mid-way through the second node
        int prefix = "<p>One</p>\n".length();
        FlakyAppendable flaky = new FlakyAppendable(prefix + 2, false);
        SerializationException e = assertThrows(SerializationException.class, () -> ps.outerHtml(flaky));
        assertTrue(e.getCause() instanceof IOException);
        String written = flaky.toString();
        assertTrue(written.startsWith("<p>One</p>\n"));
        assertTrue(written.startsWith("<p>One</p>\n<p"));
        assertFalse(written.contains("Two"));
    }

    @Test void streamToStringBuilderMatchesOuterHtml() {
        Document doc = Jsoup.parse("<!DOCTYPE html><div>One</div><!--c--><div>Two</div>");
        Nodes<Node> nodes = new Nodes<>(doc.documentType(), doc.expectFirst("div"), new Comment("c"));

        StringBuilder sb = new StringBuilder("existing:");
        nodes.outerHtml(sb);
        assertEquals("existing:" + nodes.outerHtml(), sb.toString());
        assertEquals(nodes.outerHtml(), nodes.toString());
    }

    /** Appendable backed by a StringBuilder that throws an IOException once a threshold of chars is written,
     *  or immediately on newline separators. */
    static final class FlakyAppendable implements Appendable {
        private final StringBuilder sb = new StringBuilder();
        private int remaining;
        private final boolean failOnNewline;

        FlakyAppendable(int charBudget, boolean failOnNewline) {
            this.remaining = charBudget;
            this.failOnNewline = failOnNewline;
        }

        @Override public Appendable append(CharSequence csq, int start, int end) throws IOException {
            for (int i = start; i < end; i++) append(csq.charAt(i));
            return this;
        }

        @Override public Appendable append(char c) throws IOException {
            if (failOnNewline && c == '\n') throw new IOException("boom");
            if (remaining-- <= 0) throw new IOException("full");
            sb.append(c);
            return this;
        }

        @Override public Appendable append(CharSequence csq) throws IOException {
            return append(csq, 0, csq.length());
        }

        @Override public String toString() {
            return sb.toString();
        }
    }
}
