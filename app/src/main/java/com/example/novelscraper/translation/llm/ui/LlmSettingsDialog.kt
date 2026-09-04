package com.example.novelscraper.translation.llm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.novelscraper.translation.llm.engine.LlmProvider
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.engine.ModelProfile
import com.example.novelscraper.translation.llm.engine.PromptOrderPreset
import com.example.novelscraper.translation.llm.prompt.TranslationPrompts
import com.example.novelscraper.ui.theme.AppColors

private enum class SettingsTab(val title: String) {
    MODELS("モデル設定"),
    COMMON("共通・APIキー"),
    PROMPTS("プロンプト編集")
}

@Composable
fun LlmSettingsDialog(
    currentConfig: LlmTranslationConfig,
    onSaveConfig: (LlmTranslationConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(SettingsTab.MODELS) }

    // モデル個別プロファイルリスト
    val modelProfiles = remember { mutableStateListOf<ModelProfile>().apply { addAll(currentConfig.modelProfiles) } }
    var expandedModelId by remember { mutableStateOf<String?>(null) }
    var modelSelectionTarget by remember { mutableStateOf<String?>(null) }

    // プロンプト順序プリセット一覧
    val promptPresets = remember { mutableStateListOf<PromptOrderPreset>().apply { addAll(currentConfig.promptPresets) } }
    var batchPromptOrderText by remember {
        mutableStateOf(modelProfiles.firstOrNull()?.promptOrder?.joinToString(", ") ?: "1, 1")
    }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var presetOrderToSave by remember { mutableStateOf("1, 1") }
    var presetToDelete by remember { mutableStateOf<PromptOrderPreset?>(null) }

    // 共通・APIキー設定
    var geminiKeysText by remember { mutableStateOf(currentConfig.geminiApiKeys.joinToString("\n")) }
    var openRouterKey by remember { mutableStateOf(currentConfig.openRouterApiKey) }
    var groqKey by remember { mutableStateOf(currentConfig.groqApiKey) }

    var geminiRotationEnabled by remember { mutableStateOf(currentConfig.geminiRotationEnabled) }
    var geminiCooldownSecText by remember { mutableStateOf(currentConfig.geminiCooldownSec.toString()) }
    var requestDelaySecText by remember { mutableStateOf(currentConfig.requestDelaySec.toString()) }

    var enableCompletionMarker by remember { mutableStateOf(currentConfig.enableCompletionMarker) }
    var enableTextSplit by remember { mutableStateOf(currentConfig.enableTextSplit) }
    var textSplitSizeCharsText by remember { mutableStateOf(currentConfig.textSplitSizeChars.coerceAtLeast(500).toString()) }

    var enableDictGen by remember { mutableStateOf(currentConfig.enableDictGen) }
    var dictProvider by remember { mutableStateOf(currentConfig.dictProvider) }

    var dictGeminiModel by remember { mutableStateOf(currentConfig.dictGeminiModel) }
    var dictGeminiMergeModel by remember { mutableStateOf(currentConfig.dictGeminiMergeModel) }

    var dictOpenRouterModel by remember { mutableStateOf(currentConfig.dictOpenRouterModel) }
    var dictOpenRouterMergeModel by remember { mutableStateOf(currentConfig.dictOpenRouterMergeModel) }
    var dictOpenRouterProviderOrderText by remember { mutableStateOf(currentConfig.dictOpenRouterProviderOrder.joinToString(", ")) }
    var dictOpenRouterProviderAllowFallbacks by remember { mutableStateOf(currentConfig.dictOpenRouterProviderAllowFallbacks == true) }

    var dictGroqModel by remember { mutableStateOf(currentConfig.dictGroqModel) }
    var dictGroqMergeModel by remember { mutableStateOf(currentConfig.dictGroqMergeModel) }
    var dictSampleMode by remember { mutableStateOf(currentConfig.dictSampleMode) }
    var dictWorkerCountText by remember { mutableStateOf(currentConfig.dictWorkerCount.toString()) }
    var dictConcurrencyText by remember { mutableStateOf(currentConfig.dictConcurrencyPerWorker.toString()) }
    var parallelWorkersText by remember { mutableStateOf(currentConfig.parallelWorkers.toString()) }
    var filesPerFolderText by remember { mutableStateOf(currentConfig.filesPerFolder.toString()) }
    var dictTotalPartsText by remember { mutableStateOf(currentConfig.dictTotalParts.toString()) }
    var dictBatchMaxKbText by remember { mutableStateOf((currentConfig.dictBatchMaxBytes / 1000).toString()) }
    var dictRequestDelaySecText by remember { mutableStateOf(currentConfig.dictRequestDelaySec.toString()) }
    var dict429CooldownSecText by remember { mutableStateOf(currentConfig.dict429CooldownSec.toString()) }

    var enableAutoPromptOrder by remember { mutableStateOf(currentConfig.enableAutoPromptOrder) }
    var autoPromptOrderKoreanText by remember { mutableStateOf(currentConfig.autoPromptOrderKorean.joinToString(", ")) }
    var autoPromptOrderChineseText by remember { mutableStateOf(currentConfig.autoPromptOrderChinese.joinToString(", ")) }
    var autoPromptOrderEnglishText by remember { mutableStateOf(currentConfig.autoPromptOrderEnglish.joinToString(", ")) }

    var enablePrevSrcContext by remember { mutableStateOf(currentConfig.enablePrevSrcContext) }
    var outputSubDir by remember { mutableStateOf(currentConfig.outputSubDir) }

    var sizeRatioZhMinText by remember { mutableStateOf(currentConfig.sizeRatioZhMin.toString()) }
    var sizeRatioZhMaxText by remember { mutableStateOf(currentConfig.sizeRatioZhMax.toString()) }
    var sizeRatioKoMinText by remember { mutableStateOf(currentConfig.sizeRatioKoMin.toString()) }
    var sizeRatioKoMaxText by remember { mutableStateOf(currentConfig.sizeRatioKoMax.toString()) }
    var sizeRatioEnMinText by remember { mutableStateOf(currentConfig.sizeRatioEnMin.toString()) }
    var sizeRatioEnMaxText by remember { mutableStateOf(currentConfig.sizeRatioEnMax.toString()) }
    var sizeRatioJaMinText by remember { mutableStateOf(currentConfig.sizeRatioJaMin.toString()) }
    var sizeRatioJaMaxText by remember { mutableStateOf(currentConfig.sizeRatioJaMax.toString()) }

    // プロンプト編集
    var editingPromptNumber by remember { mutableStateOf(1) }
    val customPromptsMap = remember { mutableStateMapOf<Int, String>().apply { putAll(currentConfig.customPrompts) } }

    var showBatchSplitHelpDialog by remember { mutableStateOf(false) }

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
                Text(
                    text = "AI / LLM 翻訳 詳細設定",
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
                    SettingsTab.entries.forEach { tab ->
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
                        SettingsTab.MODELS -> {
                            // モデル設定タブ
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.accentTealDark.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "💡 リストの並び順がフォールバック（リトライ）優先順になります。\n例: #1 (Gemini) で失敗した場合、自動的に #2 (OpenRouter) や #3 (Groq) へ切り替えて同じファイルを再試行します。",
                                    color = AppColors.accentTealLight,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // プロンプト順序設定エリア (言語連動自動選択 or 全モデル手動一括)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = AppColors.surfaceMedium),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = enableAutoPromptOrder,
                                            onCheckedChange = { enableAutoPromptOrder = it }
                                        )
                                        Column {
                                            Text("言語連動 プロンプト自動選択", color = AppColors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                if (enableAutoPromptOrder) "※小説フォルダの言語に応じたプロンプト順序を自動適用"
                                                else "※OFF: 成人向け等の手動プロンプト順序を最優先 (全モデル共通)",
                                                color = AppColors.textSecondary,
                                                fontSize = 9.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    if (enableAutoPromptOrder) {
                                        // 言語別 自動選択プロンプト順序のカスタマイズ入力欄
                                        Text("言語別の適用プロンプト順序 (カンマ区切りで自由に変更可能):", color = AppColors.accentTealLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("韓国語 (KO):", color = AppColors.textSecondary, fontSize = 9.sp)
                                                BasicInputArea(value = autoPromptOrderKoreanText, onValueChange = { autoPromptOrderKoreanText = it })
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("中国語 (ZH):", color = AppColors.textSecondary, fontSize = 9.sp)
                                                BasicInputArea(value = autoPromptOrderChineseText, onValueChange = { autoPromptOrderChineseText = it })
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("英語 (EN):", color = AppColors.textSecondary, fontSize = 9.sp)
                                                BasicInputArea(value = autoPromptOrderEnglishText, onValueChange = { autoPromptOrderEnglishText = it })
                                            }
                                        }
                                    } else {
                                        // 手動一括プロンプト設定 (既存の入力欄 + プリセットボタン)
                                        Text("⚡ 手動プロンプト順序 (全モデル一括設定):", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // プロンプト順序入力欄
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(36.dp)
                                                    .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                                                    .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 8.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                BasicTextField(
                                                    value = batchPromptOrderText,
                                                    onValueChange = { str -> batchPromptOrderText = str },
                                                    singleLine = true,
                                                    textStyle = TextStyle(
                                                        color = AppColors.textPrimary,
                                                        fontSize = 12.sp
                                                    ),
                                                    cursorBrush = SolidColor(AppColors.accentTealLight),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            // 「全モデルに適用」ボタン (一発で全モデルに反映)
                                            Button(
                                                onClick = {
                                                    val parsed = batchPromptOrderText.split(Regex("[,、，\\s]+")).mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.ifEmpty { listOf(1, 1) }
                                                    for (i in modelProfiles.indices) {
                                                        modelProfiles[i] = modelProfiles[i].copy(promptOrder = parsed, useCustomPromptOrder = false)
                                                    }
                                                    batchPromptOrderText = parsed.joinToString(", ")
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                                modifier = Modifier.height(36.dp)
                                            ) {
                                                Text("全モデルに適用", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }

                                            // 「プリセット」プルダウンボタン (適用ボタンの右隣に配置)
                                            Box(
                                                modifier = Modifier
                                                    .height(36.dp)
                                                    .background(AppColors.surfaceMedium, RoundedCornerShape(4.dp))
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
                                                        val isCurrent = (modelProfiles.firstOrNull()?.promptOrder == preset.order)
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
                                                                        presetToDelete = preset
                                                                        presetMenuExpanded = false
                                                                    },
                                                                    modifier = Modifier.size(24.dp)
                                                                ) {
                                                                    Icon(
                                                                        Icons.Default.Delete,
                                                                        contentDescription = "削除",
                                                                        tint = Color(0xFFFF6666),
                                                                        modifier = Modifier.size(14.dp)
                                                                    )
                                                                }
                                                            },
                                                            onClick = {
                                                                for (i in modelProfiles.indices) {
                                                                    modelProfiles[i] = modelProfiles[i].copy(promptOrder = preset.order, useCustomPromptOrder = false)
                                                                }
                                                                batchPromptOrderText = preset.order.joinToString(", ")
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
                                                            presetOrderToSave = batchPromptOrderText
                                                            showAddPresetDialog = true
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

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "実行・巡回モデルリスト (${modelProfiles.size}件):",
                                    color = AppColors.textSecondary,
                                    fontSize = 11.sp
                                )

                                Button(
                                    onClick = { modelSelectionTarget = "WORKER_LIST" },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("＋ モデルを追加", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (modelProfiles.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("モデルが登録されていません。「＋ モデルを追加」から追加してください", color = AppColors.textSecondary, fontSize = 11.sp)
                                }
                            } else {
                                val parsedBatchOrder = batchPromptOrderText.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(1, 1) }
                                val effectiveCommonOrder = if (enableAutoPromptOrder) {
                                    autoPromptOrderKoreanText.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(3, 7) }
                                } else {
                                    parsedBatchOrder
                                }

                                modelProfiles.forEachIndexed { index, profile ->
                                    val isExpanded = (expandedModelId == profile.id)
                                    ModelProfileCard(
                                        index = index,
                                        totalCount = modelProfiles.size,
                                        profile = profile,
                                        isExpanded = isExpanded,
                                        promptPresets = promptPresets,
                                        commonPromptOrder = effectiveCommonOrder,
                                        isAutoPromptEnabled = enableAutoPromptOrder,
                                        onToggleExpand = {
                                            expandedModelId = if (isExpanded) null else profile.id
                                        },
                                        onUpdate = { updated ->
                                            modelProfiles[index] = updated
                                        },
                                        onRequestSavePreset = { order ->
                                            presetOrderToSave = order.joinToString(", ")
                                            showAddPresetDialog = true
                                        },
                                        onMoveUp = {
                                            if (index > 0) {
                                                val item = modelProfiles.removeAt(index)
                                                modelProfiles.add(index - 1, item)
                                            }
                                        },
                                        onMoveDown = {
                                            if (index < modelProfiles.size - 1) {
                                                val item = modelProfiles.removeAt(index)
                                                modelProfiles.add(index + 1, item)
                                            }
                                        },
                                        onDelete = {
                                            // 最低1件は残す (0件保存→開始不能の防止)
                                            if (modelProfiles.size > 1) {
                                                modelProfiles.removeAt(index)
                                                if (expandedModelId == profile.id) {
                                                    expandedModelId = modelProfiles.firstOrNull()?.id
                                                }
                                            }
                                        },
                                        onShowBatchSplitHelp = { showBatchSplitHelpDialog = true }
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }

                        SettingsTab.COMMON -> {
                            // ==========================================
                            // 1. 🔑 各社 APIキー設定
                            // ==========================================
                            Text("🔑 各社 APIキー設定", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))

                            Text("Google AI Studio (Gemini) APIキープール (1行1キー / 複数可):", color = AppColors.textSecondary, fontSize = 10.sp)
                            BasicInputArea(value = geminiKeysText, onValueChange = { geminiKeysText = it }, minLines = 2)
                            TextButton(
                                onClick = {
                                    geminiKeysText = LlmTranslationConfig().geminiApiKeys.joinToString("\n")
                                }
                            ) {
                                Text("同梱値にリセット", color = AppColors.accentTealLight, fontSize = 10.sp)
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = geminiRotationEnabled, onCheckedChange = { geminiRotationEnabled = it })
                                Text("429検知時の即時キー/モデルローテーション有効", color = AppColors.textPrimary, fontSize = 10.sp)
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("OpenRouter APIキー:", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = openRouterKey, onValueChange = { openRouterKey = it }, minLines = 1)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Groq APIキー:", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = groqKey, onValueChange = { groqKey = it }, minLines = 1)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(8.dp))

                            // ==========================================
                            // 2. 📄 本文翻訳・実行設定 (翻訳に関するすべての設定を集約！)
                            // ==========================================
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📄 本文翻訳・実行設定", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(AppColors.accentTeal.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                                        .border(0.5.dp, AppColors.accentTeal, RoundedCornerShape(3.dp))
                                        .clickable { showBatchSplitHelpDialog = true }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("💡 バッチ・分割の仕組み図解", color = AppColors.accentTealLight, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("出力フォルダ名:", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = outputSubDir, onValueChange = { outputSubDir = it })
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("本文翻訳 並列ワーカー数 (1〜6):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = parallelWorkersText, onValueChange = { parallelWorkersText = it })
                                    Text("※同時並行ファイル数 (1〜6)", color = AppColors.textTertiary, fontSize = 8.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("本文翻訳 リクエスト間隔 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = requestDelaySecText, onValueChange = { requestDelaySecText = it })
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Gemini 429待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = geminiCooldownSecText, onValueChange = { geminiCooldownSecText = it })
                                }
                            }



                            Spacer(modifier = Modifier.height(4.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enableCompletionMarker, onCheckedChange = { enableCompletionMarker = it })
                                Text("[SRC_END] 完了マーカー (途絶自動検出)", color = AppColors.textPrimary, fontSize = 10.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enableTextSplit, onCheckedChange = { enableTextSplit = it })
                                Text("巨大小説の物理分割 (part_*.txt化)", color = AppColors.textPrimary, fontSize = 10.sp)
                            }
                            if (enableTextSplit) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("物理分割文字数 (文字):", color = AppColors.textSecondary, fontSize = 9.sp)
                                BasicInputArea(value = textSplitSizeCharsText, onValueChange = { textSplitSizeCharsText = it })
                                Text("※例: 8000 ➔ 約8,000文字 (約2〜3話相当) 毎にパート分割", color = AppColors.textTertiary, fontSize = 8.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enablePrevSrcContext, onCheckedChange = { enablePrevSrcContext = it })
                                Text("直前ファイル原文末尾の文脈注入 (20行)", color = AppColors.textPrimary, fontSize = 10.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(8.dp))

                            // ==========================================
                            // 3. 📊 品質検証・サイズ比 (%) 設定 (すべて設定から変更可能！)
                            // ==========================================
                            Text("📊 品質検証・サイズ比 (%) 設定", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "※ 原文に対する訳文のバイト比率 (UTF-8)。下限未満は省略疑い、上限超過は水増し・ハルシネーション疑いとして自動リジェクトします。",
                                color = AppColors.textTertiary,
                                fontSize = 9.sp,
                                lineHeight = 13.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("中国語 (ZH) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) { BasicInputArea(value = sizeRatioZhMinText, onValueChange = { sizeRatioZhMinText = it }) }
                                        Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        Box(modifier = Modifier.weight(1f)) { BasicInputArea(value = sizeRatioZhMaxText, onValueChange = { sizeRatioZhMaxText = it }) }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("韓国語 (KO) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) { BasicInputArea(value = sizeRatioKoMinText, onValueChange = { sizeRatioKoMinText = it }) }
                                        Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        Box(modifier = Modifier.weight(1f)) { BasicInputArea(value = sizeRatioKoMaxText, onValueChange = { sizeRatioKoMaxText = it }) }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("英語 (EN) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) { BasicInputArea(value = sizeRatioEnMinText, onValueChange = { sizeRatioEnMinText = it }) }
                                        Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        Box(modifier = Modifier.weight(1f)) { BasicInputArea(value = sizeRatioEnMaxText, onValueChange = { sizeRatioEnMaxText = it }) }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("日本語 (JA) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) { BasicInputArea(value = sizeRatioJaMinText, onValueChange = { sizeRatioJaMinText = it }) }
                                        Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        Box(modifier = Modifier.weight(1f)) { BasicInputArea(value = sizeRatioJaMaxText, onValueChange = { sizeRatioJaMaxText = it }) }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(8.dp))

                            // ==========================================
                            // 3. 📖 人名辞書自動生成 (dictionary.json) (辞書に関するすべての設定を集約！)
                            // ==========================================
                            Text("📖 人名辞書自動生成 (dictionary.json)", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enableDictGen, onCheckedChange = { enableDictGen = it })
                                Text("辞書自動生成を有効にする", color = AppColors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            if (enableDictGen) {
                                Spacer(modifier = Modifier.height(4.dp))

                                Text("辞書抽出用モデル:", color = AppColors.textSecondary, fontSize = 10.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val currentDisplayModel = when (dictProvider) {
                                        LlmProvider.GEMINI -> dictGeminiModel
                                        LlmProvider.OPENROUTER -> dictOpenRouterModel
                                        LlmProvider.GROQ -> dictGroqModel
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                                            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        BasicTextField(
                                            value = currentDisplayModel,
                                            onValueChange = { newVal ->
                                                when (dictProvider) {
                                                    LlmProvider.GEMINI -> dictGeminiModel = newVal
                                                    LlmProvider.OPENROUTER -> dictOpenRouterModel = newVal
                                                    LlmProvider.GROQ -> dictGroqModel = newVal
                                                }
                                            },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                color = AppColors.textPrimary,
                                                fontSize = 11.sp
                                            ),
                                            cursorBrush = SolidColor(AppColors.accentTealLight),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Button(
                                        onClick = { modelSelectionTarget = "DICT_EXTRACT" },
                                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                                        shape = RoundedCornerShape(4.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text("モデル選択", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text("マージ・レビュー用モデル (空欄で抽出と同一):", color = AppColors.textSecondary, fontSize = 10.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val currentDisplayMerge = when (dictProvider) {
                                        LlmProvider.GEMINI -> dictGeminiMergeModel
                                        LlmProvider.OPENROUTER -> dictOpenRouterMergeModel
                                        LlmProvider.GROQ -> dictGroqMergeModel
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                                            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        BasicTextField(
                                            value = currentDisplayMerge,
                                            onValueChange = { newVal ->
                                                when (dictProvider) {
                                                    LlmProvider.GEMINI -> dictGeminiMergeModel = newVal
                                                    LlmProvider.OPENROUTER -> dictOpenRouterMergeModel = newVal
                                                    LlmProvider.GROQ -> dictGroqMergeModel = newVal
                                                }
                                            },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                color = AppColors.textPrimary,
                                                fontSize = 11.sp
                                            ),
                                            cursorBrush = SolidColor(AppColors.accentTealLight),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Button(
                                        onClick = { modelSelectionTarget = "DICT_MERGE" },
                                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                                        shape = RoundedCornerShape(4.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text("モデル選択", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (dictProvider == LlmProvider.OPENROUTER) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("OpenRouter プロバイダー指定 (カンマ区切り, 例: upstage, baidu/fp8):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = dictOpenRouterProviderOrderText, onValueChange = { dictOpenRouterProviderOrderText = it })
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = dictOpenRouterProviderAllowFallbacks,
                                            onCheckedChange = { dictOpenRouterProviderAllowFallbacks = it }
                                        )
                                        Text("指定プロバイダー障害時に他社へフォールバック許可", color = AppColors.textPrimary, fontSize = 10.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("対象総パート数 (先頭N件 / 0=全件):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictTotalPartsText, onValueChange = { dictTotalPartsText = it })
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("1回の送信上限サイズ (KB / 上限200):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictBatchMaxKbText, onValueChange = { dictBatchMaxKbText = it })
                                        Text("※エンジン上限200KB (5並列なら100KB〜200KB推奨)", color = AppColors.textTertiary, fontSize = 8.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("辞書抽出 ワーカー数 (キー分散数):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictWorkerCountText, onValueChange = { dictWorkerCountText = it })
                                        Text("※登録APIキー数に応じた値 (例: 6)", color = AppColors.textTertiary, fontSize = 8.sp)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("1ワーカーあたり並列数:", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictConcurrencyText, onValueChange = { dictConcurrencyText = it })
                                        Text("※1キーあたりの並行数 (例: 5)", color = AppColors.textTertiary, fontSize = 8.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                val totalReq = ((dictWorkerCountText.toIntOrNull() ?: 6) * (dictConcurrencyText.toIntOrNull() ?: 5)).coerceIn(1, 30)
                                Text("➔ 同時APIリクエスト合計: ${totalReq} 並列 (ワーカー数 × 並列数 / 最大30)", color = AppColors.accentTealLight, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("リクエスト待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictRequestDelaySecText, onValueChange = { dictRequestDelaySecText = it })
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("429検知時の待機時間 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dict429CooldownSecText, onValueChange = { dict429CooldownSecText = it })
                                    }
                                }
                            }
                        }

                        SettingsTab.PROMPTS -> {
                            Text("編集するプロンプトを選択:", color = AppColors.textSecondary, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                (1..7).forEach { num ->
                                    val isSelected = (editingPromptNumber == num)
                                    val hasCustom = customPromptsMap[num]?.isNotBlank() == true
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(
                                                if (isSelected) AppColors.accentTeal else if (hasCustom) AppColors.accentTealDark else AppColors.surfaceMedium,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { editingPromptNumber = num }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = num.toString(),
                                            color = if (isSelected) Color.White else AppColors.textPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            val promptTitle = when (editingPromptNumber) {
                                1 -> "1: 中国語標準 (通常の小説・一般向け)"
                                2 -> "2: 英語標準"
                                3 -> "3: 韓国語標準"
                                4 -> "4: NSFW対応 (成人向け・官能描写)"
                                5 -> "5: 直訳重視 (構造保持)"
                                6 -> "6: 読みやすさ重視 (自然な意訳)"
                                7 -> "7: 簡潔リトライ用"
                                else -> "$editingPromptNumber: カスタム"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(promptTitle, color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                TextButton(
                                    onClick = {
                                        customPromptsMap.remove(editingPromptNumber)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("初期値に戻す", color = Color(0xFFFF8888), fontSize = 10.sp)
                                }
                            }

                            val currentText = customPromptsMap[editingPromptNumber] ?: TranslationPrompts.getPromptByNumber(editingPromptNumber)
                            BasicInputArea(
                                value = currentText,
                                onValueChange = { customPromptsMap[editingPromptNumber] = it },
                                minLines = 8
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

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
                        Text("キャンセル", color = AppColors.textSecondary, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val keys = geminiKeysText.lines().map { it.trim() }.filter { it.isNotBlank() }
                            val newConfig = currentConfig.copy(
                                geminiApiKeys = keys,
                                geminiRotationEnabled = geminiRotationEnabled,
                                geminiCooldownSec = geminiCooldownSecText.toIntOrNull() ?: 15,
                                openRouterApiKey = openRouterKey.trim(),
                                groqApiKey = groqKey.trim(),
                                modelProfiles = modelProfiles.map { it.copy(promptOrder = it.promptOrder.ifEmpty { listOf(1, 1) }) },
                                promptPresets = promptPresets.toList(),
                                customPrompts = customPromptsMap.toMap(),
                                outputSubDir = outputSubDir.trim().ifBlank { "翻訳完了_LLM" },
                                enableCompletionMarker = enableCompletionMarker,
                                enableTextSplit = enableTextSplit,
                                textSplitSizeChars = (textSplitSizeCharsText.toIntOrNull() ?: 8000).coerceAtLeast(500),
                                enableDictGen = enableDictGen,
                                dictProvider = dictProvider,
                                dictModel = when (dictProvider) {
                                    LlmProvider.GEMINI -> dictGeminiModel.trim().ifBlank { "gemini-3.1-flash-lite" }
                                    LlmProvider.OPENROUTER -> dictOpenRouterModel.trim().ifBlank { "google/gemma-4-31b-it:free" }
                                    LlmProvider.GROQ -> dictGroqModel.trim().ifBlank { "llama-3.3-70b-versatile" }
                                },
                                dictMergeModel = when (dictProvider) {
                                    LlmProvider.GEMINI -> dictGeminiMergeModel.trim()
                                    LlmProvider.OPENROUTER -> dictOpenRouterMergeModel.trim()
                                    LlmProvider.GROQ -> dictGroqMergeModel.trim()
                                },
                                dictGeminiModel = dictGeminiModel.trim().ifBlank { "gemini-3.1-flash-lite" },
                                dictGeminiMergeModel = dictGeminiMergeModel.trim(),
                                dictOpenRouterModel = dictOpenRouterModel.trim().ifBlank { "google/gemma-4-31b-it:free" },
                                dictOpenRouterMergeModel = dictOpenRouterMergeModel.trim(),
                                dictOpenRouterProviderOrder = dictOpenRouterProviderOrderText.split(Regex("[,、，\\s]+")).map { it.trim() }.filter { it.isNotBlank() },
                                dictOpenRouterProviderAllowFallbacks = dictOpenRouterProviderAllowFallbacks,
                                dictGroqModel = dictGroqModel.trim().ifBlank { "llama-3.3-70b-versatile" },
                                dictGroqMergeModel = dictGroqMergeModel.trim(),
                                dictSampleMode = dictSampleMode,
                                dictTotalParts = dictTotalPartsText.toIntOrNull()?.coerceAtLeast(0) ?: 100,
                                dictBatchMaxBytes = ((dictBatchMaxKbText.toIntOrNull() ?: 50) * 1000).coerceIn(4000, 200000),
                                dictWorkerCount = dictWorkerCountText.toIntOrNull()?.coerceIn(1, 30) ?: 6,
                                dictConcurrencyPerWorker = dictConcurrencyText.toIntOrNull()?.coerceIn(1, 10) ?: 5,
                                dictParallelCount = ((dictWorkerCountText.toIntOrNull() ?: 6) * (dictConcurrencyText.toIntOrNull() ?: 5)).coerceIn(1, 30),
                                dictRequestDelaySec = dictRequestDelaySecText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                                dict429CooldownSec = dict429CooldownSecText.toIntOrNull()?.coerceIn(5, 300) ?: 60,
                                parallelWorkers = parallelWorkersText.toIntOrNull()?.coerceIn(1, 6) ?: 2,
                                enablePrevSrcContext = enablePrevSrcContext,
                                requestDelaySec = requestDelaySecText.toIntOrNull()?.coerceAtLeast(0) ?: 10,
                                enableAutoPromptOrder = enableAutoPromptOrder,
                                autoPromptOrderKorean = autoPromptOrderKoreanText.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(3, 7) },
                                autoPromptOrderChinese = autoPromptOrderChineseText.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(1, 1) },
                                autoPromptOrderEnglish = autoPromptOrderEnglishText.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(2, 7) },
                                sizeRatioZhMin = sizeRatioZhMinText.toIntOrNull()?.coerceIn(50, 300) ?: 102,
                                sizeRatioZhMax = sizeRatioZhMaxText.toIntOrNull()?.coerceIn(100, 500) ?: 200,
                                sizeRatioKoMin = sizeRatioKoMinText.toIntOrNull()?.coerceIn(50, 300) ?: 102,
                                sizeRatioKoMax = sizeRatioKoMaxText.toIntOrNull()?.coerceIn(100, 500) ?: 150,
                                sizeRatioEnMin = sizeRatioEnMinText.toIntOrNull()?.coerceIn(50, 300) ?: 105,
                                sizeRatioEnMax = sizeRatioEnMaxText.toIntOrNull()?.coerceIn(100, 500) ?: 220,
                                sizeRatioJaMin = sizeRatioJaMinText.toIntOrNull()?.coerceIn(50, 300) ?: 100,
                                sizeRatioJaMax = sizeRatioJaMaxText.toIntOrNull()?.coerceIn(100, 500) ?: 200
                            )
                            onSaveConfig(newConfig)
                        },
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
        var newLabel by remember { mutableStateOf("") }
        var newOrderText by remember(presetOrderToSave) { mutableStateOf(presetOrderToSave) }

        AlertDialog(
            onDismissRequest = { showAddPresetDialog = false },
            containerColor = AppColors.surfaceDark,
            title = { Text("プロンプト順序プリセット登録", color = AppColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("プリセット名 (例: 中短文, 韓リトライ):", color = AppColors.textSecondary, fontSize = 11.sp)
                    BasicInputArea(value = newLabel, onValueChange = { newLabel = it })

                    Text("プロンプト順序 (カンマ区切り, 例: 3, 7, 7):", color = AppColors.textSecondary, fontSize = 11.sp)
                    BasicInputArea(value = newOrderText, onValueChange = { newOrderText = it })
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = newOrderText.split(",").mapNotNull { it.trim().toIntOrNull() }
                        // 1-7は内蔵プロンプト、8以上はカスタム本文の登録が必須
                        val allKnown = parsed.isNotEmpty() &&
                                parsed.all { it in 1..7 || customPromptsMap[it]?.isNotBlank() == true }
                        if (allKnown && newLabel.isNotBlank()) {
                            promptPresets.add(PromptOrderPreset(label = newLabel.trim(), order = parsed))
                            showAddPresetDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal)
                ) {
                    Text("追加", color = Color.White, fontSize = 11.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPresetDialog = false }) {
                    Text("キャンセル", color = AppColors.textSecondary, fontSize = 11.sp)
                }
            }
        )
    }

    // プリセット削除確認ダイアログ
    if (presetToDelete != null) {
        val preset = presetToDelete!!
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            containerColor = AppColors.surfaceDark,
            title = { Text("プリセット削除", color = AppColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = { Text("プリセット「${preset.label}」を削除しますか？", color = AppColors.textPrimary, fontSize = 12.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        promptPresets.remove(preset)
                        presetToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5555))
                ) {
                    Text("削除", color = Color.White, fontSize = 11.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text("キャンセル", color = AppColors.textSecondary, fontSize = 11.sp)
                }
            }
        )
    }

    if (modelSelectionTarget != null) {
        AddModelSelectionDialog(
            registeredProfiles = modelProfiles,
            onAdd = { newProf ->
                when (modelSelectionTarget) {
                    "WORKER_LIST" -> {
                        val currentBatchOrder = batchPromptOrderText.split(Regex("[,、，\\s]+")).mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.ifEmpty { listOf(1, 1) }
                        modelProfiles.add(newProf.copy(promptOrder = currentBatchOrder, useCustomPromptOrder = false))
                        expandedModelId = newProf.id
                    }
                    "DICT_EXTRACT" -> {
                        dictProvider = newProf.provider
                        when (newProf.provider) {
                            LlmProvider.GEMINI -> dictGeminiModel = newProf.modelName
                            LlmProvider.OPENROUTER -> {
                                dictOpenRouterModel = newProf.modelName
                                if (newProf.providerOrder.isNotEmpty()) {
                                    dictOpenRouterProviderOrderText = newProf.providerOrder.joinToString(", ")
                                }
                                if (newProf.providerAllowFallbacks != null) {
                                    dictOpenRouterProviderAllowFallbacks = newProf.providerAllowFallbacks
                                }
                            }
                            LlmProvider.GROQ -> dictGroqModel = newProf.modelName
                        }
                    }
                    "DICT_MERGE" -> {
                        when (newProf.provider) {
                            LlmProvider.GEMINI -> dictGeminiMergeModel = newProf.modelName
                            LlmProvider.OPENROUTER -> dictOpenRouterMergeModel = newProf.modelName
                            LlmProvider.GROQ -> dictGroqMergeModel = newProf.modelName
                        }
                    }
                }
                modelSelectionTarget = null
            },
            onDismiss = { modelSelectionTarget = null }
        )
    }

    if (showBatchSplitHelpDialog) {
        BatchSplitExplanationDialog(
            onDismiss = { showBatchSplitHelpDialog = false }
        )
    }
}

/**
 * モデル個別設定カード
 */
@Composable
private fun ModelProfileCard(
    index: Int,
    totalCount: Int,
    profile: ModelProfile,
    isExpanded: Boolean,
    promptPresets: List<PromptOrderPreset>,
    commonPromptOrder: List<Int>,
    isAutoPromptEnabled: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (ModelProfile) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onRequestSavePreset: (List<Int>) -> Unit,
    onShowBatchSplitHelp: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = if (isExpanded) AppColors.surfaceMedium else AppColors.backgroundDark)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            // ヘッダー行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text("#${index + 1}", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = profile.modelName,
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
                        Text(profile.provider.name, color = Color.White, fontSize = 8.sp)
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
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "削除", tint = Color(0xFFFF6666), modifier = Modifier.size(16.dp))
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(6.dp))

                    // Thinking Level
                    if (profile.provider == LlmProvider.GEMINI) {
                        Text("Thinking Level (推論レベル):", color = AppColors.textSecondary, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("minimal", "low", "medium", "high").forEach { lvl ->
                                val isSel = (profile.thinkingLevel == lvl)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (isSel) AppColors.accentTeal else AppColors.backgroundDark, RoundedCornerShape(3.dp))
                                        .clickable { onUpdate(profile.copy(thinkingLevel = lvl)) }
                                        .padding(vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(lvl, color = if (isSel) Color.White else AppColors.textSecondary, fontSize = 9.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    if (profile.provider == LlmProvider.OPENROUTER) {
                        Text("Reasoning Effort (思考モード / V4系):", color = AppColors.textSecondary, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("none", "low", "medium", "high").forEach { eff ->
                                val isSel = (profile.reasoningEffort == eff)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (isSel) AppColors.accentTeal else AppColors.backgroundDark, RoundedCornerShape(3.dp))
                                        .clickable { onUpdate(profile.copy(reasoningEffort = eff)) }
                                        .padding(vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(eff, color = if (isSel) Color.White else AppColors.textSecondary, fontSize = 9.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))

                        Text("Reasoning Enabled (真偽値 / V3.2系):", color = AppColors.textSecondary, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("未指定" to null, "OFF (false)" to false, "ON (true)" to true).forEach { (label, boolVal) ->
                                val isSel = (profile.reasoningEnabled == boolVal)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (isSel) AppColors.accentTeal else AppColors.backgroundDark, RoundedCornerShape(3.dp))
                                        .clickable { onUpdate(profile.copy(reasoningEnabled = boolVal)) }
                                        .padding(vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = if (isSel) Color.White else AppColors.textSecondary, fontSize = 9.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))

                        // OpenRouter プロバイダー固定 & フォールバック設定 (モデル毎個別)
                        Text("プロバイダー指定 (カンマ区切り, 例: upstage, baidu/fp8):", color = AppColors.textSecondary, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        BasicInputArea(
                            value = profile.providerOrder.joinToString(", "),
                            onValueChange = { str ->
                                val list = str.split(Regex("[,、，\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
                                onUpdate(profile.copy(providerOrder = list))
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = (profile.providerAllowFallbacks == true),
                                onCheckedChange = { checked ->
                                    onUpdate(profile.copy(providerAllowFallbacks = checked))
                                }
                            )
                            Text("指定プロバイダー障害時に他社へフォールバック許可", color = AppColors.textPrimary, fontSize = 10.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Temperature
                    Text("Temperature (未設定可):", color = AppColors.textSecondary, fontSize = 9.sp)
                    BasicInputArea(
                        value = profile.temperature?.toString() ?: "",
                        onValueChange = { onUpdate(profile.copy(temperature = it.toDoubleOrNull())) }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // 個別プロンプト順序設定カード (保護モード & プリセット呼出/登録)
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
                                    // 個別プロンプト順序入力欄
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(32.dp)
                                            .background(AppColors.surfaceDark, RoundedCornerShape(4.dp))
                                            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        var textVal by remember(profile.promptOrder) { mutableStateOf(profile.promptOrder.joinToString(", ")) }
                                        BasicTextField(
                                            value = textVal,
                                            onValueChange = { str ->
                                                textVal = str
                                                val list = str.split(Regex("[,、，\\s]+")).mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }
                                                onUpdate(profile.copy(promptOrder = list.ifEmpty { listOf(1, 1) }))
                                            },
                                            singleLine = true,
                                            textStyle = TextStyle(color = AppColors.textPrimary, fontSize = 11.sp),
                                            cursorBrush = SolidColor(AppColors.accentTealLight),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    // プリセット呼出ボタン (▼)
                                    var presetMenuOpen by remember { mutableStateOf(false) }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
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
                                                            fontSize = 11.sp
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

                                    // プリセット登録ボタン (＋)
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(AppColors.accentTealDark, RoundedCornerShape(4.dp))
                                            .clickable { onRequestSavePreset(profile.promptOrder) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "プリセット登録", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else {
                                // 保護OFF時: 現在の実効プロンプト順序を可視化プレビュー
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

                    Spacer(modifier = Modifier.height(6.dp))

                    // 目標日本語出力文字数 (言語別入力サイズ自動逆算)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("目標日本語出力文字数 (文字):", color = AppColors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(AppColors.accentTeal.copy(alpha = 0.2f), RoundedCornerShape(3.dp))
                                    .border(0.5.dp, AppColors.accentTeal, RoundedCornerShape(3.dp))
                                    .clickable { onShowBatchSplitHelp() }
                                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
                            ) {
                                Text("💡 仕組み図解", color = AppColors.accentTealLight, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                BasicInputArea(
                                    value = profile.maxOutputChars.toString(),
                                    onValueChange = { onUpdate(profile.copy(maxOutputChars = it.toIntOrNull()?.coerceIn(2000, 100000) ?: 15000)) }
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
                        Text(
                            "※言語（中国語/韓国語/英語）を判定し、この日本語文字数が出力されるよう入力サイズを自動逆算して最適分割します",
                            color = AppColors.textTertiary,
                            fontSize = 8.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * モデル追加モーダルダイアログ
 */
@Composable
private fun AddModelSelectionDialog(
    registeredProfiles: List<ModelProfile> = emptyList(),
    onAdd: (ModelProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var dialogTab by remember { mutableIntStateOf(0) } // 0: プリセットから選ぶ, 1: 手動カスタム入力
    var customModelName by remember { mutableStateOf("") }
    var customProvider by remember { mutableStateOf(LlmProvider.GEMINI) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = RoundedCornerShape(8.dp),
            color = AppColors.surfaceDark,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                Text("モデルを選択", color = AppColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // 最上部のタブ切り替え (セグメントコントロール)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.backgroundDark, RoundedCornerShape(6.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isPresetTab = (dialogTab == 0)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isPresetTab) AppColors.accentTeal else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { dialogTab = 0 }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "プリセットから選ぶ",
                            color = if (isPresetTab) Color.White else AppColors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val isCustomTab = (dialogTab == 1)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isCustomTab) AppColors.accentTeal else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { dialogTab = 1 }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "＋ 手動カスタム入力",
                            color = if (isCustomTab) Color.White else AppColors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // タブに応じたコンテンツ表示領域
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (dialogTab == 0) {
                        // --- プリセットタブ ---
                        if (registeredProfiles.isNotEmpty()) {
                            Text("登録中モデルから選択:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            registeredProfiles.forEach { prof ->
                                PresetModelButton(
                                    label = "${prof.modelName} [${prof.provider.displayName}]",
                                    onClick = { onAdd(prof) }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Gemini
                        Text("Google AI Studio (Gemini):", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        listOf(
                            PresetModelItem("Gemini 3.5 Flash (標準・安定)", ModelProfile(modelName = "gemini-3.5-flash", provider = LlmProvider.GEMINI, thinkingLevel = "medium", splitThresholdBytes = 50000, chunkSizeBytes = 45000, batchMaxBytes = 45000)),
                            PresetModelItem("Gemini 3.6 Flash (最新Flash)", ModelProfile(modelName = "gemini-3.6-flash", provider = LlmProvider.GEMINI, thinkingLevel = "medium", splitThresholdBytes = 50000, chunkSizeBytes = 45000, batchMaxBytes = 45000)),
                            PresetModelItem("Gemini 3.7 Flash (最上位Flash)", ModelProfile(modelName = "gemini-3.7-flash", provider = LlmProvider.GEMINI, thinkingLevel = "medium", splitThresholdBytes = 50000, chunkSizeBytes = 45000, batchMaxBytes = 45000)),
                            PresetModelItem("Gemini 3.1 Flash Lite (高速・軽量)", ModelProfile(modelName = "gemini-3.1-flash-lite", provider = LlmProvider.GEMINI, thinkingLevel = "medium", temperature = 1.0, splitThresholdBytes = 50000, chunkSizeBytes = 45000, batchMaxBytes = 45000)),
                            PresetModelItem("Gemini 3.5 Flash Lite (最新Lite)", ModelProfile(modelName = "gemini-3.5-flash-lite", provider = LlmProvider.GEMINI, thinkingLevel = "medium", temperature = 1.0, splitThresholdBytes = 50000, chunkSizeBytes = 45000, batchMaxBytes = 45000)),
                            PresetModelItem("Gemma 4 31B (辞書・高品質)", ModelProfile(modelName = "gemma-4-31b-it", provider = LlmProvider.GEMINI, thinkingLevel = "medium", temperature = 1.0, splitThresholdBytes = 6000, chunkSizeBytes = 4000, batchMaxBytes = 6000))
                        ).forEach { item ->
                            PresetModelButton(label = item.label, onClick = { onAdd(item.profile) })
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(8.dp))

                        // OpenRouter
                        Text("OpenRouter:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        listOf(
                            PresetModelItem("DeepSeek V3.2", ModelProfile(modelName = "deepseek/deepseek-v3.2", provider = LlmProvider.OPENROUTER, temperature = 0.5, reasoningEnabled = false)),
                            PresetModelItem("GLM 5.3 Flash", ModelProfile(modelName = "z-ai/glm-5.3-flash", provider = LlmProvider.OPENROUTER, temperature = 0.5))
                        ).forEach { item ->
                            PresetModelButton(label = item.label, onClick = { onAdd(item.profile) })
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(8.dp))

                        // Groq
                        Text("Groq:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        listOf(
                            PresetModelItem("Llama 3.3 70B (Versatile)", ModelProfile(modelName = "llama-3.3-70b-versatile", provider = LlmProvider.GROQ, temperature = 0.5)),
                            PresetModelItem("Qwen 3.6 27B", ModelProfile(modelName = "qwen/qwen3.6-27b", provider = LlmProvider.GROQ, temperature = 0.5)),
                            PresetModelItem("Llama 3.1 8B (Instant)", ModelProfile(modelName = "llama-3.1-8b-instant", provider = LlmProvider.GROQ, temperature = 0.5))
                        ).forEach { item ->
                            PresetModelButton(label = item.label, onClick = { onAdd(item.profile) })
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        // 手動入力への誘導ボタン
                        Button(
                            onClick = { dialogTab = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                        ) {
                            Text("＋ 一覧にないカスタムモデルを手動入力する", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // --- 手動カスタム入力タブ ---
                        Text("プロバイダーを選択:", color = AppColors.textSecondary, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LlmProvider.entries.forEach { p ->
                                val isSel = (customProvider == p)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (isSel) AppColors.accentTeal else AppColors.surfaceMedium, RoundedCornerShape(4.dp))
                                        .border(1.dp, if (isSel) AppColors.accentTealLight else Color.DarkGray, RoundedCornerShape(4.dp))
                                        .clickable { customProvider = p }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        p.displayName,
                                        color = if (isSel) Color.White else AppColors.textSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("モデル識別名 (API Model ID):", color = AppColors.textSecondary, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                                .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = customModelName,
                                onValueChange = { customModelName = it },
                                singleLine = true,
                                textStyle = TextStyle(color = AppColors.textPrimary, fontSize = 12.sp),
                                cursorBrush = SolidColor(AppColors.accentTealLight),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            when (customProvider) {
                                LlmProvider.GEMINI -> "※例: gemini-3.1-flash-lite, gemma-4-31b-it"
                                LlmProvider.OPENROUTER -> "※例: google/gemma-4-31b-it:free, anthropic/claude-3.5-sonnet"
                                LlmProvider.GROQ -> "※例: llama-3.3-70b-versatile, mixtral-8x7b-32768"
                            },
                            color = AppColors.textTertiary,
                            fontSize = 9.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                if (customModelName.isNotBlank()) {
                                    onAdd(ModelProfile(modelName = customModelName.trim(), provider = customProvider))
                                }
                            },
                            enabled = customModelName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text("このカスタムモデルを選択して決定", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                ) {
                    Text("閉じる", color = AppColors.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

private data class PresetModelItem(val label: String, val profile: ModelProfile)

@Composable
private fun PresetModelButton(label: String, onClick: () -> Unit) {
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

@Composable
private fun BasicInputArea(
    value: String,
    onValueChange: (String) -> Unit,
    minLines: Int = 1,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Box(
        modifier = modifier
            .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
            .padding(6.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            minLines = minLines,
            textStyle = TextStyle(
                color = AppColors.textPrimary,
                fontSize = 12.sp
            ),
            cursorBrush = SolidColor(AppColors.accentTealLight),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * バッチ翻訳・分割チャンク翻訳の仕組み解説ダイアログ (図解・テキスト)
 */
@Composable
private fun BatchSplitExplanationDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(8.dp),
            color = AppColors.surfaceDark,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // ヘッダー
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "💡 バッチ翻訳と分割翻訳の仕組み",
                        color = AppColors.accentTealLight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる", tint = AppColors.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "本アプリのAI翻訳は、AIの出力限界やAPI制限に合わせて【小さければまとめて翻訳（バッチ）】、【大きければ安全に小さく分割（チャンク）】して最速かつ安全に処理します。",
                        color = AppColors.textPrimary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // カード 1: 📦 小ファイル（バッチ翻訳）
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppColors.backgroundDark),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("📦 小ファイルの場合：バッチ翻訳（まとめて送信）", color = Color(0xFF64B5F6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "短いチャプターを1話ずつ送ると、API待機時間や1分あたりのリクエスト制限（RPM 15回等）に引っかかります。そのため、バッチ枠（約32〜49KB）に収まる範囲で最大10話を自動で1つに束ねて翻訳します。",
                                color = AppColors.textSecondary,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // 図解ボックス
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1E2630), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFF2A3B4D), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "【バッチ翻訳の流れ】\n" +
                                            "📄 第1話 (5KB) ┐\n" +
                                            "📄 第2話 (8KB) ┼─> 📦 1リクエストに結合 ─> 🤖 AI (LLM)\n" +
                                            "📄 第3話 (6KB) ┘    [SEG:1]...[SEG:3]...          │\n" +
                                            "                                                  ▼\n" +
                                            "✅ 応答を各ファイルに自動分解・個別保存！ ◀───────┘",
                                    color = Color(0xFF90CAF9),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("※万が一AIが崩れた場合も、各ファイルを単体翻訳へ自動フォールバックして確実に救済します。", color = AppColors.textTertiary, fontSize = 8.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // カード 2: ✂️ 大ファイル（分割チャンク翻訳）
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppColors.backgroundDark),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("✂️ 大ファイルの場合：分割チャンク翻訳（小さくして送信）", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "AIが一度に出力できる文章量には上限があります。閾値を超える巨大小説や長文は、文区切り・段落境界を崩さずに安全なチャンクに自動分割して順次翻訳します。",
                                color = AppColors.textSecondary,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // 図解ボックス
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2E261B), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFF4D3B2A), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "【分割チャンク翻訳の流れ】\n" +
                                            "📚 巨大ファイル (120KB)\n" +
                                            "  ├─> ✂️ チャンク1 (35KB) ─> 🤖 AI ─> 保存 (.parts_*/out)\n" +
                                            "  ├─> ✂️ チャンク2 (35KB) ─> 🤖 AI ─> 保存 (.parts_*/out)\n" +
                                            "  └─> ✂️ チャンク3 (50KB) ─> 🤖 AI ─> 保存 (.parts_*/out)\n" +
                                            "                                           │\n" +
                                            "✨ 全チャンク完了後、自動で1つのファイルに完全結合！ ◀─┘",
                                    color = Color(0xFFFFCC80),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("※最後の行がたった1行だけ余った場合も、直前チャンクに自動マージされるため1行だけのゴミチャンクは発生しません。", color = AppColors.textTertiary, fontSize = 8.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // カード 3: ⚙️ AIモデル毎の「目標日本語出力文字数」の設定基準
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppColors.backgroundDark),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("⚙️「目標日本語出力文字数」で何が変わる？", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "AIモデル毎の出力能力に合わせて設定します。言語（中国語/韓国語/英語）の翻訳膨張率から逆算して、バッチ枠とチャンクサイズが自動調整されます。",
                                color = AppColors.textSecondary,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            // 比較テーブル
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.surfaceDark, RoundedCornerShape(4.dp))
                                    .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("モデル / 設定文字数", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                                    Text("中/英/韓 入力安全枠", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                                    Text("特徴", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                }
                                HorizontalDivider(color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Gemini Flash系\n(20,000文字)", color = AppColors.accentTealLight, fontSize = 9.sp, modifier = Modifier.weight(1.3f))
                                    Text("約 32KB 〜 49KB", color = AppColors.textPrimary, fontSize = 9.sp, modifier = Modifier.weight(1.3f))
                                    Text("大容量まとめ\n最速進行", color = AppColors.textSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Gemma 4 31B系\n(6,000文字)", color = Color(0xFFFFD54F), fontSize = 9.sp, modifier = Modifier.weight(1.3f))
                                    Text("約 10KB 〜 15KB", color = AppColors.textPrimary, fontSize = 9.sp, modifier = Modifier.weight(1.3f))
                                    Text("中規模枠\n高品質安定", color = AppColors.textSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("小型・軽量モデル\n(3,000〜4,000文字)", color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.weight(1.3f))
                                    Text("約 5KB 〜 8KB", color = AppColors.textPrimary, fontSize = 9.sp, modifier = Modifier.weight(1.3f))
                                    Text("途絶完全防止\n安全重視", color = AppColors.textSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                ) {
                    Text("閉じる", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}