package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tappable phone numbers and web addresses in replies, without turning dates, times, amounts or versions into links. */
class LinkTextTest {
    private fun found(text: String): List<String> { val m = LinkText.PHONE.matcher(text); val out = mutableListOf<String>(); while (m.find()) LinkText.phone(m.group())?.let { out.add(it) }; return out }

    @Test fun phoneNumbersBecomeLinks() {
        assertEquals(listOf("tel:+447911123456"), found("Call +44 7911 123456 tomorrow."))
        assertEquals(listOf("tel:5551234567"), found("Their number is (555) 123-4567."))
        assertEquals(listOf("tel:02079460958"), found("Ring 020 7946 0958 before 5."))
        assertEquals(listOf("tel:5551234567"), found("5551234567"))
    }
    @Test fun otherNumbersStayText() {
        assertEquals(emptyList<String>(), found("Meet at 13:00 on 2026-11-17, it costs 1,200,000 dollars."))
        assertEquals(emptyList<String>(), found("Version 10.0.19045 is out; order 1234567 shipped."))
        assertEquals(emptyList<String>(), found("On 17.11.2026 at 9.30"))
        assertEquals(emptyList<String>(), found("see /var/log/1234567890 or id-12345678"))
    }
    @Test fun webAddresses() {
        assertEquals("https://example.com/a", LinkText.web("https://example.com/a"))
        assertEquals("https://www.bbc.co.uk", LinkText.web("www.bbc.co.uk"))
        assertEquals("https://github.com/x", LinkText.web("github.com/x"))
        assertNull(LinkText.web("me@site.com")); assertNull(LinkText.web("notes.txt")); assertNull(LinkText.web("e.g"))
    }
}
