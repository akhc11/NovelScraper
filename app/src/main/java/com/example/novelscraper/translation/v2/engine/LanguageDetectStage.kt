package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.common.ingest.IngestResult
import com.example.novelscraper.translation.common.ingest.TextIngest
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.detectLanguage

/**
 * 言語判定ステージ（読み取り専用の検出のみ）。
 * 永続化の単一所有者は [LangCacheStore]。このファイルにキャッシュファイル名リテラルを持たない。
 *
 * 構造的根拠：検出（読み取り専用）と永続化（outputDir への書き込み）を分離し、
 * 入力フォルダへの書き込みを物理的に不可能にすることで二重生成を構造的に排除する。
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
     * 言語判定（読み取り専用）。キャッシュへの書き込みは一切行わない。
     * 優先順: outputDir のキャッシュ → 入力フォルダのキャッシュ（後方互換）→ inherited → テキスト検出。
     *
     * 技術的根拠1行：書き込みを [LangCacheStore] に完全分離することで、
     * processFolder 時点（outputDir 未作成）に入力フォルダへ書いてしまう問題を構造的に排除する。
     */
    suspend fun detectLanguage(
        store: FileStore,
        inputFolderUri: String,
        files: List<VDoc>,
        outputDirUri: String? = null,
        inherited: SourceLang? = null,
        onLog: (String) -> Unit = {}
    ): SourceLang {
        // 1. outputDir のキャッシュを優先（再実行時の高速パス）
        if (outputDirUri != null) {
            val cached = LangCacheStore.load(store, outputDirUri, onLog)
            if (cached != null) return cached
        }

        // 2. 入力フォルダのキャッシュ（後方互換：旧バージョンからのマイグレーション）
        val inputCached = LangCacheStore.load(store, inputFolderUri, onLog)
        if (inputCached != null) return inputCached

        // 3. 親から継承
        if (inherited != null) return inherited

        // 4. テキストサンプリングで検出
        val sample = sampleTextForLanguage(store, files)
        val detected = detectLanguage(sample)
        onLog("detected language: ${detected.language} (${detected.reason})")
        return detected.language
    }
}