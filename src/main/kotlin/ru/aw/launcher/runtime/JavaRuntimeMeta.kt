package ru.aw.launcher.runtime

import kotlinx.serialization.Serializable
import ru.aw.launcher.meta.Artifact
import ru.aw.launcher.meta.RuleEnvironment

@Serializable
data class RuntimeEntry(
    val manifest: Artifact? = null,
    val version: RuntimeVersion? = null,
)

@Serializable
data class RuntimeVersion(val name: String = "", val released: String = "")

@Serializable
data class RuntimeManifest(val files: Map<String, RuntimeFile> = emptyMap())

@Serializable
data class RuntimeFile(
    val type: String = "file",
    val executable: Boolean = false,
    val downloads: RuntimeDownloads? = null,
    val target: String? = null,
)

@Serializable
data class RuntimeDownloads(
    val raw: Artifact? = null,
)

object JavaComponents {
    const val LEGACY = "jre-legacy"
    const val ALPHA = "java-runtime-alpha"
    const val GAMMA = "java-runtime-gamma"
    const val DELTA = "java-runtime-delta"

    fun forMajor(major: Int): String = when {
        major >= 21 -> DELTA
        major >= 17 -> GAMMA
        major >= 16 -> ALPHA
        else -> LEGACY
    }
}

object JavaPlatform {

    fun key(env: RuleEnvironment = RuleEnvironment.current): String = when (env.osName) {
        "windows" -> when (env.osArch) {
            "x86" -> "windows-x86"
            "arm64" -> "windows-arm64"
            else -> "windows-x64"
        }
        "osx" -> if (env.osArch == "arm64") "mac-os-arm64" else "mac-os"
        else -> if (env.osArch == "x86") "linux-i386" else "linux"
    }

    fun fallbacks(env: RuleEnvironment = RuleEnvironment.current): List<String> = when (key(env)) {
        "windows-arm64" -> listOf("windows-arm64", "windows-x64", "windows-x86")
        "windows-x64" -> listOf("windows-x64", "windows-x86")
        else -> listOf(key(env))
    }
}
