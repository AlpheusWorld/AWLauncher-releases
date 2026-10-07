package ru.aw.launcher.dev

import kotlinx.coroutines.runBlocking
import ru.aw.launcher.auth.AccountManager
import ru.aw.launcher.core.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    println("Microsoft auth data directory: ${Paths.root}")
    AccountManager.load()
    try {
        if ("--refresh" in args) {
            val account = AccountManager.selected.value ?: error("No selected account to refresh")
            check(!account.isOffline) { "Selected account is offline" }
            val renewed = AccountManager.prepareForLaunch(account.copy(expiresAt = 0)) {
                println("Microsoft refresh stage: $it")
            }
            check(renewed.uuid == account.uuid) { "Refresh returned a different Minecraft profile" }
        } else {
            AccountManager.signInMicrosoft { println("Microsoft auth stage: $it") }
        }
        println("Microsoft sign-in completed; Minecraft profile and licence verified")
    } catch (failure: Exception) {
        println("Microsoft sign-in failed: ${failure.javaClass.simpleName}")
        println(failure.message)
        exitProcess(1)
    }
}
