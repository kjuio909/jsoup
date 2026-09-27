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
        assertEquals("three=four", data.get(1).toString()); // single select submits one value: the first selected option
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

    @Test void formAttributeControlsAreAssociatedInDocumentOrder() {
        String html = "<input form=f1 name=ext1 value=a>" +
            "<form id=f1><input name=in1 value=b></form>" +
            "<div><input form=f1 name=ext2 value=c></div>" +
            "<input form=other name=ext3 value=d>" +
            "<input form=nope name=ext4 value=e>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(3, data.size());
        assertEquals("ext1=a", data.get(0).toString());
        assertEquals("in1=b", data.get(1).toString());
        assertEquals("ext2=c", data.get(2).toString());
    }

    @Test void formAttributeOverridesAncestor() {
        String html = "<form id=a><input name=one value=1 form=b><input name=two value=2></form><form id=b></form>";
        Document doc = Jsoup.parse(html);
        FormElement a = doc.expectForm("#a");
        FormElement b = doc.expectForm("#b");

        List<Connection.KeyVal> dataA = a.formData();
        assertEquals(1, dataA.size());
        assertEquals("two=2", dataA.get(0).toString());

        List<Connection.KeyVal> dataB = b.formData();
        assertEquals(1, dataB.size());
        assertEquals("one=1", dataB.get(0).toString());
    }

    @Test void reassignedControlsAreNotStale() {
        String html = "<form id=a><input name=one value=1></form><form id=b></form>";
        Document doc = Jsoup.parse(html);
        FormElement a = doc.expectForm("#a");
        FormElement b = doc.expectForm("#b");
        assertEquals(1, a.formData().size());

        Element input = doc.selectFirst("input");
        input.attr("form", "b"); // reassign to the other form
        assertEquals(0, a.formData().size());
        List<Connection.KeyVal> dataB = b.formData();
        assertEquals(1, dataB.size());
        assertEquals("one=1", dataB.get(0).toString());

        input.remove(); // detached controls leave no stale values
        assertEquals(0, b.formData().size());
    }

    @Test void parserMovedControlsAppearOnceInDocumentOrder() {
        // inputs foster-parented out of a table remain associated, appear once, and merge in document order
        String html = "<form><input name=zero value=0><table><input name=one value=1><tr><td><input name=two value=2></td></tr></table><input name=three value=3></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(4, data.size());
        assertEquals("zero=0", data.get(0).toString());
        assertEquals("one=1", data.get(1).toString());
        assertEquals("two=2", data.get(2).toString());
        assertEquals("three=3", data.get(3).toString());

        assertEquals(data.toString(), form.formData().toString()); // repeatable, no duplication on re-read
    }

    @Test void disabledFieldsetDisablesExceptFirstLegend() {
        String html = "<form><fieldset disabled>" +
            "<input name=a value=1>" +
            "<legend><input name=b value=2></legend>" +
            "<legend><input name=c value=3></legend>" +
            "<span><input name=d value=4></span>" +
            "</fieldset>" +
            "<input name=e value=5></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("b=2", data.get(0).toString());
        assertEquals("e=5", data.get(1).toString());
    }

    @Test void nestedDisabledFieldsets() {
        String html = "<form><fieldset disabled><legend><fieldset disabled><input name=a value=1></fieldset></legend></fieldset>" +
            "<fieldset><input name=b value=2></fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("b=2", data.get(0).toString());
    }

    @Test void disabledOptionsAndOptgroups() {
        String html = "<form>" +
            "<select name=single><option value=1 disabled selected><option value=2><option value=3></select>" +
            "<select name=multi multiple><option value=1 selected disabled><option value=2 selected>" +
            "<optgroup disabled><option value=3 selected></optgroup><option value=4 selected></select>" +
            "<select name=none multiple><option value=1 disabled><optgroup disabled><option value=2></optgroup></select>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(3, data.size());
        assertEquals("single=2", data.get(0).toString()); // selected is disabled, falls back to first enabled
        assertEquals("multi=2", data.get(1).toString());
        assertEquals("multi=4", data.get(2).toString());
        // none produces no values, so no entry for that name
    }

    @Test void multiSelectKeepsAllSelectedValues() {
        String html = "<form><select name=s multiple><option value=1 selected><option value=2><option value=3 selected></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("s=1", data.get(0).toString());
        assertEquals("s=3", data.get(1).toString());
    }

    @Test void sameNameControlsKeepAllValues() {
        String html = "<form><input name=dup value=1><input name=dup value=2><input name=dup value=3></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(3, data.size());
        assertEquals("dup=1", data.get(0).toString());
        assertEquals("dup=2", data.get(1).toString());
        assertEquals("dup=3", data.get(2).toString());
    }

    @Test void formDataIsAnIndependentSnapshot() {
        Document doc = Jsoup.parse("<form><input name=a value=1></form>");
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> first = form.formData();
        assertEquals(1, first.size());
        first.clear();
        first.add(org.jsoup.helper.HttpConnection.KeyVal.create("junk", "junk"));

        List<Connection.KeyVal> second = form.formData();
        assertEquals(1, second.size());
        assertEquals("a=1", second.get(0).toString());
    }

    @Test void emptyFormHasEmptyFormData() {
        Document doc = Jsoup.parse("<form></form>");
        FormElement form = doc.expectForm("form");
        List<Connection.KeyVal> data = form.formData();
        assertNotNull(data);
        assertTrue(data.isEmpty());
        assertTrue(form.formData().isEmpty()); // repeatable
    }

    @Test void formDataDoesNotModifyDocument() {
        String html = "<form id=f><input name=a value=1><select name=s><option value=o1 selected><option value=o2></select></form><input form=f name=b value=2>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");
        String before = doc.html();

        List<Connection.KeyVal> data = form.formData();
        assertEquals(3, data.size());
        assertEquals(before, doc.html()); // document is unchanged by the extraction
        assertEquals(data.toString(), form.formData().toString()); // and can be re-extracted
    }
}
