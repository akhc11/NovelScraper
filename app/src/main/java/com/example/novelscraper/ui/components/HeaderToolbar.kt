package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.MainUiState
import com.example.novelscraper.PanelType
import com.example.novelscraper.ui.theme.AppColors
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun HeaderToolbar(
    uiState: MainUiState,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onStarClick: () -> Unit,
    onStarLongClick: () -> Unit = {},
    onUrlSubmit: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onPanelToggle: (PanelType) -> Unit,
    onInspectModeToggle: () -> Unit,
    onInspectToolClick: () -> Unit,
    onToggleDesktopModeClick: () -> Unit = {},
    onToggleDarkModeClick: () -> Unit = {},
    onToggleLiveTranslateClick: () -> Unit = {},
    onStartScrapingClick: () -> Unit = {},
    onTestRunClick: () -> Unit = {},
    onSearchTextQueryClick: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.backgroundDark)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Row 1: ナビゲーション & URLバー & お気に入り
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppColors.textPrimary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onForwardClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", tint = AppColors.textPrimary, modifier = Modifier.size(20.dp))
            }

            // 独立したURL入力バー（入力中の他ボタンへのリコンポジション伝播を完全遮断）
            UrlSearchBar(
                inputUrl = uiState.inputUrl,
                onUrlChange = onUrlChange,
                onUrlSubmit = onUrlSubmit,
                modifier = Modifier.weight(1f)
            )

            // 星マークボタン: 通常タップでお気に入り追加、250ms長押しでお気に入り一覧を直接開閉（ルール2厳守）
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .pointerInput(onStarClick, onStarLongClick) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val up = withTimeoutOrNull(250L) {
                                waitForUpOrCancellation()
                            }
                            if (up == null) {
                                // 250ms経過で長押し発火（お気に入り一覧開閉）
                                onStarLongClick()
                                waitForUpOrCancellation()
                            } else {
                                // 250ms未満で離された場合は通常タップ（お気に入り追加ダイアログ）
                                onStarClick()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = AppColors.accentYellow,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: ツールボタン行（主要ボタン ＋ [︙] メニュー化により画面幅320dpで完全収容）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // パネル系 (左): 履歴 & 翻訳キュー
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolButton(Icons.AutoMirrored.Filled.List, { onPanelToggle(PanelType.HISTORY) }, uiState.activePanelType == PanelType.HISTORY)
                ToolButton(null, { onPanelToggle(PanelType.TRANSLATION) }, uiState.activePanelType == PanelType.TRANSLATION, text = "翻")
            }

            // 主要操作系 (右): 設定, テスト, 虫眼鏡, 即時翻訳(🌐), 再生, ︙
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolButton(Icons.Filled.Settings, { onPanelToggle(PanelType.SETTINGS) }, uiState.activePanelType == PanelType.SETTINGS)
                ToolButton(Icons.Filled.CheckCircle, onTestRunClick, uiState.isTestResultShowing, AppColors.surfaceLight)
                ToolButton(Icons.Filled.Search, onInspectModeToggle, uiState.isInspectMode, if (uiState.isInspectMode) AppColors.inspectActive else AppColors.surfaceMedium)
                
                // 即時翻訳ボタン (🌐): Kiwi / TWP スタイルのインプレースDOM翻訳
                ToolButton(
                    icon = null,
                    onClick = onToggleLiveTranslateClick,
                    isSelected = uiState.isLiveTranslated,
                    activeColor = AppColors.accentTeal,
                    text = if (uiState.isLiveTranslating) "…" else "🌐"
                )

                ToolButton(Icons.Filled.PlayArrow, onStartScrapingClick, false, AppColors.accentOrange)

                // その他メニュー [︙]
                Box {
                    ToolButton(Icons.Filled.MoreVert, { menuExpanded = true }, menuExpanded)

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(AppColors.surfaceDark)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (uiState.isLiveTranslated) "即時翻訳: ON (原文に戻す)" else "即時翻訳: OFF (日本語化)",
                                    color = if (uiState.isLiveTranslated) AppColors.accentTeal else AppColors.textPrimary,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                onToggleLiveTranslateClick()
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Text("🌐", fontSize = 14.sp)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (uiState.isDesktopMode) "PC版サイト表示: ON" else "PC版サイト表示: OFF (モバイル)",
                                    color = AppColors.textPrimary,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                onToggleDesktopModeClick()
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Text("💻", fontSize = 14.sp)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (uiState.isWebViewDarkMode) "ダークモード: ON" else "ダークモード: OFF",
                                    color = AppColors.textPrimary,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                onToggleDarkModeClick()
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Text(if (uiState.isWebViewDarkMode) "🌙" else "☀️", fontSize = 14.sp)
                            }
                        )

                        HorizontalDivider(color = Color.DarkGray)

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "テキストから要素を検索",
                                    color = AppColors.accentTealLight,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                onSearchTextQueryClick()
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = AppColors.accentTealLight, modifier = Modifier.size(18.dp))
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "開発者ツール (Eruda)",
                                    color = AppColors.accentTealLight,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                onInspectToolClick()
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Build, contentDescription = null, tint = AppColors.accentTealLight, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlSearchBar(
    inputUrl: String,
    onUrlChange: (String) -> Unit,
    onUrlSubmit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier
            .height(38.dp)
            .padding(horizontal = 4.dp)
            .background(AppColors.surfaceDark, RoundedCornerShape(4.dp))
            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = inputUrl,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                color = AppColors.textPrimary,
                fontSize = 13.sp
            ),
            singleLine = true,
            cursorBrush = SolidColor(AppColors.accentTealLight),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    onUrlSubmit(inputUrl)
                    focusManager.clearFocus()
                }
            )
        )
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector?,
    onClick: () -> Unit,
    isSelected: Boolean,
    activeColor: Color = AppColors.accentTeal,
    text: String? = null
) {
    val background = if (isSelected) activeColor else AppColors.surfaceMedium
    val contentColor = if (isSelected) Color.White else AppColors.textPrimary

    Box(
        modifier = Modifier
            .size(35.dp)
            .background(background, RoundedCornerShape(6.dp))
            .border(
                1.dp,
                if (isSelected) activeColor else Color.Transparent,
                RoundedCornerShape(6.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize()
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            } else if (text != null) {
                Text(
                    text = text,
                    color = contentColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}