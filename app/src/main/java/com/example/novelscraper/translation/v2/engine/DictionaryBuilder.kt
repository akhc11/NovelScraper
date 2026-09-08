package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.AcquireResult
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.DictOptions
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.cleanseBasic
import com.example.novelscraper.translation.v2.pipeline.generateDictionary
import com.example.novelscraper.translation.v2.pipeline.parseNovelDict
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 登場人物辞書の生成・公開を担う。RunEngineからはフォルダ処理の辞書段階だけを委譲される。
 * ハンドラー生成は呼出側の責務（テスト差替え口と本番束縛を共有する）。
 */
class DictionaryBuilder(
    private val store: FileStore,
    private val buildHandler: (V2Settings, V2ModelProfile, String) -> ProviderHandler,
    private val stopped: () -> Boolean = { false },
    private val log: (String) -> Unit = {}
) {
    suspend fun build(
        folderUri: String,
        files: List<VDoc>,
        settings: V2Settings,
        pool: QuotaPool
    ): NovelDict? {
        val dict = settings.dict
        val workDir = store.findChild(folderUri, ".dict_building")
            ?: store.createDir(folderUri, ".dict_building")
            ?: return null
        // 技術的根拠: 1,000ファイル超の長編小説フォルダでメモリ枯渇（OOM）を起こさないよう、
        // ファイル名だけ先に渡し、本文はサンプリング後に1件ずつ遅延読込する
        val docByName = files.associateBy { it.name }
        val scopes = listOf(dict.model.ifBlank { "dict" })
        // 技術的根拠1行：OpenRouterは単一キー共有でGeminiプールを使わない（空プールでの誤枯渇を防ぐ。送って作って統合するだけ）。
        val dictCall: suspend (String, String, String) -> LlmResult = { model, prompt, text ->
            // 技術的根拠1行：辞書設定のproviderOrder/allowFallbacksが無視されるとOpenRouterの振分け指定が死に設定になるため引継ぐ（解決はhandlerFor側）。
            val profile = V2ModelProfile(
                providerId = dict.providerId,
                model = model,
                providerOrder = dict.providerOrder,
                providerAllowFallbacks = dict.providerAllowFallbacks
            )
            fun dictRequest(key: String): LlmRequest = LlmRequest(
                dict.providerId, model, prompt, text,
                resolveProfileOptions(
                    profile.copy(thinkingLevel = dict.thinkingLevel),
                    dict.providerId.toProviderId()?.let {
                        mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR, ProviderId.OPENROUTER to OPENROUTER_DESCRIPTOR)[it]
                    }
                )
            )
            if (dict.providerId.toProviderId() == ProviderId.OPENROUTER) {
                try {
                    buildHandler(settings, profile, settings.openRouterKey).call(dictRequest(settings.openRouterKey))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LlmResult.Failure(ClassifiedFailure(FailureKind.RETRYABLE_AFTER, note = "io:${e.message}"))
                }
            } else {
                pooledCall(pool, scopes, settings.dict.cooldown429Sec) { key ->
                    buildHandler(settings, profile, key).call(dictRequest(key))
                }
            }
        }
        val result = generateDictionary(
            store, workDir.uri, files.map { it.name },
            readText = { name ->
                if (stopped()) null
                else docByName[name]?.let { store.readText(it.uri) }
                    ?.let { cleanseBasic(it).trim() }
                    ?.takeIf { it.isNotBlank() }
            },
            dictCall,
            DictOptions(
                model = dict.model,
                mergeModel = dict.mergeModel,
                thinkingLevel = dict.thinkingLevel,
                maxFiles = dict.totalParts,
                maxBatchBytes = dict.batchMaxBytes,
                maxTotalScanBytes = dict.maxTotalScanBytes,
                parallelism = (dict.workerCount * dict.concurrencyPerWorker).coerceIn(
                    TranslationLimits.DICT_PARALLELISM_RANGE.first,
                    TranslationLimits.DICT_PARALLELISM_RANGE.last
                ),
                maxRetriesPerBatch = 4
            ),
            log = log
        )
        if (result != null) {
            publish(folderUri, workDir.uri)
        }
        return result
    }

    /**
     * 確定物の公開：作業所の dictionary.json をフォルダ直下へ写し、作業所を掃除する。
     * 技術的根拠1行：読込点（folder/dictionary.json）と保存点（.dict_building下）の不一致では
     * 次回も再生成になるため、確定時のみ公開＋掃除する（凍結仕様§8。保留時は再開用に残す）。
     */
    suspend fun publish(folderUri: String, workDirUri: String) {
        val finalized = store.findChild(workDirUri, "dictionary.json")?.let { store.readText(it.uri) }
        if (finalized.isNullOrBlank()) {
            log("dict: finalized artifact missing, keep workdir")
            return
        }
        val dest = store.findChild(folderUri, "dictionary.json")
            ?: store.createFile(folderUri, "dictionary.json", "application/json")
        if (dest == null || !store.writeText(dest.uri, finalized)) {
            log("dict: publish failed, keep workdir")
            return
        }
        if (!store.deleteRecursively(workDirUri)) {
            log("dict: workdir cleanup failed")
        }
    }

    private suspend fun pooledCall(
        pool: QuotaPool,
        scopes: List<String>,
        cooldownSec: Int,
        block: suspend (key: String) -> LlmResult
    ): LlmResult {
        var tries = 0
        val maxTries = 8
        while (tries++ < maxTries) {
            if (stopped()) {
                return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "stopped"))
            }
            when (val acq = pool.acquire(scopes)) {
                is AcquireResult.Ready -> {
                    try {
                        val result = block(acq.credential)
                        if (result is LlmResult.Failure &&
                            (result.failure.kind == FailureKind.QUOTA_DAILY ||
                                result.failure.kind == FailureKind.QUOTA_MINUTE)
                        ) {
                            val scope = scopes.firstOrNull() ?: "shared"
                            pool.reportQuota(
                                acq.credentialIndex, scope,
                                result.failure.kind == FailureKind.QUOTA_DAILY,
                                cooldownSec
                            )
                            continue
                        }
                        return result
                    } finally {
                        pool.release(acq.credentialIndex)
                    }
                }
                is AcquireResult.Wait -> {
                    delay(acq.waitMillis.coerceAtMost(30000L))
                    continue
                }
                is AcquireResult.Exhausted -> {
                    return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "pool-exhausted"))
                }
            }
        }
        return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "pool-cap"))
    }

    companion object {
        fun parseDictJson(raw: String): NovelDict? {
            if (raw.isBlank()) return null
            return try {
                parseNovelDict(raw)
            } catch (_: Exception) {
                null
            }
        }
    }
}
