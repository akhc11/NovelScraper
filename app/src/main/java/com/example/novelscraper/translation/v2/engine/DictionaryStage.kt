package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.DictResolveResult
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings

/**
 * 辞書解決ステージ。
 * ターゲット小説フォルダにおける既存辞書（dictionary.json）の読込、
 * またはサンプリング生成・保存を行う。
 * 技術的根拠1行：辞書生成失敗時のトークン浪費防止（中断）判定をカプセル化し、呼び出し元の直線性を保証する。
 */
object DictionaryStage {

    suspend fun resolveDictionary(
        store: FileStore,
        folderUri: String,
        folderName: String,
        files: List<VDoc>,
        settings: V2Settings,
        pool: QuotaPool,
        buildHandler: (V2Settings, V2ModelProfile, String) -> ProviderHandler,
        stopped: () -> Boolean,
        onLog: (String) -> Unit
    ): DictResolveResult {
        if (!settings.dict.enabled) {
            return DictResolveResult.Ready(null)
        }
        val existingDict = store.findChild(folderUri, "dictionary.json")
        val dictJson = existingDict?.let { store.readText(it.uri) } ?: ""
        var novelDict = DictionaryBuilder.parseDictJson(dictJson)
        if (novelDict == null) {
            novelDict = DictionaryBuilder(
                store = store,
                buildHandler = buildHandler,
                stopped = stopped,
                log = onLog
            ).build(folderUri, files, settings, pool)
        }
        return if (novelDict != null) {
            DictResolveResult.Ready(novelDict)
        } else {
            DictResolveResult.Aborted("⚠️ 辞書未完成のため「$folderName」の翻訳を中断しました（トークン浪費防止）")
        }
    }
}