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

    @Test public void formDataWithSubmitSubmitter() {
        String html = "<form><input name='q' value='jsoup'>"
            + "<input type='submit' name='go' value='Go'>"
            + "<input type='submit' name='other' value='Other'>"
            + "<input type='submit' value='NoName'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("form");
        Element go = doc.selectFirst("input[name=go]");

        List<Connection.KeyVal> data = form.formData(go, 0, 0);
        assertEquals(2, data.size());
        assertEquals("q=jsoup", data.get(0).toString());
        assertEquals("go=Go", data.get(1).toString());
        // the other named submit input is not counted; only the submitter is

        Element noName = doc.select("input[type=submit]").get(2);
        List<Connection.KeyVal> data2 = form.formData(noName, 0, 0);
        assertEquals(1, data2.size()); // an unnamed submitter contributes nothing
        assertEquals("q=jsoup", data2.get(0).toString());
    }

    @Test public void formDataWithButtonSubmitter() {
        String html = "<form><button name='b' value='1'>Go</button><button>Plain</button>"
            + "<input name='q' value='v'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("form");

        Element named = doc.selectFirst("button[name=b]");
        List<Connection.KeyVal> data = form.formData(named, 0, 0);
        assertEquals(2, data.size());
        assertEquals("b=1", data.get(0).toString()); // contributes at its document position
        assertEquals("q=v", data.get(1).toString());

        Element plain = doc.select("button").get(1);
        List<Connection.KeyVal> data2 = form.formData(plain, 0, 0);
        assertEquals(1, data2.size()); // an unnamed button contributes nothing
        assertEquals("q=v", data2.get(0).toString());
    }

    @Test public void formDataWithImageSubmitter() {
        String html = "<form><input name='q' value='v'>"
            + "<input type='image' name='pin' src='a.png'>"
            + "<input type='image' src='b.png'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("form");

        Element pin = doc.selectFirst("input[name=pin]");
        List<Connection.KeyVal> data = form.formData(pin, 10, 20);
        assertEquals(3, data.size());
        assertEquals("q=v", data.get(0).toString());
        assertEquals("pin.x=10", data.get(1).toString());
        assertEquals("pin.y=20", data.get(2).toString());

        Element unnamed = doc.select("input[type=image]").get(1);
        List<Connection.KeyVal> data2 = form.formData(unnamed, 3, 4);
        assertEquals(3, data2.size());
        assertEquals("x=3", data2.get(1).toString());
        assertEquals("y=4", data2.get(2).toString());
    }

    @Test public void formDataSubmitterValidation() {
        String html = "<form id=f1><input name='q' value='v'><input type='submit' name='s' value='S'></form>"
            + "<form id=f2><input type='submit' name='s2' value='S2'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form1 = (FormElement) doc.selectFirst("#f1");
        String before = doc.html();

        assertThrows(IllegalArgumentException.class, () -> form1.formData(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> form1.formData(doc.selectFirst("input[name=q]"), 0, 0)); // not a submit control
        assertThrows(IllegalArgumentException.class, () -> form1.formData(doc.selectFirst("#f2 input"), 0, 0)); // not associated with f1
        assertEquals(before, doc.html()); // failures do not modify the DOM
    }

    @Test public void submitWithSubmitter() {
        String html = "<form action='/search' method='post'><input name='q' value='jsoup'>"
            + "<input type='image' name='pin'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("form");
        Element pin = doc.selectFirst("input[type=image]");

        Connection con = form.submit(pin, 5, 7);
        assertEquals(Connection.Method.POST, con.request().method());
        assertEquals("http://example.com/search", con.request().url().toExternalForm());

        List<Connection.KeyVal> expected = form.formData(pin, 5, 7);
        List<Connection.KeyVal> actual = (List<Connection.KeyVal>) con.request().data();
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).key(), actual.get(i).key());
            assertEquals(expected.get(i).value(), actual.get(i).value());
        }
    }

    @Test public void submitWithSubmitterDefaultsToGet() {
        String html = "<form action='/search'><input type='submit' name='go' value='Go'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("form");
        Connection con = form.submit(doc.selectFirst("input[type=submit]"), 0, 0);
        assertEquals(Connection.Method.GET, con.request().method());
        assertEquals("http://example.com/search", con.request().url().toExternalForm());
    }

    @Test public void submitWithSubmitterNoBaseUri() {
        String html = "<form><input type='submit' name='s' value='S'></form>";
        Document doc = Jsoup.parse(html, "");
        FormElement form = (FormElement) doc.selectFirst("form");
        Element s = doc.selectFirst("input[type=submit]");
        assertThrows(IllegalArgumentException.class, () -> form.submit(s, 0, 0));
    }

    @Test public void detachedFormDataWithSubmitter() {
        String html = "<form id=f><input name='in' value='1'><input name='out' value='2' form='f'>"
            + "<input type='submit' name='s' value='S'></form>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("form");
        Element s = doc.selectFirst("input[type=submit]");
        form.remove(); // detach; without a Document the form= attribute cannot resolve

        List<Connection.KeyVal> data = form.formData(s, 0, 0);
        assertEquals(2, data.size());
        assertEquals("in=1", data.get(0).toString());
        assertEquals("s=S", data.get(1).toString());
        // 'out' is unassociated: its form= attribute is not resolved on a detached form
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

    @Test void formAttributeAssociatesExternalControl() {
        String html = "<form id=f1><input name=a></form><input name=b form=f1>";
        Document doc = Jsoup.parse(html);
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertNames(f1.elements(), "a", "b");
    }

    @Test void formAttributeWorksForAllListedTags() {
        String html = "<form id=f1></form>" +
            "<input name=i form=f1><keygen name=k form=f1><object name=o form=f1></object>" +
            "<select name=s form=f1><option value=1></select><textarea name=t form=f1>x</textarea>";
        Document doc = Jsoup.parse(html);
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertNames(f1.elements(), "i", "k", "o", "s", "t");
    }

    @Test void formAttributeOverridesAncestorForm() {
        // control sits inside f2 but names f1; the explicit owner wins over the ancestor form
        String html = "<form id=f1><input name=outer></form>" +
            "<form id=f2><input name=inner form=f1></form>";
        Document doc = Jsoup.parse(html);
        FormElement f1 = (FormElement) doc.getElementById("f1");
        FormElement f2 = (FormElement) doc.getElementById("f2");
        assertNames(f1.elements(), "outer", "inner");
        assertTrue(f2.elements().isEmpty());
    }

    @Test void danglingFormAttributeIsUnowned() {
        // a form= that names a missing element, or a non-form element, means no owner at all — no ancestor fallback
        Document doc = Jsoup.parse("<form id=f1><input name=a></form>" +
            "<input name=b form=nope><div id=d1></div><input name=c form=d1>");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertNames(f1.elements(), "a");
    }

    @Test void emptyFormAttributeIsUnowned() {
        // form="" is an explicit (but unresolvable) owner: the control is unowned, not adopted by an ancestor
        Document doc = Jsoup.parse("<form id=f1><input name=a form=''></form>");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertTrue(f1.elements().isEmpty());
    }

    @Test void formAttributeWorksForButtonFieldsetAndOutput() {
        // button, fieldset and output resolve a form= owner just like the other listed controls;
        // the input inside the external fieldset is not adopted (fieldets are not forms)
        String html = "<form id=f1><input name=a></form>" +
            "<button type='submit' name='go' value='Go' form=f1>Go</button>" +
            "<fieldset name='fs' form=f1><input name='inside'></fieldset>" +
            "<output name='o' for='a' form=f1>x</output>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertNames(f1.elements(), "a", "go", "fs", "o");
    }

    @Test void externalButtonCanSubmit() {
        // a button outside the form, associated via form=, is a usable submitter
        String html = "<form id=f1 action='/search' method='post'><input name=q value=v></form>" +
            "<button type='submit' name='go' value='Go' form=f1>Go</button>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        Element go = doc.selectFirst("button[name=go]");
        assertTrue(f1.elements().contains(go));

        List<Connection.KeyVal> data = f1.formData(go, 0, 0);
        assertEquals(2, data.size());
        assertEquals("q=v", data.get(0).toString());
        assertEquals("go=Go", data.get(1).toString()); // appears at the button's document position (after q)

        Connection con = f1.submit(go, 0, 0);
        assertEquals(Connection.Method.POST, con.request().method());
        assertEquals("http://example.com/search", con.request().url().toExternalForm());
    }

    @Test void fieldsetAndOutputCarryNoFormData() {
        // they are listed (present in elements()) but submit no entries
        String html = "<form id=f1></form>" +
            "<fieldset name='fs' form=f1></fieldset><output name='o' form=f1>x</output>";
        Document doc = Jsoup.parse(html, "http://example.com/");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertEquals(2, f1.elements().size());
        assertTrue(f1.formData().isEmpty());
    }

    @Test void formAttributeOnButtonFieldsetOutputOverridesAncestor() {
        // forms cannot nest via the parser, so build the nested structure through the DOM
        Document doc = Jsoup.parse("<form id=f1></form><form id=f2></form>");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        FormElement f2 = (FormElement) doc.getElementById("f2");
        Element button = new Element("button").attr("name", "b").attr("form", "f1");
        Element fieldset = new Element("fieldset").attr("name", "fs").attr("form", "f1");
        Element output = new Element("output").attr("name", "o").attr("form", "f1");
        f2.appendChild(button);
        f2.appendChild(fieldset);
        f2.appendChild(output);

        assertNames(f1.elements(), "b", "fs", "o");
        assertTrue(f2.elements().isEmpty());
    }

    @Test void danglingOrSelfFormAttributeOnButtonFieldsetOutputIsUnowned() {
        Document doc = Jsoup.parse("<form id=f1></form>" +
            "<button id=btn name=b form=nope></button>" +      // missing target
            "<fieldset name=fs form=''></fieldset>" +           // empty target
            "<output id=out name=o form=out>x</output>" +       // target is the control itself
            "<button name=self form=btn>x</button>");           // targets a non-form button
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertTrue(f1.elements().isEmpty());
    }

    @Test void detachedFormIgnoresFormAttributeOnAnyControl() {
        // a detached form (no Document) collects form-less descendants only; any form= attribute is unresolvable
        Document doc = Jsoup.parse("<form id=f><input name=in value=1 form=other>" +
            "<button name=go value=Go form=other></button>" +
            "<input name=ok value=2></form>");
        FormElement f = (FormElement) doc.getElementById("f");
        f.remove();

        assertNames(f.elements(), "ok");
        List<Connection.KeyVal> data = f.formData();
        assertEquals(1, data.size());
        assertEquals("ok=2", data.get(0).toString());
    }

    @Test void controlWithoutFormAttributeUsesNearestAncestorForm() {
        // forms cannot nest via the HTML parser, so build a nested structure through the DOM:
        //   <form id=outer><div><form id=inner></form><input name=x></div></form>
        Document doc = Jsoup.parse("<form id=outer></form><form id=inner></form>");
        FormElement outer = (FormElement) doc.getElementById("outer");
        FormElement inner = (FormElement) doc.getElementById("inner");
        Element div = outer.appendElement("div");
        div.appendChild(inner);
        div.appendElement("input").attr("name", "x"); // sibling of inner, within outer

        assertNames(outer.elements(), "x");
        assertTrue(inner.elements().isEmpty());
    }

    @Test void ownershipRecomputedAfterMove() {
        Document doc = Jsoup.parse("<form id=f1><input name=a></form><form id=f2></form>");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        FormElement f2 = (FormElement) doc.getElementById("f2");
        Element a = doc.selectFirst("input[name=a]");
        assertNames(f1.elements(), "a");
        assertTrue(f2.elements().isEmpty());

        f2.appendChild(a);
        assertTrue(f1.elements().isEmpty());
        assertNames(f2.elements(), "a");
    }

    @Test void ownershipRecomputedAfterAttributeChanges() {
        Document doc = Jsoup.parse("<form id=f1></form><input name=b>");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        Element b = doc.selectFirst("input[name=b]");
        assertTrue(f1.elements().isEmpty());

        b.attr("form", "f1");
        assertNames(f1.elements(), "b");

        f1.attr("id", "renamed");
        assertTrue(f1.elements().isEmpty());

        b.attr("form", "renamed");
        assertNames(f1.elements(), "b");
    }

    @Test void elementsAreInDocumentOrderAndDeduplicated() {
        Document doc = Jsoup.parse("<input name=z form=f1><form id=f1><input name=a></form><input name=m form=f1>");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertNames(f1.elements(), "z", "a", "m");
        f1.addElement(doc.selectFirst("input[name=a]")); // duplicate parse link must not duplicate
        assertEquals(3, f1.elements().size());
    }

    @Test void adoptedParserLinkRetainedUntilExplicitOwnerOrAncestor() {
        // table recovery hoists the form out, so the inputs are not its descendants, but the parser linked them
        Document doc = Jsoup.parse("<table><form id=f1 action='/x'>" +
            "<tr><td><input name=u></td></tr></form></table>");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        assertNames(f1.elements(), "u");
        assertEquals("u", f1.formData().get(0).key());

        // giving that adopted control an explicit owner elsewhere moves it off f1
        Document d2 = Jsoup.parse("<table><form id=f1><tr><td><input name=u form=f2></td></tr></form></table><form id=f2></form>");
        FormElement g1 = (FormElement) d2.getElementById("f1");
        FormElement g2 = (FormElement) d2.getElementById("f2");
        assertTrue(g1.elements().isEmpty());
        assertNames(g2.elements(), "u");
    }

    @Test void externalControlsAreSubmittedWithFiltering() {
        // disabled, fieldset/legend, checkbox and ordering rules apply equally to form=-owned external controls
        String html = "<form id=f1></form>" +
            "<input name=off form=f1 disabled>" +
            "<input name=chk form=f1 type=checkbox>" +
            "<fieldset disabled form=f1><input name=a form=f1><legend><input name=b form=f1></legend></fieldset>" +
            "<input name=ok form=f1 value=1>";
        Document doc = Jsoup.parse(html);
        FormElement f1 = (FormElement) doc.getElementById("f1");
        List<Connection.KeyVal> data = f1.formData();
        assertEquals(2, data.size());
        assertEquals("b=", data.get(0).toString()); // external fieldset's first legend is exempt
        assertEquals("ok=1", data.get(1).toString());
    }

    @Test void relativeActionWithoutBaseUriThrows() {
        Document doc = Jsoup.parse("<form action='/search'><input name=q></form>");
        FormElement form = (FormElement) doc.selectFirst("form");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, form::submit);
        assertTrue(e.getMessage().contains("Could not resolve the form's absolute action URL"));
    }

    @Test void submitCarriesFormDataAndMethod() {
        Document doc = Jsoup.parse("<form action='/search' method=post><input name=q></form>", "http://example.com/");
        FormElement form = (FormElement) doc.selectFirst("form");
        doc.selectFirst("input").attr("value", "jsoup");
        Connection con = form.submit();

        assertEquals(Connection.Method.POST, con.request().method());
        assertEquals("http://example.com/search", con.request().url().toExternalForm());
        @SuppressWarnings("unchecked")
        List<Connection.KeyVal> requestData = (List<Connection.KeyVal>) con.request().data();
        assertEquals(form.formData().size(), requestData.size());
        assertEquals("q=jsoup", requestData.get(0).toString());
    }

    @Test void detachedFormKeepsDescendantAndLinkedControls() {
        // a form removed from its document still enumerates its descendants and controls linked to it
        Document doc = Jsoup.parse("<form id=f1><input name=a></form><input name=b>");
        FormElement f1 = (FormElement) doc.getElementById("f1");
        Element b = doc.selectFirst("input[name=b]");
        f1.remove();
        f1.addElement(b); // linked but still in the original document — must not be counted against a detached form

        assertNames(f1.elements(), "a");
        assertEquals("a=", f1.formData().get(0).toString());
    }

    private static void assertNames(Elements els, String... names) {
        assertEquals(names.length, els.size());
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], els.get(i).attr("name"),
                "element " + i + " expected name " + names[i] + " but was " + els.get(i).attr("name"));
        }
    }
}
