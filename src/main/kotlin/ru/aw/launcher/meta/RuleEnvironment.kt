package ru.aw.launcher.meta

data class RuleEnvironment(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val features: Map<String, Boolean> = emptyMap(),
) {
    val archBits: String get() = if (osArch == "x86") "32" else "64"

    fun withFeatures(vararg pairs: Pair<String, Boolean>): RuleEnvironment =
        copy(features = features + pairs.toMap())

    companion object {
        val current: RuleEnvironment by lazy {
            RuleEnvironment(
                osName = detectOsName(),
                osVersion = System.getProperty("os.version").orEmpty(),
                osArch = detectArch(),
            )
        }

        private fun detectOsName(): String {
            val raw = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                raw.contains("win") -> "windows"
                raw.contains("mac") || raw.contains("darwin") -> "osx"
                else -> "linux"
            }
        }

        private fun detectArch(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "x86", "i386", "i486", "i586", "i686" -> "x86"
            "aarch64", "arm64" -> "arm64"
            "arm", "arm32" -> "arm32"
            else -> "x86_64"
        }
    }
}

fun mavenPath(coordinates: String): String {
    val (coords, extension) = coordinates.split('@', limit = 2)
        .let { it[0] to (it.getOrNull(1) ?: "jar") }
    val parts = coords.split(':')
    require(parts.size >= 3) { "malformed maven coordinates: $coordinates" }

    val group = parts[0].replace('.', '/')
    val artifact = parts[1]
    val version = parts[2]
    val classifier = parts.getOrNull(3)

    val fileName = buildString {
        append(artifact).append('-').append(version)
        if (classifier != null) append('-').append(classifier)
        append('.').append(extension)
    }
    return "$group/$artifact/$version/$fileName"
}
