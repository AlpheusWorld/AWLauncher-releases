package ru.aw.launcher.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.net.Http

enum class LoaderKind(val label: String) {
    VANILLA("Vanilla"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    FORGE("Forge"),
    NEOFORGE("NeoForge");

    val isModded: Boolean get() = this != VANILLA

    val installsWithJava: Boolean get() = this == FORGE || this == NEOFORGE

    fun ownsProfile(id: String, gameVersion: String): Boolean = when (this) {
        VANILLA -> id == gameVersion
        FABRIC -> id.startsWith("fabric-loader-") && id.endsWith("-$gameVersion")
        QUILT -> id.startsWith("quilt-loader-") && id.endsWith("-$gameVersion")
        FORGE -> id.startsWith("$gameVersion-forge", ignoreCase = true)
        NEOFORGE -> id.startsWith("neoforge-") &&
            LoaderRepository.neoForgeGameVersion(id.removePrefix("neoforge-")) == gameVersion
    }
}

data class LoaderVersion(
    val version: String,
    val stable: Boolean = false,
    val recommended: Boolean = false,
) {
    val label: String
        get() = buildString {
            append(version)
            if (recommended) append(" · рекомендуемая")
            else if (stable) append(" · стабильная")
        }
}

@Serializable
data class LoaderSupport(
    val fabric: Set<String> = emptySet(),
    val quilt: Set<String> = emptySet(),
    val forge: Set<String> = emptySet(),
    val neoforge: Set<String> = emptySet(),
) {
    fun supports(kind: LoaderKind, gameVersion: String): Boolean = when (kind) {
        LoaderKind.VANILLA -> true
        LoaderKind.FABRIC -> gameVersion in fabric
        LoaderKind.QUILT -> gameVersion in quilt
        LoaderKind.FORGE -> gameVersion in forge
        LoaderKind.NEOFORGE -> gameVersion in neoforge
    }

    fun loadersFor(gameVersion: String): List<LoaderKind> =
        LoaderKind.entries.filter { supports(it, gameVersion) }

    val isEmpty: Boolean get() = fabric.isEmpty() && quilt.isEmpty() && forge.isEmpty() && neoforge.isEmpty()
}

object LoaderRepository {

    private const val FABRIC_META = "https://meta.fabricmc.net/v2"
    private const val QUILT_META = "https://meta.quiltmc.org/v3"
    private const val FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
    private const val FORGE_PROMOS =
        "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
    private const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge"

    private val cache = HashMap<String, List<LoaderVersion>>()

    fun versionsFor(kind: LoaderKind, gameVersion: String): List<LoaderVersion> {
        if (kind == LoaderKind.VANILLA || gameVersion.isBlank()) return emptyList()
        val key = "${kind.name}:$gameVersion"
        cache[key]?.let { return it }

        val result = runCatching {
            when (kind) {
                LoaderKind.FABRIC -> fabricLike("$FABRIC_META/versions/loader/$gameVersion")
                LoaderKind.QUILT -> fabricLike("$QUILT_META/versions/loader/$gameVersion")
                LoaderKind.FORGE -> forge(gameVersion)
                LoaderKind.NEOFORGE -> neoForge(gameVersion)
                LoaderKind.VANILLA -> emptyList()
            }
        }.onFailure { Log.warn("loader list failed for $key: ${it.message}") }.getOrDefault(emptyList())

        if (result.isNotEmpty()) cache[key] = result
        return result
    }

    fun latestBuild(kind: LoaderKind, gameVersion: String): String? {
        val all = versionsFor(kind, gameVersion)
        return (all.firstOrNull { it.recommended }
            ?: all.firstOrNull { it.stable }
            ?: all.firstOrNull())?.version
    }

    fun supportedGameVersions(kind: LoaderKind, attempts: Int = 3): Set<String> {
        repeat(attempts) { attempt ->
            val result = runCatching {
                when (kind) {
                    LoaderKind.FABRIC -> gameVersionList("$FABRIC_META/versions/game")
                    LoaderKind.QUILT -> gameVersionList("$QUILT_META/versions/game")
                    LoaderKind.FORGE -> mavenVersions("$FORGE_MAVEN/maven-metadata.xml")
                        .mapNotNull { it.substringBefore('-').takeIf { p -> p.isNotBlank() } }
                        .filter(::forgeInstallable)
                        .toSet()
                    LoaderKind.NEOFORGE -> mavenVersions("$NEOFORGE_MAVEN/maven-metadata.xml")
                        .mapNotNull { neoForgeGameVersion(it) }
                        .toSet()
                    LoaderKind.VANILLA -> emptySet()
                }
            }
            result.onSuccess { if (it.isNotEmpty() || kind == LoaderKind.VANILLA) return it }
            result.onFailure {
                Log.warn("supported versions for ${kind.label}, attempt ${attempt + 1}: ${it.message}")
            }
            if (attempt < attempts - 1) Thread.sleep(600L * (attempt + 1))
        }
        return emptySet()
    }

    @Serializable
    private data class GameVersionEntry(val version: String = "")

    private fun gameVersionList(url: String): Set<String> =
        Json.decodeFromString<List<GameVersionEntry>>(Http.getString(url))
            .map { it.version }
            .filter { it.isNotBlank() }
            .toSet()

    fun profileUrl(kind: LoaderKind, gameVersion: String, loaderVersion: String): String = when (kind) {
        LoaderKind.FABRIC -> "$FABRIC_META/versions/loader/$gameVersion/$loaderVersion/profile/json"
        LoaderKind.QUILT -> "$QUILT_META/versions/loader/$gameVersion/$loaderVersion/profile/json"
        else -> throw IllegalArgumentException("$kind has no ready-made profile json")
    }

    fun installerUrl(kind: LoaderKind, gameVersion: String, loaderVersion: String): String = when (kind) {
        LoaderKind.FORGE ->
            "$FORGE_MAVEN/$gameVersion-$loaderVersion/forge-$gameVersion-$loaderVersion-installer.jar"
        LoaderKind.NEOFORGE ->
            "$NEOFORGE_MAVEN/$loaderVersion/neoforge-$loaderVersion-installer.jar"
        else -> throw IllegalArgumentException("$kind is not installed from an installer jar")
    }

    @Serializable
    private data class FabricLoaderEntry(val loader: FabricLoader = FabricLoader())

    @Serializable
    private data class FabricLoader(
        val version: String = "",
        val stable: Boolean = false,
        val build: Int = 0,
    )

    private fun fabricLike(url: String): List<LoaderVersion> =
        Json.decodeFromString<List<FabricLoaderEntry>>(Http.getString(url))
            .filter { it.loader.version.isNotBlank() }
            .map { entry ->
                val version = entry.loader.version
                val looksPre = version.contains("beta", true) || version.contains("pre", true) ||
                    version.contains("rc", true)
                LoaderVersion(version = version, stable = entry.loader.stable || !looksPre)
            }

    @Serializable
    private data class ForgePromotions(@SerialName("promos") val promos: Map<String, String> = emptyMap())

    private fun forge(gameVersion: String): List<LoaderVersion> {
        val recommended = runCatching {
            Json.decodeFromString<ForgePromotions>(Http.getString(FORGE_PROMOS))
                .promos["$gameVersion-recommended"]
        }.getOrNull()
        return forgeBuilds(gameVersion, mavenVersions("$FORGE_MAVEN/maven-metadata.xml"), recommended)
    }

    internal fun forgeBuilds(gameVersion: String, mavenVersions: List<String>, recommended: String?): List<LoaderVersion> {
        val prefix = "$gameVersion-"
        return mavenVersions
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .distinct()
            .sortedWith(NEWEST_FIRST)
            .map { LoaderVersion(version = it, stable = true, recommended = it.substringBefore('-') == recommended) }
    }

    internal fun forgeInstallable(gameVersion: String): Boolean = compareNumbers(gameVersion, "1.7.10") >= 0

    private fun neoForge(gameVersion: String): List<LoaderVersion> =
        neoForgeBuilds(gameVersion, mavenVersions("$NEOFORGE_MAVEN/maven-metadata.xml"))

    internal fun neoForgeBuilds(gameVersion: String, mavenVersions: List<String>): List<LoaderVersion> =
        mavenVersions
            .filter { neoForgeGameVersion(it) == gameVersion }
            .sortedWith(NEWEST_FIRST)
            .map { LoaderVersion(version = it, stable = '-' !in it) }

    fun neoForgeGameVersion(loaderVersion: String): String? {
        if ('+' in loaderVersion) return null
        val parts = loaderVersion.substringBefore('-').split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (major >= 26) {
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: return null
            return if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
        }
        return if (minor == 0) "1.$major" else "1.$major.$minor"
    }

    internal val NEWEST_FIRST: Comparator<String> = Comparator { a, b ->
        compareNumbers(b.substringBefore('-'), a.substringBefore('-')).takeIf { it != 0 }
            ?: ('-' in a).compareTo('-' in b)
    }

    private fun compareNumbers(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val difference = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private val MAVEN_VERSION = Regex("<version>([^<]+)</version>")

    private fun mavenVersions(url: String): List<String> =
        MAVEN_VERSION.findAll(Http.getString(url)).map { it.groupValues[1] }.toList()
}
