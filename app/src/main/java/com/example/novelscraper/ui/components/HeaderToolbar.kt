package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.MainUiState
import com.example.novelscraper.PanelType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeaderToolbar(
    uiState: MainUiState,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onStarClick: () -> Unit,
    onUrlSubmit: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onPanelToggle: (PanelType) -> Unit,
    onInspectModeToggle: () -> Unit,
    onInspectToolClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .background(Color.Black) // ステータスバー領域の背景色
            .statusBarsPadding()     // ステータスバーの高さ分だけ押し下げる
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface) // ツールバー自体の背景色
            .padding(bottom = 4.dp)
    ) {
        // Top Row: Navigation and URL
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp) // よりコンパクトな高さに
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.LightGray)
            }
            IconButton(onClick = onForwardClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "Forward", tint = Color.LightGray)
            }
            IconButton(onClick = onStarClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Star, contentDescription = "Star", tint = Color.LightGray)
            }
            
            BasicTextField(
                value = uiState.inputUrl,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(34.dp) // 余白を減らしたコンパクトな入力欄
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onUrlSubmit(uiState.inputUrl)
                        focusManager.clearFocus()
                    }
                ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(start = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (uiState.inputUrl.isEmpty()) {
                                    Text("検索 または URL", color = Color.Gray, fontSize = 14.sp)
                                }
                                innerTextField()
                            }
                            if (uiState.inputUrl.isNotEmpty()) {
                                IconButton(
                                    onClick = { 
                                        onUrlChange("")
                                        focusRequester.requestFocus() // クリアと同時にフォーカス
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Clear",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }

        // Bottom Row: Quick Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFF2A2A2A)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickActionButton(
                text = "★ ブックマーク",
                isActive = uiState.openedPanel == PanelType.FAVORITES,
                onClick = { onPanelToggle(PanelType.FAVORITES) },
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                text = "🕒 履歴",
                isActive = uiState.openedPanel == PanelType.HISTORY,
                onClick = { onPanelToggle(PanelType.HISTORY) },
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                text = if (uiState.isInspectMode) "👆 選択ON" else "👆 選択OFF",
                isActive = uiState.isInspectMode,
                onClick = onInspectModeToggle,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                text = "🔍 解析ツール",
                isActive = false,
                onClick = onInspectToolClick,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                text = "🔧 設定",
                isActive = uiState.openedPanel == PanelType.SETTINGS,
                onClick = { onPanelToggle(PanelType.SETTINGS) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun QuickActionButton(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .background(if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text, fontSize = 10.sp, color = Color.White)
    }
}
