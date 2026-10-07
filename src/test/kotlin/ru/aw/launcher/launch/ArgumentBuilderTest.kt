package ru.aw.launcher.launch

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import ru.aw.launcher.instance.InstanceOptions
import org.junit.jupiter.api.Test
import ru.aw.launcher.auth.Account
import ru.aw.launcher.core.LauncherSettings
import ru.aw.launcher.install.InstalledVersion
import ru.aw.launcher.meta.Argument
import ru.aw.launcher.meta.Arguments
import ru.aw.launcher.meta.Rule
import ru.aw.launcher.meta.VersionJson
import java.nio.file.Path

class ArgumentBuilderTest {

    private val modern = VersionJson(
        id = "26.3",
        arguments = Arguments(
            game = listOf(
                Argument.Literal("--username"),
                Argument.Literal("\${auth_player_name}"),
                Argument.Conditional(
                    rules = listOf(Rule(features = mapOf("is_quick_play_multiplayer" to true))),
                    values = listOf("--quickPlayMultiplayer", "\${quickPlayMultiplayer}"),
                ),
            ),
            jvm = listOf(Argument.Literal("-cp"), Argument.Literal("\${classpath}")),
        ),
    )

    private val legacy = VersionJson(
        id = "1.12.2",
        minecraftArguments = "--username \${auth_player_name} --version \${version_name}",
    )

    private fun command(version: VersionJson, server: String?, options: InstanceOptions = InstanceOptions(), defaults: LauncherSettings = LauncherSettings()): List<String> =
        ArgumentBuilder(
            installed = InstalledVersion(
                json = version,
                clientJar = Path.of("client.jar"),
                classpath = listOf(Path.of("lib.jar")),
                nativesDir = Path.of("natives"),
                assetsDir = Path.of("assets"),
                assetIndexId = "1",
                logConfig = null,
                logConfigArgument = null,
            ),
            account = Account.offline("Tester"),
            settings = options.launchSettings(defaults),
            javaExecutable = Path.of("java"),
            gameDir = Path.of("game"),
            serverAddress = server,
            options = options,
        ).build()

    @Test
    fun `a version with quick play joins through it`() {
        val args = command(modern, "play.example.net")
        val at = args.indexOf("--quickPlayMultiplayer")
        assertTrue(at >= 0)
        assertTrue(args[at + 1] == "play.example.net")
        assertFalse("--server" in args)
    }

    @Test
    fun `an older version gets server and port`() {
        val args = command(legacy, "127.0.0.1:25566")
        val at = args.indexOf("--server")
        assertTrue(at >= 0)
        assertTrue(args.subList(at, at + 4) == listOf("--server", "127.0.0.1", "--port", "25566"))
    }

    @Test
    fun `without a server nothing is added`() {
        assertFalse("--quickPlayMultiplayer" in command(modern, null))
        assertFalse("--server" in command(legacy, null))
    }

    @Test
    fun `personal heap and arguments are used instead of the common values`() {
        val defaults = LauncherSettings(memoryMb = 4096, jvmArgs = "-Dshared=yes")
        val options = InstanceOptions(memoryMb = 3072, jvmArgs = "-Dpersonal=yes")
        val args = command(modern, null, options, defaults)
        assertTrue("-Xmx3072M" in args)
        assertTrue("-Dpersonal=yes" in args)
        assertFalse("-Xmx4096M" in args)
        assertFalse("-Dshared=yes" in args)
    }

    @Test
    fun `custom resolution activates manifest conditions without duplicating flags`() {
        val version = modern.copy(arguments = modern.arguments!!.copy(game = modern.arguments.game + Argument.Conditional(
            rules = listOf(Rule(features = mapOf("has_custom_resolution" to true))),
            values = listOf("--width", "\${resolution_width}", "--height", "\${resolution_height}"))))
        val args = command(version, null, InstanceOptions(windowWidth = 1920, windowHeight = 1080))
        assertEquals(1, args.count { it == "--width" })
        assertEquals("1920", args[args.indexOf("--width") + 1])
        assertEquals("1080", args[args.indexOf("--height") + 1])
        assertFalse("--width" in command(version, null))
    }

    @Test
    fun `legacy manifests get explicit window dimensions only when selected`() {
        val args = command(legacy, null, InstanceOptions(windowWidth = 1280, windowHeight = 720))
        assertEquals("1280", args[args.indexOf("--width") + 1])
        assertEquals("720", args[args.indexOf("--height") + 1])
        assertFalse("--width" in command(legacy, null))
    }

    @Test
    fun `quoted JVM property values preserve spaces and Windows paths`() {
        assertEquals(listOf("-Dpath=C:\\Games Folder\\data", "-Dlabel=hello world", "-XX:+UseG1GC"),
            ArgumentBuilder.parseJvmArguments("-Dpath=\"C:\\Games Folder\\data\" -Dlabel='hello world'\t-XX:+UseG1GC"))
        assertThrows(IllegalArgumentException::class.java) { ArgumentBuilder.parseJvmArguments("-Dpath=\"unterminated") }
    }
}
