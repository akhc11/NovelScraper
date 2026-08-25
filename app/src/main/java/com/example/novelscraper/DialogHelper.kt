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

    // ライフサイクル安全ガード（旧isShowingInspectDialog静的booleanの置換）。
    // 静的booleanはActivity再生成後にtrue固定となり二度とダイアログが出なくなるため、
    // 「同一コンテキストで表示中のダイアログ」のみを抑止対象にするWeakReference方式。
    private var activeInspectDialog: java.lang.ref.WeakReference<AlertDialog>? = null

    fun showInspectElementDialog(
        context: Context,
        selector: String,
        onApply: (SelectorField, String) -> Unit
    ) {
        activeInspectDialog?.get()?.let { existing ->
            if (existing.isShowing && existing.context === context) return
        }

        val fields = SelectorField.entries.toTypedArray()
        val options = fields.map { "📝 ${it.displayName} に適用" }.toMutableList()
        options.add("📋 クリップボードにコピー")

        val input = EditText(context).apply {
            setText(selector)
            setSingleLine(true)
            setSelection(selector.length)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("セレクタ取得・適用")
            .setView(input)
            .setItems(options.toTypedArray()) { _, which ->
                val currentSel = input.text.toString().trim()
                if (which < fields.size) {
                    val field = fields[which]
                    onApply(field, currentSel)
                    android.widget.Toast.makeText(context, "${field.displayName} にセレクタを反映しました", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Selector", currentSel))
                    android.widget.Toast.makeText(context, "セレクタをコピーしました", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("閉じる", null)
            .setOnDismissListener { activeInspectDialog = null }
            .create()
        dialog.show()
        activeInspectDialog = java.lang.ref.WeakReference(dialog)
    }

    fun showInspectResultDialog(context: Context, selector: String) {
        showInspectElementDialog(context, selector) { _, _ -> }
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
