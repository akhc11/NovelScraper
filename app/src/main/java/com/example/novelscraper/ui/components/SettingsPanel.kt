package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val config = uiState.currentConfig

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }

    val configItems = listOf(
        Triple("作品名 Selector", config.folder) { v: String -> onConfigChange(config.copy(folder = v)) },
        Triple("作品名 Regex", config.regex) { v: String -> onConfigChange(config.copy(regex = v)) },
        Triple("別URL取得 Selector", config.folderLink) { v: String -> onConfigChange(config.copy(folderLink = v)) },
        Triple("タイトル Selector", config.title) { v: String -> onConfigChange(config.copy(title = v)) },
        Triple("タイトル Regex", config.fileRegex) { v: String -> onConfigChange(config.copy(fileRegex = v)) },
        Triple("チャプター番号 Selector", config.chapter) { v: String -> onConfigChange(config.copy(chapter = v)) },
        Triple("チャプター番号 Regex", config.chapterRegex) { v: String -> onConfigChange(config.copy(chapterRegex = v)) },
        Triple("本文 Selector", config.body) { v: String -> onConfigChange(config.copy(body = v)) },
        Triple("次ページ Selector", config.next) { v: String -> onConfigChange(config.copy(next = v)) },
        Triple("待機時間(秒)", config.delay) { v: String -> onConfigChange(config.copy(delay = v)) },
        Triple("終了検知 Regex", config.endCheck) { v: String -> onConfigChange(config.copy(endCheck = v)) },
        Triple("自動適用URL (ドメイン)", config.autoUrl) { v: String -> onConfigChange(config.copy(autoUrl = v)) }
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.backgroundLight)
            .padding(horizontal = 15.dp),
        contentPadding = PaddingValues(top = 15.dp, bottom = 100.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("設定", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                Button(
                    onClick = { showHelpDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                    modifier = Modifier.height(36.dp).padding(end = 8.dp)
                ) { Text("説明書", fontSize = 12.sp) }
                Button(
                    onClick = onCloseClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                    modifier = Modifier.height(36.dp)
                ) { Text("閉じる", fontSize = 12.sp) }
            }
        }


        item {
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.surfaceHighlight, MaterialTheme.shapes.small)
                        .clickable { dropdownExpanded = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.currentPresetName.ifEmpty { "プリセット選択..." },
                        color = AppColors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = AppColors.textPrimary)
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.background(AppColors.surfaceLight)
                ) {
                    presets.keys.sorted().forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name, color = AppColors.textPrimary) },
                            onClick = {
                                presets[name]?.let { onPresetSelected(name, it) }
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Button(
                        onClick = onSavePresetClick,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.neutralButton),
                        modifier = Modifier.weight(1f).padding(end = 5.dp)
                    ) { Text("保存") }
                    Button(
                        onClick = onDeletePresetClick,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.error),
                        modifier = Modifier.weight(1f)
                    ) { Text("削除") }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onImportPresetsClick,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                        modifier = Modifier.weight(1f).padding(end = 5.dp)
                    ) { Text("インポート") }
                    Button(
                        onClick = onExportPresetsClick,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                        modifier = Modifier.weight(1f)
                    ) { Text("エクスポート") }
                }
            }
        }

        items(configItems) { item ->
            ConfigTextField(
                hint = item.first,
                value = item.second,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                onValueChange = item.third
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

@Composable
fun ConfigTextField(hint: String, value: String, modifier: Modifier = Modifier, onValueChange: (String) -> Unit) {
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = AppColors.textPrimary,
        unfocusedTextColor = AppColors.textPrimary,
        focusedBorderColor = AppColors.accentTeal,
        unfocusedBorderColor = Color.DarkGray,
        focusedContainerColor = AppColors.surfaceMedium,
        unfocusedContainerColor = AppColors.surfaceMedium
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(hint, color = AppColors.textTertiary, fontSize = 12.sp) },
        colors = colors,
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp)
    )
}