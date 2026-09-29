package org.jsoup.safety;

import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 Parses and sanitizes the text of an allowed {@code <style>} element, one stylesheet rule at a time. Like
 {@link StyleCleaner}, it is a small hand scanner rather than a full CSS parser: each scope (the whole sheet, or an
 at-rule block) is split into qualified rules ({@code selector { declarations }}) and statements ({@code @import …},
 {@code @media …}) while tracking the actual nesting of strings, parentheses, block comments, and CSS backslash
 escapes, so punctuation inside strings, functions, or comments cannot break out of its rule or declaration. A
 brace at the top level of a scope always bounds a block (a brace inside a balanced function does not), and a '}'
 after an unclosed function is honored as the recovery boundary, so the malformed declaration invalidates only
 itself and never swallows the rules after the block. Nested blocks (rules inside an {@code @media}/
 {@code @supports} block) are handled recursively and independently.

 <p>Selectors, at-rule preludes, ordinary declaration names and values (numbers, colors, fonts, custom
 properties), whitespace, comments, and duplicates are emitted verbatim in source order. {@code @import}
 references, every {@code url(…)} argument (including one nested inside another function), and the legacy
 {@code expression(…)} function are checked with the same URL policy and the same obfuscation handling (mixed
 case, HTML entities, percent-encoding, control bytes, and CSS escapes) as an inline {@code style} attribute. The
 HTML parser keeps {@code <style>} contents as raw text and never applies HTML entities to it, so rule and
 declaration boundaries are always scanned in the raw text (an entity such as {@code &#123;} cannot forge a block
 boundary), while entities within an already bounded statement or declaration are decoded only for the safety
 decision; a surviving fragment always keeps its original spelling.</p>

 <p>A rejected import or declaration takes only itself; its rule's surviving selectors and declarations are kept,
 and no safe content in the same rule is lost. Unclosed strings, parentheses, or comments, repeated separators, and
 truncated imports and nested references never throw: rules whose boundaries can still be determined are handled
 independently, and only the unbounded or undecidable fragment is discarded. When no rule survives the result is
 empty, in which case the caller removes the {@code <style>} element.</p>

 <p>The cleaner is stateless and thread-safe; all per-sheet state lives on the local parse stack.</p>
 */
final class StyleSheetCleaner {
    // bound recursion so a deliberately or accidentally deep nesting cannot exhaust a small (e.g. virtual-thread)
    // stack; this matches the parser's own max depth and is far beyond any realistically authored stylesheet
    private static final int MaxNesting = 512;

    private final StyleCleaner sc;

    private StyleSheetCleaner(Safelist safelist, String tagName, Element el) {
        this.sc = StyleCleaner.create(safelist, tagName, el);
    }

    /**
     Cleans the raw text of an allowed {@code <style>} element rule by rule, preserving the order and original
     spelling of every rule, declaration, and value that survives.
     @return the cleaned stylesheet text, or {@code null} when no rule survives (in which case the element is removed)
     */
    static String clean(Safelist safelist, String tagName, Element el, String css) {
        return new StyleSheetCleaner(safelist, tagName, el).cleanSheet(css);
    }

    private String cleanSheet(String css) {
        int[] match = sc.parenMatches(css); // every parenthesis's mate, computed once and shared by every scope
        int[] opens = new int[css.length()]; // the per-scope open stack; block boundaries always reset its depth
        StringBuilder out = StringUtil.borrowBuilder();
        process(css, 0, css.length(), 0, match, opens, out);
        return out.length() == 0 ? null : StringUtil.releaseBuilder(out);
    }

    /**
     Walks one stylesheet scope (the whole sheet, or an at-rule block) and appends every surviving statement and
     rule to {@code out} in source order. A parenthesis closes a function only when its mate lies within the
     scope, so a semicolon stays function content only for a function that closes before the block ends.
     */
    private void process(String css, int start, int end, int depth, int[] match, int[] opens, StringBuilder out) {
        int parenDepth = 0;
        int ruleStart = start;
        int i = start;
        while (i < end) {
            char c = css.charAt(i);
            if (c == '\\') { // CSS escape: the next code unit is literal, even '\{' or '\;'
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                int[] quote = sc.scanQuoted(css, i + 1, c);
                i = quote[1] == 1 ? quote[0] + 1 : quote[0]; // recover at a newline/';', else run to scope end
                continue;
            }
            if (c == '/' && i + 1 < end && css.charAt(i + 1) == '*') {
                int close = css.indexOf("*/", i + 2);
                // a closer beyond this scope cannot close the comment inside it: a CSS comment runs to EOF, so the
                // comment and everything after it in this scope is an unbounded fragment. The statement accumulated
                // before the comment still has a determinable boundary and is validated on its own merits; a later
                // scope (after the block's real '}') is unaffected and parses independently.
                if (close < 0 || close >= end) {
                    emitStatement(css, ruleStart, i, false, out);
                    ruleStart = end;
                    i = end;
                } else {
                    i = close + 2;
                }
                continue;
            }
            if (c == '(') {
                opens[parenDepth++] = i;
            } else if (c == ')') {
                if (parenDepth > 0) parenDepth--; // a stray ')' outside any function is ordinary separator text
            } else if (c == '{' && parenDepth == 0) {
                int close = matchingBrace(css, i + 1, end, match, opens);
                int bodyEnd = close >= 0 ? close : end;
                emitRule(css, ruleStart, i, bodyEnd, depth, match, opens, out);
                parenDepth = 0; // braces always bound blocks; an unclosed function does not carry into the next rule
                ruleStart = close >= 0 ? close + 1 : end;
                i = ruleStart;
                continue;
            } else if (c == '}' && parenDepth == 0) {
                // a stray '}' closes no block opened in this scope: this is a CSS parse error and the unfinished
                // statement accumulated before it (whitespace and comments included, since a comment-only fragment
                // would not survive a re-clean on its own) is discarded rather than glued onto the next surviving
                // statement, which would change its meaning and fail to re-clean. The following rules, whose
                // boundaries can still be determined, parse independently.
                parenDepth = 0;
                ruleStart = i + 1;
            }
            // a '{' or '}' inside an unclosed function is ordinary (invalid) declaration text: block boundaries are
            // established by matchingBrace, which honors '}' so the function cannot swallow the following rules
            else if (c == ';' && (parenDepth == 0 || match[opens[parenDepth - 1]] < 0
                || match[opens[parenDepth - 1]] >= end)) {
                // a ';' ends the statement unless it is content of a function that closes later in this scope
                emitStatement(css, ruleStart, i, true, out);
                parenDepth = 0;
                ruleStart = i + 1;
            }
            i++;
        }
        if (ruleStart < end) emitStatement(css, ruleStart, end, false, out); // a trailing statement without ';'
    }

    /**
     Validates and emits one qualified rule (or at-rule block): the prelude is copied verbatim when it carries no
     unsafe reference, and the block is emitted only when its body yields surviving content. Nested blocks are
     processed into their own buffer first, so an empty body never leaves an empty rule behind.
     */
    private void emitRule(String css, int preludeStart, int brace, int bodyEnd, int depth, int[] match, int[] opens,
                          StringBuilder out) {
        int p = sc.skipSpaceAndComments(css, preludeStart, brace);
        if (p < 0 || p >= brace) return; // an unterminated comment or an empty prelude (a stray '{') is not a rule
        if (!preludeIsSafe(css, p, brace)) return;
        StringBuilder body = StringUtil.borrowBuilder();
        // every block body is walked as a rule scope: nested rules (@media/@supports) recurse, and declarations
        // (@font-face/@page, or a qualified rule's contents) validate as declarations
        if (depth < MaxNesting) process(css, brace + 1, bodyEnd, depth + 1, match, opens, body);
        if (body.length() == 0) {
            StringUtil.releaseBuilderVoid(body);
            return;
        }
        out.append(css, preludeStart, brace + 1); // selector/at-rule prelude and the opening brace, verbatim
        out.append(body);
        StringUtil.releaseBuilderVoid(body);
        // a block whose '}' was lost (e.g. swallowed by an unterminated comment) is closed here, exactly as a
        // browser closes it at EOF, so the output never carries an orphan opening brace and stays idempotent. When
        // the body ends with an unescaped run of backslashes, one added '}' would itself be CSS-escaped and fail to
        // close the block; add a second '}' the run cannot consume.
        int slashes = 0;
        for (int b = out.length() - 1; b >= 0 && out.charAt(b) == '\\'; b--) slashes++;
        out.append('}');
        if (slashes % 2 == 1) out.append('}');
    }

    /**
     A selector/at-rule prelude is inert text except for an actual reference it may carry. The leading at-keyword
     of an at-rule prelude ({@code @media}, {@code @supports}) is skipped, since an '@' cannot start a declaration
     value; the remainder is checked like an ordinary declaration value.
     */
    private boolean preludeIsSafe(String css, int start, int end) {
        String raw = css.substring(start, end);
        String check = raw.indexOf('&') >= 0 ? Parser.unescapeEntities(raw, false) : raw;
        int len = check.length();
        int s = sc.skipSpaceAndComments(check, 0, len);
        if (s < 0 || s >= len) return false;
        if (check.charAt(s) == '@') {
            StringBuilder atName = new StringBuilder();
            int nameEnd = sc.scanIdent(check, s + 1, len, atName);
            if (nameEnd == s + 1) return false; // a bare '@' is not an at-rule
            s = sc.skipSeparators(check, nameEnd, len);
            if (s < 0 || s >= len) return true; // an at-keyword with no prelude condition
        }
        return sc.referencesAreSafe(check, s, len);
    }

    /**
     Validates and emits one top-level statement between separators. Whitespace- or comment-only fragments are
     skipped; an {@code @import}'s reference must pass the URL rules, any other at-statement passes when its own
     references pass, and a declaration-shaped statement passes the same declaration rules as an inline
     {@code style} attribute (so the legacy script-entry properties are denied everywhere). A surviving statement
     keeps its source spelling, and the source semicolon that terminated it ({@code terminated}) is emitted so
     adjacent statements stay separated; a block- or EOF-bounded statement gains none.
     */
    private void emitStatement(String css, int start, int end, boolean terminated, StringBuilder out) {
        int p = sc.skipSpaceAndComments(css, start, end);
        if (p < 0 || p >= end) return; // an unterminated comment makes the statement unbounded
        int q = end;
        while (q > p && StyleCleaner.isCssSpace(css.charAt(q - 1))) q--;
        if (p == q) return;
        String raw = css.substring(start, q);
        String check = raw.indexOf('&') >= 0 ? Parser.unescapeEntities(raw, false) : raw;
        int len = check.length();
        int s = sc.skipSpaceAndComments(check, 0, len);
        if (s < 0 || s >= len) return;
        boolean safe;
        int keptEnd = q; // source end of the surviving text (usually the whole trimmed statement)
        if (check.charAt(s) == '@') {
            StringBuilder atName = new StringBuilder();
            int nameEnd = sc.scanIdent(check, s + 1, len, atName);
            if (nameEnd == s + 1) return; // a bare '@' is not an at-rule
            if (atName.toString().equalsIgnoreCase("import")) {
                safe = sc.isSafeImportDeclaration(check, s, len);
            } else {
                // other at-statements (@charset, @namespace, an unknown prelude): skip the at-keyword, then check
                // the remainder like an ordinary value
                int r = sc.skipSeparators(check, nameEnd, len);
                safe = r >= 0 && (r >= len || sc.referencesAreSafe(check, r, len));
            }
        } else {
            // a declaration-shaped statement (including the contents of an @font-face/@page block)
            int to = sc.safeDeclarationRange(check, 0, len, false);
            safe = to > 0;
            if (safe && check == raw) keptEnd = start + to; // trim exactly as the inline cleaner would
        }
        if (safe) {
            out.append(css, start, keptEnd);
            if (terminated) {
                // when the surviving text ends with an unescaped run of backslashes, an added ';' would itself be
                // CSS-escaped and glue this statement to the next one on a re-clean; a single space consumes the
                // escape so the separator stays a separator
                int slashes = 0;
                for (int b = out.length() - 1; b >= 0 && out.charAt(b) == '\\'; b--) slashes++;
                if (slashes % 2 == 1) out.append(' ');
                out.append(';');
            }
        }
    }

    /**
     Finds the '}' that closes a block, skipping strings, comments, escapes, parentheses, and nested braces. A '{'
     inside a balanced function never starts a nested block, and a '}' inside a balanced function never closes the
     outer block; but a '}' after an unclosed '(' (a function whose ')' is missing within the scope) is honored as
     the block boundary, the same parse-error recovery a browser performs, so the malformed declaration invalidates
     only itself and the following rules still parse independently.
     */
    private int matchingBrace(String css, int from, int end, int[] match, int[] opens) {
        int braceDepth = 1;
        int parenDepth = 0;
        int i = from;
        while (i < end) {
            char c = css.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"' || c == '\'') {
                int[] quote = sc.scanQuoted(css, i + 1, c);
                i = quote[1] == 1 ? quote[0] + 1 : quote[0]; // closed quote, or recovery at a newline/';'/EOF
                continue;
            }
            if (c == '/' && i + 1 < end && css.charAt(i + 1) == '*') {
                int close = css.indexOf("*/", i + 2);
                if (close < 0 || close >= end) return -1;
                i = close + 2;
                continue;
            }
            if (c == '(') {
                opens[parenDepth++] = i;
            } else if (c == ')') {
                if (parenDepth > 0) parenDepth--;
            } else if (parenDepth == 0 && c == '{') {
                braceDepth++;
            } else if (c == '}') {
                // a '}' bounds the block only when the innermost '(' is closed within the scope; otherwise the
                // function is unclosed and this '}' is the browser-style recovery point
                int open = parenDepth > 0 ? opens[parenDepth - 1] : -1;
                boolean insideClosedFunction = open >= 0 && match[open] >= 0 && match[open] < end;
                if (!insideClosedFunction) {
                    braceDepth--;
                    if (braceDepth == 0) return i;
                }
            }
            i++;
        }
        return -1;
    }
}
