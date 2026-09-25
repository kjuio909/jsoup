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

        assertEquals(6, data.size());
        assertEquals("one=two", data.get(0).toString());
        assertEquals("three=four", data.get(1).toString());
        assertEquals("three=five", data.get(2).toString());
        assertEquals("six=seven", data.get(3).toString());
        assertEquals("seven=on", data.get(4).toString()); // set
        assertEquals("eight=on", data.get(5).toString()); // default
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

    @Test void controlWithFormAttributeIsOwnedById() {
        // a control with a form attribute belongs to the form with that ID, even outside its subtree
        String html = "<input id=a name=a value=1 form=f><form id=f><input id=b name=b value=2></form><input id=c name=c value=3 form=f>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(3, data.size());
        assertEquals("a=1", data.get(0).toString()); // document order, not descendant-then-linked order
        assertEquals("b=2", data.get(1).toString());
        assertEquals("c=3", data.get(2).toString());
    }

    @Test void controlWithMissingOrEmptyFormIdHasNoOwner() {
        // an explicit form attribute pointing to a missing or empty ID does not fall back to an ancestor form
        String html = "<form><input name=a value=1 form=missing><input name=b value=2 form><input name=c value=3 form=''></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        assertTrue(form.formData().isEmpty());
    }

    @Test void controlWithDuplicateFormIdIsOwnedByFirstForm() {
        String html = "<form id=f><input name=a value=1></form><form id=f></form><input name=b value=2 form=f>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.select("form").forms();

        List<Connection.KeyVal> first = forms.get(0).formData();
        assertEquals(2, first.size());
        assertEquals("a=1", first.get(0).toString());
        assertEquals("b=2", first.get(1).toString());
        assertTrue(forms.get(1).formData().isEmpty());
    }

    @Test void ownershipReflectsDomChanges() {
        String html = "<form id=f1><input name=a value=1></form><form id=f2></form>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.select("form").forms();
        FormElement f1 = forms.get(0);
        FormElement f2 = forms.get(1);
        Element input = doc.selectFirst("input");

        // moving a control to another form re-associates it
        f2.appendChild(input);
        assertTrue(f1.formData().isEmpty());
        assertEquals("a=1", f2.formData().get(0).toString());

        // moving a control out of any form disassociates it
        doc.body().appendChild(input);
        assertTrue(f2.formData().isEmpty());

        // adding a form attribute associates by ID
        input.attr("form", "f1");
        assertEquals("a=1", f1.formData().get(0).toString());

        // changing the form's ID drops the association
        f1.attr("id", "f1-renamed");
        assertTrue(f1.formData().isEmpty());

        // removing the form attribute does not fall back to an ancestor form
        f2.appendChild(input);
        input.removeAttr("form");
        assertEquals("a=1", f2.formData().get(0).toString());
    }

    @Test void disabledFieldsetDisablesControls() {
        String html = "<form>" +
            "<fieldset disabled><input name=a value=1><legend><input name=b value=2></legend></fieldset>" +
            "<fieldset><input name=c value=3></fieldset>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("b=2", data.get(0).toString()); // within the fieldset's first legend, so not disabled
        assertEquals("c=3", data.get(1).toString());
    }

    @Test void nestedDisabledFieldsetDisablesControls() {
        String html = "<form><fieldset disabled><legend><input name=a value=1></legend>" +
            "<fieldset><input name=b value=2></fieldset></fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("a=1", data.get(0).toString()); // in the outer fieldset's first legend
        // b is excluded: the outer disabled fieldset applies, and the inner fieldset is not within the legend
    }

    @Test void selectExcludesDisabledOptions() {
        String html = "<form><select name=s>" +
            "<option value=a selected>" +
            "<option value=b selected disabled>" +
            "<optgroup disabled><option value=c selected></optgroup>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("s=a", data.get(0).toString());
    }

    @Test void selectDefaultsToFirstAvailableOption() {
        String html = "<form><select name=s><option value=a disabled><option value=b><option value=c></select>" +
            "<select name=m multiple><option value=x><option value=y></select>" +
            "<select name=d disabled><option value=z></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("s=b", data.get(0).toString()); // first available (non-disabled) option
        // m is multiple with nothing selected, so submits no values; d is disabled
    }

    @Test void optionWithoutValueUsesText() {
        String html = "<form><select name=s><option>One</option><option value=two selected></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        assertEquals("s=two", form.formData().get(0).toString());

        doc.selectFirst("option").attr("selected", "");
        List<Connection.KeyVal> data = form.formData();
        assertEquals("s=One", data.get(0).toString()); // no value attribute, uses its text
        assertEquals("s=two", data.get(1).toString());
    }
}
