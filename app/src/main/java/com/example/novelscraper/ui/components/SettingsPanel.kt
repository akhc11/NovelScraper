package com.example.novelscraper.ui.components

import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.scraper.*
import com.example.novelscraper.ui.theme.AppColors

private val ButtonCornerShape = RoundedCornerShape(6.dp)
private val CardCornerShape = RoundedCornerShape(8.dp)
private val InputCornerShape = RoundedCornerShape(4.dp)
private val InputBorderColor = Color(0xFF444444)
private val InputTextStyle = TextStyle(color = AppColors.textPrimary, fontSize = 13.sp)
private val InputLabelTextStyle = TextStyle(color = AppColors.textSecondary, fontSize = 11.sp)

// Allocation Zero: 静的 Modifier キャッシュ
private val InputFieldColumnModifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
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
    onExportBackupClick: (Boolean) -> Unit = {},
    onImportBackupClick: () -> Unit = {},
    onToggleWebViewDarkModeClick: () -> Unit = {},
    currentUrl: String = "",
    modifier: Modifier = Modifier
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var activeHelpInfo by remember { mutableStateOf<SelectorHelpInfo?>(null) }
    var onHelpApplyCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val scrollState = rememberScrollState()

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

    fun openHelp(info: SelectorHelpInfo, onApply: (String) -> Unit) {
        activeHelpInfo = info
        onHelpApplyCallback = onApply
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }

    // 各セレクタ専用のヘルプ＆テンプレートダイアログ (UI案2)
    activeHelpInfo?.let { info ->
        SelectorHelpDialog(
            helpInfo = info,
            onApplyTemplate = { selectedTemplate ->
                onHelpApplyCallback?.invoke(selectedTemplate)
                activeHelpInfo = null
            },
            onDismiss = { activeHelpInfo = null }
        )
    }

    val currentDomain = remember(currentUrl) {
        try {
            Uri.parse(currentUrl).host ?: ""
        } catch (e: Exception) {
            ""
        }
    }

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
                Text("全体説明書", fontSize = 12.sp)
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
            // 全体バックアップ行（設定・履歴・お気に入り・LLM翻訳。APIキー同梱のみ選択式）
            var backupIncludeKeys by remember { mutableStateOf(false) }
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onExportBackupClick(backupIncludeKeys) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTealDark),
                    shape = ButtonCornerShape,
                    modifier = Modifier.weight(1f).padding(end = 4.dp).height(36.dp)
                ) { Text("バックアップ", fontSize = 12.sp) }
                Button(
                    onClick = onImportBackupClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTealDark),
                    shape = ButtonCornerShape,
                    modifier = Modifier.weight(1f).height(36.dp)
                ) { Text("復元", fontSize = 12.sp) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = backupIncludeKeys,
                    onCheckedChange = { backupIncludeKeys = it }
                )
                Text(
                    text = "APIキーを含める（平文注意）",
                    color = AppColors.textSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // セクション1: 作品・章の識別設定
        SettingsCard(title = "作品・章の識別設定") {
            ConfigInputField(
                label = "作品名 Selector",
                value = localConfig.folder,
                helpInfo = SettingsHelpData.FOLDER,
                onHelpClick = { openHelp(SettingsHelpData.FOLDER) { v -> updateField { it.copy(folder = v) } } },
                onValueChange = { updateField { c -> c.copy(folder = it) } }
            )
            ConfigInputField(
                label = "作品名 Regex",
                value = localConfig.regex,
                helpInfo = SettingsHelpData.FOLDER_REGEX,
                onHelpClick = { openHelp(SettingsHelpData.FOLDER_REGEX) { v -> updateField { it.copy(regex = v) } } },
                onValueChange = { updateField { c -> c.copy(regex = it) } }
            )
            ConfigInputField(
                label = "別URL取得 Selector",
                value = localConfig.folderLink,
                helpInfo = SettingsHelpData.FOLDER_LINK,
                onHelpClick = { openHelp(SettingsHelpData.FOLDER_LINK) { v -> updateField { it.copy(folderLink = v) } } },
                onValueChange = { updateField { c -> c.copy(folderLink = it) } }
            )
            ConfigInputField(
                label = "保存先フォルダ",
                value = localConfig.saveDir,
                helpInfo = SettingsHelpData.SAVE_DIR,
                onHelpClick = { openHelp(SettingsHelpData.SAVE_DIR) { v -> updateField { it.copy(saveDir = v) } } },
                onValueChange = { updateField { c -> c.copy(saveDir = it) } }
            )
            ConfigInputField(
                label = "タイトル Selector",
                value = localConfig.title,
                helpInfo = SettingsHelpData.TITLE,
                onHelpClick = { openHelp(SettingsHelpData.TITLE) { v -> updateField { it.copy(title = v) } } },
                onValueChange = { updateField { c -> c.copy(title = it) } }
            )
            ConfigInputField(
                label = "タイトル Regex",
                value = localConfig.fileRegex,
                helpInfo = SettingsHelpData.TITLE_REGEX,
                onHelpClick = { openHelp(SettingsHelpData.TITLE_REGEX) { v -> updateField { it.copy(fileRegex = v) } } },
                onValueChange = { updateField { c -> c.copy(fileRegex = it) } }
            )
            ConfigInputField(
                label = "チャプター番号 Selector",
                value = localConfig.chapter,
                helpInfo = SettingsHelpData.CHAPTER,
                onHelpClick = { openHelp(SettingsHelpData.CHAPTER) { v -> updateField { it.copy(chapter = v) } } },
                onValueChange = { updateField { c -> c.copy(chapter = it) } }
            )
            ConfigInputField(
                label = "チャプター番号 Regex",
                value = localConfig.chapterRegex,
                helpInfo = SettingsHelpData.CHAPTER_REGEX,
                onHelpClick = { openHelp(SettingsHelpData.CHAPTER_REGEX) { v -> updateField { it.copy(chapterRegex = v) } } },
                onValueChange = { updateField { c -> c.copy(chapterRegex = it) } }
            )
        }

        // セクション2: 本文・ページ巡回設定
        SettingsCard(title = "本文・ページ巡回設定") {
            ConfigInputField(
                label = "本文 Selector",
                value = localConfig.body,
                helpInfo = SettingsHelpData.BODY,
                onHelpClick = { openHelp(SettingsHelpData.BODY) { v -> updateField { it.copy(body = v) } } },
                onValueChange = { updateField { c -> c.copy(body = it) } }
            )
            ConfigInputField(
                label = "除外要素 (複数: , 区切り)",
                value = localConfig.exclude,
                helpInfo = SettingsHelpData.EXCLUDE,
                onHelpClick = { openHelp(SettingsHelpData.EXCLUDE) { v -> updateField { it.copy(exclude = v) } } },
                onValueChange = { updateField { c -> c.copy(exclude = it) } }
            )
            ConfigInputField(
                label = "次ページ Selector",
                value = localConfig.next,
                helpInfo = SettingsHelpData.NEXT,
                onHelpClick = { openHelp(SettingsHelpData.NEXT) { v -> updateField { it.copy(next = v) } } },
                onValueChange = { updateField { c -> c.copy(next = it) } }
            )
            ConfigInputField(
                label = "終了検知 Regex",
                value = localConfig.endCheck,
                helpInfo = SettingsHelpData.END_CHECK,
                onHelpClick = { openHelp(SettingsHelpData.END_CHECK) { v -> updateField { it.copy(endCheck = v) } } },
                onValueChange = { updateField { c -> c.copy(endCheck = it) } }
            )
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
            ConfigInputField(
                label = "待機時間(秒)",
                value = localConfig.delay,
                helpInfo = SettingsHelpData.DELAY,
                onHelpClick = { openHelp(SettingsHelpData.DELAY) { v -> updateField { it.copy(delay = v) } } },
                onValueChange = { updateField { c -> c.copy(delay = it) } }
            )
            val autoUrlHelp = remember(currentDomain) { SettingsHelpData.getAutoUrlHelp(currentDomain) }
            ConfigInputField(
                label = "自動適用URL (ドメイン)",
                value = localConfig.autoUrl,
                helpInfo = autoUrlHelp,
                onHelpClick = { openHelp(autoUrlHelp) { v -> updateField { it.copy(autoUrl = v) } } },
                onValueChange = { updateField { c -> c.copy(autoUrl = it) } }
            )
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
 * 高速かつ軽量なテキスト入力欄（右側に「使い方/テンプレ」ボタン付き）。
 */
@Composable
private fun ConfigInputField(
    label: String,
    value: String,
    helpInfo: SelectorHelpInfo? = null,
    onHelpClick: (() -> Unit)? = null,
    onValueChange: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }

    Column(modifier = InputFieldColumnModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = AppColors.textSecondary,
                style = InputLabelTextStyle
            )
            if (helpInfo != null && onHelpClick != null) {
                Row(
                    modifier = Modifier
                        .clickable { onHelpClick() }
                        .background(AppColors.surfaceMedium, RoundedCornerShape(3.dp))
                        .border(0.5.dp, AppColors.accentTeal.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💡 使い方/テンプレ",
                        color = AppColors.accentTealLight,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(AppColors.surfaceMedium, InputCornerShape)
                .border(1.dp, InputBorderColor, InputCornerShape)
                .padding(horizontal = 10.dp),
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

/**
 * 各セレクタ専用の解説・構文ルール・テンプレート選択ダイアログ (UI案2)
 */
@Composable
private fun SelectorHelpDialog(
    helpInfo: SelectorHelpInfo,
    onApplyTemplate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = helpInfo.title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = AppColors.accentTealLight
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. 概要説明
                Text(
                    text = helpInfo.description,
                    fontSize = 13.sp,
                    color = AppColors.textPrimary,
                    lineHeight = 18.sp
                )

                // 2. 構文・ルール
                if (helpInfo.syntaxRules.isNotEmpty()) {
                    Text(
                        text = "■ 構文・入力ルール",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = AppColors.accentOrange
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.backgroundDarkest, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        helpInfo.syntaxRules.forEach { rule ->
                            Text(
                                text = rule,
                                fontSize = 11.sp,
                                color = AppColors.textSecondary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                // 3. テンプレート一覧
                if (helpInfo.templates.isNotEmpty()) {
                    Text(
                        text = "■ よく使うテンプレート (タップで反映)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = AppColors.accentTeal
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        helpInfo.templates.forEach { tpl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.surfaceLight, RoundedCornerShape(6.dp))
                                    .clickable { onApplyTemplate(tpl.value) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tpl.label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.textPrimary
                                    )
                                    Text(
                                        text = tpl.value,
                                        fontSize = 11.sp,
                                        color = AppColors.accentTealLight,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (tpl.description.isNotEmpty()) {
                                        Text(
                                            text = tpl.description,
                                            fontSize = 10.sp,
                                            color = AppColors.textTertiary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = { onApplyTemplate(tpl.value) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("反映", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる", color = AppColors.accentTealLight)
            }
        },
        containerColor = AppColors.backgroundMedium,
        titleContentColor = AppColors.textPrimary,
        textContentColor = AppColors.textPrimary
    )
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アプリの使いかた・完全ガイド", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("■ 基本の指定")
                HelpText("セレクタには ID(#name) や クラス(.name) を書きます。虫眼鏡ダイアログからコピーしたものを貼り付けてください。各入力欄の「💡 使い方/テンプレ」ボタンから代表的な指定例をワンタップで入力できます。")

                SectionTitle("■ 作品名（フォルダ名）")
                HelpText("""・空欄：サイトから自動で取得します。
・@名前：「@夏目漱石」のように書くと、その名前のフォルダを作ります。""")

                SectionTitle("■ チャプター番号（★おすすめ: ハイブリッド指定）")
                HelpText("""・セレクタ || @1：サイトから話数が取れるときはそれを使い、番外編などは自動通し連番で補完します（重複上書きを100%防止）。
・@1：4桁0埋め連番（0001, 0002...）
・@01：2桁0埋め連番（01, 02...）
・@1#：0埋めなし連番（1, 2, 3...）
・@URL：URL末尾の数字から自動抽出""")

                SectionTitle("■ 次ページ（js: 指定）")
                HelpText("""・ボタンがないサイト用。js: に続けてJavaScriptを書くと、裏側のデータからURLを取得できます。
例: js:window.book.nextUrl""")

                SectionTitle("■ 自動適用URL")
                HelpText("ドメイン（syosetu.com など）を書いて保存すると、次回からそのサイトを開くだけでこの設定が自動で選ばれます。テンプレから「現在のドメインをセット」も可能です。")

                SectionTitle("■ 待機時間")
                HelpText("「3」なら3秒、「3-5」なら3〜5秒の間でランダムに待機します。")
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