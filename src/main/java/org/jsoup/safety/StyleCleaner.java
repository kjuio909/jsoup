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
 declarations are still parsed independently. Comment text is emitted verbatim, so every comment — including one in a
 leading, name-to-colon, or function-name separator position — is scanned for a concealed reference; a rejected URL
 can never survive inside a comment.
 Each surviving declaration is scanned for references ({@code url(...)}, the legacy {@code expression(...)}
 function, and an {@code @import} at-rule) and every reference found is normalized through CSS unescaping and checked
 against the safelist's URL rules. Repeated separators and other degenerate input never throw and never cause
 following declarations to be joined into a rejected value.</p>

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
    private int[] scanQuoted(String value, int from, char quote) {
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
        if (p < 0) return; // an unterminated or unsafe leading comment rejects only this declaration
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
    private boolean isSafeImportDeclaration(String value, int at, int end) {
        int i = at + 1;
        if (!matchesName(value, i, end, "import")) return false;
        i += 6;
        while (i < end && isCssSpace(value.charAt(i))) i++;
        if (i == end) return false;
        int afterRef;
        char c = value.charAt(i);
        if (c == '"' || c == '\'') {
            int[] quote = scanQuoted(value, i + 1, c);
            if (quote[1] != 1) return false;
            if (!safelist.isSafeStyleUrl(tagName, el, unescape(value.substring(i + 1, quote[0])))) return false;
            afterRef = quote[0] + 1;
        } else if (matchesName(value, i, end, "url")) {
            int k = skipSeparators(value, i + 3, end);
            if (k < 0 || k >= end || value.charAt(k) != '(') return false;
            int argEnd = matchingParen(value, k + 1, end);
            if (argEnd < 0 || !isSafeUrlArgument(value, k + 1, argEnd)) return false;
            afterRef = argEnd + 1;
        } else {
            return false;
        }
        return referencesAreSafe(value, afterRef, end);
    }

    /**
     Advances past CSS whitespace and complete block comments, scanning every skipped comment body for a concealed
     reference: the comment text is emitted verbatim, so a rejected URL in a leading comment, a name-to-colon gap, or
     a function-name separator must not escape the URL rules. Returns {@code -1} when a comment is unterminated or
     carries an unsafe reference.
     */
    private int skipSpaceAndComments(String value, int i, int end) {
        while (i < end) {
            char c = value.charAt(i);
            if (isCssSpace(c)) { i++; continue; }
            if (c == '/' && i + 1 < end && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0 || close >= end) return -1;
                if (!freeTextIsSafe(value.substring(i + 2, close))) return -1;
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
     Tests whether the gap {@code [nameEnd, sepEnd)} just skipped is made up solely of block comments with no
     * whitespace and glues the preceding name to another identifier run that is itself followed (optionally via
     * separators) by {@code (}. A comment wedged between two identifier runs ({@code ur/**\/l( ... )} or
     * {@code expr/**\/ession( ... )}) does not join them under a strict CSS tokenizer, but a lenient engine could
     * read the glued run as a single function token; such a declaration is rejected rather than risk a split
     * {@code url} or {@code expression} escaping recognition. Two bare runs without a following call
     * ({@code a/**\/b}) stay untouched.
     */
    private boolean commentGluesNameToIdent(String value, int nameEnd, int sepEnd, int end) {
        if (sepEnd >= end || !isIdentChar(value.charAt(sepEnd))) return false;
        int i = nameEnd;
        boolean sawComment = false;
        while (i < sepEnd) {
            char c = value.charAt(i);
            if (isCssSpace(c)) return false; // real whitespace keeps the two runs distinct tokens
            if (c == '/' && i + 1 < sepEnd && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0 || close > sepEnd) return false;
                sawComment = true;
                i = close + 2;
                continue;
            }
            return false;
        }
        if (!sawComment) return false;
        int k = sepEnd;
        while (k < end && isIdentChar(value.charAt(k))) k++; // the run the comment glued onto
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
     recognized exactly as in declaration splitting. Block comments are skipped entirely (their text is never parsed
     as CSS); string contents are still scanned for a concealed {@code url(...)}-shaped reference, because when the
     string is itself the argument of {@code url()} the browser unescapes it before fetching; every {@code url(...)}
     argument, an {@code @import} reference, and the legacy {@code expression(...)} function is normalized and checked
     against the safelist. Unbalanced parentheses, an unterminated string or comment, or any failed reference check
     rejects the declaration.
     */
    private boolean referencesAreSafe(String value, int start, int end) {
        int parenDepth = 0;
        int i = start;
        while (i < end) {
            char c = value.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"' || c == '\'') {
                int[] quote = scanQuoted(value, i + 1, c);
                if (quote[1] != 1) return false; // no bad-string recovery inside a value being checked
                if (!freeTextIsSafe(value.substring(i + 1, quote[0]))) return false;
                i = quote[0] + 1;
                continue;
            }
            if (c == '/' && i + 1 < end && value.charAt(i + 1) == '*') {
                int close = value.indexOf("*/", i + 2);
                if (close < 0 || close >= end) return false; // unterminated comment within the declaration
                // comment text is never a declaration, but a reference concealed in a comment must still pass the
                // same URL rules so that a rejected URL can never survive in the emitted attribute
                if (!freeTextIsSafe(value.substring(i + 2, close))) return false;
                i = close + 2;
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
            if (isIdentStart(c) || c == '-') {
                int[] name = readName(value, i, end);
                int nameEnd = name[0];
                String fnName = name[1] == 1 ? unescape(value.substring(i, nameEnd)) : value.substring(i, nameEnd);
                int j = skipSeparators(value, nameEnd, end);
                if (j < 0) return false; // an unterminated or unsafe comment after the name rejects the declaration
                if (commentGluesNameToIdent(value, nameEnd, j, end)) return false; // e.g. ur/**/l(...)
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
     Reads a CSS name token (identifier characters, plus backslash escapes) from {@code from}. Returns
     {@code [end, escaped]}: {@code end} is the index after the token, and {@code escaped} is 1 when it contained a
     backslash. A leading run of dashes is consumed so that e.g. {@code -moz-binding} reads as one token.
     */
    private int[] readName(String value, int from, int end) {
        int i = from;
        int escaped = 0;
        while (i < end) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (i + 1 >= end) break;
                escaped = 1;
                i += 2;
                continue;
            }
            if (isIdentChar(c)) { i++; continue; }
            break;
        }
        return new int[] {i, escaped};
    }

    /**
     Applies the same URL rules to text that is not itself parsed as declarations — the body of a quoted string, a
     * block comment, or the value of a custom property. Such text cannot introduce a declaration, but a rejected
     * reference must not survive in the emitted style, so every {@code url(...)}-shaped reference and every
     * {@code @import} reference is extracted (CSS-escapes normalized) and checked; the legacy
     * {@code expression(...)} function is always rejected.
     */
    private boolean freeTextIsSafe(String text) {
        int end = text.length();
        int i = 0;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '@' && matchesName(text, i + 1, end, "import")) {
                if (!freeTextImportIsSafe(text, i + 7, end)) return false;
                i += 7;
                continue;
            }
            if (isIdentStart(c) || c == '-') {
                int[] name = readName(text, i, end);
                int j = skipSeparators(text, name[0], end);
                if (j < 0) return false;
                if (commentGluesNameToIdent(text, name[0], j, end)) return false; // e.g. ur/**/l(...)
                if (j < end && text.charAt(j) == '(') {
                    String fnName = name[1] == 1 ? unescape(text.substring(i, name[0])) : text.substring(i, name[0]);
                    int argEnd = matchingParen(text, j + 1, end);
                    if (argEnd < 0) return false;
                    if (!isPlainName(fnName)) return false;
                    if (fnName.equalsIgnoreCase("expression")) return false;
                    if (fnName.equalsIgnoreCase("url") && !isSafeUrlArgument(text, j + 1, argEnd)) return false;
                    i = j; // resume at '(' so a nested url()/expression() in the arguments is also checked
                    continue;
                }
                i = name[0];
                continue;
            }
            i++;
        }
        return true;
    }

    /** Checks the quoted-string or {@code url(...)} reference of an {@code @import} found in free text. */
    private boolean freeTextImportIsSafe(String text, int from, int end) {
        int i = from;
        while (i < end && isCssSpace(text.charAt(i))) i++;
        if (i >= end) return true; // a dangling "@import" with no reference is inert junk
        char c = text.charAt(i);
        if (c == '"' || c == '\'') {
            int[] quote = scanQuoted(text, i + 1, c);
            if (quote[1] != 1) return false;
            return safelist.isSafeStyleUrl(tagName, el, unescape(text.substring(i + 1, quote[0])));
        }
        if (matchesName(text, i, end, "url")) {
            int k = skipSeparators(text, i + 3, end);
            if (k < 0 || k >= end || text.charAt(k) != '(') return false;
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
            // an unquoted URL token is a single run: bare whitespace or quotes are syntax errors, not part of the URL
            for (int k = i; k < j; k++) {
                char c = value.charAt(k);
                if (c == '\\') { k++; continue; }
                if (c == '"' || c == '\'' || isCssSpace(c)) return false;
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

    private boolean matchesName(String value, int from, int end, String name) {
        int len = name.length();
        if (from + len > end) return false;
        for (int k = 0; k < len; k++) {
            char a = value.charAt(from + k);
            char b = name.charAt(k);
            if (a != b && a != Character.toUpperCase(b)) return false;
        }
        return true;
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

    private static boolean isCssSpace(char c) {
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
