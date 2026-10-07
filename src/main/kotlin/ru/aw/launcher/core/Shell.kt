package ru.aw.launcher.core

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object Shell {

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    val appExecutable: Path? by lazy {
        System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.takeIf { it.exists() }
    }

    fun openFolder(dir: Path, onError: (String) -> Unit) {
        runCatching { dir.createDirectories() }
            .onFailure {
                onError("Не удалось создать папку $dir: ${it.message}")
                return
            }

        val path = dir.toAbsolutePath().toString()
        if (isWindows) {
            val started = runCatching { ProcessBuilder("explorer.exe", path).start() }
                .onFailure { Log.warn("explorer.exe failed: ${it.message}") }
                .isSuccess
            if (started) return
        }

        val viaDesktop = runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) return@runCatching false
            desktop.open(dir.toFile())
            true
        }.onFailure { Log.warn("Desktop.open failed: ${it.message}") }.getOrDefault(false)

        if (!viaDesktop) onError("Не удалось открыть проводник. Папка: $dir")
    }

    fun browse(url: String): Boolean {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return true
            }
        }.onFailure { Log.debug("Desktop.browse failed: ${it.message}") }
        return runCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start() }.isSuccess
    }

    private const val CREATE_NO_WINDOW = 0x08000000

    fun runHidden(commandLine: String, waitMs: Int = 30_000): Int? {
        if (!isWindows) return null
        return runCatching {
            val startup = WinBase.STARTUPINFO()
            val info = WinBase.PROCESS_INFORMATION()
            val created = Kernel32.INSTANCE.CreateProcess(
                null, commandLine, null, null, false,
                WinDef.DWORD(CREATE_NO_WINDOW.toLong()), null, null, startup, info,
            )
            if (!created) {
                Log.warn("CreateProcess failed with ${Kernel32.INSTANCE.GetLastError()}")
                return@runCatching null
            }
            try {
                if (waitMs <= 0) return@runCatching 0
                if (Kernel32.INSTANCE.WaitForSingleObject(info.hProcess, waitMs) != WinBase.WAIT_OBJECT_0) {
                    Log.warn("hidden process did not finish within $waitMs ms")
                    return@runCatching null
                }
                val exit = IntByReference()
                Kernel32.INSTANCE.GetExitCodeProcess(info.hProcess, exit)
                exit.value
            } finally {
                Kernel32.INSTANCE.CloseHandle(info.hThread)
                Kernel32.INSTANCE.CloseHandle(info.hProcess)
            }
        }.onFailure { Log.warn("hidden process failed: ${it.message}") }.getOrNull()
    }

    fun powershell(script: String): String {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded"
    }

    fun psLiteral(value: String): String = "'" + value.replace("'", "''") + "'"
}
