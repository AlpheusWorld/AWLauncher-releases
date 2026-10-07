package ru.aw.launcher.mods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class GameOptionsTest {

    @Test
    fun `fullscreen override preserves controls video and resource pack settings`(@TempDir game: Path) {
        val file = game.resolve("options.txt")
        file.writeText("fullscreen:true\nlang:ru_ru\nfov:0.5\nresourcePacks:[\"vanilla\"]\n")
        GameOptions.setFullscreen(game, false)
        assertEquals(listOf("fullscreen:false", "lang:ru_ru", "fov:0.5", "resourcePacks:[\"vanilla\"]"), file.readLines())
        GameOptions.setFullscreen(game, true)
        assertEquals(1, file.readLines().count { it.startsWith("fullscreen:") })
    }

    @Test
    fun `a resource pack is switched on and off without losing other settings`(@TempDir game: Path) {
        game.resolve("options.txt").writeText("lang:ru_ru\nresourcePacks:[\"vanilla\",\"fabric\"]\nfov:0.5\n")

        GameOptions.setResourcePack(game, "Fresh Animations.zip", enabled = true)
        assertEquals(listOf("vanilla", "fabric", "file/Fresh Animations.zip"), GameOptions.resourcePacks(game))
        assertTrue(GameOptions.isResourcePackOn(game, "Fresh Animations.zip"))
        assertEquals(listOf("lang:ru_ru", "fov:0.5"), game.resolve("options.txt").readLines().filterNot { it.startsWith("resourcePacks:") })

        GameOptions.setResourcePack(game, "Fresh Animations.zip", enabled = false)
        assertEquals(listOf("vanilla", "fabric"), GameOptions.resourcePacks(game))
    }

    @Test
    fun `a fresh folder gets an options file with just the pack`(@TempDir game: Path) {
        GameOptions.setResourcePack(game, "Faithful.zip", enabled = true)
        assertEquals(listOf("resourcePacks:[\"vanilla\",\"file/Faithful.zip\"]"), game.resolve("options.txt").readLines())
    }

    @Test
    fun `the chosen shader is written for iris and can be turned off`(@TempDir game: Path) {
        assertNull(GameOptions.activeShader(game))
        GameOptions.setShader(game, "ComplementaryReimagined_r5.9.3.zip")
        assertEquals("ComplementaryReimagined_r5.9.3.zip", GameOptions.activeShader(game))
        GameOptions.setShader(game, null)
        assertNull(GameOptions.activeShader(game))
    }

    @Test
    fun `switching and removing packs goes through the game settings`(@TempDir game: Path) {
        val shader = ContentKind.SHADER.dir(game).createDirectories().resolve("BSL.zip").apply { writeText("x") }
        val pack = ContentKind.RESOURCE_PACK.dir(game).createDirectories().resolve("Faithful").apply { writeText("y") }
        fun item(file: Path, kind: ContentKind, enabled: Boolean) = InstalledItem(
            file = file, kind = kind, enabled = enabled, sha1 = "", projectId = null, title = file.fileName.toString(),
            versionNumber = "", iconUrl = null, update = null,
        )

        ModManager.setEnabled(item(shader, ContentKind.SHADER, enabled = false), enabled = true)
        assertEquals("BSL.zip", GameOptions.activeShader(game))
        ModManager.setEnabled(item(pack, ContentKind.RESOURCE_PACK, enabled = false), enabled = true)
        assertTrue(GameOptions.isResourcePackOn(game, "Faithful"))

        ModManager.remove(item(shader, ContentKind.SHADER, enabled = true))
        ModManager.remove(item(pack, ContentKind.RESOURCE_PACK, enabled = true))
        assertFalse(shader.exists())
        assertFalse(pack.exists())
        assertNull(GameOptions.activeShader(game))
        assertFalse(GameOptions.isResourcePackOn(game, "Faithful"))
    }
}
