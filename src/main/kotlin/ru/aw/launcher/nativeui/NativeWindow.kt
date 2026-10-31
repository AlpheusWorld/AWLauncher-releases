package ru.aw.launcher.nativeui

import ru.aw.launcher.core.Shell
import java.nio.file.Files
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.window.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import ru.aw.launcher.core.*
import ru.aw.launcher.instance.LocalBuilds
import ru.aw.launcher.packs.Modpacks
import ru.aw.launcher.ui.*
import ru.aw.launcher.ui.theme.*

object NativeWindow {
    internal suspend fun openOriginal(request:NativeTasks.Request,onReady:()->Unit={},onGameStarted:(Process)->Unit={}) {
        val operation=currentCoroutineContext()[Job]
        val preloaded=Preloader.run { _,_-> }
        application {
            val scope=rememberCoroutineScope()
            val state=remember { LauncherState(scope,preloaded) }
            val settings by Settings.state.collectAsState()
            val windowState=rememberWindowState(width=1280.dp,height=780.dp)
            var nativeFrame by remember { mutableStateOf(false) }
            val close={state.cancel();exitApplication()}
            state.onQuit=close
            LaunchedEffect(Unit) {
                if(request.op=="accountSkinWindow") {
                    val account=ru.aw.launcher.auth.AccountManager.accounts.value.firstOrNull { it.uuid==request.file }
                        ?:throw java.io.IOException("Аккаунт больше не найден")
                    state.openSkinEditor(account)
                } else {
                    val entry=if(request.target.startsWith("build:"))LocalBuilds.list().firstOrNull{it.id==request.target.removePrefix("build:")}?.let(state::entryFor)
                        else Modpacks.list().firstOrNull{it.id==request.target.removePrefix("pack:")}?.let(state::entryFor)
                    if(entry==null)throw java.io.IOException("Сборка больше не найдена")
                    state.openInstance(entry)
                    when(request.kind) {
                        "settings"->state.modal=Modal.Settings(entry)
                        "icon"->state.editInstanceIcon(entry)
                        "groups"->state.modal=Modal.Groups(listOf(entry.key))
                        "export"->state.modal=Modal.Export(entry)
                        "duplicate"->state.modal=Modal.Duplicate(entry)
                        "migrate"->state.openMigration(entry)
                        "download"->state.downloadVersion(entry)
                        "reinstall"->state.reinstall(entry)
                        "delete"->state.modal=Modal.Delete(entry)
                    }
                }
                delay(150);onReady()
            }
            LaunchedEffect(Unit) {
                val owner=System.getProperty("aw.native.pid")?.toLongOrNull()
                while(true) {delay(250);if(operation?.isActive==false||(owner!=null&&!ProcessHandle.of(owner).map{it.isAlive}.orElse(false))){close();break}}
            }
            Window(onCloseRequest=close,title=if(request.op=="accountSkinWindow")"Скины — AWLauncher" else "Сборка — AWLauncher",
                undecorated=true,state=windowState,onPreviewKeyEvent={state.onKey(it)}) {
                window.minimumSize=java.awt.Dimension(600,420)
                DisposableEffect(window) {
                    val release=WindowChrome.configureFramelessBounds(window)
                    nativeFrame=WindowChrome.usesNativeFrame(window)
                    onDispose {release()}
                }
                val dark=settings.themeMode.isDark(isSystemInDarkTheme())
                LaunchedEffect(Unit){WindowChrome.applyIcon(window)}
                LaunchedEffect(dark){WindowChrome.applyTheme(window,dark)}
                AWTheme(settings.themeMode) {
                    if(request.op=="accountSkinWindow") {
                        Box(Modifier.fillMaxSize().background(AWColors.Background)) {
                            Column(Modifier.fillMaxSize()) {
                                LauncherTitleBar(state,this@Window,windowState,onClose=close,nativeFrame=nativeFrame)
                                Box(Modifier.weight(1f).fillMaxWidth()){ru.aw.launcher.ui.screens.SkinsScreen(state)}
                            }
                            ru.aw.launcher.ui.components.NoticeToast(state,Modifier.align(Alignment.BottomEnd).padding(24.dp))
                        }
                    } else App(state,onGameStarted=onGameStarted,titleBar={LauncherTitleBar(state,this@Window,windowState,onClose=close,nativeFrame=nativeFrame)})
                }
            }
        }
    }
    fun start(args:Array<String>):Boolean {
        if(args.any{it=="--compose-ui"})return false
        val executable=Shell.appExecutable?:return false
        val directory=if(Shell.isWindows)executable.parent.resolve("app") else executable.parent.parent.resolve("lib/app")
        val ui=directory.resolve(if(Shell.isWindows)"AWLauncher-native.exe" else "awlauncher-native")
        if(!Files.isRegularFile(ui))return false
        val exit=runCatching { ProcessBuilder(listOf(ui.toAbsolutePath().toString(),"--backend",executable.toAbsolutePath().toString())+args.toList()).inheritIO().start().waitFor() }
            .getOrElse { System.err.println("Native interface could not start: ${it.message}");return false }
        if(exit!=0)System.err.println("Native interface exited with code $exit; starting Compose interface")
        return exit==0
    }
}
