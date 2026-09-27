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
        assertEquals("three=four", data.get(1).toString()); // a single select submits only its first selected option
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

    @Test void multiSelectSubmitsAllSelectedOptions() {
        String html = "<form>" +
            "<select name=multi multiple>" +
            "<option value=one selected><option value=two><option value=three selected><option value=four selected disabled>" +
            "</select>" +
            "<select name=unset multiple><option value=a><option value=b></select>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("multi=one", data.get(0).toString());
        assertEquals("multi=three", data.get(1).toString());
        // four is disabled, so not submitted; the unset multi select produces no entry
    }

    @Test void singleSelectSubmitsOneValue() {
        String html = "<form>" +
            "<select name=one><option value=a selected><option value=b selected></select>" +
            "<select name=two><option value=a><option value=b></select>" +
            "<select name=three><option value=a disabled><option value=b></select>" +
            "<select name=four><option value=a selected disabled><option value=b></select>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(4, data.size());
        assertEquals("one=a", data.get(0).toString()); // first selected option only
        assertEquals("two=a", data.get(1).toString()); // nothing selected, first option
        assertEquals("three=b", data.get(2).toString()); // first option disabled, first enabled option
        assertEquals("four=b", data.get(3).toString()); // selected option disabled, first enabled option
    }

    @Test void disabledOptionsAndOptgroupsAreNotSubmitted() {
        String html = "<form>" +
            "<select name=one>" +
            "<optgroup disabled><option value=a selected><option value=b></optgroup>" +
            "<option value=c disabled><option value=d>" +
            "</select>" +
            "<select name=two multiple>" +
            "<optgroup label=g><option value=a selected><option value=b selected disabled></optgroup>" +
            "</select>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(2, data.size());
        assertEquals("one=d", data.get(0).toString()); // selected and disabled options skipped, first enabled option
        assertEquals("two=a", data.get(1).toString()); // enabled optgroup, selected option
    }

    @Test void disabledFieldsetExceptFirstLegend() {
        String html = "<form>" +
            "<fieldset disabled>" +
            "<legend><input name=one value=1></legend>" +
            "<input name=two value=2>" +
            "<legend><input name=three value=3></legend>" +
            "<div><input name=four value=4></div>" +
            "</fieldset>" +
            "<fieldset><legend><input name=five value=5></legend><input name=six value=6></fieldset>" +
            "<input name=seven value=7>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();

        assertEquals(4, data.size());
        assertEquals("one=1", data.get(0).toString()); // in the first legend of a disabled fieldset
        // two, three, four are disabled by the fieldset; the second legend gets no exception
        assertEquals("five=5", data.get(1).toString());
        assertEquals("six=6", data.get(2).toString());
        assertEquals("seven=7", data.get(3).toString());
    }

    @Test void formAttributeAssociatesControlsInDocumentOrder() {
        String html = "<input name=zero value=0 form=f1>" +
            "<form id=f1><input name=one value=1></form>" +
            "<div><input name=two value=2 form=f1></div>" +
            "<input name=three value=3 form=nosuch>" +
            "<input name=four value=4 form=f2>" +
            "<form id=f2><input name=five value=5></form>";
        Document doc = Jsoup.parse(html);

        FormElement f1 = (FormElement) doc.getElementById("f1");
        List<Connection.KeyVal> data = f1.formData();
        assertEquals(3, data.size());
        assertEquals("zero=0", data.get(0).toString()); // associated via form attribute, before the form in the document
        assertEquals("one=1", data.get(1).toString());
        assertEquals("two=2", data.get(2).toString());
        // three points to a non-existent form, four to another form; neither is included

        FormElement f2 = (FormElement) doc.getElementById("f2");
        List<Connection.KeyVal> data2 = f2.formData();
        assertEquals(2, data2.size());
        assertEquals("four=4", data2.get(0).toString());
        assertEquals("five=5", data2.get(1).toString());
    }

    @Test void formAttributeReassignsDescendantControl() {
        String html = "<form id=a><input name=one value=1 form=b><input name=two value=2></form><form id=b></form>";
        Document doc = Jsoup.parse(html);

        FormElement a = (FormElement) doc.getElementById("a");
        List<Connection.KeyVal> dataA = a.formData();
        assertEquals(1, dataA.size());
        assertEquals("two=2", dataA.get(0).toString());

        FormElement b = (FormElement) doc.getElementById("b");
        List<Connection.KeyVal> dataB = b.formData();
        assertEquals(1, dataB.size());
        assertEquals("one=1", dataB.get(0).toString());
    }

    @Test void removedOrReassignedControlsAreNotRetained() {
        String html = "<form id=a><input name=one value=1><input name=two value=2></form><form id=b></form>";
        Document doc = Jsoup.parse(html);
        FormElement a = (FormElement) doc.getElementById("a");
        FormElement b = (FormElement) doc.getElementById("b");
        assertEquals(2, a.formData().size());

        // removing a control drops it from the next read
        doc.selectFirst("input[name=one]").remove();
        List<Connection.KeyVal> data = a.formData();
        assertEquals(1, data.size());
        assertEquals("two=2", data.get(0).toString());

        // moving a control into another form re-assigns it
        Element two = doc.selectFirst("input[name=two]");
        b.appendChild(two);
        assertTrue(a.formData().isEmpty());
        List<Connection.KeyVal> dataB = b.formData();
        assertEquals(1, dataB.size());
        assertEquals("two=2", dataB.get(0).toString());

        // re-pointing a control via the form attribute re-assigns it
        two.attr("form", "a");
        assertTrue(b.formData().isEmpty());
        assertEquals("two=2", a.formData().get(0).toString());
    }

    @Test void formDataIsAnIndependentSnapshot() {
        String html = "<form><input name=one value=1></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> first = form.formData();
        assertEquals(1, first.size());
        first.clear(); // mutating a result must not impact subsequent reads

        List<Connection.KeyVal> second = form.formData();
        assertEquals(1, second.size());
        assertEquals("one=1", second.get(0).toString());
        assertNotSame(first, second);
    }

    @Test void emptyFormDataIsEmptyAndRepeatable() {
        Document doc = Jsoup.parse("<form><input value=no-name><button name=btn></button></form>");
        FormElement form = (FormElement) doc.selectFirst("form");
        assertTrue(form.formData().isEmpty());
        assertTrue(form.formData().isEmpty());
    }

    @Test void formDataDoesNotMutateDocument() {
        String html = "<table><form action='/hello.php' method='post'>" +
            "<tr><td>User:</td><td><input type='text' name='user'></td></tr>" +
            "<tr><td>Pass:</td><td><input type='password' name='pass'></td></tr>" +
            "</form></table>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        String before = doc.html();

        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("user", data.get(0).key());
        assertEquals("pass", data.get(1).key());

        assertEquals(before, doc.html()); // extraction leaves the document unchanged
        assertNotNull(doc.selectFirst("input[name=user]")); // and remains selectable
        assertEquals(data.toString(), form.formData().toString()); // and re-extractable
    }

    @Test void parserMovedAndFormAttributeControlsMergeInDocumentOrder() {
        String html = "<input name=zero value=0 form=f>" +
            "<table><form id=f><tr><td><input name=one value=1></td></tr></form></table>" +
            "<input name=two value=2 form=f>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.getElementById("f");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(3, data.size());
        assertEquals("zero=0", data.get(0).toString()); // associated via form attribute
        assertEquals("one=1", data.get(1).toString()); // linked during parse, foster-parented into the table
        assertEquals("two=2", data.get(2).toString()); // associated via form attribute
        assertEquals(3, form.elements().size()); // each control appears only once
        assertEquals(data.toString(), form.formData().toString()); // repeated reads are stable
    }
}
