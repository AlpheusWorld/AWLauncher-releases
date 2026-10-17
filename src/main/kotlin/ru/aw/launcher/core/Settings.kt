package ru.aw.launcher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.aw.launcher.meta.LoaderKind
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT, OLED }

@Serializable
enum class Language(val label: String, val tag: String) {
    EN("English (United States)", "en-US"), EN_GB("English (United Kingdom)", "en-GB"),
    ES("Español (España)", "es-ES"), ES_MX("Español (México)", "es-MX"),
    DE("Deutsch", "de-DE"), FR("Français", "fr-FR"), PT_BR("Português (Brasil)", "pt-BR"), PT("Português (Portugal)", "pt-PT"),
    RU("Русский", "ru"), IT("Italiano", "it-IT"), NL("Nederlands", "nl-NL"), PL("Polski", "pl-PL"), TR("Türkçe", "tr-TR"),
    ZH_CN("简体中文", "zh-CN"), ZH_TW("繁體中文", "zh-TW"), JA("日本語", "ja-JP"), ID("Bahasa Indonesia", "id-ID"),
    FA("فارسی", "fa-IR"), CS("Čeština", "cs-CZ"), VI("Tiếng Việt", "vi-VN"), KO("한국어", "ko-KR"),
    UK("Українська", "uk-UA"), AR("العربية", "ar-SA"), HU("Magyar", "hu-HU"), SV("Svenska", "sv-SE"),
    RO("Română", "ro-RO"), EL("Ελληνικά", "el-GR"), DA("Dansk", "da-DK"), FI("Suomi", "fi-FI"),
    HE("עברית", "he-IL"), SK("Slovenčina", "sk-SK"), TH("ไทย", "th-TH"), BG("Български", "bg-BG"),
    HR("Hrvatski", "hr-HR"), SR("Српски", "sr-RS"), NB("Norsk bokmål", "nb-NO"), LT("Lietuvių", "lt-LT"),
    SL("Slovenščina", "sl-SI"), CA("Català", "ca-ES"), ET("Eesti", "et-EE"), LV("Latviešu", "lv-LV"),
    BN("বাংলা", "bn-BD"), HI("हिन्दी", "hi-IN"), BS("Bosanski", "bs-BA"), AZ("Azərbaycanca", "az-AZ"),
    KA("ქართული", "ka-GE"), IS("Íslenska", "is-IS"), MS("Bahasa Melayu", "ms-MY"), KK("Қазақша", "kk-KZ"), HY("Հայերեն", "hy-AM");

    val resourceTag: String get() = when (this) { EN -> "en"; ES -> "es"; else -> tag }
    val locale: java.util.Locale get() = java.util.Locale.forLanguageTag(tag)
    val isRtl: Boolean get() = this == AR || this == FA || this == HE
    val englishName: String get() = locale.getDisplayName(java.util.Locale.ENGLISH)
}

@Serializable
data class LauncherSettings(
    val memoryMb: Int = SettingsDefaults.memoryMb(),
    val jvmArgs: String = SettingsDefaults.JVM_ARGS,
    val showSnapshots: Boolean = false,
    val showOldVersions: Boolean = false,
    val onlyInstalled: Boolean = false,
    val downloadConcurrency: Int = 0,
    val customGameDir: String? = null,
    val lastVersionId: String? = null,
    val lastLoader: String? = null,
    val lastPack: String? = null,
    val lastBuildId: String? = null,
    val forceVerify: Boolean = false,
    val installId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: Language = Language.EN,
    val keepLauncherOpen: Boolean = false,
    val libraryGroups: List<ru.aw.launcher.instance.LibraryGroup> = emptyList(),
    val discordPresence: Boolean = true,
    val discordShowLauncher: Boolean = true,
    val discordShowInstance: Boolean = true,
    val discordShowServer: Boolean = false,
)

object SettingsDefaults {

    const val JVM_ARGS =
        "-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:MaxGCPauseMillis=50 " +
            "-XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M -XX:+DisableExplicitGC"

    fun memoryMb(): Int = recommendedMemory(totalSystemMemoryMb())

    fun recommendedMemory(totalMb: Int): Int {
        val nominalMb = (totalMb + 512) / 1024 * 1024
        val quarter = (nominalMb / 4).coerceIn(2048, 8192)
        return (memoryPresets(totalMb).filter { it <= quarter }.maxOrNull() ?: 2048)
            .coerceIn(512, memoryLimit(totalMb))
    }

    fun memoryLimit(totalMb: Int): Int = (totalMb - 1024).coerceAtLeast(512)

    fun memoryPresets(totalMb: Int): List<Int> =
        listOf(2, 4, 6, 8, 12, 16).map { it * 1024 }.filter { it <= maxOf(4096, totalMb - 2048) }

    fun totalSystemMemoryMb(): Int = runCatching {
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as com.sun.management.OperatingSystemMXBean
        (bean.totalMemorySize / 1024 / 1024).toInt()
    }.getOrDefault(8192)
}

object Settings {

    private val _state = MutableStateFlow(LauncherSettings())
    private val saveLock = Any()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        saveScope.launch {
            for (request in saveRequests) {
                do {
                    delay(250)
                } while (saveRequests.tryReceive().isSuccess)
                save()
            }
        }
    }
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    val current: LauncherSettings get() = _state.value

    fun load() {
        val file = Paths.settingsFile
        if (!file.exists()) return
        runCatching { _state.value = Json.decodeFromString<LauncherSettings>(file.readText()) }
            .onFailure { Log.warn("launcher.json unreadable, using defaults", it) }
    }

    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        _state.update(transform)
        saveRequests.trySend(Unit)
    }

    fun save() {
        synchronized(saveLock) {
            runCatching { Paths.settingsFile.writeAtomically(PrettyJson.encodeToString(_state.value)) }
                .onFailure { Log.error("could not save settings", it) }
        }
    }

    fun gameDir(gameVersion: String, loader: LoaderKind = LoaderKind.VANILLA): Path {
        val name = if (loader.isModded) "$gameVersion-${loader.name.lowercase()}" else gameVersion
        return customGameRoot()?.resolve(name) ?: Paths.instanceDir(name)
    }

    fun packsDir(): Path = (customGameRoot() ?: Paths.instances).resolve(PACKS)

    fun buildsDir(): Path = (customGameRoot() ?: Paths.instances).resolve(BUILDS)

    fun gameRoots(): List<Path> {
        val roots = listOfNotNull(Paths.instances, customGameRoot()).distinct()
        return (roots + roots.flatMap { listOf(it.resolve(PACKS), it.resolve(BUILDS)) }).distinct()
    }

    private fun customGameRoot(): Path? = current.customGameDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    private const val PACKS = "packs"
    private const val BUILDS = "builds"
}
