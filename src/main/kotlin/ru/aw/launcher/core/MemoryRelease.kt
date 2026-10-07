package ru.aw.launcher.core

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object MemoryRelease {

    @Suppress("FunctionName")
    private interface Psapi : StdCallLibrary {
        fun EmptyWorkingSet(process: WinNT.HANDLE): Boolean
    }

    private val psapi: Psapi? by lazy {
        runCatching { Native.load("psapi", Psapi::class.java) }
            .onFailure { Log.warn("psapi unavailable, working set stays as is: ${it.message}") }
            .getOrNull()
    }

    suspend fun afterWindowClosed() = withContext(Dispatchers.IO) {
        delay(1_000)
        System.gc()
        delay(500)
        val trimmed = runCatching { psapi?.EmptyWorkingSet(Kernel32.INSTANCE.GetCurrentProcess()) == true }
            .getOrDefault(false)
        Log.info("memory handed back for the game (working set trimmed: $trimmed)")
    }
}
