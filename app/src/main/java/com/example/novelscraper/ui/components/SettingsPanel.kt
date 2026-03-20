package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.novelscraper.MainUiState
import com.example.novelscraper.ScraperConfig

@Composable
fun SettingsPanel(
    uiState: MainUiState,
    presets: Map<String, ScraperConfig>,
    onCloseClick: () -> Unit,
    onTestRunClick: () -> Unit,
    onToggleImagesClick: () -> Unit,
    onPresetSelected: (String, ScraperConfig) -> Unit,
    onSavePresetClick: () -> Unit,
    onDeletePresetClick: () -> Unit,
    onConfigChange: (ScraperConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val config = uiState.currentConfig

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF2D2D2D))
            .padding(15.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "解析設定",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { showHelpDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp).padding(end = 8.dp)
            ) {
                Text("📖 説明書", fontSize = 12.sp, color = Color.White)
            }
            Button(
                onClick = onCloseClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("閉じる ✕", fontSize = 12.sp, color = Color.White)
            }
        }

        // Test Run & Toggle Images (Side-by-side)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onTestRunClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("🧪 テスト解析", color = Color.White, fontSize = 14.sp)
            }

            Button(
                onClick = onToggleImagesClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.blockImages) Color(0xFF555555) else Color(0xFF00897B)
                ),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (uiState.blockImages) "🖼️ 画像: OFF" else "🖼️ 画像: ON", color = Color.White, fontSize = 14.sp)
            }
        }

        // Presets Spinner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 40.dp)
                    .background(Color(0xFF555555), MaterialTheme.shapes.small)
                    .clickable { dropdownExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.currentPresetName.ifEmpty { "プリセットを選択..." },
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier.background(Color(0xFF444444))
            ) {
                presets.keys.sorted().forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, color = Color.White) },
                        onClick = {
                            presets[name]?.let { onPresetSelected(name, it) }
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        // Save / Delete Preset
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 15.dp)
        ) {
            Button(
                onClick = onSavePresetClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9E9E9E)),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 5.dp)
            ) {
                Text("保存", color = Color.White)
            }
            Button(
                onClick = onDeletePresetClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
            ) {
                Text("削除", color = Color.White)
            }
        }

        // Settings Fields
        val textModifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        ConfigTextField("作品名Selector(フォルダ)", config.folder, textModifier) { onConfigChange(config.copy(folder = it)) }
        ConfigTextField("作品名Regex(フォルダ)", config.regex, textModifier) { onConfigChange(config.copy(regex = it)) }
        ConfigTextField("作品名リンクSelector(フォルダ)", config.folderLink, textModifier) { onConfigChange(config.copy(folderLink = it)) }
        
        ConfigTextField("タイトル名Selector(ファイル)", config.title, textModifier) { onConfigChange(config.copy(title = it)) }
        ConfigTextField("タイトル名Regex(ファイル)", config.fileRegex, textModifier) { onConfigChange(config.copy(fileRegex = it)) }
        
        ConfigTextField("チャプター番号Selector", config.chapter, textModifier) { onConfigChange(config.copy(chapter = it)) }
        ConfigTextField("チャプター番号Regex", config.chapterRegex, textModifier) { onConfigChange(config.copy(chapterRegex = it)) }
        
        ConfigTextField("本文Selector", config.body, textModifier) { onConfigChange(config.copy(body = it)) }
        ConfigTextField("次へボタンSelector", config.next, textModifier) { onConfigChange(config.copy(next = it)) }
        
        ConfigTextField("待機時間(秒)", config.delay, textModifier) { onConfigChange(config.copy(delay = it)) }
        ConfigTextField("終了条件(Regex/文字)", config.endCheck, textModifier) { onConfigChange(config.copy(endCheck = it)) }
        ConfigTextField("自動適用URL(一部一致)", config.autoUrl, textModifier) { onConfigChange(config.copy(autoUrl = it)) }

        Spacer(modifier = Modifier.height(150.dp))
    }
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📝 猿でもわかる設定の説明書", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("基本の仕組み", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Text("ウェブページの中の特定の「文字」を正確に取り出すための設定です。\n\n・Selector: どこから取るか（場所の指定）\n・Regex: 取った文字をどう整えるか（切り抜き・消去）\n", fontSize = 14.sp)
                
                Text("🔍 Selector（場所の指定）", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
                Text("ヘッダーの「🔍 解析ツール」を使うと、画面をタップするだけで自動入力できます。\n（例: .novel_title, #chapter-name など）\n空白の場合はAIが自動で探します。\n", fontSize = 14.sp)
                
                Text("✂️ Regex（正規表現）の具体的な使い方", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
                Text("取得した文字から「欲しい部分だけを抜き出す」ための強力なルールです。\n\n" +
                     "【使い方1: 欲しい部分を「()」で囲んで抜き出す】（超おすすめ）\n" +
                     "「(\\d+)」と入力すると、最初に見つかった「連続した数字」だけを綺麗に抜き出します。\n" +
                     "例: 「第12話 始まり」→「12」だけになる\n\n" +
                     "【使い方2: 特定の文字の後ろの数字を抜き出す】\n" +
                     "タイトルが「更新分: 01 - 234243」で「01」が欲しい場合。\n" +
                     "👉 「更新分: (\\d+)」と入力。\n" +
                     "（更新分の後にある数字だけを抜き出します）\n\n" +
                     "【使い方3: 不要な文字を消す】\n" +
                     "「第|話」と入力すると、「第」と「話」という文字が消去されます（| は「または」の意味）。\n", fontSize = 14.sp)
                
                Text("🔢 URLの途中の数字をチャプターにしたい場合", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                Text("自動取得に任せると、URLの「一番最後」の数字がチャプター番号になってしまいます。\n\n" +
                     "もし「.../01/2342432423/」というURLから「01」を取り出したい場合は、以下の裏技を使います。\n\n" +
                     "1. チャプター番号Selector に 「@URL」と入力。\n" +
                     "2. チャプター番号Regex に 「/(\\d+)/\\d+/?$」と入力。\n" +
                     "（意味：スラッシュに囲まれた数字の後ろに、さらに数字が続いて終わるURLの場合、最初の数字を抜き出す）\n", fontSize = 14.sp)
                     
                Text("📚 各項目の意味", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Text("・作品名(フォルダ): 全体をまとめる親フォルダの名前。\n・タイトル名(ファイル): 保存されるテキストファイルの名前。\n・チャプター番号: ファイル名に「001_」と連番をつけるための番号。\n・次へボタン: 次の話に進むためのリンクの場所。\n・待機時間: ページ移動の待ち時間。「2-5」と書くと2〜5秒のランダム待ちになります。", fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("分かった！", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF222222),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

@Composable
fun ConfigTextField(hint: String, value: String, modifier: Modifier = Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(hint, color = Color.Gray, fontSize = 12.sp) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.DarkGray,
            focusedContainerColor = Color(0xFF333333),
            unfocusedContainerColor = Color(0xFF333333)
        ),
        modifier = modifier,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
    )
}
