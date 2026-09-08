package com.example.novelscraper.translation.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2PromptPreset
import com.example.novelscraper.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun V2ModelsTab(
    autoPromptEnabled: Boolean,
    onAutoPromptEnabledChange: (Boolean) -> Unit,
    autoOrderKoText: String,
    onAutoOrderKoTextChange: (String) -> Unit,
    autoOrderZhText: String,
    onAutoOrderZhTextChange: (String) -> Unit,
    autoOrderEnText: String,
    onAutoOrderEnTextChange: (String) -> Unit,
    batchPromptOrderText: String,
    onBatchPromptOrderTextChange: (String) -> Unit,
    profiles: MutableList<V2ModelProfile>,
    promptPresets: List<V2PromptPreset>,
    onOpenAddModelDialog: () -> Unit,
    onRequestSavePreset: (String) -> Unit,
    onRequestDeletePreset: (V2PromptPreset) -> Unit,
    onTestConnection: (suspend (V2ModelProfile) -> String)?
) {
    val coroutineScope = rememberCoroutineScope()
    var expandedModelId by remember { mutableStateOf<String?>(null) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf(mapOf<String, String>()) }
    var testingId by remember { mutableStateOf<String?>(null) }

    // プロンプト順序設定カード
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surfaceMedium),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = autoPromptEnabled,
                    onCheckedChange = onAutoPromptEnabledChange
                )
                Column {
                    Text(
                        "言語連動 プロンプト自動選択",
                        color = AppColors.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (autoPromptEnabled) "※小説フォルダの言語に応じたプロンプト順序を自動適用"
                        else "※OFF: 手動プロンプト順序を最優先 (全モデル共通)",
                        color = AppColors.textSecondary,
                        fontSize = 9.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (autoPromptEnabled) {
                Text(
                    "言語別の適用プロンプト順序 (カンマ区切りで自由に変更可能):",
                    color = AppColors.accentTealLight,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("韓国語 (KO):", color = AppColors.textSecondary, fontSize = 9.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        V2InputArea(value = autoOrderKoText, onValueChange = onAutoOrderKoTextChange, singleLine = true)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("中国語 (ZH):", color = AppColors.textSecondary, fontSize = 9.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        V2InputArea(value = autoOrderZhText, onValueChange = onAutoOrderZhTextChange, singleLine = true)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("英語 (EN):", color = AppColors.textSecondary, fontSize = 9.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        V2InputArea(value = autoOrderEnText, onValueChange = onAutoOrderEnTextChange, singleLine = true)
                    }
                }
            } else {
                Text(
                    "⚡ 手動プロンプト順序 (全モデル一括設定):",
                    color = AppColors.accentTealLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        V2InputArea(
                            value = batchPromptOrderText,
                            onValueChange = onBatchPromptOrderTextChange,
                            singleLine = true
                        )
                    }

                    Button(
                        onClick = {
                            val parsed = parsePromptList(batchPromptOrderText, listOf(1, 1))
                            for (i in profiles.indices) {
                                profiles[i] = profiles[i].copy(promptOrder = parsed, useCustomPromptOrder = false)
                            }
                            onBatchPromptOrderTextChange(parsed.joinToString(", "))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("全モデルに適用", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // プリセットドロップダウン
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .background(AppColors.surfaceDark, RoundedCornerShape(4.dp))
                            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                            .clickable { presetMenuExpanded = true }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("プリセット", color = AppColors.textPrimary, fontSize = 11.sp)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "プリセット", tint = Color.White, modifier = Modifier.size(18.dp))
                        }

                        DropdownMenu(
                            expanded = presetMenuExpanded,
                            onDismissRequest = { presetMenuExpanded = false },
                            modifier = Modifier.background(AppColors.surfaceDark)
                        ) {
                            promptPresets.forEach { preset ->
                                val isCurrent = (profiles.firstOrNull()?.promptOrder == preset.order)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${if (isCurrent) "✓ " else ""}${preset.label} [${preset.order.joinToString(",")}]",
                                            color = if (isCurrent) AppColors.accentTealLight else AppColors.textPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                onRequestDeletePreset(preset)
                                                presetMenuExpanded = false
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "削除", tint = Color(0xFFFF6666), modifier = Modifier.size(14.dp))
                                        }
                                    },
                                    onClick = {
                                        for (i in profiles.indices) {
                                            profiles[i] = profiles[i].copy(promptOrder = preset.order, useCustomPromptOrder = false)
                                        }
                                        onBatchPromptOrderTextChange(preset.order.joinToString(", "))
                                        presetMenuExpanded = false
                                    }
                                )
                            }

                            HorizontalDivider(color = Color.DarkGray)

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = AppColors.accentTeal, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("現在の順序をプリセット保存", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                },
                                onClick = {
                                    presetMenuExpanded = false
                                    onRequestSavePreset(batchPromptOrderText)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("※ 共通モデルに反映 (個別保護ONのモデルは保護されます) /「▼」でプリセット呼出・保存", color = AppColors.textSecondary, fontSize = 9.sp)
            }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // モデル一覧ヘッダー
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "実行・巡回モデルリスト (${profiles.size}件):",
            color = AppColors.textSecondary,
            fontSize = 11.sp
        )

        Button(
            onClick = onOpenAddModelDialog,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
            Spacer(modifier = Modifier.width(4.dp))
            Text("＋ モデルを追加", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (profiles.size > 1) {
        val minOutput = profiles.minOfOrNull { it.maxOutputChars } ?: 15000
        val minModel = profiles.minByOrNull { it.maxOutputChars }?.model?.ifBlank { "未指定" } ?: "未指定"
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.accentTealDark.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                .border(1.dp, AppColors.accentTeal.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚡ 分割基準: 最小モデル「$minModel」の $minOutput 文字に合わせて自動調整中",
                color = AppColors.accentTealLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // モデルカード一覧（アコーディオン）
    val parsedBatchOrder = parsePromptList(batchPromptOrderText, listOf(1, 1))
    val effectiveCommonOrder = if (autoPromptEnabled) {
        parsePromptList(autoOrderKoText, listOf(3, 7))
    } else {
        parsedBatchOrder
    }

    profiles.forEachIndexed { index, profile ->
        val profileKey = profile.id.ifBlank { "$index" }
        val isExpanded = (expandedModelId == profileKey)
        V2ProfileCard(
            index = index,
            totalCount = profiles.size,
            profile = profile,
            isExpanded = isExpanded,
            promptPresets = promptPresets,
            commonPromptOrder = effectiveCommonOrder,
            isAutoPromptEnabled = autoPromptEnabled,
            testResult = testResults[profileKey],
            testing = testingId == profileKey,
            onToggleExpand = {
                expandedModelId = if (isExpanded) null else profileKey
            },
            onUpdate = { updated -> profiles[index] = updated },
            onMoveUp = {
                if (index > 0) {
                    val item = profiles.removeAt(index)
                    profiles.add(index - 1, item)
                }
            },
            onMoveDown = {
                if (index < profiles.size - 1) {
                    val item = profiles.removeAt(index)
                    profiles.add(index + 1, item)
                }
            },
            onDelete = {
                if (profiles.size > 1) {
                    profiles.removeAt(index)
                    if (expandedModelId == profileKey) {
                        expandedModelId = null
                    }
                }
            },
            onRequestSavePreset = { order ->
                onRequestSavePreset(order.joinToString(", "))
            },
            onTest = if (onTestConnection == null) null else ({
                testingId = profileKey
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) { onTestConnection(profile) }
                    testResults = testResults + (profileKey to result)
                    testingId = null
                }
            })
        )
        Spacer(modifier = Modifier.height(6.dp))
    }
}