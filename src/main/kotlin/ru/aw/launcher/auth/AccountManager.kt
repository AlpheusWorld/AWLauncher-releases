package ru.aw.launcher.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.PrettyJson
import ru.aw.launcher.core.writeAtomically
import kotlin.io.path.exists
import kotlin.io.path.readText

object AccountManager {
    private val skinOperations = Mutex()
    private val skins by lazy { MinecraftSkins() }

    private val NICKNAME = Regex("^[A-Za-z0-9_]{3,16}$")

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _selected = MutableStateFlow<Account?>(null)
    val selected: StateFlow<Account?> = _selected.asStateFlow()

    fun load() {
        val file = Paths.accountsFile
        if (!file.exists()) return
        runCatching {
            val store = Json.decodeFromString<AccountStore>(file.readText())
            val loaded = store.accounts.map { account ->
                if (account.isOffline) account else account.copy(
                    accessToken = TokenStore.reveal(account.accessToken),
                    refreshToken = TokenStore.reveal(account.refreshToken),
                )
            }
            _accounts.value = loaded
            _selected.value = loaded.firstOrNull { it.uuid == store.selectedUuid }
                ?: loaded.firstOrNull()
            Log.info("loaded ${store.accounts.size} account(s)")
            save()
        }.onFailure { Log.warn("accounts.json unreadable, starting empty", it) }
    }

    private fun save() {
        runCatching {
            val saved = _accounts.value.map { account ->
                if (account.isOffline) account else account.copy(
                    accessToken = TokenStore.protect(account.accessToken),
                    refreshToken = TokenStore.protect(account.refreshToken),
                )
            }
            val store = AccountStore(saved, _selected.value?.uuid)
            Paths.accountsFile.writeAtomically(PrettyJson.encodeToString(store))
        }.onFailure { Log.error("could not save accounts", it) }
    }

    @Synchronized fun select(uuid: String) {
        _selected.value = _accounts.value.firstOrNull { it.uuid == uuid } ?: return
        save()
    }

    @Synchronized fun remove(uuid: String) {
        _accounts.value = _accounts.value.filterNot { it.uuid == uuid }
        if (_selected.value?.uuid == uuid) _selected.value = _accounts.value.firstOrNull()
        save()
    }

    @Synchronized fun upsert(account: Account) {
        val inMemory = if (account.isOffline) account else account.copy(
            accessToken = TokenStore.reveal(account.accessToken),
            refreshToken = TokenStore.reveal(account.refreshToken),
        )
        _accounts.value = _accounts.value.filterNot { it.uuid == inMemory.uuid } + inMemory
        _selected.value = inMemory
        save()
    }

    suspend fun signInMicrosoft(
        onCode: (MicrosoftLoginCode) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): Account {
        val account = MicrosoftAuth().signIn(onStage = onStage, onCode = onCode)
        upsert(account)
        return account
    }

    fun addOffline(name: String): Account {
        val trimmed = name.trim()
        if (!NICKNAME.matches(trimmed)) {
            throw AuthException("Ник: 3-16 символов, только латиница, цифры и подчёркивание")
        }
        val account = Account.offline(trimmed)
        upsert(account)
        return account
    }

    suspend fun prepareForLaunch(account: Account, onStage: (String) -> Unit = {}): Account = prepareSession(account, onStage, true)

    internal suspend fun prepareForProfile(account: Account): Account = withContext(Dispatchers.IO) { prepareSession(account, {}, false) }

    private suspend fun prepareSession(account: Account, onStage: (String) -> Unit, select: Boolean): Account {
        if (account.isOffline || (account.accessToken.isNotBlank() && !account.isExpired)) return account

        val refreshToken = TokenStore.reveal(account.refreshToken)
        if (refreshToken.isBlank()) {
            throw AuthException("Сессия ${account.name} истекла — войдите в аккаунт заново")
        }

        val renewed = MicrosoftAuth().refresh(refreshToken, onStage)
        if (select) upsert(renewed) else replaceExisting(renewed)
        return renewed
    }

    @Synchronized
    private fun replaceExisting(account: Account) {
        if (_accounts.value.none { it.uuid == account.uuid }) throw AuthException("Аккаунт был удалён из лаунчера")
        _accounts.value = _accounts.value.map { if (it.uuid == account.uuid) account else it }
        if (_selected.value?.uuid == account.uuid) _selected.value = account
        save()
    }

    @Synchronized
    internal fun updateProfile(uuid: String, profile: McProfile): Account {
        if (profile.id.replace("-", "").lowercase() != uuid.replace("-", "").lowercase() || profile.name.isBlank())
            throw AuthException("Профиль Minecraft не совпадает с выбранным аккаунтом")
        val current = _accounts.value.firstOrNull { it.uuid == uuid } ?: throw AuthException("Аккаунт был удалён из лаунчера")
        val skin = profile.skins.firstOrNull { it.state.equals("ACTIVE", true) }
        val updated = current.copy(name = profile.name, skinUrl = skin?.url,
            skinModel = SkinModel.entries.firstOrNull { it.apiValue.equals(skin?.variant, true) } ?: SkinModel.CLASSIC,
            capes = profile.capes, capeId = profile.capes.firstOrNull { it.state.equals("ACTIVE", true) }?.id)
        replaceExisting(updated)
        return updated
    }

    internal suspend fun skinProfile(uuid: String): Account = skinOperations.withLock {
        val account = _accounts.value.firstOrNull { it.uuid == uuid } ?: throw AuthException("Аккаунт был удалён из лаунчера")
        if (account.isOffline) throw AuthException("Скин профиля доступен только для лицензионного аккаунта")
        val ready = prepareForProfile(account)
        val profile = skins.profile(ready)
        withContext(Dispatchers.IO) { updateProfile(uuid, profile) }
    }

    internal suspend fun changeSkin(uuid: String, image: SkinImage?, model: SkinModel): Account = skinOperations.withLock {
        val account = _accounts.value.firstOrNull { it.uuid == uuid } ?: throw AuthException("Аккаунт был удалён из лаунчера")
        if (account.isOffline) throw AuthException("Скин профиля доступен только для лицензионного аккаунта")
        val ready = prepareForProfile(account)
        val profile = if (image == null) skins.reset(ready) else skins.upload(ready, image, model)
        withContext(Dispatchers.IO) { updateProfile(uuid, profile) }
    }

    internal suspend fun changeCape(uuid: String, capeId: String?): Account = skinOperations.withLock {
        val account = _accounts.value.firstOrNull { it.uuid == uuid } ?: throw AuthException("Аккаунт был удалён из лаунчера")
        val ready = prepareForProfile(account)
        val profile = skins.changeCape(ready, capeId)
        withContext(Dispatchers.IO) { updateProfile(uuid, profile) }
    }
}
