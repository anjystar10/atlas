package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {
    private val gmail = Vault.Entry("1", "My Gmail", "accounts.google.com", "king@example.com", "s3cr\"et'\\pw</script>")
    private val shop = Vault.Entry("2", "Jumia", "jumia.com.ng", "king", "pw2", trusted = true)

    @Test fun sitesAreStoredPlain() {
        assertEquals("accounts.google.com", Vault.siteOf("https://www.accounts.google.com/signin?x=1"))
        assertEquals("jumia.com.ng", Vault.siteOf("www.jumia.com.ng/login"))
        assertEquals("example.com", Vault.siteOf("Example.com"))
    }

    @Test fun aSignInOnlyWorksOnItsOwnSite() {
        assertTrue(Vault.siteMatches("google.com", "accounts.google.com"))
        assertTrue(Vault.siteMatches("accounts.google.com", "accounts.google.com"))
        assertFalse("a look-alike domain doesn't match", Vault.siteMatches("google.com", "google.com.evil.example"))
        assertFalse(Vault.siteMatches("google.com", "notgoogle.com"))
        assertFalse(Vault.siteMatches("", "google.com"))
    }

    @Test fun findsByNameIdOrSite() {
        val all = listOf(gmail, shop)
        assertEquals("2", Vault.find(all, "jumia")?.id ?: Vault.find(all, "Jumia")?.id)
        assertEquals("1", Vault.find(all, "1")?.id)
        assertEquals("1", Vault.find(all, "accounts.google.com")?.id)
        assertNull(Vault.find(all, "nothing"))
    }

    @Test fun theModelNeverSeesUsernamesOrPasswords() {
        val text = Vault.forModel(listOf(gmail, shop))
        assertFalse(text.contains("king@example.com")); assertFalse(text.contains("pw2")); assertFalse(text.contains("s3cr"))
        assertTrue(text.contains("My Gmail")); assertTrue(text.contains("jumia.com.ng"))
    }

    @Test fun valuesAreQuotedSafelyForThePage() {
        val js = Vault.fillJs(gmail.username, gmail.password)
        // the password with quotes, a backslash and a closing tag stays one JS string literal
        assertTrue(js.contains(JSONObject.quote(gmail.password)))
        assertFalse(js.contains("'pw</script>"))
    }

    @Test fun fillingASignInNeedsAnOk() {
        assertNotNull(BrowserRules.approval("browser_fill_login", JSONObject().put("entry", "My Gmail"), null, false, "accounts.google.com"))
    }
}
