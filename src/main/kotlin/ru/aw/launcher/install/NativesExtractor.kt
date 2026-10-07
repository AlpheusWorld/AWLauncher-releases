package ru.aw.launcher.install

import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.Extract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

data class NativeJar(val path: Path, val extract: Extract?)

object NativesExtractor {

    private const val MARKER = ".aw-natives"

    fun extract(versionId: String, jars: List<NativeJar>, force: Boolean = false): Path {
        val dir = Paths.nativesDir(versionId)
        val marker = dir.resolve(MARKER)
        val fingerprint = jars.joinToString("\n") { "${it.path.fileName}:${sizeOf(it.path)}" }

        if (!force && marker.exists() && runCatching { marker.readText() }.getOrNull() == fingerprint) {
            Log.debug("natives for $versionId up to date")
            return dir
        }

        dir.createDirectories()
        var count = 0
        for (jar in jars) {
            if (!jar.path.exists()) {
                Log.warn("native jar missing, skipping: ${jar.path}")
                continue
            }
            count += unpack(jar, dir)
        }

        dir.resolve(MARKER).writeAtomically(fingerprint)
        Log.info("extracted $count native files for $versionId")
        return dir
    }

    private fun unpack(jar: NativeJar, target: Path): Int {
        var written = 0
        ZipFile(jar.path.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name

                if (name.startsWith("META-INF/")) continue
                if (jar.extract?.exclude?.any { name.startsWith(it) } == true) continue

                val fileName = name.substringAfterLast('/')
                if (fileName.isBlank()) continue

                val dest = target.resolve(fileName)
                if (!dest.normalize().startsWith(target.normalize())) {
                    Log.warn("skipping suspicious native entry: $name")
                    continue
                }

                zip.getInputStream(entry).use { input ->
                    Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
                }
                written++
            }
        }
        return written
    }

    private fun sizeOf(path: Path): Long = runCatching { Files.size(path) }.getOrDefault(-1L)
}
