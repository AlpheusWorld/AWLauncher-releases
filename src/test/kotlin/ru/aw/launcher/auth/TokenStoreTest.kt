package ru.aw.launcher.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import ru.aw.launcher.core.Paths
import java.util.Base64
import java.util.UUID
import kotlin.io.path.readText
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.assertThrows

class TokenStoreTest {

    @Test
    fun `Linux token envelopes use fresh nonces and reject tampering`() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val first = TokenStore.encrypt("minecraft-token", key)
        val second = TokenStore.encrypt("minecraft-token", key)
        assertNotEquals(first, second)
        assertEquals("minecraft-token", TokenStore.decrypt(first, key))
        val changed = Base64.getDecoder().decode(first).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows<Exception> { TokenStore.decrypt(Base64.getEncoder().encodeToString(changed), key) }
        assertThrows<Exception> { TokenStore.decrypt(first, SecretKeySpec(ByteArray(32) { 7 }, "AES")) }
        assertThrows<IllegalArgumentException> { TokenStore.decrypt("AA==", key) }
    }

    @Test
    fun `temporarily unreadable protected tokens are not erased during account saves`() {
        val stored = "secret-service:encrypted-value"
        assertEquals(stored, TokenStore.protectPreserving("", stored))
        assertEquals("", TokenStore.protectPreserving("", "plain:old-token"))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `Linux tokens survive through the desktop Secret Service`() {
        val token = "linux-test-token-" + UUID.randomUUID()
        val protected = TokenStore.protect(token)
        assertTrue(protected.startsWith("secret-service:"))
        assertFalse(token in protected)
        assertEquals(token, TokenStore.reveal(protected))
    }

    @Test
    fun `legacy base64 tokens remain readable for migration`() {
        val token = "legacy-access-token"
        val legacy = "plain:" + Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))

        assertEquals(token, TokenStore.reveal(legacy))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `new tokens are protected with the current Windows account`() {
        val token = "minecraft-access-token-value"
        val protected = TokenStore.protect(token)

        assertTrue(protected.startsWith("dpapi:"))
        assertNotEquals(token, protected)
        assertEquals(token, TokenStore.reveal(protected))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `account files do not contain Microsoft tokens in plain text`() {
        val id = UUID.randomUUID().toString()
        val accessToken = "minecraft-access-${UUID.randomUUID()}"
        val refreshToken = "microsoft-refresh-${UUID.randomUUID()}"
        try {
            AccountManager.upsert(
                Account(
                    uuid = id,
                    name = "TestPlayer",
                    type = AccountType.MICROSOFT,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                ),
            )

            val saved = Paths.accountsFile.readText()
            assertFalse(accessToken in saved)
            assertFalse(refreshToken in saved)
            assertTrue("dpapi:" in saved)
        } finally {
            AccountManager.remove(id)
        }
    }
}
