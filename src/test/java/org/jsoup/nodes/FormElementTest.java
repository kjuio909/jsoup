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
        assertEquals("three=four", data.get(1).toString()); // single select submits only the first selected option
        assertEquals("six=seven", data.get(2).toString());
        assertEquals("seven=on", data.get(3).toString()); // set
        assertEquals("eight=on", data.get(4).toString()); // default
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

    @Test void formAttributeAssociatesOuterControls() {
        String html = "<input id=before name=before value=1 form=f>" +
            "<form id=f><input id=inner name=inner value=2></form>" +
            "<input id=after name=after value=3 form=f>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        SelectorTest.assertSelectedIds(form.elements(), "before", "inner", "after");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(3, data.size());
        assertEquals("before=1", data.get(0).toString());
        assertEquals("inner=2", data.get(1).toString());
        assertEquals("after=3", data.get(2).toString());
    }

    @Test void controlsPointingElsewhereAreIgnored() {
        String html = "<form id=f1><input id=a name=a value=1 form=f2><input id=b name=b value=2 form=nope><input id=c name=c value=3></form>" +
            "<form id=f2><input id=d name=d value=4 form=f1></form>" +
            "<input id=e name=e value=5 form=gone>";
        Document doc = Jsoup.parse(html);
        FormElement f1 = doc.expectForm("#f1");
        FormElement f2 = doc.expectForm("#f2");

        // a points to f2, b to a non-existent id, e points nowhere; d points back to f1
        SelectorTest.assertSelectedIds(f1.elements(), "c", "d");
        SelectorTest.assertSelectedIds(f2.elements(), "a");

        List<Connection.KeyVal> data = f1.formData();
        assertEquals(2, data.size());
        assertEquals("c=3", data.get(0).toString());
        assertEquals("d=4", data.get(1).toString());
    }

    @Test void controlsWithoutNameAreIgnored() {
        String html = "<form><input value=no-name><input name='' value=empty><input name=ok value=yes></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("ok=yes", data.get(0).toString());
    }

    @Test void disabledFieldsetExcludesControlsExceptFirstLegend() {
        String html = "<form>" +
            "<fieldset disabled>" +
            "  <legend><input name=kept value=1></legend>" +
            "  <input name=gone1 value=2>" +
            "  <legend><input name=gone2 value=3></legend>" +
            "  <div><input name=gone3 value=4></div>" +
            "</fieldset>" +
            "<fieldset disabled><input name=gone4 value=5></fieldset>" +
            "<fieldset><legend><input name=kept2 value=6></legend><input name=kept3 value=7></fieldset>" +
            "<input name=kept4 value=8>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(4, data.size());
        assertEquals("kept=1", data.get(0).toString());
        assertEquals("kept2=6", data.get(1).toString());
        assertEquals("kept3=7", data.get(2).toString());
        assertEquals("kept4=8", data.get(3).toString());
    }

    @Test void nestedDisabledFieldsets() {
        String html = "<form><fieldset disabled><fieldset><input name=gone value=1></fieldset>" +
            "<legend><fieldset><input name=kept value=2></fieldset></legend></fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("kept=2", data.get(0).toString());
    }

    @Test void disabledOptionsAndOptgroups() {
        String html = "<form>" +
            "<select name=single><option value=a disabled selected><option value=b></select>" +
            "<select name=group><optgroup disabled><option value=c selected></optgroup><option value=d></select>" +
            "<select name=multi multiple><option value=e selected disabled><option value=f selected>" +
            "<optgroup disabled><option value=g selected></optgroup><option value=h selected></select>" +
            "<select name=none multiple><option value=i disabled><optgroup disabled><option value=j selected></optgroup></select>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");
        List<Connection.KeyVal> data = form.formData();

        // single: selected option is disabled, falls back to first enabled
        // group: selected option is in a disabled optgroup, falls back
        // multi: keeps selected enabled options in order
        // none: no enabled selected option, produces nothing
        assertEquals(4, data.size());
        assertEquals("single=b", data.get(0).toString());
        assertEquals("group=d", data.get(1).toString());
        assertEquals("multi=f", data.get(2).toString());
        assertEquals("multi=h", data.get(3).toString());
    }

    @Test void singleSelectPicksFirstUsableSelected() {
        String html = "<form>" +
            "<select name=one><option value=a selected><option value=b selected></select>" +
            "<select name=two><option value=c><option value=d selected></select>" +
            "<select name=empty></select>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("one=a", data.get(0).toString());
        assertEquals("two=d", data.get(1).toString());
    }

    @Test void formDataIsIndependentSnapshot() {
        String html = "<form><input name=a value=1><input name=b value=2></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> first = form.formData();
        assertEquals(2, first.size());
        first.clear();

        List<Connection.KeyVal> second = form.formData();
        assertEquals(2, second.size());
        assertEquals("a=1", second.get(0).toString());
        assertEquals("b=2", second.get(1).toString());
    }

    @Test void removedAndReassignedControlsDoNotLeaveStaleValues() {
        String html = "<form id=f1><input name=a value=1><input name=b value=2></form><form id=f2></form>";
        Document doc = Jsoup.parse(html);
        FormElement f1 = doc.expectForm("#f1");
        FormElement f2 = doc.expectForm("#f2");

        assertEquals(2, f1.formData().size());

        // delete one, reassign the other to f2
        doc.selectFirst("input[name=a]").remove();
        doc.selectFirst("input[name=b]").attr("form", "f2");

        List<Connection.KeyVal> data1 = f1.formData();
        assertEquals(0, data1.size());

        List<Connection.KeyVal> data2 = f2.formData();
        assertEquals(1, data2.size());
        assertEquals("b=2", data2.get(0).toString());
    }

    @Test void emptyFormHasEmptyData() {
        Document doc = Jsoup.parse("<form></form>");
        FormElement form = doc.expectForm("form");
        List<Connection.KeyVal> data = form.formData();
        assertNotNull(data);
        assertTrue(data.isEmpty());
    }

    @Test void formDataDoesNotMutateDocument() {
        String html = "<form id=f><fieldset disabled><legend><input name=a value=1></legend></fieldset>" +
            "<select name=s><option value=x selected></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");
        String before = doc.html();

        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals(before, doc.html());
        assertSame(form, doc.expectForm("#f"));
    }
}
