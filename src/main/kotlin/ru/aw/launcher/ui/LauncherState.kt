package ru.aw.launcher.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.aw.launcher.activity.ActivityStats
import ru.aw.launcher.activity.PlayHistory
import ru.aw.launcher.auth.Account
import ru.aw.launcher.auth.AccountManager
import ru.aw.launcher.auth.MicrosoftLoginCode
import ru.aw.launcher.auth.SkinImage
import ru.aw.launcher.auth.SkinModel
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Notice
import ru.aw.launcher.core.NoticeAction
import ru.aw.launcher.core.NoticeLevel
import ru.aw.launcher.core.Notices
import ru.aw.launcher.core.PlayRequest
import ru.aw.launcher.core.PreloadResult
import ru.aw.launcher.core.Preloader
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.Shell
import ru.aw.launcher.core.Shortcuts
import ru.aw.launcher.core.Storage
import ru.aw.launcher.core.VerifyCache
import ru.aw.launcher.discord.DiscordPresence
import ru.aw.launcher.discord.GameEvent
import ru.aw.launcher.discord.GameEvents
import ru.aw.launcher.discord.Presence
import ru.aw.launcher.instance.InstanceOptions
import ru.aw.launcher.instance.InstanceStore
import ru.aw.launcher.instance.LocalBuild
import ru.aw.launcher.instance.LocalBuilds
import ru.aw.launcher.launch.GameLauncher
import ru.aw.launcher.logs.CrashHints
import ru.aw.launcher.logs.LogSource
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.meta.LoaderSupport
import ru.aw.launcher.meta.ManifestVersion
import ru.aw.launcher.meta.VersionKind
import ru.aw.launcher.meta.VersionManifest
import ru.aw.launcher.mods.ModCompat
import ru.aw.launcher.mods.ModManager
import ru.aw.launcher.mods.ContentCatalog
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.packs.Modpack
import ru.aw.launcher.packs.Modpacks
import ru.aw.launcher.packs.PackSource
import ru.aw.launcher.servers.ServerEntry
import ru.aw.launcher.servers.ServerPing
import ru.aw.launcher.servers.ServerStatus
import ru.aw.launcher.servers.Servers
import ru.aw.launcher.update.UpdateManifest
import ru.aw.launcher.update.Updater

enum class Screen { HOME, PLAY, BUILDS, CATALOG, INSTANCE, SCREENSHOTS, ACTIVITY, NOTICES, SETTINGS, ACCOUNTS }

data class VersionEntry(
    val version: ManifestVersion,
    val loader: LoaderKind,
    val pack: Modpack? = null,
    val build: LocalBuild? = null,
) {
    val id: String get() = version.id
    val key: String get() = when {
        build != null -> BUILD_KEY + build.id
        pack != null -> PACK_KEY + pack.id
        else -> "${version.id}#${loader.name}"
    }
    val title: String get() = build?.name ?: pack?.title ?: id
    val label: String get() = build?.name ?: pack?.title ?: if (loader.isModded) "$id ${loader.label}" else id
}

private const val PACK_KEY = "pack:"
private const val BUILD_KEY = "build:"
const val PACKS_GROUP = "Сборки"

data class VersionGroup(
    val key: String,
    val entries: List<VersionEntry>,
)

enum class CatalogTab { PACKS, MODS, SHADERS, RESOURCE_PACKS, INSTALLED }

sealed interface Modal {
    data class Delete(val entry: VersionEntry) : Modal
    data class Logs(val gameDir: Path?, val title: String, val source: LogSource) : Modal
    data class Icon(val entry: VersionEntry) : Modal
    data class Groups(val entryKeys: List<String> = emptyList()) : Modal
    data class Duplicate(val entry: VersionEntry) : Modal
    data class DeleteMany(val entries: List<VersionEntry>) : Modal
    data class ImportDirectory(val directory: Path) : Modal
    data class Settings(val entry: VersionEntry? = null, val section: Int = 0) : Modal
    data class Skin(val accountUuid: String) : Modal
}

sealed interface PingState {
    data object Pinging : PingState
    data object Offline : PingState
    class Online(val status: ServerStatus) : PingState
}

class LauncherState(
    private val scope: CoroutineScope,
    preloaded: PreloadResult? = null,
    playRequest: PlayRequest? = null,
) {

    private var visibleScreen by mutableStateOf(Screen.HOME)
    private var previousScreens by mutableStateOf<List<Screen>>(emptyList())
    private var nextScreens by mutableStateOf<List<Screen>>(emptyList())
    var screen: Screen
        get() = visibleScreen
        set(value) {
            if (value == Screen.SETTINGS) { openSettings(); return }
            if (value == visibleScreen) return
            previousScreens = (previousScreens + visibleScreen).takeLast(32)
            nextScreens = emptyList()
            visibleScreen = value
        }
    var catalogPageTitle by mutableStateOf<String?>(null)

    fun openSettings(entry: VersionEntry? = null, section: Int = 0) {
        entry?.let(::selectEntry)
        modal = Modal.Settings(entry, section)
    }
    var catalogBackAction by mutableStateOf<(() -> Unit)?>(null)
    val canNavigateBack: Boolean get() = previousScreens.isNotEmpty() || (screen == Screen.CATALOG && catalogBackAction != null)
    val canNavigateForward: Boolean get() = nextScreens.isNotEmpty()

    fun navigateBack() {
        if (screen == Screen.CATALOG) catalogBackAction?.let {
            catalogBackAction = null
            catalogPageTitle = null
            it()
            return
        }
        val previous = previousScreens.lastOrNull() ?: return
        nextScreens = nextScreens + visibleScreen
        previousScreens = previousScreens.dropLast(1)
        visibleScreen = previous
    }

    fun navigateForward() {
        val next = nextScreens.lastOrNull() ?: return
        previousScreens = (previousScreens + visibleScreen).takeLast(32)
        nextScreens = nextScreens.dropLast(1)
        visibleScreen = next
    }
    var versions by mutableStateOf<List<ManifestVersion>>(emptyList())
    var selectedVersionId by mutableStateOf(Settings.current.lastVersionId)
    var searchQuery by mutableStateOf("")
    var onlyInstalled by mutableStateOf(Settings.current.onlyInstalled)
        private set

    var installed by mutableStateOf<Map<String, Long>>(emptyMap())

    var profiles by mutableStateOf<Set<String>>(emptySet())
    var expandedGroups by mutableStateOf<Set<String>>(emptySet())

    var selectedLoader by mutableStateOf(LoaderKind.VANILLA)

    var selectedPackId by mutableStateOf<String?>(null)
        private set

    var selectedBuildId by mutableStateOf<String?>(null)
        private set

    var packs by mutableStateOf<List<Modpack>>(emptyList())
        private set

    var builds by mutableStateOf<List<LocalBuild>>(emptyList())
        private set

    var buildsBusy by mutableStateOf(false)
        private set
    var savingInstanceSettings by mutableStateOf(false)
        private set
    var libraryBusy by mutableStateOf(false)
        private set
    var libraryLoading by mutableStateOf(false)
        private set
    var libraryError by mutableStateOf<String?>(null)
        private set

    fun editInstanceIcon(entry: VersionEntry) { libraryError = null; modal = Modal.Icon(entry) }

    var packUpdates by mutableStateOf<Map<String, PackSource>>(emptyMap())
        private set

    var installingPack by mutableStateOf<String?>(null)
        private set

    var catalogTab by mutableStateOf(CatalogTab.PACKS)
    var catalogSource by mutableStateOf(ru.aw.launcher.mods.CatalogSource.MODRINTH)
    var catalogTarget by mutableStateOf<VersionEntry?>(null)
    var catalogQuery by mutableStateOf("")
    var catalogProject by mutableStateOf<ru.aw.launcher.mods.Modrinth.SearchHit?>(null)
    var catalogProjectTab by mutableStateOf(0)
    var instanceKey by mutableStateOf<String?>(null)
    private val contentModels = LinkedHashMap<String, ContentModel>()
    private val packModels = HashMap<ru.aw.launcher.mods.CatalogSource, ru.aw.launcher.ui.screens.PacksModel>()

    internal fun packsModel(source: ru.aw.launcher.mods.CatalogSource) =
        packModels.getOrPut(source) { ru.aw.launcher.ui.screens.PacksModel(scope, source) }

    internal fun contentModel(entry: VersionEntry, source: ru.aw.launcher.mods.CatalogSource = catalogSource): ContentModel {
        val key = entry.key
        return contentModels.getOrPut(key) {
            ContentModel(gameDirOf(entry), entry.loader, entry.id, scope,
                onChanged = { modsChanged(entry) }, source = source)
        }.also {
            it.useSource(source)
            if (contentModels.size > 8) contentModels.entries.firstOrNull { old -> old.key != key && old.value.working.isEmpty() && !old.value.refreshing }
                ?.let { old -> contentModels.remove(old.key) }
        }
    }

    fun openInstance(entry: VersionEntry) {
        selectEntry(entry)
        instanceKey = entry.key
        screen = Screen.INSTANCE
    }

    fun instanceEntry(): VersionEntry? = instanceKey?.let(::entryByKey)

    var loaderSupport by mutableStateOf(LoaderSupport())

    var selectedOptions by mutableStateOf(InstanceOptions())
        private set

    var instanceRevision by mutableStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set
    var stage by mutableStateOf("")
        private set
    var progress by mutableStateOf<DownloadProgress?>(null)
        private set

    var busyEntry by mutableStateOf<VersionEntry?>(null)
        private set

    var signingIn by mutableStateOf(false)
        private set
    var signInStage by mutableStateOf("")
        private set
    var signInCode by mutableStateOf<MicrosoftLoginCode?>(null)
        private set
    private var signInJob: Job? = null

    var toast by mutableStateOf<Notice?>(null)
        private set

    val notices = Notices.items
    val noticesSeen = Notices.seen

    var seenBeforeOpen by mutableStateOf(0L)
        private set

    var activity by mutableStateOf<ActivityStats?>(null)
        private set
    private val activityLock = Mutex()
    private var activityLoadedAt = 0L

    var modal by mutableStateOf<Modal?>(null)

    var openMenus by mutableStateOf(0)
        private set

    val searchFocus = FocusRequester()
    var searchFocused by mutableStateOf(false)

    var keyboardMoves by mutableStateOf(0)
        private set

    var serverStatus by mutableStateOf<Map<String, PingState>>(emptyMap())
        private set

    val listState = LazyListState()

    var onGameStarted: (Process) -> Unit = {}
    private val runningGames = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()

    var onQuit: () -> Unit = {}

    private var lastLaunch: Pair<VersionEntry, Path>? = null

    private var job: Job? = null
    private var jobToken: Any? = null
    private var pendingPlay: Boolean = false

    val accounts = AccountManager.accounts
    val selectedAccount = AccountManager.selected
    val servers = Servers.all
    val updates = Updater.state

    init {
        preloaded?.let {
            adopt(it)
            val refreshVersions = it.manifestStale || it.manifest.versions.isEmpty()
            if (refreshVersions || it.loaderSupportStale) {
                refreshInBackground(refreshVersions, it.loaderSupportStale)
            }
        }
        playRequest?.let { request ->
            val entry = requested(request)
            if (entry != null) {
                selectEntry(entry)
                pendingPlay = true
            }
        }
    }

    private fun adopt(preloaded: PreloadResult) {
        versions = preloaded.manifest.versions
        installed = preloaded.installed
        profiles = preloaded.profiles
        loaderSupport = preloaded.loaderSupport
        packs = preloaded.packs
        builds = preloaded.builds
        selectStartingEntry(preloaded.manifest.latest.release)
        expandedGroups = defaultExpanded()
        if (versions.isEmpty()) fail(NO_VERSIONS)
        if (packs.isNotEmpty()) checkPackUpdates()
    }

    private fun defaultExpanded(): Set<String> =
        setOfNotNull(PACKS_GROUP, groups().firstOrNull { it.key != PACKS_GROUP }?.key)

    private fun refreshInBackground(refreshVersions: Boolean, refreshLoaders: Boolean) {
        scope.launch {
            val manifest = if (refreshVersions) async { Preloader.refreshManifest() } else null
            val support = if (refreshLoaders) async { Preloader.refreshLoaderSupport() } else null

            val versionsOutcome = when (manifest) {
                null -> "skipped"
                else -> manifest.await()?.let { if (applyManifest(it)) "updated" else "unchanged" } ?: "unreachable"
            }
            val loadersOutcome = when (support) {
                null -> "skipped"
                else -> {
                    val fresh = support.await()
                    if (fresh != loaderSupport) {
                        loaderSupport = fresh
                        "updated"
                    } else {
                        "unchanged"
                    }
                }
            }
            Log.info("background refresh: versions $versionsOutcome, loaders $loadersOutcome")
        }
    }

    private fun applyManifest(fresh: VersionManifest): Boolean {
        if (fresh.versions.isEmpty() || fresh.versions == versions) return false
        val wasEmpty = versions.isEmpty()
        versions = fresh.versions
        if (wasEmpty) {
            selectStartingEntry(fresh.latest.release)
            expandedGroups = defaultExpanded()
            toast?.takeIf { it.text == NO_VERSIONS }?.let { dismissToast(it.id) }
        }
        return true
    }

    private fun selectStartingEntry(latestRelease: String) {
        Settings.current.lastBuildId?.let { id -> builds.firstOrNull { it.id == id } }?.let { build ->
            selectEntry(entryFor(build))
            return
        }
        Settings.current.lastPack?.let { id -> packs.firstOrNull { it.id == id } }?.let { pack ->
            selectEntry(entryFor(pack))
            return
        }
        val remembered = selectedVersionId
        val rememberedLoader = LoaderKind.entries.firstOrNull { it.name == Settings.current.lastLoader }
            ?: LoaderKind.VANILLA

        val (version, loader) = when {
            remembered != null && isInstalled(remembered, rememberedLoader) -> remembered to rememberedLoader
            remembered != null && isInstalled(remembered) -> remembered to LoaderKind.VANILLA
            else -> {
                val fallback = versions.firstOrNull { isInstalled(it.id) }?.id
                    ?: latestRelease.takeIf { it.isNotBlank() }
                    ?: versions.firstOrNull { it.kind == VersionKind.RELEASE }?.id
                    ?: versions.firstOrNull()?.id
                    ?: remembered
                fallback to LoaderKind.VANILLA
            }
        }
        selectedVersionId = version
        selectedLoader = loader
        selectedPackId = null
        refreshSelectedOptions()
    }

    fun refreshInstalled() {
        scope.launch {
            val (jars, found) = withContext(Dispatchers.IO) { Preloader.scanInstalled() }
            installed = jars
            profiles = found
        }
    }

    fun isInstalled(id: String?, loader: LoaderKind = LoaderKind.VANILLA): Boolean = when {
        id == null -> false
        loader == LoaderKind.VANILLA -> installed.containsKey(id)
        else -> profiles.any { loader.ownsProfile(it, id) }
    }

    fun isEntryInstalled(entry: VersionEntry): Boolean = isInstalled(entry.id, entry.loader)

    private fun visibleVersions(): List<ManifestVersion> {
        val query = searchQuery.trim().lowercase()
        return versions.asSequence()
            .filter(::isVersionVisible)
            .filter { query.isEmpty() || it.id.lowercase().contains(query) }
            .toList()
    }

    fun selectableVersions(query: String = ""): List<ManifestVersion> = versions.filter { version ->
        isVersionVisible(version) && (query.isBlank() || version.id.contains(query.trim(), ignoreCase = true))
    }

    fun isVersionVisible(version: ManifestVersion): Boolean = when (version.kind) {
        VersionKind.RELEASE -> true
        VersionKind.SNAPSHOT -> Settings.current.showSnapshots
        VersionKind.OLD_BETA, VersionKind.OLD_ALPHA, VersionKind.OTHER -> Settings.current.showOldVersions
    }

    fun isVersionVisible(id: String): Boolean = versions.firstOrNull { it.id == id }?.let(::isVersionVisible) ?: true

    private var groupsCache: Pair<List<Any?>, List<VersionGroup>>? = null

    fun groups(): List<VersionGroup> {
        val settings = Settings.current
        val inputs = listOf(
            versions, searchQuery, onlyInstalled, packs, loaderSupport, installed, profiles,
            settings.showSnapshots, settings.showOldVersions,
        )
        groupsCache?.takeIf { it.first == inputs }?.let { return it.second }
        return buildGroups().also { groupsCache = inputs to it }
    }

    private fun buildGroups(): List<VersionGroup> {
        val query = searchQuery.trim().lowercase()
        val packEntries = packs
            .filter { query.isEmpty() || query in it.title.lowercase() || query in it.gameVersion.lowercase() }
            .map(::entryFor)
        val versionGroups = visibleVersions()
            .groupBy { groupKeyOf(it) }
            .map { (key, items) ->
                VersionGroup(
                    key = key,
                    entries = items.flatMap { version ->
                        loaderSupport.loadersFor(version.id).map { VersionEntry(version, it) }
                    }.filter { !onlyInstalled || isEntryInstalled(it) },
                )
            }
            .filter { it.entries.isNotEmpty() }
        return listOfNotNull(VersionGroup(PACKS_GROUP, packEntries).takeIf { packEntries.isNotEmpty() }) + versionGroups
    }

    fun isExpanded(group: VersionGroup): Boolean = searchQuery.isNotBlank() || onlyInstalled || group.key in expandedGroups

    fun toggleOnlyInstalled() {
        onlyInstalled = !onlyInstalled
        Settings.update { it.copy(onlyInstalled = onlyInstalled) }
    }

    fun toggleGroup(key: String) {
        expandedGroups = if (key in expandedGroups) expandedGroups - key else expandedGroups + key
    }

    fun selectEntry(entry: VersionEntry) {
        selectedVersionId = entry.version.id
        selectedLoader = entry.loader
        selectedPackId = entry.pack?.id
        selectedBuildId = entry.build?.id
        refreshSelectedOptions()
    }

    fun isSelected(entry: VersionEntry): Boolean =
        entry.pack?.id == selectedPackId && entry.build?.id == selectedBuildId &&
            entry.version.id == selectedVersionId && entry.loader == selectedLoader

    fun currentEntry(): VersionEntry? {
        currentBuildEntry()?.let { return it }
        val id = selectedVersionId ?: return null
        return VersionEntry(versionById(id), selectedLoader)
    }

    fun currentBuildEntry(): VersionEntry? {
        selectedBuildId?.let { id -> builds.firstOrNull { it.id == id } }?.let { return entryFor(it) }
        selectedPackId?.let { id -> packs.firstOrNull { it.id == id } }?.let { return entryFor(it) }
        return null
    }

    fun buildChoices(): List<VersionEntry> =
        (builds.map(::entryFor) + packs.map(::entryFor)).distinctBy { it.key }

    fun libraryOptions(entry: VersionEntry): InstanceOptions = InstanceStore.cached(gameDirOf(entry)) ?: InstanceOptions()
    fun libraryEntryBusy(entry: VersionEntry): Boolean = contentModels[entry.key]?.working?.isNotEmpty() == true || (busy && busyEntry?.key == entry.key)

    fun loadLibraryMetadata() {
        if (libraryLoading) return
        val entries = buildChoices().filter { InstanceStore.cached(gameDirOf(it)) == null }
        if (entries.isEmpty()) return
        libraryLoading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { entries.forEach { InstanceStore.get(gameDirOf(it)) } }
                instanceRevision++
            } finally { libraryLoading = false }
        }
    }

    fun receiveLibraryFiles(files: List<Path>) {
        if (busy || buildsBusy || libraryBusy || savingInstanceSettings) return
        if (files.size != 1) { fail("Перенеси одну папку профиля или один архив сборки"); return }
        val file = files.single()
        scope.launch {
            if (withContext(Dispatchers.IO) { java.nio.file.Files.isDirectory(file) }) modal = Modal.ImportDirectory(file)
            else if (file.fileName.toString().lowercase().let { it.endsWith(".mrpack") || it.endsWith(".zip") })
                importMrpack(file, file.fileName.toString().substringBeforeLast('.').take(48))
            else fail("Папки и архивы сборок добавляются в библиотеку, отдельные моды — во вкладку «Контент»")
        }
    }

    private fun updateLibrary(entries: List<VersionEntry>, transform: (InstanceOptions) -> InstanceOptions) {
        if (libraryBusy || buildsBusy || busy || savingInstanceSettings) return
        libraryBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { entries.distinctBy { it.key }.forEach { InstanceStore.update(gameDirOf(it), transform) } }
                instanceRevision++
                currentEntry()?.let { refreshSelectedOptions() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e.message, title = "Не удалось обновить библиотеку") }
            finally { libraryBusy = false }
        }
    }

    fun toggleFavorite(entry: VersionEntry) = updateLibrary(listOf(entry)) { it.copy(favorite = !it.favorite) }
    fun setFavorites(entries: List<VersionEntry>, value: Boolean) = updateLibrary(entries) { it.copy(favorite = value) }
    fun assignGroup(entries: List<VersionEntry>, groupId: String?) {
        if (groupId != null && Settings.current.libraryGroups.none { it.id == groupId }) return
        updateLibrary(entries) { it.copy(groupId = groupId) }
    }

    fun createLibraryGroup(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 40) { fail("Название группы должно содержать от 1 до 40 символов"); return null }
        if (Settings.current.libraryGroups.any { it.name.equals(clean, true) }) { fail("Группа с таким названием уже есть"); return null }
        val group = ru.aw.launcher.instance.LibraryGroup(java.util.UUID.randomUUID().toString(), clean)
        Settings.update { it.copy(libraryGroups = it.libraryGroups + group) }
        return group.id
    }

    fun renameLibraryGroup(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 40) { fail("Название группы должно содержать от 1 до 40 символов"); return }
        if (Settings.current.libraryGroups.any { it.id != id && it.name.equals(clean, true) }) { fail("Группа с таким названием уже есть"); return }
        Settings.update { it.copy(libraryGroups = it.libraryGroups.map { group -> if (group.id == id) group.copy(name = clean) else group }) }
    }

    fun removeLibraryGroup(id: String) {
        Settings.update { it.copy(libraryGroups = it.libraryGroups.filterNot { group -> group.id == id }) }
        updateLibrary(buildChoices()) { if (it.groupId == id) it.copy(groupId = null) else it }
    }

    fun saveInstanceIcon(entry: VersionEntry, preset: String?, background: String?, image: Path? = null, reset: Boolean = false) {
        if (libraryBusy || busy || buildsBusy || savingInstanceSettings) return
        if (!InstanceIcons.validSymbol(preset) || !InstanceIcons.validBackground(background)) return
        libraryBusy = true
        libraryError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = gameDirOf(entry)
                    val imported = image?.let { ru.aw.launcher.instance.InstanceImages.import(dir, it) }
                    InstanceStore.update(dir) { old ->
                        if (reset) old.copy(iconPreset = null, iconBackground = null, iconImage = null, iconUrl = null)
                        else old.copy(iconPreset = preset, iconBackground = background, iconImage = imported, iconUrl = null)
                    }
                }
                instanceChanged(entry)
                modal = null
                inform("Иконка сборки сохранена", entry)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { libraryError = e.message; fail(e.message, entry, "Не удалось сохранить иконку") }
            finally { libraryBusy = false }
        }
    }

    fun duplicateEntry(entry: VersionEntry, name: String) {
        if (busy || buildsBusy || libraryBusy || savingInstanceSettings) return
        if (libraryEntryBusy(entry)) { fail("Дождись завершения установки или обновления контента", entry); return }
        buildsBusy = true
        modal = null
        startJob(null, "pack") {
            try {
                stageChanged("Создаю копию ${entry.title}")
                val copy = withContext(Dispatchers.IO) {
                    val source = gameDirOf(entry)
                    val options = InstanceStore.get(source)
                    val icon = entry.build?.iconFile?.takeIf { it == "aw-build-icon.png" }?.let(source::resolve)
                    val profile = ru.aw.launcher.instance.ImportProfile(source, source, name, entry.id, entry.loader,
                        entry.build?.loaderVersion ?: entry.pack?.loaderVersion, icon = icon)
                    val created = LocalBuilds.importProfile(profile, copyHistory = false) { ensureActive() }
                    InstanceStore.update(LocalBuilds.dirOf(created)) { options.copy(favorite = false, iconUrl = options.iconUrl ?: entry.pack?.iconUrl) }
                    created
                }
                builds = withContext(Dispatchers.IO) { LocalBuilds.list() }
                instanceRevision++
                openInstance(entryFor(copy))
                inform("Копия ${copy.name} создана", entryFor(copy))
            } finally {
                builds = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { LocalBuilds.list() }
                instanceRevision++
                buildsBusy = false
            }
        }
    }

    fun deleteLibraryEntries(entries: List<VersionEntry>) {
        if (busy || buildsBusy || libraryBusy || savingInstanceSettings) return
        if (entries.any(::libraryEntryBusy)) { fail("Дождись завершения установки или обновления контента"); return }
        modal = null
        mutateBuilds("Не удалось удалить сборки") {
            var removed = 0
            val errors = ArrayList<String>()
            for (entry in entries.distinctBy { it.key }) {
                if (entry.build == null && entry.pack == null) continue
                try {
                    withContext(Dispatchers.IO) {
                        Storage.deleteGameDir(gameDirOf(entry))
                        entry.build?.let { LocalBuilds.remove(it.id) }
                    }
                    removed++
                    if (isSelected(entry)) { selectedBuildId = null; selectedPackId = null }
                    contentModels.remove(entry.key)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += "${entry.title}: ${e.message}" }
            }
            builds = withContext(Dispatchers.IO) { LocalBuilds.list() }
            packs = withContext(Dispatchers.IO) { Modpacks.list() }
            packUpdates = packUpdates.filterKeys { id -> packs.any { it.id == id } }
            Settings.update { it.copy(lastBuildId = it.lastBuildId?.takeIf { id -> builds.any { build -> build.id == id } },
                lastPack = it.lastPack?.takeIf { id -> packs.any { pack -> pack.id == id } }) }
            instanceRevision++
            if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
            if (removed > 0) inform("Удалено сборок: $removed")
        }
    }

    fun entryFor(pack: Modpack): VersionEntry = VersionEntry(versionById(pack.gameVersion), pack.loader, pack)

    fun entryFor(build: LocalBuild): VersionEntry = VersionEntry(versionById(build.versionId), build.loader, build = build)

    fun entryByKey(key: String): VersionEntry? {
        if (key.startsWith(BUILD_KEY)) return builds.firstOrNull { it.id == key.removePrefix(BUILD_KEY) }?.let(::entryFor)
        if (key.startsWith(PACK_KEY)) return packs.firstOrNull { it.id == key.removePrefix(PACK_KEY) }?.let(::entryFor)
        val id = key.substringBeforeLast('#')
        val loader = LoaderKind.entries.firstOrNull { it.name == key.substringAfterLast('#') } ?: return null
        return entryFor(id, loader)
    }

    fun entryFor(versionId: String, loader: LoaderKind): VersionEntry = VersionEntry(versionById(versionId), loader)

    private fun requested(request: PlayRequest): VersionEntry? {
        val entry = when {
            request.build != null -> builds.firstOrNull { it.id == request.build }?.let(::entryFor)
            request.pack != null -> packs.firstOrNull { it.id == request.pack }?.let(::entryFor)
            else -> versions.firstOrNull { it.id == request.versionId }?.let { VersionEntry(it, request.loader) }
        }
        if (entry == null) {
            fail(
                if (request.build != null || request.pack != null) "Сборки из ярлыка больше нет — проверь список сборок"
                else "Версии ${request.versionId} из ярлыка нет в списке Mojang"
            )
        }
        return entry
    }

    private fun versionById(id: String): ManifestVersion =
        versions.firstOrNull { it.id == id } ?: ManifestVersion(id = id, url = "")

    fun gameDirOf(entry: VersionEntry): Path = when {
        entry.build != null -> LocalBuilds.dirOf(entry.build)
        entry.pack != null -> Modpacks.dirOf(entry.pack)
        else -> Settings.gameDir(entry.id, entry.loader)
    }

    private fun refreshSelectedOptions() {
        val entry = currentEntry() ?: return
        val dir = gameDirOf(entry)
        selectedOptions = InstanceStore.cached(dir) ?: InstanceOptions()
        scope.launch {
            val options = withContext(Dispatchers.IO) { InstanceStore.get(dir) }
            if (isSelected(entry)) selectedOptions = options
        }
    }

    private fun instanceChanged(entry: VersionEntry) {
        instanceRevision++
        if (isSelected(entry)) refreshSelectedOptions()
    }

    private fun startJob(entry: VersionEntry?, what: String, block: suspend () -> Unit) {
        if (busy) return
        val token = Any()
        jobToken = token
        busy = true
        busyEntry = entry
        stage = ""
        progress = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (jobToken === token) {
                    Log.error("$what failed", e)
                    fail(e.message ?: e::class.simpleName, entry, title = FAIL_TITLES[what])
                }
            } finally {
                if (jobToken === token) finishJob()
            }
        }
    }

    private fun stageChanged(text: String) {
        stage = text
        progress = null
    }

    private fun finishJob() {
        busy = false
        busyEntry = null
        stage = ""
        progress = null
        job = null
        jobToken = null
    }

    fun cancel() {
        job?.cancel()
        finishJob()
    }

    fun gameExited(exitCode: Int) {
        if (exitCode == 0) return
        val (entry, logFile) = lastLaunch ?: return
        Log.warn("game exited with code $exitCode")
        val actions = buildList {
            add(NoticeAction.LOGS)
            when {
                !entry.loader.isModded -> Unit
                hasModConflicts(entry) -> add(NoticeAction.FIX_MODS)
                else -> add(NoticeAction.MODS)
            }
        }
        fail(CrashHints.detail(logFile, entry.loader, entry.id, exitCode), entry, "Игра закрылась с ошибкой", actions)
    }

    private fun hasModConflicts(entry: VersionEntry): Boolean =
        runCatching { ModCompat.conflictsIn(ModManager.modsDir(gameDirOf(entry))).isNotEmpty() }.getOrDefault(false)

    fun fail(
        text: String?,
        entry: VersionEntry? = null,
        title: String? = null,
        actions: List<NoticeAction> = emptyList(),
    ) = post(NoticeLevel.ERROR, text ?: "Неизвестная ошибка", title, entry, actions)

    fun inform(
        text: String,
        entry: VersionEntry? = null,
        title: String? = null,
        level: NoticeLevel = NoticeLevel.SUCCESS,
    ) = post(level, text, title, entry, emptyList())

    private fun post(level: NoticeLevel, text: String, title: String?, entry: VersionEntry?, actions: List<NoticeAction>) {
        val (notice, repeated) = Notices.post(level, text, title, entry?.key, entry?.label, actions)
        when {
            screen == Screen.NOTICES -> Notices.markSeen()
            !repeated || level == NoticeLevel.ERROR -> toast = notice
        }
    }

    fun dismissToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    fun refreshActivity() {
        scope.launch { loadActivity(force = true) }
    }

    suspend fun loadActivity(force: Boolean = false) = activityLock.withLock {
        if (!force && activity != null && System.currentTimeMillis() - activityLoadedAt < 30_000) return@withLock
        val roots = Settings.gameRoots()
        activity = withContext(Dispatchers.IO) {
            runCatching { ActivityStats.of(PlayHistory.scan(roots), imports = builds) }
                .onFailure { Log.warn("activity scan failed: ${it.message}") }
                .getOrNull()
        } ?: activity ?: ActivityStats.of(emptyList())
        activityLoadedAt = System.currentTimeMillis()
    }

    fun openNotices() {
        toast = null
        if (screen != Screen.NOTICES) seenBeforeOpen = Notices.seen.value
        screen = Screen.NOTICES
        Notices.markSeen()
    }

    fun removeNotice(notice: Notice) {
        Notices.remove(notice.id)
        dismissToast(notice.id)
    }

    fun clearNotices() {
        Notices.clear()
        toast = null
    }

    fun runAction(notice: Notice, action: NoticeAction) {
        dismissToast(notice.id)
        val entry = notice.entryKey?.let(::entryByKey)
        when (action) {
            NoticeAction.LOGS -> showLogs(entry)
            NoticeAction.MODS -> entry?.let { openCatalog(it, CatalogTab.INSTALLED) }
            NoticeAction.FIX_MODS -> entry?.let { fixMods(it, notice) }
            NoticeAction.PLAY_ANYWAY -> entry?.let {
                Notices.resolve(notice.id)
                selectEntry(it)
                play(skipModCheck = true)
            }
        }
    }

    fun fixMods(entry: VersionEntry, source: Notice? = null) {
        startJob(entry, "fix mods") {
            stageChanged("Подбираю совместимые версии модов")
            val changes = try {
                ModManager.fixConflicts(gameDirOf(entry), entry.loader, entry.id) { progress = it }
            } catch (e: IOException) {
                fail(e.message, entry, "Моды не исправлены", listOf(NoticeAction.MODS))
                return@startJob
            }
            source?.let { Notices.resolve(it.id) }
            modsChanged(entry)
            inform(changes.joinToString("\n").ifEmpty { "Несовместимых модов не нашлось" }, entry, "Моды исправлены")
        }
    }

    fun consumePendingPlay(): Boolean = pendingPlay.also { pendingPlay = false }

    fun play(serverAddress: String? = null, skipModCheck: Boolean = false) {
        if (busy || savingInstanceSettings) return
        val entry = currentEntry() ?: run {
            fail("Выберите версию")
            return
        }
        val account = selectedAccount.value ?: run {
            fail("Добавьте аккаунт")
            screen = Screen.ACCOUNTS
            return
        }
        startJob(entry, "launch") { launch(entry, account, serverAddress, skipModCheck) }
    }

    private suspend fun launch(entry: VersionEntry, account: Account, serverAddress: String?, skipModCheck: Boolean) {
        val gameDir = gameDirOf(entry)
        if (!skipModCheck && entry.loader.isModded) {
            val conflicts = withContext(Dispatchers.IO) {
                runCatching { ModCompat.conflictsIn(ModManager.modsDir(gameDir)) }.getOrDefault(emptyList())
            }
            if (conflicts.isNotEmpty()) {
                fail(
                    conflicts.take(3).joinToString("\n") { it.text },
                    entry,
                    "Игра не запущена: моды несовместимы",
                    listOf(NoticeAction.FIX_MODS, NoticeAction.PLAY_ANYWAY),
                )
                return
            }
        }
        val notices = ArrayList<String>()
        DiscordPresence.show(Presence.Starting(entry.title, entry.id, entry.loader.takeIf { it.isModded }?.label))
        val result = try { GameLauncher.launch(
            versionId = entry.id,
            account = account,
            loader = entry.loader,
            serverAddress = serverAddress,
            gameDir = gameDir,
            loaderVersion = entry.pack?.loaderVersion ?: entry.build?.loaderVersion,
            onStage = ::stageChanged,
            onProgress = { progress = it },
            onNotice = { notices += it },
        ) } catch (e: Exception) {
            DiscordPresence.show(Presence.Launcher)
            throw e
        }
        Settings.update {
            it.copy(
                lastVersionId = entry.id,
                lastLoader = entry.loader.name,
                lastPack = entry.pack?.id,
                lastBuildId = entry.build?.id,
            )
        }
        notices.forEach { inform(it, entry, level = NoticeLevel.INFO) }
        refreshInstalled()
        instanceChanged(entry)
        lastLaunch = entry to result.logFile
        val playing = Presence.Playing(
            versionId = entry.id,
            loaderLabel = entry.loader.takeIf { it.isModded }?.label,
            server = serverAddress?.let(GameEvents::display),
            mods = if (entry.loader.isModded) ModManager.count(gameDir) else 0,
            startedAt = System.currentTimeMillis(),
            pack = entry.build?.name ?: entry.pack?.title,
            packIcon = entry.pack?.iconUrl,
            packUrl = entry.pack?.let { ContentCatalog.page(it.projectId, "modpack", "") },
        )
        DiscordPresence.show(playing)
        watchGame(result.process, result.logFile, playing)
        onGameStarted(result.process)
    }

    fun updatePack(pack: Modpack) {
        packUpdates[pack.id]?.let { installPack(it) }
    }

    fun installPack(source: PackSource) {
        if (busy) return
        installingPack = source.projectId
        startJob(packs.firstOrNull { it.id == source.id }?.let(::entryFor), "pack") {
            try {
                val pack = Modpacks.install(source, onStage = ::stageChanged, onProgress = { progress = it })
                packs = withContext(Dispatchers.IO) { Modpacks.list() }
                packUpdates = packUpdates - pack.id
                val entry = entryFor(pack)
                selectEntry(entry)
                instanceChanged(entry)
                inform("Сборка ${pack.title} ${pack.version} установлена", entry)
            } finally {
                installingPack = null
            }
        }
    }

    private fun reinstallPack(pack: Modpack) {
        if (busy) return
        scope.launch {
            runCatching { Modpacks.reinstallSource(pack) }
                .onSuccess { installPack(it) }
                .onFailure { if (it !is CancellationException) fail(it.message, entryFor(pack), "Сборка не переустановилась") }
        }
    }

    private fun checkPackUpdates() {
        scope.launch {
            val found = packs.mapNotNull { pack ->
                runCatching { Modpacks.update(pack) }
                    .onFailure { if (it !is CancellationException) Log.warn("pack update check for ${pack.id}: ${it.message}") }
                    .getOrNull()
                    ?.let { pack.id to it }
            }.toMap()
            packUpdates = found
        }
    }

    private fun watchGame(process: Process, logFile: Path, start: Presence.Playing) {
        runningGames.add(process)
        process.onExit().thenRun { runningGames.remove(process) }
        scope.launch(Dispatchers.IO) {
            val tail = GameEvents.Tail(logFile)
            var shown = start
            var iconOf: String? = null
            while (process.isAlive) {
                delay(GAME_LOG_POLL_MILLIS)
                var next = shown
                tail.lines().forEach { line ->
                    when (val event = GameEvents.parse(line)) {
                        is GameEvent.JoinedServer -> next = next.copy(server = event.address, singleplayer = false)
                        GameEvent.Singleplayer -> next = next.copy(server = null, singleplayer = true)
                        GameEvent.Menu -> next = next.copy(server = null, singleplayer = false)
                        null -> Unit
                    }
                }
                if (!Settings.current.discordPresence || !Settings.current.discordShowServer) {
                    iconOf = null
                    next = next.copy(serverIcon = null)
                } else if (next.server != iconOf) {
                    iconOf = next.server
                    next = next.copy(serverIcon = next.server?.let { serverIcon(it) })
                }
                if (next != shown && process.isAlive) {
                    shown = next
                    DiscordPresence.show(next)
                }
            }
        }
    }

    private suspend fun serverIcon(address: String): String? =
        DiscordPresence.serverIcon(address)?.takeIf { ServerPing.ping(address)?.favicon != null }

    fun playFromShortcut(request: PlayRequest, gameRunning: Boolean) {
        if (busy) {
            fail("Сначала дождитесь окончания загрузки ${busyEntry?.label.orEmpty()}".trimEnd())
            return
        }
        val entry = requested(request) ?: return
        selectEntry(entry)
        if (gameRunning) inform("Игра уже запущена. ${entry.label} выбрана — запустите её, когда закончите", entry, level = NoticeLevel.INFO) else play()
    }

    fun playOnServer(server: ServerEntry) {
        if (busy) return
        if (selectedVersionId == null) server.entryKey?.let(::entryByKey)?.let(::selectEntry)
        play(serverAddress = server.address)
    }

    fun reinstall(entry: VersionEntry) {
        if (busy) return
        entry.pack?.let {
            reinstallPack(it)
            return
        }
        selectEntry(entry)
        startJob(entry, "reinstall") {
            val notices = ArrayList<String>()
            withContext(Dispatchers.IO) {
                Storage.deleteVersionFiles(Storage.versionIdsOf(entry.id, entry.loader, versions.map { it.id }))
                VerifyCache.clear()
            }
            GameLauncher.prepare(
                versionId = entry.id,
                loader = entry.loader,
                gameDir = gameDirOf(entry),
                loaderVersion = entry.pack?.loaderVersion ?: entry.build?.loaderVersion,
                onStage = ::stageChanged,
                onProgress = { progress = it },
                onNotice = { notices += it },
            )
            refreshInstalled()
            instanceChanged(entry)
            inform((listOf("${entry.label} переустановлена") + notices).joinToString("\n"), entry)
        }
    }

    fun delete(entry: VersionEntry, withGameDir: Boolean) {
        if (busy && busyEntry == entry) {
            fail("Сначала дождитесь окончания загрузки ${entry.label}", entry)
            return
        }
        entry.pack?.let {
            deletePack(it)
            return
        }
        entry.build?.let {
            removeBuild(it)
            return
        }
        scope.launch {
            val trashed = try {
                withContext(Dispatchers.IO) {
                    Storage.deleteVersionFiles(Storage.versionIdsOf(entry.id, entry.loader, versions.map { it.id }))
                    if (withGameDir) Storage.deleteGameDir(gameDirOf(entry)) else false
                }
            } catch (e: IOException) {
                refreshInstalled()
                instanceChanged(entry)
                fail(e.message, entry)
                return@launch
            }
            refreshInstalled()
            instanceChanged(entry)
            inform(
                when {
                    !withGameDir -> "Файлы ${entry.label} удалены, миры и настройки на месте"
                    trashed -> "${entry.label} удалена, папка сборки в корзине"
                    else -> "${entry.label} удалена вместе с папкой сборки"
                },
            )
        }
    }

    private fun deletePack(pack: Modpack) {
        scope.launch {
            val trashed = try {
                withContext(Dispatchers.IO) { Storage.deleteGameDir(Modpacks.dirOf(pack)) }
            } catch (e: IOException) {
                fail(e.message, entryFor(pack))
                return@launch
            }
            if (selectedPackId == pack.id) {
                selectedPackId = null
                refreshSelectedOptions()
            }
            if (catalogTarget?.pack?.id == pack.id) catalogTarget = null
            if (Settings.current.lastPack == pack.id) Settings.update { it.copy(lastPack = null) }
            packs = withContext(Dispatchers.IO) { Modpacks.list() }
            packUpdates = packUpdates - pack.id
            inform(if (trashed) "Сборка ${pack.title} в корзине" else "Сборка ${pack.title} удалена")
        }
    }

    fun openBuild(build: LocalBuild) {
        openInstance(entryFor(build))
    }

    fun importMrpack(archive: Path, name: String) {
        if (busy || buildsBusy) return
        buildsBusy = true
        startJob(null, "pack") {
            try {
                val build = Modpacks.importMrpack(
                    archive = archive,
                    buildName = name,
                    onStage = ::stageChanged,
                    onProgress = { progress = it },
                )
                builds = withContext(Dispatchers.IO) { LocalBuilds.list() }
                val entry = entryFor(build)
                selectEntry(entry)
                screen = Screen.PLAY
                inform("Сборка ${build.name} импортирована", entry)
            } finally {
                buildsBusy = false
            }
        }
    }

    fun importBuildDirectory(source: Path, name: String, versionId: String, loader: LoaderKind) {
        if (busy || buildsBusy) return
        if (versions.none { it.id == versionId }) {
            fail("Выбери версию из списка Minecraft")
            return
        }
        if (!loaderSupport.supports(loader, versionId)) {
            fail("${loader.label} не поддерживает Minecraft $versionId")
            return
        }
        buildsBusy = true
        startJob(null, "pack") {
            try {
                stageChanged("Переношу $name")
                val build = withContext(Dispatchers.IO) {
                    LocalBuilds.importDirectory(source, name, versionId, loader, onProgress = { progress = it }) { ensureActive() }
                }
                builds = withContext(Dispatchers.IO) { LocalBuilds.list() }
                val entry = entryFor(build)
                selectEntry(entry)
                screen = Screen.PLAY
                inform("Профиль ${build.name} импортирован", entry)
            } finally { buildsBusy = false }
        }
    }

    fun importProfiles(profiles: List<ru.aw.launcher.instance.ImportProfile>) {
        if (profiles.isEmpty() || busy || buildsBusy) return
        buildsBusy = true
        startJob(null, "pack") {
            val imported = ArrayList<LocalBuild>()
            try {
                for (profile in profiles) {
                    stageChanged("Переношу ${profile.name}")
                    val build = withContext(Dispatchers.IO) {
                        LocalBuilds.importProfile(profile, onProgress = { progress = it }) { ensureActive() }
                    }
                    imported += build
                    builds = withContext(Dispatchers.IO) { LocalBuilds.list() }
                    inform("Сборка ${build.name} импортирована", entryFor(build))
                }
            } finally {
                builds = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { LocalBuilds.list() }
                buildsBusy = false
                imported.lastOrNull()?.let { openInstance(entryFor(it)) }
                refreshActivity()
                refreshInstalled()
            }
        }
    }

    fun playTimeOf(entry: VersionEntry): Long {
        val sessions = sessionsOf(entry)
        val build = entry.build ?: return sessions.sumOf { it.millis }
        val historical = sessions.sumOf { (minOf(it.end, build.importedAt ?: 0) - it.start).coerceAtLeast(0) }
        return sessions.sumOf { it.millis } + (build.importedPlayTimeMillis - historical).coerceAtLeast(0)
    }

    fun lastPlayedOf(entry: VersionEntry): Long? = listOfNotNull(entry.build?.lastPlayed, sessionsOf(entry).maxOfOrNull { it.start }).maxOrNull()

    fun activityOf(entry: VersionEntry): ActivityStats = ActivityStats.of(sessionsOf(entry), imports = listOfNotNull(entry.build))

    private var indexedActivity: ActivityStats? = null
    private var sessionsByBuild: Map<String?, List<ru.aw.launcher.activity.PlaySession>> = emptyMap()
    private var sessionsByInstance: Map<String, List<ru.aw.launcher.activity.PlaySession>> = emptyMap()
    private var sessionsByVersion: Map<Pair<String, LoaderKind>, List<ru.aw.launcher.activity.PlaySession>> = emptyMap()

    private fun sessionsOf(entry: VersionEntry): List<ru.aw.launcher.activity.PlaySession> {
        val current = activity
        if (indexedActivity !== current) {
            val sessions = current?.sessions.orEmpty()
            sessionsByBuild = sessions.filter { it.buildId != null }.groupBy { it.buildId }
            sessionsByInstance = sessions.groupBy { it.instance }
            sessionsByVersion = sessions.filter { it.buildId == null && it.pack == null }.groupBy { it.versionId to it.loader }
            indexedActivity = current
        }
        return when {
            entry.build != null -> sessionsByBuild[entry.build.id]
            entry.pack != null -> sessionsByInstance[entry.pack.id]
            else -> sessionsByVersion[entry.id to entry.loader]
        }.orEmpty()
    }

    fun createBuild(name: String, versionId: String, loader: LoaderKind) {
        if (buildsBusy) return
        if (versions.none { it.id == versionId }) {
            fail("Выбери версию из списка Minecraft")
            return
        }
        if (!loaderSupport.supports(loader, versionId)) {
            fail("${loader.label} не поддерживает Minecraft $versionId")
            return
        }
        mutateBuilds("Не удалось создать сборку") {
            val build = withContext(Dispatchers.IO) { LocalBuilds.create(name, versionId, loader) }
            builds = withContext(Dispatchers.IO) { LocalBuilds.list() }
            selectEntry(entryFor(build))
            screen = Screen.PLAY
            inform("Сборка ${build.name} создана", entryFor(build))
        }
    }

    fun renameBuild(build: LocalBuild, name: String) {
        mutateBuilds("Не удалось переименовать сборку") {
            val renamed = withContext(Dispatchers.IO) { LocalBuilds.rename(build.id, name) }
            builds = withContext(Dispatchers.IO) { LocalBuilds.list() }
            if (selectedBuildId == renamed.id) selectEntry(entryFor(renamed))
            inform("Сборка переименована: ${renamed.name}", entryFor(renamed))
        }
    }

    fun removeBuild(build: LocalBuild) {
        if (busy && busyEntry?.build?.id == build.id) {
            fail("Сначала дождитесь окончания загрузки ${build.name}")
            return
        }
        mutateBuilds("Не удалось удалить сборку") {
            withContext(Dispatchers.IO) { Storage.deleteGameDir(LocalBuilds.dirOf(build)) }
            withContext(Dispatchers.IO) { LocalBuilds.remove(build.id) }
            builds = withContext(Dispatchers.IO) { LocalBuilds.list() }
            if (selectedBuildId == build.id) {
                selectedBuildId = null
                selectedPackId = null
                refreshSelectedOptions()
            }
            if (Settings.current.lastBuildId == build.id) Settings.update { it.copy(lastBuildId = null) }
            inform("Сборка ${build.name} удалена")
        }
    }

    private fun mutateBuilds(failureTitle: String, action: suspend () -> Unit) {
        if (buildsBusy) return
        buildsBusy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.error("local build update failed", e)
                fail(e.message ?: failureTitle, title = failureTitle)
            } finally {
                buildsBusy = false
            }
        }
    }

    fun saveInstanceSettings(entry: VersionEntry, draft: InstanceOptions) {
        if (savingInstanceSettings || busy || buildsBusy) return
        savingInstanceSettings = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    draft.memoryMb?.let { require(it in 512..ru.aw.launcher.core.SettingsDefaults.memoryLimit(ru.aw.launcher.core.SettingsDefaults.totalSystemMemoryMb())) { "Выбери допустимый объём памяти" } }
                    draft.jvmArgs?.let { ru.aw.launcher.launch.ArgumentBuilder.parseJvmArguments(it) }
                    draft.javaPath?.let { ru.aw.launcher.runtime.JavaManager.customExecutable(it) }
                    if (draft.windowWidth != null || draft.windowHeight != null) require(
                        draft.windowWidth != null && draft.windowWidth in 320..16384 && draft.windowHeight != null && draft.windowHeight in 200..16384
                    ) { "Некорректный размер окна Minecraft" }
                    InstanceStore.update(gameDirOf(entry)) { old -> old.copy(
                        memoryMb = draft.memoryMb, jvmArgs = draft.jvmArgs, javaPath = draft.javaPath,
                        windowWidth = draft.windowWidth, windowHeight = draft.windowHeight, fullscreen = draft.fullscreen,
                    ) }
                }
                instanceChanged(entry)
                inform("Настройки сборки сохранены", entry)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e.message, entry, "Не удалось сохранить настройки сборки") }
            finally { savingInstanceSettings = false }
        }
    }

    fun supportsShaders(entry: VersionEntry): Boolean = when (entry.loader) {
        LoaderKind.VANILLA -> false
        LoaderKind.FORGE -> false
        else -> true
    }

    fun modsChanged(entry: VersionEntry) = instanceChanged(entry)

    fun openCatalog(entry: VersionEntry? = null, tab: CatalogTab? = null, query: String = "") {
        catalogTarget = entry
        tab?.let { catalogTab = it }
        catalogQuery = query
        catalogProject = null
        catalogProjectTab = 0
        screen = Screen.CATALOG
    }

    fun openProject(entry: VersionEntry, hit: ru.aw.launcher.mods.Modrinth.SearchHit) {
        openCatalog(entry, CatalogTab.INSTALLED)
        catalogProject = hit
    }

    fun catalogTargets(): List<VersionEntry> {
        val installedVersions = versions
            .flatMap { version -> loaderSupport.loadersFor(version.id).map { VersionEntry(version, it) } }
            .filter(::isEntryInstalled)
        return (builds.map(::entryFor) + packs.map(::entryFor) + installedVersions + listOfNotNull(currentEntry()))
            .distinctBy { it.key }
    }

    fun openFolder(dir: Path) = Shell.openFolder(dir) { fail(it) }

    fun showLogs(entry: VersionEntry?, source: LogSource = LogSource.GAME) {
        modal = Modal.Logs(entry?.let(::gameDirOf), entry?.label ?: "AWLauncher", source)
    }

    fun createShortcut(entry: VersionEntry) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    Shortcuts.createOnDesktop(
                        entry.title.takeIf { entry.pack != null || entry.build != null } ?: "Minecraft ${entry.label}",
                        entry.id,
                        entry.loader,
                        entry.pack?.id,
                        entry.build?.id,
                    )
                }
            }
                .onSuccess { inform("Ярлык «${it.fileName.toString().removeSuffix(".lnk")}» на рабочем столе", entry) }
                .onFailure { fail(it.message, entry) }
        }
    }

    fun trackMenu(open: Boolean) {
        openMenus = (openMenus + if (open) 1 else -1).coerceAtLeast(0)
    }

    fun refreshServers() {
        servers.forEach(::pingServer)
    }

    private fun pingServer(server: ServerEntry) {
        if (serverStatus[server.address] !is PingState.Online) setPing(server.address, PingState.Pinging)
        scope.launch {
            val status = ServerPing.ping(server.address)
            setPing(server.address, status?.let { PingState.Online(it) } ?: PingState.Offline)
        }
    }

    private val pingLock = Any()

    private fun setPing(id: String, value: PingState) {
        synchronized(pingLock) { serverStatus = serverStatus + (id to value) }
    }

    fun checkForUpdates() {
        scope.launch { Updater.check() }
    }

    fun installUpdate(update: UpdateManifest) {
        if (runningGames.any { it.isAlive }) { fail("Закрой Minecraft перед обновлением лаунчера"); return }
        scope.launch {
            runCatching { Updater.install(update) }
                .onSuccess { if (it) onQuit() }
                .onFailure { if (it !is CancellationException) fail(it.message, title = "Обновление не установилось") }
        }
    }

    fun signInMicrosoft() {
        if (signingIn) return
        signingIn = true
        signInCode = null
        signInJob = scope.launch {
            try {
                runCatching {
                    AccountManager.signInMicrosoft(onCode = { signInCode = it }) { signInStage = it }
                }
                    .onSuccess { inform("Вход выполнен: ${it.name}") }
                    .onFailure { failure ->
                        if (failure !is CancellationException) {
                            Log.error("microsoft sign-in failed", failure)
                            fail(failure.message ?: "Вход не удался", title = "Вход через Microsoft")
                        }
                    }
            } finally {
                signingIn = false
                signInStage = ""
                signInCode = null
                signInJob = null
            }
        }
    }

    fun cancelMicrosoftSignIn() {
        signInJob?.cancel()
    }

    fun openMicrosoftSignInPage() {
        signInCode?.let { Shell.browse(it.verificationUrl) }
    }

    fun addOffline(nickname: String): String? =
        runCatching { AccountManager.addOffline(nickname) }.fold(
            onSuccess = {
                inform("Добавлен офлайн-аккаунт ${it.name}")
                null
            },
            onFailure = { it.message ?: "Не получилось добавить аккаунт" },
        )

    internal var skinWorkingUuid by mutableStateOf<String?>(null)
        private set
    var skinError by mutableStateOf<String?>(null)
        private set

    fun openSkinEditor(account: Account) {
        if (account.isOffline || skinWorkingUuid != null) return
        skinError = null
        modal = Modal.Skin(account.uuid)
    }

    internal fun applySkin(uuid: String, image: SkinImage?, model: SkinModel,
                           capeId: String? = null, changeCape: Boolean = false, changeSkin: Boolean = true) {
        if (skinWorkingUuid != null) return
        skinWorkingUuid = uuid
        skinError = null
        scope.launch {
            try {
                var updated = if (changeSkin) AccountManager.changeSkin(uuid, image, model)
                    else AccountManager.accounts.value.firstOrNull { it.uuid == uuid }
                        ?: throw ru.aw.launcher.auth.AuthException("Аккаунт был удалён из лаунчера")
                if (changeCape) updated = AccountManager.changeCape(uuid, capeId)
                if ((modal as? Modal.Skin)?.accountUuid == uuid) modal = null
                inform("Внешний вид ${updated.name} изменён в профиле Minecraft")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { skinError = error.message ?: "Не удалось изменить скин или плащ" }
            finally { skinWorkingUuid = null }
        }
    }

    fun removeAccount(uuid: String) { if (skinWorkingUuid != uuid) AccountManager.remove(uuid) }

    fun selectAccount(uuid: String) = AccountManager.select(uuid)

    private fun visibleEntries(): List<VersionEntry> = buildChoices()

    fun moveSelection(delta: Int) {
        val entries = visibleEntries()
        if (entries.isEmpty()) return
        val current = entries.indexOfFirst(::isSelected)
        val next = when {
            current < 0 -> if (delta > 0) 0 else entries.lastIndex
            else -> (current + delta).coerceIn(0, entries.lastIndex)
        }
        selectEntry(entries[next])
        keyboardMoves++
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isAltPressed && modal == null && openMenus == 0) {
            when (event.key) {
                Key.DirectionLeft -> { navigateBack(); return true }
                Key.DirectionRight -> { navigateForward(); return true }
            }
        }
        if (screen != Screen.PLAY || modal != null || openMenus > 0) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false

        when (event.key) {
            Key.Enter, Key.NumPadEnter -> {
                if (!busy && currentBuildEntry() != null) play()
                return true
            }
            Key.DirectionDown -> {
                moveSelection(+1)
                return true
            }
            Key.DirectionUp -> {
                moveSelection(-1)
                return true
            }
            Key.Escape -> {
                if (searchQuery.isEmpty()) return false
                searchQuery = ""
                return true
            }
        }

        return false
    }

    private companion object {
        const val GAME_LOG_POLL_MILLIS = 2_000L
        val FAIL_TITLES = mapOf(
            "launch" to "Игра не запустилась",
            "reinstall" to "Переустановка не удалась",
            "fix mods" to "Моды не исправлены",
            "pack" to "Сборка не установилась",
        )
        const val NO_VERSIONS = "Не удалось получить список версий. Проверьте интернет и перезапустите лаунчер."
    }
}

private val RELEASE_LINE = Regex("""^(\d+)\.(\d+)""")
private val WEEKLY_SNAPSHOT = Regex("""^(\d{2})w\d{2}[a-z~]""")

fun groupKeyOf(version: ManifestVersion): String {
    when (version.kind) {
        VersionKind.OLD_BETA -> return "Beta"
        VersionKind.OLD_ALPHA -> return "Alpha"
        else -> Unit
    }
    WEEKLY_SNAPSHOT.find(version.id)?.let { return "Снапшоты ${it.groupValues[1]}w" }
    RELEASE_LINE.find(version.id)?.let { return "${it.groupValues[1]}.${it.groupValues[2]}" }
    return "Прочее"
}
