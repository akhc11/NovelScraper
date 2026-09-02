package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.novelscraper.SelectorField
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun InspectElementDialog(
    initialSelector: String,
    onApply: (SelectorField, String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(8.dp),
            color = AppColors.surfaceDark,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "インスペクター要素解析",
                    color = AppColors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = initialSelector,
                        color = AppColors.accentTealLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("反映先フィールドを選択:", color = AppColors.textSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectorField.entries.forEach { field ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.surfaceMedium, RoundedCornerShape(4.dp))
                                .clickable { onApply(field, initialSelector) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(field.displayName, color = Color.White, fontSize = 12.sp)
                            Text("反映 →", color = AppColors.accentTealLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onCopy(initialSelector) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("セレクタをコピー", color = AppColors.textPrimary, fontSize = 12.sp)
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceMedium),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("閉じる", color = AppColors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}