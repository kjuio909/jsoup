package org.jsoup.safety;

/*
    Thank you to Ryan Grove (wonko.com) for the Ruby HTML cleaner http://github.com/rgrove/sanitize/, which inspired
    this safe-list configuration, and the initial defaults.
 */

import org.jsoup.helper.Validate;
import org.jsoup.internal.Normalizer;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Element;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.jsoup.internal.Normalizer.lowerCase;


/**
 Safelists define what HTML (elements and attributes) to allow through a {@link Cleaner}. Everything else is removed.
 <p>
 Start with one of the defaults:
 </p>
 <ul>
 <li>{@link #none}
 <li>{@link #simpleText}
 <li>{@link #basic}
 <li>{@link #basicWithImages}
 <li>{@link #relaxed}
 </ul>
 <p>
 If you need to allow more through (please be careful!), tweak a base safelist with:
 </p>
 <ul>
 <li>{@link #addTags(String... tagNames)}
 <li>{@link #addAttributes(String tagName, String... attributes)}
 <li>{@link #addEnforcedAttribute(String tagName, String attribute, String value)}
 <li>{@link #addProtocols(String tagName, String attribute, String... protocols)}
 </ul>
 <p>
 You can remove any setting from an existing safelist with:
 </p>
 <ul>
 <li>{@link #removeTags(String... tagNames)}
 <li>{@link #removeAttributes(String tagName, String... attributes)}
 <li>{@link #removeEnforcedAttribute(String tagName, String attribute)}
 <li>{@link #removeProtocols(String tagName, String attribute, String... removeProtocols)}
 </ul>

 <p>
 The {@link Cleaner} and these safelists assume that you want to clean a <code>body</code> fragment of HTML (to add user
 supplied HTML into a templated page), and not to clean a full HTML document. If the latter is the case, you could wrap
 the templated document HTML around the cleaned body HTML.
 </p>
 <p>
 Safelists are mutable. A {@link Cleaner} uses the supplied safelist directly, so later changes affect later cleaning
 calls. If you want to share a safelist across threads, finish configuring it first and do not mutate it while it is in
 use. To build a variant from an existing configuration, use {@link #Safelist(Safelist)} to make a copy.
 </p>
 <p>
 If you are going to extend a safelist, please be very careful. Make sure you understand what attributes may lead to
 XSS attack vectors. URL attributes are particularly vulnerable and require careful validation. See 
 the <a href="https://owasp.org/www-community/xss-filter-evasion-cheatsheet">XSS Filter Evasion Cheat Sheet</a> for some
 XSS attack examples (that jsoup will safeguard against with the default Cleaner and Safelist configuration).
 </p>
 */
public class Safelist {
    private static final String All = ":all";
    private static final TagName AllTag = TagName.valueOf(All);
    private static final String Srcset = "srcset";
    private static final AttributeKey SrcsetKey = AttributeKey.valueOf(Srcset);
    private final Set<TagName> tagNames; // tags allowed, lower case. e.g. [p, br, span]
    private final Map<TagName, Set<AttributeKey>> attributes; // tag -> attribute[]. allowed attributes [href] for a tag.
    private final Map<TagName, Map<AttributeKey, AttributeValue>> enforcedAttributes; // always set these attribute values
    private final Map<TagName, Map<AttributeKey, Set<Protocol>>> protocols; // allowed URL protocols for attributes
    private boolean preserveRelativeLinks; // option to preserve relative links

    /**
     This safelist allows only text nodes: any HTML Element or any Node other than a TextNode will be removed.
     <p>
     Note that the output of {@link org.jsoup.Jsoup#clean(String, Safelist)} is still <b>HTML</b> even when using
     this Safelist, and so any HTML entities in the output will be appropriately escaped. If you want plain text, not
     HTML, you should use a text method such as {@link Element#text()} instead, after cleaning the document.
     </p>
     <p>Example:</p>
     <pre>{@code
     String sourceBodyHtml = "<p>5 is &lt; 6.</p>";
     String html = Jsoup.clean(sourceBodyHtml, Safelist.none());

     Cleaner cleaner = new Cleaner(Safelist.none());
     String text = cleaner.clean(Jsoup.parse(sourceBodyHtml)).text();

     // html is: 5 is &lt; 6.
     // text is: 5 is < 6.
     }</pre>

     @return safelist
     */
    public static Safelist none() {
        return new Safelist();
    }

    /**
     This safelist allows only simple text formatting: <code>b, em, i, strong, u</code>. All other HTML (tags and
     attributes) will be removed.

     @return safelist
     */
    public static Safelist simpleText() {
        return new Safelist()
                .addTags("b", "em", "i", "strong", "u")
                ;
    }

    /**
     <p>
     This safelist allows a fuller range of text nodes: <code>a, b, blockquote, br, cite, code, dd, dl, dt, em, i, li,
     ol, p, pre, q, small, span, strike, strong, sub, sup, u, ul</code>, and appropriate attributes.
     </p>
     <p>
     Links (<code>a</code> elements) can point to <code>http, https, ftp, mailto</code>, and have an enforced
     <code>rel=nofollow</code> attribute if they link offsite (as indicated by the specified base URI).
     </p>
     <p>
     Does not allow images.
     </p>

     @return safelist
     */
    public static Safelist basic() {
        return new Safelist()
                .addTags(
                        "a", "b", "blockquote", "br", "cite", "code", "dd", "dl", "dt", "em",
                        "i", "li", "ol", "p", "pre", "q", "small", "span", "strike", "strong", "sub",
                        "sup", "u", "ul")

                .addAttributes("a", "href")
                .addAttributes("blockquote", "cite")
                .addAttributes("q", "cite")

                .addProtocols("a", "href", "ftp", "http", "https", "mailto")
                .addProtocols("blockquote", "cite", "http", "https")
                .addProtocols("cite", "cite", "http", "https")

                .addEnforcedAttribute("a", "rel", "nofollow") // has special handling for external links, in Cleaner
                ;

    }

    /**
     This safelist allows the same text tags as {@link #basic}, and also allows <code>img</code> tags, with appropriate
     attributes, with <code>src</code> pointing to <code>http</code> or <code>https</code>.

     @return safelist
     */
    public static Safelist basicWithImages() {
        return basic()
                .addTags("img")
                .addAttributes("img", "align", "alt", "height", "src", "title", "width")
                .addProtocols("img", "src", "http", "https")
                ;
    }

    /**
     This safelist allows a full range of text and structural body HTML: <code>a, b, blockquote, br, caption, cite,
     code, col, colgroup, dd, div, dl, dt, em, h1, h2, h3, h4, h5, h6, i, img, li, ol, p, pre, q, small, span, strike, strong, sub,
     sup, table, tbody, td, tfoot, th, thead, tr, u, ul</code>
     <p>
     Links do not have an enforced <code>rel=nofollow</code> attribute, but you can add that if desired.
     </p>

     @return safelist
     */
    public static Safelist relaxed() {
        return new Safelist()
                .addTags(
                        "a", "b", "blockquote", "br", "caption", "cite", "code", "col",
                        "colgroup", "dd", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6",
                        "i", "img", "li", "ol", "p", "pre", "q", "small", "span", "strike", "strong",
                        "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u",
                        "ul")

                .addAttributes("a", "href", "title")
                .addAttributes("blockquote", "cite")
                .addAttributes("col", "span", "width")
                .addAttributes("colgroup", "span", "width")
                .addAttributes("img", "align", "alt", "height", "src", "title", "width")
                .addAttributes("ol", "start", "type")
                .addAttributes("q", "cite")
                .addAttributes("table", "summary", "width")
                .addAttributes("td", "abbr", "axis", "colspan", "rowspan", "width")
                .addAttributes(
                        "th", "abbr", "axis", "colspan", "rowspan", "scope",
                        "width")
                .addAttributes("ul", "type")

                .addProtocols("a", "href", "ftp", "http", "https", "mailto")
                .addProtocols("blockquote", "cite", "http", "https")
                .addProtocols("cite", "cite", "http", "https")
                .addProtocols("img", "src", "http", "https")
                .addProtocols("q", "cite", "http", "https")
                ;
    }

    /**
     Create a new, empty safelist. Generally it will be better to start with a default prepared safelist instead.

     @see #basic()
     @see #basicWithImages()
     @see #simpleText()
     @see #relaxed()
     */
    public Safelist() {
        tagNames = new HashSet<>();
        attributes = new HashMap<>();
        enforcedAttributes = new HashMap<>();
        protocols = new HashMap<>();
        preserveRelativeLinks = false;
    }

    /**
     Deep copy an existing Safelist to a new Safelist.
     @param copy the Safelist to copy
     */
    public Safelist(Safelist copy) {
        this();
        tagNames.addAll(copy.tagNames);
        for (Map.Entry<TagName, Set<AttributeKey>> copyTagAttributes : copy.attributes.entrySet()) {
            attributes.put(copyTagAttributes.getKey(), new HashSet<>(copyTagAttributes.getValue()));
        }
        for (Map.Entry<TagName, Map<AttributeKey, AttributeValue>> enforcedEntry : copy.enforcedAttributes.entrySet()) {
            enforcedAttributes.put(enforcedEntry.getKey(), new HashMap<>(enforcedEntry.getValue()));
        }
        for (Map.Entry<TagName, Map<AttributeKey, Set<Protocol>>> protocolsEntry : copy.protocols.entrySet()) {
            Map<AttributeKey, Set<Protocol>> attributeProtocolsCopy = new HashMap<>();
            for (Map.Entry<AttributeKey, Set<Protocol>> attributeProtocols : protocolsEntry.getValue().entrySet()) {
                attributeProtocolsCopy.put(attributeProtocols.getKey(), new HashSet<>(attributeProtocols.getValue()));
            }
            protocols.put(protocolsEntry.getKey(), attributeProtocolsCopy);
        }
        preserveRelativeLinks = copy.preserveRelativeLinks;
    }

    /**
     Add a list of allowed elements to a safelist. (If a tag is not allowed, it will be removed from the HTML.)

     @param tags tag names to allow
     @return this (for chaining)
     */
    public Safelist addTags(String... tags) {
        Validate.notNull(tags);

        for (String tagName : tags) {
            Validate.notEmpty(tagName);
            Validate.isFalse(tagName.equalsIgnoreCase("noscript"),
                "noscript is unsupported in Safelists, due to incompatibilities between parsers with and without script-mode enabled");
            tagNames.add(TagName.valueOf(tagName));
        }
        return this;
    }

    /**
     Remove a list of allowed elements from a safelist. (If a tag is not allowed, it will be removed from the HTML.)

     @param tags tag names to disallow
     @return this (for chaining)
     */
    public Safelist removeTags(String... tags) {
        Validate.notNull(tags);

        for(String tag: tags) {
            Validate.notEmpty(tag);
            TagName tagName = TagName.valueOf(tag);

            if(tagNames.remove(tagName)) { // Only look in sub-maps if tag was allowed
                attributes.remove(tagName);
                enforcedAttributes.remove(tagName);
                protocols.remove(tagName);
            }
        }
        return this;
    }

    /**
     Add a list of allowed attributes to a tag. (If an attribute is not allowed on an element, it will be removed.)
     <p>
     E.g.: <code>addAttributes("a", "href", "class")</code> allows <code>href</code> and <code>class</code> attributes
     on <code>a</code> tags.
     </p>
     <p>
     To make an attribute valid for <b>all tags</b>, use the pseudo tag <code>:all</code>, e.g.
     <code>addAttributes(":all", "class")</code>.
     </p>

     @param tag  The tag the attributes are for. The tag will be added to the allowed tag list if necessary.
     @param attributes List of valid attributes for the tag
     @return this (for chaining)
     */
    public Safelist addAttributes(String tag, String... attributes) {
        Validate.notEmpty(tag);
        Validate.notNull(attributes);
        Validate.isTrue(attributes.length > 0, "No attribute names supplied.");

        addTags(tag);
        TagName tagName = TagName.valueOf(tag);
        Set<AttributeKey> attributeSet = new HashSet<>();
        for (String key : attributes) {
            Validate.notEmpty(key);
            attributeSet.add(AttributeKey.valueOf(key));
        }
        Set<AttributeKey> currentSet = this.attributes.computeIfAbsent(tagName, k -> new HashSet<>());
        currentSet.addAll(attributeSet);
        return this;
    }

    /**
     Remove a list of allowed attributes from a tag. (If an attribute is not allowed on an element, it will be removed.)
     <p>
     E.g.: <code>removeAttributes("a", "href", "class")</code> disallows <code>href</code> and <code>class</code>
     attributes on <code>a</code> tags.
     </p>
     <p>
     To make an attribute invalid for <b>all tags</b>, use the pseudo tag <code>:all</code>, e.g.
     <code>removeAttributes(":all", "class")</code>.
     </p>

     @param tag  The tag the attributes are for.
     @param attributes List of invalid attributes for the tag
     @return this (for chaining)
     */
    public Safelist removeAttributes(String tag, String... attributes) {
        Validate.notEmpty(tag);
        Validate.notNull(attributes);
        Validate.isTrue(attributes.length > 0, "No attribute names supplied.");

        TagName tagName = TagName.valueOf(tag);
        Set<AttributeKey> attributeSet = new HashSet<>();
        for (String key : attributes) {
            Validate.notEmpty(key);
            attributeSet.add(AttributeKey.valueOf(key));
        }
        if(tagNames.contains(tagName) && this.attributes.containsKey(tagName)) { // Only look in sub-maps if tag was allowed
            Set<AttributeKey> currentSet = this.attributes.get(tagName);
            currentSet.removeAll(attributeSet);

            if(currentSet.isEmpty()) // Remove tag from attribute map if no attributes are allowed for tag
                this.attributes.remove(tagName);
        }
        if(tag.equals(All)) { // Attribute needs to be removed from all individually set tags
            Iterator<Map.Entry<TagName, Set<AttributeKey>>> it = this.attributes.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<TagName, Set<AttributeKey>> entry = it.next();
                Set<AttributeKey> currentSet = entry.getValue();
                currentSet.removeAll(attributeSet);
                if(currentSet.isEmpty()) // Remove tag from attribute map if no attributes are allowed for tag
                    it.remove();
            }
        }
        return this;
    }

    /**
     Add an enforced attribute to a tag. An enforced attribute will always be added to the element. If the element
     already has the attribute set, it will be overridden with this value.
     <p>
     E.g.: <code>addEnforcedAttribute("a", "rel", "nofollow")</code> will make all <code>a</code> tags output as
     <code>&lt;a href="..." rel="nofollow"&gt;</code>
     </p>

     @param tag   The tag the enforced attribute is for. The tag will be added to the allowed tag list if necessary.
     @param attribute   The attribute name
     @param value The enforced attribute value
     @return this (for chaining)
     */
    public Safelist addEnforcedAttribute(String tag, String attribute, String value) {
        Validate.notEmpty(tag);
        Validate.notEmpty(attribute);
        Validate.notEmpty(value);

        TagName tagName = TagName.valueOf(tag);
        tagNames.add(tagName);
        AttributeKey attrKey = AttributeKey.valueOf(attribute);
        AttributeValue attrVal = AttributeValue.valueOf(value);

        Map<AttributeKey, AttributeValue> attrMap = enforcedAttributes.computeIfAbsent(tagName, k -> new HashMap<>());
        attrMap.put(attrKey, attrVal);
        return this;
    }

    /**
     Remove a previously configured enforced attribute from a tag.

     @param tag   The tag the enforced attribute is for.
     @param attribute   The attribute name
     @return this (for chaining)
     */
    public Safelist removeEnforcedAttribute(String tag, String attribute) {
        Validate.notEmpty(tag);
        Validate.notEmpty(attribute);

        TagName tagName = TagName.valueOf(tag);
        if(tagNames.contains(tagName) && enforcedAttributes.containsKey(tagName)) {
            AttributeKey attrKey = AttributeKey.valueOf(attribute);
            Map<AttributeKey, AttributeValue> attrMap = enforcedAttributes.get(tagName);
            attrMap.remove(attrKey);

            if(attrMap.isEmpty()) // Remove tag from enforced attribute map if no enforced attributes are present
                enforcedAttributes.remove(tagName);
        }
        return this;
    }

    /**
     * Configure this Safelist to preserve relative links in an element's URL attribute, or convert them to absolute
     * links. By default, this is <b>false</b>: URLs will be  made absolute (e.g. start with an allowed protocol, like
     * e.g. {@code http://}.
     *
     * @param preserve {@code true} to allow relative links, {@code false} (default) to deny
     * @return this Safelist, for chaining.
     * @see #addProtocols
     */
    public Safelist preserveRelativeLinks(boolean preserve) {
        preserveRelativeLinks = preserve;
        return this;
    }

    /**
     * Get the current setting for preserving relative links.
     * @return {@code true} if relative links are preserved, {@code false} if they are converted to absolute.
     */
    public boolean preserveRelativeLinks() {
        return preserveRelativeLinks;
    }

    /**
     Add allowed URL protocols for an element's URL attribute. This restricts the possible values of the attribute to
     URLs with the defined protocol.
     <p>
     E.g.: <code>addProtocols("a", "href", "ftp", "http", "https")</code>
     </p>
     <p>
     To allow a link to an in-page URL anchor (i.e. <code>&lt;a href="#anchor"&gt;</code>, add a <code>#</code>:<br>
     E.g.: <code>addProtocols("a", "href", "#")</code>
     </p>

     @param tag       Tag the URL protocol is for
     @param attribute       Attribute name
     @param protocols List of valid protocols
     @return this, for chaining
     */
    public Safelist addProtocols(String tag, String attribute, String... protocols) {
        Validate.notEmpty(tag);
        Validate.notEmpty(attribute);
        Validate.notNull(protocols);

        TagName tagName = TagName.valueOf(tag);
        AttributeKey attrKey = AttributeKey.valueOf(attribute);
        Map<AttributeKey, Set<Protocol>> attrMap = this.protocols.computeIfAbsent(tagName, k -> new HashMap<>());
        Set<Protocol> protSet = attrMap.computeIfAbsent(attrKey, k -> new HashSet<>());

        for (String protocol : protocols) {
            Validate.notEmpty(protocol);
            Protocol prot = Protocol.valueOf(protocol);
            protSet.add(prot);
        }
        return this;
    }

    /**
     Remove allowed URL protocols for an element's URL attribute. If you remove all protocols for an attribute, that
     attribute will allow any protocol.
     <p>
     E.g.: <code>removeProtocols("a", "href", "ftp")</code>
     </p>

     @param tag Tag the URL protocol is for
     @param attribute Attribute name
     @param removeProtocols List of invalid protocols
     @return this, for chaining
     */
    public Safelist removeProtocols(String tag, String attribute, String... removeProtocols) {
        Validate.notEmpty(tag);
        Validate.notEmpty(attribute);
        Validate.notNull(removeProtocols);

        TagName tagName = TagName.valueOf(tag);
        AttributeKey attr = AttributeKey.valueOf(attribute);

        // make sure that what we're removing actually exists; otherwise can open the tag to any data and that can
        // be surprising
        Validate.isTrue(protocols.containsKey(tagName), "Cannot remove a protocol that is not set.");
        Map<AttributeKey, Set<Protocol>> tagProtocols = protocols.get(tagName);
        Validate.isTrue(tagProtocols.containsKey(attr), "Cannot remove a protocol that is not set.");

        Set<Protocol> attrProtocols = tagProtocols.get(attr);
        for (String protocol : removeProtocols) {
            Validate.notEmpty(protocol);
            attrProtocols.remove(Protocol.valueOf(protocol));
        }

        if (attrProtocols.isEmpty()) { // Remove protocol set if empty
            tagProtocols.remove(attr);
            if (tagProtocols.isEmpty()) // Remove entry for tag if empty
                protocols.remove(tagName);
        }
        return this;
    }

    /**
     * Test if the supplied tag is allowed by this safelist.
     * @param tag test tag
     * @return true if allowed
     */
    public boolean isSafeTag(String tag) {
        return tagNames.contains(TagName.valueOf(tag));
    }

    /**
     * Test if the supplied attribute is allowed by this safelist for this tag.
     * <p>This method does not modify the input element or attribute.</p>
     * <p>Note that for {@code srcset} attributes, which hold a comma-separated list of URL candidates, this method
     * only tests that the attribute itself is allowed; the {@link Cleaner} validates each candidate URL against the
     * configured protocols when cleaning.</p>
     * @param tagName tag to consider allowing the attribute in
     * @param el element under test, to confirm protocol
     * @param attr attribute under test
     * @return true if allowed
     */
    public boolean isSafeAttribute(String tagName, Element el, Attribute attr) {
        TagName tag = TagName.valueOf(tagName);
        AttributeKey key = AttributeKey.valueOf(attr.getKey());

        Set<AttributeKey> okSet = attributes.get(tag);
        if (okSet != null && okSet.contains(key)) {
            if (protocols.containsKey(tag)) {
                Map<AttributeKey, Set<Protocol>> attrProts = protocols.get(tag);
                // ok if not defined protocol; otherwise test. srcset URLs are tested per candidate in the Cleaner.
                return !attrProts.containsKey(key) || isSrcset(attr.getKey())
                    || (isSoundUrlValue(attr.getValue())
                        && isSafeProtocol(getProtocolValue(el, attr), attrProts.get(key)));
            } else { // attribute found, no protocols defined, so OK
                return true;
            }
        }
        Map<AttributeKey, AttributeValue> enforcedSet = enforcedAttributes.get(tag);
        if (enforcedSet != null && enforcedSet.containsKey(key)) {
            // enforced attr key was LCed via AttributeKey.valueOf(attr.getKey()),
            // if the input already has that exact value, treat it as safe
            return enforcedSet.get(key).equals(AttributeValue.valueOf(attr.getValue()));
        }
        // no attributes defined for tag, try :all tag
        return !tagName.equals(All) && isSafeAttribute(All, el, attr);
    }

    private String getProtocolValue(Element el, Attribute attr) {
        String value = el.absUrl(attr.getKey());
        if (value.isEmpty() && !StringUtil.hasHttpScheme(attr.getValue()))
            value = attr.getValue(); // if it could not be made abs, run as-is to allow custom unknown protocols
        return value;
    }

    private static final Pattern BareScheme = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+-.]*:$");

    /**
     Tests if a single-value URL attribute value is structurally sound for protocol validation. Empty and
     whitespace-only values are left to the standard resolution rules. Otherwise, the value must not contain control
     characters or whitespace, and must not be a bare scheme (e.g. {@code "mailto:"}) with no content after the
     colon.
     */
    private static boolean isSoundUrlValue(String value) {
        if (StringUtil.isBlank(value)) return true; // blank values follow the standard resolution rules
        for (int i = 0, length = value.length(); i < length; i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7f) return false; // control character or whitespace within the URL
        }
        return !BareScheme.matcher(value).matches();
    }

    private boolean isSafeProtocol(String value, Set<Protocol> protocols) {
        for (Protocol protocol : protocols) {
            String prot = protocol.toString();

            if (prot.equals("#")) { // allows anchor links
                if (isValidAnchor(value)) {
                    return true;
                } else {
                    continue;
                }
            }

            String lc = lowerCase(value);
            if (lc.startsWith(prot)
                && lc.length() > prot.length()
                && lc.charAt(prot.length()) == ':') {
                return true;
            }
        }
        return false;
    }

    /**
     Tests if the attribute is {@code srcset} (case-insensitive), which holds a comma-separated list of image
     candidate URLs and so gets per-candidate cleaning in the {@link Cleaner}.
     */
    static boolean isSrcset(String attrKey) {
        return attrKey.equalsIgnoreCase(Srcset);
    }

    /**
     Cleans a {@code srcset} attribute value, candidate by candidate. Candidates with invalid syntax (missing URL,
     empty candidates, malformed descriptors, or trailing junk) are dropped, as are those whose URL fails the
     protocol checks configured for the attribute on this tag. Surviving candidates keep their original order and
     are normalized to {@code url descriptor, url descriptor} form. Does not throw on malformed input.

     @param tagName the tag the attribute is on; selects the applicable protocol configuration
     @param el the element the attribute is on, to resolve URLs against its base URI
     @param value the source srcset attribute value
     @return the cleaned value, or {@code null} if no candidates survive (in which case the attribute is removed)
     */
    String cleanSrcset(String tagName, Element el, String value) {
        Set<Protocol> protocols = srcsetProtocols(TagName.valueOf(tagName));
        StringBuilder sb = StringUtil.borrowBuilder();

        int pos = 0, length = value.length();
        while (pos <= length) {
            // find the end of this candidate: a comma separates candidates, unless it is within the URL and directly
            // followed by a non-whitespace, non-comma character (e.g. the comma in a data: URL). Consecutive
            // separators produce empty candidates, which are invalid.
            int start = pos, end = pos;
            boolean inUrl = true; // still scanning the URL run (no whitespace after URL content yet)
            boolean started = false; // seen non-whitespace content in this candidate
            while (end < length) {
                char c = value.charAt(end);
                if (c == ',') {
                    if (inUrl && started && end + 1 < length && !isSrcsetSpace(value.charAt(end + 1)) && value.charAt(end + 1) != ',')
                        end++; // comma is part of the URL
                    else
                        break; // candidate separator
                } else {
                    if (isSrcsetSpace(c)) {
                        if (started) inUrl = false;
                    } else {
                        started = true;
                    }
                    end++;
                }
            }
            appendSrcsetCandidate(el, value, start, end, protocols, sb);
            if (end >= length) break;
            pos = end + 1; // step past the separator comma
        }

        if (sb.length() == 0) {
            StringUtil.releaseBuilderVoid(sb);
            return null;
        }
        return StringUtil.releaseBuilder(sb);
    }

    /**
     Validates and appends a single srcset candidate (the {@code value} range {@code start}..{@code end}) to
     {@code sb}. Invalid or unsafe candidates are silently dropped; their text is never recombined into new URLs.
     */
    private void appendSrcsetCandidate(Element el, String value, int start, int end, Set<Protocol> protocols, StringBuilder sb) {
        // trim ASCII whitespace from the candidate
        while (start < end && isSrcsetSpace(value.charAt(start))) start++;
        while (end > start && isSrcsetSpace(value.charAt(end - 1))) end--;
        if (start == end) return; // empty candidate (e.g. from consecutive separators)

        // the URL is the leading run of non-whitespace
        int urlEnd = start;
        while (urlEnd < end && !isSrcsetSpace(value.charAt(urlEnd))) urlEnd++;
        String url = value.substring(start, urlEnd);

        // an optional descriptor may follow, introduced by at least one ASCII space
        String descriptor = null;
        if (urlEnd < end) {
            if (value.charAt(urlEnd) != ' ') return; // the descriptor must be space-separated from the URL
            int descStart = urlEnd;
            while (descStart < end && value.charAt(descStart) == ' ') descStart++;
            int descEnd = descStart;
            while (descEnd < end && !isSrcsetSpace(value.charAt(descEnd))) descEnd++;
            if (descEnd != end) return; // unexpected content after the descriptor (e.g. a second descriptor)
            descriptor = value.substring(descStart, descEnd);
            if (!isValidSrcsetDescriptor(descriptor)) return;
        }

        if (protocols != null && !isSafeSrcsetUrl(el, url, protocols)) return;

        if (sb.length() > 0) sb.append(", ");
        sb.append(url);
        if (descriptor != null) sb.append(' ').append(descriptor);
    }

    /**
     Finds the protocol configuration applicable to {@code srcset} on the given tag, following the same tag to
     {@code :all} fallback as {@link #isSafeAttribute}, so that per-tag differences neither widen nor narrow each
     other. Returns {@code null} when URLs are unrestricted.
     */
    private Set<Protocol> srcsetProtocols(TagName tag) {
        Set<AttributeKey> okSet = attributes.get(tag);
        if (okSet != null && okSet.contains(SrcsetKey)) {
            Map<AttributeKey, Set<Protocol>> attrProts = protocols.get(tag);
            return attrProts != null ? attrProts.get(SrcsetKey) : null;
        }
        Map<AttributeKey, AttributeValue> enforcedSet = enforcedAttributes.get(tag);
        if (enforcedSet != null && enforcedSet.containsKey(SrcsetKey)) return null;
        return !tag.equals(AllTag) ? srcsetProtocols(AllTag) : null;
    }

    private boolean isSafeSrcsetUrl(Element el, String url, Set<Protocol> protocols) {
        String value = StringUtil.resolve(el.baseUri(), url);
        if (value.isEmpty() && !StringUtil.hasHttpScheme(url))
            value = url; // if it could not be made absolute, run as-is to allow custom unknown protocols
        return isSafeProtocol(value, protocols);
    }

    /**
     Validates a srcset descriptor: a positive integer followed by a lowercase {@code w} (e.g. {@code 100w}), or a
     positive number (digits, or digits.digits) followed by a lowercase {@code x} (e.g. {@code 2x}, {@code 1.5x}).
     Signs, exponents, a dot missing a side, and zero values are all invalid.
     */
    private static boolean isValidSrcsetDescriptor(String descriptor) {
        int length = descriptor.length();
        if (length < 2) return false;
        String number = descriptor.substring(0, length - 1);
        switch (descriptor.charAt(length - 1)) {
            case 'w': return isPositiveInteger(number);
            case 'x': return isPositiveNumber(number);
            default:  return false;
        }
    }

    /** Tests if the value is all digits and greater than zero. */
    private static boolean isPositiveInteger(String number) {
        boolean nonZero = false;
        for (int i = 0, length = number.length(); i < length; i++) {
            char c = number.charAt(i);
            if (c < '0' || c > '9') return false;
            if (c != '0') nonZero = true;
        }
        return nonZero;
    }

    /** Tests if the value is digits, or digits.digits, and greater than zero. */
    private static boolean isPositiveNumber(String number) {
        int dot = number.indexOf('.');
        if (dot == -1) return isPositiveInteger(number);
        if (number.indexOf('.', dot + 1) != -1) return false; // at most one dot
        String integer = number.substring(0, dot);
        String fraction = number.substring(dot + 1);
        if (!isDigits(integer) || !isDigits(fraction)) return false; // the dot needs digits on both sides
        return isPositiveInteger(integer) || isPositiveInteger(fraction);
    }

    private static boolean isDigits(String value) {
        for (int i = 0, length = value.length(); i < length; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return value.length() > 0;
    }

    /** The ASCII whitespace characters: space, tab, newline, carriage return, and form feed. */
    private static boolean isSrcsetSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /**
     Check if a URL attribute should be normalized to an absolute URL in the cleaned output. Uses the configured
     protocols for that tag+attribute pair, falling back to {@code :all} only if the tag does not define the
     attribute.
     */
    boolean shouldAbsUrl(String tagName, String attrKey) {
        if (preserveRelativeLinks) return false;
        return shouldAbsUrl(TagName.valueOf(tagName), AttributeKey.valueOf(attrKey));
    }

    private boolean shouldAbsUrl(TagName tag, AttributeKey key) {
        Set<AttributeKey> allowedAttrs = attributes.get(tag);
        if (allowedAttrs != null && allowedAttrs.contains(key)) {
            Map<AttributeKey, Set<Protocol>> protocolsByAttr = protocols.get(tag);
            return protocolsByAttr != null && protocolsByAttr.containsKey(key);
        }

        Map<AttributeKey, AttributeValue> enforcedAttrs = enforcedAttributes.get(tag);
        if (enforcedAttrs != null && enforcedAttrs.containsKey(key)) return false;

        return !tag.equals(AllTag) && shouldAbsUrl(AllTag, key);
    }

    private static boolean isValidAnchor(String value) {
        return value.startsWith("#") && !value.matches(".*\\s.*");
    }

    /**
     Gets the Attributes that should be enforced for a given tag
     * @param tagName the tag
     * @return the attributes that will be enforced; empty if none are set for the given tag
     */
    public Attributes getEnforcedAttributes(String tagName) {
        Attributes attrs = new Attributes();
        TagName tag = TagName.valueOf(tagName);
        if (enforcedAttributes.containsKey(tag)) {
            Map<AttributeKey, AttributeValue> keyVals = enforcedAttributes.get(tag);
            for (Map.Entry<AttributeKey, AttributeValue> entry : keyVals.entrySet()) {
                attrs.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return attrs;
    }
    
    // named types for config. All just hold strings, but here for my sanity.

    static class TagName extends TypedValue {
        TagName(String value) {
            super(value);
        }

        static TagName valueOf(String value) {
            return new TagName(Normalizer.lowerCase(value));
        }
    }

    static class AttributeKey extends TypedValue {
        AttributeKey(String value) {
            super(value);
        }

        static AttributeKey valueOf(String value) {
            return new AttributeKey(Normalizer.lowerCase(value));
        }
    }

    static class AttributeValue extends TypedValue {
        AttributeValue(String value) {
            super(value);
        }

        static AttributeValue valueOf(String value) {
            return new AttributeValue(value);
        }
    }

    static class Protocol extends TypedValue {
        Protocol(String value) {
            super(value);
        }

        static Protocol valueOf(String value) {
            return new Protocol(value);
        }
    }

    abstract static class TypedValue {
        private final String value;

        TypedValue(String value) {
            Validate.notNull(value);
            this.value = value;
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TypedValue other = (TypedValue) obj;
            return Objects.equals(value, other.value);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
