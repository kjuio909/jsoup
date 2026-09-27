package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.integration.TestServer;
import org.jsoup.integration.routes.CookieRoute;
import org.jsoup.select.Elements;
import org.jsoup.select.SelectorTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FormElement
 *
 * @author Jonathan Hedley
 */
public class FormElementTest {
    @BeforeAll
    public static void setUp() {
        TestServer.start();
    }

    @Test public void hasAssociatedControls() {
        //"button", "fieldset", "input", "keygen", "object", "output", "select", "textarea"
        String html = "<form id=1><button id=1><fieldset id=2 /><input id=3><keygen id=4><object id=5><output id=6>" +
                "<select id=7><option></select><textarea id=8><p id=9>";
        Document doc = Jsoup.parse(html);

        FormElement form = (FormElement) doc.select("form").first();
        assertEquals(8, form.elements().size());
    }

    @Test public void createsFormData() {
        String html = "<form><input name='one' value='two'><select name='three'><option value='not'>" +
                "<option value='four' selected><option value='five' selected><textarea name=six>seven</textarea>" +
                "<input name='seven' type='radio' value='on' checked><input name='seven' type='radio' value='off'>" +
                "<input name='eight' type='checkbox' checked><input name='nine' type='checkbox' value='unset'>" +
                "<input name='ten' value='text' disabled>" +
                "<input name='eleven' value='text' type='button'>" +
                "<input name='twelve' value='text' type='image'>" +
                "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.select("form").first();
        List<Connection.KeyVal> data = form.formData();

        assertEquals(5, data.size());
        assertEquals("one=two", data.get(0).toString());
        assertEquals("three=four", data.get(1).toString()); // single select: only first selected option
        assertEquals("six=seven", data.get(2).toString());
        assertEquals("seven=on", data.get(3).toString()); // set
        assertEquals("eight=on", data.get(4).toString()); // default
        // five should not appear, single select only submits one
        // nine should not appear, not checked checkbox
        // ten should not appear, disabled
        // eleven should not appear, button
    }

    @Test public void formDataUsesFirstAttribute() {
        String html = "<form><input name=test value=foo name=test2 value=bar>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        assertEquals("test=foo", form.formData().get(0).toString());
    }

    @Test public void createsSubmitableConnection() {
        String html = "<form action='/search'><input name='q'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        doc.select("[name=q]").attr("value", "jsoup");

        FormElement form = ((FormElement) doc.select("form").first());
        Connection con = form.submit();

        assertEquals(Connection.Method.GET, con.request().method());
        assertEquals("http://example.com/search", con.request().url().toExternalForm());
        List<Connection.KeyVal> dataList = (List<Connection.KeyVal>) con.request().data();
        assertEquals("q=jsoup", dataList.get(0).toString());

        doc.select("form").attr("method", "post");
        Connection con2 = form.submit();
        assertEquals(Connection.Method.POST, con2.request().method());
    }

    @Test public void actionWithNoValue() {
        String html = "<form><input name='q'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = ((FormElement) doc.select("form").first());
        Connection con = form.submit();

        assertEquals("http://example.com/", con.request().url().toExternalForm());
    }

    @Test public void actionWithNoBaseUri() {
        String html = "<form><input name='q'></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = ((FormElement) doc.select("form").first());


        boolean threw = false;
        try {
            form.submit();
        } catch (IllegalArgumentException e) {
            threw = true;
            assertEquals("Could not determine a form action URL for submit. Ensure you set a base URI when parsing.",
                    e.getMessage());
        }
        assertTrue(threw);
    }

    @Test public void formsAddedAfterParseAreFormElements() {
        Document doc = Jsoup.parse("<body />");
        doc.body().html("<form action='http://example.com/search'><input name='q' value='search'>");
        Element formEl = doc.select("form").first();
        assertTrue(formEl instanceof FormElement);

        FormElement form = (FormElement) formEl;
        assertEquals(1, form.elements().size());
    }

    @Test public void controlsAddedAfterParseAreLinkedWithForms() {
        Document doc = Jsoup.parse("<body />");
        doc.body().html("<form />");

        Element formEl = doc.select("form").first();
        formEl.append("<input name=foo value=bar>");

        assertTrue(formEl instanceof FormElement);
        FormElement form = (FormElement) formEl;
        assertEquals(1, form.elements().size());

        List<Connection.KeyVal> data = form.formData();
        assertEquals("foo=bar", data.get(0).toString());
    }

    @Test public void usesOnForCheckboxValueIfNoValueSet() {
        Document doc = Jsoup.parse("<form><input type=checkbox checked name=foo></form>");
        FormElement form = (FormElement) doc.select("form").first();
        List<Connection.KeyVal> data = form.formData();
        assertEquals("on", data.get(0).value());
        assertEquals("foo", data.get(0).key());
    }

    @Test public void adoptedFormsRetainInputs() {
        // test for https://github.com/jhy/jsoup/issues/249
        String html = "<html>\n" +
                "<body>  \n" +
                "  <table>\n" +
                "      <form action=\"/hello.php\" method=\"post\">\n" +
                "      <tr><td>User:</td><td> <input type=\"text\" name=\"user\" /></td></tr>\n" +
                "      <tr><td>Password:</td><td> <input type=\"password\" name=\"pass\" /></td></tr>\n" +
                "      <tr><td><input type=\"submit\" name=\"login\" value=\"login\" /></td></tr>\n" +
                "   </form>\n" +
                "  </table>\n" +
                "</body>\n" +
                "</html>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.select("form").first();
        List<Connection.KeyVal> data = form.formData();
        assertEquals(3, data.size());
        assertEquals("user", data.get(0).key());
        assertEquals("pass", data.get(1).key());
        assertEquals("login", data.get(2).key());
    }

    @Test public void removeFormElement() {
        String html = "<html>\n" +
                "  <body> \n" +
                "      <form action=\"/hello.php\" method=\"post\">\n" +
                "      User:<input type=\"text\" name=\"user\" />\n" +
                "      Password:<input type=\"password\" name=\"pass\" />\n" +
                "      <input type=\"submit\" name=\"login\" value=\"login\" />\n" +
                "   </form>\n" +
                "  </body>\n" +
                "</html>  ";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        Element pass = form.selectFirst("input[name=pass]");
        pass.remove();

        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("user", data.get(0).key());
        assertEquals("login", data.get(1).key());
        assertNull(doc.selectFirst("input[name=pass]"));
    }

    @Test public void formSubmissionCarriesCookiesFromSession() throws IOException {
        String echoUrl = TestServer.origin().echo.url();
        Document cookieDoc = Jsoup.connect(TestServer.origin().cookie.url())
            .data(CookieRoute.SetCookiesParam, "1")
            .get();
        Document formDoc = cookieDoc.connection().newRequest() // carries cookies from above set
            .url(TestServer.origin().file.url("/htmltests/upload-form.html"))
            .get();
        FormElement form = formDoc.select("form").forms().get(0);
        Document echo = form.submit().post();

        assertEquals(echoUrl, echo.location());
        Elements els = echo.select("th:contains(Cookie: One)");
        // ensure that the cookies are there and in path-specific order (two with same name)
        assertEquals("Echo", els.get(0).nextElementSibling().text());
        assertEquals("Root", els.get(1).nextElementSibling().text());

        // make sure that the session following kept unique requests
        assertTrue(cookieDoc.connection().response().url().toExternalForm().contains("Cookie"));
        assertTrue(formDoc.connection().response().url().toExternalForm().contains("upload-form"));
        assertTrue(echo.connection().response().url().toExternalForm().contains("Echo"));
    }

    @Test void formElementsAreLive() {
        final String html = "<html><body><form><div id=d1><input id=foo name=foo value=none></div><input id=bar name=bar value=one></form></body></html>";
        final Document doc = Jsoup.parse(html);
        doc.select("#d1").remove();
        final FormElement form = (FormElement) doc.selectFirst("form");
        form.appendElement("input").attr("id", "baz").attr("name", "baz").attr("value", "two");
        SelectorTest.assertSelectedIds(form.elements(), "bar", "baz");

        List<Connection.KeyVal> keyVals = form.formData();
        assertEquals("one", keyVals.get(0).value());
        assertEquals("two", keyVals.get(1).value());
    }

    private static List<String> dataStrings(List<Connection.KeyVal> data) {
        List<String> strings = new ArrayList<>();
        for (Connection.KeyVal kv : data) strings.add(kv.toString());
        return strings;
    }

    @Test void formDataIncludesExternalFormAttributeControlsInDocumentOrder() {
        String html =
            "<form id=fA><input name=a value=1></form>" +
            "<form id=fB><input name=b value=2></form>" +
            "<input name=c value=3 form=fA>" +
            "<input name=other value=x form=nope>" +   // invalid form id, ignored
            "<input value=noname form=fA>" +           // no name, ignored
            "<select name=s form=fA><option selected>A1<option>A2</select>";
        Document doc = Jsoup.parse(html);

        FormElement fA = (FormElement) doc.getElementById("fA");
        assertEquals(Arrays.asList("a=1", "c=3", "s=A1"), dataStrings(fA.formData()));

        // an external control after the form is included by that form
        Document doc2 = Jsoup.parse("<body>" +
            "<form id=fB><input name=b value=2></form>" +
            "<input name=ext value=9 form=fB></body>");
        assertEquals(Arrays.asList("b=2", "ext=9"),
            dataStrings(((FormElement) doc2.getElementById("fB")).formData()));
    }

    @Test void externalControlPointingAtOtherFormIsIgnored() {
        Document doc = Jsoup.parse("<form id=a><input name=inside value=1></form>" +
            "<form id=b></form><input name=x value=2 form=b>");
        FormElement a = (FormElement) doc.getElementById("a");
        FormElement b = (FormElement) doc.getElementById("b");
        assertEquals(Collections.singletonList("inside=1"), dataStrings(a.formData()));
        assertEquals(Collections.singletonList("x=2"), dataStrings(b.formData()));
    }

    @Test void formAttributeControlInsideAnotherFormIsOwnedByAttribute() {
        // a control nested in form B but with form="a" belongs to form a
        Document doc = Jsoup.parse("<form id=a></form>" +
            "<form id=b><input name=x value=2 form=a><input name=y value=3></form>");
        FormElement a = (FormElement) doc.getElementById("a");
        FormElement b = (FormElement) doc.getElementById("b");
        assertEquals(Collections.singletonList("x=2"), dataStrings(a.formData()));
        assertEquals(Collections.singletonList("y=3"), dataStrings(b.formData()));
    }

    @Test void sameControlReachedByTwoPathsIsIncludedOnce() {
        // fostered control is parser-linked and also carries a matching form attribute
        Document doc = Jsoup.parse("<table><tr><form id=g><input name=inside value=1></form>" +
            "<td><input name=out value=2 form=g></td></tr></table>");
        FormElement g = (FormElement) doc.getElementById("g");
        assertEquals(Arrays.asList("inside=1", "out=2"), dataStrings(g.formData()));
    }

    @Test void controlsOrderedByFinalDocumentPosition() {
        Document doc = Jsoup.parse("<body>" +
            "<form id=f></form>" +
            "<input name=z value=1 form=f>" +
            "<div><input name=deep value=2 form=f></div>" +
            "<input name=a value=3 form=f>" +
            "</body>");
        FormElement f = (FormElement) doc.getElementById("f");
        assertEquals(Arrays.asList("z=1", "deep=2", "a=3"), dataStrings(f.formData()));
    }

    @Test void sameNamedControlsAreAllPreservedInOrder() {
        Document doc = Jsoup.parse("<form id=f>" +
            "<input name=x value=1><input name=x value=2><input name=x value=3></form>");
        assertEquals(Arrays.asList("x=1", "x=2", "x=3"),
            dataStrings(((FormElement) doc.getElementById("f")).formData()));
    }

    @Test void emptyFormAttributeIsNoOwner() {
        Document doc = Jsoup.parse("<form id=f><input name=a value=1></form>" +
            "<input name=b value=2 form=''>");
        FormElement f = (FormElement) doc.getElementById("f");
        assertEquals(1, f.formData().size());
        assertEquals("a=1", f.formData().get(0).toString());
    }

    @Test void disabledFieldsetExcludesControlsButFirstLegendStaysActive() {
        Document doc = Jsoup.parse("<form id=f>" +
            "<fieldset disabled>" +
              "<legend><input name=leg value=1></legend>" +
              "<input name=body value=2>" +
              "<legend><input name=secondLeg value=3></legend>" +
              "<fieldset><input name=nested value=4></fieldset>" +
            "</fieldset>" +
            "<fieldset><input name=enabled value=5></fieldset>" +
            "</form>");
        assertEquals(Arrays.asList("leg=1", "enabled=5"),
            dataStrings(((FormElement) doc.getElementById("f")).formData()));
    }

    @Test void disabledFieldsetDoesNotCrossFormOwnership() {
        // external control owned by form a is not trapped by a disabled fieldset inside form b
        Document doc = Jsoup.parse("<form id=a></form>" +
            "<form id=b><fieldset disabled><input name=x value=1 form=a><input name=y value=2></fieldset></form>");
        FormElement a = (FormElement) doc.getElementById("a");
        FormElement b = (FormElement) doc.getElementById("b");
        assertEquals(Collections.singletonList("x=1"), dataStrings(a.formData()));
        assertTrue(b.formData().isEmpty());
    }

    @Test void singleSelectUsesFirstEnabledSelectedOrFirstEnabledOption() {
        Document doc = Jsoup.parse("<form id=f>" +
            "<select name=sel><option value=d disabled selected><option value=s2 selected><option value=s3 selected></select>" +
            "<select name=fb><option value=d2 disabled><option value=ok></select>" +
            "<select name=all><option disabled>a<option disabled>b</select>" +
            "</form>");
        assertEquals(Arrays.asList("sel=s2", "fb=ok"),
            dataStrings(((FormElement) doc.getElementById("f")).formData()));
    }

    @Test void multipleSelectSubmitsAllEnabledSelectedOptions() {
        Document doc = Jsoup.parse("<form id=f><select name=m multiple>" +
            "<option value=1 selected>" +
            "<option value=2 disabled selected>" +
            "<option value=3 selected>" +
            "<optgroup disabled><option value=4 selected></optgroup>" +
            "<option value=5>" +
            "</select><select name=none multiple></select></form>");
        assertEquals(Arrays.asList("m=1", "m=3"),
            dataStrings(((FormElement) doc.getElementById("f")).formData()));
    }

    @Test void optionWithoutValueSubmitsItsText() {
        Document doc = Jsoup.parse("<form id=f><select name=s><option selected>Hello</option>" +
            "<option value selected></select></form>");
        assertEquals("s=Hello", ((FormElement) doc.getElementById("f")).formData().get(0).toString());
    }

    @Test void disabledOptionAndOptgroupAreUnavailable() {
        Document doc = Jsoup.parse("<form id=f><select name=s multiple>" +
            "<option disabled selected value=1>" +
            "<optgroup disabled><option selected value=2></optgroup>" +
            "<option selected value=3>" +
            "</select></form>");
        assertEquals(Collections.singletonList("s=3"),
            dataStrings(((FormElement) doc.getElementById("f")).formData()));
    }

    @Test void radioAndCheckboxOnlySubmitWhenChecked() {
        Document doc = Jsoup.parse("<form id=f>" +
            "<input type=checkbox name=cb checked>" +
            "<input type=checkbox name=cboff>" +
            "<input type=radio name=r checked value=RV>" +
            "<input type=radio name=roff value=X>" +
            "</form>");
        assertEquals(Arrays.asList("cb=on", "r=RV"),
            dataStrings(((FormElement) doc.getElementById("f")).formData()));
    }

    @Test void textAndTextareaValuesDoNotRegress() {
        Document doc = Jsoup.parse("<form id=f><input name=t value=''><textarea name=ta></textarea>" +
            "<input type=hidden name=h value=''><textarea name=ta2>body text</textarea></form>");
        assertEquals(Arrays.asList("t=", "ta=", "h=", "ta2=body text"),
            dataStrings(((FormElement) doc.getElementById("f")).formData()));
    }

    @Test void formDataReflectsDeletionDetachAndReassignment() {
        Document doc = Jsoup.parse("<body><form id=n><input name=k1 value=v1></form>" +
            "<input name=k2 value=v2 form=n></body>");
        FormElement n = (FormElement) doc.getElementById("n");
        assertEquals(Arrays.asList("k1=v1", "k2=v2"), dataStrings(n.formData()));

        doc.selectFirst("input[name=k1]").remove();
        assertEquals(Collections.singletonList("k2=v2"), dataStrings(n.formData()));

        Element k2 = doc.selectFirst("input[name=k2]");
        k2.attr("form", "zzz"); // invalid id: control now has no owner
        assertTrue(n.formData().isEmpty());

        doc.body().append("<form id=p></form>");
        k2.attr("form", "p"); // reassigned to a new form
        assertTrue(n.formData().isEmpty());
        assertEquals(Collections.singletonList("k2=v2"),
            dataStrings(((FormElement) doc.getElementById("p")).formData()));

        k2.removeAttr("form"); // outside of form, no attribute: no owner
        assertTrue(n.formData().isEmpty());

        n.appendChild(k2); // moved back into form
        assertEquals(Collections.singletonList("k2=v2"), dataStrings(n.formData()));
    }

    @Test void returnedFormDataIsAnIndependentCopy() {
        Document doc = Jsoup.parse("<form id=f><input name=z value=1><input name=z value=2></form>");
        FormElement f = (FormElement) doc.getElementById("f");
        List<Connection.KeyVal> first = f.formData();
        first.clear();
        assertEquals(Arrays.asList("z=1", "z=2"), dataStrings(f.formData()));
    }

    @Test void emptyFormReturnsGenuinelyEmptyList() {
        Document doc = Jsoup.parse("<form id=e></form>");
        FormElement e = (FormElement) doc.getElementById("e");
        assertNotNull(e.formData());
        assertTrue(e.formData().isEmpty());
    }

    @Test void readingFormDataDoesNotMutateTheDocument() {
        String html = "<body><form id=f><input name=a value=1></form><input name=b value=2 form=f></body>";
        Document doc = Jsoup.parse(html);
        String before = doc.toString();
        FormElement f = (FormElement) doc.getElementById("f");
        f.formData();
        f.formData();
        assertEquals(before, doc.toString());
        assertEquals(2, doc.select("input").size());
        assertNotNull(doc.selectFirst("input[name=b]"));
    }

    @Test void malformedAndDeeplyNestedDisabledStructuresDoNotThrow() {
        String html = "<form id=f>" +
            "<fieldset disabled><fieldset disabled><legend>" +
            "<fieldset disabled><input name=x value=1></fieldset>" +
            "</legend></fieldset></fieldset>" +
            "<select name=s><option value=1 selected><option selected><optgroup><option selected></optgroup>" +
            "<div><option value=deep selected></div></select>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.getElementById("f")).formData();
        // x is trapped by the inner disabled fieldset even though inside the outer fieldset's legend
        assertEquals("s=1", data.get(0).toString());
        assertEquals(1, data.size());
    }
}
