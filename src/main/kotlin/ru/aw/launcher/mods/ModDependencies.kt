package ru.aw.launcher.mods

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Resolves the catalog graph before any live files are replaced. */
internal class ModDependencies(
    private val loaders: List<String>,
    private val gameVersion: String,
    private val versions: (String) -> List<Modrinth.Version>,
    private val version: (String) -> Modrinth.Version,
    private val project: (String) -> Modrinth.Project,
) {
    private val byId = HashMap<String, Modrinth.Version>()
    private val byProject = HashMap<String, List<Modrinth.Version>>()
    private val projects = HashMap<String, Modrinth.Project>()

    fun detail(id: String): Modrinth.Version = byId.getOrPut(id) {
        lookup(id) { version(id) }.also { if (it.id != id) throw IOException("Каталог вернул другой выпуск мода") }
    }

    fun candidates(id: String): List<Modrinth.Version> = byProject.getOrPut(id) {
        lookup(id) { versions(id) }.filter { it.projectId == id && compatible(it) }.also { list -> list.forEach { byId[it.id] = it } }
            .sortedBy { when (it.versionType) { "release" -> 0; "beta" -> 1; else -> 2 } }
    }

    fun title(id: String): String = projects[id]?.title?.takeIf { it.isNotBlank() } ?: id

    private fun <T> lookup(id: String, block: () -> T): T = try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { throw IOException("Не удалось проверить зависимость $id в каталоге ${ContentCatalog.source(id).label}", e) }

    fun compatible(value: Modrinth.Version): Boolean = gameVersion in value.gameVersions &&
        value.loaders.any { it in loaders } && value.primaryFile?.let { ContentKind.MOD.safeName(it.filename) != null } == true

    suspend fun resolve(roots: List<Modrinth.Version>, installed: List<InstalledItem>,
                        preferred: Map<String, Modrinth.Version> = emptyMap()): List<Modrinth.Version> {
        val fixed = roots.associateBy { it.projectId }
        if (fixed.size != roots.size) throw IOException("Выбрано несколько выпусков одного мода")
        roots.forEach {
            if (!compatible(it)) throw IOException("${it.name.ifBlank { it.projectId }} несовместим с этой версией Minecraft и загрузчиком")
            byId[it.id] = it
        }
        var previousPins = emptyMap<String, String>()
        repeat(32) {
            currentCoroutineContext().ensureActive()
            val chosen = LinkedHashMap(fixed)
            val pins = linkedMapOf<String, MutableSet<String>>()
            val queue = ArrayDeque(roots)
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val parent = queue.removeFirst()
                for (dependency in parent.dependencies.filter { it.type == "required" }) {
                    val pinned = dependency.versionId?.let(::detail)
                    val id = dependency.projectId ?: pinned?.projectId
                        ?: throw IOException("У ${parent.name.ifBlank { parent.projectId }} не указана ссылка на обязательный мод")
                    if (pinned != null && pinned.projectId != id) throw IOException("Обязательная зависимость относится к другому моду")
                    if (pinned != null) {
                        if (!compatible(pinned)) throw IOException("${parent.name.ifBlank { parent.projectId }} требует выпуск ${pinned.versionNumber.ifBlank { pinned.id }}, несовместимый с этой сборкой")
                        pins.getOrPut(id) { linkedSetOf() } += pinned.id
                    } else if (!CurseForge.owns(id)) {
                        val info = projects.getOrPut(id) { lookup(id) { project(id) } }
                        // Some universal releases list dependencies for other loaders too.
                        if (!ModManager.dependencyApplies(info.loaders, loaders)) continue
                    }
                    if (id in chosen) continue
                    if (chosen.size >= 256) throw IOException("У мода слишком много связанных зависимостей")
                    val desired = previousPins[id]?.let(::detail) ?: pinned ?: preferred[id]
                    val existing = if (desired == null) installed.filter { it.projectId == id && it.versionId != null }
                        .sortedByDescending { it.enabled }.firstNotNullOfOrNull { item -> detail(item.versionId!!).takeIf(::compatible) } else null
                    val selected = desired ?: existing ?: Modrinth.pick(candidates(id))
                        ?: throw IOException("Для обязательного мода ${title(id)} нет подходящего выпуска под Minecraft $gameVersion")
                    if (selected.projectId != id || !compatible(selected)) throw IOException("Для обязательного мода ${title(id)} выбран несовместимый выпуск")
                    chosen[id] = selected
                    queue += selected
                }
            }
            val mismatches = pins.filter { (id, ids) -> ids.size == 1 && chosen[id]?.id != ids.single() }
            if (mismatches.keys.any { it in fixed }) throw IOException("Выбранный выпуск мода не подходит обязательной зависимости другого мода")
            if (mismatches.isNotEmpty()) {
                previousPins = pins.filterValues { it.size == 1 }.mapValues { it.value.single() }
                return@repeat
            }
            if (pins.any { it.value.size > 1 }) throw IOException("Моды требуют разные выпуски одной зависимости — вместе их установить нельзя")
            return chosen.values.toList()
        }
        throw IOException("Не удалось согласовать обязательные зависимости модов")
    }
}
