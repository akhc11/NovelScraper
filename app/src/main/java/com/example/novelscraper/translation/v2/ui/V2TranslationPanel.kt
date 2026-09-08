package com.example.novelscraper.translation.v2.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.ui.theme.AppColors

/**
 * v2実行パネル（新旧比較用のv2入口）。開始前のプリフライト判定（モデル・キー・設定エラー）も行い、
 * 本判定はViewModel/Engine側でも重ねて行う。
 */
@Composable
fun V2TranslationPanel(viewModel: V2TranslationViewModel) {
    val settings by viewModel.settings.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val importWarnings by viewModel.importWarnings.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                // 権限確保に失敗しても中断せず、フォルダ読込失敗としてエンジンログに残る
            }
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            val folderName = doc?.name ?: treeUri.lastPathSegment ?: "選択フォルダ"
            viewModel.addFolder(treeUri, folderName)
        }
    }

    val blocking = remember(settings) { validateV2Settings(settings).filter { it.blocksSave } }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 新旧並置の明確な境界ヘッダー
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF162826), RoundedCornerShape(4.dp))
                .border(0.5.dp, AppColors.accentTeal.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(AppColors.accentTeal, RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("v2", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("LLM翻訳 v2 (新世代エンジン)", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text("能力駆動 / コスト上限対応", color = AppColors.textTertiary, fontSize = 9.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 設定サマリー & 詳細設定ボタン
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val primaryProfile = settings.profiles.firstOrNull()
            val primaryModelName = primaryProfile?.model?.ifBlank { "未設定" } ?: "未設定"
            val totalModels = settings.profiles.size
            val workers = settings.limits.parallelWorkers

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "主モデル: $primaryModelName (${workers}並列 / 全${totalModels}モデル巡回)",
                    color = AppColors.accentTealLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "辞書: ${if (settings.dict.enabled) "有効 (${settings.dict.workerCount}並列)" else "OFF"} / コスト上限: ${settings.cost.maxTokens?.let { "${it}tok" } ?: "無制限"}",
                    color = AppColors.textTertiary,
                    fontSize = 10.sp
                )
            }
            Button(
                onClick = { showSettings = true },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                shape = RoundedCornerShape(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "v2詳細設定", tint = AppColors.accentTealLight, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("v2詳細設定", color = AppColors.textPrimary, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 対象フォルダ
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("対象フォルダ(v2):", color = AppColors.textSecondary, fontSize = 12.sp)
            if (folders.size > 1) {
                Text(
                    "全 ${folders.size} フォルダ一括処理",
                    color = AppColors.accentTealLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .background(AppColors.surfaceMedium, RoundedCornerShape(4.dp))
                    .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                    .clickable(enabled = !engineState.isRunning) { folderLauncher.launch(null) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📁", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (folders.isNotEmpty()) {
                            folders.first().name + if (folders.size > 1) " 他${folders.size - 1}件" else ""
                        } else {
                            "フォルダを選択 (OSピッカー)"
                        },
                        color = if (folders.isNotEmpty()) AppColors.textPrimary else AppColors.textTertiary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                onClick = { folderLauncher.launch(null) },
                enabled = !engineState.isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                shape = RoundedCornerShape(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(if (folders.isEmpty()) "選択" else "＋ 追加", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (folders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1B2F2C), RoundedCornerShape(4.dp))
                    .border(1.dp, AppColors.accentTeal, RoundedCornerShape(4.dp))
                    .padding(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("キュー一覧(v2 ${folders.size}件):", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (!engineState.isRunning) {
                        Text("全解除", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.clickable { viewModel.clearFolders() })
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(folders) { index, item ->
                        Row(
                            modifier = Modifier
                                .background(Color(0xFF112220), RoundedCornerShape(3.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${index + 1}. ${item.name}", color = Color.White, fontSize = 11.sp, maxLines = 1)
                            if (!engineState.isRunning) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "削除",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(13.dp).clickable { viewModel.removeFolder(index) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 進捗 & ログ表示エリア
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Text(
                "ステータス(v2): ${engineState.statusText}",
                color = AppColors.accentTealLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (engineState.progress.second > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "進捗: ${engineState.progress.first} / ${engineState.progress.second} ファイル",
                    color = AppColors.textSecondary,
                    fontSize = 11.sp
                )
            }
            if (engineState.chunkProgress.second > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "大ファイル進捗: ${engineState.chunkProgress.first} / ${engineState.chunkProgress.second} チャンク",
                    color = AppColors.accentTealLight,
                    fontSize = 11.sp
                )
            }
            if (engineState.fileName.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "処理中: ${engineState.fileName}",
                    color = AppColors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "実行ログ (${engineState.logs.size}件):",
                    color = AppColors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (engineState.logs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .clickable {
                                clipboardManager.setText(AnnotatedString(engineState.logs.joinToString("\n")))
                                android.widget.Toast.makeText(context, "ログをクリップボードにコピーしました", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .background(Color(0xFF1B2E2B), RoundedCornerShape(3.dp))
                            .border(1.dp, AppColors.accentTeal, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📋 全ログコピー", color = AppColors.accentTealLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF0F1416), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF1F292E), RoundedCornerShape(4.dp))
                    .padding(6.dp)
            ) {
                if (engineState.logs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("ログはまだありません", color = AppColors.textTertiary, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = false
                    ) {
                        // 最新のログが最上部に見えるように reversed() のみを使用（二重反転解消）
                        items(engineState.logs.reversed()) { logLine ->
                            val color = when {
                                logLine.contains("✅") -> Color(0xFF81C784)
                                logLine.contains("❌") -> Color(0xFFE57373)
                                logLine.contains("⚠️") -> Color(0xFFFFB74D)
                                logLine.contains("🔄") || logLine.contains("🔁") -> Color(0xFF64B5F6)
                                logLine.contains("📝") || logLine.contains("📄") || logLine.contains("🚀") -> AppColors.accentTealLight
                                logLine.contains("⏳") -> Color(0xFFFFD54F)
                                else -> AppColors.textPrimary
                            }
                            Text(
                                text = logLine,
                                color = color,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // 開始プリフライト警告（原因を1行で明瞭に通知）
        val hasModel = settings.profiles.isNotEmpty()
        val hasKey = settings.geminiKeys.any { it.isNotBlank() } || settings.openRouterKey.isNotBlank()
        val startBlockedReason = when {
            !hasModel -> "モデル未登録のため開始できません (v2詳細設定で追加)"
            !hasKey -> "APIキー未設定のため開始できません (v2詳細設定で入力)"
            blocking.isNotEmpty() -> "設定エラー: ${blocking.first().message}"
            else -> null
        }

        if (folders.isNotEmpty() && startBlockedReason != null && !engineState.isRunning) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "⚠️ $startBlockedReason",
                color = Color(0xFFFFAA66),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
        } else {
            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.start() },
                enabled = !engineState.isRunning && folders.isNotEmpty() && startBlockedReason == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.accentOrange,
                    disabledContainerColor = AppColors.surfaceMedium
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "v2開始", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("v2翻訳開始", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { viewModel.stop() },
                enabled = engineState.isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.surfaceMedium,
                    disabledContainerColor = AppColors.surfaceMedium
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "停止",
                    tint = if (engineState.isRunning) Color.White else AppColors.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("停止", color = if (engineState.isRunning) Color.White else AppColors.textTertiary, fontSize = 13.sp)
            }
        }
    }

    if (showSettings) {
        V2SettingsDialog(
            initial = settings,
            importWarnings = importWarnings,
            onSave = {
                viewModel.saveSettings(it)
                showSettings = false
            },
            onImportLegacy = { viewModel.importLegacy(it) },
            onTestConnection = { profile -> viewModel.testConnection(profile) },
            onDismiss = {
                viewModel.clearImportWarnings()
                showSettings = false
            }
        )
    }
}
