package dev.gounthar.xcpng.toolbox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Attempt.matchesStored`, which decides one sentence in the Test connection popup.
 *
 * It exists because of a real click on 2026-08-21: an already-configured pool tested green and
 * was told *"Nothing was saved. Open Settings again and press Save to keep these values"*, which
 * is true, useless, and invites the reader to save values that are already saved. The same
 * sentence is exactly right for somebody who just typed a new URL. One sentence cannot be both,
 * so the decision is made here and tested here.
 */
class PoolSettingsAttemptTest {

    private val storedUrl = "https://xoa.example.com"

    private fun attempt(
        baseUrl: String = storedUrl,
        typedToken: String? = null,
        allowUnauthorized: Boolean = false,
    ) = PoolSettingsPage.Attempt(baseUrl, typedToken, allowUnauthorized)

    @Test
    fun `testing the stored configuration unchanged is not an edit`() {
        assertTrue(attempt().matchesStored(storedUrl, false))
    }

    @Test
    fun `a different URL is an edit`() {
        assertFalse(attempt(baseUrl = "https://other.example.com").matchesStored(storedUrl, false))
    }

    @Test
    fun `toggling the certificate checkbox is an edit`() {
        assertFalse(attempt(allowUnauthorized = true).matchesStored(storedUrl, false))
    }

    /**
     * A blank token field means "use the stored one" (see the note on `tokenField`), so it is the
     * *typed* token that signals an edit. Somebody retyping the identical token is counted as an
     * edit, which is not distinguishable without comparing secrets and lands on the more cautious
     * of the two sentences.
     */
    @Test
    fun `a typed token is an edit even when everything else matches`() {
        assertFalse(attempt(typedToken = "a-token").matchesStored(storedUrl, false))
    }

    /**
     * The first save of a fresh install has nothing stored to match, so a green test there must
     * take the "nothing was saved" branch, which is the case that sentence was written for.
     */
    @Test
    fun `nothing stored yet is always an edit`() {
        assertFalse(attempt().matchesStored(null, false))
    }

    /**
     * A URL saved before `save()` started trimming still has to compare equal, or every such
     * installation reads as permanently edited and never gets the quieter sentence.
     */
    @Test
    fun `a stored URL with stray whitespace still matches the trimmed form`() {
        assertTrue(attempt().matchesStored("  $storedUrl  ", false))
    }
}
