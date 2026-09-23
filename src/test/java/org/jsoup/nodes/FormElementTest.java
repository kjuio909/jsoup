package org.jsoup.nodes;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
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
                "<button name='thirteen' value='go'>go</button>" +
                "</form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.select("form").first();
        List<Connection.KeyVal> data = form.formData();

        assertEquals(5, data.size());
        assertEquals("one=two", data.get(0).toString());
        assertEquals("three=four", data.get(1).toString()); // single select: first enabled selected only
        assertEquals("six=seven", data.get(2).toString());
        assertEquals("seven=on", data.get(3).toString()); // set
        assertEquals("eight=on", data.get(4).toString()); // default
        // nine should not appear, not checked checkbox
        // ten should not appear, disabled
        // eleven should not appear, button
        // twelve should not appear, image
        // thirteen should not appear, <button> element
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

    @Test void disabledFieldsetControlsAreSkipped() {
        String html = "<form><fieldset disabled>" +
            "<input name='a' value='1'>" +
            "<legend><input name='b' value='2'></legend>" +
            "<input name='c' value='3'>" +
            "</fieldset><input name='d' value='4'></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("b=2", data.get(0).toString()); // first legend subtree is exempt
        assertEquals("d=4", data.get(1).toString()); // outside the fieldset
    }

    @Test void onlyFirstLegendExemptsDisabledFieldset() {
        String html = "<form><fieldset disabled>" +
            "<legend><input name='a' value='1'></legend>" +
            "<legend><input name='b' value='2'></legend>" +
            "<legend>nested<div><input name='c' value='3'></div></legend>" +
            "</fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(1, data.size());
        assertEquals("a=1", data.get(0).toString());
    }

    @Test void nestedDisabledFieldsetNotExemptByOuterLegend() {
        // the outer disabled fieldset's legend exempts its own subtree, but a nested disabled fieldset still disables
        // its non-legend controls — though its own first legend remains exempt
        String html = "<form><fieldset disabled><legend><fieldset disabled>" +
            "<input name='a' value='1'>" +
            "<legend><input name='b' value='2'></legend>" +
            "</fieldset><input name='c' value='3'></legend></fieldset></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(2, data.size());
        assertEquals("b=2", data.get(0).toString()); // inner fieldset's own first legend
        assertEquals("c=3", data.get(1).toString()); // outer fieldset's first legend
        // a is skipped: the outer legend does not re-enable the nested disabled fieldset
    }

    @Test void singleSelectUsesFirstEnabledSelectedOnly() {
        String html = "<form><select name='s'>" +
            "<option value='a' selected disabled>" +
            "<option value='b' selected>" +
            "<option value='c' selected>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();
        assertEquals(1, data.size());
        assertEquals("s=b", data.get(0).toString());
    }

    @Test void singleSelectWithAllSelectedDisabledHasNoData() {
        String html = "<form><select name='s'>" +
            "<option value='a' selected disabled>" +
            "<option value='b' selected disabled>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();
        assertTrue(data.isEmpty());
    }

    @Test void singleSelectDefaultsToFirstEnabledOption() {
        String html = "<form><select name='s'>" +
            "<option value='a' disabled>" +
            "<option value='b'>" +
            "<option value='c'>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();
        assertEquals(1, data.size());
        assertEquals("s=b", data.get(0).toString());
    }

    @Test void singleSelectWithOnlyDisabledOptionsHasNoData() {
        String html = "<form><select name='s'><option value='a' disabled><option value='b' disabled></select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();
        assertTrue(data.isEmpty());
    }

    @Test void multipleSelectSubmitsAllEnabledSelectedInOrder() {
        String html = "<form><select name='s' multiple>" +
            "<option value='a' selected>" +
            "<option value='b' selected disabled>" +
            "<optgroup label='g' disabled><option value='c' selected></optgroup>" +
            "<optgroup label='h'><option value='d' selected></optgroup>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();
        assertEquals(2, data.size());
        assertEquals("s=a", data.get(0).toString());
        assertEquals("s=d", data.get(1).toString());
    }

    @Test void multipleSelectWithNoEnabledSelectedHasNoData() {
        String html = "<form><select name='s' multiple>" +
            "<option value='a'>" +
            "<option value='b' selected disabled>" +
            "</select></form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();
        assertTrue(data.isEmpty());
    }

    @Test void buttonsAreNotSubmittedButSubmitAndResetInputsAre() {
        String html = "<form>" +
            "<button name='a' value='1'>go</button>" +
            "<button type='submit' name='b' value='2'>go</button>" +
            "<input type='button' name='c' value='3'>" +
            "<input type='image' name='d' value='4'>" +
            "<input type='submit' name='e' value='5'>" +
            "<input type='reset' name='f' value='6'>" +
            "</form>";
        Document doc = Jsoup.parse(html);
        List<Connection.KeyVal> data = ((FormElement) doc.selectFirst("form")).formData();
        assertEquals(2, data.size());
        assertEquals("e=5", data.get(0).toString());
        assertEquals("f=6", data.get(1).toString());
    }

    @Test void duplicateNamesAreKeptAsSeparateEntries() {
        String html = "<form><input name='x' value='1'><input name='x' value='2'>" +
            "<select name='y' multiple><option value='a' selected><option value='b' selected></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> data = form.formData();
        assertEquals(4, data.size());
        assertEquals("x=1", data.get(0).toString());
        assertEquals("x=2", data.get(1).toString());
        assertEquals("y=a", data.get(2).toString());
        assertEquals("y=b", data.get(3).toString());
    }

    @Test void formDataIsIndependentAndRecomputed() {
        Document doc = Jsoup.parse("<form><input name='a' value='1'></form>");
        FormElement form = (FormElement) doc.selectFirst("form");
        List<Connection.KeyVal> first = form.formData();
        first.clear();
        first.add(HttpConnection.KeyVal.create("z", "9"));

        List<Connection.KeyVal> second = form.formData();
        assertEquals(1, second.size());
        assertEquals("a=1", second.get(0).toString());

        // and DOM changes are reflected on the next call
        form.selectFirst("input").attr("value", "2");
        assertEquals("a=2", form.formData().get(0).toString());
    }

    // form="" attribute ownership: https://developer.mozilla.org/docs/Web/HTML/Element/input#form
    @Test void controlOutsideFormIsLinkedByFormAttribute() {
        String html = "<form id=f><input name=inside value=1></form>" +
            "<input name=outside value=2 form=f>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("#f");
        assertEquals(2, form.elements().size());
        List<Connection.KeyVal> data = form.formData();
        assertEquals("inside=1", data.get(0).toString());
        assertEquals("outside=2", data.get(1).toString()); // document order
    }

    @Test void formAttributeWorksForAllSubmittableTags() {
        String html = "<form id=f></form>" +
            "<input form=f><keygen form=f><object form=f></object>" +
            "<select form=f></select><textarea form=f></textarea>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("#f");
        assertEquals(5, form.elements().size());
    }

    @Test void formAttributeAcrossFormsBeatsAncestor() {
        String html = "<form id=f></form>" +
            "<form id=g><input name=x value=1 form=f><select name=y form=f>" +
            "<option value=a selected></select></form>";
        Document doc = Jsoup.parse(html);
        FormElement f = (FormElement) doc.selectFirst("#f");
        FormElement g = (FormElement) doc.selectFirst("#g");

        assertEquals(2, f.elements().size());
        assertEquals(2, f.formData().size());
        assertEquals("x=1", f.formData().get(0).toString());
        assertEquals("y=a", f.formData().get(1).toString());
        assertTrue(g.elements().isEmpty()); // claimed away by f's id
        assertTrue(g.formData().isEmpty());
    }

    @Test void formAttributeTakesPrecedenceOverAncestor() {
        String html = "<form id=f></form><form id=g><input name=x value=1 form=f></form>";
        Document doc = Jsoup.parse(html);
        FormElement f = (FormElement) doc.selectFirst("#f");
        FormElement g = (FormElement) doc.selectFirst("#g");
        assertEquals(1, f.elements().size());
        assertTrue(g.elements().isEmpty());
    }

    @Test void missingFormAttributeTargetMeansNoOwner() {
        String html = "<form id=f><input name=x value=1 form=nope></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("#f");
        assertTrue(form.elements().isEmpty());
        assertTrue(form.formData().isEmpty());
    }

    @Test void nonFormFormAttributeTargetMeansNoOwner() {
        String html = "<div id=f></div><form id=g><input name=x form=f></form>";
        Document doc = Jsoup.parse(html);
        FormElement g = (FormElement) doc.selectFirst("#g");
        assertTrue(g.elements().isEmpty()); // div is not a form; no ancestor fallback
    }

    @Test void emptyFormAttributeMeansNoOwner() {
        // a present but empty form attribute is an explicit, unresolved reference
        String html = "<form id=f><input name=x value=1 form=''></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("#f");
        assertTrue(form.elements().isEmpty());
    }

    @Test void noFormAttributeFallsBackToNearestAncestorForm() {
        // nested forms are rejected by the parser, so build them via the DOM to exercise nearest-ancestor precedence
        Document doc = Jsoup.parse("<form id=f><input name=outer value=1></form><form id=g></form>");
        FormElement f = (FormElement) doc.selectFirst("#f");
        FormElement g = (FormElement) doc.selectFirst("#g");
        f.appendChild(g);                 // g now nested inside f
        g.appendElement("input").attr("name", "inner").attr("value", "2");

        assertEquals(1, f.elements().size());  // outer input only
        assertEquals("outer=1", f.formData().get(0).toString());
        assertEquals(1, g.elements().size());  // nearest ancestor wins
        assertEquals("inner=2", g.formData().get(0).toString());
    }

    @Test void formAttributeMayReferenceLaterForm() {
        String html = "<input name=x value=1 form=f><form id=f></form>";
        Document doc = Jsoup.parse(html);
        FormElement form = (FormElement) doc.selectFirst("#f");
        assertEquals(1, form.elements().size());
        assertEquals("x=1", form.formData().get(0).toString());
    }

    @Test void duplicateFormIdsResolveToFirstForm() {
        String html = "<form id=f><input name=first value=1></form>" +
            "<form id=f><input name=second value=2></form>" +
            "<input name=third value=3 form=f>";
        Document doc = Jsoup.parse(html);
        Elements forms = doc.select("form");
        FormElement f1 = (FormElement) forms.get(0);
        FormElement f2 = (FormElement) forms.get(1);

        // the external control associates with the first form carrying the id
        assertEquals(2, f1.elements().size());
        assertEquals("first=1", f1.formData().get(0).toString());
        assertEquals("third=3", f1.formData().get(1).toString());
        assertEquals(1, f2.elements().size());
        assertEquals("second=2", f2.formData().get(0).toString());
    }

    @Test void ownershipRecomputedAfterMovingNode() {
        String html = "<form id=a><input name=x value=1></form><form id=b></form>";
        Document doc = Jsoup.parse(html);
        FormElement a = (FormElement) doc.selectFirst("#a");
        FormElement b = (FormElement) doc.selectFirst("#b");
        Element x = doc.selectFirst("input");

        assertEquals(1, a.elements().size());
        assertTrue(b.elements().isEmpty());

        b.appendChild(x); // move into the other form
        assertTrue(a.elements().isEmpty());
        assertEquals(1, b.elements().size());
        assertEquals("x=1", b.formData().get(0).toString());

        x.remove(); // detach entirely
        assertTrue(a.elements().isEmpty());
        assertTrue(b.elements().isEmpty());
    }

    @Test void ownershipRecomputedAfterFormAttributeAndIdChanges() {
        String html = "<form id=f></form><form id=g><input name=x value=1></form>";
        Document doc = Jsoup.parse(html);
        FormElement f = (FormElement) doc.selectFirst("#f");
        FormElement g = (FormElement) doc.selectFirst("#g");
        Element x = doc.selectFirst("input");

        assertTrue(f.elements().isEmpty());
        assertEquals(1, g.elements().size());

        x.attr("form", "f"); // point it at f
        assertEquals(1, f.elements().size());
        assertTrue(g.elements().isEmpty());

        f.id("h"); // target id no longer matches
        assertTrue(f.elements().isEmpty());
        assertTrue(g.elements().isEmpty());

        f.id("f"); // restored
        assertEquals(1, f.elements().size());

        x.removeAttr("form"); // back to nearest ancestor (g)
        assertTrue(f.elements().isEmpty());
        assertEquals(1, g.elements().size());
    }

    @Test void elementsAreDocumentOrderedAndDeduplicated() {
        String html = "<form id=f></form>" +
            "<input id=a form=f><div><input id=b form=f></div>" +
            "<form id=g><input id=c form=f></form><input id=d>";
        Document doc = Jsoup.parse(html);
        FormElement f = (FormElement) doc.selectFirst("#f");
        SelectorTest.assertSelectedIds(f.elements(), "a", "b", "c");
    }

    @Test void submitCarriesActionMethodAndFormData() {
        String html = "<form id=f action='/search' method='post'><input name=q></form>" +
            "<input name=extra value=x form=f>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("#f");
        Connection con = form.submit();

        assertEquals(Connection.Method.POST, con.request().method());
        assertEquals("http://example.com/search", con.request().url().toExternalForm());
        @SuppressWarnings("unchecked")
        List<Connection.KeyVal> requestData = (List<Connection.KeyVal>) con.request().data();
        List<Connection.KeyVal> formData = form.formData();
        assertEquals(formData.size(), requestData.size());
        assertEquals("q=", requestData.get(0).toString());
        assertEquals("extra=x", requestData.get(1).toString()); // outside-form control submitted too
    }

    @Test void submitWithUnresolvableAbsoluteActionThrows() {
        String html = "<form action='http://exa mple.com/x'><input name=q></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("form");
        assertThrows(IllegalArgumentException.class, form::submit);
    }
}
