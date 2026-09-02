package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.ScraperConfig
import com.example.novelscraper.ui.theme.AppColors

private val ButtonCornerShape = RoundedCornerShape(6.dp)
private val CardCornerShape = RoundedCornerShape(8.dp)
private val InputCornerShape = RoundedCornerShape(4.dp)
private val InputBorderColor = Color(0xFF444444)
private val InputTextStyle = TextStyle(color = AppColors.textPrimary, fontSize = 13.sp)
private val InputLabelTextStyle = TextStyle(color = AppColors.textSecondary, fontSize = 11.sp)

// Allocation Zero: 静的 Modifier キャッシュ（オブジェクト生成コストを完全排除）
private val InputFieldColumnModifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
private val InputLabelModifier = Modifier.padding(bottom = 2.dp)
private val InputBoxModifier = Modifier
    .fillMaxWidth()
    .height(38.dp)
    .background(AppColors.surfaceMedium, InputCornerShape)
    .border(1.dp, InputBorderColor, InputCornerShape)
    .padding(horizontal = 10.dp)
private val CardContainerModifier = Modifier
    .fillMaxWidth()
    .background(AppColors.backgroundMedium, CardCornerShape)
    .padding(12.dp)
private val CardTitleModifier = Modifier.padding(bottom = 8.dp)

@Composable
fun SettingsPanel(
    currentConfig: ScraperConfig,
    currentPresetName: String,
    isWebViewDarkMode: Boolean,
    presets: Map<String, ScraperConfig>,
    onCloseClick: () -> Unit,
    onPresetSelected: (String, ScraperConfig) -> Unit,
    onSavePresetClick: () -> Unit,
    onDeletePresetClick: () -> Unit,
    onConfigChange: (ScraperConfig) -> Unit,
    onImportPresetsClick: () -> Unit,
    onExportPresetsClick: () -> Unit,
    onToggleWebViewDarkModeClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // 外部からの明示的更新（プリセット選択等）のみ同期し、編集中はローカルで高速に保持
    var localConfig by remember { mutableStateOf(currentConfig) }

    LaunchedEffect(currentConfig) {
        if (localConfig != currentConfig) {
            localConfig = currentConfig
        }
    }

    fun updateField(updater: (ScraperConfig) -> ScraperConfig) {
        val updated = updater(localConfig)
        localConfig = updated
        onConfigChange(updated)
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }

    // 1回のレイアウトパスでスムーズにスクロール可能な Column + verticalScroll
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.backgroundDarkest)
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ヘッダー行
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "スクレイパー設定",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { showHelpDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                shape = ButtonCornerShape,
                modifier = Modifier.height(34.dp).padding(end = 6.dp)
            ) {
                Text("説明書", fontSize = 12.sp)
            }
            Button(
                onClick = onCloseClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                shape = ButtonCornerShape,
                modifier = Modifier.height(34.dp)
            ) {
                Text("閉じる", fontSize = 12.sp, color = AppColors.textPrimary)
            }
        }

        // プリセット選択ドロップダウン
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.surfaceMedium, ButtonCornerShape)
                    .border(1.dp, Color.DarkGray, ButtonCornerShape)
                    .clickable { dropdownExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentPresetName.ifEmpty { "プリセット選択..." },
                    color = if (currentPresetName.isNotEmpty()) AppColors.textPrimary else AppColors.textTertiary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = AppColors.textPrimary)
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier.background(AppColors.surfaceLight)
            ) {
                val sortedPresetNames = remember(presets) { presets.keys.sorted() }
                sortedPresetNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, color = AppColors.textPrimary) },
                        onClick = {
                            presets[name]?.let { selectedConfig ->
                                localConfig = selectedConfig
                                onPresetSelected(name, selectedConfig)
                            }
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        // プリセット操作ボタン行
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onSavePresetClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.neutralButton),
                    shape = ButtonCornerShape,
                    modifier = Modifier.weight(1f).padding(end = 4.dp).height(36.dp)
                ) { Text("保存", fontSize = 12.sp) }
                Button(
                    onClick = onDeletePresetClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.error),
                    shape = ButtonCornerShape,
                    modifier = Modifier.weight(1f).height(36.dp)
                ) { Text("削除", fontSize = 12.sp) }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onImportPresetsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    shape = ButtonCornerShape,
                    modifier = Modifier.weight(1f).padding(end = 4.dp).height(36.dp)
                ) { Text("インポート", fontSize = 12.sp) }
                Button(
                    onClick = onExportPresetsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    shape = ButtonCornerShape,
                    modifier = Modifier.weight(1f).height(36.dp)
                ) { Text("エクスポート", fontSize = 12.sp) }
            }
        }

        // セクション1: 作品・章の識別設定
        SettingsCard(title = "作品・章の識別設定") {
            ConfigInputField("作品名 Selector", localConfig.folder) { updateField { c -> c.copy(folder = it) } }
            ConfigInputField("作品名 Regex", localConfig.regex) { updateField { c -> c.copy(regex = it) } }
            ConfigInputField("別URL取得 Selector", localConfig.folderLink) { updateField { c -> c.copy(folderLink = it) } }
            ConfigInputField("タイトル Selector", localConfig.title) { updateField { c -> c.copy(title = it) } }
            ConfigInputField("タイトル Regex", localConfig.fileRegex) { updateField { c -> c.copy(fileRegex = it) } }
            ConfigInputField("チャプター番号 Selector", localConfig.chapter) { updateField { c -> c.copy(chapter = it) } }
            ConfigInputField("チャプター番号 Regex", localConfig.chapterRegex) { updateField { c -> c.copy(chapterRegex = it) } }
        }

        // セクション2: 本文・ページ巡回設定
        SettingsCard(title = "本文・ページ巡回設定") {
            ConfigInputField("本文 Selector", localConfig.body) { updateField { c -> c.copy(body = it) } }
            ConfigInputField("除外要素 (複数: , 区切り)", localConfig.exclude) { updateField { c -> c.copy(exclude = it) } }
            ConfigInputField("次ページ Selector", localConfig.next) { updateField { c -> c.copy(next = it) } }
            ConfigInputField("終了検知 Regex", localConfig.endCheck) { updateField { c -> c.copy(endCheck = it) } }
        }

        // セクション3: 表示・動作設定
        SettingsCard(title = "表示・動作設定") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WebView ダークモード",
                        color = AppColors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "ウェブページを自動でダークテーマ表示",
                        color = AppColors.textSecondary,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = isWebViewDarkMode,
                    onCheckedChange = { onToggleWebViewDarkModeClick() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AppColors.accentTealLight,
                        checkedTrackColor = AppColors.accentTeal,
                        uncheckedThumbColor = AppColors.surfaceLight,
                        uncheckedTrackColor = AppColors.surfaceMedium
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            ConfigInputField("待機時間(秒)", localConfig.delay) { updateField { c -> c.copy(delay = it) } }
            ConfigInputField("自動適用URL (ドメイン)", localConfig.autoUrl) { updateField { c -> c.copy(autoUrl = it) } }
        }

        // 下部余白（スクロール時の余裕）
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = CardContainerModifier) {
        Text(
            text = title,
            color = AppColors.accentTeal,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = CardTitleModifier
        )
        content()
    }
}

/**
 * 高速かつ軽量なテキスト入力欄。
 */
@Composable
private fun ConfigInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }

    Column(modifier = InputFieldColumnModifier) {
        Text(
            text = label,
            color = AppColors.textSecondary,
            style = InputLabelTextStyle,
            modifier = InputLabelModifier
        )
        Box(
            modifier = InputBoxModifier,
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    onValueChange(newText)
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = InputTextStyle,
                cursorBrush = SolidColor(AppColors.accentTealLight),
                singleLine = true
            )
        }
    }
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アプリの使いかた・完全ガイド", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("■ 基本の指定")
                HelpText("セレクタには ID(#name) や クラス(.name) を書きます。虫眼鏡ダイアログからコピーしたものを貼り付けてください。")

                SectionTitle("■ 作品名（フォルダ名）")
                HelpText("""・空欄：サイトから自動で取得します。
・@名前：「@夏目漱石」のように書くと、その名前のフォルダを作ります。""")

                SectionTitle("■ チャプター番号（4桁0埋め）")
                HelpText("""・空欄：サイトの文字やURLから数字を探します。
・@番号：「@1」と書くと、0001から順に自動で番号を増やしながら保存します。サイトに番号がない時や、途中から始めたい時に便利です。""")

                SectionTitle("■ 次ページ（js: 指定）")
                HelpText("""・ボタンがないサイト用。js: に紐げてJavaScriptを書くと、裏側のデータからURLを作れます。
例: js:window.book.nextUrl""")

                SectionTitle("■ 自動適用URL")
                HelpText("ドメイン（syosetu.com など）を書いて保存すると、次回からそのサイトを開くだけでこの設定が自動で選ばれます。")

                SectionTitle("■ 待機時間")
                HelpText("「3」なら3秒、「2-5」なら2〜5秒の間でランダムに待機します。")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        containerColor = AppColors.backgroundMedium,
        titleContentColor = AppColors.textPrimary,
        textContentColor = AppColors.textPrimary
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = AppColors.accentTeal, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun HelpText(text: String) {
    Text(text, fontSize = 13.sp, color = AppColors.textSecondary, lineHeight = 18.sp)
}