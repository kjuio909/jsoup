package org.jsoup.safety;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.NodeInternals;
import org.jsoup.nodes.Range;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.ParseErrorList;
import org.jsoup.parser.Parser;
import org.jsoup.internal.StringUtil;
import org.jsoup.select.NodeVisitor;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.jsoup.internal.SharedConstants.DummyUri;

/**
 The {@link Safelist}-based HTML cleaner. Use to ensure that end-user provided HTML contains only the elements and attributes
 that you are expecting; no junk, and no cross-site scripting attacks!
 <p>
 The HTML cleaner parses the input as HTML and then runs it through a safelist, so the output HTML can only contain
 HTML that is allowed by the safelist.
 </p>
 <p>
 It is assumed that the input HTML is a body fragment; the clean methods only pull from the source's body, and the
 canned safelists only allow body-contained tags.
 </p>
 <p>
 Rather than interacting directly with a Cleaner object, generally see the {@code clean} methods in {@link org.jsoup.Jsoup}.
 </p>
 <p>
 A Cleaner may be reused across multiple documents and shared across concurrent threads once its {@link Safelist} has
 been configured. The cleaner uses the supplied safelist directly, so later safelist changes affect later cleaning
 calls. If you need a variant of an existing configuration, use {@link Safelist#Safelist(Safelist)} to make a copy.
 </p>
 */
public class Cleaner {
    private final Safelist safelist;

    /**
     Create a new cleaner, that sanitizes documents using the supplied safelist.
     @param safelist safe-list to clean with
     */
    public Cleaner(Safelist safelist) {
        Validate.notNull(safelist);
        this.safelist = safelist;
    }

    /**
     Creates a new, clean document, from the original dirty document, containing only elements allowed by the safelist.
     The original document is not modified. Only elements from the dirty document's <code>body</code> are used. The
     OutputSettings of the original document are cloned into the clean document.
     @param dirtyDocument Untrusted base document to clean.
     @return cleaned document.
     */
    public Document clean(Document dirtyDocument) {
        Validate.notNull(dirtyDocument);

        Document clean = Document.createShell(dirtyDocument.baseUri());
        copySafeNodes(dirtyDocument.body(), clean.body());
        clean.outputSettings(dirtyDocument.outputSettings().clone());
        if (clean.outputSettings().prettyPrint())
            normaliseLayoutWhitespace(clean.body());

        return clean;
    }

    /**
     Removes non-significant whitespace text nodes from the cleaned tree so that the output is a fixed point under a
     clean -> serialize -> reparse -> clean round trip.

     <p>The pretty printer emits its own line breaks and indentation around block-level nodes (and {@code <br>}). For
     block parents, any whitespace text node butting against such a node is trimmed while printing. For inline
     parents (an {@code <a>}, {@code <b>}, and so on that contain block-level content), the whitespace is normally
     significant and printed literally; the printer then adds its own indentation as well. On reparse that
     indentation becomes a literal text node, so a second clean would gain extra spaces (and so on). The nodes
     responsible are the whitespace-only text nodes sitting at these block boundaries; dropping them changes no
     visible text, since the boundary (or the word gap into a block element) is rendered independently. Whitespace
     attached to real words (e.g. {@code "Hello "}) and anything inside a whitespace-preserving context such as
     {@code <pre>}, {@code <code>}, or {@code <textarea>} is left untouched.</p>
     */
    private void normaliseLayoutWhitespace(Element root) {
        for (Element el : root.getAllElements()) {
            if (!isInlineContainer(el)) continue;
            if (preservesWhitespace(el)) continue;
            boolean blockLayout = isBlockLike(nextNonBlank(el.firstChild()));

            List<Node> children = new ArrayList<>(el.childNodes()); // live references; childNodesCopy() returns clones
            for (Node child : children) {
                if (!(child instanceof TextNode)) continue;
                TextNode text = (TextNode) child;
                Node prevNonBlank = previousNonBlank(text.previousSibling());
                Node nextNonBlank = nextNonBlank(text.nextSibling());
                boolean facesBlockPrev = isBlockLike(prevNonBlank);
                boolean facesBlockNext = isBlockLike(nextNonBlank);
                boolean atClosingEdge = nextNonBlank == null && blockLayout;

                if (text.isBlank()) {
                    // whitespace-only node: drop it wherever the printer emits its own line break -- next to a block
                    // sibling, or at the element's indented close tag
                    if (facesBlockPrev || facesBlockNext || atClosingEdge)
                        text.remove();
                } else if (facesBlockNext || atClosingEdge) {
                    // Last real content before a directly-following block sibling, or before the element's own
                    // indented close tag: trailing ASCII whitespace is the (regenerated) line break. The emitted
                    // newline re-parses as the word gap, so rendered text is unchanged; &nbsp; and leading text kept.
                    trimTrailingHtmlWhitespace(text);
                }
            }
        }
    }

    private static void trimTrailingHtmlWhitespace(TextNode text) {
        String whole = text.getWholeText();
        int end = whole.length();
        while (end > 0 && isHtmlWhitespace(whole.charAt(end - 1))) end--;
        if (end < whole.length()) text.text(whole.substring(0, end));
    }

    /** An element that lays out inline but can be found containing block-level children after cleaning. */
    private static boolean isInlineContainer(Element el) {
        return !isBlockLike(el);
    }

    /** Mirrors the pretty printer's notion of a node that gets its own line: block elements, {@code <br>}, and
     unknown elements that contain block content. */
    private static boolean isBlockLike(Node node) {
        if (!(node instanceof Element)) return false;
        Element el = (Element) node;
        return el.nameIs("br") || el.tag().isBlock()
            || (!el.tag().isKnownTag() && hasBlockChild(el));
    }

    private static boolean hasBlockChild(Element el) {
        Element child = el.firstElementChild();
        for (int i = 0; i < 5 && child != null; i++) {
            if (child.tag().isBlock() || !child.tag().isKnownTag()) return true;
            child = child.nextElementSibling();
        }
        return false;
    }

    /** Whether the element is, or sits within five levels of, a tag such as pre/code/textarea that keeps
     whitespace verbatim (mirrors Element#preserveWhitespace). */
    private static boolean preservesWhitespace(Node node) {
        int depth = 0;
        while (node instanceof Element && depth < 6) {
            if (((Element) node).tag().preserveWhitespace()) return true;
            node = node.parentNode();
            depth++;
        }
        return false;
    }

    private static Node previousNonBlank(Node node) {
        while (isBlankText(node)) node = node.previousSibling();
        return node;
    }

    private static Node nextNonBlank(Node node) {
        while (isBlankText(node)) node = node.nextSibling();
        return node;
    }

    private static boolean isBlankText(Node node) {
        return node instanceof TextNode && ((TextNode) node).isBlank();
    }

    /**
     Determines if the input document's <b>body</b> is valid, against the safelist. It is considered valid if all the
     tags and attributes in the input HTML are allowed by the safelist, and that there is no content in the
     <code>head</code>.
     <p>
     This method is intended to be used in a user interface as a validator for user input. Note that regardless of the
     output of this method, the input document <b>must always</b> be normalized using a method such as
     {@link #clean(Document)}, and the result of that method used to store or serialize the document before later reuse
     such as presentation to end users. This ensures that enforced attributes are set correctly, and that any
     differences between how a given browser and how jsoup parses the input HTML are normalized.
     </p>
     <p>Example:
     <pre>{@code
     Document inputDoc = Jsoup.parse(inputHtml);
     Cleaner cleaner = new Cleaner(Safelist.relaxed());
     boolean isValid = cleaner.isValid(inputDoc);
     Document normalizedDoc = cleaner.clean(inputDoc);
     }</pre>
     </p>
     @param dirtyDocument document to test
     @return true if no tags or attributes need to be removed; false if they do
     */
    public boolean isValid(Document dirtyDocument) {
        Validate.notNull(dirtyDocument);

        Document clean = Document.createShell(dirtyDocument.baseUri());
        int numDiscarded = copySafeNodes(dirtyDocument.body(), clean.body());
        return numDiscarded == 0
            && dirtyDocument.head().childNodes().isEmpty(); // because we only look at the body, but we start from a shell, make sure there's nothing in the head
    }

    /**
     Determines if the input document's <b>body HTML</b> is valid, against the safelist. It is considered valid if all
     the tags and attributes in the input HTML are allowed by the safelist.
     <p>
     This method is intended to be used in a user interface as a validator for user input. Note that regardless of the
     output of this method, the input document <b>must always</b> be normalized using a method such as
     {@link #clean(Document)}, and the result of that method used to store or serialize the document before later reuse
     such as presentation to end users. This ensures that enforced attributes are set correctly, and that any
     differences between how a given browser and how jsoup parses the input HTML are normalized.
     </p>
     <p>Example:
     <pre>{@code
     Document inputDoc = Jsoup.parse(inputHtml);
     Cleaner cleaner = new Cleaner(Safelist.relaxed());
     boolean isValid = cleaner.isValidBodyHtml(inputHtml);
     Document normalizedDoc = cleaner.clean(inputDoc);
     }</pre>
     </p>
     @param bodyHtml HTML fragment to test
     @return true if no tags or attributes need to be removed; false if they do
     */
    public boolean isValidBodyHtml(String bodyHtml) {
        String baseUri = (safelist.preserveRelativeLinks()) ? DummyUri : ""; // fake base URI to allow relative URLs to remain valid
        Document clean = Document.createShell(baseUri);
        Document dirty = Document.createShell(baseUri);
        ParseErrorList errorList = ParseErrorList.tracking(1);
        List<Node> nodes = Parser.parseFragment(bodyHtml, dirty.body(), baseUri, errorList);
        dirty.body().insertChildren(0, nodes);
        int numDiscarded = copySafeNodes(dirty.body(), clean.body());
        return numDiscarded == 0 && errorList.isEmpty();
    }

    /**
     Iterates the input and copies trusted nodes (tags, attributes, text) into the destination.
     */
    private final class CleaningVisitor implements NodeVisitor {
        private int numDiscarded = 0;
        private final Element root;
        private Element destination; // current element to append nodes to

        private CleaningVisitor(Element root, Element destination) {
            this.root = root;
            this.destination = destination;
        }

        @Override public void head(Node source, int depth) {
            if (source instanceof Element) {
                Element sourceEl = (Element) source;

                if (safelist.isSafeTag(sourceEl.normalName())) { // safe, clone and copy safe attrs
                    ElementMeta meta = createSafeElement(sourceEl);
                    Element destChild = meta.el;
                    destination.appendChild(destChild);

                    numDiscarded += meta.numAttribsDiscarded;
                    destination = destChild;
                } else if (source != root) { // not a safe tag, so don't add. don't count root against discarded.
                    numDiscarded++;
                }
            } else if (source instanceof TextNode) {
                TextNode sourceText = (TextNode) source;
                appendText(destination, sourceText.getWholeText());
            } else if (source instanceof DataNode && safelist.isSafeTag(source.parent().normalName())) {
                DataNode sourceData = (DataNode) source;
                DataNode destData = new DataNode(sourceData.getWholeData());
                destination.appendChild(destData);
            } else { // else, we don't care about comments, xml proc instructions, etc
                numDiscarded++;
            }
        }

        @Override public void tail(Node source, int depth) {
            if (source instanceof Element && safelist.isSafeTag(source.normalName())) {
                destination = destination.parent(); // would have descended, so pop destination stack
            }
        }

        /**
         Appends source text to the destination. When the preceding source node was discarded (leaving this text
         directly adjacent to an earlier text node in the output), the runs are joined into one text node. This
         mirrors how the serialized output is re-parsed later -- adjacent text nodes are merged then -- and keeps
         whitespace normalization from collapsing across the join differently on a second clean.
         */
        private void appendText(Element dest, String text) {
            Node last = dest.lastChild();
            if (last != null && last.getClass() == TextNode.class) {
                TextNode lastText = (TextNode) last;
                lastText.text(lastText.getWholeText() + text);
            } else {
                dest.appendChild(new TextNode(text));
            }
        }
    }

    private int copySafeNodes(Element source, Element dest) {
        CleaningVisitor cleaningVisitor = new CleaningVisitor(source, dest);
        cleaningVisitor.traverse(source);
        return cleaningVisitor.numDiscarded;
    }

    private ElementMeta createSafeElement(Element sourceEl) {
        Element dest = sourceEl.shallowClone(); // reuses tag, clones attributes and preserves any user data
        String sourceTag = sourceEl.tagName();
        Attributes destAttrs = dest.attributes();
        dest.clearAttributes(); // clear all non-internal attributes, ready for safe copy

        int numDiscarded = 0;
        Attributes sourceAttrs = sourceEl.attributes();
        for (Attribute sourceAttr : sourceAttrs) {
            if (safelist.isSafeAttribute(sourceTag, sourceEl, sourceAttr)) { // will keep this attr
                String key = sourceAttr.getKey();
                String value = sourceAttr.getValue();

                if (safelist.shouldAbsUrl(sourceTag, key)) { // configured to make absolute urls for this key (href)
                    if (Safelist.multiValueUrlAttribute(key)) { // srcset etc: resolve each candidate independently
                        value = resolveCandidateUrls(sourceEl, value);
                    } else {
                        value = sourceEl.absUrl(key);
                        if (value.isEmpty()) // could not be made abs; leave as-is to allow custom unknown protocols
                            value = sourceAttr.getValue();
                    }
                }
                Range.AttributeRange range = sourceAttrs.sourceRange(key);
                destAttrs.put(key, value);
                NodeInternals.attributeRange(destAttrs, key, range);
            } else
                numDiscarded++;
        }

        Attributes enforcedAttrs = safelist.getEnforcedAttributes(sourceTag);
        // special case for <a href rel=nofollow>, only apply to external links:
        if (sourceEl.nameIs("a") && enforcedAttrs.get("rel").equals("nofollow")) {
            // only a link whose href actually passed the safelist (and so is retained) can be same-site; a dropped or
            // unsafe href must keep the enforced nofollow, otherwise cleaning the href-less output a second time
            // would add it and break idempotency
            Attribute hrefAttr = sourceEl.attribute("href");
            if (hrefAttr != null && safelist.isSafeAttribute(sourceTag, sourceEl, hrefAttr)) {
                String href = sourceEl.absUrl("href");
                if (!href.isEmpty()) {
                    try {
                        URL baseUrl = new URL(sourceEl.baseUri());
                        URL linkUrl = new URL(href);
                        String baseHost = baseUrl.getHost();
                        if (!baseHost.isEmpty() && baseHost.equalsIgnoreCase(linkUrl.getHost())) // same site, so don't set the nofollow
                            enforcedAttrs.remove("rel");
                    } catch (MalformedURLException ignored) {}
                }
            }
        }

        // apply enforced attributes case-insensitively, so a preserved-case source attr is canonicalized to the enforced key
        for (Attribute enforcedAttr : enforcedAttrs) {
            destAttrs.removeIgnoreCase(enforcedAttr.getKey());
            destAttrs.put(enforcedAttr.getKey(), enforcedAttr.getValue());
        }
        dest.attributes().addAll(destAttrs); // re-attach, if removed in clear
        return new ElementMeta(dest, numDiscarded);
    }

    private static boolean isHtmlWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /**
     Resolve each comma-separated candidate URL (e.g. in a {@code srcset} attribute) against the element's base URI,
     preserving the candidate descriptors and the original separators. Returns the source value untouched when none of
     the candidates changed, so that already-normalized output is stable on a second clean.
     */
    private String resolveCandidateUrls(Element sourceEl, String value) {
        String baseUri = sourceEl.baseUri();
        int len = value.length();
        StringBuilder out = null;
        int start = 0;
        for (int i = 0; i <= len; i++) {
            if (i != len && value.charAt(i) != ',') continue;

            String segment = value.substring(start, i);
            int segLen = segment.length();
            int urlStart = 0;
            while (urlStart < segLen && isHtmlWhitespace(segment.charAt(urlStart))) urlStart++;
            int urlEnd = urlStart;
            while (urlEnd < segLen && !isHtmlWhitespace(segment.charAt(urlEnd))) urlEnd++;

            boolean changed = false;
            if (urlEnd > urlStart) {
                String candidate = segment.substring(urlStart, urlEnd);
                String resolved = StringUtil.resolve(baseUri, candidate);
                if (resolved.isEmpty()) resolved = candidate; // mirror single-URL behavior: leave unresolvable as-is
                if (!resolved.equals(candidate)) {
                    changed = true;
                    if (out == null) {
                        out = new StringBuilder(len + 16);
                        out.append(value, 0, start); // everything before the first changed candidate, flushed once
                    }
                    out.append(segment, 0, urlStart).append(resolved).append(segment, urlEnd, segLen);
                }
            }
            if (!changed && out != null) out.append(segment);
            if (out != null && i < len) out.append(',');

            start = i + 1;
        }
        return out == null ? value : out.toString();
    }

    private static class ElementMeta {
        Element el;
        int numAttribsDiscarded;

        ElementMeta(Element el, int numAttribsDiscarded) {
            this.el = el;
            this.numAttribsDiscarded = numAttribsDiscarded;
        }
    }

}
