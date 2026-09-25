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

    @Test void controlWithFormAttributeOutsideFormIsAssociated() {
        String html = "<form id='f'></form><input name='a' value='1' form='f'>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("#f");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("a=1", data.get(0).toString());
    }

    @Test void controlWithFormAttributeInsideAnotherFormIsNotFallback() {
        // form attr wins over ancestor: control belongs to the referenced form, not the one it is in
        String html = "<form id='a'><input name='x' value='1' form='b'></form>" +
            "<form id='b'><input name='y' value='2'></form>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.forms();

        assertEquals(0, forms.get(0).formData().size());
        List<Connection.KeyVal> dataB = forms.get(1).formData();
        assertEquals(2, dataB.size());
        assertEquals("x=1", dataB.get(0).toString());
        assertEquals("y=2", dataB.get(1).toString());
    }

    @Test void emptyOrUnknownFormAttributeDoesNotFallbackToAncestor() {
        String html = "<form id='f'>" +
            "<input name='empty' value='1' form=''>" +
            "<input name='nope' value='2' form='missing'>" +
            "<input name='ok' value='3'>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("#f");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("ok=3", data.get(0).toString());
    }

    @Test void duplicateFormIdsAssociatesToFirstForm() {
        String html = "<form id='dup'><input name='a' value='1'></form>" +
            "<form id='dup'><input name='b' value='2'></form>" +
            "<input name='c' value='3' form='dup'>";
        Document doc = Jsoup.parse(html);
        List<FormElement> forms = doc.forms();

        List<Connection.KeyVal> first = forms.get(0).formData();
        assertEquals(2, first.size());
        assertEquals("a=1", first.get(0).toString());
        assertEquals("c=3", first.get(1).toString());
        assertEquals(1, forms.get(1).formData().size());
    }

    @Test void associationReflectsPostParseChanges() {
        String html = "<form id='a'><input id='i' name='n' value='1'></form><form id='b'></form>";
        Document doc = Jsoup.parse(html);
        FormElement a = doc.expectForm("#a");
        FormElement b = doc.expectForm("#b");
        Element input = doc.getElementById("i");

        // add a form attribute: re-home from ancestor form a to form b
        input.attr("form", "b");
        assertEquals(0, a.formData().size());
        assertEquals("n=1", b.formData().get(0).toString());

        // remove it: back to the nearest ancestor (it is still inside form a's subtree)
        input.removeAttr("form");
        assertEquals("n=1", a.formData().get(0).toString());
        assertEquals(0, b.formData().size());

        // physically move the control outside both forms; explicit attr still resolves to form b
        doc.body().appendChild(input);
        assertEquals(0, a.formData().size()); // no longer a descendant, no parser-style retention here
        input.attr("form", "b");
        assertEquals("n=1", b.formData().get(0).toString());

        // the referenced form changes its id: the dangling reference resolves nowhere, no ancestor fallback
        b.attr("id", "c");
        assertEquals(0, a.formData().size());
        assertEquals(0, b.formData().size());
    }

    @Test void movedControlReassociatesToNearestAncestor() {
        String html = "<form id='a'><input id='i' name='n' value='1'></form><form id='b'></form>";
        Document doc = Jsoup.parse(html);
        FormElement a = doc.expectForm("#a");
        FormElement b = doc.expectForm("#b");
        Element input = doc.getElementById("i");

        b.appendChild(input); // moved into the other form, no form attribute present
        assertEquals(0, a.formData().size());
        assertEquals("n=1", b.formData().get(0).toString());
    }

    @Test void disabledFieldsetExcludesControlsExceptFirstLegend() {
        String html = "<form>" +
            "<fieldset disabled>" +
            "<legend><input name='inLegend' value='1'></legend>" +
            "<input name='inFieldset' value='2'>" +
            "<fieldset><input name='inNested' value='3'></fieldset>" +
            "<legend><input name='secondLegend' value='4'></legend>" +
            "</fieldset>" +
            "<input name='outside' value='5'>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");

        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("inLegend=1", data.get(0).toString());
        assertEquals("outside=5", data.get(1).toString());
    }

    @Test void enabledInnerFieldsetInsideDisabledOuterStillExcludes() {
        // any disabled fieldset in the ancestor chain bars the control
        String html = "<form><fieldset disabled><fieldset><input name='n' value='1'></fieldset></fieldset></form>";
        Document doc = Jsoup.parse(html);
        assertEquals(0, doc.expectForm("form").formData().size());
    }

    @Test void disabledFieldsetAfterEnableIncludesControls() {
        String html = "<form><fieldset disabled><input name='n' value='1'></fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("form");
        assertEquals(0, form.formData().size());

        form.selectFirst("fieldset").removeAttr("disabled");
        assertEquals("n=1", form.formData().get(0).toString());
    }

    @Test void disabledSelectIsEmpty() {
        String html = "<form><select name='s' disabled><option selected value='1'></select></form>";
        Document doc = Jsoup.parse(html);
        assertEquals(0, doc.expectForm("form").formData().size());
    }

    @Test void selectSkipsDisabledOptionsAndOptgroups() {
        String html = "<form><select name='s'>" +
            "<option value='disabled-sel' selected disabled>" +
            "<optgroup disabled><option value='og-disabled' selected></optgroup>" +
            "<optgroup><option value='og-ok' selected></optgroup>" +
            "<option value='ok' selected>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = doc.expectForm("form").formData();
        assertEquals(2, data.size());
        assertEquals("s=og-ok", data.get(0).toString());
        assertEquals("s=ok", data.get(1).toString());
    }

    @Test void selectNoSelectionUsesFirstAvailableOption() {
        String html = "<form><select name='s'>" +
            "<option value='first' disabled>" +
            "<optgroup disabled><option value='grouped'></optgroup>" +
            "<option value='fallback'>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = doc.expectForm("form").formData();
        assertEquals(1, data.size());
        assertEquals("s=fallback", data.get(0).toString());
    }

    @Test void selectMultipleWithNoSelectionIsEmpty() {
        String html = "<form><select name='s' multiple><option value='1'><option value='2'></select></form>";
        Document doc = Jsoup.parse(html);
        assertEquals(0, doc.expectForm("form").formData().size());
    }

    @Test void selectWithNoAvailableOptionsIsEmpty() {
        String html = "<form><select name='s'><option value='1' disabled></select></form>";
        Document doc = Jsoup.parse(html);
        assertEquals(0, doc.expectForm("form").formData().size());
    }

    @Test void optionWithoutValueUsesText() {
        String html = "<form><select name='s'><option selected>One &amp; Two</option></select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = doc.expectForm("form").formData();
        assertEquals("s=One & Two", data.get(0).toString());
    }

    @Test void formDataIsDocumentOrderedWithDuplicateNames() {
        String html = "<form id='f'><input name='n' value='1'><select name='n'><option value='2' selected>" +
            "<option value='3' selected></select></form><input name='n' value='4' form='f'>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = doc.expectForm("#f").formData();
        assertEquals(4, data.size());
        assertEquals("1", data.get(0).value());
        assertEquals("2", data.get(1).value());
        assertEquals("3", data.get(2).value());
        assertEquals("4", data.get(3).value()); // form-attr control outside the subtree, in document order
    }

    @Test void submitButtonIsIncluded() {
        String html = "<form><input type='submit' name='go' value='Submit'></form>";
        Document doc = Jsoup.parse(html);
        assertEquals("go=Submit", doc.expectForm("form").formData().get(0).toString());
    }

    @Test void fosteredControlKeepsAssociationUntilMoved() {
        // controls fostered out of a form in a table stay associated while untouched
        String html = "<table><form id='f'><tr><td><input name='user' value='u'></td></tr></form></table>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("#f");
        assertEquals("user=u", form.formData().get(0).toString());

        // moving the control drops the parse-time association; it has no ancestor form and no form attribute
        Element input = doc.selectFirst("input[name=user]");
        doc.body().appendChild(input);
        assertEquals(0, form.formData().size());

        // moving it into the form reassociates it via the ancestor rule
        form.appendChild(input);
        assertEquals("user=u", form.formData().get(0).toString());
    }

    @Test void fosteredControlWithFormAttributeFollowsAttribute() {
        String html = "<table><form id='f'><tr><td><input name='user' value='u'></td></tr></form></table>" +
            "<form id='g'></form>";
        Document doc = Jsoup.parse(html);
        FormElement f = doc.expectForm("#f");
        FormElement g = doc.expectForm("#g");
        assertEquals(1, f.formData().size());

        // an explicit form attribute takes over from the parse-time association
        Element input = doc.selectFirst("input[name=user]");
        input.attr("form", "g");
        assertEquals(0, f.formData().size());
        assertEquals("user=u", g.formData().get(0).toString());

        // removing it again restores the parse-time association, as the control was never moved
        input.removeAttr("form");
        assertEquals("user=u", f.formData().get(0).toString());
        assertEquals(0, g.formData().size());
    }

    @Test void formDataHandlesUnusualDocumentsWithoutThrowing() {
        // no controls at all
        assertEquals(0, Jsoup.parse("<form></form>").expectForm("form").formData().size());
        // only unnamed / disabled controls
        String html = "<form><input value='1'><input name='d' disabled>" +
            "<select name='s'></select></form>";
        assertEquals(0, Jsoup.parse(html).expectForm("form").formData().size());
        // form attribute pointing nowhere does not throw
        String orphan = "<form id='f'></form><input name='n' form='gone'>";
        assertEquals(0, Jsoup.parse(orphan).expectForm("#f").formData().size());
    }
}
