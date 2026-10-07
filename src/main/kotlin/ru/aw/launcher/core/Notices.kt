package ru.aw.launcher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
enum class NoticeLevel { ERROR, WARNING, INFO, SUCCESS }

@Serializable
enum class NoticeAction { LOGS, MODS, FIX_MODS, PLAY_ANYWAY }

@Serializable
data class Notice(
    val id: Long,
    val level: NoticeLevel,
    val text: String,
    val title: String? = null,
    val at: Long = id,
    val entryKey: String? = null,
    val entryLabel: String? = null,
    val actions: List<NoticeAction> = emptyList(),
    val count: Int = 1,
)

object Notices {

    const val LIMIT = 60
    private const val REPEAT_WINDOW_MILLIS = 24L * 60 * 60 * 1000

    @Serializable
    private data class Stored(val seen: Long = 0, val items: List<Notice> = emptyList())

    private val _items = MutableStateFlow<List<Notice>>(emptyList())
    val items: StateFlow<List<Notice>> = _items.asStateFlow()

    private val _seen = MutableStateFlow(0L)
    val seen: StateFlow<Long> = _seen.asStateFlow()

    internal var file: Path? = Paths.noticesFile

    fun load() {
        val target = file ?: return
        if (!target.exists()) return
        runCatching { Json.decodeFromString<Stored>(target.readText()) }
            .onSuccess {
                _items.value = it.items.sortedByDescending(Notice::at).take(LIMIT)
                _seen.value = it.seen
            }
            .onFailure { Log.warn("notifications.json unreadable, starting empty", it) }
    }

    @Synchronized
    fun post(
        level: NoticeLevel,
        text: String,
        title: String? = null,
        entryKey: String? = null,
        entryLabel: String? = null,
        actions: List<NoticeAction> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): Pair<Notice, Boolean> {
        val current = _items.value
        val repeat = current.take(10).firstOrNull {
            it.text == text && it.title == title && it.entryKey == entryKey && now - it.at in 0..REPEAT_WINDOW_MILLIS
        }
        val id = maxOf(now, (current.maxOfOrNull(Notice::id) ?: 0) + 1)
        val notice = repeat?.copy(id = id, at = now, count = repeat.count + 1, actions = actions)
            ?: Notice(id, level, text, title, now, entryKey, entryLabel, actions)
        _items.value = (listOf(notice) + current.filter { it.id != repeat?.id }).take(LIMIT)
        save()
        return notice to (repeat != null)
    }

    @Synchronized
    fun remove(id: Long) {
        _items.value = _items.value.filter { it.id != id }
        save()
    }

    @Synchronized
    fun resolve(id: Long) {
        _items.value = _items.value.map { if (it.id == id) it.copy(actions = emptyList()) else it }
        save()
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
        save()
    }

    @Synchronized
    fun markSeen() {
        val latest = _items.value.maxOfOrNull(Notice::id) ?: return
        if (latest <= _seen.value) return
        _seen.value = latest
        save()
    }

    fun unread(items: List<Notice>, seen: Long): Int = items.count { it.id > seen }

    private fun save() {
        val target = file ?: return
        runCatching { target.writeAtomically(PrettyJson.encodeToString(Stored(_seen.value, _items.value))) }
            .onFailure { Log.warn("could not save notifications", it) }
    }
}
