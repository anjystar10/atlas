package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDataTest {
    @Test fun daysForTheClockApp() {
        assertEquals(listOf(2, 4, 6), PhoneData.parseDays("mon,wed,fri"))
        assertEquals(listOf(2, 3, 4, 5, 6), PhoneData.parseDays("weekdays"))
        assertEquals(listOf(1, 7), PhoneData.parseDays("Saturday, Sunday"))
        assertEquals((1..7).toList(), PhoneData.parseDays("daily"))
        assertNull("blank means once", PhoneData.parseDays(" "))
        assertNull("a word that isn't a day is refused", PhoneData.parseDays("mon,someday"))
    }

    @Test fun calendarChangesNeedAnOkFromBots() {
        assertNotNull(BotRules.phoneApproval("calendar_change", JSONObject().put("action", "delete"), null))
        assertNotNull(BotRules.phoneApproval("calendar_change", JSONObject().put("action", "update"), null))
        assertNull(BotRules.phoneApproval("calendar_add", JSONObject(), null))
        assertNull(BotRules.phoneApproval("calendar_events", JSONObject(), null))
        assertNull(BotRules.phoneApproval("find_contact", JSONObject(), null))
    }

    @Test fun everyDeclaredToolIsHandled() {
        val declared = PhoneData.declarations.map { it.getString("name") }.toSet()
        assertEquals(PhoneData.names, declared)
        // Gemini refuses an object with no properties: tools that take arguments must declare them
        PhoneData.declarations.forEach { d -> d.optJSONObject("parameters")?.let { assertTrue(d.getString("name"), it.getJSONObject("properties").length() > 0) } }
    }
}
