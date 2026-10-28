package ru.aw.launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.aw.launcher.core.Settings
import ru.aw.launcher.friends.Friend
import ru.aw.launcher.friends.Friends
import ru.aw.launcher.friends.FriendsMod
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.Screen
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FriendsScreen(state: LauncherState) {
    val model by Friends.state.collectAsState()
    val settings by Settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var pendingJoin by remember { mutableStateOf<Pair<Friend,VersionEntry>?>(null) }
    var copiedInvite by remember { mutableStateOf<String?>(null) }
    var activeFriendId by remember { mutableStateOf<String?>(null) }
    fun action(name: String, id: String) { scope.launch { Friends.action(name,id) } }
    fun join(friend: Friend, entry: VersionEntry, confirmed: Boolean = false) {
        activeFriendId = friend.id
        val invite = friend.invite ?: return
        if (Friends.state.value.snapshot.friends.none { it.id == friend.id && it.invite?.id == invite.id } || !Friends.state.value.connected) {
            localError = "Приглашение больше не доступно — дождись обновления списка"; return
        }
        val running = state.runningGameEntries()
        if (running.size > 1) { localError = "Оставь открытым один Minecraft, чтобы принять приглашение"; return }
        val target = running.singleOrNull() ?: entry
        if (target.id != invite.version || target.loader != invite.loader) {
            localError = "Запущена другая версия Minecraft или загрузчик. Закрой игру и выбери подходящую сборку"; return
        }
        scope.launch {
            checking = true; localError = null
            try {
                val signature = withContext(Dispatchers.IO) { FriendsMod.fingerprint(state.gameDirOf(target)) }
                if (!confirmed && signature != invite.fingerprint) { pendingJoin = friend to target; return@launch }
                if (state.runningGameEntries().map { it.key } != running.map { it.key }) {
                    localError = "Игра была запущена или закрыта. Нажми «Присоединиться» ещё раз"; return@launch
                }
                if (Friends.state.value.snapshot.friends.none { it.id == friend.id && it.invite?.id == invite.id } || !Friends.state.value.connected) {
                    localError = "Приглашение больше не доступно — дождись обновления списка"; return@launch
                }
                pendingJoin = null
                if (running.isNotEmpty()) {
                    clipboard.setText(AnnotatedString(invite.address))
                    copiedInvite = invite.id
                } else {
                    copiedInvite = null
                    state.selectEntry(target)
                    state.play(serverAddress = invite.address)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { localError = e.message ?: "Не удалось проверить сборку" }
            finally { checking = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Друзья",style = MaterialTheme.typography.displaySmall,color = AWColors.Text)
        if (!settings.friendsEnabled) {
            Panel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Игра с друзьями выключена")
                    Text("Включи её, чтобы получать приглашения и автоматически устанавливать мод подключения",color = AWColors.TextSoft)
                    AWButton("Включить",onClick = { Settings.update { it.copy(friendsEnabled = true) } },style = ButtonStyle.PRIMARY)
                }
            }
            return@Column
        }
        (localError ?: model.error)?.let { Text(it,color = AWColors.Danger) }
        model.message?.let { Text(it,color = AWColors.Success) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val codePanel: @Composable () -> Unit = {
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Мой код друга")
                Text(if (model.connected) "Подключено" else "Подключаемся к сервису друзей…",color = if (model.connected) AWColors.Success else AWColors.TextMuted)
                if (model.code.isNotEmpty()) {
                    SelectionContainer { Text(model.code,color = AWColors.Accent,style = MaterialTheme.typography.headlineSmall,translate = false) }
                    AWButton("Скопировать код",onClick = { clipboard.setText(AnnotatedString(model.code)) },icon = AWIcons.Copy)
                }
                AWTextField(code,{ code = it.take(64) },"Код друга AWF-…",Modifier.fillMaxWidth())
                AWButton("Добавить друга",onClick = { action("request",code) },enabled = model.connected && !model.busy && code.isNotBlank(),style = ButtonStyle.PRIMARY)
            }
        }
            }
            val details: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (model.snapshot.requests.isNotEmpty()) {
            SectionTitle("Заявки в друзья")
            model.snapshot.requests.forEach { friend ->
                Panel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(friend.name,color = AWColors.Text,translate = false)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AWButton("Принять",onClick = { action("accept",friend.id) },enabled = !model.busy,style = ButtonStyle.PRIMARY)
                            AWButton("Отклонить",onClick = { action("reject",friend.id) },enabled = !model.busy)
                        }
                    }
                }
            }
        }
        SectionTitle("Мои друзья")
        if (model.snapshot.friends.isEmpty()) Text("Добавь друга по коду — после подтверждения он появится здесь",color = AWColors.TextMuted)
        model.snapshot.friends.forEach { friend ->
            key(friend.id) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(friend.name,color = AWColors.Text,style = MaterialTheme.typography.titleMedium,translate = false)
                        Text(when { !model.connected -> "Статус недоступен"; !friend.online -> "Не в сети"; friend.hosting -> "Мир открыт · ${friend.version}"; else -> "В сети" },color = if (friend.online && model.connected) AWColors.Success else AWColors.TextMuted)
                        friend.invite?.let { invite ->
                            Text("Приглашает в мир: ${invite.title}",color = AWColors.Accent)
                            val choices = (state.runningGameEntries() + listOfNotNull(state.currentEntry()) + state.buildChoices() +
                                listOfNotNull(state.versions.firstOrNull { it.id == invite.version }?.let { state.entryFor(it.id,invite.loader) }))
                                .filter { it.id == invite.version && it.loader == invite.loader }.distinctBy { it.key }
                            var selectedKey by remember(invite.id) { mutableStateOf(choices.firstOrNull()?.key) }
                            val entry = choices.firstOrNull { it.key == selectedKey } ?: choices.firstOrNull()
                            var expanded by remember { mutableStateOf(false) }
                            Text("Нужна версия: ${invite.version} · ${invite.loader.label}",color = AWColors.TextMuted)
                            Box {
                                AWButton(entry?.label ?: "Подходящая версия не найдена",onClick = { expanded = true },enabled = choices.isNotEmpty(),translate = false)
                                AWDropdownMenu(expanded,onDismissRequest = { expanded = false }) {
                                    choices.forEach { item -> AWMenuItem(item.label,onClick = { selectedKey = item.key; expanded = false },translate = false) }
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AWButton("Присоединиться",onClick = { entry?.let { join(friend,it) } },enabled = entry != null && model.connected && !checking && !state.busy,style = ButtonStyle.PRIMARY)
                                AWButton("Отклонить приглашение",onClick = { action("decline",friend.id) },enabled = !model.busy)
                            }
                            if (copiedInvite == invite.id) {
                                Text("Адрес скопирован. В Minecraft выйди в главное меню → Сетевая игра → По адресу, вставь адрес и подключись",color = AWColors.Success)
                            } else if (state.hasRunningGame()) {
                                Text("Игра уже запущена. Нажми «Присоединиться», чтобы скопировать адрес для входа через Minecraft",color = AWColors.TextMuted,style = MaterialTheme.typography.bodySmall)
                            }
                            if (activeFriendId == friend.id) localError?.let { Text(it,color = AWColors.Danger) }
                            pendingJoin?.takeIf { it.first.id == friend.id && it.first.invite?.id == invite.id }?.let { (_, target) ->
                                SectionTitle("Моды в сборках отличаются")
                                Text("Для модифицированного мира могут потребоваться такие же моды, как у хозяина. Используй одинаковую сборку или попробуй подключиться с текущей",color = AWColors.TextSoft)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AWButton("Всё равно подключиться",onClick = { join(friend,target,true) },enabled = !checking,style = ButtonStyle.PRIMARY)
                                    AWButton("Отмена",onClick = { pendingJoin = null })
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AWButton("Пригласить в мой мир",onClick = { action("invite",friend.id) },enabled = model.connected && !model.busy && model.world?.info?.address?.isNotEmpty() == true)
                            AWButton("Удалить из друзей",onClick = { action("remove",friend.id) },enabled = model.connected && !model.busy)
                        }
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Мой мир")
                val hosted = model.world
                if (hosted == null) {
                    Text("Запусти сборку с Fabric, Quilt, Forge или NeoForge. Затем в своём мире нажми Esc → Открыть для сети",color = AWColors.TextSoft)
                    Text("На поддерживаемых версиях e4mc и необходимые библиотеки устанавливаются автоматически. Порты на роутере открывать не нужно",color = AWColors.TextMuted,style = MaterialTheme.typography.bodySmall)
                    Text("Выбрана сборка: ${state.currentEntry()?.label ?: "не выбрана"}",color = AWColors.TextSoft)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AWButton("Выбрать сборку",onClick = { state.screen = Screen.PLAY })
                        AWButton("Играть",onClick = { state.play() },enabled = !state.busy && !state.hasRunningGame() && state.currentEntry()?.loader != LoaderKind.VANILLA,style = ButtonStyle.PRIMARY)
                    }
                } else {
                    Text(hosted.info.title,color = AWColors.Text,translate = false)
                    Text("${hosted.info.version} · ${hosted.info.loader.label}",color = AWColors.TextMuted,translate = false)
                    if (hosted.info.address.isEmpty()) Text(hosted.error ?: "Зайди в свой мир и нажми Esc → Открыть для сети. После подключения появятся кнопки приглашения",color = if (hosted.error == null) AWColors.TextSoft else AWColors.Danger)
                    else {
                        Text("Мир доступен — пригласи друга из списка ниже",color = AWColors.Success)
                        Text("Доступ работает, пока открыт мир. Для остановки введи /e4mc stop в игре или выйди из мира",color = AWColors.TextMuted,style = MaterialTheme.typography.bodySmall)
                        Text("Приглашённый получает адрес подключения — передавай доступ только тем, кому доверяешь",color = AWColors.TextMuted,style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (model.snapshot.outgoing.isNotEmpty()) {
            SectionTitle("Ожидают подтверждения")
            model.snapshot.outgoing.forEach { friend ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(friend.name,color = AWColors.TextMuted,translate = false)
                    AWButton("Отменить заявку",onClick = { action("remove",friend.id) },enabled = !model.busy)
                }
            }
        }
                }
            }
            if (maxWidth >= 760.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1.6f)) { details() }
                    Column(Modifier.weight(1f)) { codePanel() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { codePanel(); details() }
            }
        }
    }
}
