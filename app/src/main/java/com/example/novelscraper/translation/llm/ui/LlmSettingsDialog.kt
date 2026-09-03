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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
    var showAddModelDialog by remember { mutableStateOf(false) }

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
    var textSplitSizeBytesText by remember { mutableStateOf(currentConfig.textSplitSizeBytes.toString()) }

    var enableDictGen by remember { mutableStateOf(currentConfig.enableDictGen) }
    var dictProvider by remember { mutableStateOf(currentConfig.dictProvider) }
    var dictModel by remember { mutableStateOf(currentConfig.dictModel) }
    var dictMergeModel by remember { mutableStateOf(currentConfig.dictMergeModel) }
    var dictSampleMode by remember { mutableStateOf(currentConfig.dictSampleMode) }
    var dictParallelCountText by remember { mutableStateOf(currentConfig.dictParallelCount.toString()) }
    var parallelWorkers by remember { mutableStateOf(currentConfig.parallelWorkers) }
    var filesPerFolderText by remember { mutableStateOf(currentConfig.filesPerFolder.toString()) }
    var dictTotalPartsText by remember { mutableStateOf(currentConfig.dictTotalParts.toString()) }
    var dictBatchMaxKbText by remember { mutableStateOf((currentConfig.dictBatchMaxBytes / 1000).toString()) }
    var dictRequestDelaySecText by remember { mutableStateOf(currentConfig.dictRequestDelaySec.toString()) }
    var dict429CooldownSecText by remember { mutableStateOf(currentConfig.dict429CooldownSec.toString()) }

    var enableAutoLanguageSize by remember { mutableStateOf(currentConfig.enableAutoLanguageSize) }
    var langSplitKoreanKbText by remember { mutableStateOf(currentConfig.langSplitKoreanKb.toString()) }
    var langSplitChineseKbText by remember { mutableStateOf(currentConfig.langSplitChineseKb.toString()) }
    var langSplitEnglishKbText by remember { mutableStateOf(currentConfig.langSplitEnglishKb.toString()) }

    var enableAutoPromptOrder by remember { mutableStateOf(currentConfig.enableAutoPromptOrder) }
    var autoPromptOrderKoreanText by remember { mutableStateOf(currentConfig.autoPromptOrderKorean.joinToString(", ")) }
    var autoPromptOrderChineseText by remember { mutableStateOf(currentConfig.autoPromptOrderChinese.joinToString(", ")) }
    var autoPromptOrderEnglishText by remember { mutableStateOf(currentConfig.autoPromptOrderEnglish.joinToString(", ")) }

    var enablePrevSrcContext by remember { mutableStateOf(currentConfig.enablePrevSrcContext) }
    var outputSubDir by remember { mutableStateOf(currentConfig.outputSubDir) }

    // プロンプト編集
    var editingPromptNumber by remember { mutableStateOf(1) }
    val customPromptsMap = remember { mutableStateMapOf<Int, String>().apply { putAll(currentConfig.customPrompts) } }

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
                                            // プロンプト順序入力欄 (入力時に即座に全モデルへ自動反映)
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
                                                    onValueChange = { str ->
                                                        batchPromptOrderText = str
                                                        val parsed = str.split(",").mapNotNull { it.trim().toIntOrNull() }
                                                        if (parsed.isNotEmpty()) {
                                                            for (i in modelProfiles.indices) {
                                                                if (!modelProfiles[i].useCustomPromptOrder) {
                                                                    modelProfiles[i] = modelProfiles[i].copy(promptOrder = parsed)
                                                                }
                                                            }
                                                        }
                                                    },
                                                    singleLine = true,
                                                    textStyle = TextStyle(
                                                        color = AppColors.textPrimary,
                                                        fontSize = 12.sp
                                                    ),
                                                    cursorBrush = SolidColor(AppColors.accentTealLight),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }

                                            // 「プリセット」プルダウン正方形ボタン (純粋なBoxで 36.dp x 36.dp を完全一致)
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(AppColors.accentTeal, RoundedCornerShape(4.dp))
                                                    .clickable { presetMenuExpanded = true },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.ArrowDropDown,
                                                    contentDescription = "プリセット",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(24.dp)
                                                )

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
                                                                    if (!modelProfiles[i].useCustomPromptOrder) {
                                                                        modelProfiles[i] = modelProfiles[i].copy(promptOrder = preset.order)
                                                                    }
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
                                    onClick = { showAddModelDialog = true },
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
                                modelProfiles.forEachIndexed { index, profile ->
                                    val isExpanded = (expandedModelId == profile.id)
                                    ModelProfileCard(
                                        index = index,
                                        totalCount = modelProfiles.size,
                                        profile = profile,
                                        isExpanded = isExpanded,
                                        promptPresets = promptPresets,
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
                                            modelProfiles.removeAt(index)
                                            if (expandedModelId == profile.id) {
                                                expandedModelId = modelProfiles.firstOrNull()?.id
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }

                        SettingsTab.COMMON -> {
                            // 共通・APIキー設定タブ
                            Text("Google AI Studio (Gemini) APIキープール (1行1キー / 複数可):", color = AppColors.textSecondary, fontSize = 11.sp)
                            BasicInputArea(value = geminiKeysText, onValueChange = { geminiKeysText = it }, minLines = 2)

                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = geminiRotationEnabled, onCheckedChange = { geminiRotationEnabled = it })
                                Text("429検知時の即時キー/モデルローテーション有効", color = AppColors.textPrimary, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("OpenRouter APIキー:", color = AppColors.textSecondary, fontSize = 11.sp)
                                    BasicInputArea(value = openRouterKey, onValueChange = { openRouterKey = it }, minLines = 1)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Groq APIキー:", color = AppColors.textSecondary, fontSize = 11.sp)
                                    BasicInputArea(value = groqKey, onValueChange = { groqKey = it }, minLines = 1)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(8.dp))

                            Text("動作タイミング & フォルダ設定:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("リクエスト間隔 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = requestDelaySecText, onValueChange = { requestDelaySecText = it })
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Gemini 429待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = geminiCooldownSecText, onValueChange = { geminiCooldownSecText = it })
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("出力フォルダ名:", color = AppColors.textSecondary, fontSize = 10.sp)
                                    BasicInputArea(value = outputSubDir, onValueChange = { outputSubDir = it })
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(8.dp))

                            Text("人名辞書自動生成 (dictionary.json):", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enableDictGen, onCheckedChange = { enableDictGen = it })
                                Text("辞書自動生成を有効にする", color = AppColors.textPrimary, fontSize = 11.sp)
                            }
                            if (enableDictGen) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("辞書生成プロバイダー:", color = AppColors.textSecondary, fontSize = 10.sp)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    LlmProvider.entries.forEach { p ->
                                        val isSel = (dictProvider == p)
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(if (isSel) AppColors.accentTeal else AppColors.backgroundDark, RoundedCornerShape(3.dp))
                                                .clickable { dictProvider = p }
                                                .padding(vertical = 3.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(p.name, color = if (isSel) Color.White else AppColors.textSecondary, fontSize = 9.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("辞書抽出用モデル (例: gemma-4-31b-it):", color = AppColors.textSecondary, fontSize = 10.sp)
                                BasicInputArea(value = dictModel, onValueChange = { dictModel = it })
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("マージ・レビュー用モデル (例: gemini-3.5-flash / 空欄で抽出と同一):", color = AppColors.textSecondary, fontSize = 10.sp)
                                BasicInputArea(value = dictMergeModel, onValueChange = { dictMergeModel = it })
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("対象総パート数 (先頭N件):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictTotalPartsText, onValueChange = { dictTotalPartsText = it })
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            listOf(50, 100, 200, 300, 500).forEach { p ->
                                                val isSel = (dictTotalPartsText == p.toString())
                                                Box(
                                                    modifier = Modifier
                                                        .background(if (isSel) AppColors.accentTeal else AppColors.surfaceMedium, RoundedCornerShape(2.dp))
                                                        .clickable { dictTotalPartsText = p.toString() }
                                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(p.toString(), color = if (isSel) Color.White else AppColors.textTertiary, fontSize = 8.sp)
                                                }
                                            }
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("1回の送信上限サイズ (KB):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictBatchMaxKbText, onValueChange = { dictBatchMaxKbText = it })
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("辞書抽出 並列数 (Gemma等は1〜2):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictParallelCountText, onValueChange = { dictParallelCountText = it })
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("リクエスト待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        BasicInputArea(value = dictRequestDelaySecText, onValueChange = { dictRequestDelaySecText = it })
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("429検知時の待機時間 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                BasicInputArea(value = dict429CooldownSecText, onValueChange = { dict429CooldownSecText = it })
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(8.dp))

                            Text("並列翻訳 & 品質オプション:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("本文翻訳 並列ワーカー数 (同時並行ファイル数):", color = AppColors.textSecondary, fontSize = 10.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (1..6).forEach { count ->
                                    val isSel = (parallelWorkers == count)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(if (isSel) AppColors.accentTeal else AppColors.backgroundDark, RoundedCornerShape(3.dp))
                                            .clickable { parallelWorkers = count }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("${count}並列", color = if (isSel) Color.White else AppColors.textSecondary, fontSize = 9.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enableAutoLanguageSize, onCheckedChange = { enableAutoLanguageSize = it })
                                Text("言語別の分割サイズ自動調整 (推奨)", color = AppColors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            if (enableAutoLanguageSize) {
                                Text("言語別の分割閾値 (KB):", color = AppColors.textSecondary, fontSize = 10.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("韓国語 (KB):", color = AppColors.textSecondary, fontSize = 9.sp)
                                        BasicInputArea(value = langSplitKoreanKbText, onValueChange = { langSplitKoreanKbText = it })
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("中国語 (KB):", color = AppColors.textSecondary, fontSize = 9.sp)
                                        BasicInputArea(value = langSplitChineseKbText, onValueChange = { langSplitChineseKbText = it })
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("英語 (KB):", color = AppColors.textSecondary, fontSize = 9.sp)
                                        BasicInputArea(value = langSplitEnglishKbText, onValueChange = { langSplitEnglishKbText = it })
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enableCompletionMarker, onCheckedChange = { enableCompletionMarker = it })
                                Text("[SRC_END] 完了マーカー (途絶自動検出)", color = AppColors.textPrimary, fontSize = 11.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enableTextSplit, onCheckedChange = { enableTextSplit = it })
                                Text("巨大小説の物理分割 (part_*.txt化)", color = AppColors.textPrimary, fontSize = 11.sp)
                            }
                            if (enableTextSplit) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("物理分割ファイルサイズ (B):", color = AppColors.textSecondary, fontSize = 10.sp)
                                BasicInputArea(value = textSplitSizeBytesText, onValueChange = { textSplitSizeBytesText = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = enablePrevSrcContext, onCheckedChange = { enablePrevSrcContext = it })
                                Text("直前ファイル原文末尾の文脈注入 (20行)", color = AppColors.textPrimary, fontSize = 11.sp)
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
                                geminiApiKeys = keys.ifEmpty { currentConfig.geminiApiKeys },
                                geminiRotationEnabled = geminiRotationEnabled,
                                geminiCooldownSec = geminiCooldownSecText.toIntOrNull() ?: 15,
                                openRouterApiKey = openRouterKey.trim(),
                                groqApiKey = groqKey.trim(),
                                modelProfiles = modelProfiles.toList(),
                                promptPresets = promptPresets.toList(),
                                customPrompts = customPromptsMap.toMap(),
                                outputSubDir = outputSubDir.trim().ifBlank { "翻訳完了_LLM" },
                                enableCompletionMarker = enableCompletionMarker,
                                enableTextSplit = enableTextSplit,
                                textSplitSizeBytes = textSplitSizeBytesText.toIntOrNull() ?: 8000,
                                enableDictGen = enableDictGen,
                                dictProvider = dictProvider,
                                dictModel = dictModel.trim().ifBlank { "gemma-4-31b-it" },
                                dictMergeModel = dictMergeModel.trim(),
                                dictSampleMode = dictSampleMode,
                                dictTotalParts = dictTotalPartsText.toIntOrNull()?.coerceAtLeast(0) ?: 100,
                                dictBatchMaxBytes = ((dictBatchMaxKbText.toIntOrNull() ?: 50) * 1000).coerceIn(4000, 100000),
                                dictParallelCount = dictParallelCountText.toIntOrNull()?.coerceIn(1, 30) ?: 30,
                                dictRequestDelaySec = dictRequestDelaySecText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                                dict429CooldownSec = dict429CooldownSecText.toIntOrNull()?.coerceIn(5, 300) ?: 60,
                                parallelWorkers = parallelWorkers,
                                enablePrevSrcContext = enablePrevSrcContext,
                                requestDelaySec = requestDelaySecText.toIntOrNull()?.coerceAtLeast(0) ?: 2,
                                enableAutoLanguageSize = enableAutoLanguageSize,
                                langSplitKoreanKb = langSplitKoreanKbText.toIntOrNull()?.coerceIn(5, 100) ?: 25,
                                langSplitChineseKb = langSplitChineseKbText.toIntOrNull()?.coerceIn(5, 100) ?: 20,
                                enableAutoPromptOrder = enableAutoPromptOrder,
                                autoPromptOrderKorean = autoPromptOrderKoreanText.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(3, 7) },
                                autoPromptOrderChinese = autoPromptOrderChineseText.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(1, 1) },
                                autoPromptOrderEnglish = autoPromptOrderEnglishText.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(2, 7) }
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
                        if (parsed.isNotEmpty() && newLabel.isNotBlank()) {
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

    if (showAddModelDialog) {
        AddModelSelectionDialog(
            onAdd = { newProf ->
                modelProfiles.add(newProf)
                expandedModelId = newProf.id
                showAddModelDialog = false
            },
            onDismiss = { showAddModelDialog = false }
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
    onToggleExpand: () -> Unit,
    onUpdate: (ModelProfile) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onRequestSavePreset: (List<Int>) -> Unit
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
                                val list = str.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
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
                                                val list = str.split(Regex("[,\\s]+")).mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }
                                                if (list.isNotEmpty()) {
                                                    onUpdate(profile.copy(promptOrder = list))
                                                }
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
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // チャンクサイズ・大ファイル閾値・バッチ上限
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("大ファイル閾値(B):", color = AppColors.textSecondary, fontSize = 9.sp)
                            BasicInputArea(
                                value = profile.splitThresholdBytes.toString(),
                                onValueChange = { onUpdate(profile.copy(splitThresholdBytes = it.toIntOrNull() ?: 13000)) }
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("チャンクサイズ(B):", color = AppColors.textSecondary, fontSize = 9.sp)
                            BasicInputArea(
                                value = profile.chunkSizeBytes.toString(),
                                onValueChange = { onUpdate(profile.copy(chunkSizeBytes = it.toIntOrNull() ?: 12000)) }
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("バッチ上限(B):", color = AppColors.textSecondary, fontSize = 9.sp)
                            BasicInputArea(
                                value = profile.batchMaxBytes.toString(),
                                onValueChange = { onUpdate(profile.copy(batchMaxBytes = it.toIntOrNull() ?: 12000)) }
                            )
                        }
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
    onAdd: (ModelProfile) -> Unit,
    onDismiss: () -> Unit
) {
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
                    .verticalScroll(rememberScrollState())
            ) {
                Text("＋ モデルを選択して追加", color = AppColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                // Gemini 3.x プリセット
                Text("Google AI Studio (Gemini):", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                listOf(
                    PresetModelItem("Gemini 3.5 Flash (標準・安定)", ModelProfile(modelName = "gemini-3.5-flash", provider = LlmProvider.GEMINI, thinkingLevel = "medium", splitThresholdBytes = 13000, chunkSizeBytes = 12000, batchMaxBytes = 12000)),
                    PresetModelItem("Gemini 3.6 Flash (最新Flash)", ModelProfile(modelName = "gemini-3.6-flash", provider = LlmProvider.GEMINI, thinkingLevel = "medium", splitThresholdBytes = 14000, chunkSizeBytes = 12000, batchMaxBytes = 12000)),
                    PresetModelItem("Gemini 3.7 Flash (最上位Flash)", ModelProfile(modelName = "gemini-3.7-flash", provider = LlmProvider.GEMINI, thinkingLevel = "medium", splitThresholdBytes = 14000, chunkSizeBytes = 12000, batchMaxBytes = 12000)),
                    PresetModelItem("Gemini 3.1 Flash Lite (高速・軽量)", ModelProfile(modelName = "gemini-3.1-flash-lite", provider = LlmProvider.GEMINI, thinkingLevel = "medium", temperature = 1.0, splitThresholdBytes = 14000, chunkSizeBytes = 12000, batchMaxBytes = 12000)),
                    PresetModelItem("Gemini 3.5 Flash Lite (最新Lite)", ModelProfile(modelName = "gemini-3.5-flash-lite", provider = LlmProvider.GEMINI, thinkingLevel = "medium", temperature = 1.0, splitThresholdBytes = 14000, chunkSizeBytes = 12000, batchMaxBytes = 12000)),
                    PresetModelItem("Gemma 4 31B (辞書・高品質)", ModelProfile(modelName = "gemma-4-31b-it", provider = LlmProvider.GEMINI, thinkingLevel = "medium", temperature = 1.0, splitThresholdBytes = 6000, chunkSizeBytes = 4000, batchMaxBytes = 6000))
                ).forEach { item ->
                    PresetModelButton(label = item.label, onClick = { onAdd(item.profile) })
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))

                // OpenRouter プリセット (ユーザー登録モデル)
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

                // Groq プリセット
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

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))

                // 手動カスタム追加
                Text("手動でモデルを追加:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LlmProvider.entries.forEach { p ->
                        val isSel = (customProvider == p)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (isSel) AppColors.accentTeal else AppColors.surfaceMedium, RoundedCornerShape(3.dp))
                                .clickable { customProvider = p }
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(p.name, color = if (isSel) Color.White else AppColors.textSecondary, fontSize = 9.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                BasicInputArea(value = customModelName, onValueChange = { customModelName = it })
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        if (customModelName.isNotBlank()) {
                            onAdd(ModelProfile(modelName = customModelName.trim(), provider = customProvider))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("カスタムモデルを追加", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
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