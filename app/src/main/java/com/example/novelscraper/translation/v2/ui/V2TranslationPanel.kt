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
import androidx.compose.foundation.layout.RowScope
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
import com.example.novelscraper.translation.picker.treeDisplayName
import com.example.novelscraper.ui.theme.AppColors

/**
 * v2実行パネル（新旧比較用のv2入口）。開始前のプリフライト判定（モデル・キー・設定エラー）も行い、
 * 本判定はViewModel/Engine側でも重ねて行う。
 */
@Composable
fun V2TranslationPanel(
    viewModel: V2TranslationViewModel,
    // 自前ブラウザ接続(加算のみ。null時は従来UIのまま)。
    onBrowseClick: (() -> Unit)? = null
) {
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
            } catch (e: Exception) {
                // 権限確保に失敗しても中断せず、フォルダ読込失敗としてエンジンログに残る
                // 技術的根拠1行：無言化せずLogcatに残すがフォルダ追加の流れは変えない（外部振る舞い不変）。
                android.util.Log.w("V2TranslationPanel", "takePersistable failed", e)
            }
            val folderName = treeDisplayName(context, treeUri)
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

        // 設定サマリー & 詳細設定ボタン（高頻度更新から隔離するため子に切る）
        V2PanelSettingsSummary(
            primaryModelName = settings.profiles.firstOrNull()?.model?.ifBlank { "未設定" } ?: "未設定",
            workers = settings.limits.parallelWorkers,
            totalModels = settings.profiles.size,
            dictEnabled = settings.dict.enabled,
            dictWorkers = settings.dict.workerCount,
            maxTokens = settings.cost.maxTokens,
            onOpenSettings = { showSettings = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 対象フォルダ（高頻度更新から隔離するため子に切る）
        V2FolderSection(
            folders = folders,
            isRunning = engineState.isRunning,
            onPickFolder = { folderLauncher.launch(null) },
            onBrowseClick = onBrowseClick,
            onRemoveFolder = { viewModel.removeFolder(it) },
            onClearFolders = { viewModel.clearFolders() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 進捗 & ログ表示エリア（更新頻度の異なる部位ごとに子に切る）
        V2RunStatusSection(
            statusText = engineState.statusText,
            done = engineState.progress.first,
            total = engineState.progress.second,
            chunkDone = engineState.chunkProgress.first,
            chunkTotal = engineState.chunkProgress.second,
            fileName = engineState.fileName
        )
        Spacer(modifier = Modifier.height(6.dp))
        V2EngineLogSection(
            logs = engineState.logs,
            onCopyLogs = {
                clipboardManager.setText(AnnotatedString(engineState.logs.joinToString("\n")))
                android.widget.Toast.makeText(context, "ログをクリップボードにコピーしました", android.widget.Toast.LENGTH_SHORT).show()
            }
        )

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
            V2RunActions(
                isRunning = engineState.isRunning,
                canStart = folders.isNotEmpty() && startBlockedReason == null,
                onStart = { viewModel.start() },
                onStop = { viewModel.stop() }
            )
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

/**
 * 設定サマリー表示。実行中の高頻度更新（ログ・進捗）から隔離する。
 * 技術的根拠1行：状態読取を子の境界まで延期し、親の再構成を子に波及させない（公式 Defer reads）。
 */
@Composable
private fun V2PanelSettingsSummary(
    primaryModelName: String,
    workers: Int,
    totalModels: Int,
    dictEnabled: Boolean,
    dictWorkers: Int,
    maxTokens: Long?,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                text = "辞書: ${if (dictEnabled) "有効 (${dictWorkers}並列)" else "OFF"} / コスト上限: ${maxTokens?.let { "${it}tok" } ?: "無制限"}",
                color = AppColors.textTertiary,
                fontSize = 10.sp
            )
        }
        Button(
            onClick = onOpenSettings,
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
}

/**
 * 対象フォルダ選択・キュー表示。ログ刻みでの再構成を抑える。
 * 技術的根拠1行：更新頻度の異なる部位を子に分け、strong skipping の同一性比較で飛ばせる形にする。
 */
@Composable
private fun V2FolderSection(
    folders: List<V2FolderItem>,
    isRunning: Boolean,
    onPickFolder: () -> Unit,
    onBrowseClick: (() -> Unit)?,
    onRemoveFolder: (Int) -> Unit,
    onClearFolders: () -> Unit
) {
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
                .clickable(enabled = !isRunning) { onPickFolder() }
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
            onClick = onPickFolder,
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
            shape = RoundedCornerShape(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(if (folders.isEmpty()) "選択" else "＋ 追加", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (onBrowseClick != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = onBrowseClick,
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
            shape = RoundedCornerShape(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔍 ブラウザ選択 (複数フォルダ一括)", color = AppColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                if (!isRunning) {
                    Text("全解除", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.clickable { onClearFolders() })
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                // 技術的根拠1行：uri は追加時重複排除済みの一意鍵のため、行単位の再構成を抑える（公式 lazy keys）。
                itemsIndexed(folders, key = { _, item -> item.uri }) { index, item ->
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF112220), RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}. ${item.name}", color = Color.White, fontSize = 11.sp, maxLines = 1)
                        if (!isRunning) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "削除",
                                tint = Color.LightGray,
                                modifier = Modifier.size(13.dp).clickable { onRemoveFolder(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 実行ステータス表示。進捗刻みでここだけ再構成されることを意図する。
 * 技術的根拠1行：頻繁に変わる値だけを子の境界内に閉じ込め、他部位への波及を断つ。
 */
@Composable
private fun V2RunStatusSection(
    statusText: String,
    done: Int,
    total: Int,
    chunkDone: Int,
    chunkTotal: Int,
    fileName: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Text(
            "ステータス(v2): $statusText",
            color = AppColors.accentTealLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        if (total > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "進捗: $done / $total ファイル",
                color = AppColors.textSecondary,
                fontSize = 11.sp
            )
        }
        if (chunkTotal > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "大ファイル進捗: $chunkDone / $chunkTotal チャンク",
                color = AppColors.accentTealLight,
                fontSize = 11.sp
            )
        }
        if (fileName.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "処理中: $fileName",
                color = AppColors.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 実行ログ表示。ログ追加でのみ再構成されることを意図する。
 * 技術的根拠1行：コピー操作は引数 lambda に寄せ、描画に必要な logs 読取だけを境界内に残す（公式 Defer reads）。
 */
@Composable
private fun V2EngineLogSection(
    logs: List<String>,
    onCopyLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "実行ログ (${logs.size}件):",
                color = AppColors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (logs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clickable { onCopyLogs() }
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
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("ログはまだありません", color = AppColors.textTertiary, fontSize = 11.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = false
                ) {
                    // 最新のログが最上部に見えるように reversed() のみを使用（二重反転解消）
                    items(logs.reversed()) { logLine ->
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
}

/**
 * 開始・停止ボタン。実行可否だけを受け取り、ログ刻みの影響を受けない形にする。
 * 技術的根拠1行：判定結果（Boolean）だけを渡し、判定材料の読取を親に残さない。
 */
@Composable
private fun RowScope.V2RunActions(
    isRunning: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Button(
        onClick = onStart,
        enabled = !isRunning && canStart,
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
        onClick = onStop,
        enabled = isRunning,
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
            tint = if (isRunning) Color.White else AppColors.textTertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("停止", color = if (isRunning) Color.White else AppColors.textTertiary, fontSize = 13.sp)
    }
}
