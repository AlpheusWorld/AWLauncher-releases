package ru.aw.launcher.auth

import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

object AuthConfig {

    const val PLACEHOLDER = "SET_YOUR_AZURE_CLIENT_ID"
    private val CLIENT_ID = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")

    private const val BUILT_IN = PLACEHOLDER

    val clientId: String by lazy {
        sequenceOf(
            System.getenv("AW_MS_CLIENT_ID"),
            readFromFile(),
            readBundled(),
            BUILT_IN,
        ).firstOrNull { !it.isNullOrBlank() && it != PLACEHOLDER } ?: PLACEHOLDER
    }

    val isConfigured: Boolean get() = CLIENT_ID.matches(clientId)

    private fun readFromFile(): String? = runCatching {
        val file = Paths.root.resolve("client_id.txt")
        if (!file.exists()) return@runCatching null
        file.readText().trim().takeIf { it.isNotBlank() }
    }.onFailure { Log.debug("client_id.txt unreadable: ${it.message}") }.getOrNull()

    private fun readBundled(): String? = runCatching {
        AuthConfig::class.java.getResourceAsStream("/auth-client-id.txt")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText().trim() }
            ?.takeIf { it.isNotBlank() && it != PLACEHOLDER }
    }.onFailure { Log.debug("bundled Microsoft client ID unreadable: ${it.message}") }.getOrNull()

    val SETUP_HINT: String
        get() = "Лицензионный вход не настроен: AWLauncher нужно зарегистрировать в Microsoft Entra ID.\n" +
            "Для разработки укажи Application (client) ID в AW_MS_CLIENT_ID или в файле " +
            "${Paths.root.resolve("client_id.txt")}. Релиз получает ID при сборке."
}
