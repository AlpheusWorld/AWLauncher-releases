package ru.aw.launcher.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.java.JavaAuthManager
import net.raphimc.minecraftauth.msa.data.MsaConstants
import net.raphimc.minecraftauth.msa.exception.MsaRequestException
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService
import net.raphimc.minecraftauth.xbl.exception.XblRequestException
import net.lenni0451.commons.httpclient.HttpClient as AuthHttpClient
import net.lenni0451.commons.httpclient.HttpResponse
import net.lenni0451.commons.httpclient.exceptions.HttpRequestException
import net.lenni0451.commons.httpclient.executor.RequestExecutor
import net.lenni0451.commons.httpclient.requests.HttpRequest
import net.lenni0451.commons.httpclient.requests.HttpContentRequest
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Shell
import ru.aw.launcher.net.Http
import java.awt.Desktop
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeoutException
import java.util.function.Consumer

data class MicrosoftLoginCode(val code: String, val verificationUrl: String)

class MicrosoftAuth(private val clientId: String = AuthConfig.clientId) {

    private companion object {
        const val AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
        const val TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        const val XBL = "https://user.auth.xboxlive.com/user/authenticate"
        const val XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize"
        const val MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox"
        const val MC_ENTITLEMENTS = "https://api.minecraftservices.com/entitlements/mcstore"
        const val MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile"
        const val SCOPE = "XboxLive.signin offline_access"
        const val DEVICE_SESSION_PREFIX = "aw-ms-device:"

        val JSON_MEDIA = "application/json".toMediaType()
    }

    suspend fun signIn(
        onStage: (String) -> Unit = {},
        onCode: (MicrosoftLoginCode) -> Unit = {},
    ): Account = withContext(Dispatchers.IO) {
        if (!AuthConfig.isConfigured) return@withContext signInDevice(onStage, onCode)

        val verifier = randomUrlSafe(64)
        val challenge = codeChallenge(verifier)
        val state = randomUrlSafe(24)

        LoopbackReceiver().use { receiver ->
            val url = AUTHORIZE.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", receiver.redirectUri)
                .addQueryParameter("scope", SCOPE)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("state", state)
                .addQueryParameter("prompt", "select_account")
                .build()
                .toString()

            onStage("Открываю браузер для входа")
            openBrowser(url)

            val code = receiver.awaitCode(expectedState = state, timeoutMillis = 5 * 60_000)

            onStage("Обмен кода на токен")
            val msToken = exchangeCode(code, verifier, receiver.redirectUri)
            finish(msToken, onStage)
        }
    }

    suspend fun refresh(refreshToken: String, onStage: (String) -> Unit = {}): Account =
        withContext(Dispatchers.IO) {
            if (refreshToken.startsWith(DEVICE_SESSION_PREFIX)) {
                return@withContext runInterruptible {
                    deviceRequest {
                        onStage("Обновление сессии")
                        val manager = JavaAuthManager.create(deviceHttpClient())
                            .login(refreshToken.removePrefix(DEVICE_SESSION_PREFIX))
                        finishDevice(manager, onStage)
                    }
                }
            }
            if (!AuthConfig.isConfigured) throw AuthException(AuthConfig.SETUP_HINT)
            onStage("Обновление сессии")
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .add("scope", SCOPE)
                .build()
            val response = postForm(TOKEN, body)
            val parsed = Json.decodeFromString<MsTokenResponse>(response)
            if (parsed.error != null) {
                throw AuthException("Сессия истекла, войдите заново (${parsed.error})")
            }
            finish(parsed.copy(refreshToken = parsed.refreshToken.ifBlank { refreshToken }), onStage)
        }

    private suspend fun signInDevice(
        onStage: (String) -> Unit,
        onCode: (MicrosoftLoginCode) -> Unit,
    ): Account = runInterruptible(Dispatchers.IO) {
        deviceRequest {
            onStage("Получение кода Microsoft")
            val httpClient = deviceHttpClient()
            val config = MsaApplicationConfig(MsaConstants.JAVA_TITLE_ID, MsaConstants.SCOPE_TITLE_AUTH)
            val service = DeviceCodeMsaAuthService(httpClient, config, Consumer { device ->
                onCode(MicrosoftLoginCode(device.userCode, device.directVerificationUri))
                onStage("Подтвердите вход в браузере")
                Shell.browse(device.directVerificationUri)
            })
            val manager = JavaAuthManager.create(httpClient).login(service.acquireToken())
            finishDevice(manager, onStage)
        }
    }

    private fun finishDevice(manager: JavaAuthManager, onStage: (String) -> Unit): Account {
        onStage("Вход в Minecraft")
        val token = manager.minecraftToken.upToDate
        onStage("Проверка лицензии")
        requireOwnership(token.token)
        onStage("Загрузка профиля")
        val profile = fetchProfile(token.token)
        val refreshToken = manager.msaToken.upToDate.refreshToken
            ?.takeIf { it.isNotBlank() }
            ?: throw AuthException("Microsoft не вернул токен обновления — войдите заново")
        return Account(
            uuid = profile.id,
            name = profile.name,
            type = AccountType.MICROSOFT,
            accessToken = token.token,
            refreshToken = DEVICE_SESSION_PREFIX + refreshToken,
            expiresAt = token.expireTimeMs,
            skinUrl = profile.skins.firstOrNull { it.state.equals("ACTIVE", true) }?.url,
            capes = profile.capes,
            capeId = profile.capes.firstOrNull { it.state.equals("ACTIVE", true) }?.id,
        )
    }

    internal fun <T> deviceRequest(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        if (error !is CancellationException && error !is InterruptedException) {
            val requestError = error as? HttpRequestException
            val response = requestError?.response
            val endpoint = response?.url?.let { "${it.host}${it.path}" }.orEmpty()
            Log.warn("microsoft auth failed: ${error.javaClass.simpleName}; HTTP ${response?.statusCode ?: "n/a"}; $endpoint")
            Log.debug("microsoft auth stack: ${error.stackTrace.take(5).joinToString(" | ")}")
        }
        when (error) {
            is CancellationException, is InterruptedException, is AuthException -> throw error
            is TimeoutException -> throw AuthException("Время входа истекло — запросите новый код Microsoft")
            is XblRequestException -> throw AuthException(describeXErr(error.errorCode))
            is MsaRequestException -> throw AuthException(when (error.error) {
                "authorization_declined", "access_denied" -> "Вход Microsoft отменён"
                "expired_token" -> "Код Microsoft истёк — запросите новый код"
                "invalid_grant" -> "Сессия Microsoft истекла — войдите заново"
                else -> "Microsoft отклонил вход — попробуйте войти заново"
            })
            is HttpRequestException -> throw AuthException("Сервис ${error.response.url.host} отклонил вход (HTTP ${error.response.statusCode})")
            // Upstream exceptions can contain tokens or response bodies. Never log them.
            else -> throw AuthException("Не удалось завершить вход Microsoft — проверьте подключение и попробуйте ещё раз")
        }
    }

    internal fun deviceHttpClient(network: OkHttpClient = Http.client): AuthHttpClient = MinecraftAuth.createHttpClient(Http.USER_AGENT)
        .setExecutor { client ->
            val authNetwork = network.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .cache(null)
                .build()
            object : RequestExecutor(client) {
                override fun execute(request: HttpRequest): HttpResponse {
                    val endpoint = "${request.url.host}${request.url.path}"
                    val content = (request as? HttpContentRequest)?.content
                    val body = content?.asBytes?.toRequestBody(content.type.toString().toMediaTypeOrNull())
                    val outgoing = Request.Builder().url(request.url)
                        .method(request.method, body)
                    getHeaders(request, null).forEach { (name, values) ->
                        values.forEach { outgoing.addHeader(name, it) }
                    }
                    authNetwork.newCall(outgoing.build()).execute().use { response ->
                        if (response.code != 400 || !request.url.path.contains("token")) {
                            Log.info("microsoft auth: $endpoint HTTP ${response.code}")
                        }
                        return HttpResponse(request.url, response.code, response.body?.bytes() ?: byteArrayOf(), response.headers.toMultimap())
                    }
                }
            }
        }

    private fun finish(msToken: MsTokenResponse, onStage: (String) -> Unit): Account {
        onStage("Вход в Xbox Live")
        val (xblToken, userHash) = authenticateXbl(msToken.accessToken)

        onStage("Проверка XSTS")
        val xstsToken = authorizeXsts(xblToken)

        onStage("Вход в Minecraft")
        val mcToken = loginWithXbox(userHash, xstsToken)

        onStage("Проверка лицензии")
        requireOwnership(mcToken.accessToken)

        onStage("Загрузка профиля")
        val profile = fetchProfile(mcToken.accessToken)

        return Account(
            uuid = profile.id,
            name = profile.name,
            type = AccountType.MICROSOFT,
            accessToken = mcToken.accessToken,
            refreshToken = msToken.refreshToken,
            expiresAt = System.currentTimeMillis() + mcToken.expiresIn * 1000,
            skinUrl = profile.skins.firstOrNull { it.state.equals("ACTIVE", true) }?.url,
            capes = profile.capes,
            capeId = profile.capes.firstOrNull { it.state.equals("ACTIVE", true) }?.id,
        )
    }

    private fun exchangeCode(code: String, verifier: String, redirectUri: String): MsTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .add("scope", SCOPE)
            .build()
        val parsed = Json.decodeFromString<MsTokenResponse>(postForm(TOKEN, body))
        if (parsed.error != null) {
            throw AuthException("Microsoft отклонил вход: ${parsed.errorDescription ?: parsed.error}")
        }
        if (parsed.accessToken.isBlank()) throw AuthException("Microsoft не вернул токен доступа")
        if (parsed.refreshToken.isBlank()) throw AuthException("Microsoft не вернул токен обновления")
        return parsed
    }

    private fun authenticateXbl(msAccessToken: String): Pair<String, String> {
        val payload = """
            {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=$msAccessToken"},
             "RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}
        """.trimIndent()
        val parsed = Json.decodeFromString<XboxResponse>(postJson(XBL, payload))
        val hash = parsed.displayClaims?.xui?.firstOrNull()?.uhs
            ?: throw AuthException("Xbox Live не вернул идентификатор пользователя")
        if (parsed.token.isBlank()) throw AuthException("Xbox Live не вернул токен")
        return parsed.token to hash
    }

    private fun authorizeXsts(xblToken: String): String {
        val payload = """
            {"Properties":{"SandboxId":"RETAIL","UserTokens":["$xblToken"]},
             "RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}
        """.trimIndent()

        val (code, body) = postJsonRaw(XSTS, payload)
        if (code == 401) {
            val error = runCatching { Json.decodeFromString<XboxResponse>(body) }.getOrNull()
            throw AuthException(describeXErr(error?.xErr))
        }
        if (code !in 200..299) throw AuthException("XSTS вернул HTTP $code")

        val parsed = Json.decodeFromString<XboxResponse>(body)
        if (parsed.token.isBlank()) throw AuthException("XSTS не вернул токен")
        return parsed.token
    }

    private fun describeXErr(xErr: Long?): String = when (xErr) {
        2148916233L -> "К этому аккаунту Microsoft не привязан профиль Xbox. " +
            "Зайдите на minecraft.net под этим аккаунтом и создайте профиль."
        2148916235L -> "Xbox Live недоступен в стране вашего аккаунта."
        2148916236L, 2148916237L -> "Аккаунту требуется подтверждение возраста (Xbox adult verification)."
        2148916238L -> "Детский аккаунт: добавьте его в семейную группу Microsoft."
        2148916227L -> "Аккаунт заблокирован за нарушение правил Xbox Live."
        else -> "Xbox Live отклонил вход" + (xErr?.let { " (код $it)" } ?: "")
    }

    private fun loginWithXbox(userHash: String, xstsToken: String): McLoginResponse {
        val payload = """{"identityToken":"XBL3.0 x=$userHash;$xstsToken"}"""
        val (code, body) = postJsonRaw(MC_LOGIN, payload)
        if (code == 403) {
            throw AuthException(
                "Minecraft не пускает это приложение Azure (HTTP 403). Новое приложение нужно " +
                    "отдельно одобрить у Mojang: заявка на aka.ms/mce-reviewappid, подробности в README."
            )
        }
        if (code !in 200..299) throw AuthException("Minecraft отклонил вход (HTTP $code)")

        val parsed = Json.decodeFromString<McLoginResponse>(body)
        if (parsed.accessToken.isBlank()) throw AuthException("Minecraft не выдал сессионный токен")
        return parsed
    }

    private fun requireOwnership(mcAccessToken: String) {
        val body = get(MC_ENTITLEMENTS, mcAccessToken)
        val entitlements = runCatching { Json.decodeFromString<Entitlements>(body) }.getOrNull()
        val owns = entitlements?.items.orEmpty().any {
            it.name == "product_minecraft" || it.name == "game_minecraft"
        }
        if (!owns) {
            throw AuthException(
                "На этом аккаунте Microsoft нет лицензии Minecraft: Java Edition. " +
                    "Если игра куплена на другом аккаунте — войдите под ним."
            )
        }
    }

    private fun fetchProfile(mcAccessToken: String): McProfile {
        val (code, body) = getRaw(MC_PROFILE, mcAccessToken)
        if (code == 404) {
            throw AuthException(
                "У аккаунта нет профиля Minecraft. Зайдите на minecraft.net и задайте никнейм."
            )
        }
        if (code !in 200..299) throw AuthException("Не удалось получить профиль (HTTP $code)")
        val profile = Json.decodeFromString<McProfile>(body)
        if (profile.id.isBlank() || profile.name.isBlank()) {
            throw AuthException("Сервис Minecraft вернул пустой профиль")
        }
        return profile
    }

    private fun postForm(url: String, body: FormBody): String {
        Http.client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 400) {
                throw AuthException("HTTP ${response.code} от $url")
            }
            return text
        }
    }

    private fun postJson(url: String, payload: String): String {
        val (code, body) = postJsonRaw(url, payload)
        if (code !in 200..299) throw AuthException("HTTP $code от $url")
        return body
    }

    private fun postJsonRaw(url: String, payload: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    private fun get(url: String, bearer: String): String {
        val (code, body) = getRaw(url, bearer)
        if (code !in 200..299) throw AuthException("HTTP $code от $url")
        return body
    }

    private fun getRaw(url: String, bearer: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun openBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }.onFailure { Log.debug("Desktop.browse failed: ${it.message}") }

        runCatching {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
        }.onFailure {
            throw AuthException("Не удалось открыть браузер. Откройте ссылку вручную:\n$url", it)
        }
    }
}

@Serializable
private data class MsTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
private data class XboxResponse(
    @SerialName("Token") val token: String = "",
    @SerialName("DisplayClaims") val displayClaims: DisplayClaims? = null,
    @SerialName("XErr") val xErr: Long? = null,
)

@Serializable
private data class DisplayClaims(val xui: List<Xui> = emptyList())

@Serializable
private data class Xui(val uhs: String = "")

@Serializable
private data class McLoginResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 86_400,
)

@Serializable
internal data class McProfile(
    val id: String = "",
    val name: String = "",
    val skins: List<McSkin> = emptyList(),
    val capes: List<MinecraftCape> = emptyList(),
)

@Serializable
internal data class McSkin(val url: String = "", val state: String = "", val variant: String = "CLASSIC")

@Serializable
private data class Entitlements(val items: List<EntitlementItem> = emptyList())

@Serializable
private data class EntitlementItem(val name: String = "")
