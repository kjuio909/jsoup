package org.jsoup.safety;

import org.jsoup.helper.Validate;
import org.jsoup.internal.StringUtil;
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
import org.jsoup.select.NodeVisitor;

import java.net.MalformedURLException;
import java.net.URL;
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

        return clean;
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
                TextNode destText = new TextNode(sourceText.getWholeText());
                destination.appendChild(destText);
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
            String key = sourceAttr.getKey();

            if (SrcSetKey.equalsIgnoreCase(key) &&
                safelist.isAttributeAllowed(sourceTag, SrcSetKey)) { // srcset allowed: validate each candidate
                SrcSetClean result = cleanSrcSet(sourceEl, sourceTag, sourceAttr.getValue());
                if (result.value == null) { // blank input, or no candidate survived: remove the attribute
                    numDiscarded += Math.max(1, result.discarded);
                    continue;
                }
                numDiscarded += result.discarded;
                Range.AttributeRange range = sourceAttrs.sourceRange(key);
                destAttrs.put(key, result.value);
                NodeInternals.attributeRange(destAttrs, key, range);
            } else if (safelist.isSafeAttribute(sourceTag, sourceEl, sourceAttr)) { // will keep this attr
                String value = sourceAttr.getValue();

                if (safelist.shouldAbsUrl(sourceTag, key)) { // configured to make absolute urls for this key (href)
                    value = sourceEl.absUrl(key);
                    if (value.isEmpty()) // could not be made abs; leave as-is to allow custom unknown protocols
                        value = sourceAttr.getValue();
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

        // apply enforced attributes case-insensitively, so a preserved-case source attr is canonicalized to the enforced key
        for (Attribute enforcedAttr : enforcedAttrs) {
            destAttrs.removeIgnoreCase(enforcedAttr.getKey());
            destAttrs.put(enforcedAttr.getKey(), enforcedAttr.getValue());
        }
        dest.attributes().addAll(destAttrs); // re-attach, if removed in clear
        return new ElementMeta(dest, numDiscarded);
    }

    private static class ElementMeta {
        Element el;
        int numAttribsDiscarded;

        ElementMeta(Element el, int numAttribsDiscarded) {
            this.el = el;
            this.numAttribsDiscarded = numAttribsDiscarded;
        }
    }

    // The srcset attribute name, for which candidates are parsed and protocol-checked independently.
    private static final String SrcSetKey = "srcset";

    /** Holds a cleaned srcset value and the number of candidates that were dropped. */
    private static final class SrcSetClean {
        final String value; // normalized value, or null if no candidate survived (remove the attribute)
        final int discarded; // count of invalid/dropped candidates

        SrcSetClean(String value, int discarded) {
            this.value = value;
            this.discarded = discarded;
        }
    }

    /**
     Clean an {@code srcset} attribute value: parse it into image candidates, validate each candidate's URL against
     the safelist rules of the containing tag, and serialize the survivors in a stable, idempotent form.
     <p>Parsing follows the srcset candidate rules: a comma directly surrounded by non-whitespace URL characters is
     part of the URL (as in {@code data:image/png;base64,AAAA}); any other comma separates candidates, optional
     whitespace around it is insignificant, and consecutive separators produce empty, invalid candidates. After the
     URL, at most one descriptor ({@code Nw} or {@code Nx}) may follow, introduced by whitespace.</p>
     @param sourceEl the owning element (supplies the base URI for relative/protocol resolution)
     @param sourceTag the owning tag's name
     @param srcset the raw attribute value
     @return the normalized value (or {@code null} if no candidate survived) plus the number of dropped candidates
     */
    private SrcSetClean cleanSrcSet(Element sourceEl, String sourceTag, String srcset) {
        StringBuilder out = new StringBuilder();
        int discarded = 0;
        int len = srcset.length();
        int pos = 0;

        while (pos < len) {
            // splitting loop: ignore leading whitespace; a comma here marks an empty candidate (leading or repeated
            // separator), which is invalid
            while (pos < len && isAsciiWhitespace(srcset.charAt(pos)))
                pos++;
            if (pos >= len)
                break;
            if (srcset.charAt(pos) == ',') {
                discarded++;
                pos++;
                continue;
            }

            // URL collection: consecutive non-whitespace characters. A comma belongs to the URL when the character
            // after it is also a URL character (not whitespace or another comma), as in
            // data:image/png;base64,AAAA; otherwise it ends the candidate and is consumed by the next splitting
            // iteration. Consecutive commas therefore yield empty, invalid candidates.
            int urlStart = pos;
            while (pos < len) {
                char c = srcset.charAt(pos);
                if (isAsciiWhitespace(c))
                    break;
                if (c == ',') {
                    boolean embedded = pos + 1 < len && isRegularUrlChar(srcset.charAt(pos + 1));
                    if (!embedded)
                        break;
                }
                pos++;
            }
            String url = srcset.substring(urlStart, pos);

            // descriptor tokenizer: whitespace then, optionally, exactly one descriptor token; further tokens or a
            // bad descriptor invalidate the candidate
            String descriptor = null;
            boolean invalid = false;
            while (pos < len) {
                while (pos < len && isAsciiWhitespace(srcset.charAt(pos)))
                    pos++;
                if (pos >= len || srcset.charAt(pos) == ',')
                    break;
                int tokenStart = pos;
                while (pos < len && !isAsciiWhitespace(srcset.charAt(pos)) && srcset.charAt(pos) != ',')
                    pos++;
                String token = srcset.substring(tokenStart, pos);
                if (descriptor == null)
                    descriptor = token;
                else
                    invalid = true; // more than one descriptor token
            }
            if (!invalid && descriptor != null && !isValidSrcSetDescriptor(descriptor))
                invalid = true;

            if (!invalid) {
                String safeUrl = resolveSrcSetUrl(sourceEl, sourceTag, url);
                if (safeUrl != null) {
                    if (out.length() > 0)
                        out.append(", ");
                    out.append(safeUrl);
                    if (descriptor != null)
                        out.append(' ').append(descriptor);
                } else {
                    discarded++; // failed this tag's protocol rules, or relative links are not preserved
                }
            } else {
                discarded++;
            }

            if (pos < len && srcset.charAt(pos) == ',')
                pos++; // consume the candidate-ending comma
        }

        return new SrcSetClean(out.length() == 0 ? null : out.toString(), discarded);
    }

    /**
     Run the existing per-attribute protocol check for one srcset candidate URL and, when the safelist normalizes URL
     attributes for this tag (relative links not preserved), return the resolved absolute URL.
     @return the URL to output, or {@code null} if the candidate fails the tag's protocol rules
     */
    private String resolveSrcSetUrl(Element sourceEl, String sourceTag, String url) {
        if (!safelist.isSafeAttributeValue(sourceTag, sourceEl, SrcSetKey, url))
            return null;
        if (safelist.shouldAbsUrl(sourceTag, SrcSetKey)) {
            String resolved = StringUtil.resolve(sourceEl.baseUri(), url);
            return resolved.isEmpty() ? url : resolved; // mirror createSafeElement's fallback for custom protocols
        }
        return url;
    }

    /**
     Validate an srcset descriptor: a positive integer width ({@code 123w}) or a positive number density
     ({@code 2x}). A density number is plain digits or digits-dot-digits; signs, exponents, missing fraction
     digits, and zero values are rejected.
     */
    private static boolean isValidSrcSetDescriptor(String descriptor) {
        int len = descriptor.length();
        if (len < 2)
            return false;
        char suffix = descriptor.charAt(len - 1);

        if (suffix == 'w')
            return isPositiveInteger(descriptor, 0, len - 1);
        if (suffix == 'x')
            return isPositiveNumber(descriptor, 0, len - 1);
        return false; // unknown descriptor
    }

    /** A positive integer: one or more ASCII digits with a value greater than zero. */
    private static boolean isPositiveInteger(String s, int from, int to) {
        if (from >= to)
            return false;
        boolean nonZero = false;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9')
                return false;
            if (c != '0')
                nonZero = true;
        }
        return nonZero;
    }

    /**
     A positive number in plain-digit form ({@code 2}, {@code 1.5}) or digits-dot-digits ({@code 2.0}); no sign,
     exponent, or dangling decimal point. Zero values are rejected.
     */
    private static boolean isPositiveNumber(String s, int from, int to) {
        if (from >= to)
            return false;
        int dot = -1;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (dot != -1)
                    return false; // more than one dot
                dot = i;
            } else if (c < '0' || c > '9') {
                return false; // sign, exponent, or any other character
            }
        }
        if (dot != -1 && (dot == from || dot == to - 1))
            return false; // dot must have digits on both sides
        // reject a zero value (e.g. 0, 0.0, 00.000)
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c != '0' && c != '.')
                return true;
        }
        return false;
    }

    private static boolean isRegularUrlChar(char c) {
        return c != ',' && !isAsciiWhitespace(c);
    }

    private static boolean isAsciiWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

}
