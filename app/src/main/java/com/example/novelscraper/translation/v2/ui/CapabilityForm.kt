package com.example.novelscraper.translation.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.translation.v2.domain.ModelCapabilities
import com.example.novelscraper.translation.v2.domain.SamplingParam
import com.example.novelscraper.translation.v2.domain.ThinkingSupport
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.ui.theme.AppColors

/**
 * 能力駆動の動的フォーム。有効な項目のみ出す（表示＝変更が反映される）。
 * 未指定（null）は送信しない（ただしmaxOutputTokensは能力最大値で送信する）。範囲外は能力範囲に丸めて警告する。
 */
@Composable
fun ThinkingLevelEditor(
    selected: String?,
    supported: ThinkingSupport,
    onSelect: (String?) -> Unit
) {
    val levels = (supported as? ThinkingSupport.Levels)?.supported ?: return
    Text("Thinking Level (推論レベル):", color = AppColors.textSecondary, fontSize = 10.sp)
    Spacer(modifier = Modifier.height(2.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectBox(label = "未指定", selected = selected == null, onClick = { onSelect(null) }, modifier = Modifier.weight(1f))
        levels.sorted().forEach { lvl ->
            SelectBox(label = lvl, selected = selected == lvl, onClick = { onSelect(lvl) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun NullableDoubleEditor(
    label: String,
    value: Double?,
    range: SamplingParam?,
    onChange: (Double?) -> Unit
) {
    val display = value?.toString() ?: ""
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = AppColors.textSecondary, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    value = display,
                    onValueChange = { str ->
                        if (str.isBlank()) {
                            onChange(null)
                            return@BasicTextField
                        }
                        val parsed = str.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@BasicTextField
                        val fixed = if (range != null) parsed.coerceIn(range.min, range.max) else parsed
                        onChange(fixed)
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = AppColors.textPrimary, fontSize = 12.sp),
                    cursorBrush = SolidColor(AppColors.accentTealLight),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (range != null) {
            Text(
                "範囲 ${range.min}〜${range.max}（空＝未指定・未送信）",
                color = AppColors.textTertiary,
                fontSize = 8.sp
            )
        }
    }
}

@Composable
fun SelectBox(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(if (selected) AppColors.accentTeal else AppColors.backgroundDark, RoundedCornerShape(3.dp))
            .border(0.5.dp, if (selected) AppColors.accentTealLight else Color.DarkGray, RoundedCornerShape(3.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else AppColors.textSecondary,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * モデルカード用能力フォーム。記述子から有効項目だけを組み立てる。
 * reasoning系はOpenRouterプロバイダー単位で出す（社仕様のため）。
 */
@Composable
fun ProfileCapabilityEditors(
    profile: V2ModelProfile,
    capabilities: ModelCapabilities?,
    onUpdate: (V2ModelProfile) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ThinkingLevelEditor(
            selected = profile.thinkingLevel,
            supported = capabilities?.thinking ?: ThinkingSupport.None,
            onSelect = { onUpdate(profile.copy(thinkingLevel = it)) }
        )

        val sampling = capabilities?.sampling ?: emptyMap()
        if (sampling.containsKey("temperature")) {
            NullableDoubleEditor(
                label = "Temperature（空＝未指定・未送信）:",
                value = profile.temperature,
                range = sampling["temperature"],
                onChange = { onUpdate(profile.copy(temperature = it)) }
            )
        }
        if (sampling.containsKey("topP")) {
            NullableDoubleEditor(
                label = "Top-P:",
                value = profile.topP,
                range = sampling["topP"],
                onChange = { onUpdate(profile.copy(topP = it)) }
            )
        }

        if (profile.providerId == "openrouter") {
            Text("Reasoning Effort (思考モード):", color = AppColors.textSecondary, fontSize = 10.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(null to "未指定", "none" to "none", "low" to "low", "medium" to "medium", "high" to "high").forEach { (v, label) ->
                    SelectBox(
                        label = label,
                        selected = profile.reasoningEffort == v,
                        onClick = { onUpdate(profile.copy(reasoningEffort = v)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text("Reasoning Enabled (思考真偽値):", color = AppColors.textSecondary, fontSize = 10.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("未指定" to null, "OFF (false)" to false, "ON (true)" to true).forEach { (label, boolVal) ->
                    SelectBox(
                        label = label,
                        selected = profile.reasoningEnabled == boolVal,
                        onClick = { onUpdate(profile.copy(reasoningEnabled = boolVal)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
