package com.example.novelscraper.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * アプリ全体で使うセマンティックカラー定数。
 * ハードコードされた Color(0xFF...) を排除し、意味のある名前で管理する。
 */
object AppColors {
    // 背景レイヤー（暗い順）
    val backgroundDarkest = Color(0xFF121212)
    val backgroundDark = Color(0xFF1A1A1A)
    val backgroundMedium = Color(0xFF222222)
    val backgroundLight = Color(0xFF2D2D2D)

    // サーフェス（入力欄・カード等）
    val surfaceDark = Color(0xFF2A2A2A)
    val surfaceMedium = Color(0xFF333333)
    val surfaceLight = Color(0xFF444444)
    val surfaceHighlight = Color(0xFF555555)

    // アクセントカラー
    val accentTeal = Color(0xFF00897B)
    val accentTealLight = Color(0xFF03DAC5)
    val accentOrange = Color(0xFFFF5722)
    val accentYellow = Color(0xFFFFD600)

    // テキスト
    val textPrimary = Color.White
    val textSecondary = Color.LightGray
    val textTertiary = Color.Gray
    val textMuted = Color(0xFFCCCCCC)

    // セマンティック
    val error = Color(0xFFD32F2F)
    val inspectActive = Color.Red
    val neutralButton = Color(0xFF9E9E9E)
}
