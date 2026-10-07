package ru.aw.launcher.instance

import org.jetbrains.skia.Image
import ru.aw.launcher.core.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object InstanceImages {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val managedName = Regex("^aw-icon-[0-9a-f]{24}\\.img$")

    fun resolve(gameDir: Path, name: String?): Path? = name?.takeIf { managedName.matches(it) }?.let(gameDir::resolve)

    fun import(gameDir: Path, source: Path): String {
        val bytes = Files.newInputStream(source).use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw IOException("Выбери изображение до 8 МБ")
        try {
            Image.makeFromEncoded(bytes).use { image ->
                if (image.width !in 1..8192 || image.height !in 1..8192 || image.width.toLong() * image.height > 16_000_000)
                    throw IOException("Иконка слишком большая: максимум 16 миллионов пикселей")
            }
        } catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException("Не удалось прочитать изображение: выбери PNG, JPEG или WebP", e) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
        val name = "aw-icon-$hash.img"
        gameDir.resolve(name).writeAtomically(bytes)
        return name
    }
}
