package ru.aw.launcher.dev

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.skia.EncodedImageFormat
import ru.aw.launcher.activity.PlayHistory
import ru.aw.launcher.activity.PlaySession
import ru.aw.launcher.auth.AccountManager
import ru.aw.launcher.core.NoticeAction
import ru.aw.launcher.core.NoticeLevel
import ru.aw.launcher.core.Notices
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.Preloader
import ru.aw.launcher.core.ThemeMode
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.Json
import kotlinx.serialization.encodeToString
import ru.aw.launcher.core.Language
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.ui.ModIcons
import ru.aw.launcher.ui.components.ProjectDescription
import ru.aw.launcher.ui.screens.ProjectPageData
import ru.aw.launcher.ui.screens.ProjectScreen
import ru.aw.launcher.logs.LogSource
import ru.aw.launcher.instance.LocalBuild
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.packs.Modpack
import ru.aw.launcher.ui.App
import ru.aw.launcher.ui.CatalogTab
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.Modal
import ru.aw.launcher.ui.Screen
import ru.aw.launcher.ui.SplashContent
import ru.aw.launcher.ui.screens.BuildCreationDialog
import ru.aw.launcher.ui.screens.InstanceScreen
import ru.aw.launcher.ui.screens.ImportDirectoryDialog
import ru.aw.launcher.instance.LocalBuilds
import ru.aw.launcher.ui.theme.AWTheme
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

private const val FRAME_NANOS = 16_000_000L
private const val SETTLE_NANOS = 600_000_000L

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/preview").apply { mkdirs() }
    val scale = args.getOrNull(1)?.toFloatOrNull() ?: 1.25f
    val selectedScreens = args.getOrNull(2)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        ?.takeIf { it.isNotEmpty() }

    if(selectedScreens == setOf("discord-assets")) {
        for ((key,symbol) in listOf("playing" to "planet", "singleplayer" to "grass", "multiplayer" to "compass", "launching" to "flame")) {
            val bitmap = ru.aw.launcher.ui.InstanceIcons.generateArtwork(symbol,1024)
            org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(out,"$key.png").writeBytes(data.bytes) }
            }
        }
        File("branding/AWLauncher-mark.png").copyTo(File(out,"awlauncher.png"),overwrite=true)
        println("rendered Discord assets in $out"); exitProcess(0)
    }
    if(selectedScreens == setOf("icon-assets")) {
        val icons=File(out,"instance-icons").apply { mkdirs() }
        for(id in ru.aw.launcher.ui.InstanceIcons.symbols.map { it.first }+"cube") {
            val bitmap=ru.aw.launcher.ui.InstanceIcons.generateArtwork(id)
            org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> File(icons,"$id.png").writeBytes(data.bytes) }
            }
        }
        println("rendered $icons");exitProcess(0)
    }
    if(selectedScreens == setOf("flag-assets")) {
        val flags=File(out,"flags").apply { mkdirs() }
        for(id in Language.entries.map(ru.aw.launcher.ui.components.Brand::flagId).distinct()) {
            val source=File("build/flag-sources/$id.svg")
            org.jetbrains.skia.Data.makeFromBytes(source.readBytes()).use { data ->
                org.jetbrains.skia.svg.SVGDOM(data).use { svg ->
                    checkNotNull(svg.root) { "Invalid flag SVG: $id" }
                    svg.setContainerSize(96f,72f)
                    org.jetbrains.skia.Surface.makeRasterN32Premul(96,72).use { surface ->
                        surface.canvas.clear(0)
                        svg.render(surface.canvas)
                        surface.makeImageSnapshot().use { image ->
                            image.encodeToData(EncodedImageFormat.PNG)!!.use { png -> File(flags,"$id.png").writeBytes(png.bytes) }
                        }
                    }
                }
            }
        }
        println("rendered $flags");exitProcess(0)
    }

    Notices.file = null
    PlayHistory.cacheFile = null
    PlayHistory.historyFile = File(out, "activity-preview.json").toPath().also { copy ->
        runCatching { Files.copy(Paths.root.resolve("activity.json"), copy, StandardCopyOption.REPLACE_EXISTING) }
    }
    val preloaded = if (selectedScreens?.all { it.startsWith("skin-editor") || it.startsWith("downloads") || it.startsWith("migration") || it.startsWith("settings-redesign") || it.startsWith("accounts-redesign") } == true)
        ru.aw.launcher.core.PreloadResult(ru.aw.launcher.meta.VersionManifest(versions = listOf(
            ru.aw.launcher.meta.ManifestVersion(id = "1.21.11", url = ""))), emptyMap(), emptySet())
    else runBlocking { Preloader.run { _, _ -> } }.copy(manifestStale = false, loaderSupportStale = false)
    Settings.update { it.copy(language = Language.RU) }
    val state = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded)
    AccountManager.addOffline("AlexPlayer")

    fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        if (selectedScreens != null && name !in selectedScreens) return
        val scene = ImageComposeScene(
            width = (width * scale).toInt(),
            height = (height * scale).toInt(),
            density = Density(scale),
            content = { CompositionLocalProvider(ru.aw.launcher.ui.components.LocalDialogPreview provides true) { content() } },
        )
        try {
            var time = 0L
            while (time < SETTLE_NANOS) {
                scene.render(time).close()
                Thread.sleep(16)
                time += FRAME_NANOS
            }
            scene.render(time).use { image ->
                val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed for $name")
                png.use { File(out,"$name.png").writeBytes(it.bytes) }
            }
        } finally {
            scene.close()
        }
        println("rendered ${File(out, "$name.png")}")
    }

    if (selectedScreens?.all { it.startsWith("downloads") } == true) {
        state.downloads.enqueue("sample-active", "Fabulously Optimized · Fabric 1.21.11") { report ->
            report.stage("Загружаю файлы сборки")
            report.progress(ru.aw.launcher.net.DownloadProgress(17, 40, 42L * 1024 * 1024, 100L * 1024 * 1024, 8L * 1024 * 1024, "fabric-api.jar"))
            kotlinx.coroutines.awaitCancellation()
        }
        state.downloads.enqueue("sample-next", "Sodium · Моя сборка") {}
        state.downloads.enqueue("sample-third", "Файлы Minecraft · 1.21.11") {}
        state.screen = Screen.DOWNLOADS
        render("downloads", 1280, 780) { AWTheme(ThemeMode.DARK) { App(state, onGameStarted = {}) } }
        render("downloads-compact", 800, 520) { AWTheme(ThemeMode.DARK) { App(state, onGameStarted = {}) } }
        exitProcess(0)
    }

    if (selectedScreens?.all { it.startsWith("migration") } == true) {
        val build = LocalBuild("00000000-0000-0000-0000-000000000012", "Моя сборка", "1.20.1", LoaderKind.FABRIC)
        val entry = state.entryFor(build)
        val dir = Paths.root.resolve("migration-preview")
        fun item(name: String, title: String) = ru.aw.launcher.mods.InstalledItem(dir.resolve("mods/$name"),
            ru.aw.launcher.mods.ContentKind.MOD, true, "1".repeat(40), "example", title, "1.0", null, null)
        val version = Modrinth.Version("preview-new", "example", versionNumber = "0.110.0+1.21.11", gameVersions = listOf("1.21.11"),
            loaders = listOf("fabric"), files = listOf(Modrinth.VersionFile("https://cdn.modrinth.com/preview.jar", "new.jar")))
        val ready = ru.aw.launcher.instance.MigrationPlan(dir, "1.20.1", "1.21.11", LoaderKind.FABRIC, listOf(
            ru.aw.launcher.instance.MigrationMod(item("fabric-api.jar", "Fabric API"), version, null),
            ru.aw.launcher.instance.MigrationMod(item("custom-mod.jar", "Мод без новой версии"), null, "На Modrinth нет версии для 1.21.11")), emptyMap())
        render("migration", 1040, 780) { AWTheme(ThemeMode.DARK) { ru.aw.launcher.ui.dialogs.BuildMigrationDialog(state, entry, ready) } }
        render("migration-compact", 800, 520) { AWTheme(ThemeMode.DARK) { ru.aw.launcher.ui.dialogs.BuildMigrationDialog(state, entry, ready) } }
        exitProcess(0)
    }

    fun renderSkins() {
        val png = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        png.createGraphics().also { graphics ->
            graphics.color = java.awt.Color(179, 126, 91); graphics.fillRect(0, 0, 64, 32)
            graphics.color = java.awt.Color(39, 132, 153); graphics.fillRect(16, 16, 24, 16)
            graphics.color = java.awt.Color(54, 60, 96); graphics.fillRect(0, 16, 16, 16)
            graphics.color = java.awt.Color(179, 126, 91); graphics.fillRect(32, 48, 16, 16)
            graphics.color = java.awt.Color(54, 60, 96); graphics.fillRect(16, 48, 16, 16)
            graphics.color = java.awt.Color(78, 51, 40); graphics.fillRect(8, 8, 8, 3)
            graphics.color = java.awt.Color(49, 47, 58); graphics.fillRect(9, 12, 2, 1); graphics.fillRect(13, 12, 2, 1)
            graphics.composite = java.awt.AlphaComposite.Clear; graphics.fillRect(40, 8, 8, 8)
            graphics.dispose()
        }
        val fixture = File(out,"minecraft-steve.png").takeIf { it.isFile }?.let(javax.imageio.ImageIO::read)
        val sampleSkin = ru.aw.launcher.auth.MinecraftSkins.fromImage(fixture ?: png)
        val cape = java.awt.image.BufferedImage(64,32,java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().also { g ->
                g.color=java.awt.Color(35,191,121);g.fillRect(0,0,64,32)
                g.color=java.awt.Color(18,55,40);g.fillRect(14,4,6,10);g.dispose()
            }
        }
        val capeUrl="https://textures.minecraft.net/texture/aw-preview-cape"
        val cachedCape=Paths.cache.resolve("skins").resolve(ru.aw.launcher.core.sha1Of(capeUrl.byteInputStream())+".png")
        Files.createDirectories(cachedCape.parent)
        javax.imageio.ImageIO.write(cape,"png",cachedCape.toFile())
        val skinAccount = ru.aw.launcher.auth.Account("00000000000000000000000000000009", "SkinPreview", ru.aw.launcher.auth.AccountType.MICROSOFT,
            capes=listOf(ru.aw.launcher.auth.MinecraftCape("cape-preview",capeUrl,"ACTIVE","AW Preview")),capeId="cape-preview")
        AccountManager.upsert(skinAccount)
        for (language in listOf(Language.RU, Language.EN)) {
            Settings.update { it.copy(language = language) }
            render("skin-editor-${language.tag}", 1040, 720) { AWTheme(ThemeMode.OLED) { ru.aw.launcher.ui.dialogs.SkinDialog(state, skinAccount.uuid, preview = sampleSkin) } }
        }
        Settings.update { it.copy(language = Language.RU) }
        render("skin-editor-small",600,540) { AWTheme(ThemeMode.OLED) { ru.aw.launcher.ui.dialogs.SkinDialog(state,skinAccount.uuid,preview=sampleSkin) } }
        render("skin-editor-capes",1040,720) { AWTheme(ThemeMode.OLED) { ru.aw.launcher.ui.dialogs.SkinDialog(state,skinAccount.uuid,preview=sampleSkin,initialTab=1) } }
        render("skin-editor-capes-small",600,540) { AWTheme(ThemeMode.OLED) { ru.aw.launcher.ui.dialogs.SkinDialog(state,skinAccount.uuid,preview=sampleSkin,initialTab=1) } }
        Settings.update { it.copy(language = Language.RU) }
    }
    if (selectedScreens?.all { it.startsWith("skin-editor") } == true) { renderSkins(); exitProcess(0) }

    if (selectedScreens?.all { it.startsWith("settings-redesign") || it.startsWith("accounts-redesign") } == true) {
        val selected = ru.aw.launcher.auth.Account("00000000000000000000000000000010", "MinecraftPlayer", ru.aw.launcher.auth.AccountType.MICROSOFT,
            expiresAt = Long.MAX_VALUE)
        AccountManager.addOffline("CreativePlayer")
        AccountManager.upsert(selected)
        state.screen = Screen.ACCOUNTS
        render("accounts-redesign", 1280, 780) { AWTheme(ThemeMode.DARK) { App(state, onGameStarted = {}) } }
        render("accounts-redesign-small", 600, 540) { AWTheme(ThemeMode.DARK) { App(state, onGameStarted = {}) } }
        for ((name, section) in listOf("appearance" to 0, "game" to 2, "downloads" to 3, "about" to 4)) {
            state.modal = Modal.Settings(section = section)
            render("settings-redesign-$name", 1280, 780) { AWTheme(ThemeMode.DARK) { App(state, onGameStarted = {}) } }
            render("settings-redesign-$name-small", 600, 540) { AWTheme(ThemeMode.DARK) { App(state, onGameStarted = {}) } }
        }
        exitProcess(0)
    }

    if (selectedScreens?.all { it.startsWith("profile-directory") } == true) {
        val directory = File(out, "profile-picker-fixture").toPath()
        for (name in listOf("Adventure", "Better Minecraft", "Create", "PrismLauncher", "Survival")) {
            Files.createDirectories(directory.resolve(name))
        }
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            render("profile-directory-${mode.name.lowercase()}", 1040, 720) {
                AWTheme(mode) {
                    ru.aw.launcher.ui.dialogs.ProfileDirectoryPicker(onDismiss = {}, onSelect = {}, initialDirectory = directory)
                }
            }
        }
        render("profile-directory-compact", 600, 500) {
            AWTheme(ThemeMode.DARK) {
                ru.aw.launcher.ui.dialogs.ProfileDirectoryPicker(onDismiss = {}, onSelect = {}, initialDirectory = directory)
            }
        }
        render("profile-directory-small", 600, 420) {
            AWTheme(ThemeMode.DARK) {
                ru.aw.launcher.ui.dialogs.ProfileDirectoryPicker(onDismiss = {}, onSelect = {}, initialDirectory = directory)
            }
        }
        exitProcess(0)
    }

    render("splash", 520, 180) { AWTheme { SplashContent(0.7f, "Подтягиваю загрузчики модов") } }
    render("aw-home-dark", 1280, 780) { AWTheme(ThemeMode.DARK) { App(state, onGameStarted = {}) } }
    render("aw-home-light", 1280, 780) { AWTheme(ThemeMode.LIGHT) { App(state, onGameStarted = {}) } }

    val sample = state.currentEntry()
    val day = 24L * 60 * 60 * 1000
    val now = System.currentTimeMillis()
    Notices.post(
        NoticeLevel.ERROR, "Игре не хватило памяти. Добавь памяти в настройках сборки.", "Игра закрылась с ошибкой",
        sample?.key, sample?.label, listOf(NoticeAction.LOGS, NoticeAction.MODS), now = now - day - 3_600_000,
    )
    Notices.post(NoticeLevel.SUCCESS, "Вход выполнен: AlexPlayer", now = now - day)
    Notices.post(
        NoticeLevel.INFO, "Minecraft ${sample?.id} готов к запуску", null,
        sample?.key, sample?.label, now = now - 7_200_000,
    )
    Notices.post(NoticeLevel.INFO, "Minecraft ${sample?.id} готов к запуску", null, sample?.key, sample?.label, now = now - 3_600_000)
    Notices.post(NoticeLevel.SUCCESS, "Sodium 0.8.15-beta.1 → 0.8.12", "Моды исправлены", sample?.key, sample?.label, now = now - 60_000)
    Notices.markSeen()
    state.fail(
        "Sodium 0.8.15-beta.1 несовместим с Iris 1.10.7",
        sample,
        "Игра не запущена: моды несовместимы",
        listOf(NoticeAction.FIX_MODS, NoticeAction.PLAY_ANYWAY),
    )
    render("home-toast", 1040, 660) { AWTheme { App(state, onGameStarted = {}) } }
    state.toast?.let { state.dismissToast(it.id) }

    runBlocking { state.loadActivity() }
    for (screen in Screen.entries) {
        state.modal = null
        if (screen == Screen.NOTICES) state.openNotices() else state.screen = screen
        render(screen.name.lowercase(), 1040, 660) { AWTheme { App(state, onGameStarted = {}) } }
    }
    render("build-creation-flow", 640, 760) {
        AWTheme {
            BuildCreationDialog(
                onDismiss = {},
                onCustom = {},
                onSearch = { _, _ -> },
                onMrpack = {},
                onImportDirectory = {},
            )
        }
    }

    state.screen = Screen.ACTIVITY
    render("activity-tall", 1040, 1000) { AWTheme { App(state, onGameStarted = {}) } }

    state.screen = Screen.HOME
    val entry = state.currentEntry()
    val dialogs = listOfNotNull(
        entry?.let { "dialog-delete" to Modal.Delete(it) },
        entry?.let { "dialog-logs" to Modal.Logs(state.gameDirOf(it), it.label, LogSource.GAME) },
    )
    for ((name, modal) in dialogs) {
        state.modal = modal
        render(name, 1040, 660) { AWTheme { App(state, onGameStarted = {}) } }
    }
    state.modal = null

    for (tab in listOf(CatalogTab.MODS, CatalogTab.SHADERS, CatalogTab.INSTALLED)) {
        state.openCatalog(tab = tab)
        render("catalog-${tab.name.lowercase()}", 1040, 660) { AWTheme { App(state, onGameStarted = {}) } }
    }

    val pack = Modpack(
        id = "fabulously-optimized",
        title = "Fabulously Optimized",
        version = "14.1.0",
        gameVersion = "26.2",
        loader = LoaderKind.FABRIC,
        loaderVersion = "0.19.5",
        projectId = "1KVo5zza",
        versionId = "ssWn7YI0",
    )
    val localBuild = LocalBuild("72a4ca77-3aae-4c19-b237-652bf6a4fd12", "Better Vanilla+", "26.2", LoaderKind.FABRIC)
    val withBuilds = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded.copy(packs = listOf(pack), builds = listOf(localBuild)))
    withBuilds.selectEntry(withBuilds.entryFor(localBuild))
    withBuilds.screen = Screen.PLAY
    render("play-local-builds", 1280, 780) { AWTheme { App(withBuilds, onGameStarted = {}) } }
    render("play-local-builds-compact", 1040, 660) { AWTheme(ThemeMode.OLED) { App(withBuilds, onGameStarted = {}) } }
    render("play-local-builds-minimum", 600, 420) { AWTheme(ThemeMode.OLED) { App(withBuilds, onGameStarted = {}) } }
    render("play-local-builds-wide", 1600, 900) { AWTheme(ThemeMode.OLED) { App(withBuilds, onGameStarted = {}) } }
    withBuilds.screen = Screen.BUILDS
    render("builds-profiles", 1280, 780) { AWTheme { App(withBuilds, onGameStarted = {}) } }

    if (selectedScreens?.any { it.startsWith("screenshots-") } == true) {
        val screenshotDir = Files.createDirectories(withBuilds.gameDirOf(withBuilds.entryFor(localBuild)).resolve("screenshots"))
        for (index in 1..4) {
            val artwork = File("src/main/resources/instance-icons/${listOf("grass", "crystal", "planet", "fox")[index - 1]}.png")
            val file = screenshotDir.resolve("2026-10-05_12-00-0$index.png")
            Files.copy(artwork.toPath(), file, StandardCopyOption.REPLACE_EXISTING)
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(now - index * 60_000))
        }
        withBuilds.screen = Screen.SCREENSHOTS
        render("screenshots-gallery", 1280, 780) { AWTheme(ThemeMode.OLED) { App(withBuilds, onGameStarted = {}) } }
        render("screenshots-small", 600, 540) { AWTheme(ThemeMode.OLED) { App(withBuilds, onGameStarted = {}) } }
    }

    if (selectedScreens == null || selectedScreens.any { it.startsWith("instance-") || it == "import-profiles" }) {
        val instanceDir = LocalBuilds.dirOf(localBuild)
        Files.createDirectories(instanceDir.resolve("mods"))
        Files.createDirectories(instanceDir.resolve("saves/Мир выживания"))
        Files.createDirectories(instanceDir.resolve("logs"))
        Files.writeString(instanceDir.resolve("mods/Sodium-0.6.13.jar"), "preview content")
        Files.writeString(instanceDir.resolve("mods/Iris-1.8.8.jar.disabled"), "preview content")
        for(index in 1..70) Files.writeString(instanceDir.resolve("mods/Project-${index.toString().padStart(2,'0')}.jar"), "preview content")
        Files.writeString(instanceDir.resolve("options.txt"), "fullscreen:false")
        Files.writeString(instanceDir.resolve("logs/latest.log"), "[19:22:14] [main/INFO]: Loading Minecraft 26.2 with Fabric Loader 0.19.5\n[19:24:10] [Render thread/INFO]: Preparing spawn area\n")
        withBuilds.openInstance(withBuilds.entryFor(localBuild))
        for (index in 0..4) {
            render("instance-${listOf("content", "files", "worlds", "journal", "statistics")[index]}", 1280, 780) {
                AWTheme(ThemeMode.OLED) {
                    Column(Modifier.fillMaxSize()) {
                        ru.aw.launcher.ui.LauncherTitleBar(withBuilds)
                        Box(Modifier.weight(1f)) { InstanceScreen(withBuilds, initialTab = index) }
                    }
                }
            }
        }
        render("instance-content-small", 1050, 660) { AWTheme(ThemeMode.OLED) { App(withBuilds, onGameStarted = {}) } }
        render("instance-content-minimum",600,420) { AWTheme(ThemeMode.OLED) { App(withBuilds,onGameStarted={}) } }
        render("import-progress",1050,660) { AWTheme(ThemeMode.OLED) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.BottomCenter) {
                Column(Modifier.fillMaxSize()) {
                    ru.aw.launcher.ui.LauncherTitleBar(withBuilds)
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    ru.aw.launcher.ui.components.StatusStrip(ru.aw.launcher.net.DownloadProgress(280,720,620_000_000,1_000_000_000,60_000_000,"region.mca"),null,"Переношу Adventure")
                }
            }
        } }
        val prism = out.toPath().resolve("import-preview/instances/Adventure")
        Files.createDirectories(prism.resolve(".minecraft/mods"))
        Files.writeString(prism.resolve("instance.cfg"), "[General]\nname=Adventure\ntotalTimePlayed=28800\nlastLaunchTime=1790233795000\n")
        Files.writeString(prism.resolve("mmc-pack.json"), """{"components":[{"uid":"net.minecraft","version":"1.21.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.14"}]}""")
        render("import-profiles", 800, 580) { AWTheme(ThemeMode.OLED) { ImportDirectoryDialog(withBuilds, prism.parent.parent, {}, {}) } }
    }

    val dashboardBuilds = listOf(
        LocalBuild("00000000-0000-0000-0000-000000000001", "Мир выживания", "1.21.1", LoaderKind.FABRIC),
        LocalBuild("00000000-0000-0000-0000-000000000002", "Строительство", "1.21.4", LoaderKind.FABRIC),
        LocalBuild("00000000-0000-0000-0000-000000000003", "Путешествие", "1.20.1", LoaderKind.FORGE),
        LocalBuild("00000000-0000-0000-0000-000000000004", "Vanilla", "26.2", LoaderKind.VANILLA),
        LocalBuild("00000000-0000-0000-0000-000000000005", "Тестовый мир", "26.3", LoaderKind.NEOFORGE),
    )
    val dashboard = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded.copy(builds = dashboardBuilds, packs = listOf(pack)))
    dashboard.selectEntry(dashboard.entryFor(dashboardBuilds.first()))
    PlayHistory.resetForTests()
    PlayHistory.historyFile = File(out, "dashboard-history-preview.json").toPath().also { path ->
        val sessions = dashboardBuilds.take(4).mapIndexed { index, build ->
            PlaySession(build.id, build.versionId, build.loader, now - (index + 1) * day, now - (index + 1) * day + 3_600_000,
                buildId = build.id, buildName = build.name)
        }
        path.toFile().writeText(Json.encodeToString(sessions))
    }
    runBlocking { dashboard.loadActivity() }
    val group = ru.aw.launcher.instance.LibraryGroup("preview-group", "Выживание")
    Settings.update { it.copy(libraryGroups = listOf(group)) }
    dashboardBuilds.forEachIndexed { index, build ->
        ru.aw.launcher.instance.InstanceStore.update(LocalBuilds.dirOf(build)) {
            it.copy(iconPreset = ru.aw.launcher.ui.InstanceIcons.symbols[index].first,
                iconBackground = ru.aw.launcher.ui.InstanceIcons.backgrounds[index].first,
                favorite = index == 0, groupId = group.id.takeIf { index < 2 })
        }
    }
    dashboard.loadLibraryMetadata()
    render("dashboard-library", 1280, 780) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    render("dashboard-library-small", 1050, 660) { AWTheme(ThemeMode.LIGHT) { App(dashboard, onGameStarted = {}) } }
    render("dashboard-library-half", 960, 700) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    render("dashboard-library-minimum", 600, 540) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }

    dashboard.editInstanceIcon(dashboard.entryFor(dashboardBuilds.first()))
    render("icon-editor", 1040, 780) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    dashboard.modal = Modal.Groups(listOf(dashboard.entryFor(dashboardBuilds.first()).key))
    render("library-groups", 1040, 780) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    dashboard.modal = null
    dashboard.openInstance(dashboard.entryFor(dashboardBuilds.first()))
    render("instance-half", 960, 700) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    render("instance-minimum", 600, 540) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    dashboard.openSettings(dashboard.entryFor(dashboardBuilds.first()), section = 1)
    render("instance-settings-modal", 1050, 720) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    render("instance-settings-half", 960, 700) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    render("instance-settings-minimum", 600, 540) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    dashboard.modal = null
    for(theme in listOf(ThemeMode.OLED, ThemeMode.LIGHT)) {
        Settings.update { it.copy(language = Language.RU) }
        dashboard.modal = Modal.Settings(section = 4)
        render("settings-about-${theme.name.lowercase()}",1050,720) { AWTheme(theme) { App(dashboard,onGameStarted={}) } }
    }
    for (language in listOf(Language.EN, Language.RU, Language.ZH_CN, Language.AR)) {
        Settings.update { it.copy(language = language) }
        dashboard.modal = Modal.Settings(section = 1)
        render("settings-languages-${language.tag}", 1050, 720) { AWTheme(ThemeMode.OLED) { App(dashboard, onGameStarted = {}) } }
    }
    for (language in listOf(Language.RU,Language.EN)) {
        Settings.update { it.copy(language = language) }
        dashboard.modal = Modal.Settings(section = 5)
        render("settings-discord-${language.tag}",1050,720) { AWTheme(ThemeMode.OLED) { App(dashboard,onGameStarted={}) } }
        if(language == Language.RU) render("settings-discord-minimum",600,540) { AWTheme(ThemeMode.OLED) { App(dashboard,onGameStarted={}) } }
    }
    dashboard.modal = null
    Settings.update { it.copy(language = Language.EN) }
    val withPack = LauncherState(CoroutineScope(Dispatchers.Unconfined), preloaded.copy(packs = listOf(pack)))
    withPack.selectEntry(withPack.entryFor(pack))
    render("home-pack", 1040, 660) { AWTheme { App(withPack, onGameStarted = {}) } }
    withPack.openCatalog(tab = CatalogTab.PACKS)
    render("catalog-packs-installed", 1040, 660) { AWTheme { App(withPack, onGameStarted = {}) } }
    for (language in listOf(Language.RU, Language.EN, Language.ES)) {
        Settings.update { it.copy(language = language, memoryMb = 3584, themeMode = ThemeMode.OLED) }
        state.screen = Screen.SETTINGS
        render("settings-${language.tag}-oled", 1280, 1000) { AWTheme(ThemeMode.OLED) { App(state, onGameStarted = {}) } }
        state.screen = Screen.HOME
        state.modal = null
        render("home-${language.tag}-oled", 1280, 780) { AWTheme(ThemeMode.OLED) { App(state, onGameStarted = {}) } }
    }
    Settings.update { it.copy(language = Language.RU) }
    if (selectedScreens?.any { it.startsWith("skin-editor") } == true) renderSkins()
    if (selectedScreens == null || selectedScreens.any { it.startsWith("project-") }) {
        val (project, versions) = runBlocking {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val project = async { Modrinth.project("1KVo5zza") }
                    val versions = async { Modrinth.versions("1KVo5zza") }
                    project.await() to versions.await()
                }
            }
        }
        val body = ProjectDescription.parse(project.body)
        val page = ProjectPageData(project, versions, body)
        val hit = Modrinth.SearchHit(projectId = project.id, title = project.title, slug = project.slug, description = project.description, iconUrl = project.iconUrl, author = "robotoor", projectType = "modpack")
        runBlocking {
            (project.gallery.take(4).map { it.url } + body.flatMap { element -> element.select("img").map { it.attr("src") } }.take(6))
                .filter { it.startsWith("https://") }.distinct().forEach { ModIcons.load(it, 1280) }
        }
        for (index in 0..2) {
            render("project-${listOf("description", "versions", "gallery")[index]}", 1026, 780) {
                AWTheme(ThemeMode.OLED) {
                    ProjectScreen(hit, false, null, true, null, { true }, {}, {}, preview = page, initialTab = index)
                }
            }
        }
    }
    exitProcess(0)
}
