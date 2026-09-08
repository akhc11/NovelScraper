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
import com.example.novelscraper.translation.v2.domain.isQuotaLike
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.DictOptions
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.cleanseBasic
import com.example.novelscraper.translation.v2.pipeline.generateDictionary
import com.example.novelscraper.translation.v2.pipeline.parseNovelDictLenient
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
        return try {
            val dict = settings.dict
            val workDir = store.findChild(folderUri, ".dict_building")
                ?: store.createDir(folderUri, ".dict_building")
                ?: run {
                    log("❌ 辞書作業フォルダ（.dict_building）の作成に失敗しました")
                    return null
                }
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
                        val res = buildHandler(settings, profile, settings.openRouterKey).call(dictRequest(settings.openRouterKey))
                        if (res is LlmResult.Success && dict.requestDelaySec > 0) {
                            delay(dict.requestDelaySec * 1000L)
                        } else if (res is LlmResult.Failure && res.failure.kind.isQuotaLike()) {
                            val waitSec = res.failure.retryAfterSec?.toLong()?.coerceIn(0L, TranslationLimits.RETRY_AFTER_MAX_SEC)
                                ?: dict.cooldown429Sec.toLong().coerceAtLeast(0L)
                            if (waitSec > 0) {
                                log("  ⏳ 辞書生成: ${res.failure.kind}のため${waitSec}秒待機します")
                                delay(waitSec * 1000L)
                            } else {
                                log("  ⚡ 辞書生成: ${res.failure.kind}のため即座に再送します")
                            }
                        }
                        res
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LlmResult.Failure(ClassifiedFailure(FailureKind.RETRYABLE_AFTER, note = "io:${e.message}"))
                    }
                } else {
                    pooledCall(pool, scopes, settings.dict.cooldown429Sec, settings.dict.requestDelaySec) { key ->
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
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("❌ 辞書生成処理でエラー (${e::class.java.simpleName}: ${e.message})")
            null
        }
    }

    /**
     * 確定物の公開：作業所の dictionary.json をフォルダ直下へ写し、作業所を掃除する。
     * 技術的根拠1行：読込点（folder/dictionary.json）と保存点（.dict_building下）の不一致では
     * 次回も再生成になるため、確定時のみ公開＋掃除する（凍結仕様§8。保留時は再開用に残す）。
     */
    suspend fun publish(folderUri: String, workDirUri: String) {
        try {
            val finalized = store.findChild(workDirUri, "dictionary.json")?.let { store.readText(it.uri) }
            if (finalized.isNullOrBlank()) {
                log("📖 辞書公開: 確定物がないため作業所を残します")
                return
            }
            val dest = store.findChild(folderUri, "dictionary.json")
                ?: store.createFile(folderUri, "dictionary.json", "application/json")
            if (dest == null || !store.writeText(dest.uri, finalized)) {
                log("📖 辞書公開: 書込失敗のため作業所を残します")
                return
            }
            if (!store.deleteRecursively(workDirUri)) {
                log("📖 辞書公開: 作業所の掃除に失敗しました")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("📖 辞書公開: 保存処理中にエラー (${e::class.java.simpleName}: ${e.message})")
        }
    }

    private suspend fun pooledCall(
        pool: QuotaPool,
        scopes: List<String>,
        cooldownSec: Int,
        requestDelaySec: Int,
        block: suspend (key: String) -> LlmResult
    ): LlmResult {
        var tries = 0
        val maxTries = 8
        while (tries++ < maxTries) {
            if (stopped()) {
                return LlmResult.Failure(ClassifiedFailure(FailureKind.FATAL, note = "stopped"))
            }
            when (val acq = pool.acquire(scopes, busyWaitMs = 200L)) {
                is AcquireResult.Ready -> {
                    try {
                        val result = block(acq.credential)
                        if (result is LlmResult.Failure && result.failure.kind.isQuotaLike()) {
                            val scope = scopes.firstOrNull() ?: "shared"
                            pool.reportQuota(
                                acq.credentialIndex, scope,
                                result.failure.kind == FailureKind.QUOTA_DAILY,
                                cooldownSec
                            )
                            continue
                        }
                        if (result is LlmResult.Success && requestDelaySec > 0) {
                            delay(requestDelaySec * 1000L)
                        }
                        return result
                    } finally {
                        pool.release(acq.credentialIndex)
                    }
                }
                is AcquireResult.Wait -> {
                    if (acq.waitMillis >= 1000L) {
                        val waitSec = (acq.waitMillis / 1000).coerceAtLeast(1)
                        log("  ⏳ 辞書生成: 429制限のため${waitSec}秒待機します")
                        delay(acq.waitMillis)
                    } else {
                        delay(acq.waitMillis)
                    }
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
        /**
         * 公開辞書の読込。空辞書も有効として再利用する（毎回の再生成ループ防止）。
         * 構文破損時のみ null（再生成）。
         */
        fun parseDictJson(raw: String): NovelDict? {
            if (raw.isBlank()) return null
            return try {
                parseNovelDictLenient(raw)
            } catch (_: Exception) {
                null
            }
        }
    }
}
