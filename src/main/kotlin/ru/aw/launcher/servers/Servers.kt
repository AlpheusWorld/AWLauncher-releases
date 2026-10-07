package ru.aw.launcher.servers

data class ServerEntry(
    val name: String,
    val address: String,
    val entryKey: String? = null,
)

object Servers {

    val all: List<ServerEntry> = emptyList()
}
