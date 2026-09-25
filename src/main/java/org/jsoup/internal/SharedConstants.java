package org.jsoup.internal;

/**
 jsoup constants used between packages. Do not use as they may change without warning. Users will not be able to see
 this package when modules are enabled.
 */
public final class SharedConstants {
    public static final String UserDataKey = "/jsoup.userdata";
    /** @deprecated Internal source ranges now use {@link #RangeSpansKey}. */
    @Deprecated public final static String AttrRangeKey = "jsoup.attrs";
    /** @deprecated Internal source ranges now use {@link #RangeSpansKey}. */
    @Deprecated public static final String RangeKey = "jsoup.start";
    /** @deprecated Internal source ranges now use {@link #RangeSpansKey}. */
    @Deprecated public static final String EndRangeKey = "jsoup.end";
    public static final String RangeSpansKey = "/jsoup.spans";
    public static final String XmlnsAttr = "jsoup.xmlns-";

    /** Internal user-data key: on a control that the parser associated with a form but inserted outside of any form
     * subtree (e.g. foster-parented out of a table), the parent element it was inserted under. The fallback
     * association holds only while the control keeps that parent, so moving it after parsing drops the stale link and
     * lets the current tree and the {@code form} attribute rule. */
    public static final String FormOrphanParentKey = "/jsoup.formOrphanParent";

    public static final int DefaultBufferSize = 8 * 1024;

    public static final String[] FormSubmitTags = {
        "input", "keygen", "object", "select", "textarea"
    };

    public static final String DummyUri = "https://dummy.example/"; // used as a base URI if none provided, to allow abs url resolution to preserve relative links

    public static final String UseHttpClient = "jsoup.useHttpClient";

    public static final String UseRe2j = "jsoup.useRe2j"; // enables use of the re2j regular expression engine when true and it's on the classpath

    private SharedConstants() {}
}
