package com.example.novelscraper.translation.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun V2PresetModelButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label, color = AppColors.textPrimary, fontSize = 11.sp)
    }
}

/**
 * プリセット＆手動入力モデル選択ダイアログ
 */
@Composable
fun V2AddModelSelectionDialog(
    registeredProfiles: List<V2ModelProfile> = emptyList(),
    onSelect: (V2ModelProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var dialogTab by remember { mutableIntStateOf(0) }
    var customModelName by remember { mutableStateOf("") }
    var customProvider by remember { mutableStateOf("gemini") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = RoundedCornerShape(8.dp),
            color = AppColors.surfaceDark,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Text("モデルを選択", color = AppColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.backgroundDark, RoundedCornerShape(6.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SelectBox(
                        label = "プリセットから選ぶ",
                        selected = dialogTab == 0,
                        onClick = { dialogTab = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    SelectBox(
                        label = "＋ 手動カスタム入力",
                        selected = dialogTab == 1,
                        onClick = { dialogTab = 1 },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (dialogTab == 0) {
                        if (registeredProfiles.isNotEmpty()) {
                            Text("登録中モデルから選択:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            registeredProfiles.forEach { prof ->
                                V2PresetModelButton(
                                    label = "${prof.model} [${prof.providerId.uppercase()}]",
                                    onClick = { onSelect(prof.copy(id = System.currentTimeMillis().toString())) }
                                )
                            }
                            HorizontalDivider(color = Color.DarkGray)
                        }

                        Text("Google AI Studio (Gemini):", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        listOf(
                            "Gemini 3.5 Flash (標準・安定)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash", thinkingLevel = "medium", maxOutputTokens = 65536),
                            "Gemini 3.6 Flash (安定)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.6-flash", thinkingLevel = "medium", maxOutputTokens = 65536),
                            "Gemini 3.7 Flash (安定・高性能)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.7-flash", thinkingLevel = "medium", maxOutputTokens = 64000),
                            "Gemini 3.8 Flash (最新・最上位Flash)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.8-flash", thinkingLevel = "medium", maxOutputTokens = 64000),
                            "Gemini 3 Flash Preview (旧世代・互換用)" to V2ModelProfile(providerId = "gemini", model = "gemini-3-flash-preview", thinkingLevel = "medium", maxOutputTokens = 65536),
                            "Gemini 3.1 Flash Lite (高速・軽量)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.1-flash-lite", thinkingLevel = "medium", temperature = 1.0, maxOutputTokens = 65536),
                            "Gemini 3.5 Flash Lite (最新Lite)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash-lite", thinkingLevel = "medium", temperature = 1.0, maxOutputTokens = 65536),
                            "Gemma 4 31B (辞書・高品質)" to V2ModelProfile(providerId = "gemini", model = "gemma-4-31b-it", maxOutputTokens = 8192)
                        ).forEach { (label, prof) ->
                            V2PresetModelButton(label = label, onClick = { onSelect(prof.copy(id = java.util.UUID.randomUUID().toString())) })
                        }

                        HorizontalDivider(color = Color.DarkGray)

                        Text("OpenRouter:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        listOf(
                            "DeepSeek V3.2" to V2ModelProfile(providerId = "openrouter", model = "deepseek/deepseek-v3.2", temperature = 0.5, reasoningEnabled = false, maxOutputTokens = 8192),
                            "DeepSeek V3.2 (思考ON)" to V2ModelProfile(providerId = "openrouter", model = "deepseek/deepseek-v3.2", temperature = 0.5, reasoningEnabled = true, maxOutputTokens = 8192),
                            "DeepSeek V3.1" to V2ModelProfile(providerId = "openrouter", model = "deepseek/deepseek-v3.1", temperature = 0.5, reasoningEnabled = false, maxOutputTokens = 8192),
                            "DeepSeek R1 (推論型)" to V2ModelProfile(providerId = "openrouter", model = "deepseek/deepseek-r1", temperature = 0.6, reasoningEffort = "medium", maxOutputTokens = 8192),
                            "Claude 3.5 Sonnet" to V2ModelProfile(providerId = "openrouter", model = "anthropic/claude-3.5-sonnet", temperature = 0.3, maxOutputTokens = 8192),
                            "Claude 3.5 Haiku" to V2ModelProfile(providerId = "openrouter", model = "anthropic/claude-3.5-haiku", temperature = 0.3, maxOutputTokens = 8192),
                            "GPT-4o Mini" to V2ModelProfile(providerId = "openrouter", model = "openai/gpt-4o-mini", temperature = 0.3, maxOutputTokens = 16384),
                            "Qwen 2.5 72B Instruct" to V2ModelProfile(providerId = "openrouter", model = "qwen/qwen-2.5-72b-instruct", temperature = 0.7, maxOutputTokens = 8192)
                        ).forEach { (label, prof) ->
                            V2PresetModelButton(label = label, onClick = { onSelect(prof.copy(id = java.util.UUID.randomUUID().toString())) })
                        }
                    } else {
                        // 手動入力タブ
                        Text("プロバイダー種別:", color = AppColors.textSecondary, fontSize = 10.sp)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SelectBox(
                                label = "Google AI Studio (Gemini)",
                                selected = customProvider == "gemini",
                                onClick = { customProvider = "gemini" },
                                modifier = Modifier.weight(1f)
                            )
                            SelectBox(
                                label = "OpenRouter",
                                selected = customProvider == "openrouter",
                                onClick = { customProvider = "openrouter" },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text("モデル識別子 (ID):", color = AppColors.textSecondary, fontSize = 10.sp)
                        V2InputArea(value = customModelName, onValueChange = { customModelName = it }, singleLine = true)
                        Text(
                            if (customProvider == "gemini") "例: gemini-3.1-flash-lite, gemma-4-31b-it"
                            else "例: deepseek/deepseek-v3.2, anthropic/claude-3.5-haiku",
                            color = AppColors.textTertiary,
                            fontSize = 9.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (customModelName.isNotBlank()) {
                                    val trimmedModel = customModelName.trim()
                                    val desc = if (customProvider == "gemini") {
                                        com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
                                    } else {
                                        com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
                                    }
                                    val maxTokens = desc.capabilitiesFor(trimmedModel).maxOutputTokens
                                    onSelect(
                                        V2ModelProfile(
                                            id = java.util.UUID.randomUUID().toString(),
                                            providerId = customProvider,
                                            model = trimmedModel,
                                            maxOutputTokens = maxTokens
                                        )
                                    )
                                }
                            },
                            enabled = customModelName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Text("このモデルを追加する", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().height(34.dp)
                ) {
                    Text("キャンセル", color = AppColors.textPrimary, fontSize = 11.sp)
                }
            }
        }
    }
}