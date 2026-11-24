package com.anjy.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun btn(t: String, s: Int, f: Boolean) = NotifRules.Btn(t, s, f)

class BackgroundTest {
    private val none = RouteRules.Avail()
    private val full = RouteRules.Avail(emailConnected = true, notificationAccess = true, hasReplyableMessage = true, smsAllowed = true)

    @Test fun messagingAppsPointAtBrowserAndNotificationReplies() {
        val alts = RouteRules.alternatives("WhatsApp", full)
        assertEquals(listOf(RouteRules.Lane.BROWSER, RouteRules.Lane.NOTIFICATION), alts.map { it.lane })
        assertTrue(alts[0].how.contains("web.whatsapp.com"))
        assertTrue(alts[1].how.contains("messages waiting"))
        // by package name too
        assertEquals(alts.map { it.lane }, RouteRules.alternatives("com.whatsapp", full).map { it.lane })
    }

    @Test fun backgroundWaysAreOrderedConnectionIntentBrowserNotification() {
        val sms = RouteRules.alternatives("messages", full).map { it.lane }
        assertEquals(listOf(RouteRules.Lane.INTENT, RouteRules.Lane.BROWSER, RouteRules.Lane.NOTIFICATION), sms)
        val gmail = RouteRules.alternatives("Gmail", full).map { it.lane }
        assertEquals(RouteRules.Lane.CONNECTION, gmail.first())
    }

    @Test fun emailToolsOnlyOfferedWhenAnAccountIsConnected() {
        assertTrue(RouteRules.alternatives("gmail", none).none { it.lane == RouteRules.Lane.CONNECTION })
        assertTrue(RouteRules.alternatives("gmail", full).any { it.lane == RouteRules.Lane.CONNECTION })
    }

    @Test fun unknownAppsHaveNoHintSoTheScreenIsTheWay() {
        assertTrue(RouteRules.alternatives("Some Bank App", full).isEmpty())
        assertNull(RouteRules.hint("Some Bank App", full))
        assertNull(RouteRules.hint("", full))
        // short words inside other names don't match by accident ("x" in "Xender")
        assertTrue(RouteRules.alternatives("Xender", full).isEmpty())
    }

    @Test fun hintTellsHowToForceTheScreen() {
        val h = RouteRules.hint("Instagram", none)
        assertNotNull(h); assertTrue(h!!.contains("instagram.com")); assertTrue(h.contains("use_screen=true"))
    }

    @Test fun headsUpOnlySkippedWhenTheUserIsInAtlasAskingThemselves() {
        assertFalse(RouteRules.needsHeadsUp(fromChat = true, atlasInFront = true, sessionActive = false))
        assertTrue(RouteRules.needsHeadsUp(fromChat = true, atlasInFront = false, sessionActive = false))
        assertTrue(RouteRules.needsHeadsUp(fromChat = false, atlasInFront = true, sessionActive = false))
        assertFalse(RouteRules.needsHeadsUp(fromChat = false, atlasInFront = false, sessionActive = true))
    }

    @Test fun replyButtonPicksTheSemanticReplyThenWordsThenAnyTextBox() {
        val b = ::btn
        assertEquals(1, NotifRules.replyIndex(listOf(b("Mark as read", 2, false), b("Reply", 1, true))))
        assertEquals(1, NotifRules.replyIndex(listOf(b("Quick note", 0, true), b("Reply", 0, true))))
        assertEquals(0, NotifRules.replyIndex(listOf(b("Antworten…", 0, true))))
        assertEquals(0, NotifRules.replyIndex(listOf(b("Write", 0, true), b("Archive", 0, false))))
        assertEquals(-1, NotifRules.replyIndex(listOf(b("Reply", 1, false), b("Delete", 0, false))))   // a "reply" that only opens the app
    }

    @Test fun markReadPicksTheSemanticActionOrItsWords() {
        val b = ::btn
        assertEquals(1, NotifRules.markReadIndex(listOf(b("Reply", 1, true), b("Done", 2, false))))
        assertEquals(0, NotifRules.markReadIndex(listOf(b("Mark as read", 0, false), b("Reply", 1, true))))
        assertEquals(-1, NotifRules.markReadIndex(listOf(b("Reply", 1, true), b("Mute", 0, false))))
    }

    @Test fun onlyMessagesAreListed() {
        val reply = listOf(NotifRules.Btn("Reply", 1, true))
        assertTrue(NotifRules.isMessage(null, true, emptyList()))
        assertTrue(NotifRules.isMessage("msg", false, emptyList()))
        assertTrue(NotifRules.isMessage(null, false, reply))
        assertFalse(NotifRules.isMessage("progress", false, emptyList()))   // a download bar isn't a message
    }

    @Test fun notificationIdsAreShortAndStable() {
        val k = "0|com.whatsapp|1|abc|10123"
        assertEquals(NotifRules.shortId(k), NotifRules.shortId(k))
        assertTrue(NotifRules.shortId(k).length <= 8)
    }

    @Test fun permissionAskDoesNotSpam() {
        val now = 10_000_000L
        assertEquals(PermFlow.Step.PROCEED, PermFlow.next(true, false, 0, 0, now))
        assertEquals(PermFlow.Step.ASK_ON_SCREEN, PermFlow.next(false, true, now - 1000, 0, now))
        assertEquals(PermFlow.Step.ASK_BY_NOTIFICATION, PermFlow.next(false, false, 0, 0, now))
        assertEquals(PermFlow.Step.WAIT, PermFlow.next(false, false, now - 60_000, 0, now))   // asked a minute ago: just wait
        assertEquals(PermFlow.Step.WAIT, PermFlow.next(false, false, 0, now - 60_000, now))   // said "not now" a minute ago
        assertEquals(PermFlow.Step.ASK_BY_NOTIFICATION, PermFlow.next(false, false, now - PermFlow.QUIET_MS - 1, 0, now))
        assertTrue(PermFlow.waitMs(forBot = true) > PermFlow.waitMs(forBot = false))
    }

    @Test fun theScreensStopHoldsAfterTheSessionEnds() {
        val m = RouteRules.StopMark()
        // the chat had the screen; Stop ends the session (the owner goes), the mark stays
        m.stop("chat")
        assertTrue(m.blocksChat())
        assertFalse(m.mustAsk("job1"))
        m.cleared("job1")
        assertTrue("another owner's OK doesn't lift the chat's stop", m.blocksChat())
        m.cleared("chat")   // the user's next request
        assertFalse(m.blocksChat())
        // a bot: it must ask by name until the user OKs it again
        m.stop("job1")
        assertTrue(m.mustAsk("job1")); assertFalse(m.blocksChat())
        m.cleared("job1")
        assertFalse(m.mustAsk("job1"))
        // Stop with nobody driving changes nothing
        m.stop(null)
        assertNull(m.key)
    }

    @Test fun noHeadsUpWhilePhoneControlIsOff() {
        assertTrue(RouteRules.needsHeadsUp(fromChat = true, atlasInFront = false, sessionActive = false))
        assertFalse(RouteRules.needsHeadsUp(fromChat = true, atlasInFront = false, sessionActive = false, phoneControlOn = false))
        assertFalse(RouteRules.needsHeadsUp(fromChat = false, atlasInFront = false, sessionActive = false, phoneControlOn = false))
    }
}
