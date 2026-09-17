package com.example.novelscraper.translation.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.translation.v2.domain.ProviderRegistry
import com.example.novelscraper.translation.v2.domain.V2_ENCODING_OPTIONS
import com.example.novelscraper.translation.v2.pipeline.DEFAULT_REFINE_PROMPT
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.V2SizeRatios
import com.example.novelscraper.ui.theme.AppColors

class V2CommonTabState(
    val geminiKeysText: MutableState<String>,
    val geminiRotationEnabled: MutableState<Boolean>,
    val geminiCooldownSecText: MutableState<String>,
    val transientRetryDelayText: MutableState<String>,
    val openRouterKey: MutableState<String>,
    val openRouterEndpoint: MutableState<String>,
    val outputSubDir: MutableState<String>,
    val parallelWorkers: MutableState<String>,
    val requestDelay: MutableState<String>,
    val trialMaxFiles: MutableState<String>,
    val splitEnabled: MutableState<Boolean>,
    val splitSize: MutableState<String>,
    val splitEncoding: MutableState<String>,
    val prevEnabled: MutableState<Boolean>,
    val prevLines: MutableState<String>,
    val refineEnabled: MutableState<Boolean>,
    val refinePrompt: MutableState<String>,
    val refineThinking: MutableState<String?>,
    val refineThinkingBudget: MutableState<String>,
    val refineReasoningEffort: MutableState<String?>,
    val refineReasoningEnabled: MutableState<Boolean?>,
    val sizeRatioZhMinText: MutableState<String>,
    val sizeRatioZhMaxText: MutableState<String>,
    val sizeRatioKoMinText: MutableState<String>,
    val sizeRatioKoMaxText: MutableState<String>,
    val sizeRatioEnMinText: MutableState<String>,
    val sizeRatioEnMaxText: MutableState<String>,
    val sizeRatioJaMinText: MutableState<String>,
    val sizeRatioJaMaxText: MutableState<String>,
    val dictEnabled: MutableState<Boolean>,
    val dictProvider: MutableState<String>,
    val dictModel: MutableState<String>,
    val dictMergeModel: MutableState<String>,
    val dictThinking: MutableState<String?>,
    val dictOpenRouterProviderOrderText: MutableState<String>,
    val dictOpenRouterProviderAllowFallbacks: MutableState<Boolean>,
    val dictWorker: MutableState<String>,
    val dictConcurrency: MutableState<String>,
    val dictTotalParts: MutableState<String>,
    val dictBatchKb: MutableState<String>,
    val dictScanMb: MutableState<String>,
    val dictDelay: MutableState<String>,
    val dictCooldown: MutableState<String>,
    val maxTokens: MutableState<String>,
    val maxCost: MutableState<String>,
    val legacyJson: MutableState<String>
) {
    fun applyTo(base: V2Settings): V2Settings {
        return base.copy(
            geminiKeys = geminiKeysText.value.lines().map { it.trim() }.filter { it.isNotEmpty() },
            geminiRotationEnabled = geminiRotationEnabled.value,
            geminiCooldownSec = geminiCooldownSecText.value.toIntOrNull() ?: base.geminiCooldownSec,
            transientRetryDelaySec = transientRetryDelayText.value.toIntOrNull() ?: base.transientRetryDelaySec,
            openRouterKey = openRouterKey.value.trim(),
            openRouterEndpoint = openRouterEndpoint.value.trim().ifBlank { base.openRouterEndpoint },
            dict = base.dict.copy(
                enabled = dictEnabled.value,
                providerId = dictProvider.value,
                model = dictModel.value.trim(),
                mergeModel = dictMergeModel.value.trim(),
                thinkingLevel = dictThinking.value?.ifBlank { null },
                providerOrder = dictOpenRouterProviderOrderText.value.split(Regex("[,、，\\s]+")).map { it.trim() }.filter { it.isNotBlank() },
                providerAllowFallbacks = dictOpenRouterProviderAllowFallbacks.value,
                workerCount = dictWorker.value.toIntOrNull() ?: base.dict.workerCount,
                concurrencyPerWorker = dictConcurrency.value.toIntOrNull() ?: base.dict.concurrencyPerWorker,
                totalParts = dictTotalParts.value.toIntOrNull() ?: base.dict.totalParts,
                batchMaxBytes = ((dictBatchKb.value.toIntOrNull() ?: (base.dict.batchMaxBytes / 1000)) * 1000),
                maxTotalScanBytes = ((dictScanMb.value.toIntOrNull() ?: (base.dict.maxTotalScanBytes / 1000000)) * 1000000),
                requestDelaySec = dictDelay.value.toIntOrNull() ?: base.dict.requestDelaySec,
                cooldown429Sec = dictCooldown.value.toIntOrNull() ?: base.dict.cooldown429Sec
            ),
            limits = base.limits.copy(
                parallelWorkers = parallelWorkers.value.toIntOrNull() ?: base.limits.parallelWorkers,
                requestDelaySec = requestDelay.value.toIntOrNull() ?: base.limits.requestDelaySec,
                filesPerFolder = trialMaxFiles.value.toIntOrNull() ?: base.limits.filesPerFolder,
                outputSubDir = outputSubDir.value.ifBlank { base.limits.outputSubDir }
            ),
            split = base.split.copy(
                enabled = splitEnabled.value,
                splitSizeChars = splitSize.value.toIntOrNull() ?: base.split.splitSizeChars,
                inputEncoding = splitEncoding.value.ifBlank { "AUTO" }
            ),
            prevContext = base.prevContext.copy(
                enabled = prevEnabled.value,
                lines = prevLines.value.toIntOrNull() ?: base.prevContext.lines
            ),
            refine = base.refine.copy(
                enabled = refineEnabled.value,
                prompt = refinePrompt.value,
                thinkingLevel = refineThinking.value?.ifBlank { null },
                thinkingBudget = refineThinkingBudget.value.ifBlank { null }?.toIntOrNull(),
                reasoningEffort = refineReasoningEffort.value?.ifBlank { null },
                reasoningEnabled = refineReasoningEnabled.value
            ),
            sizeRatios = V2SizeRatios(
                zhMin = sizeRatioZhMinText.value.toIntOrNull() ?: base.sizeRatios.zhMin,
                zhMax = sizeRatioZhMaxText.value.toIntOrNull() ?: base.sizeRatios.zhMax,
                koMin = sizeRatioKoMinText.value.toIntOrNull() ?: base.sizeRatios.koMin,
                koMax = sizeRatioKoMaxText.value.toIntOrNull() ?: base.sizeRatios.koMax,
                enMin = sizeRatioEnMinText.value.toIntOrNull() ?: base.sizeRatios.enMin,
                enMax = sizeRatioEnMaxText.value.toIntOrNull() ?: base.sizeRatios.enMax,
                jaMin = sizeRatioJaMinText.value.toIntOrNull() ?: base.sizeRatios.jaMin,
                jaMax = sizeRatioJaMaxText.value.toIntOrNull() ?: base.sizeRatios.jaMax
            ),
            cost = base.cost.copy(
                maxTokens = maxTokens.value.ifBlank { null }?.toLongOrNull(),
                maxCost = maxCost.value.ifBlank { null }?.toDoubleOrNull()
            )
        )
    }

    companion object {
        fun from(initial: V2Settings): V2CommonTabState {
            return V2CommonTabState(
                geminiKeysText = mutableStateOf(initial.geminiKeys.joinToString("\n")),
                geminiRotationEnabled = mutableStateOf(initial.geminiRotationEnabled),
                geminiCooldownSecText = mutableStateOf(initial.geminiCooldownSec.toString()),
                transientRetryDelayText = mutableStateOf(initial.transientRetryDelaySec.toString()),
                openRouterKey = mutableStateOf(initial.openRouterKey),
                openRouterEndpoint = mutableStateOf(initial.openRouterEndpoint),
                outputSubDir = mutableStateOf(initial.limits.outputSubDir),
                parallelWorkers = mutableStateOf(initial.limits.parallelWorkers.toString()),
                requestDelay = mutableStateOf(initial.limits.requestDelaySec.toString()),
                trialMaxFiles = mutableStateOf(initial.limits.filesPerFolder.toString()),
                splitEnabled = mutableStateOf(initial.split.enabled),
                splitSize = mutableStateOf(initial.split.splitSizeChars.toString()),
                splitEncoding = mutableStateOf(initial.split.inputEncoding),
                prevEnabled = mutableStateOf(initial.prevContext.enabled),
                prevLines = mutableStateOf(initial.prevContext.lines.toString()),
                refineEnabled = mutableStateOf(initial.refine.enabled),
                refinePrompt = mutableStateOf(initial.refine.prompt),
                refineThinking = mutableStateOf(initial.refine.thinkingLevel),
                refineThinkingBudget = mutableStateOf(initial.refine.thinkingBudget?.toString() ?: ""),
                refineReasoningEffort = mutableStateOf(initial.refine.reasoningEffort),
                refineReasoningEnabled = mutableStateOf(initial.refine.reasoningEnabled),
                sizeRatioZhMinText = mutableStateOf(initial.sizeRatios.zhMin.toString()),
                sizeRatioZhMaxText = mutableStateOf(initial.sizeRatios.zhMax.toString()),
                sizeRatioKoMinText = mutableStateOf(initial.sizeRatios.koMin.toString()),
                sizeRatioKoMaxText = mutableStateOf(initial.sizeRatios.koMax.toString()),
                sizeRatioEnMinText = mutableStateOf(initial.sizeRatios.enMin.toString()),
                sizeRatioEnMaxText = mutableStateOf(initial.sizeRatios.enMax.toString()),
                sizeRatioJaMinText = mutableStateOf(initial.sizeRatios.jaMin.toString()),
                sizeRatioJaMaxText = mutableStateOf(initial.sizeRatios.jaMax.toString()),
                dictEnabled = mutableStateOf(initial.dict.enabled),
                dictProvider = mutableStateOf(initial.dict.providerId),
                dictModel = mutableStateOf(initial.dict.model),
                dictMergeModel = mutableStateOf(initial.dict.mergeModel),
                dictThinking = mutableStateOf(initial.dict.thinkingLevel),
                dictOpenRouterProviderOrderText = mutableStateOf(initial.dict.providerOrder.joinToString(", ")),
                dictOpenRouterProviderAllowFallbacks = mutableStateOf(initial.dict.providerAllowFallbacks == true),
                dictWorker = mutableStateOf(initial.dict.workerCount.toString()),
                dictConcurrency = mutableStateOf(initial.dict.concurrencyPerWorker.toString()),
                dictTotalParts = mutableStateOf(initial.dict.totalParts.toString()),
                dictBatchKb = mutableStateOf((initial.dict.batchMaxBytes / 1000).toString()),
                dictScanMb = mutableStateOf((initial.dict.maxTotalScanBytes / 1000000).toString()),
                dictDelay = mutableStateOf(initial.dict.requestDelaySec.toString()),
                dictCooldown = mutableStateOf(initial.dict.cooldown429Sec.toString()),
                maxTokens = mutableStateOf(initial.cost.maxTokens?.toString() ?: ""),
                maxCost = mutableStateOf(initial.cost.maxCost?.toString() ?: ""),
                legacyJson = mutableStateOf("")
            )
        }
    }
}

@Composable
internal fun V2CommonTab(
    state: V2CommonTabState,
    onSelectDictModel: () -> Unit,
    onSelectDictMergeModel: () -> Unit,
    onImportLegacy: ((String) -> Unit)?,
    importWarnings: List<String>
) {
    var splitEncodingMenu by remember { mutableStateOf(false) }

    // ==========================================
    // 1. 🔑 各社 APIキー設定
    // ==========================================
    Text("🔑 各社 APIキー設定", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Google AI Studio (Gemini) APIキープール (1行1キー / 複数可):", color = AppColors.textSecondary, fontSize = 10.sp)
        Text(
            text = "クリア",
            color = AppColors.accentTealLight,
            fontSize = 10.sp,
            modifier = Modifier.clickable {
                // 技術的根拠1行：同梱既定キー廃止(BYOK化)のため、リセット復元ではなく空消去にする。
                // 動作例：押すと鍵欄が空になり、自分の鍵を貼り直して開始する。
                state.geminiKeysText.value = ""
            }
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
    V2InputArea(
        value = state.geminiKeysText.value,
        onValueChange = { state.geminiKeysText.value = it },
        minLines = 2,
        maxLines = 4
    )

    Spacer(modifier = Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.geminiRotationEnabled.value,
            onCheckedChange = { state.geminiRotationEnabled.value = it }
        )
        Text("429検知時の即時キー/モデルローテーション有効", color = AppColors.textPrimary, fontSize = 10.sp)
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("OpenRouter APIキー:", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.openRouterKey.value, onValueChange = { state.openRouterKey.value = it }, singleLine = true)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("OpenRouter エンドポイント (空＝標準URL):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.openRouterEndpoint.value, onValueChange = { state.openRouterEndpoint.value = it }, singleLine = true)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = Color.DarkGray)
    Spacer(modifier = Modifier.height(8.dp))

    // ==========================================
    // 2. 📄 本文翻訳・実行設定
    // ==========================================
    Text("📄 本文翻訳・実行設定", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("出力フォルダ名:", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.outputSubDir.value, onValueChange = { state.outputSubDir.value = it }, singleLine = true)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("本文翻訳 並列ワーカー数 (1〜6):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.parallelWorkers.value, onValueChange = { state.parallelWorkers.value = it }, singleLine = true)
            Spacer(modifier = Modifier.height(2.dp))
            Text("※同時並行ファイル数 (1〜6)", color = AppColors.textTertiary, fontSize = 8.sp)
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("要求間隔 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.requestDelay.value, onValueChange = { state.requestDelay.value = it }, singleLine = true)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("429待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.geminiCooldownSecText.value, onValueChange = { state.geminiCooldownSecText.value = it }, singleLine = true)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("一時エラー待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.transientRetryDelayText.value, onValueChange = { state.transientRetryDelayText.value = it }, singleLine = true)
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("試し読み上限 (件/小説, 0=無制限):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.trialMaxFiles.value, onValueChange = { state.trialMaxFiles.value = it }, singleLine = true)
            Spacer(modifier = Modifier.height(2.dp))
            Text("※例: 40 → 先頭40件で打ち切り→次の小説へ。続ける時は上限UP/0にして再実行 (続きから再開)", color = AppColors.textTertiary, fontSize = 8.sp)
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.splitEnabled.value, onCheckedChange = { state.splitEnabled.value = it })
        Text("巨大小説の物理分割 (part_*.txt化)", color = AppColors.textPrimary, fontSize = 10.sp)
    }
    if (state.splitEnabled.value) {
        Spacer(modifier = Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("物理分割文字数 (文字):", color = AppColors.textSecondary, fontSize = 9.sp)
                Spacer(modifier = Modifier.height(2.dp))
                V2InputArea(value = state.splitSize.value, onValueChange = { state.splitSize.value = it }, singleLine = true)
                Spacer(modifier = Modifier.height(2.dp))
                Text("※例: 7000 (約2〜3話相当)", color = AppColors.textTertiary, fontSize = 8.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("入力文字コード (自動判定推奨):", color = AppColors.textSecondary, fontSize = 9.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                        .clickable { splitEncodingMenu = true }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = V2_ENCODING_OPTIONS.firstOrNull { it.first == state.splitEncoding.value }?.second ?: state.splitEncoding.value,
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
                                        text = "${if (option.first == state.splitEncoding.value) "✓ " else ""}${option.second}",
                                        color = if (option.first == state.splitEncoding.value) AppColors.accentTealLight else AppColors.textPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (option.first == state.splitEncoding.value) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    state.splitEncoding.value = option.first
                                    splitEncodingMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(2.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Checkbox(checked = state.prevEnabled.value, onCheckedChange = { state.prevEnabled.value = it })
        Text("直前ファイル原文末尾の文脈注入", color = AppColors.textPrimary, fontSize = 10.sp)
        if (state.prevEnabled.value) {
            Spacer(modifier = Modifier.width(4.dp))
            Text("注入行数:", color = AppColors.textSecondary, fontSize = 9.sp)
            Box(modifier = Modifier.width(60.dp)) {
                V2InputArea(value = state.prevLines.value, onValueChange = { state.prevLines.value = it }, singleLine = true)
            }
            Text("行", color = AppColors.textSecondary, fontSize = 9.sp)
        }
    }

    Spacer(modifier = Modifier.height(2.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Checkbox(checked = state.refineEnabled.value, onCheckedChange = { state.refineEnabled.value = it })
        Text("最終推敲（訳文の磨き直し・1回のみ）", color = AppColors.textPrimary, fontSize = 10.sp)
    }
    if (state.refineEnabled.value) {
        Spacer(modifier = Modifier.height(2.dp))
        Text("※ 空欄で既定文を使用。不合格時は初回訳文を採用します", color = AppColors.textTertiary, fontSize = 9.sp)
        Spacer(modifier = Modifier.height(4.dp))
        V2InputArea(
            value = state.refinePrompt.value,
            onValueChange = { state.refinePrompt.value = it },
            minLines = 6,
            maxLines = 10
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("▼ 既定文（参考・このままでは送信されません）", color = AppColors.textTertiary, fontSize = 9.sp)
        Text(DEFAULT_REFINE_PROMPT, color = AppColors.textSecondary, fontSize = 9.sp)
        Spacer(modifier = Modifier.height(4.dp))
        // 技術的根拠1行：推敲は複数プロファイルを束ねるため能力表で絞らず、送信直前の解決則に可否を一任する（表示＝候補、反映＝解決則）。
        Text("推敲の思考レベル（空・継承＝翻訳と同じ）:", color = AppColors.textSecondary, fontSize = 9.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectBox(label = "継承", selected = state.refineThinking.value == null, onClick = { state.refineThinking.value = null }, modifier = Modifier.weight(1f))
            listOf("high", "medium", "low", "minimal").forEach { lvl ->
                SelectBox(label = lvl, selected = state.refineThinking.value == lvl, onClick = { state.refineThinking.value = lvl }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("推敲の思考予算（2.5系のみ・空＝継承）:", color = AppColors.textSecondary, fontSize = 9.sp)
        Spacer(modifier = Modifier.height(2.dp))
        V2InputArea(value = state.refineThinkingBudget.value, onValueChange = { state.refineThinkingBudget.value = it }, singleLine = true)
        Spacer(modifier = Modifier.height(4.dp))
        Text("推敲のReasoning Effort（OpenRouter・「継承」＝翻訳と同じ）:", color = AppColors.textSecondary, fontSize = 9.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectBox(label = "継承", selected = state.refineReasoningEffort.value == null, onClick = { state.refineReasoningEffort.value = null }, modifier = Modifier.weight(1f))
            listOf("low", "medium", "high").forEach { lvl ->
                SelectBox(label = lvl, selected = state.refineReasoningEffort.value == lvl, onClick = { state.refineReasoningEffort.value = lvl }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("none" to "none", "minimal" to "minimal", "xhigh" to "xhigh", "max" to "max").forEach { (v, label) ->
                SelectBox(label = label, selected = state.refineReasoningEffort.value == v, onClick = { state.refineReasoningEffort.value = v }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("推敲のReasoning Enabled（OpenRouter・未指定＝継承）:", color = AppColors.textSecondary, fontSize = 9.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectBox(label = "継承", selected = state.refineReasoningEnabled.value == null, onClick = { state.refineReasoningEnabled.value = null }, modifier = Modifier.weight(1f))
            SelectBox(label = "OFF", selected = state.refineReasoningEnabled.value == false, onClick = { state.refineReasoningEnabled.value = false }, modifier = Modifier.weight(1f))
            SelectBox(label = "ON", selected = state.refineReasoningEnabled.value == true, onClick = { state.refineReasoningEnabled.value = true }, modifier = Modifier.weight(1f))
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = Color.DarkGray)
    Spacer(modifier = Modifier.height(8.dp))

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
    Spacer(modifier = Modifier.height(6.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("中国語 (ZH) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { V2InputArea(value = state.sizeRatioZhMinText.value, onValueChange = { state.sizeRatioZhMinText.value = it }, singleLine = true) }
                Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Box(modifier = Modifier.weight(1f)) { V2InputArea(value = state.sizeRatioZhMaxText.value, onValueChange = { state.sizeRatioZhMaxText.value = it }, singleLine = true) }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("韓国語 (KO) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { V2InputArea(value = state.sizeRatioKoMinText.value, onValueChange = { state.sizeRatioKoMinText.value = it }, singleLine = true) }
                Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Box(modifier = Modifier.weight(1f)) { V2InputArea(value = state.sizeRatioKoMaxText.value, onValueChange = { state.sizeRatioKoMaxText.value = it }, singleLine = true) }
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("英語 (EN) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { V2InputArea(value = state.sizeRatioEnMinText.value, onValueChange = { state.sizeRatioEnMinText.value = it }, singleLine = true) }
                Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Box(modifier = Modifier.weight(1f)) { V2InputArea(value = state.sizeRatioEnMaxText.value, onValueChange = { state.sizeRatioEnMaxText.value = it }, singleLine = true) }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("日本語 (JA) 最小〜最大 %:", color = AppColors.textSecondary, fontSize = 9.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { V2InputArea(value = state.sizeRatioJaMinText.value, onValueChange = { state.sizeRatioJaMinText.value = it }, singleLine = true) }
                Text("〜", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Box(modifier = Modifier.weight(1f)) { V2InputArea(value = state.sizeRatioJaMaxText.value, onValueChange = { state.sizeRatioJaMaxText.value = it }, singleLine = true) }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = Color.DarkGray)
    Spacer(modifier = Modifier.height(8.dp))

    // ==========================================
    // 4. 📖 人名辞書自動生成 (dictionary.json)
    // ==========================================
    Text("📖 人名辞書自動生成 (dictionary.json)", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(2.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.dictEnabled.value, onCheckedChange = { state.dictEnabled.value = it })
        Text("辞書自動生成を有効にする", color = AppColors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }

    if (state.dictEnabled.value) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("gemini" to "Gemini", "openrouter" to "OpenRouter").forEach { (v, label) ->
                SelectBox(
                    label = label,
                    selected = state.dictProvider.value == v,
                    onClick = { state.dictProvider.value = v },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("辞書抽出用モデル:", color = AppColors.textSecondary, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                V2InputArea(value = state.dictModel.value, onValueChange = { state.dictModel.value = it }, singleLine = true)
            }
            Button(
                onClick = onSelectDictModel,
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
            Box(modifier = Modifier.weight(1f)) {
                V2InputArea(value = state.dictMergeModel.value, onValueChange = { state.dictMergeModel.value = it }, singleLine = true)
            }
            Button(
                onClick = onSelectDictMergeModel,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("モデル選択", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 技術的根拠1行：辞書の表示用能力を登録簿に一本化し、空欄既定・未知フォールバックは従来通り（gemini完全一致のみflash既定）。
        val dictDefault = if (state.dictProvider.value == "gemini") "gemini-3.5-flash" else state.dictModel.value
        val dictCaps = ProviderRegistry.capabilitiesForOrOpenRouter(
            state.dictProvider.value,
            state.dictModel.value.ifBlank { dictDefault }
        )
        ThinkingLevelEditor(
            selected = state.dictThinking.value,
            supported = dictCaps.thinking,
            onSelect = { state.dictThinking.value = it }
        )

        if (state.dictProvider.value == "openrouter") {
            Spacer(modifier = Modifier.height(4.dp))
            Text("OpenRouter プロバイダー指定 (カンマ区切り, 例: upstage, baidu/fp8):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.dictOpenRouterProviderOrderText.value, onValueChange = { state.dictOpenRouterProviderOrderText.value = it }, singleLine = true)
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.dictOpenRouterProviderAllowFallbacks.value,
                    onCheckedChange = { state.dictOpenRouterProviderAllowFallbacks.value = it }
                )
                Text("指定プロバイダー障害時に他社へフォールバック許可", color = AppColors.textPrimary, fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("対象ファイル数 (0=全件):", color = AppColors.textSecondary, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                V2InputArea(value = state.dictTotalParts.value, onValueChange = { state.dictTotalParts.value = it }, singleLine = true)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("1回の送信上限 (KB / 上限200):", color = AppColors.textSecondary, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                V2InputArea(value = state.dictBatchKb.value, onValueChange = { state.dictBatchKb.value = it }, singleLine = true)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("辞書抽出 ワーカー数 (1〜30):", color = AppColors.textSecondary, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                V2InputArea(value = state.dictWorker.value, onValueChange = { state.dictWorker.value = it }, singleLine = true)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("1ワーカーあたり並列数 (1〜10):", color = AppColors.textSecondary, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                V2InputArea(value = state.dictConcurrency.value, onValueChange = { state.dictConcurrency.value = it }, singleLine = true)
            }
        }
        val totalReq = ((state.dictWorker.value.toIntOrNull() ?: 6) * (state.dictConcurrency.value.toIntOrNull() ?: 5)).coerceIn(1, 30)
        Spacer(modifier = Modifier.height(2.dp))
        Text("➔ 同時APIリクエスト合計: ${totalReq} 並列 (ワーカー数 × 並列数 / 最大30)", color = AppColors.accentTealLight, fontSize = 9.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("リクエスト待機 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                V2InputArea(value = state.dictDelay.value, onValueChange = { state.dictDelay.value = it }, singleLine = true)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("429待機時間 (秒):", color = AppColors.textSecondary, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                V2InputArea(value = state.dictCooldown.value, onValueChange = { state.dictCooldown.value = it }, singleLine = true)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = Color.DarkGray)
    Spacer(modifier = Modifier.height(8.dp))

    // ==========================================
    // 5. 💰 コスト上限 & 旧設定取込 (v2固有)
    // ==========================================
    Text("💰 コスト上限 & 旧設定取込", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(2.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text("最大トークン (空＝無制限):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.maxTokens.value, onValueChange = { state.maxTokens.value = it }, singleLine = true)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("最大コスト (USD想定 / 空＝無制限):", color = AppColors.textSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            V2InputArea(value = state.maxCost.value, onValueChange = { state.maxCost.value = it }, singleLine = true)
        }
    }

    if (onImportLegacy != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Text("旧設定JSON取込（検証・合格分のみ継承）:", color = AppColors.textSecondary, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(2.dp))
        V2InputArea(value = state.legacyJson.value, onValueChange = { state.legacyJson.value = it }, minLines = 2, maxLines = 4)
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { onImportLegacy(state.legacyJson.value) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.height(36.dp)
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
