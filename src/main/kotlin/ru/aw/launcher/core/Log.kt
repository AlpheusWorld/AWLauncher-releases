package ru.aw.launcher.core

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries

object Log {

    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val fileStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    @Volatile private var writer: PrintWriter? = null

    @Volatile var currentFile: Path? = null
        private set

    fun init() {
        runCatching {
            Paths.logs.createDirectories()
            pruneOldLogs()
            val file = Paths.logs.resolve("launcher_${LocalDateTime.now().format(fileStamp)}.log")
            writer = PrintWriter(
                Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                true,
            )
            currentFile = file
        }
    }

    private fun pruneOldLogs() {
        runCatching {
            Paths.logs.listDirectoryEntries("launcher_*.log")
                .sortedDescending()
                .drop(9)
                .forEach { it.deleteIfExists() }
        }
    }

    fun info(msg: String) = write("INFO ", msg, null)
    fun warn(msg: String, t: Throwable? = null) = write("WARN ", msg, t)
    fun error(msg: String, t: Throwable? = null) = write("ERROR", msg, t)
    fun debug(msg: String) { if (DEBUG) write("DEBUG", msg, null) }

    private val DEBUG = System.getenv("AW_DEBUG") != null

    private fun write(level: String, msg: String, t: Throwable?) {
        val line = "${LocalDateTime.now().format(stamp)} [$level] $msg"
        println(line)
        t?.printStackTrace()
        writer?.let { w ->
            synchronized(w) {
                w.println(line)
                t?.printStackTrace(w)
            }
        }
    }

    fun close() {
        writer?.close()
        writer = null
    }
}
