package ru.aw.launcher.core

import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

object ClassArchives {

    fun removeStale() {
        val current = ManagementFactory.getRuntimeMXBean().inputArguments
            .firstOrNull { it.startsWith("-XX:SharedArchiveFile=") }
            ?.substringAfter('=')
            ?.let { Path.of(it) }
            ?: return
        val dir = current.parent ?: return

        dir.listDirectoryEntries("aw*.jsa")
            .filter { it.name != current.name }
            .forEach { stale ->
                runCatching {
                    stale.toFile().setWritable(true)
                    stale.deleteIfExists()
                }
                    .onSuccess { deleted -> if (deleted) Log.info("removed stale class archive ${stale.name}") }
                    .onFailure { Log.warn("could not remove stale class archive ${stale.name}: ${it.message}") }
            }
    }
}
