package ru.aw.launcher.nativeui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import ru.aw.launcher.auth.*
import ru.aw.launcher.ui.SkinHeads
import java.io.IOException
import java.util.Base64

/** Data adapter for the embedded Tauri skin screen. No Compose window is created here. */
internal object NativeSkins {
    private fun png(image: SkinImage) = "data:image/png;base64," + Base64.getEncoder().encodeToString(image.png)
    private fun decode(value: String): SkinImage {
        require(value.startsWith("data:image/png;base64,") && value.length <= 32768) { "Выбери PNG скина размером 64×64 или 64×32" }
        return MinecraftSkins.decode(Base64.getDecoder().decode(value.substringAfter(',')))
    }
    private fun variant(value: String) = SkinModel.entries.firstOrNull { it.name == value }
        ?: throw IOException("Выбери модель рук")
    private suspend fun profile(account: Account, cached: Boolean = false): JsonObject = buildJsonObject {
        put("id", account.uuid); put("name", account.name); put("model", account.skinModel.name)
        put("capes", ru.aw.launcher.core.Json.encodeToJsonElement(account.capes))
        account.capeId?.let { put("capeId", it) }
        val image = if(cached) SkinHeads.cachedTexture(account) else SkinHeads.texture(account)
        image?.let { put("png", png(MinecraftSkins.fromImage(it))) }
    }

    suspend fun execute(r: NativeTasks.Request): JsonElement {
        val library = SkinLibrary()
        if(r.op == "accountSkinTextures") {
            val ids = r.options["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            require(ids.size in 1..12) { "Слишком много текстур" }
            val saved = library.list()
            val capeOwner=if(ids.any { it.startsWith("cape:") }) AccountManager.savedProfile(r.file)
                ?.takeUnless { it.isOffline } ?: throw IOException("Аккаунт больше не найден") else null
            return coroutineScope {
                val permits=Semaphore(4)
                JsonArray(ids.map { id -> async { permits.withPermit { buildJsonObject {
                    put("id",id)
                    try {
                        val image = if(id.startsWith("cape:")) {
                            capeOwner?.capes?.firstOrNull { it.id==id.removePrefix("cape:") }?.let { SkinHeads.capeTexture(it) }
                                ?.let { "data:image/png;base64,"+Base64.getEncoder().encodeToString(java.io.ByteArrayOutputStream().also { out->javax.imageio.ImageIO.write(it,"png",out) }.toByteArray()) }
                        } else {
                            val skin=saved.firstOrNull { it.id==id }?.let { library.image(it) }
                                ?: SkinCatalog.presets.firstOrNull { it.id==id }?.let { SkinHeads.texture(it)?.let(MinecraftSkins::fromImage) }
                                ?: throw IOException("Скин больше не найден")
                            png(skin)
                        }
                        if(image==null) throw IOException("Не удалось загрузить текстуру")
                        put("png",image)
                    } catch(failure: CancellationException) { throw failure }
                    catch(failure: Exception) { put("error",failure.message ?: "Не удалось загрузить текстуру") }
                } } } }.awaitAll())
            }
        }
        if(r.op=="accountSkinRead") {
            val source=r.options["png"]?.jsonPrimitive?.contentOrNull
            val image=if(source!=null)decode(source) else {
                var path: java.nio.file.Path?=null
                javax.swing.SwingUtilities.invokeAndWait { path=ru.aw.launcher.ui.dialogs.chooseSkinFile() }
                path?.let(MinecraftSkins::read) ?: return JsonNull
            }
            return buildJsonObject { put("png",png(image));put("model",MinecraftSkins.modelOf(image.image).name) }
        }
        val owner=AccountManager.savedProfile(r.file)?.takeUnless { it.isOffline }
            ?: throw IOException("Выбери лицензионный аккаунт Minecraft")
        when(r.op) {
            "accountSkinState" -> return buildJsonObject {
                put("profile",profile(owner,cached=true))
                put("saved",ru.aw.launcher.core.Json.encodeToJsonElement(library.list()))
                put("presets",buildJsonArray { SkinCatalog.presets.forEach { skin->add(buildJsonObject {
                    put("id",skin.id);put("name",skin.name);put("section",skin.section);put("model",skin.model.name)
                }) } })
            }
            "accountSkinProfile" -> {
                AccountManager.load()
                return buildJsonObject {
                put("profile",profile(AccountManager.skinProfile(owner.uuid)))
                put("accounts",NativeTasks.accountsData())
                }
            }
            "accountSkinSave" -> {
                val image=decode(r.options["png"]?.jsonPrimitive?.content ?: throw IOException("Выбери текстуру"))
                val cape=r.options["capeId"]?.jsonPrimitive?.contentOrNull
                require(cape==null || owner.capes.any { it.id==cape }) { "Этот плащ недоступен выбранному аккаунту Minecraft" }
                val saved=library.save(image,r.title,variant(r.kind),cape,r.project.takeIf { it.isNotBlank() })
                return buildJsonObject {put("saved",ru.aw.launcher.core.Json.encodeToJsonElement(library.list()));put("selected",saved.id)}
            }
            "accountSkinRemove" -> return ru.aw.launcher.core.Json.encodeToJsonElement(library.remove(r.project))
            "accountSkinMove" -> return ru.aw.launcher.core.Json.encodeToJsonElement(library.move(r.project,r.options["position"]?.jsonPrimitive?.intOrNull ?: throw IOException("Выбери позицию скина")))
            "accountSkinApply" -> {
                AccountManager.load()
                var updated=owner
                if(r.options["changeSkin"]?.jsonPrimitive?.booleanOrNull!=false) {
                    val image=r.options["png"]?.jsonPrimitive?.contentOrNull?.let(::decode)
                    updated=AccountManager.changeSkin(owner.uuid,image,variant(r.kind))
                }
                if(r.options["changeCape"]?.jsonPrimitive?.booleanOrNull==true)
                    updated=AccountManager.changeCape(owner.uuid,r.options["capeId"]?.jsonPrimitive?.contentOrNull)
                return buildJsonObject {put("profile",profile(updated));put("accounts",NativeTasks.accountsData())}
            }
            else -> throw IOException("Неизвестное действие скина")
        }
    }
}
