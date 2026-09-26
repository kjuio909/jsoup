package org.jsoup.select;

import org.jsoup.helper.Regex;
import org.jsoup.internal.StringUtil;
import org.jsoup.helper.Validate;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.LeafNode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.TokenQueue;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jsoup.select.StructuralEvaluator.ImmediateParentRun;
import static org.jsoup.internal.Normalizer.normalize;

/**
 * Parses a CSS selector into an Evaluator tree.
 */
public class QueryParser implements AutoCloseable {
    private final static char[] Combinators = {'>', '+', '~'}; // ' ' is also a combinator, but found implicitly
    private final static String[] AttributeEvals = new String[]{"=", "!=", "^=", "$=", "*=", "~="};
    private final static char[] SequenceEnders = {',', ')'};

    private final TokenQueue tq;
    private final String query;
    private boolean inNodeContext; // ::comment:contains should act on node value, vs element text

    /**
     * Create a new QueryParser.
     * @param query CSS query
     */
    private QueryParser(String query) {
        Validate.notEmpty(query);
        query = query.trim();
        this.query = query;
        this.tq = new TokenQueue(query);
    }

    /**
     Parse a CSS query into an Evaluator. If you are evaluating the same query repeatedly, it may be more efficient to
     parse it once and reuse the Evaluator.

     @param query CSS query
     @return Evaluator
     @see Selector selector query syntax
     @throws Selector.SelectorParseException if the CSS query is invalid
     */
    public static Evaluator parse(String query) {
        try (QueryParser p = new QueryParser(query)) {
            return p.parse();
        } catch (IllegalArgumentException e) {
            throw new Selector.SelectorParseException(e.getMessage());
        }
    }

    /**
     Parse the query. We use this simplified expression of the grammar:
     <pre>
     SelectorGroup   ::= Selector (',' Selector)*
     Selector        ::= [ Combinator ] SimpleSequence ( Combinator SimpleSequence )*
     SimpleSequence  ::= [ TypeSelector ] ( ID | Class | Attribute | Pseudo )*
     Pseudo           ::= ':' Name [ '(' SelectorGroup ')' ]
     Combinator      ::= S+         // descendant (whitespace)
     | '>'       // child
     | '+'       // adjacent sibling
     | '~'       // general sibling
     </pre>

     See <a href="https://www.w3.org/TR/selectors-4/#grammar">selectors-4</a> for the real thing
     */
    Evaluator parse() {
        Evaluator eval = parseSelectorGroup();
        tq.consumeWhitespace();
        if (!tq.isEmpty())
            throw new Selector.SelectorParseException("Could not parse query '%s': unexpected token at '%s'", query, tq.remainder());
        return eval;
    }

    Evaluator parseSelectorGroup() {
        // SelectorGroup. Into an Or if > 1 Selector
        Evaluator left = parseSelector();
        while (tq.matchChomp(',')) {
            Evaluator right = parseSelector();
            left = or(left, right);
        }
        return left;
    }

    Evaluator parseSelector() {
        // Selector ::= [ Combinator ] SimpleSequence ( Combinator SimpleSequence )*
        tq.consumeWhitespace();

        Evaluator left;
        if (tq.matchesAny(Combinators)) {
            // e.g. query is "> div"; left side is root element
            left = new StructuralEvaluator.Root();
        } else {
            left = parseSimpleSequence();
        }

        while (true) {
            char combinator = 0;
            if (tq.consumeWhitespace())
                combinator = ' ';            // maybe descendant?
            if (tq.matchesAny(Combinators)) // no, explicit
                combinator = tq.consume();
            else if (tq.matchesAny(SequenceEnders)) // , - space after simple like "foo , bar"; ) - close of :has()
                break;

            if (combinator != 0) {
                Evaluator right = parseSimpleSequence();
                left = combinator(left, combinator, right);
            } else {
                break;
            }
        }
        return left;
    }

    Evaluator parseSimpleSequence() {
        // SimpleSequence ::= TypeSelector? ( Hash | Class | Pseudo )*
        Evaluator left = null;
        tq.consumeWhitespace();

        // one optional type selector
        if (tq.matchesWord() || tq.matches("*|"))
            left = byTag();
        else if (tq.matchChomp('*'))
            left = new Evaluator.AllElements();

        // zero or more subclasses (#, ., [)
        while(true) {
            Evaluator right = parseSubclass();
            if (right != null) {
                left = and(left, right);
            }
            else break; // no more simple tokens
        }

        if (left == null)
            throw new Selector.SelectorParseException("Could not parse query '%s': unexpected token at '%s'", query, tq.remainder());
        return left;
    }

    static Evaluator combinator(Evaluator left, char combinator, Evaluator right) {
        switch (combinator) {
            case '>':
                ImmediateParentRun run = left instanceof ImmediateParentRun ?
                    (ImmediateParentRun) left : new ImmediateParentRun(left);
                run.add(right);
                return run;
            case ' ':
                return and(new StructuralEvaluator.Ancestor(left), right);
            case '+':
                return and(new StructuralEvaluator.ImmediatePreviousSibling(left), right);
            case '~':
                return and(new StructuralEvaluator.PreviousSibling(left), right);
            default:
                throw new Selector.SelectorParseException("Unknown combinator '%s'", combinator);
        }
    }

    @Nullable Evaluator parseSubclass() {
        //  Subclass: ID | Class | Attribute | Pseudo
        if      (tq.matchChomp('#'))    return byId();
        else if (tq.matchChomp('.'))    return byClass();
        else if (tq.matches('['))       return byAttribute();
        else if (tq.matchChomp("::"))   return parseNodeSelector(); // ::comment etc
        else if (tq.matchChomp(':'))    return parsePseudoSelector();
        else                            return null;
    }

    /** Merge two evals into an Or. */
    static Evaluator or(Evaluator left, Evaluator right) {
        if (left instanceof CombiningEvaluator.Or) {
            ((CombiningEvaluator.Or) left).add(right);
            return left;
        }
        return new CombiningEvaluator.Or(left, right);
    }

    /** Merge two evals into an And. */
    static Evaluator and(@Nullable Evaluator left, Evaluator right) {
        if (left == null) return right;
        if (left instanceof CombiningEvaluator.And) {
            ((CombiningEvaluator.And) left).add(right);
            return left;
        }
        return new CombiningEvaluator.And(left, right);
    }

    private Evaluator parsePseudoSelector() {
        final String pseudo = tq.consumeCssIdentifier();
        switch (pseudo) {
            case "lt":
                return new Evaluator.IndexLessThan(consumeIndex());
            case "gt":
                return new Evaluator.IndexGreaterThan(consumeIndex());
            case "eq":
                return new Evaluator.IndexEquals(consumeIndex());
            case "has":
                return has();
            case "is":
                return is();
            case "contains":
                return contains(false);
            case "containsOwn":
                return contains(true);
            case "containsWholeText":
                return containsWholeText(false);
            case "containsWholeOwnText":
                return containsWholeText(true);
            case "containsData":
                return containsData();
            case "matches":
                return matches(false);
            case "matchesOwn":
                return matches(true);
            case "matchesWholeText":
                return matchesWholeText(false);
            case "matchesWholeOwnText":
                return matchesWholeText(true);
            case "not":
                return not();
            case "lang":
                return lang();
            case "nth-child":
                return cssNthChild(false, false);
            case "nth-last-child":
                return cssNthChild(true, false);
            case "nth-of-type":
                return cssNthChild(false, true);
            case "nth-last-of-type":
                return cssNthChild(true, true);
            case "first-child":
                return new Evaluator.IsFirstChild();
            case "last-child":
                return new Evaluator.IsLastChild();
            case "first-of-type":
                return new Evaluator.IsFirstOfType();
            case "last-of-type":
                return new Evaluator.IsLastOfType();
            case "only-child":
                return new Evaluator.IsOnlyChild();
            case "only-of-type":
                return new Evaluator.IsOnlyOfType();
            case "empty":
                return new Evaluator.IsEmpty();
            case "blank":
                return new NodeEvaluator.BlankValue();
            case "root":
                return new Evaluator.IsRoot();
            case "scope":
                return new StructuralEvaluator.Scope();
            case "matchText": {
                @SuppressWarnings("deprecation") // :matchText remains supported until its scheduled removal.
                Evaluator.MatchText matchText = new Evaluator.MatchText();
                return matchText;
            }
            default:
                throw new Selector.SelectorParseException("Could not parse query '%s': unexpected token at '%s'", query, tq.remainder());
        }
    }

    // ::comment etc
    private Evaluator parseNodeSelector() {
        final String pseudo = tq.consumeCssIdentifier();
        inNodeContext = true;  // Enter node context

        Evaluator left;
        switch (pseudo) {
            case "node":
                left = new NodeEvaluator.InstanceType(Node.class, pseudo);
                break;
            case "leafnode":
                left = new NodeEvaluator.InstanceType(LeafNode.class, pseudo);
                break;
            case "text":
                left = new NodeEvaluator.InstanceType(TextNode.class, pseudo);
                break;
            case "comment":
                left = new NodeEvaluator.InstanceType(Comment.class, pseudo);
                break;
            case "data":
                left = new NodeEvaluator.InstanceType(DataNode.class, pseudo);
                break;
            case "cdata":
                left = new NodeEvaluator.InstanceType(CDataNode.class, pseudo);
                break;
            default:
                throw new Selector.SelectorParseException(
                    "Could not parse query '%s': unknown node type '::%s'", query, pseudo);
        }

        // Handle following subclasses in node context (like ::comment:contains())
        Evaluator right;
        while ((right = parseSubclass()) != null) {
            left = and(left, right);
        }

        inNodeContext = false;
        return left;
    }

    private Evaluator byId() {
        String id = tq.consumeCssIdentifier();
        Validate.notEmpty(id);
        return new Evaluator.Id(id);
    }

    private Evaluator byClass() {
        String className = tq.consumeCssIdentifier();
        Validate.notEmpty(className);
        return new Evaluator.Class(className.trim());
    }

    private Evaluator byTag() {
        // todo - these aren't dealing perfectly with case sensitivity. For case sensitive parsers, we should also make
        // the tag in the selector case-sensitive (and also attribute names). But for now, normalize (lower-case) for
        // consistency - both the selector and the element tag
        String tagName = normalize(tq.consumeElementSelector());
        Validate.notEmpty(tagName);

        // namespaces:
        if (tagName.startsWith("*|")) { // namespaces: wildcard match equals(tagName) or ending in ":"+tagName
            String plainTag = tagName.substring(2); // strip *|
            return new CombiningEvaluator.Or(
                new Evaluator.Tag(plainTag),
                new Evaluator.TagEndsWith(":" + plainTag)
            );
        } else if (tagName.endsWith("|*")) { // ns|*
            String ns = tagName.substring(0, tagName.length() - 2) + ":"; // strip |*, to ns:
            return new Evaluator.TagStartsWith(ns);
        } else if (tagName.contains("|")) { // flip "abc|def" to "abc:def"
            tagName = tagName.replace("|", ":");
        }

        return new Evaluator.Tag(tagName);
    }

    private Evaluator byAttribute() {
        try (TokenQueue cq = new TokenQueue(tq.chompBalanced('[', ']'))) {
            return evaluatorForAttribute(cq);
        }
    }

    private Evaluator evaluatorForAttribute(TokenQueue cq) {
        cq.consumeWhitespace();
        String key = consumeAttributeKey(cq);
        key = normalize(key);
        Validate.notEmpty(key);
        Validate.isFalse(key.equals("abs:"), "Absolute attribute key must have a name");
        cq.consumeWhitespace();
        final Evaluator eval;

        if (cq.isEmpty()) {
            if (key.startsWith("^"))
                eval = new Evaluator.AttributeStarting(key.substring(1));
            else if (key.equals("*")) // any attribute
                eval = new Evaluator.AttributeStarting("");
            else
                eval = new Evaluator.Attribute(key);
        } else if (cq.matchChomp("~=")) { // regex match; the remainder of the queue (less surrounding whitespace) is the pattern
            eval = new Evaluator.AttributeWithValueMatching(key, Regex.compile(cq.remainder().trim()));
        } else {
            final String op;
            if (cq.matchChomp("!=")) op = "!=";
            else if (cq.matchChomp("^=")) op = "^=";
            else if (cq.matchChomp("$=")) op = "$=";
            else if (cq.matchChomp("*=")) op = "*=";
            else if (cq.matchChomp("=")) op = "=";
            else throw new Selector.SelectorParseException(
                "Could not parse attribute query '%s': unexpected token at '%s'", query, cq.remainder());

            cq.consumeWhitespace();
            final String rawValue = consumeAttributeValue(cq);
            final boolean quoted = !rawValue.isEmpty() && (rawValue.charAt(0) == '"' || rawValue.charAt(0) == '\'');
            // Quoted (CSS string) values keep their quotes and are decoded in the evaluator; bare values have their
            // CSS escapes decoded here, so escaped and unescaped forms of the same value match equally.
            final String value = quoted ? rawValue : TokenQueue.unescapeCss(rawValue);
            cq.consumeWhitespace();

            // Quoted (CSS string) values compare case-sensitively by default; bare values keep jsoup's historical
            // case-insensitive compare. An 'i' or 's' modifier after the value overrides the default.
            boolean caseSensitive = quoted;
            if (!cq.isEmpty()) {
                String modifier = cq.consumeCssIdentifier();
                if (modifier.equalsIgnoreCase("s")) caseSensitive = true;
                else if (modifier.equalsIgnoreCase("i")) caseSensitive = false;
                else throw new Selector.SelectorParseException(
                    "Could not parse attribute query '%s': unexpected token at '%s'", query, modifier + cq.remainder());
                cq.consumeWhitespace();
                if (!cq.isEmpty())
                    throw new Selector.SelectorParseException(
                        "Could not parse attribute query '%s': unexpected token at '%s'", query, cq.remainder());
            }

            switch (op) {
                case "=":
                    eval = new Evaluator.AttributeWithValue(key, value, caseSensitive);
                    break;
                case "!=":
                    eval = new Evaluator.AttributeWithValueNot(key, value, caseSensitive);
                    break;
                case "^=":
                    eval = new Evaluator.AttributeWithValueStarting(key, value, caseSensitive);
                    break;
                case "$=":
                    eval = new Evaluator.AttributeWithValueEnding(key, value, caseSensitive);
                    break;
                case "*=":
                    eval = new Evaluator.AttributeWithValueContaining(key, value, caseSensitive);
                    break;
                default:
                    throw new Selector.SelectorParseException(
                        "Could not parse attribute query '%s': unexpected token at '%s'", query, cq.remainder());
            }
        }
        return eval;
    }

    /**
     Consumes an attribute key off the queue, up to (but not including) whitespace or a comparison operator. CSS
     escapes in the key are decoded.
     */
    private String consumeAttributeKey(TokenQueue cq) {
        StringBuilder sb = StringUtil.borrowBuilder();
        OUT: while (!cq.isEmpty()) {
            if (cq.matchesWhitespace()) break;
            for (String eval : AttributeEvals) {
                if (cq.matches(eval)) break OUT;
            }
            if (cq.matches('\\')) cq.consumeCssEscapeSequence(sb);
            else sb.append(cq.consume());
        }
        return StringUtil.releaseBuilder(sb);
    }

    /**
     Consumes an attribute value off the queue: either a quoted string (returned with its quotes, for decoding in the
     evaluator), or a bare value running to the next whitespace or the end of the queue.
     */
    private String consumeAttributeValue(TokenQueue cq) {
        if (cq.isEmpty()) return "";

        if (cq.matches('"') || cq.matches('\'')) {
            StringBuilder sb = StringUtil.borrowBuilder();
            char quote = cq.consume();
            sb.append(quote);
            boolean closed = false;
            while (!cq.isEmpty()) {
                char c = cq.consume();
                sb.append(c);
                if (c == '\\' && !cq.isEmpty()) {
                    sb.append(cq.consume()); // keep the escaped character with its escape, for balanced decoding
                } else if (c == quote) {
                    closed = true;
                    break;
                }
            }
            if (!closed) {
                StringUtil.releaseBuilderVoid(sb);
                throw new Selector.SelectorParseException("Quoted value must have content");
            }
            return StringUtil.releaseBuilder(sb);
        }

        // bare value, up to the next whitespace or the end of the queue
        StringBuilder sb = StringUtil.borrowBuilder();
        while (!cq.isEmpty() && !cq.matchesWhitespace()) sb.append(cq.consume());
        return StringUtil.releaseBuilder(sb);
    }

    //pseudo selectors :first-child, :last-child, :nth-child, ...
    private static final Pattern NthStepOffset = Pattern.compile("(([+-])?(\\d+)?)n(\\s*([+-])?\\s*\\d+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NthOffset = Pattern.compile("([+-])?(\\d+)");

    private Evaluator cssNthChild(boolean last, boolean ofType) {
        String raw = consumeParens(); // arg is like "odd", or "-n+2", within nth-child(odd), optionally followed by " of S"
        final int ofIdx = indexOfOfClause(raw);
        final String arg = normalize(ofIdx >= 0 ? raw.substring(0, ofIdx) : raw);
        final boolean hasOf = ofIdx >= 0;

        if (hasOf && ofType)
            throw new Selector.SelectorParseException(
                "Could not parse query '%s': 'of S' is only supported by :nth-child and :nth-last-child", query);

        final int step, offset;
        if ("odd".equals(arg)) {
            step = 2;
            offset = 1;
        } else if ("even".equals(arg)) {
            step = 2;
            offset = 0;
        } else {
            Matcher stepOffsetM, stepM;
            if ((stepOffsetM = NthStepOffset.matcher(arg)).matches()) {
                if (stepOffsetM.group(3) != null) // has digits, like 3n+2 or -3n+2
                    step = Integer.parseInt(stepOffsetM.group(1).replaceFirst("^\\+", ""));
                else // no digits, might be like n+2, or -n+2. if group(2) == "-", it’s -1;
                    step = "-".equals(stepOffsetM.group(2)) ? -1 : 1;
                offset =
                    stepOffsetM.group(4) != null ? Integer.parseInt(stepOffsetM.group(4).replaceFirst("^\\+", "")) : 0;
            } else if ((stepM = NthOffset.matcher(arg)).matches()) {
                step = 0;
                offset = Integer.parseInt(stepM.group().replaceFirst("^\\+", ""));
            } else {
                throw new Selector.SelectorParseException("Could not parse nth-index '%s': unexpected format", arg);
            }
        }

        if (hasOf) {
            String subQuery = raw.substring(ofIdx + 2).trim(); // skip the "of" keyword
            Validate.notEmpty(subQuery, ":nth-child(An+B of S) selector S must not be empty");
            Evaluator selector = parse(subQuery); // a comma-separated complex selector list; validates the full grammar
            return new Evaluator.IsNthChildOf(step, offset, last, selector);
        }

        return ofType
            ? (last ? new Evaluator.IsNthLastOfType(step, offset) : new Evaluator.IsNthOfType(step, offset))
            : (last ? new Evaluator.IsNthLastChild(step, offset) : new Evaluator.IsNthChild(step, offset));
    }

    /**
     Finds the index of the {@code of} keyword separating An+B from the selector list in an
     {@code :nth-child(An+B of S)} argument. The keyword must be bounded on both sides by whitespace, and is not
     matched inside quoted strings (so attribute values containing " of " are preserved).
     @return the index of the 'o' in 'of', or -1 if there is no of clause
     */
    private static int indexOfOfClause(String arg) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean prevEscaped = false;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\' && i + 1 < arg.length()) {
                i++; // skip the escaped char
                prevEscaped = true;
                continue;
            }
            if (!inDouble && c == '\'')
                inSingle = !inSingle;
            else if (!inSingle && c == '"')
                inDouble = !inDouble;

            if (!inSingle && !inDouble && !prevEscaped
                && i > 0 && StringUtil.isWhitespace(arg.charAt(i - 1))
                && arg.regionMatches(true, i, "of", 0, 2)
                && i + 2 < arg.length() && StringUtil.isWhitespace(arg.charAt(i + 2))) {
                return i;
            }
            prevEscaped = false;
        }
        return -1;
    }

    private String consumeParens() {
        return tq.chompBalanced('(', ')');
    }

    private int consumeIndex() {
        String index = consumeParens().trim();
        Validate.isTrue(StringUtil.isNumeric(index), "Index must be numeric");
        return Integer.parseInt(index);
    }

    // pseudo selector :has(el)
    private Evaluator has() {
        return parseNested(StructuralEvaluator.Has::new, ":has() must have a selector");
    }

    // pseudo selector :is()
    private Evaluator is() {
        return parseNested(StructuralEvaluator.Is::new, ":is() must have a selector");
    }

    private Evaluator parseNested(Function<Evaluator, Evaluator> func, String err) {
        Validate.isTrue(tq.matchChomp('('), err);
        Evaluator eval = parseSelectorGroup();
        Validate.isTrue(tq.matchChomp(')'), err);
        return func.apply(eval);
    }

    // pseudo selector :contains(text), containsOwn(text)
    private Evaluator contains(boolean own) {
        String query = own ? ":containsOwn" : ":contains";
        String searchText = TokenQueue.unescape(consumeParens());
        Validate.notEmpty(searchText, query + "(text) query must not be empty");

        if (inNodeContext)
            return new NodeEvaluator.ContainsValue(searchText);

        return own
            ? new Evaluator.ContainsOwnText(searchText)
            : new Evaluator.ContainsText(searchText);
    }

    private Evaluator containsWholeText(boolean own) {
        String query = own ? ":containsWholeOwnText" : ":containsWholeText";
        String searchText = TokenQueue.unescape(consumeParens());
        Validate.notEmpty(searchText, query + "(text) query must not be empty");
        return own
            ? new Evaluator.ContainsWholeOwnText(searchText)
            : new Evaluator.ContainsWholeText(searchText);
    }

    // pseudo selector :containsData(data)
    private Evaluator containsData() {
        String searchText = TokenQueue.unescape(consumeParens());
        Validate.notEmpty(searchText, ":containsData(text) query must not be empty");
        return new Evaluator.ContainsData(searchText);
    }

    // :matches(regex), matchesOwn(regex)
    private Evaluator matches(boolean own) {
        String query = own ? ":matchesOwn" : ":matches";
        String regex = consumeParens(); // don't unescape, as regex bits will be escaped
        Validate.notEmpty(regex, query + "(regex) query must not be empty");
        Regex pattern = Regex.compile(regex);

        if (inNodeContext)
            return new NodeEvaluator.MatchesValue(pattern);

        return own
            ? new Evaluator.MatchesOwn(pattern)
            : new Evaluator.Matches(pattern);
    }

    // :matches(regex), matchesOwn(regex)
    private Evaluator matchesWholeText(boolean own) {
        String query = own ? ":matchesWholeOwnText" : ":matchesWholeText";
        String regex = consumeParens(); // don't unescape, as regex bits will be escaped
        Validate.notEmpty(regex, query + "(regex) query must not be empty");

        Regex pattern = Regex.compile(regex);
        return own
            ? new Evaluator.MatchesWholeOwnText(pattern)
            : new Evaluator.MatchesWholeText(pattern);
    }

    // :not(selector)
    private Evaluator not() {
        String subQuery = consumeParens();
        Validate.notEmpty(subQuery, ":not(selector) subselect must not be empty");

        return new StructuralEvaluator.Not(parse(subQuery));
    }

    // pseudo selector :lang(en), :lang("en-US")
    private Evaluator lang() {
        String arg = consumeParens().trim(); // whitespace just inside the parens is ignored
        Validate.notEmpty(arg, ":lang() must have a language range");

        final String range;
        final char first = arg.charAt(0);
        if (first == '\'' || first == '"') {
            // a quoted range must close at the very end of the argument, with nothing after it
            Validate.isTrue(arg.length() > 1 && arg.charAt(arg.length() - 1) == first,
                ":lang() has an unclosed quote or trailing content after the language range");
            range = arg.substring(1, arg.length() - 1);
        } else {
            range = arg;
        }

        Validate.notEmpty(range, ":lang() must have a language range");
        for (int i = 0; i < range.length(); i++) {
            char c = range.charAt(i);
            Validate.isFalse(c == ',' || StringUtil.isWhitespace(c),
                ":lang() language range must not contain whitespace or commas");
        }
        return new Evaluator.Lang(range);
    }

    @Override
    public String toString() {
        return query;
    }

    @Override
    public void close() {
        tq.close();
    }
}
