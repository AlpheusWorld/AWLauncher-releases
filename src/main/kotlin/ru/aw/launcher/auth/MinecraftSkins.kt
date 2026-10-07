package ru.aw.launcher.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.aw.launcher.core.Json
import ru.aw.launcher.net.Http
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal data class SkinImage(val png: ByteArray, val image: BufferedImage)

internal class MinecraftSkins(http: OkHttpClient = Http.client) {
    private val client = http.newBuilder().cache(null).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun profile(account: Account): McProfile = withContext(Dispatchers.IO) {
        requireLicensed(account)
        parseProfile(execute(request(account, PROFILE).get().build()), account)
    }

    suspend fun upload(account: Account, skin: SkinImage, model: SkinModel): McProfile = withContext(Dispatchers.IO) {
        profile(account) // Verify the bearer belongs to this account before changing its public profile.
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("variant", model.apiValue)
            .addFormDataPart("file", "skin.png", skin.png.toRequestBody("image/png".toMediaType())).build()
        val response = execute(request(account, "$PROFILE/skins").post(body).build())
        reconcile(account, response)
    }

    suspend fun reset(account: Account): McProfile = withContext(Dispatchers.IO) {
        profile(account)
        reconcile(account, execute(request(account, "$PROFILE/skins/active").delete().build()))
    }

    suspend fun changeCape(account: Account, capeId: String?): McProfile = withContext(Dispatchers.IO) {
        val current = profile(account)
        if (capeId != null && current.capes.none { it.id == capeId })
            throw AuthException("Этот плащ недоступен выбранному аккаунту Minecraft")
        val endpoint = "$PROFILE/capes/active"
        val request = if (capeId == null) request(account, endpoint).delete() else {
            val body = buildJsonObject { put("capeId", capeId) }.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            request(account, endpoint).put(body)
        }
        reconcile(account, execute(request.build()))
    }

    private suspend fun reconcile(account: Account, response: String): McProfile {
        runCatching { parseProfile(response, account) }.getOrNull()?.let { return it }
        return try { profile(account) }
        catch (failure: AuthException) { throw AuthException("Изменение отправлено в Minecraft, но профиль не удалось обновить. Нажми «Обновить профиль»") }
    }

    private fun request(account: Account, url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer ${account.accessToken}").header("Accept", "application/json")

    private fun execute(request: Request): String = try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AuthException(when (response.code) {
                401 -> "Сессия Minecraft истекла — войди через Microsoft заново"
                403 -> "Minecraft запретил изменение внешнего вида для этого аккаунта"
                404 -> "У аккаунта нет профиля Minecraft Java"
                429 -> "Слишком частые изменения внешнего вида — повтори позже"
                400 -> "Minecraft не принял изменение. Проверь выбранный скин или плащ"
                else -> "Сервис Minecraft вернул HTTP ${response.code}"
            })
            response.body?.byteStream()?.use { stream ->
                val bytes = stream.readNBytes(MAX_RESPONSE + 1)
                if (bytes.size > MAX_RESPONSE) throw AuthException("Сервис Minecraft вернул некорректный профиль")
                bytes.toString(Charsets.UTF_8)
            }.orEmpty()
        }
    } catch (failure: IOException) {
        throw AuthException("Не удалось связаться с Minecraft — проверь подключение")
    }

    private fun parseProfile(body: String, account: Account): McProfile {
        val profile = runCatching { Json.decodeFromString<McProfile>(body) }.getOrNull()
            ?: throw AuthException("Сервис Minecraft вернул некорректный профиль")
        if (profile.id.replace("-", "").lowercase() != account.uuid.replace("-", "").lowercase() || profile.name.isBlank())
            throw AuthException("Профиль Minecraft не совпадает с выбранным аккаунтом")
        return profile
    }

    private fun requireLicensed(account: Account) {
        if (account.isOffline) throw AuthException("Скин профиля доступен только для лицензионного аккаунта")
        if (account.accessToken.isBlank()) throw AuthException("Войди через Microsoft, чтобы изменить скин")
    }

    companion object {
        private const val PROFILE = "https://api.minecraftservices.com/minecraft/profile"
        private const val MAX_RESPONSE = 512 * 1024
        private const val MAX_IMAGE = 1024 * 1024

        fun read(path: Path): SkinImage {
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_IMAGE + 1) }
            if (bytes.size > MAX_IMAGE) throw AuthException("PNG скина должен быть не больше 1 МБ")
            return decode(bytes)
        }

        internal fun decode(bytes: ByteArray): SkinImage {
            val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            if (bytes.size > MAX_IMAGE || !bytes.take(8).toByteArray().contentEquals(signature)) throw AuthException("Выбери PNG скина размером 64×64 или 64×32")
            return try {
                ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: throw IOException()
                    try {
                        reader.input = input
                        if (reader.getWidth(0) != 64 || reader.getHeight(0) !in listOf(32, 64))
                            throw AuthException("Выбери PNG скина размером 64×64 или 64×32")
                        val image = reader.read(0)
                        val normalized = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                        SkinImage(normalized, image)
                    } finally { reader.dispose() }
                }
            } catch (failure: IOException) { throw AuthException("Не удалось прочитать PNG скина") }
        }

        fun fromImage(image: BufferedImage): SkinImage {
            val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            return decode(bytes)
        }
    }
}
