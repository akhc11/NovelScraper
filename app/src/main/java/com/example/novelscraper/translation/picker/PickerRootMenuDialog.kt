package com.example.novelscraper.translation.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.ui.theme.AppColors

/**
 * 許可根メニュー。フォルダ内選択と同一の器・同一サイズで表示する。
 */
@Composable
fun PickerRootMenuDialog(
    roots: List<GrantedRoot>,
    health: Map<String, RootHealth>,
    onOpen: (GrantedRoot) -> Unit,
    onForget: (GrantedRoot) -> Unit,
    onGrantNew: () -> Unit,
    onDismiss: () -> Unit
) {
    PickerDialogShell(title = "ブラウザ選択: 親フォルダ", onDismiss = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            if (roots.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "許可済みフォルダがありません。下の「新規許可」で親フォルダを1回許可すると、以後は中で複数選択できます。",
                        color = AppColors.textTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(roots, key = { it.treeUri }) { root ->
                        val bad = (health[root.treeUri] ?: RootHealth.OK) != RootHealth.OK
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { onOpen(root) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    "📁 ${root.displayName}" + if (bad) " ⚠️再許可" else "",
                                    color = if (bad) Color(0xFFFFB74D) else AppColors.textPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { onForget(root) }) {
                                Text("✕", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onGrantNew,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("＋ 新規許可", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Android 11以降はDownload直下・端末ルートは許可できません。中のフォルダを選んでください。",
                color = AppColors.textTertiary,
                fontSize = 10.sp
            )
        }
    }
}
