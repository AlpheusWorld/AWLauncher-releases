package ru.aw.launcher.dev

import kotlinx.coroutines.runBlocking
import ru.aw.launcher.core.Paths
import ru.aw.launcher.update.UpdateState
import ru.aw.launcher.update.Updater
import kotlin.system.exitProcess

/** Explicit integration check against the public signed feed; never runs as a unit test. */
fun main(args: Array<String>) = runBlocking {
    Paths.ensureBaseDirs()
    Updater.check()
    when (val state = Updater.state.value) {
        is UpdateState.Available -> {
            println("Verified update ${state.update.version}: signed manifest, ${state.update.size} bytes")
            if (args.contentEquals(arrayOf("--install"))) {
                if (!Updater.install(state.update)) error("Another update is already running")
                println("Verified installer handed over for installation")
                exitProcess(0)
            }
        }
        UpdateState.UpToDate -> println("Public signed feed verified; launcher is up to date")
        is UpdateState.Failed -> error(state.message)
        else -> error("Update feed is not configured or did not finish checking")
    }
}
