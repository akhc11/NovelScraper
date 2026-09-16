package com.example.novelscraper.translation.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.pipeline.DictPrompts
import com.example.novelscraper.translation.v2.pipeline.getV2PromptByNumber
import com.example.novelscraper.ui.theme.AppColors

private val DICT_PROMPT_KEYS = listOf("batch", "merge", "translate")
private val DICT_PROMPT_TITLES = mapOf(
    "batch" to "辞書・抽出：本文→人名列挙",
    "merge" to "辞書・名寄せ：断片→統合",
    "translate" to "辞書・翻訳：名列→日中辞書"
)

@Composable
internal fun V2PromptsTab(
    customPromptsMap: MutableMap<Int, String>,
    dictPromptsMap: MutableMap<String, String>
) {
    var editingPromptNumber by remember { mutableIntStateOf(1) }

    Text("プロンプト 1〜7 個別カスタマイズ", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(2.dp))
    Text("※ 空欄または「初期値に戻す」で既定プロンプトが使用されます", color = AppColors.textTertiary, fontSize = 9.sp)
    Spacer(modifier = Modifier.height(6.dp))

    // プロンプト番号選択
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        (1..7).forEach { num ->
            val isSel = (editingPromptNumber == num)
            val isCustomized = customPromptsMap.containsKey(num)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSel) AppColors.accentTeal else if (isCustomized) AppColors.accentTealDark else AppColors.surfaceMedium,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { editingPromptNumber = num }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$num${if (isCustomized) "*" else ""}",
                    color = if (isSel) Color.White else AppColors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                )
            }
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

    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = promptTitles[editingPromptNumber] ?: "$editingPromptNumber",
            color = AppColors.accentTealLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        TextButton(
            onClick = {
                customPromptsMap.remove(editingPromptNumber)
            },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("初期値に戻す", color = Color(0xFFFF8888), fontSize = 10.sp)
        }
    }

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

    if (customPromptsMap.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    customPromptsMap.clear()
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("全プロンプトを初期化", color = Color(0xFFFF6666), fontSize = 10.sp)
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    DictPromptsSection(dictPromptsMap = dictPromptsMap)
}

@Composable
private fun DictPromptsSection(
    dictPromptsMap: MutableMap<String, String>
) {
    var editingKey by remember { mutableStateOf("batch") }

    Text("辞書用プロンプト個別カスタマイズ", color = AppColors.accentTealLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        "※ 空欄で既定文を使用。JSON形式の行を消すと辞書生成に失敗します。変更後は辞書を自動再生成します",
        color = AppColors.textTertiary, fontSize = 9.sp
    )
    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DICT_PROMPT_KEYS.forEach { key ->
            val isSel = (editingKey == key)
            val isCustomized = !dictPromptsMap[key].isNullOrBlank()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSel) AppColors.accentTeal else if (isCustomized) AppColors.accentTealDark else AppColors.surfaceMedium,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { editingKey = key }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = key + if (isCustomized) "*" else "",
                    color = if (isSel) Color.White else AppColors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = DICT_PROMPT_TITLES[editingKey] ?: editingKey,
            color = AppColors.accentTealLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        TextButton(
            onClick = {
                dictPromptsMap.remove(editingKey)
            },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("初期値に戻す", color = Color(0xFFFF8888), fontSize = 10.sp)
        }
    }

    val defaults = remember { DictPrompts() }
    val currentText = dictPromptsMap[editingKey] ?: when (editingKey) {
        "merge" -> defaults.merge
        "translate" -> defaults.translate
        else -> defaults.batch
    }
    if (currentText.length > TranslationLimits.MAX_DICT_PROMPT_CHARS) {
        Text(
            "※ 上限${TranslationLimits.MAX_DICT_PROMPT_CHARS}字を超えた分は切り落として送信します",
            color = Color(0xFFFFB74D), fontSize = 10.sp
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
    V2InputArea(
        value = currentText,
        onValueChange = { newVal ->
            if (newVal.isBlank()) dictPromptsMap.remove(editingKey)
            else dictPromptsMap[editingKey] = newVal
        },
        minLines = 8,
        maxLines = 14
    )
}