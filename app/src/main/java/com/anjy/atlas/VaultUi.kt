package com.anjy.atlas

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.text.InputType
import android.view.View
import android.widget.LinearLayout

/**
 * Me → Vault: the sign-ins Atlas Bot may use. Entries show their name and site; the password stays hidden, and
 * seeing it needs the phone's own unlock. Nothing here syncs to the PC.
 */
internal fun MainActivity.vaultCard(): View {
    val c = card()
    val entries = Vault.all(this)   // a small encrypted file: quick to read
    c.addView(text(if (entries.isEmpty()) "No saved sign-ins" else "${entries.size} saved sign-in${if (entries.size == 1) "" else "s"}", 18f, FG, HEAD))
    c.add(text("Sign-ins Atlas Bot can use in its browser. It never sees them: they go straight into the page, after your OK unless you allow a site. Kept on this phone only, encrypted, never synced.", 14f, MUTED), 6)
    entries.sortedBy { it.name.lowercase() }.forEach { e ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setPadding(0, dp(12), 0, dp(4)); setOnClickListener { vaultEdit(e) } }
        row.addView(text(e.name, 16f, FG, MED))
        row.addView(text(e.site + (if (e.trusted) " · allowed without asking" else ""), 13f, MUTED))
        c.add(row, 6)
    }
    c.add(secondary("Add a Sign-in") { vaultEdit(null) }, 14)
    return c
}

private fun MainActivity.vaultEdit(old: Vault.Entry?) {
    var name = old?.name.orEmpty(); var site = old?.site.orEmpty(); var user = old?.username.orEmpty()
    var pass = old?.password.orEmpty(); var notes = old?.notes.orEmpty(); var trusted = old?.trusted ?: false
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(field("Name, e.g. My Gmail", name) { name = it })
    col.add(field("Site, e.g. accounts.google.com", site) { site = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }, 8)
    col.add(field("Username or email", user) { user = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }, 8)
    col.add(field(if (old == null) "Password" else "Password (leave as is to keep it)", "") { pass = it.ifEmpty { old?.password.orEmpty() } }
        .apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }, 8)
    col.add(field("Notes (optional)", notes) { notes = it }, 8)
    col.add(switchRow(null, "Allow Without Asking on This Site", trusted) { trusted = it }, 12)
    if (old != null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(secondary("Show Password") { unlockThen("See the password for ${old.name}") { showBanner("${old.name}: ${old.password}") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(secondary("Delete") {
            Vault.delete(this, old.id); keepSheet = false; showBanner("Deleted ${old.name}"); render(keepScroll = true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        col.add(row, 14)
    }
    sheet(null, if (old == null) "Add a Sign-in" else "Edit ${old.name}", null, content = col, primaryLabel = "Save", onPrimary = {
        when {
            name.isBlank() || Vault.siteOf(site).isBlank() || pass.isEmpty() -> { keepSheet = true; showBanner("A name, a site and a password are needed.", MISS) }
            else -> {
                try {
                    Vault.save(this, Vault.Entry(old?.id ?: java.util.UUID.randomUUID().toString(), name.trim().take(60), site, user.trim(), pass, notes.trim().take(500), trusted))
                    showBanner("Saved", DONE); render(keepScroll = true)
                } catch (e: Exception) { keepSheet = true; showBanner("Couldn't save it: ${e.message}", MISS) }
            }
        }
    }, secondaryLabel = "Cancel")
}

/** Runs [then] after the phone's own unlock (fingerprint, face or screen lock). */
private fun MainActivity.unlockThen(why: String, then: () -> Unit) {
    val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (getSystemService(BiometricManager::class.java).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
        showBanner("Set a screen lock on the phone first; saved passwords are only shown after unlocking.", MISS); return
    }
    BiometricPrompt.Builder(this).setTitle("Atlas Vault").setSubtitle(why).setAllowedAuthenticators(auth).build()
        .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { then() }
        })
}
