package com.example.novelscraper.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.novelscraper.ScraperConfig
import com.example.novelscraper.SelectorField
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun AddFavoriteDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surfaceDark,
        title = {
            Text("お気に入りに追加", color = AppColors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("タイトル:", color = AppColors.textSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                DialogInputField(
                    value = title,
                    onValueChange = { title = it },
                    onDone = {
                        val trimmed = title.trim()
                        if (trimmed.isNotEmpty()) onConfirm(trimmed)
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = title.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("追加", color = Color.White, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル", color = AppColors.textTertiary, fontSize = 13.sp)
            }
        }
    )
}

@Composable
fun SavePresetDialog(
    defaultPresetName: String,
    currentUrl: String,
    existingPresets: Map<String, ScraperConfig>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialName = remember {
        val base = if (defaultPresetName.isNotEmpty()) defaultPresetName else generatePresetName(currentUrl)
        resolveNameConflict(base, existingPresets)
    }
    var presetName by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surfaceDark,
        title = {
            Text("プリセットを保存", color = AppColors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("プリセット名:", color = AppColors.textSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                DialogInputField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    onDone = {
                        var name = presetName.trim()
                        if (name.isEmpty()) name = generatePresetName(currentUrl)
                        name = resolveNameConflict(name, existingPresets)
                        onConfirm(name)
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    var name = presetName.trim()
                    if (name.isEmpty()) name = generatePresetName(currentUrl)
                    name = resolveNameConflict(name, existingPresets)
                    onConfirm(name)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("保存", color = Color.White, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル", color = AppColors.textTertiary, fontSize = 13.sp)
            }
        }
    )
}

@Composable
fun InspectElementDialog(
    initialSelector: String,
    onApply: (SelectorField, String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectorText by remember(initialSelector) { mutableStateOf(initialSelector) }
    val fields = remember { SelectorField.entries.toTypedArray() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.surfaceDark,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "セレクタ取得・適用",
                    color = AppColors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text("取得セレクタ（編集可能）:", color = AppColors.textSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                DialogInputField(
                    value = selectorText,
                    onValueChange = { selectorText = it }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("反映先を選択:", color = AppColors.textSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    fields.forEach { field ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.surfaceMedium, RoundedCornerShape(4.dp))
                                .clickable {
                                    onApply(field, selectorText.trim())
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📝", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${field.displayName} に適用",
                                color = AppColors.textPrimary,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.surfaceMedium, RoundedCornerShape(4.dp))
                            .clickable {
                                onCopy(selectorText.trim())
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📋", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "クリップボードにコピー",
                            color = AppColors.accentTealLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("閉じる", color = AppColors.textTertiary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(AppColors.backgroundDark, RoundedCornerShape(4.dp))
            .border(1.dp, Color.DarkGray, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                color = AppColors.textPrimary,
                fontSize = 13.sp
            ),
            singleLine = true,
            cursorBrush = SolidColor(AppColors.accentTealLight),
            keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() })
        )
    }
}

private fun generatePresetName(currentUrl: String): String {
    return try {
        Uri.parse(currentUrl).host ?: "Preset"
    } catch (_: Exception) {
        "Preset"
    }
}

private fun resolveNameConflict(baseName: String, existingPresets: Map<String, ScraperConfig>): String {
    var name = baseName
    while (existingPresets.containsKey(name)) {
        val match = Regex("""(.+) \((\d+)\)$""").find(name)
        name = if (match != null) {
            "${match.groupValues[1]} (${match.groupValues[2].toInt() + 1})"
        } else {
            "$name (1)"
        }
    }
    return name
}
