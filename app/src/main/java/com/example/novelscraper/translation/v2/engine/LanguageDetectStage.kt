package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.common.ingest.IngestResult
import com.example.novelscraper.translation.common.ingest.TextIngest
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.detectLanguage
import com.example.novelscraper.translation.v2.pipeline.findOrCreateFile

/**
 * 言語判定ステージ。
 * 親フォルダまたは小説フォルダから安全にテキストをサンプリングし、
 * 言語キャッシュ（.lang_cache）の取得または新規判定・永続化を行う。
 * 技術的根拠1行：巨大ファイルの全量読み込みによるOOMを防ぐため先頭8KBサンプリング＋TextIngestで言語を確定する。
 */
object LanguageDetectStage {

    suspend fun sampleTextForLanguage(
        store: FileStore,
        files: List<VDoc>,
        targetChars: Int = 1200,
        maxFiles: Int = 5
    ): String {
        val sb = StringBuilder()
        for (f in files.take(maxFiles)) {
            // 技術的根拠1行：巨大ファイルのOOM防止および非UTF-8（GBK等）の文字コード自動判別のため先頭8KBをバイト読込・デコードする。
            val bytes = store.readBytes(f.uri, 8192)
            val text = if (bytes != null && bytes.isNotEmpty()) {
                when (val res = TextIngest.ingest(bytes)) {
                    is IngestResult.Success -> res.text
                    else -> store.readText(f.uri) ?: continue
                }
            } else {
                store.readText(f.uri) ?: continue
            }
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.length >= 2 && !trimmed.all { it in "*=-_#~ 　\t" }) {
                    sb.append(trimmed).append('\n')
                    if (sb.length >= targetChars) return sb.toString()
                }
            }
        }
        return sb.toString()
    }

    /**
     * 言語判定または既存キャッシュ (.lang_cache) の取得。
     * 技術的根拠1行：言語は小説全体の属性のため親フォルダ直下に保存し、OSの拡張子付与を防ぐため application/octet-stream で作成する。
     */
    suspend fun detectOrLoadLanguage(
        store: FileStore,
        folderUri: String,
        files: List<VDoc>,
        inherited: SourceLang? = null,
        onLog: (String) -> Unit = {}
    ): SourceLang {
        val cache = store.findChild(folderUri, ".lang_cache")
            ?: store.findChild(folderUri, ".lang_cache.txt")
        val cachedCode = cache?.let { store.readText(it.uri) }?.trim() ?: ""
        val cached = when (cachedCode) {
            "ZH" -> SourceLang.ZH
            "KO" -> SourceLang.KO
            "EN" -> SourceLang.EN
            "JA" -> SourceLang.JA
            else -> null
        }
        if (cached != null) return cached

        val lang = if (inherited != null) {
            inherited
        } else {
            val sample = sampleTextForLanguage(store, files)
            val detected = detectLanguage(sample)
            onLog("detected language: ${detected.language} (${detected.reason})")
            detected.language
        }

        val doc = cache ?: findOrCreateFile(store, folderUri, ".lang_cache", "application/octet-stream")
        if (doc != null) {
            if (!store.writeText(doc.uri, lang.name)) {
                onLog("⚠️ 言語キャッシュの書き込みに失敗しました: $folderUri")
            }
        } else {
            onLog("⚠️ 言語キャッシュの作成に失敗しました: $folderUri")
        }
        return lang
    }
}