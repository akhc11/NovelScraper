package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.HistoryItem
import com.example.novelscraper.scraper.ScrapingTask
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun HistoryPanel(
    activeTab: Int,
    onTabSelected: (Int) -> Unit,
    activeTasks: List<ScrapingTask>,
    history: Map<String, HistoryItem>,
    onCloseClick: () -> Unit,
    onStopTaskClick: (ScrapingTask) -> Unit,
    onHistoryItemClick: (String) -> Unit,
    onHistoryResumeClick: (String) -> Unit,
    onDeleteHistoryClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().background(AppColors.backgroundDarkest)) {
        // ヘッダー行（全パネル統一形式: 機能名 + 右側に閉じるボタン）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.backgroundDark)
                .padding(horizontal = 15.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "履歴",
                color = AppColors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onCloseClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("閉じる", color = AppColors.textPrimary, fontSize = 12.sp)
            }
        }

        val tabs = remember { listOf("実行中", "履歴") }
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = AppColors.backgroundDark,
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tabPositions[activeTab]), color = AppColors.accentTeal)
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = activeTab == index,
                    onClick = { onTabSelected(index) },
                    text = { Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                )
            }
        }
        
        Box(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
            if (activeTab == 0) {
                RunningTasksList(activeTasks, onStopTaskClick)
            } else {
                HistoryList(history, onHistoryItemClick, onHistoryResumeClick, onDeleteHistoryClick)
            }
        }
    }
}

@Composable
fun RunningTasksList(tasks: List<ScrapingTask>, onStopTaskClick: (ScrapingTask) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        items(
            items = tasks,
            key = { it.currentUrl.ifEmpty { it.folderName } },
            contentType = { "running_task" }
        ) { task ->
            RunningTaskRow(task = task, onStopClick = { onStopTaskClick(task) })
        }
    }
}

@Composable
private fun RunningTaskRow(
    task: ScrapingTask,
    onStopClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .background(AppColors.backgroundMedium, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.folderName,
                color = AppColors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = task.status,
                color = AppColors.accentTealLight,
                fontSize = 12.sp
            )
        }
        Button(
            onClick = onStopClick,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.error),
            shape = RoundedCornerShape(4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text("停止", fontSize = 11.sp, color = Color.White)
        }
    }
}

@Composable
fun HistoryList(
    history: Map<String, HistoryItem>,
    onItemClick: (String) -> Unit,
    onResumeClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    // 毎フレームのソートを完全排除（historyが変更された時のみ再計算）
    val sortedHistory = remember(history) {
        history.toList().sortedByDescending { it.second.timestamp }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        items(
            items = sortedHistory,
            key = { it.first },
            contentType = { "history_item" }
        ) { (folderName, item) ->
            HistoryItemRow(
                folderName = folderName,
                item = item,
                onItemClick = onItemClick,
                onResumeClick = onResumeClick,
                onDeleteClick = onDeleteClick
            )
        }
    }
}

@Composable
private fun HistoryItemRow(
    folderName: String,
    item: HistoryItem,
    onItemClick: (String) -> Unit,
    onResumeClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(AppColors.backgroundMedium, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 削除ボタン (左端に配置して誤操作防止)
        IconButton(
            onClick = { onDeleteClick(folderName) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = Color(0xFF888888),
                modifier = Modifier.size(18.dp)
            )
        }

        // 2. メイン情報エリア (タップでサイトへ移動)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .clickable { onItemClick(item.url) }
        ) {
            Text(
                text = folderName,
                color = AppColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.chapter} - ${item.title}", 
                color = AppColors.textSecondary, 
                fontSize = 12.sp, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.time,
                color = AppColors.textTertiary,
                fontSize = 10.sp
            )
        }
        
        // 3. 再開ボタン (右端に大きく配置)
        Button(
            onClick = { onResumeClick(folderName) },
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentOrange),
            shape = RoundedCornerShape(4.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(width = 48.dp, height = 36.dp)
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Resume",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}