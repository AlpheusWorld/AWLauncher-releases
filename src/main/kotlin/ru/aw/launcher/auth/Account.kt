package ru.aw.launcher.auth

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class AccountType { MICROSOFT, OFFLINE }

@Serializable
enum class SkinModel(val apiValue: String, val label: String) { CLASSIC("classic", "Classic"), SLIM("slim", "Slim") }

@Serializable
data class MinecraftCape(val id: String, val url: String = "", val state: String = "", val alias: String = "")

@Serializable
data class Account(
    val uuid: String,
    val name: String,
    val type: AccountType,
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val skinUrl: String? = null,
    val skinModel: SkinModel = SkinModel.CLASSIC,
    val capes: List<MinecraftCape> = emptyList(),
    val capeId: String? = null,
) {
    val isOffline: Boolean get() = type == AccountType.OFFLINE

    val isExpired: Boolean
        get() = type == AccountType.MICROSOFT && System.currentTimeMillis() >= expiresAt - 60_000

    val dashedUuid: String
        get() = if (uuid.length == 32) {
            "${uuid.substring(0, 8)}-${uuid.substring(8, 12)}-${uuid.substring(12, 16)}-" +
                "${uuid.substring(16, 20)}-${uuid.substring(20)}"
        } else uuid

    companion object {
        fun offline(name: String): Account {
            val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))
            return Account(
                uuid = uuid.toString().replace("-", ""),
                name = name,
                type = AccountType.OFFLINE,
                accessToken = "0",
            )
        }
    }
}

@Serializable
data class AccountStore(
    val accounts: List<Account> = emptyList(),
    val selectedUuid: String? = null,
)

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
