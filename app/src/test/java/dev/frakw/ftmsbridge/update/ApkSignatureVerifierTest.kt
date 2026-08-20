package dev.frakw.ftmsbridge.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSignatureVerifierTest {
    @Test
    fun `accepts the same signer and forward certificate rotation`() {
        val installed = identity(current = setOf("old"), history = setOf("old"))

        assertTrue(signingIdentityMatches(installed, identity(current = setOf("old"), history = setOf("old"))))
        assertTrue(signingIdentityMatches(installed, identity(current = setOf("new"), history = setOf("old", "new"))))
    }

    @Test
    fun `rejects unrelated missing and ambiguous single signers`() {
        val installed = identity(current = setOf("trusted"), history = setOf("trusted"))

        assertFalse(signingIdentityMatches(installed, identity(current = setOf("other"), history = setOf("other"))))
        assertFalse(signingIdentityMatches(installed, identity(current = emptySet(), history = emptySet())))
        assertFalse(signingIdentityMatches(installed, identity(current = setOf("trusted", "other"), history = setOf("trusted"))))
    }

    @Test
    fun `multiple signer packages require the exact signer set`() {
        val installed = identity(multiple = true, current = setOf("one", "two"))

        assertTrue(signingIdentityMatches(installed, identity(multiple = true, current = setOf("two", "one"))))
        assertFalse(signingIdentityMatches(installed, identity(multiple = true, current = setOf("one"))))
        assertFalse(signingIdentityMatches(installed, identity(current = setOf("one"), history = setOf("one", "two"))))
    }

    private fun identity(
        multiple: Boolean = false,
        current: Set<String>,
        history: Set<String> = current,
    ) = SigningIdentity(multiple, current, history)
}
