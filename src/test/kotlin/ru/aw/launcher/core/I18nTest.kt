package ru.aw.launcher.core

import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class I18nTest {
    @Test
    fun `UI text changes language and retains dynamic values`() {
        assertEquals("Настройки", I18n.text("Настройки", Language.RU))
        assertEquals("Settings", I18n.text("Настройки", Language.EN))
        assertEquals("Ajustes", I18n.text("Настройки", Language.ES))
        assertEquals("Recommended for this PC: 4 GB", I18n.text("Рекомендуется для этого ПК: 4 ГБ", Language.EN))
        assertEquals("1.5 GB", I18n.text("1,5 ГБ", Language.EN))
        assertEquals("by robotoor · 17.7 million downloads", I18n.text("от robotoor · 17,7 млн скачиваний", Language.EN))
        assertEquals("RECOMENDADA PARA ESTE PC: 4 GB", I18n.text("РЕКОМЕНДУЕТСЯ ДЛЯ ЭТОГО ПК: 4 ГБ", Language.ES).uppercase())
        assertEquals("Fabulously Optimized", I18n.text("Fabulously Optimized", Language.ES))
        assertEquals("15 Sep", I18n.text("15 сен", Language.EN))
    }

    @Test
    fun `all 50 languages have complete dictionaries and preserve placeholders`() {
        fun dictionary(language: String) = Json.decodeFromString<Map<String, String>>(
            javaClass.getResourceAsStream("/i18n/$language.json")!!.bufferedReader().use { it.readText() },
        )
        val english = dictionary("en")
        assertEquals(50, Language.entries.size)
        val placeholders = Regex("\\{\\d+}")
        for (language in Language.entries.filter { it != Language.RU }) {
            val table = dictionary(language.resourceTag)
            assertEquals(english.keys, table.keys, language.tag)
            table.forEach { (source, target) ->
                assertTrue(target.isNotBlank(), "${language.tag}: $source")
                assertEquals(placeholders.findAll(source).map { it.value }.toSet(), placeholders.findAll(target).map { it.value }.toSet(), "${language.tag}: $source")
            }
            assertEquals(table["Настройки"], I18n.text("Настройки", language), language.tag)
        }
    }

    @Test
    fun `English is the default while existing language preferences are retained`() {
        assertEquals(Language.EN, Json.decodeFromString<LauncherSettings>("{\"memoryMb\":4096}").language)
        assertEquals(Language.RU, Json.decodeFromString<LauncherSettings>("{\"language\":\"RU\"}").language)
        val saved = Json.decodeFromString<LauncherSettings>("{\"memoryMb\":3584,\"language\":\"ES\",\"themeMode\":\"OLED\"}")
        assertEquals(3584, saved.memoryMb)
        assertEquals(Language.ES, saved.language)
        assertEquals(ThemeMode.OLED, saved.themeMode)
    }
}
