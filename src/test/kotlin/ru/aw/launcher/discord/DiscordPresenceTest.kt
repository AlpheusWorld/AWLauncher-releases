package ru.aw.launcher.discord

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.aw.launcher.core.Language
import ru.aw.launcher.core.LauncherSettings
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DiscordPresenceTest {
    private val russian = LauncherSettings(language = Language.RU)
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun card(p: Presence, s: LauncherSettings = russian) = DiscordPresence.activity(p,s)
    private fun playing() = Presence.Playing("1.21.11","Fabric",null,21,1_790_000_000_123,pack="Fabulously Optimized")

    @Test
    fun `launcher and game use AW artwork and a stable session timestamp`() {
        val launcher = card(Presence.Launcher)
        assertEquals("awlauncher",launcher.getValue("assets").jsonObject.text("large_image"))
        assertEquals(launcher.getValue("timestamps"),card(Presence.Launcher).getValue("timestamps"))
        val game = card(playing())
        assertEquals("Fabulously Optimized",game.text("details"))
        assertEquals("В главном меню Minecraft · Minecraft 1.21.11 · Fabric",game.text("state"))
        assertEquals(1_790_000_000L,game.getValue("timestamps").jsonObject.getValue("start").jsonPrimitive.long)
        assertEquals("Установлено модов: 21", game.getValue("assets").jsonObject.text("large_text").substringAfter("Fabric · "))
        assertEquals(game.getValue("timestamps"),card(playing().copy(singleplayer=true)).getValue("timestamps"))
    }

    @Test
    fun `server privacy removes its address and icon while instance privacy removes name and project link`() {
        val p = playing().copy(server="secret.example.org",serverIcon="https://api.mcsrvstat.us/icon/secret.example.org",
            packIcon="https://cdn.modrinth.com/data/test/icon.png",packUrl="https://modrinth.com/modpack/test")
        val hidden = card(p,russian.copy(discordShowInstance=false))
        assertFalse(hidden.toString().contains("secret.example.org"))
        assertFalse(hidden.toString().contains("Fabulously"))
        assertFalse(hidden.toString().contains("modrinth"))
        assertEquals(1,hidden.getValue("buttons").jsonArray.size)
        val shared = card(p,russian.copy(discordShowServer=true))
        assertTrue(shared.text("state").contains("secret.example.org"))
        assertEquals(p.serverIcon,shared.getValue("assets").jsonObject.text("small_image"))
        assertEquals(p.packIcon,shared.getValue("assets").jsonObject.text("large_image"))
        assertEquals(2,shared.getValue("buttons").jsonArray.size)
    }

    @Test
    fun `local images private addresses and unexpected links never become external presence assets`() {
        for (address in listOf("localhost","127.0.0.1","192.168.1.2","10.0.0.1:25565","router.local","host.lan","::1","host/path"))
            assertNull(DiscordPresence.serverIcon(address),address)
        assertEquals("https://api.mcsrvstat.us/icon/play.example.org:25570",DiscordPresence.serverIcon("play.example.org:25570"))
        val p = playing().copy(packIcon="file:///C:/private.png",packUrl="https://evil.example/",serverIcon="https://evil.example/x")
        assertFalse(card(p).toString().contains("private"))
        assertFalse(card(p).toString().contains("evil"))
        assertEquals("awlauncher",card(p).getValue("assets").jsonObject.text("large_image"))
    }

    @Test
    fun `localized text and long unicode instance names stay valid`() {
        val p = playing().copy(pack="✨".repeat(200)+"\nname")
        val details = card(p).text("details")
        assertEquals(128,details.codePointCount(0,details.length))
        assertFalse(details.contains('\n'))
        assertEquals("In the Minecraft menu · Minecraft 1.21.11 · Fabric",card(playing(),russian.copy(language=Language.EN)).text("state"))
        assertEquals("Launching Minecraft",card(Presence.Starting("Test","26.2","Fabric"),russian.copy(language=Language.EN)).text("details"))
    }

    @Test
    fun `reconnect after idle disconnect and clear immediately when the user disables activity`() = runBlocking {
        val desired = MutableStateFlow<Presence>(Presence.Launcher)
        val settings = MutableStateFlow(russian)
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val cards = CopyOnWriteArrayList<JsonObject?>()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client = PresenceClient("1555994295134326945",desired,settings,scope,{}, {
            val index=opened.incrementAndGet()
            object : PresenceIpc {
                override fun setActivity(activity:JsonObject?) { cards.add(activity) }
                override fun ping() { if(index==1) throw IOException("disconnected") }
                override fun close() { closed.incrementAndGet() }
            }
        },30,0)
        try {
            client.start()
            withTimeout(3000) { while(opened.get()<2 || cards.size<2) delay(10) }
            assertTrue(closed.get()>0)
            settings.value=russian.copy(discordPresence=false)
            withTimeout(3000) { while(cards.lastOrNull()!=null || closed.get()<2) delay(10) }
            val count=opened.get()
            delay(100)
            assertEquals(count,opened.get())
            settings.value=russian
            withTimeout(3000) { while(opened.get()<=count) delay(10) }
        } finally { client.close() }
        withTimeout(3000) { while(scope.isActive) delay(10) }
    }

    @Test
    fun `rapid changes are coalesced and disable bypasses the publication interval`() = runBlocking {
        val desired=MutableStateFlow<Presence>(playing())
        val settings=MutableStateFlow(russian)
        val cards=CopyOnWriteArrayList<JsonObject?>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=PresenceClient("1555994295134326945",desired,settings,scope,{}, {
            object:PresenceIpc {
                override fun setActivity(activity:JsonObject?) { cards.add(activity) }
                override fun ping() {}
                override fun close() {}
            }
        },1000,300)
        try {
            client.start()
            withTimeout(3000) { while(cards.isEmpty()) delay(10) }
            desired.value=playing().copy(pack="Intermediate")
            delay(30)
            desired.value=playing().copy(pack="Latest")
            withTimeout(3000) { while(cards.last()!!.text("details")!="Latest") delay(10) }
            assertFalse(cards.any { it?.text("details")=="Intermediate" })
            desired.value=playing().copy(pack="Queued")
            settings.value=russian.copy(discordPresence=false)
            withTimeout(200) { while(cards.last()!=null) delay(5) }
            assertFalse(cards.any { it?.text("details")=="Queued" })
        } finally { client.close() }
    }

    @Test
    fun `game only mode connects for a game and hiding the build republishes the card`() = runBlocking {
        val desired=MutableStateFlow<Presence>(Presence.Launcher)
        val settings=MutableStateFlow(russian.copy(discordShowLauncher=false))
        val cards=CopyOnWriteArrayList<JsonObject?>()
        val opened=AtomicInteger()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=PresenceClient("1555994295134326945",desired,settings,scope,{}, {
            opened.incrementAndGet()
            object:PresenceIpc {
                override fun setActivity(activity:JsonObject?) { cards.add(activity) }
                override fun ping() {}
                override fun close() {}
            }
        },30,0)
        try {
            client.start(); delay(100); assertEquals(0,opened.get())
            desired.value=playing()
            withTimeout(3000) { while(cards.isEmpty()) delay(10) }
            assertTrue(cards.last()!!.text("details").contains("Fabulously"))
            settings.value=settings.value.copy(discordShowInstance=false)
            withTimeout(3000) { while(cards.last()!!.text("details").contains("Fabulously")) delay(10) }
            desired.value=Presence.Launcher
            withTimeout(3000) { while(cards.last()!=null) delay(10) }
        } finally { client.close() }
    }
}
