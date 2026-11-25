package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRulesTest {
    @Test fun urls() {
        assertEquals("https://example.com", BrowserRules.normalizeUrl("https://example.com"))
        assertEquals("http://127.0.0.1:8080/x", BrowserRules.normalizeUrl("http://127.0.0.1:8080/x"))
        assertEquals("https://example.com/a?b=1", BrowserRules.normalizeUrl("example.com/a?b=1"))
        assertEquals("https://shop.example.co.uk:8443/p", BrowserRules.normalizeUrl("shop.example.co.uk:8443/p"))
        assertEquals("https://news.example.com/a", BrowserRules.normalizeUrl("http://news.example.com/a"))
        assertEquals("http://localhost:8000/t", BrowserRules.normalizeUrl("localhost:8000/t"))
        // anything that could reach into the phone, or isn't an address
        for (bad in listOf("javascript:alert(1)", "file:///sdcard/x", "intent://scan#Intent;end", "content://x", "data:text/html,hi", "tel:123", "", "hello world", "https://", "notadomain"))
            assertNull(bad, BrowserRules.normalizeUrl(bad))
    }

    @Test fun fileNames() {
        assertEquals("report.pdf", BrowserRules.safeFileName("https://x.com/files/report.pdf?token=1#p"))
        assertEquals("my file.pdf", BrowserRules.safeFileName("my%20file.pdf"))
        assertEquals("passwd", BrowserRules.safeFileName("../../etc/passwd"))
        assertEquals("download", BrowserRules.safeFileName(""))
        assertEquals("download", BrowserRules.safeFileName("..."))
        assertEquals("a_b_c.txt", BrowserRules.safeFileName("a:b*c.txt"))
        assertTrue(BrowserRules.safeFileName("x".repeat(300) + ".pdf").let { it.length <= 100 && it.endsWith(".pdf") })
        assertEquals("q1.csv", BrowserRules.nameFromDisposition("attachment; filename=\"q1.csv\""))
        assertEquals("résumé.pdf", BrowserRules.nameFromDisposition("attachment; filename*=UTF-8''résumé.pdf"))
        assertNull(BrowserRules.nameFromDisposition("inline"))
    }

    private fun page(): JSONObject = JSONObject()
        .put("title", "Shop").put("url", "https://shop.example.com/cart").put("more", 3)
        .put("text", "Your cart\n\n\n\nTotal £20")
        .put("elements", JSONArray()
            .put(JSONObject().put("n", 1).put("kind", "link").put("label", "Home").put("href", "/"))
            .put(JSONObject().put("n", 2).put("kind", "field").put("label", "Search").put("value", ""))
            .put(JSONObject().put("n", 3).put("kind", "button").put("label", "Place order").put("inForm", true))
            .put(JSONObject().put("n", 4).put("kind", "checkbox").put("label", "Gift wrap").put("checked", true))
            .put(JSONObject().put("n", 5).put("kind", "button").put("label", "Add  to\n cart").put("disabled", true)))

    @Test fun pageText() {
        val t = BrowserRules.pageText(page(), 1000)
        assertTrue(t, t.startsWith("Page: Shop\nURL: https://shop.example.com/cart"))
        assertTrue(t, t.contains("[1] link “Home” → /"))
        assertTrue(t, t.contains("[2] field “Search” (empty)"))
        assertTrue(t, t.contains("[4] checkbox “Gift wrap” (on)"))
        assertTrue(t, t.contains("[5] button “Add to cart” (disabled)"))
        assertTrue(t, t.contains("(and 3 more further down"))
        assertTrue(t, t.contains("Your cart\n\nTotal £20"))
        assertFalse(t.contains("Note:"))
        val cut = BrowserRules.pageText(page().put("text", "x".repeat(50)), 10)
        assertTrue(cut, cut.contains("(… cut"))
        val signIn = BrowserRules.pageText(page().put("password", true).put("captcha", true), 100)
        assertTrue(signIn.contains("password field") && signIn.contains("CAPTCHA"))
    }

    @Test fun picking() {
        val els = BrowserRules.parseElements(page().getJSONArray("elements"))
        assertEquals(3, BrowserRules.pick("3", els)?.n)
        assertEquals(3, BrowserRules.pick("[3]", els)?.n)
        assertEquals(3, BrowserRules.pick("place order", els)?.n)
        assertEquals(5, BrowserRules.pick("add to", els)?.n)
        assertNull(BrowserRules.pick("9", els))
        assertNull(BrowserRules.pick("checkout", els))
    }

    @Test fun approvals() {
        val none = JSONObject()
        assertNotNull(BrowserRules.approval("browser_click", none, "Place order", true, "shop.com"))
        assertNotNull(BrowserRules.approval("browser_click", none, "Sign up", false, "x.com"))
        assertNotNull(BrowserRules.approval("browser_click", none, "I agree to the terms", false, ""))
        assertNotNull(BrowserRules.approval("browser_click", none, "Pay £20", false, ""))
        assertNull(BrowserRules.approval("browser_click", none, "Next page", false, ""))
        assertNull(BrowserRules.approval("browser_click", none, null, false, ""))
        assertNull(BrowserRules.approval("browser_type", JSONObject().put("text", "shoes"), "Search", true, ""))
        assertNotNull(BrowserRules.approval("browser_type", JSONObject().put("text", "hi").put("submit", true), "Message", true, ""))
        assertNotNull(BrowserRules.approval("browser_press", JSONObject().put("key", "Enter"), "Comment", true, ""))
        assertNull(BrowserRules.approval("browser_press", JSONObject().put("key", "Enter"), null, false, ""))
        assertNull(BrowserRules.approval("browser_press", JSONObject().put("key", "Tab"), null, true, ""))
        // "Return" submits the form just like "Enter" does
        assertNotNull(BrowserRules.approval("browser_press", JSONObject().put("key", "Return"), "Comment", true, ""))
        assertNotNull(BrowserRules.approval("browser_press", JSONObject().put("key", " return "), null, true, ""))
        assertNull(BrowserRules.approval("browser_open", JSONObject().put("url", "https://x.com"), null, false, ""))
        assertEquals("Click “Place order” on shop.com", BrowserRules.approval("browser_click", none, "Place order", true, "shop.com"))
    }

    @Test fun hosts() {
        assertEquals("amazon.com", BrowserRules.host("https://www.amazon.com/dp/1"))
        assertEquals("", BrowserRules.host("not a url"))
    }

    // ---- money: "Always allow" must never cover a payment page or a buy/pay/order action

    @Test fun paymentPagesAreSpotted() {
        val S = BrowserRules::Signals
        assertTrue(BrowserRules.paymentPage(BrowserRules.Signals(url = "https://shop.example/checkout/step-2")))
        assertTrue(BrowserRules.paymentPage(BrowserRules.Signals(url = "https://shop.example/payment?id=4")))
        assertTrue(BrowserRules.paymentPage(BrowserRules.Signals(url = "https://shop.example/cart/pay")))
        assertTrue(BrowserRules.paymentPage(BrowserRules.Signals(url = "https://news.example/a", payFields = true)))
        assertFalse(BrowserRules.paymentPage(BrowserRules.Signals(url = "https://news.example/paying-for-college")))
        assertFalse(BrowserRules.paymentPage(BrowserRules.Signals(url = "https://payments.example/")))   // only the path counts, not the host
        assertFalse(BrowserRules.paymentPage(S("https://shop.example/search?q=shoes", false, "", "", false)))
    }

    @Test fun aPageThatCantBeCheckedCountsAsMoney() {
        // the page script failed (an input id with a backslash broke a selector) or didn't answer: money can't be ruled out
        val unread = BrowserRules.Signals(url = "https://shop.example/basket", unread = true)
        assertTrue(BrowserRules.paymentPage(unread))
        assertTrue(BrowserRules.moneyRisk("browser_click", unread))
        val what = BrowserRules.approval("browser_click", JSONObject().put("target", "3"), "Continue", false, "shop.example", unread)
        assertNotNull(what); assertTrue(what!!, what.contains("couldn't check")); assertFalse("not called a payment page", what.contains("a payment page"))
        assertEquals("purchase", BotExtras.Rules.guessCategory(BrowserRules.riskText("Continue", unread) + " " + what))
    }

    @Test fun moneyAlwaysAsksEvenWhenTheButtonSaysSomethingElse() {
        val args = JSONObject().put("target", "12")
        // a pay button dressed up as "Reply": its form submits to /orders/pay, so it asks, as a purchase
        val disguised = BrowserRules.Signals(url = "https://mail.example/inbox", elementText = "reply-btn", formText = "/orders/pay Place order")
        assertTrue(BrowserRules.moneyRisk("browser_click", disguised))
        val what = BrowserRules.approval("browser_click", args, "Reply", false, "mail.example", disguised)
        assertNotNull(what); assertTrue(what!!.contains("payment page"))
        assertEquals("purchase", BotExtras.Rules.guessCategory(BrowserRules.riskText("Reply", disguised) + " " + what))
        // hidden words on the element itself
        assertTrue(BrowserRules.moneyRisk("browser_click", BrowserRules.Signals(elementText = "Continue checkout-submit")))
        // typing, choosing, pressing Enter and signing in on a payment page all ask, even with no submit
        val pay = BrowserRules.Signals(url = "https://shop.example/checkout")
        for (t in listOf("browser_type", "browser_select", "browser_press", "browser_fill_login"))
            assertNotNull(t, BrowserRules.approval(t, JSONObject().put("text", "x").put("key", "Tab").put("option", "Visa").put("entry", "Shop"), null, false, "shop.example", pay))
        // reading and scrolling are never money actions
        assertFalse(BrowserRules.moneyRisk("browser_read", pay)); assertFalse(BrowserRules.moneyRisk("browser_scroll", pay))
        // an ordinary page: a harmless click goes ahead as before
        assertNull(BrowserRules.approval("browser_click", args, "Next page", false, "news.example", BrowserRules.Signals(url = "https://news.example/a", formText = "Search")))
    }

    @Test fun theStrongestRiskDecidesTheKind() {
        // the label says message, the form says delete: delete wins
        assertEquals("delete", BotExtras.Rules.guessCategory(BrowserRules.riskText("Send", BrowserRules.Signals(formText = "Delete account"))))
        // a payment page makes anything a purchase
        assertEquals("purchase", BotExtras.Rules.guessCategory(BrowserRules.riskText("Send", BrowserRules.Signals(payFields = true))))
    }
}
