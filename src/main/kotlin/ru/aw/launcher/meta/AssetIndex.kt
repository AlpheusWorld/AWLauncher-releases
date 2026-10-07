package ru.aw.launcher.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssetIndex(
    val objects: Map<String, AssetObject> = emptyMap(),
    val virtual: Boolean = false,
    @SerialName("map_to_resources") val mapToResources: Boolean = false,
)

@Serializable
data class AssetObject(
    val hash: String,
    val size: Long = 0,
)

object AssetEndpoints {
    const val RESOURCES = "https://resources.download.minecraft.net"

    fun objectUrl(hash: String): String = "$RESOURCES/${hash.substring(0, 2)}/$hash"
}
