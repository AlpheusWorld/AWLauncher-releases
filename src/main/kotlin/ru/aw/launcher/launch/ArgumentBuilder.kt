package ru.aw.launcher.launch

import ru.aw.launcher.auth.Account
import ru.aw.launcher.auth.AccountType
import ru.aw.launcher.core.LauncherSettings
import ru.aw.launcher.core.Paths
import ru.aw.launcher.install.InstalledVersion
import ru.aw.launcher.instance.InstanceOptions
import ru.aw.launcher.meta.Argument
import ru.aw.launcher.meta.RuleEnvironment
import ru.aw.launcher.servers.ServerAddress
import ru.aw.launcher.servers.SrvResolver
import java.io.File
import java.nio.file.Path

class ArgumentBuilder(
    private val installed: InstalledVersion,
    private val account: Account,
    private val settings: LauncherSettings,
    private val javaExecutable: Path,
    private val gameDir: Path,
    private val serverAddress: String? = null,
    private val options: InstanceOptions = InstanceOptions(),
) {

    private val version = installed.json
    private val customResolution = options.windowWidth != null && options.windowHeight != null

    private val quickPlayJoin: Boolean = !serverAddress.isNullOrBlank() &&
        version.arguments?.game.orEmpty().any { argument ->
            argument is Argument.Conditional && argument.rules.any { "is_quick_play_multiplayer" in it.features }
        }

    private val env: RuleEnvironment = RuleEnvironment.current.withFeatures(
        "is_demo_user" to false,
        "has_custom_resolution" to customResolution,
        "has_quick_plays_support" to false,
        "is_quick_play_singleplayer" to false,
        "is_quick_play_multiplayer" to quickPlayJoin,
        "is_quick_play_realms" to false,
    )

    private val classpath: String =
        installed.classpath.distinct().joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }

    fun build(): List<String> = buildList {
        add(javaExecutable.toAbsolutePath().toString())
        addAll(jvmArguments())
        add(version.mainClass)
        addAll(gameArguments())
    }

    private fun jvmArguments(): List<String> = buildList {
        add("-Xmx${settings.memoryMb}M")
        add("-Xms${(settings.memoryMb / 2).coerceAtLeast(512)}M")

        add("-Dlog4j2.formatMsgNoLookups=true")

        add("-XX:-HeapDumpOnOutOfMemoryError")

        installed.logConfig?.let { config ->
            val argument = installed.logConfigArgument ?: "-Dlog4j.configurationFile=\${path}"
            add(argument.replace("\${path}", config.toAbsolutePath().toString()))
        }

        addAll(parseJvmArguments(settings.jvmArgs))

        val fromManifest = version.arguments?.jvm.orEmpty()
            .flatMap { it.resolve(env) }
            .map { template(it) }

        if (fromManifest.isEmpty()) {
            add("-Djava.library.path=${installed.nativesDir.toAbsolutePath()}")
            add("-cp")
            add(classpath)
        } else {
            addAll(fromManifest)
        }
    }

    private fun gameArguments(): List<String> = buildList {
        val structured = version.arguments?.game.orEmpty()
        if (structured.isNotEmpty()) {
            structured.forEach { argument ->
                argument.resolve(env).forEach { add(template(it)) }
            }
        } else {
            version.minecraftArguments.orEmpty()
                .split(' ')
                .filter { it.isNotBlank() }
                .forEach { add(template(it)) }
        }

        if (!quickPlayJoin) {
            serverAddress?.let { ServerAddress.parse(it) }?.let { address ->
                val target = if (address.port == null) SrvResolver.resolve(address.host) ?: address else address
                add("--server"); add(target.host)
                add("--port"); add(target.effectivePort.toString())
            }
        }
        if (customResolution) {
            require(options.windowWidth!! in 320..16384 && options.windowHeight!! in 200..16384) { "Некорректный размер окна Minecraft" }
            if ("--width" !in this) { add("--width"); add(options.windowWidth.toString()) }
            if ("--height" !in this) { add("--height"); add(options.windowHeight.toString()) }
        }
    }

    private fun template(raw: String): String {
        if (!raw.contains("\${")) return raw
        var result = raw
        for ((key, value) in replacements) {
            result = result.replace("\${$key}", value)
        }
        return result
    }

    private val replacements: Map<String, String> by lazy {
        mapOf(
            "natives_directory" to installed.nativesDir.toAbsolutePath().toString(),
            "launcher_name" to LAUNCHER_NAME,
            "launcher_version" to LAUNCHER_VERSION,
            "classpath" to classpath,
            "classpath_separator" to File.pathSeparator,
            "library_directory" to Paths.libraries.toAbsolutePath().toString(),
            "version_name" to version.id,
            "auth_player_name" to account.name,
            "game_directory" to gameDir.toAbsolutePath().toString(),
            "assets_root" to installed.assetsDir.toAbsolutePath().toString(),
            "game_assets" to installed.assetsDir.toAbsolutePath().toString(),
            "assets_index_name" to installed.assetIndexId,
            "auth_uuid" to account.uuid,
            "auth_access_token" to account.accessToken,
            "auth_session" to "token:${account.accessToken}:${account.uuid}",
            "auth_xuid" to "",
            "clientid" to "",
            "user_type" to if (account.type == AccountType.MICROSOFT) "msa" else "legacy",
            "version_type" to version.type,
            "user_properties" to "{}",
            "resolution_width" to (options.windowWidth ?: 854).toString(),
            "resolution_height" to (options.windowHeight ?: 480).toString(),
            "quickPlayPath" to "",
            "quickPlaySingleplayer" to "",
            "quickPlayMultiplayer" to serverAddress?.trim().orEmpty(),
            "quickPlayRealms" to "",
        )
    }

    companion object {
        const val LAUNCHER_NAME = "AWLauncher"
        const val LAUNCHER_VERSION = "1.0.8"

        fun parseJvmArguments(value: String): List<String> {
            val result = ArrayList<String>()
            val token = StringBuilder()
            var quote: Char? = null
            var started = false
            var index = 0
            while (index < value.length) {
                val character = value[index]
                when {
                    character == '\\' && quote != null && value.getOrNull(index + 1) == quote -> {
                        token.append(quote)
                        index++
                        started = true
                    }
                    character == '\"' || character == '\'' -> {
                        if (quote == null) { quote = character; started = true }
                        else if (quote == character) quote = null
                        else token.append(character)
                    }
                    character.isWhitespace() && quote == null -> {
                        if (started) { result += token.toString(); token.setLength(0); started = false }
                    }
                    else -> { token.append(character); started = true }
                }
                index++
            }
            require(quote == null) { "Закрой кавычки в аргументах JVM" }
            if (started) result += token.toString()
            return result
        }
    }
}
