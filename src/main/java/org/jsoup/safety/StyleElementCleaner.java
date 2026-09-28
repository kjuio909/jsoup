package org.jsoup.safety;

import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;

/**
 Sanitizes the text of an allowed {@code <style>} element, rule by rule. Like {@link StyleCleaner} for an inline
 {@code style} attribute, this is a small hand scanner rather than a full CSS parser: the stylesheet is split into
 qualified rules on top-level braces while tracking the actual nesting of quoted strings, block comments, and CSS
 backslash escapes, so punctuation inside a selector or value ({@code a[href="x}y"], {@code content: "{"}) cannot end
 the rule early.

 <p>Each rule is treated independently and emitted in original order. The selector and at-rule prelude are kept
 verbatim (plain selectors, declaration and custom-property names, numbers, colors, and fonts are untouched); only
 declarations are validated, reusing exactly the inline-style rules in {@link StyleCleaner}, so an {@code @import}
 reference, a {@code url(...)}, a nested reference, or the legacy {@code expression(...)} function is CSS-unescaped
 and checked against the protocols configured for the {@code style} tag, and a failed check removes only the
 declaration or import that owns it, never the rule's other content. Rules whose brace boundary cannot be determined,
 a dangling fragment, or a declaration whose URL cannot be resolved is discarded, while a later rule whose boundary
 is certain is still processed; a fake reference written inside a comment or a quoted string is inert and never
 checked. When no rule survives the whole {@code <style>} element is removed by the caller.</p>

 <p>The cleaner is stateless and thread-safe; all per-sheet state is local to the {@link #clean} call.</p>
 */
final class StyleElementCleaner {
    private final Safelist safelist;
    private final StyleCleaner css; // shares the inline-style reference, escape, and declaration rules

    private StyleElementCleaner(Safelist safelist, Element el) {
        this.safelist = safelist;
        this.css = StyleCleaner.forStyleElement(safelist, el);
    }

    /**
     Cleans the raw text of one {@code <style>} element.
     @return the cleaned stylesheet text, or {@code null} when no rule survives (in which case the element is removed)
     */
    static String clean(Safelist safelist, Element el, String css) {
        return new StyleElementCleaner(safelist, el).clean(css);
    }

    private String clean(String sheet) {
        StringBuilder out = StringUtil.borrowBuilder();
        int length = sheet.length();
        int i = 0;
        while (i < length) {
            int[] prelude = scanPrelude(sheet, i, length);
            int p = prelude[0];
            int state = prelude[1];
            if (state == 1) { // a complete rule body
                int open = p;
                int close = findBlockEnd(sheet, open + 1, length);
                if (close < 0) {
                    // an unclosed block has no determinable end: discard it and every unclosed fragment that follows,
                    // but a later well-bounded rule (after an intervening '}') is still reachable
                    int next = sheet.indexOf('}', open + 1);
                    if (next < 0) break;
                    i = next + 1;
                    continue;
                }
                appendRule(sheet, i, open, open + 1, close, out);
                i = close + 1;
                continue;
            }
            if (state == 2) { // a statement terminated by ';' outside any block (e.g. @import)
                int semi = p;
                appendStatement(sheet, i, semi, out);
                i = semi + 1;
                continue;
            }
            if (state == 3) { // a stray '}' that closes nothing: drop just the stray character
                i = p + 1;
                continue;
            }
            break; // state 0: no further bounded content
        }

        if (out.length() == 0) {
            StringUtil.releaseBuilderVoid(out);
            return null;
        }
        return StringUtil.releaseBuilder(out);
    }

    /**
     Scans the prelude (selector or at-rule header) beginning at {@code from} up to the start of a rule body.
     <ul>
       <li>state {@code 1}: an opening brace at {@code [0]};</li>
       <li>state {@code 2}: a top-level semicolon at {@code [0]} ending a statement;</li>
       <li>state {@code 3}: a stray closing brace at {@code [0]};</li>
       <li>state {@code 0}: the end of the sheet was reached with neither; {@code [0]} is the sheet length.</li>
     </ul>
     An unclosed string or comment in the prelude is recovered at the next line (bad-string) or at the next
     {@code ;}/{@code /}/{@code }} boundary, mirroring the declaration scanner; the prelude text itself is still
     validated for references when the rule is assembled (an {@code @import} reference lives here).
     */
    private int[] scanPrelude(String s, int from, int length) {
        int i = from;
        while (i < length) {
            char c = s.charAt(i);
            if (c == '\\') { // CSS escape: the next code unit is literal
                i += (i + 1 < length) ? 2 : 1;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = recoverToken(s, i, length, c);
                continue;
            }
            if (c == '/' && i + 1 < length && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                if (close < 0) { // unterminated comment: recover at the next boundary, if any
                    int next = nextBoundary(s, i + 2, length);
                    return new int[] {next < 0 ? length : next, next < 0 ? 0 : boundaryState(s, next)};
                }
                i = close + 2;
                continue;
            }
            if (c == '{') return new int[] {i, 1};
            if (c == ';') return new int[] {i, 2};
            if (c == '}') return new int[] {i, 3};
            i++;
        }
        return new int[] {length, 0};
    }

    /**
     Continues after an opening {@code quote} that may be unterminated: a closing quote resumes normal scanning, a
     newline ends the bad string (CSS bad-string recovery), otherwise scanning resumes at the next structural
     boundary. Returns the index to continue from.
     */
    private int recoverToken(String s, int quotePos, int length, char quote) {
        int[] quote = StyleCleaner.scanQuoted(s, quotePos + 1, quote);
        if (quote[1] == 1) return quote[0] + 1; // properly closed
        if (quote[1] == 2) return quote[0] + 1; // recovered at the newline
        // no close and no newline: the prelude is cut at its semicolon/brace boundary
        return quote[0] == length ? length : quote[0] + 1;
    }

    private static int boundaryState(String s, int idx) {
        char c = s.charAt(idx);
        if (c == '{') return 1;
        if (c == ';') return 2;
        return 3; // '}'
    }

    private static int nextBoundary(String s, int from, int length) {
        for (int i = from; i < length; i++) {
            char c = s.charAt(i);
            if (c == '{' || c == ';' || c == '}') return i;
        }
        return -1;
    }

    /**
     Finds the brace closing the block whose opening brace is just before {@code from}, applying string, comment, and
     escape lexical rules (a brace inside either is inert). Nested braces are balanced, so an {@code @media}
     conditional block and its inner rule each keep their own boundary.
     @return the index of the matching {@code }}, or {@code -1} if it never closes
     */
    private int findBlockEnd(String s, int from, int length) {
        int depth = 1;
        int i = from;
        while (i < length) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"' || c == '\'') {
                int[] quote = StyleCleaner.scanQuoted(s, i + 1, c);
                if (quote[1] == 1) { i = quote[0] + 1; continue; }
                // a bad string inside a body: recover at the next newline (quote[1]==2), else the block is unbounded
                if (quote[1] == 2) { i = quote[0] + 1; continue; }
                return -1;
            }
            if (c == '/' && i + 1 < length && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                if (close < 0) return -1; // an unterminated comment leaves the block unbounded
                i = close + 2;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
            i++;
        }
        return -1;
    }

    /**
     Validates and appends one complete rule spanning {@code [ruleStart, close+1)}: its prelude
     {@code [ruleStart, open)} is preserved verbatim (selectors carry no fetchable reference), and its body
     {@code [bodyStart, bodyEnd)} is cleaned declaration by declaration. The rule is emitted only when at least its
     prelude or one declaration survives, so no empty rule or orphaned brace pair is left behind.
     */
    private void appendRule(String s, int ruleStart, int open, int bodyStart, int bodyEnd, StringBuilder out) {
        int preludeStart = ruleStart;
        int preludeEnd = open;
        while (preludeEnd > preludeStart && StyleCleaner.isCssSpace(s.charAt(preludeEnd - 1))) preludeEnd--;
        boolean hasPrelude = preludeEnd > preludeStart
            && lastNonSpace(s, preludeStart, preludeEnd) >= 0;

        String body = cleanBlockBody(s, bodyStart, bodyEnd);
        if (!hasPrelude && body == null) return; // whitespace-only block with no selector: drop the whole rule
        appendRuleText(s, ruleStart, open, bodyStart, bodyEnd, body, out);
    }

    /**
     Emits a surviving rule. When declarations were removed but the selector (or at least the brace structure)
     remains, the rule is reconstructed as {@code prelude { body }} without orphaned separators; when the body is
     unchanged it is emitted verbatim, preserving its original whitespace.
     */
    private void appendRuleText(String s, int ruleStart, int open, int bodyStart, int bodyEnd, String body, StringBuilder out) {
        String originalBody = s.substring(bodyStart, bodyEnd);
        String prelude = s.substring(ruleStart, open).trim();
        if (body != null && body.equals(originalBody)) {
            append(out, s, ruleStart, bodyEnd + 1);
            return;
        }
        StringBuilder rule = StringUtil.borrowBuilder();
        rule.append(prelude);
        if (prelude.length() > 0) rule.append(' ');
        rule.append('{').append(body == null ? "" : body).append('}');
        appendRaw(out, StringUtil.releaseBuilder(rule));
    }

    /**
     Cleans the body of a rule block declaration by declaration, delegating each declaration to the same logic as an
     inline style value. At-rules nested in the body (an {@code @import} is invalid there, and any other statement in
     declaration position is dropped) are handled per declaration.
     @return the cleaned body text, or {@code null} when no declaration survives
     */
    private String cleanBlockBody(String s, int bodyStart, int bodyEnd) {
        // reuse the declaration scanner by feeding it the body verbatim; appendSafeDeclaration is driven by the same
        // lexical splitting and per-decision URL checks as the inline style attribute
        StringBuilder body = StringUtil.borrowBuilder();
        int i = bodyStart;
        int segStart = i;
        while (i < bodyEnd) {
            char c = s.charAt(i);
            int semi = findDeclarationEnd(s, i, bodyEnd);
            if (semi < 0) { // last declaration, or an unclosed construct runs to the block boundary
                css.appendSheetDeclaration(s, segStart, bodyEnd, body);
                break;
            }
            css.appendSheetDeclaration(s, segStart, semi, body);
            segStart = semi + 1;
            i = semi + 1;
        }
        if (body.length() == 0) {
            StringUtil.releaseBuilderVoid(body);
            return null;
        }
        return StringUtil.releaseBuilder(body);
    }

    /**
     Finds the semicolon that ends the declaration starting at {@code from} at the current nesting, honoring quoted
     strings, comments, escapes, and balanced parentheses exactly as {@code StyleCleaner.clean} does. Returns the
     semicolon index, or {@code -1} when the remaining body holds no terminating semicolon.
     */
    private int findDeclarationEnd(String s, int from, int end) {
        int parenDepth = 0;
        int i = from;
        while (i < end) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"' || c == '\'') {
                int[] quote = StyleCleaner.scanQuoted(s, i + 1, c);
                if (quote[1] == 1) i = quote[0] + 1;
                else i = quote[0]; // bad string: continue from its recovery point; the declaration check will reject it
                continue;
            }
            if (c == '/' && i + 1 < end && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                if (close < 0) return -1; // unterminated comment: the rest of the block is one fragment
                i = close + 2;
                continue;
            }
            if (c == '(') parenDepth++;
            else if (c == ')') { if (parenDepth > 0) parenDepth--; }
            else if (c == ';' && parenDepth == 0) return i;
            i++;
        }
        return -1;
    }

    /**
     Validates and appends a top-level statement terminated by a semicolon (an {@code @import}, or ordinary
     free-floating text). An {@code @import} is kept only when its reference passes the URL rules; other statements
     in this position are preserved verbatim (they carry no fetchable reference a selector cannot already express).
     */
    private void appendStatement(String s, int start, int semi, StringBuilder out) {
        int p = start;
        while (p < semi && StyleCleaner.isCssSpace(s.charAt(p))) p++;
        if (p < semi && s.charAt(p) == '@') {
            if (css.isSafeImportDeclaration(s, p, semi)) append(out, s, start, semi + 1);
            return; // a malformed/non-import at-rule statement is dropped with its terminator
        }
        append(out, s, start, semi + 1);
    }

    /** Appends a surviving text run, separated from an earlier run by nothing extra (original boundaries kept). */
    private static void append(StringBuilder out, String s, int from, int to) {
        out.append(s, from, to);
    }

    private static void appendRaw(StringBuilder out, String text) {
        out.append(text);
    }

    private static int lastNonSpace(String s, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (!StyleCleaner.isCssSpace(s.charAt(i))) return i;
        }
        return -1;
    }
}
