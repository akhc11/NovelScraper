package com.example.novelscraper.translation.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2PromptPreset
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.defaultV2PromptPresets
import com.example.novelscraper.ui.theme.AppColors

private enum class V2SettingsTab(val title: String) {
    MODELS("モデル設定"),
    COMMON("共通・APIキー"),
    PROMPTS("プロンプト編集")
}

internal fun parsePromptList(text: String, default: List<Int>): List<Int> {
    val parsed = text.split(Regex("[,、，\\s]+")).mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
    return parsed.ifEmpty { default }
}

@Composable
fun V2SettingsDialog(
    initial: V2Settings,
    onSave: (V2Settings) -> Unit,
    onDismiss: () -> Unit,
    onTestConnection: (suspend (V2ModelProfile) -> String)? = null,
    onImportLegacy: ((String) -> Unit)? = null,
    importWarnings: List<String> = emptyList()
) {
    var selectedTab by remember { mutableStateOf(V2SettingsTab.MODELS) }

    // モデル個別プロファイルリスト
    val profiles = remember { mutableStateListOf<V2ModelProfile>().apply { addAll(initial.profiles) } }
    var modelSelectionTarget by remember { mutableStateOf<String?>(null) } // "WORKER_LIST", "DICT_EXTRACT", "DICT_MERGE"

    // プロンプト順序プリセット一覧
    val promptPresets = remember {
        mutableStateListOf<V2PromptPreset>().apply {
            addAll(initial.promptPresets.ifEmpty { defaultV2PromptPresets() })
        }
    }
    var batchPromptOrderText by remember {
        mutableStateOf(profiles.firstOrNull()?.promptOrder?.joinToString(", ") ?: "1, 1")
    }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var presetOrderToSave by remember { mutableStateOf("1, 1") }
    var presetToDelete by remember { mutableStateOf<V2PromptPreset?>(null) }

    // 言語連動プロンプト順序
    var autoPromptEnabled by remember { mutableStateOf(initial.promptSelection.autoEnabled) }
    var autoOrderKoText by remember { mutableStateOf(initial.promptSelection.autoOrderKo.joinToString(", ")) }
    var autoOrderZhText by remember { mutableStateOf(initial.promptSelection.autoOrderZh.joinToString(", ")) }
    var autoOrderEnText by remember { mutableStateOf(initial.promptSelection.autoOrderEn.joinToString(", ")) }
    var newPresetLabel by remember { mutableStateOf("") }

    // 共通・APIキータブの状態
    val commonState = remember(initial) { V2CommonTabState.from(initial) }

    // プロンプト編集
    val customPromptsMap = remember { mutableStateMapOf<Int, String>().apply { putAll(initial.customPrompts) } }

    fun buildDraftSettings(): V2Settings {
        val baseWithCommon = commonState.applyTo(initial)
        return baseWithCommon.copy(
            profiles = profiles.toList(),
            promptSelection = initial.promptSelection.copy(
                autoEnabled = autoPromptEnabled,
                autoOrderKo = parsePromptList(autoOrderKoText, listOf(3, 7)),
                autoOrderZh = parsePromptList(autoOrderZhText, listOf(1, 1)),
                autoOrderEn = parsePromptList(autoOrderEnText, listOf(2, 7))
            ),
            promptPresets = promptPresets.toList(),
            customPrompts = customPromptsMap.toMap()
        )
    }

    val draft = buildDraftSettings()
    val issues = validateV2Settings(draft)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(8.dp),
            color = AppColors.surfaceDark,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // タイトル
                Text(
                    text = "AI / LLM 翻訳 (v2) 詳細設定",
                    color = AppColors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // タブバー
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    V2SettingsTab.entries.forEach { tab ->
                        val isSelected = (selectedTab == tab)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) AppColors.accentTeal else AppColors.surfaceMedium,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { selectedTab = tab }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.title,
                                color = if (isSelected) Color.White else AppColors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // コンテンツエリア
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (selectedTab) {
                        V2SettingsTab.MODELS -> {
                            V2ModelsTab(
                                autoPromptEnabled = autoPromptEnabled,
                                onAutoPromptEnabledChange = { autoPromptEnabled = it },
                                autoOrderKoText = autoOrderKoText,
                                onAutoOrderKoTextChange = { autoOrderKoText = it },
                                autoOrderZhText = autoOrderZhText,
                                onAutoOrderZhTextChange = { autoOrderZhText = it },
                                autoOrderEnText = autoOrderEnText,
                                onAutoOrderEnTextChange = { autoOrderEnText = it },
                                batchPromptOrderText = batchPromptOrderText,
                                onBatchPromptOrderTextChange = { batchPromptOrderText = it },
                                profiles = profiles,
                                promptPresets = promptPresets,
                                onOpenAddModelDialog = { modelSelectionTarget = "WORKER_LIST" },
                                onRequestSavePreset = { orderStr ->
                                    presetOrderToSave = orderStr
                                    showAddPresetDialog = true
                                },
                                onRequestDeletePreset = { preset ->
                                    presetToDelete = preset
                                },
                                onTestConnection = onTestConnection
                            )
                        }

                        V2SettingsTab.COMMON -> {
                            V2CommonTab(
                                state = commonState,
                                onSelectDictModel = { modelSelectionTarget = "DICT_EXTRACT" },
                                onSelectDictMergeModel = { modelSelectionTarget = "DICT_MERGE" },
                                onImportLegacy = onImportLegacy,
                                importWarnings = importWarnings
                            )
                        }

                        V2SettingsTab.PROMPTS -> {
                            V2PromptsTab(
                                customPromptsMap = customPromptsMap
                            )
                        }
                    }

                    // バリデーション警告一覧
                    if (issues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            issues.forEach { issue ->
                                Text(
                                    (if (issue.blocksSave) "⛔ " else "⚠️ ") + issue.message,
                                    color = if (issue.blocksSave) Color(0xFFE57373) else Color(0xFFFFB74D),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 最下部操作ボタン
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("閉じる", color = AppColors.textPrimary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onSave(coercedV2Settings(draft)) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // プリセット追加ダイアログ
    if (showAddPresetDialog) {
        var newPresetLabel by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showAddPresetDialog = false }) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AppColors.surfaceDark,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("プロンプト順序プリセットの保存", color = AppColors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("順序: [ $presetOrderToSave ]", color = AppColors.accentTealLight, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("プリセット名 (例: 中文短縮, 韓文特化):", color = AppColors.textSecondary, fontSize = 10.sp)
                    V2InputArea(value = newPresetLabel, onValueChange = { newPresetLabel = it })
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { showAddPresetDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("キャンセル", color = AppColors.textPrimary, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                val order = parsePromptList(presetOrderToSave, listOf(1, 1))
                                val label = newPresetLabel.trim().ifBlank { "プリセット #${promptPresets.size + 1}" }
                                promptPresets.add(V2PromptPreset(id = System.currentTimeMillis().toString(), label = label, order = order))
                                showAddPresetDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // プリセット削除確認ダイアログ
    presetToDelete?.let { preset ->
        Dialog(onDismissRequest = { presetToDelete = null }) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AppColors.surfaceDark,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("プリセット削除確認", color = AppColors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("プリセット「${preset.label}」を削除しますか？", color = AppColors.textSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { presetToDelete = null },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("キャンセル", color = AppColors.textPrimary, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                promptPresets.remove(preset)
                                presetToDelete = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("削除", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // モデル追加モーダルダイアログ
    if (modelSelectionTarget != null) {
        V2AddModelSelectionDialog(
            registeredProfiles = profiles.toList(),
            onSelect = { selectedProfile ->
                when (modelSelectionTarget) {
                    "WORKER_LIST" -> {
                        profiles.add(selectedProfile)
                    }
                    "DICT_EXTRACT" -> {
                        commonState.dictProvider.value = selectedProfile.providerId
                        commonState.dictModel.value = selectedProfile.model
                        commonState.dictThinking.value = selectedProfile.thinkingLevel
                    }
                    "DICT_MERGE" -> {
                        commonState.dictMergeModel.value = selectedProfile.model
                    }
                }
                modelSelectionTarget = null
            },
            onDismiss = { modelSelectionTarget = null }
        )
    }
}
