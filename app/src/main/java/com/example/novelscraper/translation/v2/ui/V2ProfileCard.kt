package com.example.novelscraper.translation.v2.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2PromptPreset
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.ui.theme.AppColors

/**
 * モデルプロファイルカード（アコーディオン形式）
 */
@Composable
internal fun V2ProfileCard(
    index: Int,
    totalCount: Int,
    profile: V2ModelProfile,
    isExpanded: Boolean,
    promptPresets: List<V2PromptPreset>,
    commonPromptOrder: List<Int>,
    isAutoPromptEnabled: Boolean,
    testResult: String?,
    testing: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (V2ModelProfile) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onRequestSavePreset: (List<Int>) -> Unit,
    onTest: (() -> Unit)?
) {
    val caps = when (profile.providerId) {
        "gemini" -> GEMINI_DESCRIPTOR.capabilitiesFor(profile.model.ifBlank { "gemini-3.5-flash" })
        else -> OPENROUTER_DESCRIPTOR.capabilitiesFor(profile.model.ifBlank { "unknown" })
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surfaceMedium),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // ヘッダー行（タップで展開/折りたたみ）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "#${index + 1}",
                        color = AppColors.accentTealLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = profile.model.ifBlank { "未設定" },
                        color = AppColors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(AppColors.accentTealDark, RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            profile.providerId.uppercase(),
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上へ", tint = AppColors.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (index < totalCount - 1) {
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下へ", tint = AppColors.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (totalCount > 1) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "削除", tint = Color(0xFFFF6666), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = Color.DarkGray)

                    // プロバイダー選択
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("gemini" to "Gemini", "openrouter" to "OpenRouter").forEach { (v, label) ->
                            SelectBox(
                                label = label,
                                selected = profile.providerId == v,
                                onClick = { onUpdate(profile.copy(providerId = v)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // モデル名入力
                    Text("モデル識別名 (API Model ID):", color = AppColors.textSecondary, fontSize = 10.sp)
                    V2InputArea(value = profile.model, onValueChange = { onUpdate(profile.copy(model = it.trim())) }, singleLine = true)

                    // 能力駆動パラメータ（Thinking Level, Temperature, Top-P, Reasoning Effort）
                    ProfileCapabilityEditors(profile = profile, capabilities = caps, onUpdate = onUpdate)

                    // maxOutputTokens
                    val defaultMaxTokens = caps?.maxOutputTokens ?: 65536
                    Text("maxOutputTokens (空＝最大値 $defaultMaxTokens / 1000〜$defaultMaxTokens):", color = AppColors.textSecondary, fontSize = 10.sp)
                    V2InputArea(
                        value = profile.maxOutputTokens?.toString() ?: "",
                        onValueChange = { str ->
                            onUpdate(profile.copy(maxOutputTokens = str.toIntOrNull() ?: if (str.isBlank()) null else profile.maxOutputTokens))
                        },
                        singleLine = true
                    )

                    // 個別プロンプト順序設定カード
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppColors.backgroundDark),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = profile.useCustomPromptOrder,
                                    onCheckedChange = { checked ->
                                        onUpdate(profile.copy(useCustomPromptOrder = checked))
                                    }
                                )
                                Column {
                                    Text(
                                        text = "このモデル固有のプロンプト順序を指定 (個別保護)",
                                        color = if (profile.useCustomPromptOrder) AppColors.accentTealLight else AppColors.textPrimary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (profile.useCustomPromptOrder) "※一括設定や言語自動選択に上書きされず、この順序を絶対優先します"
                                        else "※OFF: 最上部の一括設定または言語自動選択に従います",
                                        color = AppColors.textSecondary,
                                        fontSize = 8.sp
                                    )
                                }
                            }

                            if (profile.useCustomPromptOrder) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    var textVal by remember(profile.promptOrder) { mutableStateOf(profile.promptOrder.joinToString(", ")) }
                                    Box(modifier = Modifier.weight(1f)) {
                                        V2InputArea(
                                            value = textVal,
                                            onValueChange = { str ->
                                                textVal = str
                                                val list = str.split(Regex("[,、，\\s]+")).mapNotNull { it.toIntOrNull() }.filter { it in TranslationLimits.PROMPT_NUMBER_RANGE }
                                                onUpdate(profile.copy(promptOrder = list.ifEmpty { listOf(1, 1) }))
                                            },
                                            singleLine = true
                                        )
                                    }

                                    var presetMenuOpen by remember { mutableStateOf(false) }
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(AppColors.accentTeal, RoundedCornerShape(4.dp))
                                            .clickable { presetMenuOpen = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "プリセット読込", tint = Color.White, modifier = Modifier.size(20.dp))
                                        DropdownMenu(
                                            expanded = presetMenuOpen,
                                            onDismissRequest = { presetMenuOpen = false },
                                            modifier = Modifier.background(AppColors.surfaceDark)
                                        ) {
                                            promptPresets.forEach { preset ->
                                                val isCurrent = (profile.promptOrder == preset.order)
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = "${if (isCurrent) "✓ " else ""}${preset.label} [${preset.order.joinToString(",")}]",
                                                            color = if (isCurrent) AppColors.accentTealLight else AppColors.textPrimary,
                                                            fontSize = 11.sp,
                                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    },
                                                    onClick = {
                                                        presetMenuOpen = false
                                                        onUpdate(profile.copy(promptOrder = preset.order))
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(AppColors.accentTealDark, RoundedCornerShape(4.dp))
                                            .clickable { onRequestSavePreset(profile.promptOrder) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "プリセット登録", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(AppColors.surfaceMedium.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .border(1.dp, Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    val modeLabel = if (isAutoPromptEnabled) "言語連動に従う" else "共通一括設定に従う"
                                    Text(
                                        text = "現在の適用順序: [ ${commonPromptOrder.joinToString(", ")} ] ($modeLabel)",
                                        color = AppColors.accentTealLight,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 目標日本語出力文字数
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("目標日本語出力文字数 (文字):", color = AppColors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                V2InputArea(
                                    value = profile.maxOutputChars.toString(),
                                    onValueChange = { onUpdate(profile.copy(maxOutputChars = it.toIntOrNull()?.coerceIn(2000, 100000) ?: 15000)) },
                                    singleLine = true
                                )
                            }
                            Text(
                                "約 ${(profile.maxOutputChars / 10000.0).let { String.format("%.1f", it) }} 万文字",
                                color = AppColors.accentTealLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val (zhKb, koKb, enKb) = V2Settings.inputSizeEstimateKb(profile.maxOutputChars)
                        Text(
                            "※入力目安: 中 約${zhKb}KB / 韓 約${koKb}KB / 英 約${enKb}KB (言語別に自動逆算)" +
                                    if (totalCount > 1) " / 複数モデル時は最小モデルの値に自動同期" else "",
                            color = AppColors.textTertiary,
                            fontSize = 8.sp
                        )
                    }

                    // 構造化出力スイッチ
                    if (caps.structuredOutput) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("JSON Schema (構造化出力)", color = AppColors.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = profile.useJsonSchema,
                                onCheckedChange = { onUpdate(profile.copy(useJsonSchema = it)) },
                                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.accentTeal)
                            )
                        }
                    }

                    // 接続テスト
                    if (onTest != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = onTest,
                                enabled = !testing && profile.model.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text(if (testing) "確認中…" else "接続テスト", color = Color.White, fontSize = 10.sp)
                            }
                            if (testResult != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    testResult,
                                    color = if (testResult.startsWith("OK")) Color(0xFF81C784) else Color(0xFFE57373),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}