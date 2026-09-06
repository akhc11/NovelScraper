package com.example.novelscraper.translation.v2.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.V2_ENCODING_OPTIONS
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.pipeline.getV2PromptByNumber
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2PromptPreset
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.V2SizeRatios
import com.example.novelscraper.translation.v2.settings.defaultV2PromptPresets
import com.example.novelscraper.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class V2SettingsTab(val title: String) {
    MODELS("モデル設定"),
    COMMON("共通・APIキー"),
    PROMPTS("プロンプト編集")
}

@Composable
fun V2SettingsDialog(
    initial: V2Settings,
    importWarnings: List<String> = emptyList(),
    onSave: (V2Settings) -> Unit,
    onImportLegacy: ((String) -> Unit)? = null,
    onTestConnection: (suspend (V2ModelProfile) -> String)? = null,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(V2SettingsTab.MODELS) }

    // モデル個別プロファイルリスト
    val profiles = remember { mutableStateListOf<V2ModelProfile>().apply { addAll(initial.profiles) } }
    var expandedModelId by remember { mutableStateOf<String?>(profiles.firstOrNull()?.id) }
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
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var presetOrderToSave by remember { mutableStateOf("1, 1") }
    var presetToDelete by remember { mutableStateOf<V2PromptPreset?>(null) }

    // 言語連動プロンプト順序
    var autoPromptEnabled by remember { mutableStateOf(initial.promptSelection.autoEnabled) }
    var autoOrderKoText by remember { mutableStateOf(initial.promptSelection.autoOrderKo.joinToString(", ")) }
    var autoOrderZhText by remember { mutableStateOf(initial.promptSelection.autoOrderZh.joinToString(", ")) }
    var autoOrderEnText by remember { mutableStateOf(initial.promptSelection.autoOrderEn.joinToString(", ")) }

    // 共通・APIキー設定
    var geminiKeysText by remember { mutableStateOf(initial.geminiKeys.joinToString("\n")) }
    var geminiRotationEnabled by remember { mutableStateOf(initial.geminiRotationEnabled) }
    var geminiCooldownSecText by remember { mutableStateOf(initial.geminiCooldownSec.toString()) }
    var openRouterKey by remember { mutableStateOf(initial.openRouterKey) }
    var openRouterEndpoint by remember { mutableStateOf(initial.openRouterEndpoint) }

    // 本文翻訳・実行設定
    var outputSubDir by remember { mutableStateOf(initial.limits.outputSubDir) }
    var parallelWorkers by remember { mutableStateOf(initial.limits.parallelWorkers.toString()) }
    var requestDelay by remember { mutableStateOf(initial.limits.requestDelaySec.toString()) }
    var splitEnabled by remember { mutableStateOf(initial.split.enabled) }
    var splitSize by remember { mutableStateOf(initial.split.splitSizeChars.toString()) }
    var splitEncoding by remember { mutableStateOf(initial.split.inputEncoding) }
    var splitEncodingMenu by remember { mutableStateOf(false) }
    var prevEnabled by remember { mutableStateOf(initial.prevContext.enabled) }
    var prevLines by remember { mutableStateOf(initial.prevContext.lines.toString()) }

    // 品質検証・サイズ比
    var sizeRatioZhMinText by remember { mutableStateOf(initial.sizeRatios.zhMin.toString()) }
    var sizeRatioZhMaxText by remember { mutableStateOf(initial.sizeRatios.zhMax.toString()) }
    var sizeRatioKoMinText by remember { mutableStateOf(initial.sizeRatios.koMin.toString()) }
    var sizeRatioKoMaxText by remember { mutableStateOf(initial.sizeRatios.koMax.toString()) }
    var sizeRatioEnMinText by remember { mutableStateOf(initial.sizeRatios.enMin.toString()) }
    var sizeRatioEnMaxText by remember { mutableStateOf(initial.sizeRatios.enMax.toString()) }
    var sizeRatioJaMinText by remember { mutableStateOf(initial.sizeRatios.jaMin.toString()) }
    var sizeRatioJaMaxText by remember { mutableStateOf(initial.sizeRatios.jaMax.toString()) }

    // 辞書設定
    var dictEnabled by remember { mutableStateOf(initial.dict.enabled) }
    var dictProvider by remember { mutableStateOf(initial.dict.providerId) }
    var dictModel by remember { mutableStateOf(initial.dict.model) }
    var dictMergeModel by remember { mutableStateOf(initial.dict.mergeModel) }
    var dictThinking by remember { mutableStateOf(initial.dict.thinkingLevel) }
    var dictOpenRouterProviderOrderText by remember { mutableStateOf(initial.dict.providerOrder.joinToString(", ")) }
    var dictOpenRouterProviderAllowFallbacks by remember { mutableStateOf(initial.dict.providerAllowFallbacks == true) }
    var dictWorker by remember { mutableStateOf(initial.dict.workerCount.toString()) }
    var dictConcurrency by remember { mutableStateOf(initial.dict.concurrencyPerWorker.toString()) }
    var dictTotalParts by remember { mutableStateOf(initial.dict.totalParts.toString()) }
    var dictBatchKb by remember { mutableStateOf((initial.dict.batchMaxBytes / 1000).toString()) }
    var dictScanMb by remember { mutableStateOf((initial.dict.maxTotalScanBytes / 1000000).toString()) }
    var dictDelay by remember { mutableStateOf(initial.dict.requestDelaySec.toString()) }
    var dictCooldown by remember { mutableStateOf(initial.dict.cooldown429Sec.toString()) }

    // コスト上限 & 旧設定取込
    var maxTokens by remember { mutableStateOf(initial.cost.maxTokens?.toString() ?: "") }
    var maxCost by remember { mutableStateOf(initial.cost.maxCost?.toString() ?: "") }
    var legacyJson by remember { mutableStateOf("") }

    // プロンプト編集
    var editingPromptNumber by remember { mutableIntStateOf(1) }
    val customPromptsMap = remember { mutableStateMapOf<Int, String>().apply { putAll(initial.customPrompts) } }

    // 接続テスト状態
    var testResults by remember { mutableStateOf(mapOf<String, String>()) }
    var testingId by remember { mutableStateOf<String?>(null) }

    // 仕組み図解ダイアログ
    var showBatchSplitHelpDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun parsePromptList(text: String, default: List<Int>): List<Int> {
        val parsed = text.split(Regex("[,、，\\s]+")).mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
        return parsed.ifEmpty { default }
    }

    fun buildDraftSettings(): V2Settings {
        return initial.copy(
            geminiKeys = geminiKeysText.lines().map { it.trim() }.filter { it.isNotEmpty() },
            geminiRotationEnabled = geminiRotationEnabled,
            geminiCooldownSec = geminiCooldownSecText.toIntOrNull() ?: initial.geminiCooldownSec,
            openRouterKey = openRouterKey.trim(),
            openRouterEndpoint = openRouterEndpoint.trim().ifBlank { initial.openRouterEndpoint },
            profiles = profiles.toList(),
            dict = initial.dict.copy(
                enabled = dictEnabled,
                providerId = dictProvider,
                model = dictModel.trim(),
                mergeModel = dictMergeModel.trim(),
                thinkingLevel = dictThinking?.ifBlank { null },
                providerOrder = dictOpenRouterProviderOrderText.split(Regex("[,、，\\s]+")).map { it.trim() }.filter { it.isNotBlank() },
                providerAllowFallbacks = dictOpenRouterProviderAllowFallbacks,
                workerCount = dictWorker.toIntOrNull() ?: initial.dict.workerCount,
                concurrencyPerWorker = dictConcurrency.toIntOrNull() ?: initial.dict.concurrencyPerWorker,
                totalParts = dictTotalParts.toIntOrNull() ?: initial.dict.totalParts,
                batchMaxBytes = ((dictBatchKb.toIntOrNull() ?: (initial.dict.batchMaxBytes / 1000)) * 1000),
                maxTotalScanBytes = ((dictScanMb.toIntOrNull() ?: (initial.dict.maxTotalScanBytes / 1000000)) * 1000000),
                requestDelaySec = dictDelay.toIntOrNull() ?: initial.dict.requestDelaySec,
                cooldown429Sec = dictCooldown.toIntOrNull() ?: initial.dict.cooldown429Sec
            ),
            limits = initial.limits.copy(
                parallelWorkers = parallelWorkers.toIntOrNull() ?: initial.limits.parallelWorkers,
                requestDelaySec = requestDelay.toIntOrNull() ?: initial.limits.requestDelaySec,
                outputSubDir = outputSubDir.ifBlank { initial.limits.outputSubDir }
            ),
            split = initial.split.copy(
                enabled = splitEnabled,
                splitSizeChars = splitSize.toIntOrNull() ?: initial.split.splitSizeChars,
                inputEncoding = splitEncoding.ifBlank { "AUTO" }
            ),
            prevContext = initial.prevContext.copy(
                enabled = prevEnabled,
                lines = prevLines.toIntOrNull() ?: initial.prevContext.lines
            ),
            promptSelection = initial.promptSelection.copy(
                autoEnabled = autoPromptEnabled,
                autoOrderKo = parsePromptList(autoOrderKoText, listOf(3, 7)),
                autoOrderZh = parsePromptList(autoOrderZhText, listOf(1, 1)),
                autoOrderEn = parsePromptList(autoOrderEnText, listOf(2, 7))
            ),
            promptPresets = promptPresets.toList(),
            customPrompts = customPromptsMap.toMap(),
            sizeRatios = V2SizeRatios(
                zhMin = sizeRatioZhMinText.toIntOrNull() ?: initial.sizeRatios.zhMin,
                zhMax = sizeRatioZhMaxText.toIntOrNull() ?: initial.sizeRatios.zhMax,
                koMin = sizeRatioKoMinText.toIntOrNull() ?: initial.sizeRatios.koMin,
                koMax = sizeRatioKoMaxText.toIntOrNull() ?: initial.sizeRatios.koMax,
                enMin = sizeRatioEnMinText.toIntOrNull() ?: initial.sizeRatios.enMin,
                enMax = sizeRatioEnMaxText.toIntOrNull() ?: initial.sizeRatios.enMax,
                jaMin = sizeRatioJaMinText.toIntOrNull() ?: initial.sizeRatios.jaMin,
                jaMax = sizeRatioJaMaxText.toIntOrNull() ?: initial.sizeRatios.jaMax
            ),
            cost = initial.cost.copy(
                maxTokens = maxTokens.ifBlank { null }?.toLongOrNull(),
                maxCost = maxCost.ifBlank { null }?.toDoubleOrNull()
            )
        )
    }

    val draft = buildDraftSettings()
    val issues = validateV2Settings(draft)
    val blocking = issues.filter { it.blocksSave }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.94f),
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
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (selectedTab) {
                        V2SettingsTab.MODELS -> {
                            // ガイドバナー
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.accentTealDark.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "💡 リストの並び順がフォールバック（リトライ）優先順になります。\n例: #1 (Gemini) で失敗した場合、自動的に #2 (OpenRouter) へ切り替えて同じファイルを再試行します。",
                                    color = AppColors.accentTealLight,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                )
                            }

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
                                            onCheckedChange = { autoPromptEnabled = it }
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
                                                V2InputArea(value = autoOrderKoText, onValueChange = { autoOrderKoText = it })
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("中国語 (ZH):", color = AppColors.textSecondary, fontSize = 9.sp)
                                                V2InputArea(value = autoOrderZhText, onValueChange = { autoOrderZhText = it })
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("英語 (EN):", color = AppColors.textSecondary, fontSize = 9.sp)
                                                V2InputArea(value = autoOrderEnText, onValueChange = { autoOrderEnText = it })
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
                                                    onValueChange = { batchPromptOrderText = it },
                                                    singleLine = true
                                                )
                                            }

                                            Button(
                                                onClick = {
                                                    val parsed = parsePromptList(batchPromptOrderText, listOf(1, 1))
                                                    for (i in profiles.indices) {
                                                        profiles[i] = profiles[i].copy(promptOrder = parsed, useCustomPromptOrder = false)
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
                                                                        presetToDelete = preset
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
                                    onClick = { modelSelectionTarget = "WORKER_LIST" },
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

                            // モデルカード一覧（アコーディオン）
                            val parsedBatchOrder = parsePromptList(batchPromptOrderText, listOf(1, 1))
                            val effectiveCommonOrder = if (autoPromptEnabled) {
                                parsePromptList(autoOrderKoText, listOf(3, 7))
                            } else {
                                parsedBatchOrder
                            }

                            profiles.forEachIndexed { index, profile ->
                                val isExpanded = (expandedModelId == profile.id.ifBlank { "$index" })
                                V2ProfileCard(
                                    index = index,
                                    totalCount = profiles.size,
                                    profile = profile,
                                    isExpanded = isExpanded,
                                    promptPresets = promptPresets,
                                    commonPromptOrder = effectiveCommonOrder,
                                    isAutoPromptEnabled = autoPromptEnabled,
                                    testResult = testResults[profile.id.ifBlank { "$index" }],
                                    testing = testingId == profile.id.ifBlank { "$index" },
                                    onToggleExpand = {
                                        val key = profile.id.ifBlank { "$index" }
                                        expandedModelId = if (isExpanded) null else key
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
                                            if (expandedModelId == profile.id.ifBlank { "$index" }) {
                                                expandedModelId = profiles.firstOrNull()?.let { it.id.ifBlank { "0" } }
                                            }
                                        }
                                    },
                                    onRequestSavePreset = { order ->
                                        presetOrderToSave = order.joinToString(", ")
                                        showAddPresetDialog = true
                                    },
                                    onShowBatchSplitHelp = { showBatchSplitHelpDialog = true },
                                    onTest = if (onTestConnection == null) null else ({
                                        val key = profile.id.ifBlank { "$index" }
                                        testingId = key
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { onTestConnection(profile) }
                                            testResults = testResults + (key to result)
                                            testingId = null
                                        }
                                    })
                                )
                            }
                        }

                        V2SettingsTab.COMMON -> {
                            // ==========================================
                            // 1. 🔑 各社 APIキー設定
                            // ==========================================
                            Text("🔑 各社 APIキー設定", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))

                            Text("Google AI Studio (Gemini) APIキープール (1行1キー / 複数可):", color = AppColors.textSecondary, fontSize = 10.sp)
                            V2InputArea(value = geminiKeysText, onValueChange = { geminiKeysText = it }, minLines = 2)
                            TextButton(
                                onClick = {
                                    geminiKeysText = com.example.novelscraper.translation.llm.engine.LlmTranslationConfig().geminiApiKeys.joinToString("\n")
                                }
                            ) {
                                Text("同梱値にリセット", color = AppColors.accentTealLight, fontSize = 10.sp)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = geminiRotationEnabled, onCheckedChange = { geminiRotationEnabled = it })
                                Text("429検知時の即時キー/モデルローテーション有効", color = AppColors.textPrimary, fontSize = 10.sp)
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("OpenRouter APIキー:", color = AppColors.textSecondary, fontSize = 10.sp)
                            V2InputArea(value = openRouterKey, onValueChange = { openRouterKey = it })

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("OpenRouter エンドポイント (標準: https://openrouter.ai/api/v1/chat/completions):", color = AppColors.textSecondary, fontSize = 10.sp)
                            V2InputArea(value = openRouterEndpoint, onValueChange = { openRouterEndpoint = it })

                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(6.dp))

                            // ==========================================
                            // 2. 📄 本文翻訳・実行設定
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
                                    V2InputArea(value = outputSubDir, onValueChange = { outputSubDir = it })
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("本文翻訳 並列ワーカー数 (1〜6):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    V2InputArea(value = parallelWorkers, onValueChange = { parallelWorkers = it })
                                    Text("※同時並行ファイル数 (1〜6)", color = AppColors.textTertiary, fontSize = 8.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("本文翻訳 リクエスト間隔 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    V2InputArea(value = requestDelay, onValueChange = { requestDelay = it })
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Gemini 429待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    V2InputArea(value = geminiCooldownSecText, onValueChange = { geminiCooldownSecText = it })
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = splitEnabled, onCheckedChange = { splitEnabled = it })
                                Text("巨大小説の物理分割 (part_*.txt化)", color = AppColors.textPrimary, fontSize = 10.sp)
                            }
                            if (splitEnabled) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("物理分割文字数 (文字):", color = AppColors.textSecondary, fontSize = 9.sp)
                                V2InputArea(value = splitSize, onValueChange = { splitSize = it })
                                Text("※例: 7000 ➔ 約7,000文字 (約2〜3話相当) 毎にパート分割", color = AppColors.textTertiary, fontSize = 8.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("入力文字コード (自動判定で化ける時のみ指定):", color = AppColors.textSecondary, fontSize = 9.sp)
                                Box(
                                    modifier = Modifier
                                        .height(36.dp)
                                        .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                                        .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                        .clickable { splitEncodingMenu = true }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = V2_ENCODING_OPTIONS.firstOrNull { it.first == splitEncoding }?.second ?: splitEncoding,
                                            color = AppColors.textPrimary,
                                            fontSize = 11.sp
                                        )
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "文字コード", tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                    DropdownMenu(
                                        expanded = splitEncodingMenu,
                                        onDismissRequest = { splitEncodingMenu = false },
                                        modifier = Modifier.background(AppColors.surfaceDark)
                                    ) {
                                        V2_ENCODING_OPTIONS.forEach { option ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "${if (option.first == splitEncoding) "✓ " else ""}${option.second}",
                                                        color = if (option.first == splitEncoding) AppColors.accentTealLight else AppColors.textPrimary,
                                                        fontSize = 11.sp
                                                    )
                                                },
                                                onClick = {
                                                    splitEncoding = option.first
                                                    splitEncodingMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = prevEnabled, onCheckedChange = { prevEnabled = it })
                                Text("直前ファイル原文末尾の文脈注入", color = AppColors.textPrimary, fontSize = 10.sp)
                            }
                            if (prevEnabled) {
                                Text("注入行数 (1〜100):", color = AppColors.textSecondary, fontSize = 9.sp)
                                V2InputArea(value = prevLines, onValueChange = { prevLines = it })
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(6.dp))

                            // ==========================================
                            // 3. 📊 品質検証・サイズ比 (%) 設定
                            // ==========================================
                            Text("📊 品質検証・サイズ比 (%) 設定", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "※ 原文に対する訳文のバイト比率 (UTF-8)。下限未満は省略疑い、上限超過は水増し・ハルシネーション疑いとして自動リジェクトします。",
                                color = AppColors.textTertiary,
                                fontSize = 9.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("中国語 (ZH) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) { V2InputArea(value = sizeRatioZhMinText, onValueChange = { sizeRatioZhMinText = it }) }
                                        Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        Box(modifier = Modifier.weight(1f)) { V2InputArea(value = sizeRatioZhMaxText, onValueChange = { sizeRatioZhMaxText = it }) }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("韓国語 (KO) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) { V2InputArea(value = sizeRatioKoMinText, onValueChange = { sizeRatioKoMinText = it }) }
                                        Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        Box(modifier = Modifier.weight(1f)) { V2InputArea(value = sizeRatioKoMaxText, onValueChange = { sizeRatioKoMaxText = it }) }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("英語 (EN) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) { V2InputArea(value = sizeRatioEnMinText, onValueChange = { sizeRatioEnMinText = it }) }
                                        Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        Box(modifier = Modifier.weight(1f)) { V2InputArea(value = sizeRatioEnMaxText, onValueChange = { sizeRatioEnMaxText = it }) }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("日本語 (JA) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) { V2InputArea(value = sizeRatioJaMinText, onValueChange = { sizeRatioJaMinText = it }) }
                                        Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        Box(modifier = Modifier.weight(1f)) { V2InputArea(value = sizeRatioJaMaxText, onValueChange = { sizeRatioJaMaxText = it }) }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(6.dp))

                            // ==========================================
                            // 4. 📖 人名辞書自動生成 (dictionary.json)
                            // ==========================================
                            Text("📖 人名辞書自動生成 (dictionary.json)", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = dictEnabled, onCheckedChange = { dictEnabled = it })
                                Text("辞書自動生成を有効にする", color = AppColors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            if (dictEnabled) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("gemini" to "Gemini", "openrouter" to "OpenRouter").forEach { (v, label) ->
                                        SelectBox(
                                            label = label,
                                            selected = dictProvider == v,
                                            onClick = { dictProvider = v },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text("辞書抽出用モデル:", color = AppColors.textSecondary, fontSize = 10.sp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        V2InputArea(value = dictModel, onValueChange = { dictModel = it }, singleLine = true)
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        V2InputArea(value = dictMergeModel, onValueChange = { dictMergeModel = it }, singleLine = true)
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

                                val dictCaps = when (dictProvider) {
                                    "gemini" -> GEMINI_DESCRIPTOR.capabilitiesFor(dictModel.ifBlank { "gemini-3.5-flash" })
                                    else -> OPENROUTER_DESCRIPTOR.capabilitiesFor(dictModel)
                                }
                                ThinkingLevelEditor(
                                    selected = dictThinking,
                                    supported = dictCaps.thinking,
                                    onSelect = { dictThinking = it }
                                )

                                if (dictProvider == "openrouter") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("OpenRouter プロバイダー指定 (カンマ区切り, 例: upstage, baidu/fp8):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    V2InputArea(value = dictOpenRouterProviderOrderText, onValueChange = { dictOpenRouterProviderOrderText = it })
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = dictOpenRouterProviderAllowFallbacks,
                                            onCheckedChange = { dictOpenRouterProviderAllowFallbacks = it }
                                        )
                                        Text("指定プロバイダー障害時に他社へフォールバック許可", color = AppColors.textPrimary, fontSize = 10.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("対象ファイル数 (0=全件):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        V2InputArea(value = dictTotalParts, onValueChange = { dictTotalParts = it })
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("1回の送信上限 (KB / 上限200):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        V2InputArea(value = dictBatchKb, onValueChange = { dictBatchKb = it })
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("辞書抽出 ワーカー数 (1〜30):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        V2InputArea(value = dictWorker, onValueChange = { dictWorker = it })
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("1ワーカーあたり並列数 (1〜10):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        V2InputArea(value = dictConcurrency, onValueChange = { dictConcurrency = it })
                                    }
                                }
                                val totalReq = ((dictWorker.toIntOrNull() ?: 6) * (dictConcurrency.toIntOrNull() ?: 5)).coerceIn(1, 30)
                                Text("➔ 同時APIリクエスト合計: ${totalReq} 並列 (ワーカー数 × 並列数 / 最大30)", color = AppColors.accentTealLight, fontSize = 9.sp, fontWeight = FontWeight.Bold)

                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("リクエスト待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        V2InputArea(value = dictDelay, onValueChange = { dictDelay = it })
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("429待機時間 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                                        V2InputArea(value = dictCooldown, onValueChange = { dictCooldown = it })
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = Color.DarkGray)
                            Spacer(modifier = Modifier.height(6.dp))

                            // ==========================================
                            // 5. 💰 コスト上限 & 旧設定取込 (v2固有)
                            // ==========================================
                            Text("💰 コスト上限 & 旧設定取込", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("最大トークン (空＝無制限):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    V2InputArea(value = maxTokens, onValueChange = { maxTokens = it })
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("最大コスト (USD想定 / 空＝無制限):", color = AppColors.textSecondary, fontSize = 10.sp)
                                    V2InputArea(value = maxCost, onValueChange = { maxCost = it })
                                }
                            }

                            if (onImportLegacy != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("旧設定JSON取込（検証・合格分のみ継承）:", color = AppColors.textSecondary, fontSize = 10.sp)
                                V2InputArea(value = legacyJson, onValueChange = { legacyJson = it }, minLines = 2)
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { onImportLegacy(legacyJson) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("旧設定を取り込む", color = Color.White, fontSize = 11.sp)
                                }
                            }

                            if (importWarnings.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                importWarnings.forEach { w ->
                                    Text("⚠️ $w", color = Color(0xFFFFB74D), fontSize = 10.sp)
                                }
                            }
                        }

                        V2SettingsTab.PROMPTS -> {
                            // プロンプト編集タブ
                            Text("編集するプロンプトを選択:", color = AppColors.textSecondary, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                (1..7).forEach { num ->
                                    val isSel = (editingPromptNumber == num)
                                    val isCustomized = customPromptsMap.containsKey(num)
                                    SelectBox(
                                        label = "$num${if (isCustomized) "*" else ""}",
                                        selected = isSel,
                                        onClick = { editingPromptNumber = num },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            val promptTitles = mapOf(
                                1 to "1: 中国語 標準 (固有名詞カタカナ/漢字併記)",
                                2 to "2: 英語 標準 (カタカナ主導・世界観適合)",
                                3 to "3: 韓国語 標準 (漢字音カタカナ併記ルール)",
                                4 to "4: 成人向け (NSFW) (官能描写・無修正直接表現)",
                                5 to "5: 直訳・構造維持 (文構造・改行忠実)",
                                6 to "6: 意訳・読みやすさ重視 (自然な日本語再構築)",
                                7 to "7: リトライ短文 (最小限指示)"
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = promptTitles[editingPromptNumber] ?: "$editingPromptNumber",
                                color = AppColors.accentTealLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            val currentPromptText = customPromptsMap[editingPromptNumber]
                                ?: getV2PromptByNumber(editingPromptNumber)

                            Spacer(modifier = Modifier.height(4.dp))
                            V2InputArea(
                                value = currentPromptText,
                                onValueChange = { newVal ->
                                    customPromptsMap[editingPromptNumber] = newVal
                                },
                                minLines = 8,
                                maxLines = 14
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        customPromptsMap.remove(editingPromptNumber)
                                    }
                                ) {
                                    Text("このプロンプトをデフォルトに戻す", color = AppColors.accentTealLight, fontSize = 10.sp)
                                }

                                if (customPromptsMap.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            customPromptsMap.clear()
                                        }
                                    ) {
                                        Text("全プロンプトを初期化", color = Color(0xFFFF6666), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    // バリデーション警告一覧
                    if (issues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
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
                        enabled = blocking.isEmpty(),
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
                        expandedModelId = selectedProfile.id
                    }
                    "DICT_EXTRACT" -> {
                        dictProvider = selectedProfile.providerId
                        dictModel = selectedProfile.model
                        dictThinking = selectedProfile.thinkingLevel
                    }
                    "DICT_MERGE" -> {
                        dictMergeModel = selectedProfile.model
                    }
                }
                modelSelectionTarget = null
            },
            onDismiss = { modelSelectionTarget = null }
        )
    }

    // 仕組み図解ダイアログ
    if (showBatchSplitHelpDialog) {
        V2BatchSplitExplanationDialog(onDismiss = { showBatchSplitHelpDialog = false })
    }
}

/**
 * モデルプロファイルカード（アコーディオン形式）
 */
@Composable
private fun V2ProfileCard(
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
    onShowBatchSplitHelp: () -> Unit,
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
                    Text("maxOutputTokens (空＝未指定・1000〜200000):", color = AppColors.textSecondary, fontSize = 10.sp)
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
                                                val list = str.split(Regex("[,、，\\s]+")).mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }
                                                onUpdate(profile.copy(promptOrder = list.ifEmpty { listOf(1, 1) }))
                                            },
                                            singleLine = true
                                        )
                                    }

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
                            "※入力目安: 中 約${zhKb}KB / 韓 約${koKb}KB / 英 約${enKb}KB (言語別に自動逆算)",
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

/**
 * プリセット＆手動入力モデル選択ダイアログ
 */
@Composable
private fun V2AddModelSelectionDialog(
    registeredProfiles: List<V2ModelProfile> = emptyList(),
    onSelect: (V2ModelProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var dialogTab by remember { mutableIntStateOf(0) }
    var customModelName by remember { mutableStateOf("") }
    var customProvider by remember { mutableStateOf("gemini") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f),
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
                            "Gemini 3.5 Flash (標準・安定)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash", thinkingLevel = "medium"),
                            "Gemini 3.6 Flash (安定)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.6-flash", thinkingLevel = "medium"),
                            "Gemini 3.7 Flash (安定・高性能)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.7-flash", thinkingLevel = "medium"),
                            "Gemini 3.8 Flash (最新・最上位Flash)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.8-flash", thinkingLevel = "medium"),
                            "Gemini 3 Flash Preview (旧世代・互換用)" to V2ModelProfile(providerId = "gemini", model = "gemini-3-flash-preview", thinkingLevel = "medium"),
                            "Gemini 3.1 Flash Lite (高速・軽量)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.1-flash-lite", thinkingLevel = "medium", temperature = 1.0),
                            "Gemini 3.5 Flash Lite (最新Lite)" to V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash-lite", thinkingLevel = "medium", temperature = 1.0),
                            "Gemma 4 31B (辞書・高品質)" to V2ModelProfile(providerId = "gemini", model = "gemma-4-31b-it", thinkingLevel = "medium", temperature = 1.0)
                        ).forEach { (label, prof) ->
                            V2PresetModelButton(label = label, onClick = { onSelect(prof.copy(id = java.util.UUID.randomUUID().toString())) })
                        }

                        HorizontalDivider(color = Color.DarkGray)

                        Text("OpenRouter:", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        listOf(
                            "DeepSeek V3.2" to V2ModelProfile(providerId = "openrouter", model = "deepseek/deepseek-v3.2", temperature = 0.5, reasoningEnabled = false),
                            "GLM 5.3 Flash" to V2ModelProfile(providerId = "openrouter", model = "z-ai/glm-5.3-flash", temperature = 0.5)
                        ).forEach { (label, prof) ->
                            V2PresetModelButton(label = label, onClick = { onSelect(prof.copy(id = java.util.UUID.randomUUID().toString())) })
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { dialogTab = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                        ) {
                            Text("＋ 一覧にないカスタムモデルを手動入力する", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("プロバイダーを選択:", color = AppColors.textSecondary, fontSize = 10.sp)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("gemini" to "Gemini", "openrouter" to "OpenRouter").forEach { (v, label) ->
                                SelectBox(
                                    label = label,
                                    selected = customProvider == v,
                                    onClick = { customProvider = v },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text("モデル識別名 (API Model ID):", color = AppColors.textSecondary, fontSize = 10.sp)
                        V2InputArea(value = customModelName, onValueChange = { customModelName = it }, singleLine = true)
                        Text(
                            if (customProvider == "gemini") "※例: gemini-3.1-flash-lite, gemma-4-31b-it"
                            else "※例: deepseek/deepseek-v3.2, anthropic/claude-3.5-sonnet",
                            color = AppColors.textTertiary,
                            fontSize = 9.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (customModelName.isNotBlank()) {
                                    onSelect(
                                        V2ModelProfile(
                                            id = System.currentTimeMillis().toString(),
                                            providerId = customProvider,
                                            model = customModelName.trim()
                                        )
                                    )
                                }
                            },
                            enabled = customModelName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().height(38.dp)
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

@Composable
private fun V2PresetModelButton(label: String, onClick: () -> Unit) {
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
fun V2InputArea(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 10,
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
            singleLine = singleLine,
            minLines = minLines,
            maxLines = if (singleLine) 1 else maxLines,
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
 * バッチ翻訳・分割チャンク翻訳の仕組み解説ダイアログ (ASCIIアート図解つき)
 */
@Composable
private fun V2BatchSplitExplanationDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(8.dp),
            color = AppColors.surfaceDark,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
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
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "本アプリのAI翻訳は、AIの出力限界やAPI制限に合わせて【小さければまとめて翻訳（バッチ）】、【大きければ安全に小さく分割（チャンク）】して最速かつ安全に処理します。",
                        color = AppColors.textPrimary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )

                    // 1. 小ファイル (バッチ)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppColors.backgroundDark),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("📦 小ファイルの場合：バッチ翻訳（まとめて送信）", color = Color(0xFF64B5F6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "短いチャプターを1話ずつ送ると、API待機時間や1分あたりのリクエスト制限（RPM 15回等）に引っかかります。そのため、目標出力文字数から自動計算されるバッチ枠に収まる範囲で最大3話を自動で1つに束ねて翻訳します。",
                                color = AppColors.textSecondary,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
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
                                            "📄 第3話 (6KB) ┘    <documents><doc id=\"1\">...   │\n" +
                                            "                                                  ▼\n" +
                                            "✅ 応答を各ファイルに自動分解・個別保存！ ◀───────┘",
                                    color = Color(0xFF90CAF9),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }

                    // 2. 大ファイル (チャンク)
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
