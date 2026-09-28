package org.jsoup.safety;

import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;

import java.util.Arrays;

/**
 Parses and sanitizes the inline CSS of an element's {@code style} attribute, one declaration at a time. The parser is
 a small hand scanner rather than a full CSS parser: declarations are split on semicolons while tracking the actual
 nesting of strings, parentheses, block comments, and CSS backslash escapes, so that punctuation inside strings
 ({@code font-family: 'Calibri;'}) or functions ({@code background: url(/img;a.png)}) cannot break out of its
 declaration.

 <p>Declarations with no recognizable property, an unclosed function or string (recovered only at a newline, per the
 CSS bad-string rule), unbalanced parentheses, or a forbidden or unverifiable external reference are dropped
 individually; surrounding safe declarations, other attributes, and the element's text are untouched. An unclosed
 comment is itself an unbounded fragment and is dropped, but scanning recovers at the next semicolon so that later
 declarations are still parsed independently. Comment text and the contents of ordinary quoted strings are emitted
 verbatim but are inert as CSS — a browser removes comments and never tokenizes a function call out of a string — so
 a {@code url(...)}-shaped fragment inside either does not by itself trigger a URL check (a quoted string is still
 checked when it is the actual argument of a real {@code url()} call). Function names are read with full CSS escape
 semantics (hex runs such as {@code \75 rl}, single-character escapes such as a backslash before the letter u, and consecutive
 backslashes), so an escaped or differently cased {@code url}, {@code expression}, or equivalent reference is
 normalized before the safelist's URL rules are applied. Repeated separators and other degenerate input never throw
 and never cause following declarations to be joined into a rejected value.</p>

 <p>The cleaner is stateless and thread-safe; all per-value state lives on the local parse stack.</p>
 */
final class StyleCleaner {
    private final Safelist safelist;
    private final String tagName;
    private final Element el;

    private StyleCleaner(Safelist safelist, String tagName, Element el) {
        this.safelist = safelist;
        this.tagName = tagName;
        this.el = el;
    }

    /** Builds a cleaner for the text of an allowed {@code <style>} element, resolved against that element's base URI. */
    static StyleCleaner forStyleElement(Safelist safelist, Element el) {
        return new StyleCleaner(safelist, "style", el);
    }

    /**
     Cleans an inline {@code style} attribute value declaration by declaration, preserving the order and original
     text of every declaration that survives.
     @return the cleaned value, or {@code null} when no declaration survives (in which case the attribute is removed)
     */
    static String clean(Safelist safelist, String tagName, Element el, String value) {
        return new StyleCleaner(safelist, tagName, el).clean(value);
    }

    private String clean(String value) {
        StringBuilder out = StringUtil.borrowBuilder();
        int length = value.length();
        int[] match = parenMatches(value); // index of each parenthesis's mate, or -1 when unmatched
        int[] opens = new int[length]; // positions of the currently open parentheses
        int segStart = 0;
        int parenDepth = 0;
        boolean segmentBad = false; // this segment already contains a malformed construct
        int i = 0;
        while (i < length) {
            char c = value.charAt(i);
            if (c == '\\') { // CSS escape: the next code unit is literal, even '\;' or '\"'
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                int[] quote = scanQuoted(value, i + 1, c);
                if (quote[1] == 1) {
                    i = quote[0] + 1;
                } else {
                    segmentBad = true; // a bad-string token makes only this declaration malformed
                    i = quote[1] == 2 ? quote[0] + 1 : quote[0]; // recover at the newline, or at the next ';'
                }
                continue;
            }
            if (c == '/' && i + 1 < length && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0) {
                    // an unterminated comment cannot be bounded and so invalidates only its own fragment: recover at
                    // the next ';' so that following declarations stay independently parseable. When no ';' remains,
                    // the text before the comment is still validated on its own merits.
                    int semi = value.indexOf(';', i + 2);
                    if (semi < 0) {
                        appendSafeDeclaration(value, segStart, i, segmentBad || parenDepth != 0, out);
                        segStart = length;
                        break;
                    }
                    segmentBad = true;
                    i = semi; // the ';' branch ends the malformed fragment and resets for the next declaration
                    continue;
                }
                i = close + 2;
                continue;
            }
            if (c == '(') {
                opens[parenDepth++] = i;
            } else if (c == ')') {
                if (parenDepth > 0) parenDepth--;
                else segmentBad = true; // a stray ')' makes this declaration malformed
            } else if (c == ';') {
                if (parenDepth > 0 && match[opens[parenDepth - 1]] >= 0) {
                    // the ';' is content of a function that closes later (e.g. an unquoted data: URL); keep scanning
                    i++;
                    continue;
                }
                // otherwise end here; a function that never closes only invalidates this declaration
                appendSafeDeclaration(value, segStart, i, segmentBad || parenDepth != 0, out);
                segStart = i + 1;
                segmentBad = false;
                parenDepth = 0;
            }
            i++;
        }
        if (segStart < length) appendSafeDeclaration(value, segStart, length, segmentBad || parenDepth != 0, out);

        if (out.length() == 0) {
            StringUtil.releaseBuilderVoid(out);
            return null;
        }
        return StringUtil.releaseBuilder(out);
    }

    /**
     Computes the matching parenthesis of every balanced {@code (} and {@code )} in the value, applying the same
     * lexical rules (escapes, quoted strings with bad-string recovery, and comments) as the declaration walk. An
     * unmatched parenthesis maps to {@code -1}; a single linear pass keeps style cleaning O(n) even when a value
     * contains many semicolons nested in functions.
     */
    private int[] parenMatches(String value) {
        int length = value.length();
        int[] match = new int[length];
        Arrays.fill(match, -1);
        int[] stack = new int[length];
        int depth = 0;
        int i = 0;
        while (i < length) {
            char c = value.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"' || c == '\'') {
                int[] quote = scanQuoted(value, i + 1, c);
                i = quote[1] == 0 ? quote[0] : quote[0] + 1; // at the recovered ';', or past the quote/newline
                continue;
            }
            if (c == '/' && i + 1 < length && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0) {
                    // mirror the declaration walk: an unterminated comment invalidates only its own fragment, and
                    // scanning recovers at the next ';'. Any parentheses opened in that dropped fragment are
                    // abandoned there, so the nesting depth resets with it.
                    int semi = value.indexOf(';', i + 2);
                    if (semi < 0) break;
                    depth = 0;
                    i = semi;
                    continue;
                }
                i = close + 2;
                continue;
            }
            if (c == '(') {
                stack[depth++] = i;
            } else if (c == ')') {
                if (depth > 0) {
                    int open = stack[--depth];
                    match[open] = i;
                    match[i] = open;
                }
            }
            i++;
        }
        return match;
    }

    /**
     * <ul>
     *   <li>state {@code 1}: the closing quote was found at {@code end};</li>
     *   <li>state {@code 2}: the string was recovered at the newline at {@code end} (the CSS bad-string rule; later
     *       declarations stay independently parseable);</li>
     *   <li>state {@code 0}: neither quote nor newline remains; {@code end} is the index of the first semicolon at
     *       or after the opening quote (the malformed declaration is cut there), or the value length when none.</li>
     * </ul>
     */
    static int[] scanQuoted(String value, int from, char quote) {
        int length = value.length();
        int firstSemi = -1;
        for (int i = from; i < length; i++) {
            char c = value.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return new int[] {i, 1};
            if (c == '\n' || c == '\r') return new int[] {i, 2}; // bad-string recovery at a CSS newline
            if (c == ';' && firstSemi < 0) firstSemi = i;
        }
        return new int[] {firstSemi >= 0 ? firstSemi : length, 0};
    }

    /**
     Validates the declaration at {@code [start, end)} and, when safe, appends its verbatim text to {@code out}.
     @param structurallyBad the containing walk already found a malformed construct (a bad-string token)
     */
    private void appendSafeDeclaration(String value, int start, int end, boolean structurallyBad, StringBuilder out) {
        if (structurallyBad) return;
        int p = skipSpaceAndComments(value, start, end);
        if (p < 0) return; // an unterminated leading comment rejects only this declaration
        int q = end;
        while (q > p && isCssSpace(value.charAt(q - 1))) q--;
        if (p == q) return; // whitespace-only fragment, e.g. from a duplicated ';'
        int nameStart = p;

        // an at-rule in a declaration position: only @import is meaningful inline, and its reference must pass
        if (value.charAt(p) == '@') {
            if (isSafeImportDeclaration(value, p, q)) append(out, value, start, q);
            return;
        }
        // property: a plain CSS identifier (including a vendor prefix such as -webkit-*) or a custom property
        // (--name). Escapes, comments, and quotes are not accepted in a declaration name.
        boolean custom = false;
        if (value.charAt(p) == '-') {
            if (p + 1 < end && value.charAt(p + 1) == '-') {
                p += 2;
                custom = true;
                if (p >= q || !isIdentChar(value.charAt(p))) return; // "--" alone is not a property name
                while (p < q && isIdentChar(value.charAt(p))) p++;
            } else {
                if (p + 1 >= q || !isIdentStart(value.charAt(p + 1))) return; // a bare '-' is not a property name
                p++; // let the ordinary identifier run consume the vendor-prefixed remainder
            }
        }
        if (!custom) {
            if (p >= q || !isIdentStart(value.charAt(p))) return; // no property name
            do { p++; } while (p < q && isIdentChar(value.charAt(p)));
        }
        int afterName = skipSpaceAndComments(value, p, q); // whitespace/comments may sit between the name and the ':'
        if (afterName < 0) return; // an unterminated or unsafe comment in the gap rejects only this declaration
        if (afterName >= q || value.charAt(afterName) != ':') return; // no ':' (empty fragment, junk, duplicate ';')
        p = afterName + 1; // step past ':'

        if (isDangerousProperty(value, nameStart)) return; // known script-entry properties, regardless of value
        // a custom property (--name) holds arbitrary free text: its value is scanned as one string, so a colon that is
        // not part of a reference (e.g. "--x: https://example.com" or a time such as "12:00"), a stray quote, or an
        // at-keyword other than @import is preserved verbatim; only its references decide its fate
        boolean safe = custom
            ? freeTextIsSafe(value.substring(p, q))
            : referencesAreSafe(value, p, q);
        if (safe) append(out, value, start, q);
    }

    /** Appends a surviving declaration, separated from any earlier ones by a single semicolon. */
    private void append(StringBuilder out, String value, int from, int to) {
        if (out.length() > 0) out.append(';');
        out.append(value, from, to);
    }

    /**
     Validates an entire {@code @import} declaration: its leading reference (a quoted URL or a {@code url(...)}) must
     * pass the URL rules, and any trailing layer/media/supports conditions must themselves contain no unsafe
     * reference.
     */
    boolean isSafeImportDeclaration(String value, int at, int end) {
        int i = at + 1;
        StringBuilder atName = new StringBuilder();
        i = scanIdent(value, i, end, atName);
        if (!atName.toString().equalsIgnoreCase("import")) return false;
        int sep = skipSeparators(value, i, end);
        if (sep < 0 || sep >= end) return false;
        i = sep;
        int afterRef;
        char c = value.charAt(i);
        if (c == '"' || c == '\'') {
            int[] quote = scanQuoted(value, i + 1, c);
            if (quote[1] != 1) return false;
            if (!safelist.isSafeStyleUrl(tagName, el, unescape(value.substring(i + 1, quote[0])))) return false;
            afterRef = quote[0] + 1;
        } else if (isIdentStart(c) || c == '-' || c == '\\') {
            StringBuilder fnName = new StringBuilder();
            int nameEnd = scanIdent(value, i, end, fnName);
            int k = skipSeparators(value, nameEnd, end);
            if (k < 0 || k >= end || value.charAt(k) != '(' || !fnName.toString().equalsIgnoreCase("url")) return false;
            int argEnd = matchingParen(value, k + 1, end);
            if (argEnd < 0 || !isSafeUrlArgument(value, k + 1, argEnd)) return false;
            afterRef = argEnd + 1;
        } else {
            return false;
        }
        return referencesAreSafe(value, afterRef, end);
    }

    /**
     Advances past CSS whitespace and complete block comments. A comment is inert token separators (a browser removes
     it before tokenization), so its text is never inspected for a concealed reference; an unterminated comment is
     still an unbounded fragment and yields {@code -1}, rejecting only the declaration that contains it.
     */
    private int skipSpaceAndComments(String value, int i, int end) {
        while (i < end) {
            char c = value.charAt(i);
            if (isCssSpace(c)) { i++; continue; }
            if (c == '/' && i + 1 < end && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0 || close >= end) return -1;
                i = close + 2;
                continue;
            }
            break;
        }
        return i;
    }

    /** Whitespace and comments may separate a function name from its opening parenthesis ({@code url /**\/ ( ... )}). */
    private int skipSeparators(String value, int i, int end) {
        return skipSpaceAndComments(value, i, end);
    }

    /**
     Tests whether a comment-only separator (no whitespace) from {@code nameEnd} glues the preceding identifier run
     to another identifier run that is itself followed (optionally via separators) by {@code (}. A comment wedged
     between two identifier runs ({@code ur/**\/l( ... )} or {@code expr/**\/ession( ... )}) does not join them under
     a strict CSS tokenizer, but a lenient engine could read the glued run as a single function token; such a
     declaration is rejected rather than risk a split {@code url} or {@code expression} escaping recognition. Real
     whitespace keeps the runs distinct tokens, and two bare runs without a following call ({@code a/**\/b}) stay
     untouched. The second run is read with full escape semantics so that an escaped continuation such as
     {@code ur/**\/\6c( ... )} is just as ambiguous.
     */
    private boolean commentGluesIdentToCall(String value, int nameEnd, int end) {
        int i = nameEnd;
        boolean sawComment = false;
        while (i < end) {
            char c = value.charAt(i);
            if (isCssSpace(c)) return false; // real whitespace keeps the two runs distinct tokens
            if (c == '/' && i + 1 < end && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0 || close > end) return false;
                sawComment = true;
                i = close + 2;
                continue;
            }
            break; // reached the start of whatever the separator abuts
        }
        if (!sawComment) return false;
        if (i >= end || !(isIdentStart(value.charAt(i)) || value.charAt(i) == '\\'))
            return false; // the separator must glue onto another identifier run
        StringBuilder glued = new StringBuilder();
        int k = scanIdent(value, i, end, glued);
        k = skipSeparators(value, k, end);
        return k >= 0 && k < end && value.charAt(k) == '(';
    }

    /**
     Denies the legacy script-entry properties that load executable or behavior-bound content even through an
     * otherwise allowed-looking {@code url(...)}: {@code -moz-binding} (XBL) and IE's {@code behavior}. The name is
     * the plain identifier run starting at {@code nameStart}; a comment appended to the name cannot change the
     * comparison.
     */
    private boolean isDangerousProperty(String value, int nameStart) {
        int p = nameStart;
        while (p < value.length() && isIdentChar(value.charAt(p))) p++;
        String name = value.substring(nameStart, p);
        return name.equalsIgnoreCase("-moz-binding") || name.equalsIgnoreCase("behavior");
    }

    /**
     Walks a declaration value in a single lexical pass: strings, comments, escapes, and balanced functions are
     recognized exactly as in declaration splitting. Block comments and the contents of ordinary quoted strings are
     inert token separators (their text is emitted verbatim but never fetched or tokenized as a call); a quoted
     string is still checked when it is the actual argument of a {@code url()} call. Every {@code url(...)}
     argument, an {@code @import} reference, and the legacy {@code expression(...)} function is read with full CSS
     escape semantics, so a hex or character escape, consecutive backslashes, or a case change in the function name
     ({@code \75 rl(...)}, a backslash-escaped {@code URL}, {@code \65 xpression(...)}) reaches the same decision as the plain
     spelling. Unbalanced parentheses, an unterminated string or comment, or any failed reference check rejects the
     declaration.
     */
    private boolean referencesAreSafe(String value, int start, int end) {
        int parenDepth = 0;
        int i = start;
        while (i < end) {
            char c = value.charAt(i);
            if (c == '"' || c == '\'') {
                int[] quote = scanQuoted(value, i + 1, c);
                if (quote[1] != 1) return false; // no bad-string recovery inside a value being checked
                // an identifier run glued directly to the string and followed by a call (e.g. u"rl"(...)) is a
                // syntax error a strict browser discards but a lenient one could read as one escaped function name
                if (stringGluesIdentToCall(value, i, quote[0], end)) return false;
                // ordinary string content is inert: it is never fetched as a reference (a quoted url() argument is
                // checked separately by isSafeUrlArgument)
                i = quote[0] + 1;
                continue;
            }
            if (c == '/' && i + 1 < end && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0 || close >= end) return false; // unterminated comment within the declaration
                i = close + 2; // comment text is inert token separators, never a concealed declaration or call
                continue;
            }
            if (c == '(') { parenDepth++; i++; continue; }
            if (c == ')') {
                if (parenDepth == 0) return false; // unmatched ')'
                parenDepth--;
                i++;
                continue;
            }
            if (c == '@') return false; // no at-rule (e.g. a buried @import) is valid inside a declaration value
            if (isIdentStart(c) || c == '-' || c == '\\') {
                StringBuilder decoded = new StringBuilder();
                int nameEnd = scanIdent(value, i, end, decoded);
                String fnName = decoded.toString();
                int j = skipSeparators(value, nameEnd, end);
                if (j < 0) return false; // an unterminated comment after the name rejects the declaration
                if (commentGluesIdentToCall(value, nameEnd, end)) return false; // e.g. ur/**/l(...)
                if (j < end && value.charAt(j) == '(') {
                    int argEnd = matchingParen(value, j + 1, end);
                    if (argEnd < 0) return false;
                    // a function name whose escapes decode to punctuation (e.g. "l\;expression") is not a real CSS
                    // function token; reject rather than risk it being read as two tokens by a quirky engine
                    if (!isPlainName(fnName)) return false;
                    if (fnName.equalsIgnoreCase("expression")) return false; // legacy JScript entry point
                    if (fnName.equalsIgnoreCase("url") && !isSafeUrlArgument(value, j + 1, argEnd)) return false;
                    i = j; // resume at '(' so a nested url()/expression() inside the arguments is also checked
                    continue;
                }
                i = nameEnd;
                continue;
            }
            i++;
        }
        return parenDepth == 0;
    }

    /** Tests that a function name is a plain identifier, with no escape that decoded to punctuation or whitespace. */
    private static boolean isPlainName(String name) {
        int length = name.length();
        if (length == 0) return false;
        for (int k = 0; k < length; k++) {
            char c = name.charAt(k);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /**
     Reads a CSS identifier token starting at {@code from} with full escape semantics: plain identifier characters
     (including a leading run of dashes), single-character escapes (a backslash before any character such as u or '(', or a doubled
     {@code \\}), and hex escapes ({@code \75}, up to six hex digits) together with the single whitespace
     delimiter that may follow one ({@code \75 rl} is therefore one token, exactly as a browser reads it). The
     decoded code points are appended to {@code decoded}, and the index just past the token is returned. The decoded
     form is what a function name is compared against, so a hex-escaped, character-escaped, doubled-backslash, or
     differently cased {@code url} or {@code expression} cannot hide from the reference checks.
     */
    private int scanIdent(String value, int from, int end, StringBuilder decoded) {
        int i = from;
        while (i < end) {
            char c = value.charAt(i);
            if (c == '\\') {
                int[] escape = consumeEscape(value, i, end);
                if (escape[1] >= 0) decoded.appendCodePoint(escape[1]);
                i = escape[0];
                continue;
            }
            if (isIdentChar(c)) { decoded.append(c); i++; continue; }
            break;
        }
        return i;
    }

    /**
     Consumes one CSS escape sequence beginning at the backslash {@code i}. Returns {@code [next, codePoint]}:
     {@code next} is the index past the escape (including the single whitespace delimiter a hex run may carry, a
     CRLF pair counting as one delimiter), and {@code codePoint} is the decoded code point. Per the CSS spec a NUL,
     surrogate, or out-of-range value becomes U+FFFD; a lone trailing backslash has no escape ({@code codePoint} is
     {@code -1}) and is left to the caller as a stray character.
     */
    private static int[] consumeEscape(String value, int i, int end) {
        if (i + 1 >= end) return new int[] {end, -1};
        char next = value.charAt(i + 1);
        if (isHex(next)) {
            int h = i + 1;
            int e = h;
            while (e < end && e - h < 6 && isHex(value.charAt(e))) e++;
            int code = Integer.parseInt(value.substring(h, e), 16);
            int after = e;
            if (after < end && isCssSpace(value.charAt(after))) {
                after++; // the single delimiter whitespace is part of the hex escape
                if (after - e == 2 && value.charAt(e) == '\r' && after < end && value.charAt(after) == '\n') after++;
            }
            if (code == 0 || code > Character.MAX_CODE_POINT
                || (code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE))
                code = 0xFFFD;
            return new int[] {after, code};
        }
        return new int[] {i + 2, next};
    }

    /**
     Applies the same reference rules to text that is not itself parsed as declarations — the free-form value of a
     custom property. As in an ordinary value, quoted strings and comments are inert token separators (a fake call
     written inside either never fetches), while every real {@code url(...)} (its name possibly hex- or
     character-escaped), {@code @import}, and the legacy {@code expression(...)} function is normalized and checked.
     */
    private boolean freeTextIsSafe(String text) {
        int end = text.length();
        int i = 0;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int[] quote = scanQuoted(text, i + 1, c);
                if (quote[1] != 1) return false;
                if (stringGluesIdentToCall(text, i, quote[0], end)) return false; // e.g. "u"rl(...) or ur"l"(...)
                i = quote[0] + 1;
                continue;
            }
            if (c == '/' && i + 1 < end && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                if (close < 0) return false;
                i = close + 2;
                continue;
            }
            if (c == '@') {
                StringBuilder atName = new StringBuilder();
                int nameEnd = scanIdent(text, i + 1, end, atName);
                if (atName.toString().equalsIgnoreCase("import") && !freeTextImportIsSafe(text, nameEnd, end))
                    return false;
                i = nameEnd > i + 1 ? nameEnd : i + 1;
                continue;
            }
            if (isIdentStart(c) || c == '-' || c == '\\') {
                StringBuilder decoded = new StringBuilder();
                int nameEnd = scanIdent(text, i, end, decoded);
                int j = skipSeparators(text, nameEnd, end);
                if (j < 0) return false;
                if (commentGluesIdentToCall(text, nameEnd, end)) return false; // e.g. ur/**/l(...)
                if (j < end && text.charAt(j) == '(') {
                    String fnName = decoded.toString();
                    int argEnd = matchingParen(text, j + 1, end);
                    if (argEnd < 0) return false;
                    if (!isPlainName(fnName)) return false;
                    if (fnName.equalsIgnoreCase("expression")) return false;
                    if (fnName.equalsIgnoreCase("url") && !isSafeUrlArgument(text, j + 1, argEnd)) return false;
                    i = j; // resume at '(' so a nested url()/expression() in the arguments is also checked
                    continue;
                }
                i = nameEnd;
                continue;
            }
            i++;
        }
        return true;
    }

    /**
     Tests whether a quoted string at {@code [open, close]} is glued without whitespace to identifier runs on
     either side and followed by a call: {@code "u"rl(...)}, {@code ur"l"(...)}, or {@code u"rl"(...)}. Such
     token sequences are value syntax errors a strict browser discards, but a lenient engine could read the glued
     runs as one escaped {@code url} or {@code expression} call, so the declaration is rejected rather than risk a
     split name escaping recognition. The adjacent runs are read with full escape semantics. Ordinary tokens
     separated from the string by whitespace (such as a real {@code url(...)} later in the value) are unaffected.
     */
    private boolean stringGluesIdentToCall(String value, int open, int close, int end) {
        boolean gluedBefore = open > 0 && isIdentChar(value.charAt(open - 1));
        int after = close + 1;
        boolean gluedAfter = after < end && (isIdentStart(value.charAt(after)) || value.charAt(after) == '\\');
        if (!gluedBefore && !gluedAfter) return false;
        int k = after;
        if (gluedAfter) {
            StringBuilder ignored = new StringBuilder();
            k = scanIdent(value, k, end, ignored);
        }
        k = skipSeparators(value, k, end);
        return k >= 0 && k < end && value.charAt(k) == '(';
    }

    /** Checks the quoted-string or {@code url(...)} reference of an {@code @import} found in free text. */
    private boolean freeTextImportIsSafe(String text, int from, int end) {
        int i = skipSeparators(text, from, end);
        if (i < 0) return false;
        if (i >= end) return true; // a dangling "@import" with no reference is inert junk
        char c = text.charAt(i);
        if (c == '"' || c == '\'') {
            int[] quote = scanQuoted(text, i + 1, c);
            if (quote[1] != 1) return false;
            return safelist.isSafeStyleUrl(tagName, el, unescape(text.substring(i + 1, quote[0])));
        }
        if (isIdentStart(c) || c == '-' || c == '\\') {
            StringBuilder fnName = new StringBuilder();
            int nameEnd = scanIdent(text, i, end, fnName);
            int k = skipSeparators(text, nameEnd, end);
            if (k < 0 || k >= end || text.charAt(k) != '(' || !fnName.toString().equalsIgnoreCase("url")) return false;
            int argEnd = matchingParen(text, k + 1, end);
            return argEnd >= 0 && isSafeUrlArgument(text, k + 1, argEnd);
        }
        return false;
    }

    /** Extracts and validates the single URL argument of a {@code url(...)} function. */
    private boolean isSafeUrlArgument(String value, int from, int end) {
        int i = from;
        while (i < end && isCssSpace(value.charAt(i))) i++;
        int j = end;
        while (j > i && isCssSpace(value.charAt(j - 1))) j--;
        if (i == j) return false; // url() with no reference

        char first = value.charAt(i);
        String raw;
        if (first == '"' || first == '\'') {
            int[] quote = scanQuoted(value, i + 1, first);
            if (quote[1] != 1 || quote[0] + 1 != j) return false; // unterminated or trailing junk inside the quotes
            raw = value.substring(i + 1, quote[0]);
            for (int k = 0; k < raw.length(); k++) { // the closing quote may not appear inside, escaped or not
                char c = raw.charAt(k);
                if (c == '\\') { k++; continue; }
                if (c == first) return false;
            }
        } else {
            // an unquoted URL token is a single run: a bare quote or whitespace is a syntax error, not part of the
            // URL. Whitespace that is the delimiter of a hex escape (or a whitespace character itself escaped) is
            // part of the escape and so stays in the run, exactly as a browser reads it.
            int k = i;
            while (k < j) {
                char c = value.charAt(k);
                if (c == '\\') {
                    int[] escape = consumeEscape(value, k, j);
                    if (escape[1] < 0) return false; // a lone trailing backslash is no URL token
                    k = escape[0];
                    continue;
                }
                if (c == '"' || c == '\'' || isCssSpace(c)) return false;
                k++;
            }
            raw = value.substring(i, j);
        }
        return safelist.isSafeStyleUrl(tagName, el, unescape(raw));
    }

    /** Returns the index of the ')' matching the '(' whose argument starts at {@code from}, or {@code -1}. */
    private int matchingParen(String value, int from, int end) {
        int depth = 1;
        int i = from;
        while (i < end) {
            char c = value.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"' || c == '\'') {
                int[] quote = scanQuoted(value, i + 1, c);
                if (quote[1] != 1) return -1;
                i = quote[0] + 1;
                continue;
            }
            if (c == '/' && i + 1 < end && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0 || close >= end) return -1; // an unterminated comment leaves the function unclosed
                i = close + 2;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return i; }
            i++;
        }
        return -1;
    }

    /**
     Decodes CSS backslash escapes in a URL token, mirroring how a browser reads the reference: a {@code \}{@code hEX}
     run (up to six hex digits, optionally followed by one whitespace delimiter) becomes its code point, and any other
     backslash-prefixed character collapses to that character. An obfuscated scheme such as {@code java\73cript:} or
     {@code \6a avascript:} therefore cannot hide from the protocol check. A NUL code point is dropped, matching the
     CSS requirement to replace it.
     */
    static String unescape(String value) {
        if (value.indexOf('\\') == -1) return value;
        int length = value.length();
        StringBuilder sb = null;
        int i = 0;
        while (i < length) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < length) {
                if (sb == null) {
                    sb = new StringBuilder(length);
                    sb.append(value, 0, i);
                }
                int h = i + 1;
                int hexEnd = h;
                while (hexEnd < length && hexEnd - h < 6 && isHex(value.charAt(hexEnd))) hexEnd++;
                if (hexEnd > h) {
                    int code = Integer.parseInt(value.substring(h, hexEnd), 16);
                    if (hexEnd < length && isCssSpace(value.charAt(hexEnd))) hexEnd++;
                    // per the CSS spec, NUL, surrogates, and values above the Unicode range become U+FFFD
                    if (code == 0 || code > Character.MAX_CODE_POINT
                        || (code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE))
                        code = 0xFFFD;
                    sb.appendCodePoint(code);
                    i = hexEnd;
                } else {
                    sb.append(value.charAt(i + 1));
                    i += 2;
                }
                continue;
            }
            if (sb != null) sb.append(c);
            i++;
        }
        return sb != null ? sb.toString() : value;
    }

    static boolean isCssSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentChar(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '-';
    }
}
