package ru.aw.launcher.discord

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import ru.aw.launcher.core.I18n
import ru.aw.launcher.core.LauncherSettings
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.Settings
import java.io.IOException
import java.net.URI
import kotlin.io.path.readText

sealed interface Presence {
    data object Launcher : Presence
    data class Starting(val instance: String, val versionId: String, val loaderLabel: String?, val startedAt: Long = System.currentTimeMillis()) : Presence
    data class Playing(
        val versionId: String,
        val loaderLabel: String?,
        val server: String?,
        val mods: Int,
        val startedAt: Long,
        val pack: String? = null,
        val serverIcon: String? = null,
        val singleplayer: Boolean = false,
        val packIcon: String? = null,
        val packUrl: String? = null,
    ) : Presence
}

enum class DiscordStatus { DISABLED, NOT_CONFIGURED, WAITING, CONNECTED }

object DiscordPresence {
    private val idPattern = Regex("^[0-9]{17,20}$")
    val APP_ID: String by lazy {
        sequenceOf(
            System.getenv("AW_DISCORD_APP_ID"),
            runCatching { Paths.root.resolve("discord_app_id.txt").readText().trim() }.getOrNull(),
            javaClass.getResourceAsStream("/discord-app-id.txt")?.bufferedReader()?.use { it.readText().trim() },
        ).firstOrNull { it != null && idPattern.matches(it) } ?: "0"
    }
    const val SITE_URL = "https://awlauncher.zarplaykayt.chatgpt.site/"
    private val openedAt = System.currentTimeMillis()
    private val desired = MutableStateFlow<Presence>(Presence.Launcher)
    private val connectionStatus = MutableStateFlow(DiscordStatus.WAITING)
    val current: StateFlow<Presence> = desired.asStateFlow()
    val status: StateFlow<DiscordStatus> = connectionStatus.asStateFlow()
    private var client: PresenceClient? = null

    @Synchronized
    fun start() {
        if (client != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = PresenceClient(APP_ID, desired, Settings.state, scope, { connectionStatus.value = it }).also { it.start() }
    }

    fun show(presence: Presence) { desired.value = presence }

    @Synchronized
    fun stop() {
        client?.close()
        client = null
    }

    internal fun activity(presence: Presence, settings: LauncherSettings = Settings.current): JsonObject {
        fun tr(value: String) = I18n.text(value, settings.language)
        fun text(value: String): String {
            val clean = value.filterNot { it.isISOControl() }.trim()
            return if (clean.codePointCount(0, clean.length) <= 128) clean
                else clean.substring(0, clean.offsetByCodePoints(0, 127)) + "…"
        }
        return buildJsonObject {
            put("type", 0)
            var image = "awlauncher"
            var imageText = "AWLauncher · Minecraft Java Edition"
            var smallImage: String? = null
            var smallText: String? = null
            var packUrl: String? = null
            val start: Long
            when (presence) {
                Presence.Launcher -> {
                    put("details", tr("В лаунчере"))
                    put("state", tr("Выбирает следующую сборку"))
                    start = openedAt
                }
                is Presence.Starting -> {
                    put("details", tr("Запускает Minecraft"))
                    put("state", text(listOfNotNull(presence.instance.takeIf { settings.discordShowInstance && it != presence.versionId },
                        "Minecraft ${presence.versionId}", presence.loaderLabel).distinct().joinToString(" · ")))
                    start = presence.startedAt
                    smallImage = "launching"
                    smallText = tr("Подготовка игры")
                }
                is Presence.Playing -> {
                    val version = listOfNotNull("Minecraft ${presence.versionId}", presence.loaderLabel).joinToString(" · ")
                    val pack = presence.pack?.takeIf { settings.discordShowInstance && it.isNotBlank() }
                    put("details", text(pack ?: version))
                    val mode = when {
                        presence.server != null && settings.discordShowServer -> tr("На сервере") + " ${presence.server}"
                        presence.server != null -> tr("Сетевая игра")
                        presence.singleplayer -> tr("Одиночная игра")
                        else -> tr("В главном меню Minecraft")
                    }
                    put("state", text(listOfNotNull(mode, version.takeIf { pack != null }).joinToString(" · ")))
                    start = presence.startedAt
                    imageText = "$version · ${tr("Установлено модов")}: ${presence.mods}"
                    smallImage = when { presence.server != null -> "multiplayer"; presence.singleplayer -> "singleplayer"; else -> "playing" }
                    smallText = tr(when { presence.server != null -> "Сетевая игра"; presence.singleplayer -> "Одиночная игра"; else -> "Minecraft запущен" })
                    if (pack != null && publicImage(presence.packIcon)) {
                        image = presence.packIcon!!
                        imageText = "$pack · $version · ${tr("Установлено модов")}: ${presence.mods}"
                        smallImage = "awlauncher"
                        smallText = "AWLauncher"
                    }
                    if (settings.discordShowServer && presence.server != null && publicImage(presence.serverIcon)) {
                        smallImage = presence.serverIcon
                        smallText = presence.server
                    }
                    packUrl = presence.packUrl?.takeIf { pack != null && publicPage(it) }
                }
            }
            putJsonObject("timestamps") { put("start", start.coerceAtLeast(0) / 1000) }
            putJsonObject("assets") {
                put("large_image", image)
                put("large_text", text(imageText))
                put("large_url", packUrl ?: SITE_URL)
                smallImage?.let { put("small_image", it) }
                smallText?.let { put("small_text", text(it)) }
            }
            putJsonArray("buttons") {
                addJsonObject { put("label", tr("Сайт AWLauncher").take(32)); put("url", SITE_URL) }
                packUrl?.let { url -> addJsonObject { put("label", tr("Открыть сборку").take(32)); put("url", url) } }
            }
        }
    }

    private fun publicImage(value: String?): Boolean = runCatching {
        val uri = URI(value ?: return false)
        uri.scheme == "https" && uri.userInfo == null && uri.host in setOf("cdn.modrinth.com", "media.forgecdn.net", "api.mcsrvstat.us")
    }.getOrDefault(false)

    private fun publicPage(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.host in setOf("modrinth.com", "www.curseforge.com")
    }.getOrDefault(false)

    fun serverIcon(address: String): String? {
        // Never submit LAN addresses to a third-party icon service.
        if (!address.matches(Regex("[a-zA-Z0-9.-]+(?::[0-9]{1,5})?"))) return null
        val port = address.substringAfter(':', "").takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (port != null && port !in 1..65535) return null
        val host = address.substringBefore(':').lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") ||
            host.none { it == '.' } || host.all { it.isDigit() || it == '.' } ||
            !host.matches(Regex("[a-z0-9.-]+"))) return null
        return "https://api.mcsrvstat.us/icon/$address"
    }
}

/** One background connection; unchanged cards need only a small heartbeat, never a UI-frame timer. */
internal class PresenceClient(
    private val appId: String,
    private val desired: StateFlow<Presence>,
    private val settings: StateFlow<LauncherSettings>,
    private val scope: CoroutineScope,
    private val status: (DiscordStatus) -> Unit,
    private val connect: (String) -> PresenceIpc = DiscordIpc::connect,
    private val retryMillis: Long = 15_000,
    private val publishIntervalMillis: Long = 5_000,
) : AutoCloseable {
    @Volatile private var ipc: PresenceIpc? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            try {
                coroutineScope {
                    val target = combine(desired, settings) { presence, options ->
                        if (!options.discordPresence || (!options.discordShowLauncher && presence !is Presence.Playing)) null
                        else DiscordPresence.activity(presence, options)
                    }.stateIn(this, SharingStarted.Eagerly, null)
                    var shown: JsonObject? = null
                    var publishedAt = 0L
                    while (isActive) {
                        val card = target.value
                        val remaining = publishIntervalMillis - (System.nanoTime() - publishedAt) / 1_000_000
                        if (card != null && card != shown && shown != null && remaining > 0) {
                            withTimeoutOrNull(remaining) { target.first { it != card } }
                            continue
                        }
                        try {
                            when {
                                card == null -> {
                                    ipc?.setActivity(null)
                                    ipc?.close(); ipc = null; shown = null
                                    status(DiscordStatus.DISABLED)
                                }
                                appId == "0" -> status(DiscordStatus.NOT_CONFIGURED)
                                else -> {
                                    val connection = ipc ?: connect(appId).also { ipc = it }
                                    ensureActive()
                                    if (card != shown) {
                                        connection.setActivity(card)
                                        shown = card
                                        publishedAt = System.nanoTime()
                                    }
                                    else connection.ping()
                                    status(DiscordStatus.CONNECTED)
                                }
                            }
                        } catch (_: IOException) {
                            ipc?.close(); ipc = null; shown = null
                            status(if (card == null) DiscordStatus.DISABLED else DiscordStatus.WAITING)
                        }
                        withTimeoutOrNull(retryMillis) { target.first { it != card } }
                    }
                }
            } finally { ipc?.close(); ipc = null }
        }
    }

    override fun close() {
        job?.cancel()
        ipc?.close()
        scope.cancel()
    }
}
