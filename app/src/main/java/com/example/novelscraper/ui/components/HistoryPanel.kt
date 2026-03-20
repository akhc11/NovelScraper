package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.HistoryItem
import com.example.novelscraper.ScrapingTask

@Composable
fun HistoryPanel(
    activeTab: Int,
    onTabSelected: (Int) -> Unit,
    activeTasks: List<ScrapingTask>,
    history: Map<String, HistoryItem>,
    onStopTaskClick: (ScrapingTask) -> Unit,
    onHistoryItemClick: (String) -> Unit,
    onDeleteHistoryClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF2D2D2D))
    ) {
        val tabs = listOf("🚀 実行中", "📜 履歴")
        
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = Color(0xFF1F1F1F),
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = activeTab == index,
                    onClick = { onTabSelected(index) },
                    text = { Text(title, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (activeTab == 0) {
                RunningTasksList(activeTasks, onStopTaskClick)
            } else {
                HistoryList(history, onHistoryItemClick, onDeleteHistoryClick)
            }
        }
    }
}

@Composable
fun RunningTasksList(
    tasks: List<ScrapingTask>,
    onStopTaskClick: (ScrapingTask) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(tasks) { task ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = task.folderName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(text = task.status, color = Color(0xFF03DAC5), fontSize = 13.sp)
                }
                Button(
                    onClick = { onStopTaskClick(task) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("停止", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun HistoryList(
    history: Map<String, HistoryItem>,
    onItemClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    val sortedHistory = history.toList().sortedByDescending { it.second.time }
    
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(sortedHistory) { (folderName, item) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemClick(item.url) }
                    .padding(vertical = 12.dp, horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = folderName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    val titleText = if (item.chapter.isNotEmpty()) "第${item.chapter}話 ${item.title}" else item.title
                    Text(text = titleText, color = Color.LightGray, fontSize = 13.sp, maxLines = 1)
                    Text(text = "${item.time} - ${item.url}", color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                }
                Button(
                    onClick = { onDeleteClick(folderName) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("削除", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}
