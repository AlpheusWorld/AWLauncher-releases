package ru.aw.launcher.meta

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class VersionJson(
    val id: String = "",
    val type: String = "release",
    val mainClass: String = "net.minecraft.client.main.Main",
    val inheritsFrom: String? = null,
    val assets: String? = null,
    val assetIndex: AssetIndexRef? = null,
    val downloads: Downloads? = null,
    val libraries: List<Library> = emptyList(),
    val arguments: Arguments? = null,
    val minecraftArguments: String? = null,
    val javaVersion: JavaVersionRef? = null,
    val logging: Logging? = null,
    val minimumLauncherVersion: Int = 0,
    val releaseTime: String? = null,
    val time: String? = null,
) {
    val assetsId: String get() = assetIndex?.id ?: assets ?: "legacy"

    val usesLegacyAssets: Boolean get() = assetsId == "legacy" || assetsId == "pre-1.6"

    fun mergedOnto(parent: VersionJson): VersionJson = copy(
        id = id,
        type = type.ifBlank { parent.type },
        mainClass = mainClass.takeIf { it.isNotBlank() && inheritsFrom != null } ?: parent.mainClass,
        inheritsFrom = null,
        assets = assets ?: parent.assets,
        assetIndex = assetIndex ?: parent.assetIndex,
        downloads = downloads ?: parent.downloads,
        libraries = libraries + parent.libraries,
        arguments = Arguments(
            game = (arguments?.game ?: emptyList()) + (parent.arguments?.game ?: emptyList()),
            jvm = (arguments?.jvm ?: emptyList()) + (parent.arguments?.jvm ?: emptyList()),
        ).takeIf { it.game.isNotEmpty() || it.jvm.isNotEmpty() },
        minecraftArguments = minecraftArguments ?: parent.minecraftArguments,
        javaVersion = javaVersion ?: parent.javaVersion,
        logging = logging ?: parent.logging,
        minimumLauncherVersion = maxOf(minimumLauncherVersion, parent.minimumLauncherVersion),
        releaseTime = releaseTime ?: parent.releaseTime,
        time = time ?: parent.time,
    )
}

@Serializable
data class Downloads(
    val client: Artifact? = null,
    val server: Artifact? = null,
    @SerialName("client_mappings") val clientMappings: Artifact? = null,
    @SerialName("server_mappings") val serverMappings: Artifact? = null,
)

@Serializable
data class Artifact(
    val path: String? = null,
    val sha1: String? = null,
    val size: Long = 0,
    val url: String = "",
    val id: String? = null,
)

@Serializable
data class AssetIndexRef(
    val id: String = "legacy",
    val sha1: String? = null,
    val size: Long = 0,
    val url: String = "",
)

@Serializable
data class JavaVersionRef(
    val component: String = "jre-legacy",
    val majorVersion: Int = 8,
)

@Serializable
data class Logging(val client: LoggingClient? = null)

@Serializable
data class LoggingClient(
    val argument: String = "",
    val file: Artifact? = null,
    val type: String = "log4j2-xml",
)

@Serializable
data class Library(
    val name: String = "",
    val downloads: LibraryDownloads? = null,
    val url: String? = null,
    val rules: List<Rule> = emptyList(),
    val natives: Map<String, String> = emptyMap(),
    val extract: Extract? = null,
    val clientreq: Boolean? = null,
) {
    fun appliesTo(env: RuleEnvironment): Boolean = Rule.allows(rules, env)

    fun nativeClassifier(env: RuleEnvironment): String? =
        natives[env.osName]?.replace("\${arch}", env.archBits)
}

@Serializable
data class LibraryDownloads(
    val artifact: Artifact? = null,
    val classifiers: Map<String, Artifact> = emptyMap(),
)

@Serializable
data class Extract(val exclude: List<String> = emptyList())

@Serializable
data class Rule(
    val action: String = "allow",
    val os: OsRule? = null,
    val features: Map<String, Boolean> = emptyMap(),
) {
    fun matches(env: RuleEnvironment): Boolean {
        os?.let { rule ->
            if (rule.name != null && rule.name != env.osName) return false
            if (rule.arch != null && rule.arch != env.osArch) return false
            if (rule.version != null && !Regex(rule.version).containsMatchIn(env.osVersion)) return false
        }
        for ((feature, required) in features) {
            if (env.features.getOrDefault(feature, false) != required) return false
        }
        return true
    }

    companion object {
        fun allows(rules: List<Rule>, env: RuleEnvironment): Boolean {
            if (rules.isEmpty()) return true
            var allowed = false
            for (rule in rules) {
                if (rule.matches(env)) allowed = rule.action == "allow"
            }
            return allowed
        }
    }
}

@Serializable
data class OsRule(
    val name: String? = null,
    val version: String? = null,
    val arch: String? = null,
)

@Serializable
data class Arguments(
    val game: List<Argument> = emptyList(),
    val jvm: List<Argument> = emptyList(),
)

@Serializable(with = ArgumentSerializer::class)
sealed interface Argument {
    data class Literal(val value: String) : Argument

    data class Conditional(val rules: List<Rule>, val values: List<String>) : Argument

    fun resolve(env: RuleEnvironment): List<String> = when (this) {
        is Literal -> listOf(value)
        is Conditional -> if (Rule.allows(rules, env)) values else emptyList()
    }
}

object ArgumentSerializer : KSerializer<Argument> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ru.aw.launcher.meta.Argument")

    override fun deserialize(decoder: Decoder): Argument {
        val input = decoder as? JsonDecoder
            ?: throw UnsupportedOperationException("Argument can only be read from JSON")
        return when (val element: JsonElement = input.decodeJsonElement()) {
            is JsonPrimitive -> Argument.Literal(element.content)
            is JsonObject -> {
                val rules = element["rules"]
                    ?.let { input.json.decodeFromJsonElement<List<Rule>>(it) }
                    .orEmpty()
                val values = when (val raw = element["value"]) {
                    is JsonPrimitive -> listOf(raw.content)
                    is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.content }
                    else -> emptyList()
                }
                Argument.Conditional(rules, values)
            }
            else -> Argument.Literal("")
        }
    }

    override fun serialize(encoder: Encoder, value: Argument) {
        when (value) {
            is Argument.Literal -> encoder.encodeString(value.value)
            is Argument.Conditional -> encoder.encodeString(value.values.joinToString(" "))
        }
    }
}
