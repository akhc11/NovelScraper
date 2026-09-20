package com.example.novelscraper.translation.v2.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LLM翻訳の死因切り分け用・ファイル追記診断ログ。
 * 画面ログ(EngineState.logs)はメモリ内のみでプロセス死と共に消えるため、
 * OSキルか例外クラッシュかを死後に判定できる材料を内部ストレージに残す。
 * 技術的根拠1行：重い書込は専用IOスコープに寄せ、呼出側のスレッドと外部振る舞いを変えない（計測のみ）。
 */
object TranslationDiagnostics {

    private const val TAG = "TranslationDiag"
    const val DIR_NAME = "diagnostics"
    const val LOG_NAME = "translation_diag.log"
    const val LOG_PREV_NAME = "translation_diag.1.log"
    const val STATE_NAME = "translation_run_state.txt"

    /** 単一ファイル上限（コード正本）。超えたら1世代だけローテーションする。 */
    const val MAX_BYTES = 256 * 1024L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 実行マーカーの内容。running=trueのまま残っていれば前回は正常終了しなかった。 */
    data class RunState(val running: Boolean, val timestampMs: Long, val detail: String)

    fun diagnosticsDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 時刻付き1行の純粋整形（JVMテスト可）。 */
    fun formatLine(timeMs: Long, tag: String, message: String): String {
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timeMs))
        return "[$time] [$tag] $message\n"
    }

    /** マーカーの純粋直列化（JVMテスト可）。 */
    fun serializeRunState(state: RunState): String =
        "running=${if (state.running) 1 else 0}\nts=${state.timestampMs}\ndetail=${state.detail.replace("\n", " ")}\n"

    /** マーカーの寛容読解。欠損・破損時はnull（fail-closed）。 */
    fun parseRunState(text: String?): RunState? {
        if (text.isNullOrBlank()) return null
        var running: Boolean? = null
        var ts = 0L
        var detail = ""
        for (line in text.lines()) {
            when {
                line.startsWith("running=") -> running = line.substringAfter("running=").trim() == "1"
                line.startsWith("ts=") -> ts = line.substringAfter("ts=").trim().toLongOrNull() ?: 0L
                line.startsWith("detail=") -> detail = line.substringAfter("detail=")
            }
        }
        if (running == null) return null
        return RunState(running, ts, detail)
    }

    /** 上限超え時の1世代ローテーション（JVMテスト可）。 */
    fun prune(dir: File) {
        try {
            val log = File(dir, LOG_NAME)
            if (!log.exists() || log.length() <= MAX_BYTES) return
            val prev = File(dir, LOG_PREV_NAME)
            try {
                if (prev.exists()) prev.delete()
            } catch (e: Exception) {
                Log.w(TAG, "prune prev delete failed", e)
            }
            if (!log.renameTo(prev)) {
                Log.w(TAG, "prune rotate failed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "prune failed", e)
        }
    }

    /** 同期追記（テスト・即時性が必要な箇所用）。通常は[appendLine]を使うこと。 */
    @Synchronized
    fun appendLineSync(dir: File, tag: String, message: String) {
        try {
            if (!dir.exists()) dir.mkdirs()
            prune(dir)
            File(dir, LOG_NAME).appendText(formatLine(System.currentTimeMillis(), tag, message), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "append failed", e)
        }
    }

    /** 非同期追記。呼出側スレッドを塞がない。 */
    fun appendLine(context: Context, tag: String, message: String) {
        val app = context.applicationContext
        ioScope.launch {
            appendLineSync(diagnosticsDir(app), tag, message)
        }
    }

    /** 実行マーカーの記録。開始時にtrue、正常終了・停止時にfalseを書く。 */
    fun markRunning(context: Context, running: Boolean, detail: String = "") {
        val app = context.applicationContext
        ioScope.launch {
            try {
                val dir = diagnosticsDir(app)
                if (!dir.exists()) dir.mkdirs()
                File(dir, STATE_NAME).writeText(
                    serializeRunState(RunState(running, System.currentTimeMillis(), detail)),
                    Charsets.UTF_8
                )
            } catch (e: Exception) {
                Log.w(TAG, "markRunning failed", e)
            }
        }
    }

    /**
     * 前回が実行中に死んだかを確認する。呼出側はIOスレッドで呼ぶこと。
     * @return 中断されていた実行のdetail。正常終了後・初回はnull。
     */
    fun checkInterrupted(context: Context): String? {
        return try {
            val dir = diagnosticsDir(context.applicationContext)
            val state = parseRunState(
                File(dir, STATE_NAME).takeIf { it.exists() }?.readText(Charsets.UTF_8)
            ) ?: return null
            if (!state.running) return null
            appendLineSync(dir, "lifecycle", "前回の翻訳が中断されたままプロセスが死んでいました (detail=${state.detail})。OSキル・OOMの疑い")
            state.detail.ifBlank { "(detailなし)" }
        } catch (e: Exception) {
            Log.w(TAG, "checkInterrupted failed", e)
            null
        }
    }
}
