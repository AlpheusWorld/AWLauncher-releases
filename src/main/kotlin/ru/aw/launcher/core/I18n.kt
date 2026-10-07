package ru.aw.launcher.core

import kotlinx.serialization.decodeFromString
import java.util.Locale

object I18n {
    private class Registry(val entries: List<Translation>) {
        val exact = entries.associate { it.source to it.target }
        val folded = entries.filter { it.slots.isEmpty() }.associate { it.source.lowercase(Locale.ROOT) to it.target }
        val patterns = entries.filter { it.pattern != null }
        val fragments = entries.filter { it.slots.isEmpty() && it.source.length > 3 }
    }

    private val cache = object : LinkedHashMap<Pair<Language, String>, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Language, String>, String>?) = size > 512
    }
    private class Translation(val source: String, val target: String) {
        val slots = Regex("\\{(\\d+)}").findAll(source).map { it.groupValues[1] }.toList()
        val literal = source.split(Regex("\\{\\d+}")).maxByOrNull { it.length }.orEmpty()
        val pattern = if (slots.isEmpty()) null else Regex(
            source.split(Regex("\\{\\d+}")).joinToString("(.*?)") { Regex.escape(it) },
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    }

    private val translations = object : LinkedHashMap<Language, Registry>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Language, Registry>?) = size > 4
    }
    private fun registry(language: Language): Registry = synchronized(translations) {
        translations.getOrPut(language) {
            val text = I18n::class.java.getResourceAsStream("/i18n/${language.resourceTag}.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Missing translations: ${language.tag}")
            Registry(Json.decodeFromString<Map<String, String>>(text).map { (source, target) -> Translation(source, target) }
                .sortedByDescending { it.source.replace(Regex("\\{\\d+}"), "").length })
        }
    }

    fun text(value: String, language: Language = Settings.current.language): String {
        if (language == Language.RU || value.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return value
        val key = language to value
        synchronized(cache) { cache[key] }?.let { return it }
        return translate(value, language, 0).also { synchronized(cache) { cache[key] = it } }
    }

    private fun translate(value: String, language: Language, depth: Int): String {
        if (language == Language.RU || value.isBlank() || depth > 3) return value
        if ((language == Language.EN || language == Language.EN_GB) && value.matches(Regex("\\d+,\\d+"))) return value.replace(',', '.')
        val registry = registry(language)
        registry.exact[value]?.let { return it }
        registry.folded[value.lowercase(Locale.ROOT)]?.let {
            return if (value == value.uppercase(Locale.ROOT)) it.uppercase(Locale.forLanguageTag(language.tag)) else it
        }
        for (entry in registry.patterns) {
            if (!value.contains(entry.literal, ignoreCase = true)) continue
            val match = entry.pattern?.matchEntire(value) ?: continue
            val translated = Regex("\\{(\\d+)}").replace(entry.target) { slot ->
                val index = entry.slots.indexOf(slot.groupValues[1]) + 1
                if (index <= 0) slot.value else translate(match.groupValues[index], language, depth + 1)
            }
            return if (value == value.uppercase(Locale.ROOT)) translated.uppercase(Locale.forLanguageTag(language.tag)) else translated
        }
        var result = value
        for (entry in registry.fragments) {
            if (entry.source in result) result = result.replace(entry.source, entry.target)
        }
        return if (result == value && language != Language.EN) translate(value, Language.EN, depth + 1) else result
    }
}
