package org.jsoup.safety;

import org.jsoup.internal.Normalizer;
import org.jsoup.internal.StringUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

/**
 Sanitizes the contents of an inline {@code style} attribute, declaration by declaration.

 <p>The value is split into declarations following the actual nesting of semicolons, parentheses, quoted
 strings, and CSS comments: a {@code ;} inside {@code url(...)}, another functional value, a string, or a
 comment never terminates a declaration. An unterminated quote, parenthesis, or comment does not throw and
 does not swallow later declarations — once it is clear the opener is never closed, the declaration
 containing it is treated as malformed and the following declarations are parsed afresh.</p>

 <p>Each surviving declaration is kept verbatim, in its original position, unless it is dangerous or
 malformed:</p>
 <ul>
 <li>an {@code @import} rule, or any value containing a {@code url(...)}, is checked as a URL against the
 safelist's ordinary protocol rules (configurable protocols for {@code style} on the tag, falling back to
 {@code :all}, and defaulting to HTTP/HTTPS). Browsers interpret CSS escapes, control characters, and
 percent encoding when they read such a URL, so all of those normalizations are undone before the scheme is
 checked and cannot smuggle a {@code javascript:}, {@code vbscript:}, or unknown scheme;</li>
 <li>{@code data:} URLs are kept only when {@code data} is an explicitly allowed protocol and the media
 type is a safe (non-SVG) image type;</li>
 <li>the contents of comments and quoted strings are scanned by the same URL rules (a reference hidden
 there is still a rejected reference), but comment text is never mistaken for a declaration;</li>
 <li>everything else (ordinary colors, sizes, fonts, layout) is kept as written.</li>
 </ul>

 <p>Only offending declarations are removed; neighboring safe declarations, other attributes, and text are
 untouched. If no declaration survives, {@link #clean} returns {@code null} so the caller removes the whole
 attribute.</p>
 */
final class CssSanitizer {
    private static final String Style = "style";
    private static final String Url = "url";
    private static final String Import = "import";
    private static final String Data = "data";
    /** Legacy CSS dynamic-expression function (old IE); it executes script, so is always rejected. */
    private static final String Expression = "expression";

    private final Safelist safelist;

    CssSanitizer(Safelist safelist) {
        this.safelist = safelist;
    }

    /** Tests if the attribute name is the inline {@code style} attribute (case-insensitive). */
    static boolean isStyle(String attrKey) {
        return Style.equalsIgnoreCase(attrKey);
    }

    /**
     Cleans an inline style value for the given tag.
     @param tagName the tag the attribute is on, for protocol lookup
     @param baseUri the owning element's base URI, for resolving relative references
     @param style the original style attribute value
     @return the cleaned style value, or {@code null} if no declaration survives (the attribute must be removed)
     */
    String clean(String tagName, String baseUri, String style) {
        Set<String> protocols = safelist.styleProtocols(tagName);
        boolean preserveRelative = safelist.preserveRelativeLinks();
        StringBuilder cleaned = StringUtil.borrowBuilder();
        boolean keptAny = false;

        int length = style.length();
        // closeAt[openerPos] is the index just past a matched '(', quote, or comment opener; -1 if it never
        // closes; 0 where the character is not an opener. Built in linear time, so recovery never rescans.
        int[] closeAt = buildCloseTable(style);

        int start = 0;
        int pos = 0;
        char quote = 0;          // 0, or '"' / '\'' while inside a quoted string
        Deque<Integer> openStack = new ArrayDeque<>(); // positions of comment/quote/'(' openers
        boolean inComment = false;

        while (pos < length) {
            char c = style.charAt(pos);

            // A ';' inside nesting belongs to that nested construct (e.g. content: 'a; b') unless the
            // innermost opener never closes; in that case recover here so later declarations survive.
            if (c == ';' && !openStack.isEmpty()) {
                int opener = openStack.peek();
                if (closeAt[opener] == -1) {
                    if (appendSafe(style.substring(start, pos), true, baseUri, protocols, preserveRelative, cleaned))
                        keptAny = true;
                    start = pos + 1;
                    quote = 0;
                    inComment = false;
                    openStack.clear();
                    // the close table was built for the whole string, so no rescan is needed after recovery
                }
                pos++;
                continue;
            }

            if (inComment) {
                if (c == '*' && pos + 1 < length && style.charAt(pos + 1) == '/') {
                    inComment = false; openStack.pop(); pos += 2; continue;
                }
                pos++;
                continue;
            }
            if (quote != 0) {
                if (c == '\\' && pos + 1 < length) { pos += 2; continue; } // escaped char, incl. escaped quote
                if (c == quote) { quote = 0; openStack.pop(); }
                pos++;
                continue;
            }
            if (c == '/' && pos + 1 < length && style.charAt(pos + 1) == '*') {
                inComment = true; openStack.push(pos); pos += 2; continue;
            }
            if (c == '"' || c == '\'') { quote = c; openStack.push(pos); pos++; continue; }
            if (c == '(') { openStack.push(pos); pos++; continue; }
            if (c == ')' && !openStack.isEmpty() && style.charAt(openStack.peek()) == '(') { openStack.pop(); pos++; continue; }

            if (c == ';') {
                if (appendSafe(style.substring(start, pos), false, baseUri, protocols, preserveRelative, cleaned))
                    keptAny = true;
                start = pos + 1;
                pos++;
                continue;
            }
            pos++;
        }

        // a final unit whose nesting never closes is malformed
        if (start < length) {
            if (appendSafe(style.substring(start), !openStack.isEmpty(), baseUri, protocols, preserveRelative, cleaned))
                keptAny = true;
        }

        if (!keptAny) {
            StringUtil.releaseBuilderVoid(cleaned);
            return null;
        }
        return StringUtil.releaseBuilder(cleaned);
    }

    /**
     Builds {@code closeAt[openerPos]} in linear time: for a {@code (}, a quote, or a comment opener it
     records the index just past the matching close, or {@code -1} if that opener never closes. The scan
     mirrors the main pass's recovery semantics — when a quote or comment is never closed, the text up to the
     next semicolon belongs to the dropped unit and is skipped, after which a fresh code region begins — so
     every character is visited once even for adversarial, repeatedly malformed input.
     */
    private static int[] buildCloseTable(String s) {
        int n = s.length();
        int[] closeAt = new int[n];
        if (n == 0) return closeAt;

        // reverse "next closing delimiter" tables, used to resolve a quote/comment opener in O(1)
        int[] nextDq = new int[n + 1]; // index of next unescaped '"', or n
        int[] nextSq = new int[n + 1]; // index of next unescaped '\'', or n
        int[] nextCc = new int[n + 1]; // index of the '*' of the next "*/", or n
        nextDq[n] = nextSq[n] = nextCc[n] = n;
        for (int i = n - 1; i >= 0; i--) {
            char c = s.charAt(i);
            nextCc[i] = (c == '*' && i + 1 < n && s.charAt(i + 1) == '/') ? i : nextCc[i + 1];
            if (c == '"' || c == '\'') {
                int slashes = 0;
                for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) slashes++;
                boolean unescaped = (slashes & 1) == 0;
                if (c == '"') {
                    nextDq[i] = unescaped ? i : nextDq[i + 1];
                    nextSq[i] = nextSq[i + 1];
                } else {
                    nextSq[i] = unescaped ? i : nextSq[i + 1];
                    nextDq[i] = nextDq[i + 1];
                }
            } else {
                nextDq[i] = nextDq[i + 1];
                nextSq[i] = nextSq[i + 1];
            }
        }

        Deque<Integer> parens = new ArrayDeque<>();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int star = nextCc[i + 2];
                if (star < n) { closeAt[i] = star + 2; i = star + 2; } // a closed comment: neutral span
                else { closeAt[i] = -1; i = skipToRecovery(s, i + 2); parens.clear(); }
            } else if (c == '"' || c == '\'') {
                int close = c == '"' ? nextDq[i + 1] : nextSq[i + 1];
                if (close < n) { closeAt[i] = close + 1; i = close + 1; } // a closed string: neutral span
                else { closeAt[i] = -1; i = skipToRecovery(s, i + 1); parens.clear(); }
            } else if (c == '(') {
                parens.push(i); i++;
            } else if (c == ')' && !parens.isEmpty()) {
                closeAt[parens.pop()] = i + 1; i++;
            } else {
                i++;
            }
        }
        while (!parens.isEmpty()) closeAt[parens.pop()] = -1;
        return closeAt;
    }

    /** Returns the index just past the next semicolon at or after {@code from}, or {@code length} at EOF. */
    private static int skipToRecovery(String s, int from) {
        int idx = s.indexOf(';', from);
        return idx >= 0 ? idx + 1 : s.length();
    }

    /**
     Validates one unit and appends its safe contents. Top-level comments are never mistaken for
     declarations: their text is still scanned by the URL rules, and a comment carrying a rejected reference
     is excised on its own, without taking neighboring declarations with it. Empty or whitespace units
     (from leading, trailing, or repeated semicolons) produce nothing.
     @return whether anything was appended
     */
    private boolean appendSafe(String unit, boolean broken, String baseUri, Set<String> protocols,
                               boolean preserveRelative, StringBuilder out) {
        // split genuine comment spans (never inside a quoted string) from the code. Comment text is still
        // scanned by the URL rules; a comment carrying a rejected reference is excised on its own, without
        // taking neighboring declarations with it, and is never parsed as a declaration.
        StringBuilder code = new StringBuilder(unit.length());
        StringBuilder kept = new StringBuilder(unit.length());
        int length = unit.length();
        int i = 0;
        char quote = 0;
        while (i < length) {
            char c = unit.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < length) { code.append(c).append(unit.charAt(i + 1)); kept.append(c).append(unit.charAt(i + 1)); i += 2; continue; }
                if (c == quote) quote = 0;
                code.append(c);
                kept.append(c);
                i++;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; code.append(c); kept.append(c); i++; continue; }
            if (c == '/' && i + 1 < length && unit.charAt(i + 1) == '*') {
                int end = skipComment(unit, i);
                boolean closed = commentClosed(unit, i);
                int contentEnd = closed ? end - 2 : length;
                if (referencesAreSafe(unit.substring(i + 2, contentEnd), baseUri, protocols, preserveRelative))
                    kept.append(unit, i, end); // a harmless comment is preserved verbatim
                code.append(' '); // comments separate tokens; never let removal fuse two identifiers
                i = end;
                continue;
            }
            code.append(c);
            kept.append(c);
            i++;
        }

        String codeText = code.toString();
        if (codeText.trim().isEmpty()) {
            if (kept.toString().trim().isEmpty()) return false; // only whitespace: elide
            return appendUnit(out, kept.toString()); // only comments: keep the harmless ones
        }
        if (broken || !isSafeDeclaration(codeText, baseUri, protocols, preserveRelative)) return false;
        return appendUnit(out, kept.toString());
    }

    private static boolean appendUnit(StringBuilder out, String unit) {
        if (out.length() > 0) out.append(';');
        out.append(unit);
        return true;
    }

    /**
     Validates one declaration (without its trailing semicolon): a property, a colon, and a value. At-rules
     are only accepted as {@code @import} carrying a validatable reference.
     */
    private boolean isSafeDeclaration(String unit, String baseUri, Set<String> protocols, boolean preserveRelative) {
        String trimmed = unit.trim();
        if (trimmed.charAt(0) == '@') {
            return isSafeAtRule(trimmed, baseUri, protocols, preserveRelative);
        }

        int colon = firstTopLevelColon(unit);
        if (colon < 0) return false; // no colon and not an at-rule: malformed

        String property = unit.substring(0, colon).trim();
        String value = unit.substring(colon + 1);
        if (property.isEmpty() || !isIdentifier(property) || !isPropertyStart(property.charAt(0))) return false;
        if (StringUtil.isBlank(value)) return false; // a declaration needs a value

        return referencesAreSafe(value, baseUri, protocols, preserveRelative);
    }

    /** Validates an {@code @import} reference (and nothing else — other at-rules are invalid inline). */
    private boolean isSafeAtRule(String unit, String baseUri, Set<String> protocols, boolean preserveRelative) {
        int i = 0;
        if (unit.charAt(i) != '@') return false;
        i++;
        int nameStart = i;
        while (i < unit.length() && isTokenChar(unit.charAt(i))) i++;
        String name = Normalizer.lowerCase(unit.substring(nameStart, i));
        if (!name.equals(Import)) return false;

        int length = unit.length();
        while (i < length && isCssWhitespace(unit.charAt(i))) i++;
        if (i >= length) return false; // @import with no reference

        char c = unit.charAt(i);
        if (c == '"' || c == '\'') {
            int end = skipString(unit, i, c);
            String ref = unescapeCss(unit.substring(i + 1, quoteContentEnd(unit, i, c)));
            if (!isSafeUrl(ref, baseUri, protocols, preserveRelative)) return false;
            i = end;
        } else if (isIdentifierStart(c) || c == '\\') {
            int fnStart = i;
            int fnEnd = scanIdentifier(unit, i);
            String fn = normalizeToken(unit.substring(fnStart, fnEnd));
            i = fnEnd;
            while (i < length && isCssWhitespace(unit.charAt(i))) i++;
            if (fn.equals(Url)) {
                if (i >= length || unit.charAt(i) != '(') return false;
                UrlToken token = readUrlFunction(unit, i);
                if (token == null || !isSafeUrl(token.url, baseUri, protocols, preserveRelative)) return false;
                i = token.next;
            } else {
                // a bare absolute URL is legal in @import ("@import https://example.com/a.css;"): the
                // reference runs to the first whitespace (later layer()/supports/media are re-scanned)
                int refEnd = fnStart;
                while (refEnd < length && !isCssWhitespace(unit.charAt(refEnd))) refEnd++;
                if (!isSafeUrl(unit.substring(fnStart, refEnd), baseUri, protocols, preserveRelative)) return false;
                i = refEnd;
            }
        } else {
            return false;
        }

        // anything after the reference (layer(), supports(...), media list) must not itself fetch
        return referencesAreSafe(unit.substring(i), baseUri, protocols, preserveRelative);
    }

    /** Finds the first colon outside parentheses, quoted strings, and comments. */
    private static int firstTopLevelColon(String value) {
        int length = value.length();
        int parens = 0;
        char quote = 0;
        boolean inComment = false;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (inComment) {
                if (c == '*' && i + 1 < length && value.charAt(i + 1) == '/') { inComment = false; i++; }
                continue;
            }
            if (quote != 0) {
                if (c == '\\' && i + 1 < length) i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && i + 1 < length && value.charAt(i + 1) == '*') { inComment = true; i++; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '(') { parens++; continue; }
            if (c == ')') { if (parens > 0) parens--; continue; }
            if (c == ':' && parens == 0) return i;
        }
        return -1;
    }

    /**
     Scans a declaration value and validates every external reference it can contain: {@code url(...)}
     functions and {@code @import} rules. In code context it also rejects the legacy script-executing
     {@code expression(...)}; quoted strings and comments are scanned only by the URL rules, never treated
     as code. Never throws on unterminated strings or parentheses.
     */
    private boolean referencesAreSafe(String text, String baseUri, Set<String> protocols, boolean preserveRelative) {
        return referencesAreSafe(text, baseUri, protocols, preserveRelative, true);
    }

    private boolean referencesAreSafe(String text, String baseUri, Set<String> protocols,
                                      boolean preserveRelative, boolean codeContext) {
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);

            if (c == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
                int end = skipComment(text, i);
                int contentEnd = commentClosed(text, i) ? end - 2 : length; // exclude "*/", or run to EOF if open
                // comment text is not a declaration, but a reference hidden there still faces the URL rules
                if (!referencesAreSafe(text.substring(i + 2, contentEnd), baseUri, protocols, preserveRelative, false))
                    return false;
                i = end;
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = skipString(text, i, c);
                int contentEnd = quoteContentEnd(text, i, c); // excludes the closing quote, if any
                // string contents are scanned by the URL rules only; an expression() name quoted as text is inert
                if (!referencesAreSafe(text.substring(i + 1, contentEnd), baseUri, protocols, preserveRelative, false))
                    return false;
                i = end;
                continue;
            }
            if (c == '@') {
                int nameStart = i + 1;
                int nameEnd = nameStart;
                while (nameEnd < length && isTokenChar(text.charAt(nameEnd))) nameEnd++;
                String name = Normalizer.lowerCase(text.substring(nameStart, nameEnd));
                int j = nameEnd;
                while (j < length && isCssWhitespace(text.charAt(j))) j++;
                if (name.equals(Import)) {
                    Reference ref = readImportReference(text, j);
                    if (ref == null || !isSafeUrl(ref.url, baseUri, protocols, preserveRelative)) return false;
                    i = ref.next;
                    continue;
                }
                i = nameEnd;
                continue;
            }
            if (isIdentifierStart(c) || c == '\\') {
                int start = i;
                int end = scanIdentifier(text, i);
                String fn = normalizeToken(text.substring(start, end));
                int j = end;
                while (j < length && isCssWhitespace(text.charAt(j))) j++;
                if (fn.equals(Url) && j < length && text.charAt(j) == '(') {
                    UrlToken token = readUrlFunction(text, j);
                    if (token == null || !isSafeUrl(token.url, baseUri, protocols, preserveRelative)) return false;
                    i = token.next;
                    continue;
                }
                if (codeContext && fn.equals(Expression) && j < length && text.charAt(j) == '(')
                    return false; // executes script in legacy engines; never allowed in a declaration
                // some other function, or a plain keyword: keep scanning the remainder linearly, so a nested
                // url(...) or expression(...) inside it is still reached
                i = end;
                continue;
            }
            i++;
        }
        return true;
    }

    private static boolean commentClosed(String text, int start) {
        int idx = text.indexOf("*/", start + 2);
        return idx >= 0;
    }

    /** A reference following an {@code @import} keyword: a string, a url(), or a bare absolute URL. */
    private Reference readImportReference(String text, int i) {
        int length = text.length();
        if (i >= length) return null;
        char c = text.charAt(i);
        if (c == '"' || c == '\'') {
            int end = skipString(text, i, c);
            String ref = unescapeCss(text.substring(i + 1, quoteContentEnd(text, i, c)));
            return new Reference(ref, end);
        }
        int start = i;
        if (isIdentifierStart(c) || c == '\\') {
            int end = scanIdentifier(text, i);
            String fn = normalizeToken(text.substring(start, end));
            int j = end;
            while (j < length && isCssWhitespace(text.charAt(j))) j++;
            if (fn.equals(Url) && j < length && text.charAt(j) == '(') {
                UrlToken token = readUrlFunction(text, j);
                return token == null ? null : new Reference(token.url, token.next);
            }
            // bare token run up to whitespace or end
            while (end < length && !isCssWhitespace(text.charAt(end))) end++;
            String ref = text.substring(start, end).trim();
            return new Reference(ref, end);
        }
        return null;
    }

    private static final class Reference {
        final String url;
        final int next;
        Reference(String url, int next) { this.url = url; this.next = next; }
    }

    /** A parsed {@code url(...)} reference and the index immediately after the closing parenthesis. */
    private static final class UrlToken {
        final String url;
        final int next;
        UrlToken(String url, int next) { this.url = url; this.next = next; }
    }

    /**
     Reads a {@code url( ... )} token starting at the opening parenthesis. Quoted ({@code url("x")}) and
     unquoted ({@code url(x)}) forms are both supported. A missing closing quote or parenthesis consumes the
     rest of the text without throwing; trailing junk where the closer should be makes the reference
     syntactically invalid.
     */
    private static UrlToken readUrlFunction(String value, int openParen) {
        int length = value.length();
        int i = openParen + 1;
        while (i < length && isCssWhitespace(value.charAt(i))) i++;
        if (i >= length) return null;

        char c = value.charAt(i);
        if (c == '"' || c == '\'') {
            int contentStart = i + 1;
            int end = skipString(value, i, c);
            String raw = unescapeCss(value.substring(contentStart, quoteContentEnd(value, i, c)));
            i = end;
            while (i < length && isCssWhitespace(value.charAt(i))) i++;
            if (i < length && value.charAt(i) == ')') i++;
            else if (i != length) return null; // junk where the closer belongs
            if (StringUtil.isBlank(raw)) return null;
            return new UrlToken(raw, i);
        }

        // unquoted: the run ends at ')' or whitespace; quotes or an extra '(' are not legal
        StringBuilder sb = new StringBuilder();
        while (i < length) {
            char u = value.charAt(i);
            if (u == ')') { i++; break; }
            if (isCssWhitespace(u)) break;
            if (u == '"' || u == '\'' || u == '(') return null;
            if (u == '\\' && i + 1 < length) { sb.append(u); sb.append(value.charAt(i + 1)); i += 2; }
            else { sb.append(u); i++; }
        }
        while (i < length && isCssWhitespace(value.charAt(i))) i++;
        if (i < length && value.charAt(i) == ')') i++;
        else if (i != length) return null; // junk after an unquoted URL
        // if neither ')' nor end was reached the opener was never closed; the consumed text is the suspect ref
        String raw = unescapeCss(sb.toString());
        if (StringUtil.isBlank(raw)) return null;
        return new UrlToken(raw, i);
    }

    /** Returns the index just past a quoted string; an unterminated quote runs to the end. */
    private static int skipString(String value, int start, char quote) {
        int close = closingQuote(value, start, quote);
        return close >= 0 ? close + 1 : value.length();
    }

    /** Finds the index of the quote that closes the string opened at {@code start}, honoring escapes; -1 if open. */
    private static int closingQuote(String value, int start, char quote) {
        int i = start + 1, length = value.length();
        while (i < length) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < length) { i += 2; continue; }
            if (c == quote) return i;
            i++;
        }
        return -1;
    }

    /** Index of the end of a quoted string's content, whether or not the quote is closed. */
    private static int quoteContentEnd(String value, int start, char quote) {
        int close = closingQuote(value, start, quote);
        return close >= 0 ? close : value.length();
    }

    /** Returns the index just past a comment; an unterminated comment runs to the end. */
    private static int skipComment(String value, int start) {
        int idx = value.indexOf("*/", start + 2);
        return idx >= 0 ? idx + 2 : value.length();
    }

    /**
     Resolves and validates one CSS URL reference against the same rules as an HTML URL attribute:
     no control characters, CSS-escape- and percent-decoding cannot reveal a forbidden scheme,
     protocol-relative and relative references resolve against the document base, and undeterminable
     references are rejected.
     */
    private boolean isSafeUrl(String rawUrl, String baseUri, Set<String> protocols, boolean preserveRelative) {
        String url = rawUrl.trim();
        if (url.isEmpty()) return false;
        for (int i = 0, len = url.length(); i < len; i++) {
            if (isControl(url.charAt(i))) return false; // browsers strip controls; never let one gate a scheme
        }

        // undo the obfuscations a browser applies when reading the URL, then look at the scheme it would use
        String probe = stripControlChars(percentDecode(unescapeCss(url))).trim();
        if (probe.isEmpty()) return false;

        String scheme = schemeOf(probe);
        if (scheme != null) {
            if (scheme.equals(Data)) return dataUrlAllowed(probe, protocols);
            if (!protocols.contains(scheme)) return false; // javascript:, vbscript:, and anything unknown
            return !scheme.equals("http") && !scheme.equals("https") || hasHost(probe);
        }

        if (probe.startsWith("//")) { // network-path reference: only the base can name the scheme
            if (preserveRelative) return true;
            String resolved = StringUtil.resolve(baseUri, probe);
            if (StringUtil.isBlank(resolved)) return false; // no usable base: undeterminable, deny
            String resolvedScheme = schemeOf(resolved);
            return resolvedScheme != null && protocols.contains(resolvedScheme);
        }

        if (preserveRelative) return true; // ordinary relative reference kept as-is, per the safelist option
        if (StringUtil.isBlank(baseUri)) return false; // no base to judge a relative reference against: deny
        String resolved = StringUtil.resolve(baseUri, probe);
        if (StringUtil.isBlank(resolved)) return false;
        String resolvedScheme = schemeOf(resolved);
        if (resolvedScheme == null || !protocols.contains(resolvedScheme)) return false;
        return !resolvedScheme.equals("http") && !resolvedScheme.equals("https") || hasHost(resolved);
    }

    /** A {@code data:} URL survives only when {@code data} is explicitly allowed and its media type is safe. */
    private static boolean dataUrlAllowed(String url, Set<String> protocols) {
        if (!protocols.contains(Data)) return false;
        int comma = url.indexOf(',');
        if (comma < 0) return false; // a data URL must carry data
        String meta = url.substring(Data.length() + 1, comma).trim().toLowerCase(Locale.ROOT);
        int semi = meta.indexOf(';'); // drop ";base64" and other parameters
        String mediaType = (semi >= 0 ? meta.substring(0, semi) : meta).trim();
        if (mediaType.isEmpty()) mediaType = "text/plain"; // an omitted type defaults to text/plain
        if (!mediaType.startsWith("image/")) return false; // text/html and other active types are refused
        String subtype = mediaType.substring("image/".length());
        return !subtype.isEmpty() && !subtype.equals("svg+xml"); // svg can carry script; others are inert
    }

    /** Tests that an absolute HTTP(S) URL has the non-empty host its scheme requires. */
    private static boolean hasHost(String url) {
        int colon = url.indexOf(':');
        if (colon < 0 || !url.startsWith("//", colon + 1)) return false;
        int hostStart = colon + 3;
        for (int i = hostStart; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') return i > hostStart;
        }
        return url.length() > hostStart;
    }

    /**
     Returns the scheme of a reference whose CSS and percent escapes have already been normalized, or
     {@code null} when it has no literal-colon scheme. The run before the colon must be a legal scheme and
     start with a letter, so relative references (e.g. {@code x%3Ay.jpg}, {@code /a:b}) yield {@code null}.
     */
    private static String schemeOf(String url) {
        int colon = url.indexOf(':');
        if (colon <= 0) return null;
        String head = url.substring(0, colon);
        if (!isAsciiAlpha(head.charAt(0))) return null;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            boolean schemeChar = isAsciiAlpha(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
            if (!schemeChar) return null;
        }
        return head.toLowerCase(Locale.ROOT);
    }

    /**
     Decodes CSS character escapes: {@code j\61 vascript:}, {@code \00006a ...}, and similar become the
     characters a browser would read, so an obfuscated scheme is visible to the protocol checks. A backslash
     before any other character escapes that literal. Never throws on a trailing incomplete escape.
     */
    static String unescapeCss(String value) {
        if (value.indexOf('\\') == -1) return value;
        int length = value.length();
        StringBuilder sb = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < length) {
                char next = value.charAt(i + 1);
                if (isHex(next)) {
                    int j = i + 1;
                    while (j < length && j - (i + 1) < 6 && isHex(value.charAt(j))) j++;
                    int code = Integer.parseInt(value.substring(i + 1, j), 16);
                    if (j < length && isCssWhitespace(value.charAt(j))) j++; // one whitespace terminates the escape
                    if (code == 0 || code >= 0xD800 && code <= 0xDFFF) { i = j; continue; } // null / surrogates dropped
                    sb.appendCodePoint(code);
                    i = j;
                    continue;
                }
                sb.append(next); // escaped literal (quote, paren, backslash, ...)
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** Leniently percent-decodes {@code %XX} escapes (ISO-8859-1 mapping); malformed escapes stay literal. */
    private static String percentDecode(String value) {
        int length = value.length();
        if (length < 3 || value.indexOf('%') == -1) return value;
        StringBuilder sb = null;
        int i = 0;
        while (i < length) {
            if (value.charAt(i) == '%' && i + 2 < length
                && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                if (sb == null) { sb = new StringBuilder(length); sb.append(value, 0, i); }
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                sb.append((char) ((hi << 4) | lo));
                i += 3;
                continue;
            }
            if (sb != null) sb.append(value.charAt(i));
            i++;
        }
        return sb != null ? sb.toString() : value;
    }

    /** Removes ASCII control characters (0x00-0x1F and DEL), which browsers ignore when reading a URL. */
    private static String stripControlChars(String value) {
        int length = value.length();
        StringBuilder stripped = null;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (isControl(c)) {
                if (stripped == null) { stripped = new StringBuilder(length); stripped.append(value, 0, i); }
            } else if (stripped != null) {
                stripped.append(c);
            }
        }
        return stripped != null ? stripped.toString() : value;
    }

    /**
     Scans an identifier-like token, including CSS escapes and embedded ASCII control characters (which
     browsers discard), so an escaped or control-split {@code url} / {@code import} keyword cannot bypass
     recognition or trap the scanner.
     */
    private static int scanIdentifier(String value, int from) {
        int i = from, length = value.length();
        while (i < length) {
            char c = value.charAt(i);
            if (isTokenChar(c) || isControl(c)) { i++; continue; }
            if (c == '\\' && i + 1 < length) {
                int j = i + 1;
                if (isHex(value.charAt(j))) { // hex escape: up to six digits, then one optional whitespace
                    while (j < length && j - (i + 1) < 6 && isHex(value.charAt(j))) j++;
                    if (j < length && isCssWhitespace(value.charAt(j))) j++;
                } else {
                    j = i + 2; // escaped literal
                }
                i = j;
                continue;
            }
            break;
        }
        return i;
    }

    /** Normalizes an identifier token: drop control characters, decode CSS escapes, and lower-case. */
    private static String normalizeToken(String token) {
        if (token.indexOf('\\') == -1 && !hasControl(token)) return token.toLowerCase(Locale.ROOT);
        return unescapeCss(stripControlChars(token)).toLowerCase(Locale.ROOT);
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (isControl(value.charAt(i))) return true;
        return false;
    }

    /** A CSS property name written literally: letters, digits, hyphen, underscore, and escapes. */
    private static boolean isIdentifier(String value) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (isTokenChar(c)) continue;
            if (c == '\\' && i + 1 < length) { i++; continue; }
            return false;
        }
        return true;
    }

    private static boolean isTokenChar(char c) {
        return isAsciiAlpha(c) || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static boolean isIdentifierStart(char c) {
        return isAsciiAlpha(c) || c == '_';
    }

    /** A property name starts like an identifier, or with a hyphen (vendor prefix or a {@code --custom} prop). */
    private static boolean isPropertyStart(char c) {
        return isIdentifierStart(c) || c == '-' || c == '\\';
    }

    private static boolean isCssWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isControl(char c) {
        return c <= 0x1f || c == 0x7f;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isAsciiAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
