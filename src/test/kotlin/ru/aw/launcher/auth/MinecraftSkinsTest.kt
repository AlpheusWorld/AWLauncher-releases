package ru.aw.launcher.auth

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class MinecraftSkinsTest {
    private val account = Account("00000000000000000000000000000001", "SkinTester", AccountType.MICROSOFT,
        accessToken = "test-only-token", expiresAt = Long.MAX_VALUE)
    private fun png(width: Int = 64, height: Int = 64) = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it)
    }.toByteArray()
    private val profile = """{"id":"00000000000000000000000000000001","name":"SkinTester","skins":[{"state":"ACTIVE","url":"https://textures.minecraft.net/texture/test","variant":"SLIM"}]}"""
    private fun network(handler: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = handler(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody()).build()
    }.build()

    @Test
    fun `skin upload verifies identity and sends Minecraft bearer PNG and model`() = runBlocking {
        val requests = ArrayList<String>()
        val client = network { request ->
            assertEquals("api.minecraftservices.com", request.url.host)
            assertEquals("Bearer test-only-token", request.header("Authorization"))
            requests += request.method
            if (request.method == "POST") {
                assertEquals("/minecraft/profile/skins", request.url.encodedPath)
                val form = request.body as MultipartBody
                assertEquals(MultipartBody.FORM, form.type)
                val variant = Buffer().also { form.part(0).body.writeTo(it) }.readUtf8()
                assertEquals("slim", variant)
                assertTrue(form.part(1).headers.toString().contains("name=\"file\"; filename=\"skin.png\""))
                val uploaded = Buffer().also { form.part(1).body.writeTo(it) }.readByteArray()
                assertEquals(64, MinecraftSkins.decode(uploaded).image.width)
            }
            200 to profile
        }
        val result = MinecraftSkins(client).upload(account, MinecraftSkins.decode(png()), SkinModel.SLIM)
        assertEquals(listOf("GET", "POST"), requests)
        assertEquals("SLIM", result.skins.single().variant)
    }

    @Test
    fun `a token belonging to another account never changes a skin`() = runBlocking {
        var mutations = 0
        val client = network { request ->
            if (request.method != "GET") mutations++
            200 to profile.replace(account.uuid, "00000000000000000000000000000002")
        }
        val failed = runCatching { MinecraftSkins(client).upload(account, MinecraftSkins.decode(png()), SkinModel.CLASSIC) }
        assertTrue(failed.exceptionOrNull() is AuthException)
        assertEquals(0, mutations)
    }

    @Test
    fun `reset uses the active skin endpoint and reconciles empty responses`() = runBlocking {
        val requests = ArrayList<String>()
        val client = network { request ->
            requests += request.method
            if (request.method == "DELETE") { assertEquals("/minecraft/profile/skins/active", request.url.encodedPath); 204 to "" }
            else 200 to profile
        }
        assertEquals(account.uuid, MinecraftSkins(client).reset(account).id)
        assertEquals(listOf("GET", "DELETE", "GET"), requests)
    }

    @Test
    fun `HTTP failures do not expose response bodies or credentials`() = runBlocking {
        val client = network { 403 to "secret-refresh-token test-only-token" }
        val failure = runCatching { MinecraftSkins(client).profile(account) }.exceptionOrNull()!!
        assertTrue(failure is AuthException)
        assertFalse(failure.stackTraceToString().contains("secret-refresh-token"))
        assertFalse(failure.stackTraceToString().contains("test-only-token"))
    }

    @Test
    fun `cape selection checks ownership and uses the Mojang active cape endpoint`() = runBlocking {
        val capeId = "00000000-0000-0000-0000-000000000003"
        val capeProfile = profile.dropLast(1) + """, "capes":[{"id":"$capeId","state":"INACTIVE","url":"https://textures.minecraft.net/texture/cape","alias":"Test cape"}]}"""
        val requests = ArrayList<String>()
        val client = network { request ->
            requests += request.method
            assertEquals("Bearer test-only-token", request.header("Authorization"))
            if (request.method == "PUT") {
                assertEquals("/minecraft/profile/capes/active", request.url.encodedPath)
                assertEquals("""{"capeId":"$capeId"}""", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                200 to capeProfile.replace("INACTIVE", "ACTIVE")
            } else 200 to capeProfile
        }
        val selected = MinecraftSkins(client).changeCape(account, capeId)
        assertEquals("ACTIVE", selected.capes.single().state)
        assertEquals(listOf("GET", "PUT"), requests)
        requests.clear()
        assertTrue(runCatching { MinecraftSkins(client).changeCape(account, "unowned-cape") }.exceptionOrNull() is AuthException)
        assertEquals(listOf("GET"), requests)
    }

    @Test
    fun `removing a cape reconciles an empty response and mismatched identities cannot mutate it`() = runBlocking {
        val requests = ArrayList<String>()
        val client = network { request ->
            requests += request.method
            if (request.method == "DELETE") {
                assertEquals("/minecraft/profile/capes/active", request.url.encodedPath)
                204 to ""
            } else 200 to profile
        }
        assertTrue(MinecraftSkins(client).changeCape(account, null).capes.isEmpty())
        assertEquals(listOf("GET", "DELETE", "GET"), requests)
        var mutations = 0
        val wrong = network { request ->
            if (request.method != "GET") mutations++
            200 to profile.replace(account.uuid, "00000000000000000000000000000002")
        }
        assertTrue(runCatching { MinecraftSkins(wrong).changeCape(account, null) }.exceptionOrNull() is AuthException)
        assertEquals(0, mutations)
        assertTrue(runCatching { MinecraftSkins(wrong).changeCape(Account.offline("Offline"), null) }.exceptionOrNull() is AuthException)
    }

    @Test
    fun `offline accounts cannot contact the profile API`() = runBlocking {
        var requests = 0
        val client = network { requests++; 200 to profile }
        assertTrue(runCatching { MinecraftSkins(client).profile(Account.offline("Offline")) }.exceptionOrNull() is AuthException)
        assertEquals(0, requests)
    }

    @Test
    fun `skin validation rejects wrong dimensions malformed files and oversized payloads`() {
        assertEquals(32, MinecraftSkins.decode(png(height = 32)).image.height)
        assertThrows(AuthException::class.java) { MinecraftSkins.decode(png(128, 128)) }
        assertThrows(AuthException::class.java) { MinecraftSkins.decode("not png".toByteArray()) }
        assertThrows(AuthException::class.java) { MinecraftSkins.decode(png().copyOf(1024 * 1024 + 1)) }
        assertThrows(AuthException::class.java) { MinecraftSkins.decode(png().take(20).toByteArray()) }
    }

    @Test
    fun `profile updates retain account selection and authentication state`() {
        val original = AccountManager.accounts.value
        val selected = AccountManager.selected.value
        try {
            val target = account.copy(refreshToken = "test-only-refresh")
            AccountManager.upsert(target)
            val other = Account.offline("OtherUser")
            AccountManager.upsert(other)
            val parsed = ru.aw.launcher.core.Json.decodeFromString(McProfile.serializer(), profile)
            val updated = AccountManager.updateProfile(target.uuid, parsed)
            assertEquals(other.uuid, AccountManager.selected.value?.uuid)
            assertEquals(target.accessToken, updated.accessToken)
            assertEquals(target.refreshToken, updated.refreshToken)
            assertEquals(SkinModel.SLIM, updated.skinModel)
            assertTrue(AccountManager.accounts.value.any { it.uuid == updated.uuid })
        } finally {
            AccountManager.accounts.value.map { it.uuid }.forEach(AccountManager::remove)
            original.forEach(AccountManager::upsert)
            selected?.let { AccountManager.select(it.uuid) }
        }
    }
}
