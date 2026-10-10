package ru.aw.launcher.dev

import kotlinx.coroutines.runBlocking
import ru.aw.launcher.auth.Account
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.Settings
import ru.aw.launcher.launch.GameLauncher
import ru.aw.launcher.meta.LoaderKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val version = args.firstOrNull() ?: "26.1.2"
    val artifacts = Path.of(args.getOrNull(1) ?: "client-mod/build/libs").toAbsolutePath()
    val game = Settings.gameDir(version, LoaderKind.FABRIC)
    System.getenv("AW_JAVA25_HOME")?.let { sdk -> ru.aw.launcher.instance.InstanceStore.update(game) { it.copy(javaPath = sdk) } }
    Files.deleteIfExists(game.resolve("awassistant-smoke.ok")); Files.deleteIfExists(game.resolve("awassistant-smoke.fail"))
    Files.createDirectories(game.resolve("mods"))
    for (name in listOf("AWAssistant-fabric-$version-0.1.0.jar", "AWAssistant-smoke-$version-0.1.0.jar"))
        Files.copy(artifacts.resolve(name), game.resolve("mods").resolve(name), StandardCopyOption.REPLACE_EXISTING)
    val rules = Files.createDirectories(game.resolve("config/awassistant/rules"))
    val entries = (1..18).joinToString(",") { index ->
        """{"number":"$index.1","title":"Тест интерфейса $index","category":"Проверка","description":"Тестовый текст для проверки интерфейса — это не правила сервера.\n\nЭтот абзац проверяет перенос длинного описания. Поиск должен находить нужный результат независимо от регистра, а список слева и подробное описание справа прокручиваются отдельно. Выбор соседнего результата не меняет положение всей панели.\n\nКолесо мыши перемещает текст плавно. Стрелки выбирают соседнюю карточку, Enter возвращает описание к началу, Escape очищает запрос. Кириллица, цифры и знаки препинания должны оставаться чёткими на любом масштабе интерфейса.\n\nСледующий абзац нужен для проверки нижней границы: текст не должен перекрывать нижнюю подсказку, выходить за скруглённую панель или обрезаться при увеличении масштаба.\n\nПоследний абзац проверяет возможность прокрутить описание до самого конца и вернуться к началу. Тестовые данные находятся только в отдельной папке проверки и никогда не попадают в рабочую сборку.","sanction":"Тестовое поле, не правило сервера","keywords":["тест"]}"""
    }
    Files.writeString(rules.resolve("0-smoke.json"), """{"id":"smoke","name":"Проверка UI","addresses":[],"sourceUrl":"","rules":[$entries]}""")
    Files.writeString(game.resolve("options.txt"), "soundCategory_master:0.0\npauseOnLostFocus:false\nnarrator:0\nonboardAccessibility:false\ntutorialStep:none\nguiScale:2\n")
    val launch = runBlocking { GameLauncher.launch(version, Account.offline("AWTest"), LoaderKind.FABRIC,
        gameDir=game, loaderVersion="0.19.3", onStage={ println(it) }, onNotice={println(it)}) }
    val deadline = System.currentTimeMillis() + 240_000
    var passed = false
    try {
        while (System.currentTimeMillis() < deadline) {
            val ok = game.resolve("awassistant-smoke.ok")
            val failed = game.resolve("awassistant-smoke.fail")
            if (Files.exists(ok)) { println(Files.readString(ok)); passed=true; break }
            if (Files.exists(failed)) { println(Files.readString(failed)); break }
            if (!launch.process.isAlive) break
            Thread.sleep(1000)
        }
        println("Game data: $game")
        if (!passed && Files.exists(launch.logFile)) println(Files.readString(launch.logFile).lineSequence().toList().takeLast(35).joinToString("\n"))
    } finally {
        if (launch.process.isAlive) { launch.process.destroyForcibly(); launch.process.waitFor(20,TimeUnit.SECONDS) }
    }
    exitProcess(if(passed)0 else 1)
}
