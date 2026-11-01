package ru.aw.launcher.nativeui

import ru.aw.launcher.core.Shell
import java.nio.file.Files
import java.io.IOException

/** Launch the Tauri shell. Compose remains available only through the explicit reference flag. */
object NativeWindow {
    fun start(args: Array<String>): Boolean {
        if(args.any { it=="--compose-ui" })return false
        val executable=Shell.appExecutable ?: throw IOException("Запусти AWLauncher из собранной папки приложения")
        val directory=if(Shell.isWindows)executable.parent.resolve("app") else executable.parent.parent.resolve("lib/app")
        val ui=directory.resolve(if(Shell.isWindows)"AWLauncher-native.exe" else "awlauncher-native")
        if(!Files.isRegularFile(ui))throw IOException("Не найден интерфейс Tauri. Переустанови AWLauncher")
        val exit=ProcessBuilder(listOf(ui.toAbsolutePath().toString(),"--backend",executable.toAbsolutePath().toString())+args.toList()).inheritIO().start().waitFor()
        if(exit!=0)throw IOException("Интерфейс Tauri завершился с кодом $exit")
        return true
    }
}
