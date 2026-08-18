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
import com.example.novelscraper.MainUiState
import com.example.novelscraper.ScraperConfig
import com.example.novelscraper.ui.theme.AppColors

/**
 * 12個の入力欄の個別の状態（Granular State）を保持するホルダークラス。
 * 1つのフィールドに入力しても、他の11個のTextFieldは一切リコンポジションされない。
 */
@Stable
class SettingsFormState(initialConfig: ScraperConfig) {
    var folder by mutableStateOf(initialConfig.folder)
    var regex by mutableStateOf(initialConfig.regex)
    var folderLink by mutableStateOf(initialConfig.folderLink)
    var title by mutableStateOf(initialConfig.title)
    var fileRegex by mutableStateOf(initialConfig.fileRegex)
    var chapter by mutableStateOf(initialConfig.chapter)
    var chapterRegex by mutableStateOf(initialConfig.chapterRegex)
    var body by mutableStateOf(initialConfig.body)
    var next by mutableStateOf(initialConfig.next)
    var endCheck by mutableStateOf(initialConfig.endCheck)
    var delay by mutableStateOf(initialConfig.delay)
    var autoUrl by mutableStateOf(initialConfig.autoUrl)

    fun updateAll(config: ScraperConfig) {
        folder = config.folder
        regex = config.regex
        folderLink = config.folderLink
        title = config.title
        fileRegex = config.fileRegex
        chapter = config.chapter
        chapterRegex = config.chapterRegex
        body = config.body
        next = config.next
        endCheck = config.endCheck
        delay = config.delay
        autoUrl = config.autoUrl
    }

    fun toConfig(): ScraperConfig {
        return ScraperConfig(
            folder = folder,
            regex = regex,
            folderLink = folderLink,
            title = title,
            fileRegex = fileRegex,
            chapter = chapter,
            chapterRegex = chapterRegex,
            body = body,
            next = next,
            endCheck = endCheck,
            delay = delay,
            autoUrl = autoUrl
        )
    }
}

@Composable
fun SettingsPanel(
    uiState: MainUiState,
    presets: Map<String, ScraperConfig>,
    onCloseClick: () -> Unit,
    onPresetSelected: (String, ScraperConfig) -> Unit,
    onSavePresetClick: () -> Unit,
    onDeletePresetClick: () -> Unit,
    onConfigChange: (ScraperConfig) -> Unit,
    onImportPresetsClick: () -> Unit,
    onExportPresetsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // 【Google公式ベストプラクティス】各入力欄を個別にState化（1文字入力での他項目一斉再描画を100%防止）
    val formState = remember { SettingsFormState(uiState.currentConfig) }

    // uiState.currentConfig が外部から変更された場合（プリセット自動適用など）のみフォーム全体を同期
    LaunchedEffect(uiState.currentConfig) {
        formState.updateAll(uiState.currentConfig)
    }

    // パネルが閉じた時（アンマウント時）に最新の入力値を確実にViewModelへ一括同期
    DisposableEffect(Unit) {
        onDispose {
            onConfigChange(formState.toConfig())
        }
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.backgroundDarkest)
            .imePadding()
            .padding(horizontal = 14.dp)
            .verticalScroll(scrollState)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // ヘッダー行
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
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
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(34.dp).padding(end = 6.dp)
            ) {
                Text("説明書", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    onConfigChange(formState.toConfig())
                    onCloseClick()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("閉じる", fontSize = 12.sp, color = AppColors.textPrimary)
            }
        }

        // プリセット選択ドロップダウン
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.surfaceMedium, RoundedCornerShape(6.dp))
                    .border(1.dp, Color.DarkGray, RoundedCornerShape(6.dp))
                    .clickable { dropdownExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.currentPresetName.ifEmpty { "プリセット選択..." },
                    color = if (uiState.currentPresetName.isNotEmpty()) AppColors.textPrimary else AppColors.textTertiary,
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
                                formState.updateAll(selectedConfig)
                                onPresetSelected(name, selectedConfig)
                            }
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        // プリセット操作ボタン行
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Button(
                onClick = {
                    onConfigChange(formState.toConfig())
                    onSavePresetClick()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.neutralButton),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).padding(end = 4.dp).height(36.dp)
            ) { Text("保存", fontSize = 12.sp) }
            Button(
                onClick = onDeletePresetClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.error),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(36.dp)
            ) { Text("削除", fontSize = 12.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Button(
                onClick = onImportPresetsClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).padding(end = 4.dp).height(36.dp)
            ) { Text("インポート", fontSize = 12.sp) }
            Button(
                onClick = onExportPresetsClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(36.dp)
            ) { Text("エクスポート", fontSize = 12.sp) }
        }

        // セクション1: 基本情報
        SettingsCard(title = "作品・章の識別設定") {
            ConfigInputField("作品名 Selector", formState.folder) { formState.folder = it }
            ConfigInputField("作品名 Regex", formState.regex) { formState.regex = it }
            ConfigInputField("別URL取得 Selector", formState.folderLink) { formState.folderLink = it }
            ConfigInputField("タイトル Selector", formState.title) { formState.title = it }
            ConfigInputField("タイトル Regex", formState.fileRegex) { formState.fileRegex = it }
            ConfigInputField("チャプター番号 Selector", formState.chapter) { formState.chapter = it }
            ConfigInputField("チャプター番号 Regex", formState.chapterRegex) { formState.chapterRegex = it }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // セクション2: 本文・巡回設定
        SettingsCard(title = "本文・ページ巡回設定") {
            ConfigInputField("本文 Selector", formState.body) { formState.body = it }
            ConfigInputField("次ページ Selector", formState.next) { formState.next = it }
            ConfigInputField("終了検知 Regex", formState.endCheck) { formState.endCheck = it }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // セクション3: 動作設定
        SettingsCard(title = "動作・自動適用設定") {
            ConfigInputField("待機時間(秒)", formState.delay) { formState.delay = it }
            ConfigInputField("自動適用URL (ドメイン)", formState.autoUrl) { formState.autoUrl = it }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.backgroundMedium, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            color = AppColors.accentTeal,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

/**
 * 完全に独立した超軽量テキスト入力欄。
 * この入力欄で文字を打っても、他の入力欄や親パネルは一切リコンポジションされない。
 */
@Composable
private fun ConfigInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = label,
            color = AppColors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(AppColors.surfaceMedium, RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF444444), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = AppColors.textPrimary,
                    fontSize = 13.sp
                ),
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
                HelpText("・空欄：サイトから自動で取得します。\n・@名前：「@夏目漱石」のように書くと、その名前のフォルダを作ります。")

                SectionTitle("■ チャプター番号（4桁0埋め）")
                HelpText("・空欄：サイトの文字やURLから数字を探します。\n・@番号：「@1」と書くと、0001から順に自動で番号を増やしながら保存します。サイトに番号がない時や、途中から始めたい時に便利です。")

                SectionTitle("■ 次ページ（js: 指定）")
                HelpText("・ボタンがないサイト用。js: に紐げてJavaScriptを書くと、裏側のデータからURLを作れます。\n例: js:window.book.nextUrl")

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