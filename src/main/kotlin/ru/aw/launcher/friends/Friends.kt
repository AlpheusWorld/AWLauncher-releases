package ru.aw.launcher.friends

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.aw.launcher.auth.AccountManager
import ru.aw.launcher.auth.TokenStore
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Notices
import ru.aw.launcher.core.NoticeLevel
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.discord.GameEvent
import ru.aw.launcher.discord.GameEvents
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.net.Http
import ru.aw.launcher.servers.ServerPing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class WorldInvitation(
    val id: String, val address: String, val title: String, val version: String,
    val loader: LoaderKind, val fingerprint: String,
)

@Serializable
data class Friend(
    val id: String, val name: String, val online: Boolean = false, val hosting: Boolean = false,
    val version: String = "", val loader: String = "", val invite: WorldInvitation? = null,
)

@Serializable
data class FriendsSnapshot(
    val friends: List<Friend> = emptyList(), val requests: List<Friend> = emptyList(),
    val outgoing: List<Friend> = emptyList(),
)

data class HostedWorld(val processId: Long, val info: WorldInvitation, val error: String? = null)
data class FriendsState(
    val code: String = "", val connected: Boolean = false, val busy: Boolean = false,
    val error: String? = null, val message: String? = null,
    val snapshot: FriendsSnapshot = FriendsSnapshot(), val world: HostedWorld? = null,
)

@Serializable
private data class FriendsIdentity(val id: String, val token: String)

/** Only social metadata goes through this API. Minecraft traffic is handled by e4mc. */
object Friends {
    private const val API = "https://awlauncher.candytulip7.chatgpt.site/api/friends"
    private val domain = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+e4mc\\.link")
    private val assigned = Regex("Domain assigned: ([a-zA-Z0-9.-]+)")
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val network = Mutex()
    private val worlds = ConcurrentHashMap<Long, HostedWorld>()
    private val mutable = MutableStateFlow(FriendsState())
    val state = mutable.asStateFlow()
    private var identity: FriendsIdentity? = null
    private var registered = false
    private val announcedInvites = LinkedHashSet<String>()
    private val announcedRequests = LinkedHashSet<String>()
    private val client by lazy { Http.client.newBuilder().callTimeout(15,TimeUnit.SECONDS).build() }

    suspend fun run() = withContext(Dispatchers.IO) {
        var wasEnabled = false
        var checkedSession: String? = null
        var failedChecks = 0
        while (currentCoroutineContext().isActive) {
            val enabled = Settings.current.friendsEnabled
            try {
                val hosted = mutable.value.world
                if (hosted?.info?.id != checkedSession) { checkedSession = hosted?.info?.id; failedChecks = 0 }
                if (enabled && hosted != null && hosted.info.address.isNotEmpty()) {
                    if (ServerPing.ping(hosted.info.address) == null) failedChecks++ else failedChecks = 0
                    if (failedChecks >= 3) {
                        val stopped = hosted.copy(info = hosted.info.copy(address = ""),error = "Мир больше не доступен через интернет. Для повторного открытия используй /e4mc restart в игре")
                        if (worlds.replace(hosted.processId,hosted,stopped))
                            mutable.update { if (it.world == hosted) it.copy(world = stopped) else it }
                    }
                }
                if (enabled || wasEnabled) network.withLock { sync(enabled) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(connected = false, error = e.message ?: "Сервис друзей недоступен") }
            }
            wasEnabled = enabled
            if (!enabled) mutable.update { it.copy(connected = false, snapshot = FriendsSnapshot()) }
            delay(10_000)
        }
    }

    suspend fun action(action: String, id: String) = withContext(Dispatchers.IO) {
        if (!Settings.current.friendsEnabled) return@withContext
        mutable.update { it.copy(busy = true, error = null, message = null) }
        try {
            network.withLock {
                val normalized = id.trim().removePrefix("AWF-").lowercase()
                if (!idPattern.matches(normalized)) throw IOException("Проверь код друга — он начинается с AWF-")
                sync(true)
                request(action, buildJsonObject { put("id", normalized) }, ensureIdentity())
                sync(true)
                mutable.update { it.copy(message = when (action) {
                    "request" -> "Заявка отправлена — дождись подтверждения друга"
                    "accept" -> "Игрок добавлен в друзья"
                    "invite" -> "Приглашение в мир отправлено"
                    "remove" -> "Игрок удалён из друзей"
                    else -> null
                }) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Не удалось выполнить действие") } }
        finally { mutable.update { it.copy(busy = false) } }
    }

    private fun ensureIdentity(): FriendsIdentity {
        identity?.let { return it }
        val file = Paths.root.resolve("friends-identity.json")
        val stored = if (Files.isRegularFile(file) && Files.size(file) <= 8192) {
            Json.decodeFromString<FriendsIdentity>(Files.readString(file))
        } else {
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val sealed = TokenStore.protect(token)
            if (TokenStore.reveal(sealed) != token) throw IOException("Не удалось защитить профиль друзей — проверь хранилище ключей системы")
            FriendsIdentity(UUID.randomUUID().toString(),sealed).also { file.writeAtomically(Json.encodeToString(it)) }
        }
        val token = TokenStore.reveal(stored.token)
        if (!idPattern.matches(stored.id) || !Regex("[A-Za-z0-9_-]{43}").matches(token))
            throw IOException("Не удалось открыть защищённый профиль друзей на этом компьютере")
        return FriendsIdentity(stored.id,token).also {
            identity = it
            mutable.update { state -> state.copy(code = "AWF-" + it.id) }
        }
    }

    private fun sync(online: Boolean) {
        val auth = ensureIdentity()
        val name = AccountManager.selected.value?.name?.take(48)?.trim()?.takeIf { it.isNotEmpty() } ?: "Игрок"
        if (!registered) {
            request("register",buildJsonObject { put("id",auth.id); put("token",auth.token); put("name",name) },null)
            registered = true
        }
        val world = mutable.value.world?.info?.takeIf { online && it.address.isNotEmpty() }
        val response = request("sync",buildJsonObject {
            put("name",name); put("online",online)
            put("world",world?.let { Json.encodeToJsonElement(WorldInvitation.serializer(),it) } ?: JsonNull)
        },auth)
        val snapshot = Json.decodeFromString<FriendsSnapshot>(response)
        // Do not let an unexpected API payload become an arbitrary connection target.
        if (snapshot.friends.any { it.invite != null && !domain.matches(it.invite.address) })
            throw IOException("Сервис вернул некорректный адрес мира")
        for (friend in snapshot.friends) {
            val invite = friend.invite ?: continue
            if (announcedInvites.add(friend.id + ":" + invite.id))
                Notices.post(NoticeLevel.INFO,"${friend.name} приглашает в мир ${invite.title}. Открой вкладку «Друзья», чтобы присоединиться",title = "Приглашение в мир")
        }
        for (friend in snapshot.requests) if (announcedRequests.add(friend.id))
            Notices.post(NoticeLevel.INFO,"${friend.name} хочет добавить тебя в друзья. Подтверди заявку во вкладке «Друзья»",title = "Заявка в друзья")
        while (announcedInvites.size > 256) announcedInvites.remove(announcedInvites.first())
        announcedRequests.retainAll(snapshot.requests.map { it.id }.toSet())
        mutable.update { it.copy(snapshot = snapshot,connected = online,error = null) }
    }

    private fun request(action: String, body: JsonObject, auth: FriendsIdentity?): String {
        val request = Request.Builder().url("$API/$action").header("Cache-Control","no-store")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { auth?.let { header("Authorization","Bearer " + it.token) } }.build()
        client.newCall(request).execute().use { response ->
            if ((response.body?.contentLength() ?: 0) > 262144) throw IOException("Слишком большой ответ сервиса друзей")
            val content = response.body?.byteStream()?.readNBytes(262145)?.toString(Charsets.UTF_8).orEmpty()
            if (content.length > 262144) throw IOException("Слишком большой ответ сервиса друзей")
            if (!response.isSuccessful) {
                if (response.code == 401) registered = false
                val message = runCatching { (Json.parseToJsonElement(content) as JsonObject)["error"]?.jsonPrimitive?.content }.getOrNull()
                throw IOException(message ?: "Сервис друзей недоступен (HTTP ${response.code})")
            }
            return content
        }
    }

    fun track(processId: Long, gameDir: Path, version: String, loader: LoaderKind, title: String) {
        if (!Settings.current.friendsEnabled || !loader.isModded || !FriendsMod.isPresent(gameDir)) return
        val world = HostedWorld(processId,WorldInvitation(UUID.randomUUID().toString(),"",title.take(120),version,loader,FriendsMod.fingerprint(gameDir)))
        worlds[processId] = world
        mutable.update { it.copy(world = world) }
    }

    fun line(processId: Long, line: String) {
        if ("[CHAT]" in line) return
        val previous = worlds[processId] ?: return
        val next = when {
            "e4mc" in line && assigned.containsMatchIn(line) -> {
                if (!Settings.current.friendsEnabled) return
                val address = assigned.find(line)!!.groupValues[1].lowercase().removeSuffix(".")
                if (!domain.matches(address)) return
                previous.copy(info = previous.info.copy(id = UUID.randomUUID().toString(),address = address),error = null)
            }
            GameEvents.parse(line) == GameEvent.Menu || GameEvents.parse(line) == GameEvent.Singleplayer -> previous.copy(info = previous.info.copy(address = ""),error = null)
            "error in e4mc" in line -> previous.copy(info = previous.info.copy(address = ""),error = "Не удалось открыть мир через интернет — проверь соединение и попробуй /e4mc restart")
            else -> return
        }
        worlds[processId] = next
        mutable.update { it.copy(world = next) }
    }

    fun stopped(processId: Long) {
        worlds.remove(processId)
        mutable.update { if (it.world?.processId == processId) it.copy(world = worlds.values.firstOrNull()) else it }
    }
}
