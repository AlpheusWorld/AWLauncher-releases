package ru.aw.launcher.install

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.aw.launcher.core.Json
import ru.aw.launcher.meta.RuleEnvironment
import ru.aw.launcher.meta.VersionJson
import ru.aw.launcher.net.DownloadTask
import ru.aw.launcher.net.Downloader
import java.io.IOException
import java.nio.file.Path

class VersionInstallerTest {

    private val windows = RuleEnvironment(osName = "windows", osVersion = "10.0", osArch = "x86_64")

    private fun collect(json: String): Triple<List<DownloadTask>, List<Path>, List<NativeJar>> {
        val tasks = ArrayList<DownloadTask>()
        val classpath = ArrayList<Path>()
        val natives = ArrayList<NativeJar>()
        VersionInstaller(env = windows).collectLibraries(Json.decodeFromString<VersionJson>(json), tasks, classpath, natives)
        return Triple(tasks, classpath, natives)
    }

    private fun artifact(path: String) =
        """{"path":"$path","sha1":"${"0".repeat(40)}","size":1,"url":"https://libraries.minecraft.net/$path"}"""

    @Test
    fun `natives of a library listed twice under one name are still unpacked`() {
        val (tasks, classpath, natives) = collect(
            """
            {"id":"1.16.5","libraries":[
              {"name":"org.lwjgl:lwjgl:3.2.1","downloads":{"artifact":${artifact("org/lwjgl/lwjgl/3.2.1/lwjgl-3.2.1.jar")}},
               "rules":[{"action":"allow","os":{"name":"osx"}}]},
              {"name":"org.lwjgl:lwjgl:3.2.2","downloads":{"artifact":${artifact("org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar")}},
               "rules":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]},
              {"name":"org.lwjgl:lwjgl:3.2.2",
               "downloads":{"artifact":${artifact("org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar")},
                 "classifiers":{"natives-windows":${artifact("org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-windows.jar")}}},
               "natives":{"windows":"natives-windows"},
               "rules":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf("lwjgl-3.2.2.jar"), classpath.map { it.fileName.toString() })
        assertEquals(listOf("lwjgl-3.2.2-natives-windows.jar"), natives.map { it.path.fileName.toString() })
        assertEquals(
            listOf("lwjgl-3.2.2.jar", "lwjgl-3.2.2-natives-windows.jar"),
            tasks.map { it.dest.fileName.toString() },
        )
    }

    @Test
    fun `old Forge libraries come from their maven, or from Mojang when they name none`() {
        val (tasks, classpath, _) = collect(
            """
            {"id":"1.7.10-Forge10.13.4.1614-1.7.10","libraries":[
              {"name":"net.minecraft:launchwrapper:1.12","serverreq":true},
              {"name":"com.typesafe:config:1.2.1","url":"https://maven.minecraftforge.net/","clientreq":true,"serverreq":true},
              {"name":"jline:jline:2.13","clientreq":false,"serverreq":true},
              {"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.4","natives":{"windows":"natives-windows"}}
            ]}
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
                "https://maven.minecraftforge.net/com/typesafe/config/1.2.1/config-1.2.1.jar",
            ),
            tasks.map { it.url },
        )
        assertEquals(listOf("launchwrapper-1.12.jar", "config-1.2.1.jar"), classpath.map { it.fileName.toString() })
    }

    @Test
    fun `a missing asset index stops the install instead of leaving the game silent`() {
        val version = Json.decodeFromString<VersionJson>(
            """{"id":"9.9.9","assetIndex":{"id":"test-unreachable","size":10,"url":"http://127.0.0.1:9/index.json"}}"""
        )
        val installer = VersionInstaller(downloader = Downloader(maxAttempts = 1), env = windows)

        val error = assertThrows<IOException> { runBlocking { installer.ensureAssetIndex(version) } }
        assertTrue("список ресурсов" in error.message.orEmpty(), error.message)
    }
}
