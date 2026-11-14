package com.myfriend.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.SettingCard
import com.myfriend.app.ui.theme.LocalAccentColor

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = "关于",
                navigation = { BackNavigation(onBack) }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .size(82.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(LocalAccentColor.current.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.WavingHand, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(38.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("MyFriend", style = MaterialTheme.typography.headlineMedium)
                Text(
                    // 版本号只有一个来源：BuildConfig.VERSION_NAME（gradle 的 versionName）。
                    // 这里写死过一次 "v1.0.0"，发版时没人记得改，关于页就一直停在旧版本上。
                    "v${com.myfriend.app.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "一个安静的、可以长期相处的地方。\n每个人格都拥有独立的提示词、记忆与对话。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(30.dp))
                SectionLabel("它会如何记住你")
                SettingCard {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(20.dp))
                        Text(
                            "人格会从对话中整理稳定的长期记忆，并可在“长期记忆”中查看、修改或删除。人格进化由你确认后才会应用，所有历史版本都会完整保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                SectionLabel("隐私与安全")
                SettingCard {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Security, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(20.dp))
                        Column {
                            Text(
                                "API Key 使用 Android Keystore 加密，仅保存在本机；默认只允许 HTTPS；密钥不会写入日志，也不会包含在人格或聊天导出文件中。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "人格提示词与长期记忆只会发送给该人格绑定的 API，每轮对话通过 system message 注入。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "Kotlin · Jetpack Compose · Room · OkHttp · Android Keystore",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "聊天数据只属于你。",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalAccentColor.current
                )
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}
