package ru.aw.launcher.logs

import ru.aw.launcher.meta.LoaderKind
import java.io.RandomAccessFile
import java.nio.file.Path

object CrashHints {

    private const val TAIL_BYTES = 256 * 1024

    fun explain(logFile: Path?, loader: LoaderKind, versionId: String, exitCode: Int): String {
        val log = logFile?.let(::tail).orEmpty()
        val reason = reason(log, loader, versionId)
        return if (reason != null) "Игра закрылась с ошибкой. $reason"
        else "Игра закрылась с ошибкой (код $exitCode). Подробности — в логах."
    }

    fun detail(logFile: Path?, loader: LoaderKind, versionId: String, exitCode: Int): String =
        reason(logFile?.let(::tail).orEmpty(), loader, versionId) ?: "Код выхода $exitCode. Подробности — в логах."

    internal fun reason(log: String, loader: LoaderKind, versionId: String): String? = when {
        "Unsupported class file major version" in log && loader.isModded ->
            "${loader.label} пока не поддерживает Minecraft $versionId. " +
                (if (loader == LoaderKind.QUILT) "Выбери для этой версии Fabric." else "Попробуй другую версию или модлоадер.")
        "Could not reserve enough space" in log || "Invalid maximum heap size" in log ->
            "Java не смогла выделить столько памяти. Уменьши память в настройках сборки."
        "OutOfMemoryError" in log ->
            "Игре не хватило памяти. Добавь памяти в настройках сборки."
        "Incompatible mods found" in log || "Incompatible mod set" in log || "ModResolutionException" in log ->
            "Моды несовместимы между собой или с этой версией игры. Список проблем — в логах."
        "Mixin apply failed" in log || "MixinApplyError" in log || "InvalidInjectionException" in log ->
            "Один из модов не подходит к этой версии игры. Выключи недавно добавленные моды."
        "Missing or unsupported mandatory dependencies" in log || "requires any version of" in log ->
            "Какому-то моду не хватает зависимости. Найди её в каталоге модов — названия есть в логах."
        else -> null
    }

    private fun tail(file: Path): String? = runCatching {
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()
}
