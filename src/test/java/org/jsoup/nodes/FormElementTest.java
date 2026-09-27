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

        // a single (non-multiple) select only submits the first selected option, even if more than one is selected
        assertEquals(5, data.size());
        assertEquals("one=two", data.get(0).toString());
        assertEquals("three=four", data.get(1).toString());
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

    private static String dataString(FormElement form) {
        StringBuilder sb = new StringBuilder();
        for (Connection.KeyVal kv : form.formData()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(kv.key()).append('=').append(kv.value());
        }
        return sb.toString();
    }

    @Test void associatesExternalControlsByFormAttributeInDocumentOrder() {
        String html = "<form id=f><input name=in1 value=1><input name=in2 value=2></form>" +
            "<form id=other><input form=other name=o value=other></form>" +
            "<input form=f name=ext1 value=3>" +
            "<input form=missing name=bad value=ignored>" +   // unresolved id: ignored
            "<input form=other name=bad2 value=ignored2>" + // points at another form: ignored
            "<input form=f value=noname>" +                 // missing name: ignored
            "<input form=f name=ext2 value=4>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("#f");

        assertEquals("in1=1&in2=2&ext1=3&ext2=4", dataString(form));
        assertEquals("o=other&bad2=ignored2", dataString(doc.expectForm("#other")));
    }

    @Test void externalControlIsNotDuplicatedWhenAlsoDescendant() {
        String html = "<form id=f><input name=a value=1 form=f></form>";
        FormElement form = Jsoup.parse(html).expectForm("#f");
        assertEquals(1, form.elements().size());
        assertEquals("a=1", dataString(form));
    }

    @Test void resolvesFormAttributeToFirstFormWithId() {
        String html = "<form id=f><input name=a value=1></form><form id=f></form><input form=f name=x value=2>";
        Document doc = Jsoup.parse(html);
        java.util.List<FormElement> forms = doc.forms();
        assertEquals("a=1&x=2", dataString(forms.get(0))); // first form with id f wins
        assertEquals("", dataString(forms.get(1)));
    }

    @Test void formAttributeResolvesToFirstFormElementWithId() {
        // a non-form element sharing the id does not satisfy a form= reference; a later form does
        String html = "<div id=f></div><form id=f><input name=a value=1></form><input form=f name=x value=2>";
        Document doc = Jsoup.parse(html);
        assertEquals("a=1&x=2", dataString(doc.expectForm("#f")));
    }

    @Test void disabledFieldsetExcludesControlsExceptFirstLegendSubtree() {
        String html = "<form id=f>" +
            "<fieldset disabled>" +
              "<legend><input name=leg value=L><input type=checkbox name=legcb checked></legend>" +
              "<input name=a value=A>" +
              "<legend><input name=leg2 value=L2></legend>" +          // not the first legend
              "<fieldset><input name=b value=B></fieldset>" +          // nested fieldset not exempt
              "<legend><fieldset><input name=c value=C></fieldset></legend>" + // nested fieldset inside first legend still excluded
            "</fieldset>" +
            "<input name=d value=D>" +                                  // outside disabled fieldset
            "<input type=hidden name=h value=H>" +                      // hidden is never fieldset-disabled
            "</form>";
        FormElement form = Jsoup.parse(html).expectForm("#f");
        assertEquals("leg=L&legcb=on&d=D&h=H", dataString(form));
    }

    @Test void nestedDisabledFieldsetInsideLegendExcludesItsControls() {
        // the outer legend exempts the legend subtree of the outer fieldset; a disabled fieldset within it still applies
        String html = "<form id=f><fieldset disabled><legend>" +
            "<fieldset disabled><legend><input name=innerLeg value=I></legend><input name=inner value=X></fieldset>" +
            "<input name=outerLeg value=O></legend><input name=outer value=X></fieldset></form>";
        FormElement form = Jsoup.parse(html).expectForm("#f");
        assertEquals("innerLeg=I&outerLeg=O", dataString(form));
    }

    @Test void singleSelectSubmitsFirstEnabledSelectionOrFallsBackToFirstEnabledOption() {
        String html = "<form id=f>" +
            "<select name=s>" +                               // first selected, but disabled -> next enabled selection
              "<option value=d disabled selected>" +
              "<option value=a>" +
              "<option value=b selected>" +
              "<option value=c selected>" +
            "</select>" +
            "<select name=s2>" +                              // no selections -> first enabled option
              "<option value=x disabled><option value=y><option value=z>" +
            "</select>" +
            "<select name=s3>" +                              // only disabled options -> no entry
              "<option value=p disabled selected><option value=q disabled>" +
            "</select>" +
            "</form>";
        FormElement form = Jsoup.parse(html).expectForm("#f");
        assertEquals("s=b&s2=y", dataString(form));
    }

    @Test void multiSelectSubmitsAllEnabledSelectionsInOptionOrder() {
        String html = "<form id=f>" +
            "<select name=m multiple>" +
              "<option value=1 selected disabled>" +
              "<optgroup disabled><option value=2 selected></optgroup>" +
              "<optgroup><option value=3 selected><option value=4></optgroup>" +
              "<option value=5 selected>" +
            "</select>" +
            "<select name=n multiple><option value=1><option value=2 selected></select>" +
            "<select name=empty multiple><option value=1><option value=2></select>" +
            "</form>";
        FormElement form = Jsoup.parse(html).expectForm("#f");
        assertEquals("m=3&m=5&n=2", dataString(form));
    }

    @Test void disabledOptionAndOptgroupAreUnavailable() {
        String html = "<form id=f><select name=s>" +
            "<option value=a selected>" +
            "<optgroup disabled><option value=b selected></optgroup>" +
            "</select></form>";
        assertEquals("s=a", dataString(Jsoup.parse(html).expectForm("#f")));
    }

    @Test void hiddenInputIsSubmittedEvenWhenDisabled() {
        // per the HTML spec, the disabled attribute does not apply to an input in the hidden state
        String html = "<form id=f>" +
            "<input type=hidden name=h value=H disabled>" +
            "<input type=text name=t value=T disabled>" +
            "</form>";
        assertEquals("h=H", dataString(Jsoup.parse(html).expectForm("#f")));
    }

    @Test void optionWithoutValueAttributeSubmitsItsText() {
        String html = "<form id=f>" +
            "<select name=s><option selected>hello</option><option>world</option></select>" +
            "<select name=s2><option value=''>explicit-empty</option><option>fallback-text</option></select>" +
            "</form>";
        assertEquals("s=hello&s2=", dataString(Jsoup.parse(html).expectForm("#f")));
    }

    @Test void singleSelectFallbackOptionWithoutValueSubmitsItsText() {
        String html = "<form id=f><select name=s><option disabled selected>none</option>" +
            "<option>first enabled</option></select></form>";
        assertEquals("s=first enabled", dataString(Jsoup.parse(html).expectForm("#f")));
    }

    @Test void nestedSelectOptionsBelongToNearestSelectAfterEdit() {
        Document doc = Jsoup.parse("<form id=f><select name=s>" +
            "<option value=a selected><option value=b></select></form>");
        FormElement form = doc.expectForm("#f");
        assertEquals("s=a", dataString(form));

        Element inner = new Element("select").attr("name", "i");
        inner.appendElement("option").attr("value", "x").attr("selected", "selected");
        doc.selectFirst("select").appendChild(inner);
        assertEquals("s=a&i=x", dataString(form)); // the outer select must not swallow the nested option
    }

    @Test void checkboxesAndRadiosOnlySubmitWhenChecked() {
        String html = "<form id=f>" +
            "<input type=checkbox name=cb checked>" +        // default on
            "<input type=radio name=r checked>" +            // default on
            "<input type=checkbox name=cb2>" +               // unchecked, omitted
            "<input type=radio name=r2>" +                   // unchecked, omitted
            "<input type=checkbox name=cb3 checked value=V>" +
            "</form>";
        assertEquals("cb=on&r=on&cb3=V", dataString(Jsoup.parse(html).expectForm("#f")));
    }

    @Test void textTextareaAndEmptyValuesArePreserved() {
        String html = "<form id=f><input name=e value=''><textarea name=t></textarea>" +
            "<input type=password name=p value=sec><input type=hidden name=h value=hid></form>";
        assertEquals("e=&t=&p=sec&h=hid", dataString(Jsoup.parse(html).expectForm("#f")));
    }

    @Test void duplicateNamesAreAllKeptInOrder() {
        String html = "<form id=f><input name=x value=1><input name=x value=2>" +
            "<select name=y><option value=a selected><option value=b selected></select></form>";
        // single select contributes only its first selection; duplicate text inputs both remain
        assertEquals("x=1&x=2&y=a", dataString(Jsoup.parse(html).expectForm("#f")));
    }

    @Test void formDataReflectsRemovalDetachAndReassignment() {
        String html = "<form id=f><input name=a value=A><input name=b value=B></form><input form=f name=c value=C>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("#f");
        assertEquals("a=A&b=B&c=C", dataString(form));

        doc.expectForm("#f").selectFirst("[name=b]").remove();
        assertEquals("a=A&c=C", dataString(form));

        Element c = doc.selectFirst("[name=c]");
        c.remove(); // detached from document
        assertEquals("a=A", dataString(form));

        // adopted into a different form -> ownership moves
        doc.body().append("<form id=g></form>");
        FormElement g = doc.expectForm("#g");
        g.appendChild(c);
        c.removeAttr("form");
        assertEquals("a=A", dataString(form));
        assertEquals("c=C", dataString(g));

        // redirecting a descendant via form= attribute moves it to the target form
        FormElement h;
        doc.body().append("<form id=h></form>");
        h = doc.expectForm("#h");
        Element a = doc.selectFirst("[name=a]");
        a.attr("form", "h");
        assertEquals("", dataString(form));
        assertEquals("a=A", dataString(h));
    }

    @Test void returnedFormDataIsIndependentAcrossCalls() {
        FormElement form = Jsoup.parse("<form id=f><input name=a value=A></form>").expectForm("#f");
        List<Connection.KeyVal> first = form.formData();
        first.clear();
        first.add(org.jsoup.helper.HttpConnection.KeyVal.create("ghost", "x"));
        assertEquals("a=A", dataString(form)); // mutating one result does not affect later reads
    }

    @Test void emptyFormReturnsEmptyData() {
        FormElement form = Jsoup.parse("<form id=f></form>").expectForm("#f");
        assertTrue(form.formData().isEmpty());
    }

    @Test void malformedMarkupAndDuplicateSelectedAreStable() {
        String html = "<form id=f><select name=s>" +
            "<option value=a>a<option value=b selected>b<option value=c selected>c" +
            "<OPTGROUP DISABLED><option value=d selected>d</optgroup></select>" +
            "<input name=t value=ok></form>";
        FormElement form = Jsoup.parse(html).expectForm("#f");
        assertEquals("s=b&t=ok", dataString(form)); // first selected enabled; no exceptions, no phantom items
    }

    @Test void readingFormDataLeavesDocumentUntouched() {
        String html = "<form id=f><fieldset disabled><legend><input name=leg value=L></legend>" +
            "<input name=a value=A></fieldset><select name=s><option value=x selected></select></form>" +
            "<input form=f name=e value=E>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("#f");

        String before = doc.toString();
        String data1 = dataString(form);
        String data2 = dataString(form);
        assertEquals("leg=L&s=x&e=E", data1); // leg is in the exempt first legend; a is excluded
        assertEquals(data1, data2);
        assertEquals(before, doc.toString());            // markup/attributes/text unchanged
        assertEquals(3, form.formData().size());
        assertEquals(5, form.elements().size());        // fieldset, leg, a, s, e
        assertNotNull(doc.selectFirst("[name=e]"));     // document still queryable
    }

    @Test void clonedFormIsIndependent() {
        String html = "<form id=f><input name=a value=A></form><input form=f name=b value=B>";
        Document doc = Jsoup.parse(html);
        FormElement form = doc.expectForm("#f");
        FormElement clone = form.clone();
        // the clone is a standalone document: it keeps its descendant control but not the original's external one
        assertEquals("a=A", dataString(clone));
        // mutating the clone's result and DOM does not touch the original
        clone.formData().clear();
        assertEquals("a=A&b=B", dataString(form));
        assertEquals(2, form.elements().size());
    }
}
