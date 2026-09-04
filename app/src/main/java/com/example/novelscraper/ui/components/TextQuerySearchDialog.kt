package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.novelscraper.scraper.SelectorField
import com.example.novelscraper.scraper.TextSearchResultItem
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun TextQuerySearchDialog(
    onDismiss: () -> Unit,
    onSearch: (String, (List<TextSearchResultItem>) -> Unit) -> Unit,
    onApplyToField: (SelectorField, String) -> Unit,
    onCopySelector: (String) -> Unit
) {
    var queryText by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<TextSearchResultItem>?>(null) }

    fun doSearch() {
        val q = queryText.trim()
        if (q.isEmpty()) return
        isSearching = true
        onSearch(q) { results ->
            searchResults = results
            isSearching = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.surfaceDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ヘッダー部
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = AppColors.accentTeal,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "テキスト・セレクタから要素を検索",
                        color = AppColors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "作品名、タイトル、本文の一部、またはセレクタ（title, h1等）を入力して検索します。<head>タグ内の非表示テキストも検出されます。",
                    color = AppColors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                // 検索入力欄
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    label = { Text("探したい文字 または セレクタ", color = AppColors.textMuted, fontSize = 12.sp) },
                    placeholder = { Text("例: 仙子的修行, title, h1, #content", color = AppColors.textMuted.copy(alpha = 0.5f), fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    trailingIcon = {
                        if (queryText.isNotEmpty()) {
                            IconButton(onClick = { queryText = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "クリア", tint = AppColors.textSecondary)
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.textPrimary,
                        unfocusedTextColor = AppColors.textPrimary,
                        focusedBorderColor = AppColors.accentTeal,
                        unfocusedBorderColor = Color(0xFF424242),
                        cursorColor = AppColors.accentTeal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // 検索ボタン & キャンセル
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("閉じる", color = AppColors.textSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { doSearch() },
                        enabled = queryText.trim().isNotEmpty() && !isSearching,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("検索中…", color = Color.White, fontSize = 13.sp)
                        } else {
                            Text("検索実行", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)

                // 検索結果エリア
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isSearching -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                CircularProgressIndicator(color = AppColors.accentTeal)
                                Text("HTML全体を探索中…", color = AppColors.textSecondary, fontSize = 13.sp)
                            }
                        }
                        searchResults == null -> {
                            Text(
                                text = "キーワードを入力して「検索実行」を押してください",
                                color = AppColors.textMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 20.dp)
                            )
                        }
                        searchResults!!.isEmpty() -> {
                            Text(
                                text = "該当する要素が見つかりませんでした",
                                color = AppColors.textSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 20.dp)
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(searchResults!!) { item ->
                                    SearchResultCard(
                                        item = item,
                                        onApply = onApplyToField,
                                        onCopy = onCopySelector
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    item: TextSearchResultItem,
    onApply: (SelectorField, String) -> Unit,
    onCopy: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242424))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // バッジ行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = Color(0xFF004D40),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = item.matchType,
                        color = Color(0xFF80CBC4),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (item.tag.isNotEmpty()) {
                    Surface(
                        color = Color(0xFF37474F),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "<${item.tag.lowercase()}>",
                            color = Color(0xFFCFD8DC),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // セレクタ表示（タップでコピー）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181818), RoundedCornerShape(4.dp))
                    .clickable { onCopy(item.selector) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.selector,
                    color = Color(0xFFEEEEEE),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "タップでコピー",
                    color = AppColors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            // テキストプレビュー（中身の文字列）
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 85.dp)
                    .background(Color(0xFF141414), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(4.dp))
                    .padding(6.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = item.preview.ifBlank { "(テキストなし)" },
                    color = Color(0xFFB0BEC5),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            // クイック反映アクションボタン群（インスペクターの7フィールドと完全対応）
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ApplyChipButton(
                        text = "作品名",
                        color = AppColors.accentTeal,
                        onClick = { onApply(SelectorField.FOLDER, item.selector) }
                    )
                    ApplyChipButton(
                        text = "タイトル",
                        color = Color(0xFF00897B),
                        onClick = { onApply(SelectorField.TITLE, item.selector) }
                    )
                    ApplyChipButton(
                        text = "本文",
                        color = Color(0xFF1E88E5),
                        onClick = { onApply(SelectorField.BODY, item.selector) }
                    )
                    ApplyChipButton(
                        text = "次ページ",
                        color = Color(0xFF8E24AA),
                        onClick = { onApply(SelectorField.NEXT, item.selector) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ApplyChipButton(
                        text = "話数",
                        color = Color(0xFF6D4C41),
                        onClick = { onApply(SelectorField.CHAPTER, item.selector) }
                    )
                    ApplyChipButton(
                        text = "別URL",
                        color = Color(0xFF5C6BC0),
                        onClick = { onApply(SelectorField.FOLDER_LINK, item.selector) }
                    )
                    ApplyChipButton(
                        text = "除外",
                        color = Color(0xFF78909C),
                        onClick = { onApply(SelectorField.EXCLUDE, item.selector) }
                    )
                    ApplyChipButton(
                        text = "コピー",
                        color = Color(0xFF546E7A),
                        onClick = { onCopy(item.selector) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ApplyChipButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .weight(1f)
            .height(28.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
