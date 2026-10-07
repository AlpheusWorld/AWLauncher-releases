package ru.aw.launcher.install

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.mavenPath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories

internal object LegacyForge {

    class Profile(
        val id: String,
        val json: String,
        val library: String,
        val jarEntry: String,
    )

    fun read(installer: Path): Profile? = ZipFile(installer.toFile()).use { zip ->
        val entry = zip.getEntry("install_profile.json") ?: return null
        val root = Json.parseToJsonElement(zip.getInputStream(entry).bufferedReader().readText()).jsonObject
        val info = root["versionInfo"] as? JsonObject ?: return null
        val install = root["install"] as? JsonObject
            ?: throw IOException("Установщик Forge повреждён: в нём нет раздела install")
        if (info["inheritsFrom"] == null) {
            throw IOException("Эта сборка Forge слишком старая: лаунчер ставит Forge начиная с Minecraft 1.7.10")
        }
        Profile(
            id = info.text("id"),
            json = info.toString(),
            library = install.text("path"),
            jarEntry = install.text("filePath"),
        )
    }

    fun install(profile: Profile, installer: Path): String {
        val jar = Paths.libraryPath(mavenPath(profile.library))
        jar.parent?.createDirectories()
        val part = jar.resolveSibling("${jar.fileName}.part")
        ZipFile(installer.toFile()).use { zip ->
            val entry = zip.getEntry(profile.jarEntry)
                ?: throw IOException("Установщик Forge повреждён: в нём нет ${profile.jarEntry}")
            zip.getInputStream(entry).use { Files.copy(it, part, StandardCopyOption.REPLACE_EXISTING) }
        }
        Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING)
        Paths.versionJson(profile.id).writeAtomically(profile.json)
        return profile.id
    }

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IOException("Установщик Forge повреждён: в профиле нет поля $name")
}
