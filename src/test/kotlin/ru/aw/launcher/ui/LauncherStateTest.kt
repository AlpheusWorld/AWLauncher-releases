package ru.aw.launcher.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import ru.aw.launcher.activity.PlayHistory
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.PreloadResult
import ru.aw.launcher.core.Settings
import ru.aw.launcher.instance.LocalBuild
import ru.aw.launcher.instance.LocalBuilds
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.meta.LoaderSupport
import ru.aw.launcher.meta.ManifestVersion
import ru.aw.launcher.meta.VersionManifest
import ru.aw.launcher.packs.Modpack

class LauncherStateTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    @TempDir lateinit var temp: Path

    @Test
    fun `skin selector opens the requested account without switching the launch account`() {
        val state = LauncherState(scope, preload(emptySet()))
        val launchAccount = state.selectedAccount.value
        val editing = ru.aw.launcher.auth.Account("00000000000000000000000000000013", "AppearanceTest", ru.aw.launcher.auth.AccountType.MICROSOFT)
        state.screen = Screen.ACTIVITY
        state.openSkinEditor(editing)
        assertEquals(Screen.SKINS, state.screen)
        assertEquals(editing.uuid, state.skinAccountUuid)
        assertEquals(launchAccount, state.selectedAccount.value)
        assertNull(state.modal)
        state.navigateBack()
        assertEquals(Screen.ACTIVITY, state.screen)
        state.openSkinEditor(ru.aw.launcher.auth.Account.offline("OfflineTest"))
        assertEquals(Screen.ACTIVITY, state.screen)
    }

    @Test
    fun `settings open as overlays without replacing the current page or its history`() {
        val state = LauncherState(scope, preload(emptySet()))
        state.screen = Screen.ACTIVITY
        state.openSettings()
        assertEquals(Screen.ACTIVITY, state.screen)
        assertTrue(state.modal is Modal.Settings)
        state.modal = null
        state.navigateBack()
        assertEquals(Screen.HOME, state.screen)
        state.screen = Screen.SETTINGS
        assertEquals(Screen.HOME, state.screen)
        assertTrue(state.modal is Modal.Settings)
    }

    @Test
    fun `instance statistics isolate builds with the same version and retain imported time`() = runBlocking {
        val first = LocalBuild("72a4ca77-3aae-4c19-b237-652bf6a4fd12", "First", "26.3", LoaderKind.FABRIC,
            importedPlayTimeMillis = 10_000, importedAt = 20_000)
        val second = first.copy(id = "c4f5a4a1-2502-4330-939c-4a1c187a63f4", name = "Second", importedPlayTimeMillis = 0)
        val state = LauncherState(scope, preload(emptySet(), builds = listOf(first, second), packs = listOf(pack)))
        val history = ru.aw.launcher.activity.PlaySession(first.id, first.versionId, first.loader, 0, 5_000, buildId = first.id)
        val added = history.copy(start = 30_000, end = 32_000)
        val other = history.copy(instance = second.id, buildId = second.id, start = 40_000, end = 43_000)
        val packSession = history.copy(instance = pack.id, buildId = null, pack = pack.title, start = 50_000, end = 54_000)
        val oldHistory = PlayHistory.historyFile
        val oldCache = PlayHistory.cacheFile
        try {
            PlayHistory.historyFile = temp.resolve("history.json")
            PlayHistory.cacheFile = null
            Files.writeString(PlayHistory.historyFile, Json.encodeToString(listOf(history, added, other, packSession)))
            PlayHistory.resetForTests()
            state.loadActivity()
            assertEquals(12_000L, state.activityOf(state.entryFor(first)).totalMillis)
            assertEquals(3_000L, state.activityOf(state.entryFor(second)).totalMillis)
            assertEquals(4_000L, state.activityOf(state.entryFor(pack)).totalMillis)
            assertEquals(state.playTimeOf(state.entryFor(first)), state.activityOf(state.entryFor(first)).totalMillis)
            assertEquals(listOf(history, added), state.activityOf(state.entryFor(first)).sessions)
            val later = added.copy(start = 60_000, end = 70_000)
            Files.writeString(PlayHistory.historyFile, Json.encodeToString(listOf(later, other)))
            PlayHistory.resetForTests()
            state.loadActivity(force = true)
            assertEquals(20_000L, state.playTimeOf(state.entryFor(first)))
            assertEquals(listOf(later), state.activityOf(state.entryFor(first)).sessions)
        } finally {
            PlayHistory.historyFile = oldHistory
            PlayHistory.cacheFile = oldCache
            PlayHistory.resetForTests()
        }
    }

    @Test
    fun `library groups keep their identity through renaming and reject duplicate names`() {
        val original = Settings.current
        try {
            Settings.update { it.copy(libraryGroups = emptyList()) }
            val state = LauncherState(scope, preload(emptySet()))
            val id = state.createLibraryGroup("  Приключения  ")!!
            assertEquals("Приключения", Settings.current.libraryGroups.single().name)
            assertNull(state.createLibraryGroup("приключения"))
            state.renameLibraryGroup(id, "Выживание")
            assertEquals(id, Settings.current.libraryGroups.single().id)
            assertEquals("Выживание", Settings.current.libraryGroups.single().name)
            state.removeLibraryGroup(id)
            assertTrue(Settings.current.libraryGroups.isEmpty())
        } finally { Settings.update { original } }
    }

    private val pack = Modpack(
        id = "fabulously-optimized",
        title = "Fabulously Optimized",
        version = "1",
        gameVersion = "26.2",
        loader = LoaderKind.FABRIC,
        loaderVersion = "0.19.5",
        projectId = "1KVo5zza",
        versionId = "ssWn7YI0",
    )

    private fun preload(profiles: Set<String>, packs: List<Modpack> = emptyList(), builds: List<LocalBuild> = emptyList()) = PreloadResult(
        manifest = VersionManifest(
            latest = VersionManifest.Latest(release = "26.3"),
            versions = listOf(
                ManifestVersion(id = "26.3", url = "https://example.invalid/26.3.json"),
                ManifestVersion(id = "26.2", url = "https://example.invalid/26.2.json"),
            ),
        ),
        installed = mapOf("26.3" to 1L),
        profiles = profiles,
        loaderSupport = LoaderSupport(fabric = setOf("26.3")),
        packs = packs,
        builds = builds,
    )

    private fun rememberLaunch(version: String, loader: String) =
        Settings.update { it.copy(lastVersionId = version, lastLoader = loader) }

    @Test
    fun `version selectors and library immediately respect snapshot and old version toggles`() {
        val previous = Settings.current
        try {
            Settings.update { it.copy(showSnapshots = false, showOldVersions = false, onlyInstalled = false) }
            val state = LauncherState(scope, preload(emptySet()).copy(manifest = VersionManifest(versions = listOf(
                ManifestVersion("1.21.1", url = ""),
                ManifestVersion("25w14a", "snapshot", ""),
                ManifestVersion("b1.7.3", "old_beta", ""),
                ManifestVersion("a1.2.6", "old_alpha", ""),
            )), installed = mapOf("1.21.1" to 1, "25w14a" to 1, "b1.7.3" to 1)))
            assertEquals(listOf("1.21.1"), state.selectableVersions().map { it.id })
            assertTrue(state.selectableVersions(" A1.2 ").isEmpty())
            state.toggleOnlyInstalled()
            assertEquals(listOf("1.21.1"), state.groups().flatMap { it.entries }.map { it.id }.distinct())
            Settings.update { it.copy(showSnapshots = true, showOldVersions = true) }
            assertEquals(4, state.selectableVersions().size)
            assertEquals(listOf("a1.2.6"), state.selectableVersions("a1.2").map { it.id })
        } finally { Settings.update { previous } }
    }

    @Test
    fun `release selection is not truncated by more than 120 hidden snapshots`() {
        val recent = (1..150).map { ManifestVersion("26w${it}a", "snapshot", "") }
        val old = listOf(ManifestVersion("1.7.10", url = ""), ManifestVersion("1.0", url = ""),
            ManifestVersion("b1.7.3", "old_beta", ""), ManifestVersion("a1.2.6", "old_alpha", ""))
        val state = LauncherState(scope, preload(emptySet()).copy(manifest = VersionManifest(versions = recent + old)))
        assertEquals(listOf("1.7.10", "1.0"), state.selectableVersions().map { it.id })
        assertEquals(listOf("1.7.10"), state.selectableVersions("1.7.10").map { it.id })
    }

    @Test
    fun `opening a build navigates to its instance page and back to the library`() {
        val build = LocalBuild("72a4ca77-3aae-4c19-b237-652bf6a4fd12", "Survival", "26.3", LoaderKind.FABRIC)
        val state = LauncherState(scope, preload(emptySet(), builds = listOf(build)))
        state.openInstance(state.entryFor(build))
        assertEquals(Screen.INSTANCE, state.screen)
        assertEquals(build, state.instanceEntry()?.build)
        state.navigateBack()
        assertEquals(Screen.HOME, state.screen)
    }

    @Test
    fun `the loader of the last launch is selected again`() {
        rememberLaunch("26.3", LoaderKind.FABRIC.name)
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3")))

        assertEquals("26.3", state.selectedVersionId)
        assertEquals(LoaderKind.FABRIC, state.selectedLoader)
    }

    @Test
    fun `a removed loader profile falls back to the same version without it`() {
        rememberLaunch("26.3", LoaderKind.FABRIC.name)
        val state = LauncherState(scope, preload(setOf("26.3")))

        assertEquals("26.3", state.selectedVersionId)
        assertEquals(LoaderKind.VANILLA, state.selectedLoader)
    }

    @Test
    fun `an unknown loader name is treated as vanilla`() {
        rememberLaunch("26.3", "SOME_LOADER_FROM_THE_FUTURE")
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3")))

        assertEquals(LoaderKind.VANILLA, state.selectedLoader)
    }

    @Test
    fun `the arrow keys walk build profiles`() {
        val first = LocalBuild("72a4ca77-3aae-4c19-b237-652bf6a4fd12", "Vanilla", "26.3", LoaderKind.VANILLA)
        val second = LocalBuild("c4f5a4a1-2502-4330-939c-4a1c187a63f4", "Fabric", "26.3", LoaderKind.FABRIC)
        val state = LauncherState(scope, preload(setOf("26.3"), builds = listOf(first, second)))
        state.selectEntry(state.entryFor(first))

        state.moveSelection(+1)
        assertEquals(second.id, state.currentBuildEntry()?.build?.id)
        assertEquals(LoaderKind.FABRIC, state.selectedLoader)
        state.moveSelection(-1)
        assertEquals(first.id, state.currentBuildEntry()?.build?.id)
    }

    @Test
    fun `the Play build selector does not include vanilla version rows`() {
        val build = LocalBuild("72a4ca77-3aae-4c19-b237-652bf6a4fd12", "Survival", "26.3", LoaderKind.VANILLA)
        val state = LauncherState(scope, preload(setOf("26.3"), builds = listOf(build)))

        state.searchQuery = "26"
        assertEquals(listOf(state.entryFor(build)), state.buildChoices())
        assertNull(state.currentBuildEntry())
    }

    @Test
    fun `installed packs come first and play from their own folder`() {
        val state = LauncherState(scope, preload(setOf("26.3"), listOf(pack)))
        val group = state.groups().first()
        assertEquals(PACKS_GROUP, group.key)
        assertTrue(state.isExpanded(group))

        val entry = group.entries.single()
        assertEquals("Fabulously Optimized", entry.label)
        assertEquals(entry, state.entryByKey(entry.key))
        state.selectEntry(entry)
        assertEquals(pack, state.currentEntry()?.pack)
        assertTrue(state.isSelected(entry))
        assertEquals(Settings.packsDir().resolve("fabulously-optimized"), state.gameDirOf(entry))

        state.selectEntry(state.entryFor("26.2", LoaderKind.FABRIC))
        assertNull(state.currentEntry()?.pack)
        assertFalse(state.isSelected(entry))
    }

    @Test
    fun `the last played pack is selected again, a removed one is forgotten`() {
        Settings.update { it.copy(lastPack = pack.id) }
        try {
            assertEquals(pack, LauncherState(scope, preload(setOf("26.3"), listOf(pack))).currentEntry()?.pack)
            assertNull(LauncherState(scope, preload(setOf("26.3"))).currentEntry()?.pack)
        } finally {
            Settings.update { it.copy(lastPack = null) }
        }
    }

    @Test
    fun `the downloaded filter keeps only installed entries and opens their groups`() {
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3")))
        if (state.onlyInstalled) state.toggleOnlyInstalled()
        try {
            state.toggleOnlyInstalled()
            val groups = state.groups()
            assertEquals(listOf("26.3"), groups.map { it.key })
            assertEquals(listOf(LoaderKind.VANILLA, LoaderKind.FABRIC), groups.single().entries.map { it.loader })
            assertTrue(state.isExpanded(groups.single()))
            assertTrue(Settings.current.onlyInstalled)
        } finally {
            if (state.onlyInstalled) state.toggleOnlyInstalled()
        }
        assertEquals(listOf("26.3", "26.2"), state.groups().map { it.key })
    }

    @Test
    fun `a server's pinned version survives a round trip through its key`() {
        val state = LauncherState(scope, preload(setOf("26.3")))
        val entry = state.entryFor("26.3", LoaderKind.FABRIC)

        assertEquals(entry, state.entryByKey(entry.key))
        assertEquals(null, state.entryByKey("26.3#NOT_A_LOADER"))
    }

    @Test
    fun `a named build selects its own isolated game directory`() {
        val build = LocalBuild("72a4ca77-3aae-4c19-b237-652bf6a4fd12", "Shader Lab", "26.3", LoaderKind.FABRIC)
        val state = LauncherState(scope, preload(setOf("26.3", "fabric-loader-0.16.14-26.3"), builds = listOf(build)))

        val entry = state.entryFor(build)
        state.selectEntry(entry)

        assertEquals("build:${build.id}", entry.key)
        assertEquals(entry, state.currentEntry())
        assertEquals(entry, state.currentBuildEntry())
        assertEquals(listOf(entry), state.buildChoices())
        assertEquals(entry, state.entryByKey(entry.key))
        assertEquals(LocalBuilds.dirOf(build), state.gameDirOf(entry))
        assertFalse(state.gameDirOf(entry) == Settings.gameDir(build.versionId, build.loader))
        assertTrue(state.catalogTargets().any { it.key == entry.key })
    }

    @Test
    fun `the Play build selector excludes plain versions`() {
        val state = LauncherState(scope, preload(setOf("26.3")))

        assertNull(state.currentBuildEntry())
        assertTrue(state.buildChoices().isEmpty())
    }

    @Test
    fun `title bar navigation returns through visited pages in both directions`() {
        val state = LauncherState(scope, preload(emptySet()))
        assertFalse(state.canNavigateBack)
        state.screen = Screen.BUILDS
        state.screen = Screen.ACCOUNTS
        state.navigateBack()
        assertEquals(Screen.BUILDS, state.screen)
        state.navigateBack()
        assertEquals(Screen.HOME, state.screen)
        assertFalse(state.canNavigateBack)
        state.navigateForward()
        assertEquals(Screen.BUILDS, state.screen)
        state.navigateForward()
        assertEquals(Screen.ACCOUNTS, state.screen)
        assertFalse(state.canNavigateForward)
    }

    @Test
    fun `choosing a new page after going back clears forward navigation`() {
        val state = LauncherState(scope, preload(emptySet()))
        state.screen = Screen.BUILDS
        state.screen = Screen.ACCOUNTS
        state.navigateBack()
        state.screen = Screen.ACTIVITY
        assertFalse(state.canNavigateForward)
        state.screen = Screen.ACTIVITY
        state.navigateBack()
        assertEquals(Screen.BUILDS, state.screen)
    }

    @Test
    fun `back from a project returns to the catalog before leaving its screen`() {
        val state = LauncherState(scope, preload(emptySet()))
        state.screen = Screen.CATALOG
        var projectClosed = false
        state.catalogPageTitle = "Fabulously Optimized"
        state.catalogBackAction = { projectClosed = true }
        state.navigateBack()
        assertTrue(projectClosed)
        assertEquals(Screen.CATALOG, state.screen)
        assertNull(state.catalogPageTitle)
        state.navigateBack()
        assertEquals(Screen.HOME, state.screen)
    }
}
