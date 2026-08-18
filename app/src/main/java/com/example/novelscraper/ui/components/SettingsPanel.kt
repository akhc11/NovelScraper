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

    // 【超高速化】文字入力時の画面全体リコンポジションを防ぐローカルバッファ
    var localConfig by remember(uiState.currentConfig) { mutableStateOf(uiState.currentConfig) }

    // パネルが閉じた時（またはアンマウント時）に最新のlocalConfigを確実にViewModelへ同期
    DisposableEffect(Unit) {
        onDispose {
            onConfigChange(localConfig)
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
            .imePadding() // キーボード表示時に設定欄だけを綺麗に押し上げる（WebViewには影響なし）
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
                    onConfigChange(localConfig)
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
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Button(
                onClick = {
                    onConfigChange(localConfig)
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
            ConfigInputField("作品名 Selector", localConfig.folder) {
                localConfig = localConfig.copy(folder = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("作品名 Regex", localConfig.regex) {
                localConfig = localConfig.copy(regex = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("別URL取得 Selector", localConfig.folderLink) {
                localConfig = localConfig.copy(folderLink = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("タイトル Selector", localConfig.title) {
                localConfig = localConfig.copy(title = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("タイトル Regex", localConfig.fileRegex) {
                localConfig = localConfig.copy(fileRegex = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("チャプター番号 Selector", localConfig.chapter) {
                localConfig = localConfig.copy(chapter = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("チャプター番号 Regex", localConfig.chapterRegex) {
                localConfig = localConfig.copy(chapterRegex = it)
                onConfigChange(localConfig)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // セクション2: 本文・巡回設定
        SettingsCard(title = "本文・ページ巡回設定") {
            ConfigInputField("本文 Selector", localConfig.body) {
                localConfig = localConfig.copy(body = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("次ページ Selector", localConfig.next) {
                localConfig = localConfig.copy(next = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("終了検知 Regex", localConfig.endCheck) {
                localConfig = localConfig.copy(endCheck = it)
                onConfigChange(localConfig)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // セクション3: 動作設定
        SettingsCard(title = "動作・自動適用設定") {
            ConfigInputField("待機時間(秒)", localConfig.delay) {
                localConfig = localConfig.copy(delay = it)
                onConfigChange(localConfig)
            }
            ConfigInputField("自動適用URL (ドメイン)", localConfig.autoUrl) {
                localConfig = localConfig.copy(autoUrl = it)
                onConfigChange(localConfig)
            }
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
 * 超軽量・高レスポンスな設定入力欄。
 * OutlinedTextFieldの重厚なマテリアルアニメーション計算を排除し、
 * 最低限のレイアウトパスで高速にキー入力を受け付ける。
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