package ru.aw.launcher.nativeui

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.aw.launcher.core.*
import ru.aw.launcher.core.Paths
import ru.aw.launcher.instance.*
import ru.aw.launcher.mods.*
import ru.aw.launcher.packs.Modpacks
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.launch.GameLauncher
import ru.aw.launcher.auth.AccountManager
import ru.aw.launcher.meta.LoaderKind
import java.nio.file.*
import java.io.IOException
import kotlin.concurrent.thread

/** Local JSON-lines adapter; game files continue to use the verified installation engine. */
object NativeTasks {
    private var protocolOut:java.io.PrintStream=System.out
    @Serializable data class Request(val op:String, val target:String="", val project:String="", val title:String="", val slug:String="", val kind:String="mod", val version:String?=null, val file:String="", val enabled:Boolean=false, val world:String="", val snapshot:String="",val options:JsonObject=JsonObject(emptyMap()))
    private data class Target(val dir:Path,val game:String,val loader:LoaderKind,val loaderVersion:String?)
    @Synchronized private fun send(type:String, data:JsonElement=JsonNull) { protocolOut.println("AW_RPC "+buildJsonObject { put("type",type);put("data",data) });protocolOut.flush() }
    private fun stage(text:String)=send("stage",JsonPrimitive(text))
    private fun progress(p:DownloadProgress)=send("progress",buildJsonObject{put("fraction",p.fraction);put("completedBytes",p.completedBytes);put("totalBytes",p.totalBytes);put("bytesPerSecond",p.bytesPerSecond);put("currentFile",p.currentFile)})
    private fun accountsData():JsonArray=buildJsonArray {
        AccountManager.accounts.value.forEach { a->add(buildJsonObject {
            put("id",a.uuid);put("name",a.name);put("type",a.type.name);put("selected",AccountManager.selected.value?.uuid==a.uuid)
            if(!a.isOffline){put("skinModel",a.skinModel.name);put("capes",ru.aw.launcher.core.Json.encodeToJsonElement(a.capes));a.capeId?.let{put("capeId",it)}}
            ru.aw.launcher.ui.SkinHeads.cachedTexture(a)?.let(ru.aw.launcher.ui.SkinHeads::face)?.let { face ->
                java.io.ByteArrayOutputStream().use { output ->
                    if(javax.imageio.ImageIO.write(face,"png",output))put("face","data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(output.toByteArray()))
                }
            }
        }) }
    }
    private suspend fun accountFace() {
            runCatching {
                AccountManager.load()
                AccountManager.selected.value?.let { account ->
                    ru.aw.launcher.ui.SkinHeads.texture(account)?.let(ru.aw.launcher.ui.SkinHeads::face)?.let { face ->
                        send("accountFace",buildJsonObject {put("width",face.width);put("height",face.height);put("pixels",buildJsonArray {for(y in 0 until face.height)for(x in 0 until face.width)add(face.getRGB(x,y))})})
                    }
                }
            }
    }
    private fun target(key:String):Target {
        if(key.startsWith("build:")){val b=LocalBuilds.list().firstOrNull{it.id==key.removePrefix("build:")}?:throw IOException("Сборка больше не найдена");return Target(LocalBuilds.dirOf(b),b.versionId,b.loader,b.loaderVersion)}
        if(key.startsWith("pack:")){val p=Modpacks.list().firstOrNull{it.id==key.removePrefix("pack:")}?:throw IOException("Модпак больше не найден");return Target(Modpacks.dirOf(p),p.gameVersion,p.loader,p.loaderVersion)}
        throw IOException("Выбери сборку")
    }
    private fun editable(t:Target){
        val root=t.dir.toAbsolutePath().normalize().toString()
        val playing=ProcessHandle.allProcesses().use { stream->stream.anyMatch { p->val args=p.info().arguments().orElse(emptyArray());args.indices.any{args[it]=="--gameDir"&&args.getOrNull(it+1)?.let { Path.of(it).toAbsolutePath().normalize().toString().equals(root,ignoreCase=true) }==true} } }
        if(playing)throw IOException("Закрой Minecraft перед изменением сборки")
        if(Shell.isWindows){
            val script="\$running = Get-CimInstance Win32_Process -Filter \"CommandLine LIKE '%--gameDir%'\" | Where-Object { \$_.CommandLine -and \$_.CommandLine.Contains('--gameDir') -and \$_.CommandLine.IndexOf(${Shell.psLiteral(root)}, [StringComparison]::OrdinalIgnoreCase) -ge 0 }; if (\$running) { exit 31 } else { exit 0 }"
            when(Shell.runHidden(Shell.powershell(script),30_000)){0->Unit;31->throw IOException("Закрой Minecraft перед изменением сборки");else->throw IOException("Не удалось проверить, закрыта ли игра. Повтори после закрытия Minecraft")}
        }
    }
    fun run() {
        protocolOut=java.io.PrintStream(System.out,true,Charsets.UTF_8)
        System.setOut(System.err)
        val reader=System.`in`.bufferedReader(Charsets.UTF_8)
        val line=reader.readLine()?:return
        if(line.length>65536){send("failed",JsonPrimitive("Слишком большой запрос"));return}
        try {
            val request=ru.aw.launcher.core.Json.decodeFromString<Request>(line)
            val verifiesFiles=!request.op.startsWith("account")&&request.op!="launcherState"
            Paths.ensureBaseDirs();Settings.load();java.util.Locale.setDefault(java.util.Locale.forLanguageTag(Settings.current.language.tag));javax.swing.JComponent.setDefaultLocale(java.util.Locale.getDefault());Log.init("native-engine");if(verifiesFiles)VerifyCache.load()
            val lockFile=java.nio.channels.FileChannel.open(Paths.root.resolve("native-operation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
            lockFile.use { channel->val readOnly=request.op in setOf("ping","launcherState","content","history","project","versions","accountFace");val lock=if(readOnly)null else channel.tryLock()?:throw IOException("Дождись завершения другой операции лаунчера");lock.use {
                val legacyChannel=if(readOnly)null else java.nio.channels.FileChannel.open(Paths.root.resolve("launcher.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
                legacyChannel.use { oldChannel ->
                val oldLock=if(readOnly)null else oldChannel!!.tryLock()?:throw IOException("Закрой установленный AWLauncher перед изменением сборок в новой версии")
                oldLock.use {
                runBlocking {
                    val job=launch(Dispatchers.IO) {
                        try {val result=execute(request);if(!readOnly&&verifiesFiles)VerifyCache.save();send("done",result)}
                        catch(e:CancellationException){send("cancelled");throw e}
                        catch(e:Exception){Log.warn("Native task failed: ${request.op}",e);send("failed",JsonPrimitive(e.message?:"Операция не выполнена"))}
                    }
                    job.invokeOnCompletion { failure -> if(failure is CancellationException)send("cancelled") }
                    if(request.op!="ping")thread(isDaemon=true,name="native-task-control") {try {while(true){val control=reader.readLine();if(control==null||control=="cancel"){job.cancel();ru.aw.launcher.net.Http.client.dispatcher.cancelAll();break}}}catch(_:Exception){job.cancel();ru.aw.launcher.net.Http.client.dispatcher.cancelAll()}}
                    job.join()
                }
                }}
            }}
        }catch(e:Exception){send("failed",JsonPrimitive(e.message?:"Не удалось выполнить запрос"))}
        finally{ru.aw.launcher.net.Http.shutdown();Log.close()}
    }
    private suspend fun execute(r:Request):JsonElement {
        if(r.op=="ping")return buildJsonObject{put("protocol",1);put("version",ru.aw.launcher.launch.ArgumentBuilder.LAUNCHER_VERSION)}
        if(r.op in setOf("accountSkinWindow","instanceWindow")) {
            AccountManager.load()
            if(r.op=="accountSkinWindow") {
                val account=AccountManager.accounts.value.firstOrNull{it.uuid==r.file}?:throw IOException("Аккаунт больше не найден")
                if(account.isOffline)throw IOException("Скин и плащ доступны для лицензионного аккаунта Minecraft")
            } else {
                if(r.kind !in setOf("settings","icon","groups","export","duplicate","migrate","download","reinstall","delete"))throw IOException("Неизвестное действие сборки")
                editable(target(r.target))
            }
            NativeWindow.openOriginal(r,onReady={send("originalWindowReady")},onGameStarted={send("gameStarted",JsonPrimitive(it.pid()))})
            return accountsData()
        }
        if(r.op=="accountSelect"){AccountManager.selectSaved(r.file);return JsonPrimitive("Аккаунт выбран")}
        if(r.op=="launcherState") {
            AccountManager.load()
            Log.info("startup: native launcher ready")
            val (installed,profiles)=Preloader.scanInstalled()
            val historyFile=Paths.root.resolve("activity.json")
            val sessions=if(Files.isRegularFile(historyFile))runCatching{ru.aw.launcher.core.Json.decodeFromString<List<ru.aw.launcher.activity.PlaySession>>(Files.readString(historyFile))}
                .getOrElse{Log.warn("Could not read launcher activity",it);emptyList()}else emptyList()
            fun profile(key:String,name:String,game:String,loader:LoaderKind,dir:Path,lastPlayed:Long?,icon:String?=null,importedMs:Long=0,importedAt:Long?=null)=buildJsonObject {
                val id=key.substringAfter(':')
                val played=sessions.filter{if(key.startsWith("build:"))it.buildId==id else it.instance==id}
                put("key",key);put("name",name);put("version",game);put("loader",loader.label)
                put("dir",dir.toAbsolutePath().normalize().toString());listOfNotNull(lastPlayed,played.maxOfOrNull{it.start}).maxOrNull()?.let{put("lastPlayed",it)}
                val historical=played.sumOf{(minOf(it.end,importedAt?:0)-it.start).coerceAtLeast(0)}
                put("playTime",played.sumOf{(it.end-it.start).coerceAtLeast(0)}+(importedMs-historical).coerceAtLeast(0))
                put("options",ru.aw.launcher.core.Json.encodeToJsonElement(InstanceStore.get(dir)))
                icon?.let{put("icon",it)}
                put("ready",if(loader==LoaderKind.VANILLA)installed.containsKey(game)else profiles.any{loader.ownsProfile(it,game)})
                put("importedMs",importedMs);importedAt?.let{put("importedAt",it)}
            }
            return buildJsonObject {
                put("version",ru.aw.launcher.launch.ArgumentBuilder.LAUNCHER_VERSION)
                put("settings",ru.aw.launcher.core.Json.encodeToJsonElement(Settings.current))
                put("builds",buildJsonArray {
                    LocalBuilds.list().forEach { b->add(profile("build:${b.id}",b.name,b.versionId,b.loader,LocalBuilds.dirOf(b),b.lastPlayed,b.iconFile,b.importedPlayTimeMillis,b.importedAt)) }
                    Modpacks.list().forEach { p->add(profile("pack:${p.id}",p.title,p.gameVersion,p.loader,Modpacks.dirOf(p),null,p.iconUrl)) }
                })
                put("accounts",accountsData())
                put("notices",if(Files.isRegularFile(Paths.noticesFile))ru.aw.launcher.core.Json.parseToJsonElement(Files.readString(Paths.noticesFile))else buildJsonObject{put("items",buildJsonArray{})})
            }
        }
        if(r.op=="checkUpdate"||r.op=="installUpdate"){
            val updater=ru.aw.launcher.update.Updater
            stage("Проверяю обновления");updater.check()
            return when(val state=updater.state.value){
                is ru.aw.launcher.update.UpdateState.Available->{
                    if(r.op=="installUpdate"){
                        if(System.getenv("AW_NATIVE_GAMES")!="0")throw IOException("Закрой Minecraft перед обновлением")
                        val nativeGame=ProcessHandle.allProcesses().use{processes->processes.anyMatch{process->process.info().arguments().orElse(emptyArray()).any{it=="--gameDir"}}}
                        if(nativeGame)throw IOException("Закрой Minecraft перед обновлением")
                        if(Shell.isWindows){val script="\$games = Get-CimInstance Win32_Process | Where-Object { \$_.Name -match '^javaw?\\.exe\$' -and \$_.CommandLine -and \$_.CommandLine.Contains('--gameDir') }; if (\$games) { exit 31 } else { exit 0 }";if(Shell.runHidden(Shell.powershell(script),15_000)!=0)throw IOException("Закрой Minecraft перед обновлением")}
                        coroutineScope{val report=launch{updater.state.collect{value->if(value is ru.aw.launcher.update.UpdateState.Downloading)send("progress",buildJsonObject{put("fraction",value.fraction);put("bytesPerSecond",value.bytesPerSecond)})}};try{if(!updater.install(state.update))throw IOException("Обновление не установлено")}finally{report.cancel()}}
                        buildJsonObject{put("installing",true);put("message","Установка обновления начинается")}
                    }else buildJsonObject{put("available",state.update.version);put("message","Доступна версия ${state.update.version}")}
                }
                is ru.aw.launcher.update.UpdateState.Failed->throw IOException(state.message)
                else->buildJsonObject{put("message","Установлена последняя версия")}
            }
        }
        if(r.op in setOf("accountOffline","accountMicrosoft","accountRemove")){
            AccountManager.load()
            when(r.op){
                "accountOffline"->AccountManager.addOffline(r.title)
                "accountMicrosoft"->AccountManager.signInMicrosoft(onCode={code->send("login",buildJsonObject{put("code",code.code);put("url",code.verificationUrl)})},onStage=::stage)
                "accountRemove"->AccountManager.remove(r.file)
            }
            if(r.options["returnAccounts"]?.jsonPrimitive?.booleanOrNull==true)return accountsData()
            return JsonPrimitive("Аккаунты обновлены")
        }
        if(r.op=="versions")return ru.aw.launcher.core.Json.encodeToJsonElement(ru.aw.launcher.meta.VersionManifestRepository.load().versions)
        if(r.op=="createBuild"){
            val game=r.version?:throw IOException("Выбери версию Minecraft")
            if(ru.aw.launcher.meta.VersionManifestRepository.load().versions.none{it.id==game})throw IOException("Версия Minecraft не найдена в официальном списке")
            val loader=LoaderKind.entries.firstOrNull{it.name.equals(r.kind,true)}?:throw IOException("Выбери загрузчик")
            if(loader.isModded&&game !in ru.aw.launcher.meta.LoaderRepository.supportedGameVersions(loader))throw IOException("${loader.label} не поддерживает Minecraft $game")
            LocalBuilds.create(r.title,game,loader);return JsonPrimitive("Сборка создана")
        }
        if(r.op=="project"){
            val project=ContentCatalog.project(r.project)
            val target=r.target.takeIf{it.isNotBlank()}?.let(::target)
            val versions=ContentCatalog.versions(r.project,target?.let{ModManager.catalogLoaders(ContentKind.entries.firstOrNull{it.projectType==r.kind}?:ContentKind.MOD,it.loader)}.orEmpty(),target?.game)
            return buildJsonObject{put("project",ru.aw.launcher.core.Json.encodeToJsonElement(project));put("versions",ru.aw.launcher.core.Json.encodeToJsonElement(versions));
                put("descriptionHtml",ru.aw.launcher.ui.components.ProjectDescription.parse(project.body,if(r.project.startsWith("cf:"))"https://www.curseforge.com" else "https://modrinth.com").joinToString("\n"){it.outerHtml()})}
        }
        if(r.op=="install"&&r.kind=="modpack"){
            stage("Подбираю выпуск сборки")
            val source=Modpacks.source(r.project,r.slug,r.title,null,r.version)
            Modpacks.list().firstOrNull{it.id==source.id}?.let{editable(Target(Modpacks.dirOf(it),it.gameVersion,it.loader,it.loaderVersion))}
            Modpacks.install(source,::stage,::progress)
            return JsonPrimitive("Сборка установлена")
        }
        if(r.op=="accountFace"){accountFace();return JsonNull}
        val t=target(r.target)
        if(r.op=="content"){
            accountFace()
            val all=coroutineScope{ContentKind.entries.map{kind->async{ModManager.scan(t.dir,t.loader,t.game,kind,withUpdates=true,identifyRemote=true)}}.awaitAll().flatten()}
            return buildJsonArray{all.forEach{item->add(buildJsonObject{put("file",t.dir.toAbsolutePath().normalize().relativize(item.file.toAbsolutePath().normalize()).toString().replace('\\','/'));put("title",item.title);put("kind",item.kind.projectType);put("version",item.versionNumber);put("enabled",item.enabled);put("project",item.projectId?:"");put("icon",item.iconUrl?:"");put("update",item.update?.id?:"")})}}
        }

        if(r.op=="history")return ru.aw.launcher.core.Json.encodeToJsonElement(BuildHistory.list(t.dir))
        if(r.op=="launch"){
            AccountManager.load();val account=AccountManager.selected.value?:throw IOException("Добавь аккаунт в настройках")
            if(t.loader.isModded){val conflicts=ModCompat.conflictsIn(ModManager.modsDir(t.dir));if(conflicts.isNotEmpty())throw IOException(conflicts.take(3).joinToString("\n"){it.text})}
            val result=GameLauncher.launch(t.game,account,t.loader,gameDir=t.dir,loaderVersion=t.loaderVersion,onStage=::stage,onProgress=::progress,onNotice={stage(it)})
            return buildJsonObject{put("pid",result.process.pid());put("message","Игра запущена")}
        }
        editable(t)
        if(r.op=="saveOptions"){
            val o=r.options
            val preset=o["iconPreset"]?.jsonPrimitive?.contentOrNull
            val background=o["iconBackground"]?.jsonPrimitive?.contentOrNull
            val group=o["groupId"]?.jsonPrimitive?.contentOrNull
            if(!ru.aw.launcher.ui.InstanceIcons.validSymbol(preset)||!ru.aw.launcher.ui.InstanceIcons.validBackground(background))throw IOException("Выбери существующую иконку и фон")
            if(group!=null&&Settings.current.libraryGroups.none{it.id==group})throw IOException("Группа больше не найдена")
            val memory=o["memoryMb"]?.jsonPrimitive?.intOrNull
            if(memory!=null&&memory !in 512..SettingsDefaults.memoryLimit(SettingsDefaults.totalSystemMemoryMb()))throw IOException("Некорректный объём памяти")
            val width=o["windowWidth"]?.jsonPrimitive?.intOrNull;val height=o["windowHeight"]?.jsonPrimitive?.intOrNull
            if(width!=null&&width !in 320..16384||height!=null&&height !in 240..16384)throw IOException("Некорректное разрешение окна")
            InstanceStore.update(t.dir){old->old.copy(memoryMb=if("memoryMb" in o)memory else old.memoryMb,jvmArgs=if("jvmArgs" in o)o["jvmArgs"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()} else old.jvmArgs,
                javaPath=if("javaPath" in o)o["javaPath"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()} else old.javaPath,
                windowWidth=if("windowWidth" in o)width else old.windowWidth,windowHeight=if("windowHeight" in o)height else old.windowHeight,
                fullscreen=if("fullscreen" in o)o["fullscreen"]?.jsonPrimitive?.booleanOrNull else old.fullscreen,
                favorite=o["favorite"]?.jsonPrimitive?.booleanOrNull?:old.favorite,historyIncludeWorlds=o["historyIncludeWorlds"]?.jsonPrimitive?.booleanOrNull?:old.historyIncludeWorlds,
                iconPreset=if("iconPreset" in o)preset else old.iconPreset,iconBackground=if("iconBackground" in o)background else old.iconBackground,
                iconImage=if("iconPreset" in o)null else old.iconImage,iconUrl=if("iconPreset" in o)null else old.iconUrl,groupId=if("groupId" in o)group else old.groupId)}
            return JsonPrimitive("Настройки сборки сохранены")
        }
        if(r.op=="importIcon") {
            var source:Path?=null
            javax.swing.SwingUtilities.invokeAndWait {
                val chooser=javax.swing.JFileChooser().apply{dialogTitle="Иконка сборки";fileFilter=javax.swing.filechooser.FileNameExtensionFilter("PNG, JPEG, WebP","png","jpg","jpeg","webp")}
                if(chooser.showOpenDialog(null)==javax.swing.JFileChooser.APPROVE_OPTION)source=chooser.selectedFile.toPath()
            }
            val image=InstanceImages.import(t.dir,source?:throw CancellationException("Выбор изображения отменён"))
            InstanceStore.update(t.dir){it.copy(iconImage=image,iconPreset=null,iconUrl=null)}
            return JsonPrimitive("Иконка обновлена")
        }
        if(r.op=="rename"){
            if(!r.target.startsWith("build:"))throw IOException("Переименование доступно для локальных сборок")
            LocalBuilds.rename(r.target.removePrefix("build:"),r.title);return JsonPrimitive("Сборка переименована")
        }
        if(r.op=="duplicate"){
            LocalBuilds.importDirectory(t.dir,r.title.ifBlank{"Копия ${t.game}"},t.game,t.loader,onProgress=::progress)
            return JsonPrimitive("Копия сборки создана")
        }
        if(r.op=="shortcut"){Shortcuts.createOnDesktop(r.title.ifBlank{t.game},t.game,t.loader,pack=r.target.takeIf{it.startsWith("pack:")}?.removePrefix("pack:"),build=r.target.takeIf{it.startsWith("build:")}?.removePrefix("build:"));return JsonPrimitive("Ярлык создан")}
        if(r.op=="importFiles"){
            val kind=ContentKind.entries.firstOrNull{it.projectType==r.kind}?:ContentKind.MOD
            val destination=if(kind==ContentKind.DATA_PACK){val world=ModManager.dataPackWorlds(t.dir).firstOrNull{it.fileName.toString()==r.world}?:throw IOException("Выбери мир для датапаков");kind.dir(world)}else kind.dir(t.dir)
            var files:List<Path> = emptyList()
            javax.swing.SwingUtilities.invokeAndWait{val chooser=javax.swing.JFileChooser().apply{isMultiSelectionEnabled=true;dialogTitle=I18n.text("Добавить файлы");fileFilter=javax.swing.filechooser.FileNameExtensionFilter("Minecraft (${kind.extension})",kind.extension.removePrefix("."))};if(chooser.showOpenDialog(null)==javax.swing.JFileChooser.APPROVE_OPTION)files=chooser.selectedFiles.map{it.toPath()}}
            if(files.isEmpty())throw CancellationException("Выбор файлов отменён")
            BuildHistory.record(t.dir,t.game,t.loader,"Добавление локальных файлов",t.loaderVersion){
                if(kind==ContentKind.MOD)ModManager.importMods(t.dir,t.loader,t.game,files,::progress)
                else {
                    if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(destination,LinkOption.NOFOLLOW_LINKS))throw IOException("Некорректная папка контента")
                    Files.createDirectories(destination)
                    val incoming=files.map{source->
                        if(!Files.isRegularFile(source)||!source.fileName.toString().endsWith(kind.extension,true))throw IOException("Выбери файлы ${kind.extension}")
                        val name=source.fileName.toString().dropLast(kind.extension.length)+kind.extension
                        if('/' in name||'\\' in name||':' in name)throw IOException("Некорректное имя файла")
                        val target=destination.resolve(name);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw IOException("Файл $name уже есть в сборке")
                        source to target
                    }
                    if(incoming.map{it.second.fileName.toString().lowercase()}.distinct().size!=incoming.size)throw IOException("В выбранных файлах есть одинаковые имена")
                    for((source,target)in incoming){currentCoroutineContext().ensureActive();if(kind==ContentKind.DATA_PACK){val staged=target.resolveSibling("${target.fileName}.download");try{Files.copy(source,staged,StandardCopyOption.REPLACE_EXISTING);ModManager.normalizeDataPack(staged);Files.move(staged,target)}finally{Files.deleteIfExists(staged)}}else Files.copy(source,target)}
                }
            };return JsonPrimitive("Файлы добавлены")
        }
        if(r.op=="export"){
            var output:Path?=null
            javax.swing.SwingUtilities.invokeAndWait{
                val chooser=javax.swing.JFileChooser().apply{dialogTitle=I18n.text("Экспортировать в .mrpack");selectedFile=java.io.File("AWLauncher-build.mrpack");fileFilter=javax.swing.filechooser.FileNameExtensionFilter("Modrinth modpack","mrpack")}
                if(chooser.showSaveDialog(null)==javax.swing.JFileChooser.APPROVE_OPTION){
                    val path=chooser.selectedFile.toPath().let{if(it.fileName.toString().endsWith(".mrpack",true))it else it.resolveSibling("${it.fileName}.mrpack")}
                    if(!Files.exists(path)||javax.swing.JOptionPane.showConfirmDialog(null,I18n.text("Заменить существующий файл?"),I18n.text("Экспорт сборки"),javax.swing.JOptionPane.YES_NO_OPTION)==javax.swing.JOptionPane.YES_OPTION)output=path
                }
            }
            val path=output?:throw CancellationException("Экспорт отменён")
            val selected=Files.list(t.dir).use{paths->paths.map{it.fileName.toString()}.filter{it in setOf("mods","config","defaultconfigs","resourcepacks","shaderpacks","options.txt","optionsof.txt","optionsshaders.txt")}.toList().toSet()}
            Modpacks.export(t.dir,path,r.title.ifBlank{t.game},t.game,t.loader,InstanceStore.get(t.dir).launchLoaderVersion(t.loaderVersion),selected,onProgress={fraction->send("progress",buildJsonObject{put("fraction",fraction)})})
            return JsonPrimitive("Сборка экспортирована")
        }
        if(r.op=="install"){
            val kind=ContentKind.entries.firstOrNull{it.projectType==r.kind}?:throw IOException("Неизвестный тип контента")
            val selected=r.version?.let{ContentCatalog.version(it)}
            stage("Устанавливаю ${r.title}")
            BuildHistory.record(t.dir,t.game,t.loader,"Установка ${r.title}",t.loaderVersion){
                when(kind){
                    ContentKind.MOD->ModManager.install(t.dir,t.loader,t.game,r.project,r.title,emptySet(),selectedVersion=selected,onProgress=::progress)
                    ContentKind.SHADER->ModManager.installShader(t.dir,t.loader,t.game,r.project,r.title,emptySet(),selectedVersion=selected,onProgress=::progress)
                    ContentKind.RESOURCE_PACK->ModManager.installResourcePack(t.dir,t.game,r.project,r.title,selectedVersion=selected,onProgress=::progress)
                    ContentKind.DATA_PACK->{val world=ModManager.dataPackWorlds(t.dir).firstOrNull{it.fileName.toString()==r.world}?:throw IOException("Выбери мир для датапака");ModManager.installDataPack(t.dir,world,t.game,r.project,r.title,selectedVersion=selected,onProgress=::progress)}
                }
            };return JsonPrimitive("Проект установлен")
        }
        if(r.op=="updateItem"){
            val kind=ContentKind.entries.firstOrNull{it.projectType==r.kind}?:throw IOException("Неизвестный тип контента")
            val item=ModManager.scan(t.dir,t.loader,t.game,kind,withUpdates=true).firstOrNull{t.dir.toAbsolutePath().normalize().relativize(it.file.toAbsolutePath().normalize()).toString().replace('\\','/')==r.file}?:throw IOException("Файл больше не найден")
            val version=item.update?:return JsonPrimitive("Проект уже обновлён")
            BuildHistory.record(t.dir,t.game,t.loader,"Обновление ${item.title}",t.loaderVersion){
                if(kind==ContentKind.MOD)ModManager.updateMod(t.dir,t.loader,t.game,item,::progress)
                else{when(kind){ContentKind.SHADER->ModManager.installShader(t.dir,t.loader,t.game,version.projectId,item.title,emptySet(),selectedVersion=version,onProgress=::progress)
                ContentKind.RESOURCE_PACK->ModManager.installResourcePack(t.dir,t.game,version.projectId,item.title,selectedVersion=version,onProgress=::progress)
                ContentKind.DATA_PACK->ModManager.installDataPack(t.dir,item.file.parent.parent,t.game,version.projectId,item.title,selectedVersion=version,onProgress=::progress)
                else->Unit};if(!item.enabled){val fresh=ModManager.scan(t.dir,t.loader,t.game,kind,identifyRemote=false).firstOrNull{it.projectId==version.projectId};if(fresh!=null)ModManager.setEnabled(fresh,false)}}
            };return JsonPrimitive("Проект обновлён")
        }
        if(r.op=="toggle"||r.op=="remove"){
            val kind=ContentKind.entries.firstOrNull{it.projectType==r.kind}?:throw IOException("Неизвестный тип контента")
            val item=ModManager.scan(t.dir,t.loader,t.game,kind,identifyRemote=false).firstOrNull {t.dir.toAbsolutePath().normalize().relativize(it.file.toAbsolutePath().normalize()).toString().replace('\\','/')==r.file}?:throw IOException("Файл больше не найден")
            var updated=item
            BuildHistory.record(t.dir,t.game,t.loader,if(r.op=="remove")"Удаление ${item.title}" else "Переключение ${item.title}",t.loaderVersion){
                if(r.op=="remove")ModManager.remove(item)
                else if(r.enabled&&kind==ContentKind.MOD)updated=ModManager.enableMod(t.dir,t.loader,t.game,item,::progress).first
                else updated=ModManager.setEnabled(item,r.enabled)
            }
            if(r.op=="toggle"&&r.options["returnItem"]?.jsonPrimitive?.booleanOrNull==true)return buildJsonObject {
                put("file",t.dir.toAbsolutePath().normalize().relativize(updated.file.toAbsolutePath().normalize()).toString().replace('\\','/'));put("enabled",updated.enabled)
            }
            return JsonPrimitive("Состояние обновлено")
        }
        if(r.op=="snapshot"){BuildHistory.create(t.dir,t.game,t.loader,"Сохранённое состояние",t.loaderVersion,completed=true);return JsonPrimitive("Состояние сохранено")}
        if(r.op=="restore"){
            val snapshot=BuildHistory.list(t.dir).firstOrNull{it.id==r.snapshot}?:throw IOException("Снимок больше не найден")
            if(snapshot.gameVersion!=t.game||snapshot.loader!=t.loader)throw IOException("Этот снимок относится к другой версии Minecraft или загрузчику")
            BuildHistory.restore(t.dir,r.snapshot,t.game,t.loader,t.loaderVersion);return JsonPrimitive("Состояние восстановлено")
        }
        throw IOException("Неизвестная операция")
    }
}
