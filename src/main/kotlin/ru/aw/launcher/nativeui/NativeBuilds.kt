package ru.aw.launcher.nativeui

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.aw.launcher.core.*
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Paths
import ru.aw.launcher.instance.*
import ru.aw.launcher.launch.GameLauncher
import ru.aw.launcher.meta.*
import ru.aw.launcher.mods.*
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.packs.Modpacks
import java.awt.FileDialog
import java.awt.Frame
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/** Existing installation/library services exposed as data operations; application dialogs live in Tauri. */
internal object NativeBuilds {
    val operations = setOf("buildEditor", "buildLoaderVersions", "buildVersions", "buildDeleteInfo", "buildExportChoices",
        "buildPickPath", "buildPickIcon", "buildSaveIcon", "buildGroups", "buildDownload", "buildReinstall", "buildDelete",
        "buildMigrationAnalyze", "buildMigrationGet", "buildMigrationApply", "buildExport")
    val reads = setOf("buildEditor", "buildLoaderVersions", "buildVersions", "buildDeleteInfo", "buildExportChoices", "buildMigrationGet", "buildPickPath", "buildPickIcon")
    private val excluded = setOf(InstanceStore.FILE_NAME, Modpacks.MANIFEST, "aw-build-history", "aw-migration-backups")
    private val iconCache get() = Paths.cache.resolve("native-icons")
    private val plans get() = Paths.cache.resolve("native-migrations")
    @Serializable private data class Mod(val file: String, val enabled: Boolean, val sha1: String, val project: String?,
        val title: String, val version: String, val icon: String?, val versionId: String?, val replacement: Modrinth.Version?, val reason: String?)
    @Serializable private data class Failure(val file: String, val title: String, val message: String)
    @Serializable private data class Plan(val key: String, val source: String, val target: String, val loader: LoaderKind,
        val mods: List<Mod>, val fingerprint: Map<String,String>, val failure: Failure? = null)
    private fun names(dir: Path): List<String> = Files.list(dir).use { stream ->
        stream.filter { !Files.isSymbolicLink(it) && it.fileName.toString() !in excluded }.map { it.fileName.toString() }.sorted().toList()
    }
    private fun planFile(id: String): Path {
        require(runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false)) { "Проверь моды ещё раз" }
        return plans.resolve("$id.json")
    }
    private fun readPlan(id: String, key: String): Plan {
        val file=planFile(id)
        require(Files.isRegularFile(file,NOFOLLOW_LINKS)) { "Проверка устарела. Проверь моды ещё раз" }
        val bytes=Files.newInputStream(file).use { it.readNBytes(8*1024*1024+1) }
        require(bytes.size<=8*1024*1024) { "Проверка модов слишком большая" }
        return Json.decodeFromString<Plan>(bytes.toString(Charsets.UTF_8)).also { require(it.key==key) { "Проверка относится к другой сборке" } }
    }
    private fun describe(id: String, plan: Plan) = buildJsonObject {
        put("id",id);put("source",plan.source);put("target",plan.target)
        put("mods",buildJsonArray { plan.mods.forEach { mod -> add(buildJsonObject {
            put("file",mod.file);put("title",mod.title);put("enabled",mod.enabled);put("version",mod.version)
            put("compatible",mod.replacement!=null);mod.replacement?.let { put("replacement",it.versionNumber) };mod.reason?.let { put("reason",it) }
        }) } })
        plan.failure?.let { put("failure",buildJsonObject { put("file",it.file);put("title",it.title);put("message",it.message) }) }
    }
    private fun materialize(plan: Plan, dir: Path): MigrationPlan = MigrationPlan(dir.toAbsolutePath().normalize(),plan.source,plan.target,plan.loader,
        plan.mods.map { mod ->
            require(mod.file.isNotBlank() && mod.file !in setOf(".","..") && mod.file.none { it=='/'||it=='\\'||it==':'||it.code<32 }) { "Некорректный файл мода" }
            MigrationMod(InstalledItem(dir.resolve("mods").resolve(mod.file),ContentKind.MOD,mod.enabled,mod.sha1,mod.project,mod.title,mod.version,mod.icon,null,mod.versionId),mod.replacement,mod.reason)
        },plan.fingerprint)
    private fun noGames() {
        val playing=ProcessHandle.allProcesses().use { stream -> stream.anyMatch { p -> p.info().arguments().orElse(emptyArray()).any { it=="--gameDir" } } }
        if(playing)throw IOException("Закрой Minecraft перед изменением файлов версии")
        if(Shell.isWindows) {
            val script="\$games=Get-CimInstance Win32_Process | Where-Object { \$_.Name -match '^javaw?\\.exe\$' -and \$_.CommandLine -and \$_.CommandLine.Contains('--gameDir') }; if(\$games){exit 31}else{exit 0}"
            if(Shell.runHidden(Shell.powershell(script),15000)!=0)throw IOException("Закрой Minecraft перед изменением файлов версии")
        }
    }
    private fun choose(title: String, save: Boolean = false, file: String? = null): Path? {
        var result: Path?=null
        java.awt.EventQueue.invokeAndWait {
            FileDialog(null as Frame?,I18n.text(title),if(save)FileDialog.SAVE else FileDialog.LOAD).let { dialog ->
                try { dialog.file=file;dialog.isVisible=true;result=dialog.files.firstOrNull()?.toPath() } finally { dialog.dispose() }
            }
        }
        return result
    }
    internal fun chooseFiles(title: String, multiple: Boolean = false): List<Path> {
        var files=emptyList<Path>()
        java.awt.EventQueue.invokeAndWait {
            FileDialog(null as Frame?,I18n.text(title),FileDialog.LOAD).let { dialog ->
                try { dialog.isMultipleMode=multiple;dialog.isVisible=true;files=dialog.files.map { it.toPath() } } finally { dialog.dispose() }
            }
        }
        return files
    }
    private fun groups(r: NativeTasks.Request): JsonElement {
        val action=r.options["action"]?.jsonPrimitive?.content ?: "list"
        if(action!="list") {
            val name=r.title.trim();val current=Settings.current.libraryGroups
            if(action in setOf("create","rename")) {
                require(name.isNotBlank() && name.length<=40 && current.none { it.id!=r.project && it.name.equals(name,true) }) { "Группа должна иметь уникальное название до 40 символов" }
            }
            val updated=when(action) {
                "create" -> current+LibraryGroup(UUID.randomUUID().toString(),name)
                "rename" -> { require(current.any { it.id==r.project }) { "Группа больше не найдена" };current.map { if(it.id==r.project)it.copy(name=name) else it } }
                "remove" -> current.filterNot { it.id==r.project }
                else -> throw IOException("Неизвестное действие группы")
            }
            if(action=="remove") for(dir in LocalBuilds.list().map(LocalBuilds::dirOf)+Modpacks.list().map(Modpacks::dirOf)) {
                if(InstanceStore.get(dir).groupId==r.project)InstanceStore.update(dir) { it.copy(groupId=null) }
            }
            Settings.update { it.copy(libraryGroups=updated) }
        }
        return Json.encodeToJsonElement(Settings.current.libraryGroups)
    }
    suspend fun execute(r: NativeTasks.Request, onStage: (String)->Unit, onProgress: (DownloadProgress)->Unit): JsonElement {
        if(r.op=="buildGroups")return groups(r)
        val t=NativeTasks.target(r.target)
        val pack=if(r.target.startsWith("pack:"))Modpacks.list().firstOrNull { it.id==r.target.removePrefix("pack:") } else null
        when(r.op) {
            "buildEditor" -> return buildJsonObject {
                put("options",Json.encodeToJsonElement(InstanceStore.get(t.dir)));put("loader",t.loader.name);put("game",t.game)
                put("canRename",r.target.startsWith("build:"));put("downloadAllowed",pack==null||pack.customGameVersion)
                t.loaderVersion?.let { put("profileLoaderVersion",it) };put("groups",Json.encodeToJsonElement(Settings.current.libraryGroups))
                put("memoryLimit",SettingsDefaults.memoryLimit(SettingsDefaults.totalSystemMemoryMb()))
                put("recommendedMemory",SettingsDefaults.recommendedMemory(SettingsDefaults.totalSystemMemoryMb()))
                put("memoryPresets",Json.encodeToJsonElement(SettingsDefaults.memoryPresets(SettingsDefaults.totalSystemMemoryMb())))
            }
            "buildLoaderVersions" -> return buildJsonArray { LoaderRepository.versionsFor(t.loader,t.game,refresh=true).forEach { version -> add(buildJsonObject {
                put("version",version.version);put("label",version.label);put("stable",version.stable)
            }) } }
            "buildVersions" -> {
                val supported=LoaderRepository.supportedGameVersions(t.loader)
                return buildJsonArray { VersionManifestRepository.load().versions.filter { it.id!=t.game && (t.loader==LoaderKind.VANILLA||it.id in supported) &&
                    (it.type=="release" || (it.type=="snapshot" && Settings.current.showSnapshots) || (it.type in setOf("old_alpha","old_beta") && Settings.current.showOldVersions)) }.forEach { add(JsonPrimitive(it.id)) } }
            }
            "buildExportChoices" -> return Json.encodeToJsonElement(names(t.dir))
            "buildDeleteInfo" -> return buildJsonObject { put("bytes",Storage.sizeOf(t.dir));put("worlds",Storage.hasWorlds(t.dir));put("trash",Storage.trashAvailable) }
            "buildPickPath" -> {
                val isExport=r.kind=="export"
                val picked=choose(if(isExport)"Сохранить сборку" else "Выбрать Java",isExport,if(isExport)r.title.replace(Regex("[\\\\/:*?\"<>|]"),"_")+".mrpack" else null) ?: return JsonNull
                val path=if(isExport&&!picked.fileName.toString().endsWith(".mrpack",true))picked.resolveSibling("${picked.fileName}.mrpack") else picked
                return buildJsonObject { put("path",path.toAbsolutePath().toString());put("exists",Files.exists(path)) }
            }
            "buildPickIcon" -> {
                val picked=choose("Выбрать иконку сборки") ?: return JsonNull
                Files.createDirectories(iconCache);val name=InstanceImages.import(iconCache,picked);val bytes=Files.readAllBytes(iconCache.resolve(name))
                val mime=if(bytes.size>12&&bytes[0]==0xff.toByte()&&bytes[1]==0xd8.toByte())"image/jpeg" else if(bytes.take(4).toByteArray().toString(Charsets.US_ASCII)=="RIFF")"image/webp" else "image/png"
                return buildJsonObject { put("image",name);put("preview","data:$mime;base64,"+Base64.getEncoder().encodeToString(bytes)) }
            }
            "buildMigrationGet" -> return describe(r.snapshot,readPlan(r.snapshot,r.target))
        }
        NativeTasks.editable(t)
        when(r.op) {
            "buildExport" -> {
                val path=r.file.takeIf { it.isNotBlank() }?.let(Path::of) ?: choose("Сохранить сборку",true,r.title+".mrpack") ?: return JsonNull
                require(path.isAbsolute && path.fileName.toString().endsWith(".mrpack",true)) { "Выбери файл .mrpack" }
                require(!Files.exists(path) || r.options["overwrite"]?.jsonPrimitive?.booleanOrNull==true) { "Подтверди замену существующего файла" }
                val choices=names(t.dir).toSet()
                val selected=r.options["selected"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: choices
                require(selected.all { it in choices }) { "Список файлов изменился. Открой экспорт ещё раз" }
                var loaderVersion=InstanceStore.get(t.dir).launchLoaderVersion(t.loaderVersion)
                if(t.loader.isModded && loaderVersion==null)loaderVersion=LoaderRepository.latestBuild(t.loader,t.game)
                Modpacks.export(t.dir,path,r.title.ifBlank { t.game },t.game,t.loader,loaderVersion,selected,onProgress=NativeTasks::progressFraction)
                return JsonPrimitive("Сборка экспортирована")
            }
            "buildSaveIcon" -> {
                val reset=r.options["reset"]?.jsonPrimitive?.booleanOrNull==true
                val symbol=r.options["symbol"]?.jsonPrimitive?.contentOrNull;val background=r.options["background"]?.jsonPrimitive?.contentOrNull
                require(ru.aw.launcher.ui.InstanceIcons.validSymbol(symbol)&&ru.aw.launcher.ui.InstanceIcons.validBackground(background)) { "Выбери существующую иконку и фон" }
                val keepCurrent=r.options["keepCurrent"]?.jsonPrimitive?.booleanOrNull==true
                val image=if(reset)null else if(keepCurrent)InstanceStore.get(t.dir).iconImage else r.options["image"]?.jsonPrimitive?.contentOrNull?.let { name ->
                    val source=InstanceImages.resolve(iconCache,name) ?: throw IOException("Выбери изображение ещё раз")
                    InstanceImages.import(t.dir,source)
                }
                val updated=InstanceStore.update(t.dir) { it.copy(iconPreset=if(reset||image!=null)null else symbol,iconBackground=if(reset)null else background,iconImage=if(reset)null else image,iconUrl=null) }
                return Json.encodeToJsonElement(updated)
            }
            "buildDownload", "buildReinstall" -> {
                if(r.op=="buildReinstall")noGames()
                if(pack!=null&&!pack.customGameVersion) {
                    require(r.op=="buildReinstall") { "Файлы этого модпака устанавливаются вместе со сборкой" }
                    Modpacks.install(Modpacks.reinstallSource(pack),onStage,onProgress)
                } else {
                    if(r.op=="buildReinstall") {Storage.deleteVersionFiles(Storage.versionIdsOf(t.game,t.loader,VersionManifestRepository.load().versions.map { it.id }));VerifyCache.clear()}
                    GameLauncher.prepare(t.game,t.loader,gameDir=t.dir,loaderVersion=InstanceStore.get(t.dir).launchLoaderVersion(t.loaderVersion),onStage=onStage,onProgress=onProgress)
                }
                return JsonPrimitive(if(r.op=="buildReinstall")"Сборка переустановлена" else "Файлы Minecraft загружены")
            }
            "buildDelete" -> {
                require(r.options["confirmed"]?.jsonPrimitive?.booleanOrNull==true) { "Подтверди удаление сборки" }
                require(!Files.isSymbolicLink(t.dir)) { "Папка сборки не должна быть ссылкой" }
                val root=if(pack==null)Settings.buildsDir() else Settings.packsDir()
                require(t.dir.toAbsolutePath().normalize().parent==root.toAbsolutePath().normalize()) { "Некорректная папка сборки" }
                Storage.deleteGameDir(t.dir)
                if(pack==null){LocalBuilds.remove(r.target.removePrefix("build:"));Settings.update { if(it.lastBuildId==r.target.removePrefix("build:"))it.copy(lastBuildId=null) else it }}
                else Settings.update { if(it.lastPack==pack.id)it.copy(lastPack=null) else it }
                return JsonPrimitive("Сборка удалена")
            }
            "buildMigrationAnalyze" -> {
                noGames();val target=r.version ?: throw IOException("Выбери новую версию Minecraft")
                require(VersionManifestRepository.load().versions.any { it.id==target } && (t.loader==LoaderKind.VANILLA||target in LoaderRepository.supportedGameVersions(t.loader))) { "Выбери доступную версию Minecraft с этим загрузчиком" }
                val analyzed=BuildMigration.analyze(t.dir,t.game,target,t.loader)
                val plan=Plan(r.target,t.game,target,t.loader,analyzed.mods.map { Mod(it.item.fileName,it.item.enabled,it.item.sha1,it.item.projectId,it.item.title,it.item.versionNumber,it.item.iconUrl,it.item.versionId,it.replacement,it.reason) },analyzed.fingerprint)
                Files.createDirectories(plans)
                Files.list(plans).use { stream -> stream.filter { Files.isRegularFile(it,NOFOLLOW_LINKS)&&Files.getLastModifiedTime(it).toMillis()<System.currentTimeMillis()-86400000 }.forEach { Files.deleteIfExists(it) } }
                val id=UUID.randomUUID().toString();planFile(id).writeAtomically(Json.encodeToString(plan));return describe(id,plan)
            }
            "buildMigrationApply" -> {
                noGames();val stored=readPlan(r.snapshot,r.target)
                require(stored.source==t.game&&stored.loader==t.loader) { "Сборка изменилась. Проверь моды ещё раз" }
                var plan=materialize(stored,t.dir)
                val disabled=r.options["disableFailed"]?.jsonPrimitive?.contentOrNull
                if(disabled!=null) {val failure=stored.failure?.takeIf { it.file==disabled } ?: throw IOException("Проверь моды ещё раз");plan=BuildMigration.disableFailedMod(plan,MigrationModFailure(failure.file,failure.title,IOException(failure.message)))}
                val keep=r.options["keep"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()-setOfNotNull(disabled)
                require(keep.all { file -> plan.mods.any { it.item.fileName==file&&it.item.enabled&&it.replacement==null } }) { "Некорректный список модов" }
                try {
                    BuildHistory.record(t.dir,t.game,t.loader,"Смена версии Minecraft",t.loaderVersion,targetGameVersion=stored.target) {
                        BuildMigration.apply(plan,keep,onStage,onProgress,prepareGame={dir->GameLauncher.prepare(stored.target,t.loader,gameDir=dir,onStage=onStage,onProgress=onProgress)},commitVersion={
                            if(pack==null)LocalBuilds.changeGameVersion(r.target.removePrefix("build:"),t.game,stored.target) else Modpacks.changeGameVersion(pack,stored.target)
                        })
                    }
                }catch(failure: MigrationModFailure) {
                    val updated=stored.copy(failure=Failure(failure.fileName,failure.modTitle,failure.cause?.message ?: failure.message.orEmpty()))
                    planFile(r.snapshot).writeAtomically(Json.encodeToString(updated))
                    return buildJsonObject {put("failed",true);put("message",failure.message);put("plan",describe(r.snapshot,updated))}
                }
                Files.deleteIfExists(planFile(r.snapshot));return JsonPrimitive("Сборка обновлена до Minecraft ${stored.target}")
            }
        }
        throw IOException("Неизвестное действие сборки")
    }
}
