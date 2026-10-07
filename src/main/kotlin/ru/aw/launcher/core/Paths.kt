package ru.aw.launcher.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

object Paths {

    val root: Path = Path.of(System.getProperty("user.home")).resolve(".aw")

    val versions: Path = root.resolve("versions")
    val libraries: Path = root.resolve("libraries")
    val assets: Path = root.resolve("assets")
    val assetIndexes: Path = assets.resolve("indexes")
    val assetObjects: Path = assets.resolve("objects")
    val runtimes: Path = root.resolve("runtimes")
    val natives: Path = root.resolve("natives")
    val instances: Path = root.resolve("instances")
    val logs: Path = root.resolve("logs")
    val cache: Path = root.resolve("cache")

    val settingsFile: Path = root.resolve("launcher.json")
    val accountsFile: Path = root.resolve("accounts.json")
    val noticesFile: Path = root.resolve("notifications.json")
    val verifyCacheFile: Path = cache.resolve("verify.index")

    fun versionDir(id: String): Path = versions.resolve(id)
    fun versionJson(id: String): Path = versionDir(id).resolve("$id.json")
    fun versionJar(id: String): Path = versionDir(id).resolve("$id.jar")
    fun nativesDir(id: String): Path = natives.resolve(id)
    fun instanceDir(name: String): Path = instances.resolve(name.sanitized())

    fun libraryPath(relative: String): Path = libraries.resolve(relative)

    fun assetObject(hash: String): Path = assetObjects.resolve(hash.substring(0, 2)).resolve(hash)

    fun ensureBaseDirs() {
        listOf(root, versions, libraries, assetIndexes, assetObjects, runtimes, natives, instances, logs, cache)
            .forEach { it.createDirectories() }
    }

    private val illegal = Regex("""[\\/:*?"<>|]""")

    private fun String.sanitized(): String =
        replace(illegal, "_").replace("..", "_").trim().ifBlank { "default" }
}

fun Path.writeAtomically(bytes: ByteArray) {
    parent?.createDirectories()
    val tmp = resolveSibling("$fileName.tmp")
    Files.write(tmp, bytes)
    try {
        Files.move(tmp, this, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(tmp, this, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

fun Path.writeAtomically(text: String) = writeAtomically(text.toByteArray(Charsets.UTF_8))
