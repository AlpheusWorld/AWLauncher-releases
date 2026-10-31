package ru.aw.launcher.nativeui

import ru.aw.launcher.core.Shell
import java.nio.file.Files

object NativeWindow {
    fun start(args:Array<String>):Boolean {
        if(args.any{it=="--compose-ui"})return false
        val executable=Shell.appExecutable?:return false
        val directory=if(Shell.isWindows)executable.parent.resolve("app") else executable.parent.parent.resolve("lib/app")
        val ui=directory.resolve(if(Shell.isWindows)"AWLauncher-native.exe" else "awlauncher-native")
        if(!Files.isRegularFile(ui))return false
        ProcessBuilder(listOf(ui.toAbsolutePath().toString(),"--backend",executable.toAbsolutePath().toString())+args.toList()).inheritIO().start().waitFor()
        return true
    }
}
