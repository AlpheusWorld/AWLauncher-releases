package ru.aw.launcher.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import ru.aw.launcher.ui.components.Panel
import ru.aw.launcher.ui.components.SectionTitle
import ru.aw.launcher.ui.components.Tag
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWMotion
import ru.aw.launcher.ui.theme.AWDimens

@Composable
fun AccountsScreen(state: LauncherState) {
    val accounts by state.accounts.collectAsState()
    val selected by state.selectedAccount.collectAsState()
    var nickname by remember { mutableStateOf("") }
    var nicknameError by remember { mutableStateOf<String?>(null) }

    val addOffline = {
        val problem = state.addOffline(nickname)
        nicknameError = problem
        if (problem == null) nickname = ""
    }

    Column(
        Modifier.fillMaxSize().padding(AWDimens.Gutter).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Лицензионный вход")
                Text(
                    if (AuthConfig.isConfigured) {
                        "Откроется браузер Microsoft. Пароль вводится только там — лаунчер его не видит и не хранит."
                    } else {
                        "Вход по коду Microsoft. Подтверди свой аккаунт в браузере — он появится в лаунчере."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AWColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                AWButton(
                    when {
                        state.signingIn -> state.signInStage.ifBlank { "Вход…" }
                        else -> "Войти через Microsoft"
                    },
                    style = ButtonStyle.PRIMARY,
                    enabled = !state.signingIn,
                    onClick = { state.signInMicrosoft() },
                )
                state.signInCode?.let { login ->
                    Spacer(Modifier.height(12.dp))
                    Text("Если Microsoft попросит код, введи:", color = AWColors.TextMuted)
                    Text(login.code, style = MaterialTheme.typography.headlineMedium, color = AWColors.Accent, translate = false)
                    AWButton("Открыть Microsoft", onClick = state::openMicrosoftSignInPage)
                }
                if (state.signingIn) {
                    Spacer(Modifier.height(8.dp))
                    AWButton("Отменить", style = ButtonStyle.SECONDARY, onClick = state::cancelMicrosoftSignIn)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Офлайн-режим")
                Text(
                    "Работает только на серверах с online-mode=false. Скины и Realms недоступны.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AWColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AWTextField(
                        value = nickname,
                        onValueChange = {
                            nickname = it
                            nicknameError = null
                        },
                        placeholder = "Никнейм",
                        isError = nicknameError != null,
                        onSubmit = addOffline,
                        modifier = Modifier.width(240.dp),
                    )
                    AWButton("Добавить", onClick = addOffline, enabled = nickname.isNotBlank())
                }
                nicknameError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = AWColors.Danger,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("Аккаунты (${accounts.size})")
                if (accounts.isEmpty()) {
                    Text(
                        "Пока пусто.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AWColors.TextMuted,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        accounts.forEach { account ->
                            AccountRow(
                                account = account,
                                isSelected = account.uuid == selected?.uuid,
                                onSelect = { state.selectAccount(account.uuid) },
                                onRemove = { state.removeAccount(account.uuid) },
                                onSkin = { state.openSkinEditor(account) },
                            )
                        }
                    }
                }
            }
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
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkinHead(account, 32.dp)
        Column(Modifier.weight(1f)) {
            Text(
                account.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) AWColors.Accent else AWColors.Text,
                fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    account.isOffline -> "Вход не нужен"
                    account.isExpired -> "Сессия обновится при запуске"
                    else -> "Сессия активна"
                },
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.TextMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (account.isOffline) Tag("Офлайн", AWColors.Warning) else Tag("Лицензия", AWColors.Accent)
        if (!account.isOffline) {
            if (compact) WithTooltip("Скин и плащ") {
                IconButton(onClick = onSkin, modifier = Modifier.size(44.dp)) { Icon(AWIcons.Image, "Скин и плащ", tint = AWColors.TextMuted) }
            } else AWButton("Скин и плащ", onClick = onSkin)
        }
        if (isSelected) {
            Icon(Icons.Default.Check, null, tint = AWColors.Accent, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Delete, "Удалить", tint = AWColors.TextMuted, modifier = Modifier.size(16.dp))
        }
    }
    }
}
