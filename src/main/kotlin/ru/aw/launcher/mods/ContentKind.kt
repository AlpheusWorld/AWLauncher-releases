package ru.aw.launcher.mods

import java.nio.file.Path

enum class ContentKind(val projectType: String, val folder: String, val extension: String) {
    MOD("mod", "mods", ".jar"),
    SHADER("shader", "shaderpacks", ".zip"),
    RESOURCE_PACK("resourcepack", "resourcepacks", ".zip");

    fun dir(gameDir: Path): Path = gameDir.resolve(folder)

    fun page(slug: String): String = "https://modrinth.com/$projectType/$slug"

    fun safeName(name: String): String? =
        name.takeIf { it.endsWith(extension) && '/' !in it && '\\' !in it && ".." !in it && ':' !in it }
}
