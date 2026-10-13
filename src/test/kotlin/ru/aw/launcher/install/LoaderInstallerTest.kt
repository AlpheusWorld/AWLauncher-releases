package ru.aw.launcher.install

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.instance.InstanceOptions
import ru.aw.launcher.meta.LoaderKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LoaderInstallerTest {
    @Test
    fun `the selected Fabric profile wins over an older imported profile without invoking Java`() = runBlocking {
        val game = "loader-test-${UUID.randomUUID()}"
        val older = "fabric-loader-0.19.3-$game"
        val chosen = "fabric-loader-0.19.5-$game"
        try {
            Paths.versionJson(chosen).writeAtomically("""{"id":"$chosen"}""")
            // Even the most recently downloaded old profile must not change an explicit choice.
            Paths.versionJson(older).writeAtomically("""{"id":"$older"}""")
            val id = LoaderInstaller.ensureProfile(LoaderKind.FABRIC, game,
                javaExecutable = { fail<Path>("Fabric must not run a Java installer") },
                pinned = InstanceOptions(loaderVersion = "0.19.5").launchLoaderVersion("0.19.3"))
            assertEquals(chosen, id)
        } finally {
            for (id in listOf(older, chosen)) {
                Files.deleteIfExists(Paths.versionJson(id))
                Files.deleteIfExists(Paths.versionDir(id))
            }
        }
    }
}
