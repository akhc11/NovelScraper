package com.example.novelscraper

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.EditText

/**
 * MainActivityからダイアログ生成ロジックを分離。
 * 各メソッドは Context のみに依存し、ビジネスロジックはコールバックで呼び出し元に返す。
 */
object DialogHelper {

    fun showAddFavoriteDialog(context: Context, title: String, onConfirm: (String) -> Unit) {
        val input = EditText(context)
        input.setText(title)
        AlertDialog.Builder(context)
            .setTitle("お気に入り")
            .setView(input)
            .setPositiveButton("追加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onConfirm(name)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    fun showSavePresetDialog(
        context: Context,
        currentPresetName: String,
        currentUrl: String,
        existingPresets: Map<String, ScraperConfig>,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(context)
        input.hint = "プリセット名"
        AlertDialog.Builder(context)
            .setTitle("保存")
            .setPositiveButton("保存") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) {
                    name = generatePresetName(currentPresetName, currentUrl)
                }
                name = resolveNameConflict(name, existingPresets)
                onConfirm(name)
            }
            .setNegativeButton("キャンセル", null)
            .setView(input)
            .show()
    }

    fun showInspectResultDialog(context: Context, selector: String) {
        val input = EditText(context)
        input.setText(selector)
        AlertDialog.Builder(context)
            .setTitle("セレクタ取得")
            .setMessage("コピーしますか？")
            .setView(input)
            .setPositiveButton("コピー") { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Selector", input.text.toString()))
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    fun showTestResultDialog(
        context: Context,
        data: ScrapingResult,
        chapterDisplay: String
    ) {
        val msg = "作品: ${data.folderName}\n話: $chapterDisplay\nタイトル: ${data.title}\n次: ${data.nextUrl}\n\n本文:\n${data.content.take(300)}..."
        AlertDialog.Builder(context)
            .setTitle("テスト結果")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    /** 空入力時のプリセット名自動生成 */
    private fun generatePresetName(currentPresetName: String, currentUrl: String): String {
        if (currentPresetName.isNotEmpty()) {
            val match = Regex("""(.+) \((\d+)\)$""").find(currentPresetName)
            return if (match != null) {
                "${match.groupValues[1]} (${match.groupValues[2].toInt() + 1})"
            } else {
                "$currentPresetName (1)"
            }
        }
        return try {
            Uri.parse(currentUrl).host ?: "Preset"
        } catch (e: Exception) {
            "Preset"
        }
    }

    /** 既存プリセットと名前が衝突する場合、連番を付与 */
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
}
