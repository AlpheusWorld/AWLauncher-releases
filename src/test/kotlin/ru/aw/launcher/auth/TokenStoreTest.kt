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

class TokenStoreTest {

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
