package ru.aw.launcher.ui

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.auth.SkinCatalog
import ru.aw.launcher.auth.SkinModel
import ru.aw.launcher.auth.SkinPreset
import ru.aw.launcher.core.toHex
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class SkinCatalogTest {
    @TempDir lateinit var directory: Path
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB),"png",it) }.toByteArray()
    private fun preset(hash: String = MessageDigest.getInstance("SHA-256").digest(png).toHex()) = SkinPreset("Event skin", "Event", SkinModel.CLASSIC,
        archiveUrl = "https://www.minecraft.net/content/dam/minecraftnet/games/minecraft/software/event.zip", member = "skins/hero.png", sha256 = hash)
    private fun archive(bytes: ByteArray = png): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("skins/hero.png")); zip.write(bytes); zip.closeEntry() }
    }.toByteArray()
    private fun client(bytes: ByteArray, calls: AtomicInteger = AtomicInteger()) = OkHttpClient.Builder().addInterceptor { chain ->
        calls.incrementAndGet()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
    }.build()

    @Test fun `catalog entries have valid sources and unique selection keys across packs`() {
        assertTrue(SkinCatalog.sections.size > 2)
        assertTrue(SkinCatalog.presets.any { it.section == "MINECON Earth 2017" })
        assertEquals(SkinCatalog.presets.size, SkinCatalog.presets.map { it.id }.distinct().size)
        SkinCatalog.presets.forEach(SkinPreset::validate)
        assertThrows(IllegalArgumentException::class.java) { preset().copy(archiveUrl = "https://example.com/event.zip").validate() }
        assertThrows(IllegalArgumentException::class.java) { preset().copy(member = "../hero.png").validate() }
    }

    @Test fun `concurrent thumbnails share one download and cached archives work offline`() = runBlocking {
        val calls = AtomicInteger()
        val http = client(archive(), calls)
        coroutineScope { (1..6).map { async { SkinHeads.archiveTexture(preset(), http, directory) } }.awaitAll() }
        assertEquals(1, calls.get())
        val offline = OkHttpClient.Builder().addInterceptor { error("A cached pack must not request the network") }.build()
        assertEquals(64, SkinHeads.archiveTexture(preset(), offline, directory).width)
    }

    @Test fun `corrupt cached archives are replaced rather than hiding presets permanently`() = runBlocking {
        SkinHeads.archiveTexture(preset(), client(archive()), directory)
        Files.list(directory).use { files -> Files.write(files.findFirst().orElseThrow(), byteArrayOf(1,2,3)) }
        val calls = AtomicInteger()
        assertEquals(64, SkinHeads.archiveTexture(preset(), client(archive(), calls), directory).height)
        assertEquals(1, calls.get())
    }

    @Test fun `changed and oversized skin members are rejected before decoding`() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { SkinHeads.archiveTexture(preset("0".repeat(64)), client(archive()), directory) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            SkinHeads.archiveTexture(preset(), client(archive(ByteArray(1024*1024+1))), directory)
        } }
    }
}
