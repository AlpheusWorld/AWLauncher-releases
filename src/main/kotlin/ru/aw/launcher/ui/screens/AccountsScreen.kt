package ru.aw.launcher.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.WithTooltip
import androidx.compose.ui.unit.dp
import ru.aw.launcher.auth.Account
import ru.aw.launcher.auth.AuthConfig
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.SkinHead
import ru.aw.launcher.ui.components.ButtonStyle
import ru.aw.launcher.ui.components.AWButton
import ru.aw.launcher.ui.components.AWTextField
import ru.aw.launcher.ui.components.SectionTitle
import ru.aw.launcher.ui.components.Tag
import ru.aw.launcher.ui.components.ChoiceChip
import ru.aw.launcher.ui.components.EmptyState
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWMotion
import ru.aw.launcher.ui.theme.AWDimens

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(state: LauncherState) {
    val accounts by state.accounts.collectAsState()
    val selected by state.selectedAccount.collectAsState()
    var nickname by remember { mutableStateOf("") }
    var nicknameError by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    val addOffline = {
        nicknameError = state.addOffline(nickname)
        if (nicknameError == null) nickname = ""
    }
    Column(Modifier.fillMaxSize().padding(AWDimens.Gutter).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Аккаунты", style = MaterialTheme.typography.headlineSmall, color = AWColors.Text, modifier = Modifier.weight(1f))
            Text(accounts.size.toString(), color = AWColors.TextMuted, style = MaterialTheme.typography.titleSmall)
        }
        Text("Выбранный аккаунт используется при запуске Minecraft", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        if (accounts.isEmpty()) {
            EmptyState(AWIcons.Image, "Аккаунтов пока нет", "Добавь аккаунт ниже", Modifier.fillMaxWidth().height(130.dp))
        } else Column(Modifier.fillMaxWidth().background(AWColors.Surface, RoundedCornerShape(AWDimens.CornerCard)).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            accounts.sortedByDescending { it.uuid == selected?.uuid }.forEach { account ->
                AccountRow(account, account.uuid == selected?.uuid,
                    onSelect = { state.selectAccount(account.uuid) }, onRemove = { state.removeAccount(account.uuid) },
                    onSkin = { state.openSkinEditor(account) })
            }
        }
        androidx.compose.material3.HorizontalDivider(color = AWColors.Outline)
        SectionTitle("Добавить аккаунт")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Microsoft", selected = !offline, enabled = !state.signingIn, onClick = { offline = false })
            ChoiceChip("Офлайн", selected = offline, enabled = !state.signingIn, onClick = { offline = true })
        }
        if (!offline) {
            Text(if (AuthConfig.isConfigured) "Вход откроется в браузере Microsoft" else "Подтверди вход по коду в браузере Microsoft",
                color = AWColors.TextSoft, style = MaterialTheme.typography.bodyMedium)
            Text("Нужен аккаунт с Minecraft Java Edition", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            AWButton(if (state.signingIn) state.signInStage.ifBlank { "Вход…" } else "Войти через Microsoft",
                style = ButtonStyle.PRIMARY, enabled = !state.signingIn, onClick = state::signInMicrosoft)
            state.signInCode?.let { login ->
                Text("Код подтверждения", color = AWColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                Text(login.code, color = AWColors.Accent, style = MaterialTheme.typography.headlineMedium, translate = false)
                AWButton("Открыть Microsoft", onClick = state::openMicrosoftSignInPage)
            }
            if (state.signingIn) AWButton("Отменить", onClick = state::cancelMicrosoftSignIn)
        } else {
            Text("Для одиночной игры и серверов с офлайн-входом. Скины и Realms недоступны", color = AWColors.TextMuted,
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AWTextField(nickname, onValueChange = { nickname = it; nicknameError = null }, placeholder = "Никнейм",
                    isError = nicknameError != null, onSubmit = addOffline, modifier = Modifier.widthIn(max = 280.dp))
                AWButton("Добавить", onClick = addOffline, enabled = nickname.isNotBlank())
            }
            nicknameError?.let { Text(it, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onSkin: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            isSelected -> AWColors.Accent.copy(alpha = 0.10f)
            hovered -> AWColors.SurfaceHigh
            else -> Color.Transparent
        },
        animationSpec = AWMotion.Hover,
        label = "accountBackground",
    )

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 540.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AWDimens.CornerMedium))
                .background(background)
                .selectable(selected = isSelected, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SkinHead(account, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    account.name,
                    translate = false,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) AWColors.Accent else AWColors.Text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        account.isOffline -> "Офлайн-профиль"
                        account.isExpired -> "Сессия обновится при запуске"
                        else -> "Microsoft · сессия активна"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AWColors.TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact) {
                if (account.isOffline) Tag("Офлайн", AWColors.Warning) else Tag("Лицензия", AWColors.Accent)
            }
            if (!account.isOffline) {
                if (compact) WithTooltip("Скин и плащ") {
                    IconButton(onClick = onSkin, modifier = Modifier.size(44.dp)) { Icon(AWIcons.Image, "Скин и плащ", tint = AWColors.TextMuted) }
                } else AWButton("Скин и плащ", onClick = onSkin)
            }
            if (isSelected) {
                Icon(Icons.Default.Check, null, tint = AWColors.Accent, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, "Удалить", tint = AWColors.TextMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}
