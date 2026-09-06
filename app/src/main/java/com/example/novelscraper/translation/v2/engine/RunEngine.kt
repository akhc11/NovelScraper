package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.AcquireResult
import com.example.novelscraper.translation.v2.domain.ClassifiedFailure
import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.FailureKind
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.LlmRequest
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.ProviderDescriptor
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.GeminiHandler
import com.example.novelscraper.translation.v2.infra.OpenRouterHandler
import com.example.novelscraper.translation.v2.pipeline.BatchOutcome
import com.example.novelscraper.translation.v2.pipeline.DictOptions
import com.example.novelscraper.translation.v2.pipeline.LargeOptions
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.SingleResult
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.TranslateContext
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_1_ZH
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_2_EN
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_3_KO
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_4_NSFW
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_5_LITERAL
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_6_READABLE
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_7_RETRY
import com.example.novelscraper.translation.v2.pipeline.VerifyOptions
import com.example.novelscraper.translation.v2.pipeline.buildProfilePrompt
import com.example.novelscraper.translation.v2.pipeline.buildSystemPrompt
import com.example.novelscraper.translation.v2.pipeline.detectLanguage
import com.example.novelscraper.translation.v2.pipeline.generateDictionary
import com.example.novelscraper.translation.v2.pipeline.matchDictionaryEntries
import com.example.novelscraper.translation.v2.pipeline.ResidualOptions
import com.example.novelscraper.translation.v2.pipeline.resolvePromptOrder
import com.example.novelscraper.translation.v2.pipeline.routeFor
import com.example.novelscraper.translation.v2.pipeline.Route
import com.example.novelscraper.translation.v2.pipeline.splitSingleTextFile
import com.example.novelscraper.translation.v2.pipeline.translateBatch
import com.example.novelscraper.translation.v2.pipeline.translateLarge
import com.example.novelscraper.translation.v2.pipeline.translateSingle
import com.example.novelscraper.translation.v2.pipeline.utf8Bytes
import com.example.novelscraper.translation.v2.pipeline.writeFailed
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class EngineOptions(
    val splitThresholdBytes: Int = 30000,
    val batchMaxFiles: Int = 3,
    val chunkSizeBytes: Int = 27000,
    val maxInputBytes: Int = 200_000_000,
    val tailLines: Int = 20,
    val sizeMinPct: Int = 50,
    val sizeMaxPct: Int = 300,
    val kanaFloor: Double = 0.2,
    val markerEnabled: Boolean = true,
    val maxSameRetries: Int = 2,
    val switchCooldownSec: Int = 15,
    val workerStaggerSec: Long = 0,
    val basePrompts: Map<Int, String> = mapOf(
        1 to V2_PROMPT_1_ZH,
        2 to V2_PROMPT_2_EN,
        3 to V2_PROMPT_3_KO,
        4 to V2_PROMPT_4_NSFW,
        5 to V2_PROMPT_5_LITERAL,
        6 to V2_PROMPT_6_READABLE,
        7 to V2_PROMPT_7_RETRY
    )
)

data class EngineState(
    val isRunning: Boolean = false,
    val statusText: String = "idle",
    val folderName: String = "",
    val fileName: String = "",
    val progress: Pair<Int, Int> = 0 to 0,
    val chunkProgress: Pair<Int, Int> = 0 to 0,
    val logs: List<String> = emptyList()
)

data class RunSummary(val folders: Int, val completedFiles: Int, val totalFiles: Int, val aborted: Boolean)

/**
 * v2実行エンジン。フォルダ巡回・早期スキップ・辞書・ワーカー分配・中止判定を担う。
 * 物理事前分割は扱わない（大ファイルは翻訳時チャンクで処理する）。
 */
class RunEngine(
    private val store: FileStore,
    private val scope: CoroutineScope,
    private val options: EngineOptions = EngineOptions(),
    private val descriptors: Map<String, ProviderDescriptor> = mapOf(
        "gemini" to GEMINI_DESCRIPTOR,
        "openrouter" to OPENROUTER_DESCRIPTOR
    ),
    /** テスト用の差し替え口。null時は内蔵生成を使う */
    private val handlerFactory: ((V2Settings, V2ModelProfile, String) -> ProviderHandler)? = null
) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val stopFlag = AtomicBoolean(false)

    /** Shared send gate, engine lifetime (parity with the old global gate). */
    private val sendGate = V2SendGate()

    fun requestStop() {
        stopFlag.set(true)
    }

    private fun addLog(message: String) {
        _state.update { it.copy(logs = (it.logs + message).takeLast(200)) }
    }

    private fun geminiModels(profiles: List<V2ModelProfile>): List<String> =
        profiles.filter { it.providerId == "gemini" }.map { it.model }

    private fun hasUsableKey(settings: V2Settings): Boolean {
        val profiles = settings.profiles
        if (profiles.isEmpty()) return false
        if (profiles.any { it.providerId == "gemini" } && settings.geminiKeys.any { it.isNotBlank() }) return true
        if (profiles.any { it.providerId == "openrouter" } && settings.openRouterKey.isNotBlank()) return true
        return false
    }

    private fun hasFallbackKey(settings: V2Settings): Boolean {
        return settings.profiles.any { it.providerId == "openrouter" } && settings.openRouterKey.isNotBlank()
    }

    /** 中止判定：翻訳全滅（代替なし）または辞書必須モデルの全滅 */
    private suspend fun shouldAbort(pool: QuotaPool, settings: V2Settings): Boolean {
        val gemini = geminiModels(settings.profiles)
        if (gemini.isNotEmpty() && pool.isExhausted(gemini) && !hasFallbackKey(settings)) return true
        if (settings.dict.enabled && settings.dict.providerId == "gemini") {
            val dictModels = listOfNotNull(
                settings.dict.model.ifBlank { null },
                settings.dict.mergeModel.ifBlank { null }
            ).distinct()
            if (dictModels.isNotEmpty() && pool.isExhausted(dictModels)) return true
        }
        return false
    }

    private fun buildHandler(settings: V2Settings, profile: V2ModelProfile, key: String): ProviderHandler {
        handlerFactory?.let { return it(settings, profile, key) }
        return handlerFor(profile, key, settings)
    }

    private fun handlerFor(profile: V2ModelProfile, key: String, settings: V2Settings): com.example.novelscraper.translation.v2.domain.ProviderHandler {
        return when (profile.providerId) {
            "gemini" -> GeminiHandler(apiKey = key)
            else -> OpenRouterHandler(
                apiKey = key,
                endpoint = settings.openRouterEndpoint,
                reasoningEffort = profile.reasoningEffort,
                reasoningEnabled = profile.reasoningEnabled,
                providerOrder = profile.providerOrder,
                providerAllowFallbacks = profile.providerAllowFallbacks
            )
        }
    }

    private fun cleanseBasic(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\r' -> sb.append('\n')
                ch == '\n' || ch == '\t' -> sb.append(ch)
                ch.isISOControl() -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n")
    }

    suspend fun run(folderUris: List<String>, settings: V2Settings): RunSummary {
        stopFlag.set(false)
        _state.update {
            it.copy(isRunning = true, statusText = "starting", logs = emptyList())
        }
        val meter = CostMeter(settings.cost.maxTokens, settings.cost.maxCost)
        val pool = QuotaPool(settings.geminiKeys.filter { it.isNotBlank() })
        var foldersDone = 0
        var filesDone = 0
        var filesTotal = 0
        try {
            if (!hasUsableKey(settings)) {
                addLog("abort: no usable key (skip split/dict/workers)")
                return RunSummary(0, 0, 0, aborted = true)
            }
            for ((folderIndex, folderUri) in folderUris.withIndex()) {
                if (stopFlag.get() || !coroutineContext.isActive) break
                if (shouldAbort(pool, settings)) {
                    addLog("abort: quota exhausted (skip split/dict/workers)")
                    stopFlag.set(true)
                    break
                }
                val folderName = folderUri.substringAfterLast('/').ifBlank { "folder${folderIndex + 1}" }
                _state.update { it.copy(folderName = folderName, statusText = "folder: $folderName") }
                addLog("folder start: $folderName")
                val (done, total) = processFolder(folderUri, folderName, settings, pool, meter)
                filesDone += done
                filesTotal += total
                foldersDone++
            }
        } catch (e: CancellationException) {
            addLog("stopped by user")
        } catch (e: Exception) {
            addLog("unexpected: ${e.message}")
        } finally {
            pool.reset()
            _state.update { it.copy(isRunning = false, statusText = "stopped/done", fileName = "", chunkProgress = 0 to 0) }
        }
        return RunSummary(foldersDone, filesDone, filesTotal, aborted = stopFlag.get())
    }

    private suspend fun detectOrLoadLanguage(
        outputDirUri: String,
        firstContent: String,
        inherited: SourceLang? = null
    ): SourceLang {
        val cache = store.findChild(outputDirUri, ".lang_cache")
        val cachedCode = cache?.let { store.readText(it.uri) }?.trim() ?: ""
        val cached = when (cachedCode) {
            "ZH" -> SourceLang.ZH
            "KO" -> SourceLang.KO
            "EN" -> SourceLang.EN
            "JA" -> SourceLang.JA
            else -> null
        }
        if (cached != null) return cached
        // Inherited from pre-split ingest: re-detect nothing, just backfill the cache.
        if (inherited != null) {
            val doc = cache ?: store.createFile(outputDirUri, ".lang_cache", "text/plain")
            if (doc != null) store.writeText(doc.uri, inherited.name)
            return inherited
        }
        val detected = detectLanguage(firstContent).language
        val doc = cache ?: store.createFile(outputDirUri, ".lang_cache", "text/plain")
        if (doc != null) store.writeText(doc.uri, detected.name)
        return detected
    }

    /**
     * Physical pre-split pass. Returns accumulated (done, total) when at least
     * one raw file was found (parent direct translation is then skipped),
     * or null when there is nothing to split (normal path continues).
     * One blocked novel never stops the others: each part file translates and
     * resumes independently, so no combine step can get stuck mid-file.
     */
    private suspend fun processPreSplit(
        folderUri: String,
        folderName: String,
        settings: V2Settings,
        pool: QuotaPool,
        meter: CostMeter
    ): Pair<Int, Int>? {
        val rawFiles = store.children(folderUri)
            .filter {
                !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) &&
                    !it.name.startsWith("part_", ignoreCase = true)
            }
            .sortedBy { it.name }
        if (rawFiles.isEmpty()) return null
        val splitRoot = store.findChild(folderUri, "分割済み")
            ?: store.createDir(folderUri, "分割済み")
            ?: run {
                addLog("pre-split root failed: $folderName")
                return 0 to 0
            }
        var done = 0
        var total = 0
        for ((rawIndex, raw) in rawFiles.withIndex()) {
            if (stopFlag.get() || !coroutineContext.isActive) break
            addLog("pre-split novel ${rawIndex + 1}/${rawFiles.size}: ${raw.name}")
            val result = splitSingleTextFile(
                store = store,
                fileUri = raw.uri,
                fileName = raw.name,
                splitRootUri = splitRoot.uri,
                splitSizeChars = settings.split.splitSizeChars,
                declared = V2DeclaredEncoding.parseOrNull(settings.split.inputEncoding),
                stopped = { stopFlag.get() || !scope.isActive },
                log = { addLog(it) }
            )
            if (result != null && !stopFlag.get() && coroutineContext.isActive) {
                val lang = detectLanguage(result.sampleText).language
                val subName = result.subfolderUri.substringAfterLast('/').ifBlank { result.novelName }
                addLog("pre-split translate subfolder: $subName")
                val (d, t) = processFolder(result.subfolderUri, subName, settings, pool, meter, lang)
                done += d
                total += t
            }
        }
        return done to total
    }

    private suspend fun processFolder(
        folderUri: String,
        folderName: String,
        settings: V2Settings,
        pool: QuotaPool,
        meter: CostMeter,
        inheritedLang: SourceLang? = null
    ): Pair<Int, Int> {
        if (shouldAbort(pool, settings)) {
            addLog("abort before folder work: $folderName")
            stopFlag.set(true)
            return 0 to 0
        }
        // Physical pre-split first (same order as the frozen spec):
        // each raw file is split and its subfolder translated immediately,
        // direct translation of the parent is skipped afterwards.
        if (settings.split.enabled && inheritedLang == null) {
            val splitResult = processPreSplit(folderUri, folderName, settings, pool, meter)
            if (splitResult != null) return splitResult
        }
        val outSubDir = settings.limits.outputSubDir.ifBlank { "翻訳完了_LLM" }
        val files = store.children(folderUri)
            .filter { !it.isDirectory && it.name.endsWith(".txt") && !it.name.endsWith(".failed") }
            .sortedBy { it.name }
        if (files.isEmpty()) {
            addLog("no .txt files: $folderName")
            return 0 to 0
        }
        val outputDir = store.findChild(folderUri, outSubDir)
            ?: store.createDir(folderUri, outSubDir)
            ?: run {
                addLog("output dir failed: $outSubDir")
                return 0 to 0
            }

        val existing = Collections.synchronizedSet(mutableSetOf<String>())
        var zeroBytes = 0
        for (doc in store.children(outputDir.uri)) {
            if (!doc.isDirectory && doc.name.endsWith(".txt") && doc.length == 0L) {
                zeroBytes++
                continue
            }
            existing.add(doc.name)
        }
        if (zeroBytes > 0) addLog("zero-byte outputs reprocess: $zeroBytes")

        val total = files.size
        var preCompleted = 0
        for (f in files) {
            val hasWork = existing.contains(".parts_${f.name}")
            if (!hasWork && (existing.contains(f.name) || existing.contains("${f.name}.failed"))) {
                preCompleted++
            }
        }
        if (preCompleted >= total) {
            _state.update { it.copy(progress = total to total) }
            addLog("skip completed folder: $folderName ($total/$total)")
            return total to total
        }

        val firstContent = store.readText(files.first().uri) ?: ""
        val sourceLang = detectOrLoadLanguage(outputDir.uri, firstContent, inheritedLang)

        // 辞書
        var novelDict: NovelDict? = null
        if (settings.dict.enabled) {
            val existingDict = store.findChild(folderUri, "dictionary.json")
            val dictJson = existingDict?.let { store.readText(it.uri) } ?: ""
            novelDict = parseDictJson(dictJson)
            if (novelDict == null) {
                novelDict = buildDictionary(folderUri, files, settings, pool, meter)
            }
            if (novelDict == null) {
                addLog("dict incomplete, skip folder: $folderName")
                return preCompleted to total
            }
        }

        val completed = AtomicInteger(preCompleted)
        _state.update { it.copy(progress = completed.get() to total) }
        val profiles = settings.profiles.ifEmpty {
            listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash"))
        }
        val autoMap = mapOf(
            SourceLang.ZH to settings.promptSelection.autoOrderZh,
            SourceLang.KO to settings.promptSelection.autoOrderKo,
            SourceLang.EN to settings.promptSelection.autoOrderEn
        )
        val profilePromptOrders: Map<String, List<Int>> = profiles.associate { profile ->
            val order = resolvePromptOrder(
                sourceLang = sourceLang,
                profileOrder = profile.promptOrder,
                useCustom = profile.useCustomPromptOrder,
                autoEnabled = settings.promptSelection.autoEnabled,
                autoMap = autoMap
            )
            profile.id to order
        }
        val primary = profiles.first()
        val primaryOrder = profilePromptOrders[primary.id] ?: listOf(1, 1)
        val promptOrder = primaryOrder
        val claims = Collections.synchronizedSet(mutableSetOf<String>())
        val workerCount = settings.limits.parallelWorkers.coerceIn(1, 6)
        val batchMaxBytes = (options.splitThresholdBytes * 0.9).toInt().coerceAtLeast(3000)

        val jobs = mutableListOf<Deferred<Unit>>()
        for (wId in 1..workerCount) {
            val claimed = if (profiles.any { it.providerId == "gemini" }) {
                pool.claimNew(geminiModels(profiles))
            } else {
                0 to ""
            }
            if (claimed == null) {
                addLog("worker #$wId skipped (no key)")
                continue
            }
            val rotation = Rotation(
                workerId = wId,
                profiles = profiles,
                pool = pool,
                keyIndex = claimed.first,
                key = claimed.second,
                descriptors = mapOf("gemini" to GEMINI_DESCRIPTOR, "openrouter" to OPENROUTER_DESCRIPTOR),
                handlerFactory = { profile, key -> buildHandler(settings, profile, key) },
                openRouterKey = settings.openRouterKey,
                switchCooldownSec = 15,
                maxSameRetries = options.maxSameRetries,
                sendGate = sendGate,
                sendGateIntervalMs = maxOf(settings.limits.requestDelaySec * 1000L, 10_000L),
                stopped = { stopFlag.get() },
                meter = meter,
                log = { addLog("[W#$wId] $it") }
            )
            jobs.add(scope.async(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    if (wId > 1 && options.workerStaggerSec > 0) {
                        delay(options.workerStaggerSec * 1000L * (wId - 1))
                    }
                    runWorker(
                        wId, files, outputDir.uri, existing, claims,
                        settings, rotation, novelDict,
                        completed, total, batchMaxBytes, sourceLang, promptOrder,
                        profiles, profilePromptOrders
                    )
                } finally {
                    rotation.release()
                }
            })
        }
        jobs.awaitAll()
        return completed.get() to total
    }

    private fun parseDictJson(raw: String): NovelDict? {
        if (raw.isBlank()) return null
        return try {
            com.example.novelscraper.translation.v2.pipeline.parseNovelDict(raw)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun buildDictionary(
        folderUri: String,
        files: List<com.example.novelscraper.translation.v2.infra.VDoc>,
        settings: V2Settings,
        pool: QuotaPool,
        meter: CostMeter
    ): NovelDict? {
        val dict = settings.dict
        val workDir = store.findChild(folderUri, ".dict_building")
            ?: store.createDir(folderUri, ".dict_building")
            ?: return null
        val texts = mutableListOf<Pair<String, String>>()
        for (f in files) {
            if (stopFlag.get()) return null
            val raw = store.readText(f.uri) ?: continue
            val clean = cleanseBasic(raw).trim()
            if (clean.isNotBlank()) texts.add(f.name to clean)
        }
        val scopes = listOf(dict.model.ifBlank { "dict" })
        val dictCall: suspend (String, String, String) -> LlmResult = { model, prompt, text ->
            pooledCall(pool, scopes, settings.dict.cooldown429Sec) { key ->
                val profile = V2ModelProfile(providerId = dict.providerId, model = model)
                buildHandler(settings, profile, key).call(
                    LlmRequest(
                        dict.providerId, model, prompt, text,
                        com.example.novelscraper.translation.v2.engine.resolveProfileOptions(
                            profile.copy(thinkingLevel = dict.thinkingLevel),
                            mapOf("gemini" to GEMINI_DESCRIPTOR, "openrouter" to OPENROUTER_DESCRIPTOR)[dict.providerId]
                        )
                    )
                )
            }
        }
        val result = generateDictionary(
            store, workDir.uri, texts, dictCall,
            DictOptions(
                model = dict.model,
                mergeModel = dict.mergeModel,
                thinkingLevel = dict.thinkingLevel,
                maxFiles = dict.totalParts,
                maxBatchBytes = dict.batchMaxBytes,
                maxTotalScanBytes = dict.maxTotalScanBytes,
                parallelism = (dict.workerCount * dict.concurrencyPerWorker).coerceIn(1, 30),
                maxRetriesPerBatch = 4
            )
        ) { addLog(it) }
        if (result != null) {
            publishDictionary(folderUri, workDir.uri)
        }
        return result
    }

    /**
     * 確定物の公開：作業所の dictionary.json をフォルダ直下へ写し、作業所を掃除する。
     * 技術的根拠1行：読込点（folder/dictionary.json）と保存点（.dict_building下）の不一致では
     * 次回も再生成になるため、確定時のみ公開＋掃除する（凍結仕様§8。保留時は再開用に残す）。
     */
    private suspend fun publishDictionary(folderUri: String, workDirUri: String) {
        val finalized = store.findChild(workDirUri, "dictionary.json")?.let { store.readText(it.uri) }
        if (finalized.isNullOrBlank()) {
            addLog("dict: finalized artifact missing, keep workdir")
            return
        }
        val dest = store.findChild(folderUri, "dictionary.json")
            ?: store.createFile(folderUri, "dictionary.json", "application/json")
        if (dest == null || !store.writeText(dest.uri, finalized)) {
            addLog("dict: publish failed, keep workdir")
            return
        }
        if (!store.deleteRecursively(workDirUri)) {
            addLog("dict: workdir cleanup failed")
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
            if (stopFlag.get()) {
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

    private suspend fun runWorker(
        workerId: Int,
        files: List<com.example.novelscraper.translation.v2.infra.VDoc>,
        outputDirUri: String,
        existing: MutableSet<String>,
        claims: MutableSet<String>,
        settings: V2Settings,
        rotation: Rotation,
        novelDict: NovelDict?,
        completed: AtomicInteger,
        total: Int,
        batchMaxBytes: Int,
        sourceLang: SourceLang,
        promptOrder: List<Int>,
        profiles: List<V2ModelProfile>,
        profilePromptOrders: Map<String, List<Int>>
    ) {
        fun claim(name: String): Boolean = synchronized(claims) { claims.add(name) }
        fun unclaim(name: String) {
            synchronized(claims) { claims.remove(name) }
        }
        fun bump(name: String) {
            val done = completed.incrementAndGet()
            _state.update { it.copy(progress = done to total, fileName = name) }
        }
        val (sizeMin, sizeMax) = when (sourceLang) {
            SourceLang.ZH -> settings.sizeRatios.zhMin to settings.sizeRatios.zhMax
            SourceLang.KO -> settings.sizeRatios.koMin to settings.sizeRatios.koMax
            SourceLang.EN -> settings.sizeRatios.enMin to settings.sizeRatios.enMax
            SourceLang.JA -> settings.sizeRatios.jaMin to settings.sizeRatios.jaMax
        }
        val verify = VerifyOptions(
            sizeMinPct = sizeMin,
            sizeMaxPct = sizeMax,
            kanaFloor = options.kanaFloor,
            markerEnabled = options.markerEnabled,
            residual = ResidualOptions(sourceLang)
        )
        val allBasePrompts = options.basePrompts + settings.customPrompts
        val primaryPromptNum = promptOrder.firstOrNull() ?: 1
        val ctx = TranslateContext(
            basePrompts = allBasePrompts,
            promptOrder = promptOrder,
            driverNames = listOf("w$workerId"),
            dictionary = novelDict,
            verify = verify,
            call = { _, prompt, source ->
                val profilePrompts = profiles.associate { profile ->
                    val order = profilePromptOrders[profile.id] ?: promptOrder
                    profile.id to order.map { targetPromptNum ->
                        buildProfilePrompt(
                            originalPrompt = prompt,
                            basePrompts = allBasePrompts,
                            originalPromptNum = primaryPromptNum,
                            targetPromptNum = targetPromptNum
                        )
                    }
                }
                rotation.execute(listOf(prompt), source, profilePrompts)
            },
            maxSameRetries = 0,
            stopped = { stopFlag.get() },
            meter = null,
            log = { addLog("[W#$workerId] $it") }
        )
        // Previous-story raw tail, sliding window of 1 in file order.
        // Workers run concurrently, so a previous *translation* is generally
        // unavailable; the raw tail of the preceding file is used instead.
        // Texts are reused from memory (no re-read); a cross-worker miss yields null.
        var prevTailName: String? = null
        var prevTailText: String? = null
        fun prevTailFor(index: Int): String? {
            if (!settings.prevContext.enabled) return null
            if (index <= 0) return null
            return if (prevTailName == files[index - 1].name) prevTailText else null
        }
        fun rememberTail(name: String, content: String) {
            if (!settings.prevContext.enabled) return
            prevTailName = name
            prevTailText = content.lines()
                .takeLast(settings.prevContext.lines.coerceIn(1, 100))
                .joinToString("\n")
        }
        for ((fileIdx, file) in files.withIndex()) {
            if (stopFlag.get() || rotation.exhausted) break
            val fileName = file.name
            val hasWork = existing.contains(".parts_${fileName}")
            if (!hasWork && (existing.contains(fileName) || existing.contains("$fileName.failed"))) continue
            if (!claim(fileName)) continue

            try {
                val raw = store.readText(file.uri)
                if (raw == null) {
                    addLog("[W#$workerId] read failed: $fileName")
                    continue
                }
                val content = cleanseBasic(raw)
                if (content.isBlank()) {
                    val out = store.findChild(outputDirUri, fileName)
                        ?: store.createFile(outputDirUri, fileName, "text/plain")
                    if (out != null && store.writeText(out.uri, "")) existing.add(fileName)
                    rememberTail(fileName, content)
                    bump(fileName)
                    continue
                }
                val contentBytes = utf8Bytes(content)

                if (contentBytes > options.splitThresholdBytes) {
                    val workDir = store.findChild(outputDirUri, ".parts_${fileName}")
                        ?: store.createDir(outputDirUri, ".parts_${fileName}")
                    if (workDir == null) {
                        addLog("[W#$workerId] workdir failed: $fileName")
                    } else {
                        addLog("[W#$workerId] large: $fileName (${contentBytes}B)")
                        val ok = translateLarge(
                            store, workDir.uri, outputDirUri, fileName, content, ctx,
                            LargeOptions(chunkSizeBytes = options.chunkSizeBytes, maxInputBytes = options.maxInputBytes),
                            prevSourceTail = prevTailFor(fileIdx),
                            onChunkProgress = { cur, total ->
                                _state.update { it.copy(chunkProgress = cur to total) }
                            }
                        )
                        _state.update { it.copy(chunkProgress = 0 to 0) }
                        if (ok) existing.add(fileName)
                    }
                    rememberTail(fileName, content)
                    bump(fileName)
                    continue
                }

                // バッチ束ね（後続のみ・最大件数・合計バイト上限）
                val batch = mutableListOf(file to content)
                var batchBytes = contentBytes
                for (nextIdx in fileIdx + 1 until files.size) {
                    if (batch.size >= options.batchMaxFiles) break
                    val next = files[nextIdx]
                    val nextName = next.name
                    if (existing.contains(nextName) || existing.contains("$nextName.failed")) continue
                    if (existing.contains(".parts_${nextName}")) continue
                    if (!claim(nextName)) continue
                    val nextRaw = store.readText(next.uri)
                    if (nextRaw == null) {
                        unclaim(nextName)
                        continue
                    }
                    val nextClean = cleanseBasic(nextRaw)
                    if (nextClean.isBlank()) {
                        val out = store.findChild(outputDirUri, nextName)
                            ?: store.createFile(outputDirUri, nextName, "text/plain")
                        if (out != null && store.writeText(out.uri, "")) existing.add(nextName)
                        bump(nextName)
                        unclaim(nextName)
                        continue
                    }
                    val nextBytes = utf8Bytes(nextClean)
                    if (nextBytes > options.splitThresholdBytes || batchBytes + nextBytes > batchMaxBytes) {
                        unclaim(nextName)
                        break
                    }
                    batch.add(next to nextClean)
                    batchBytes += nextBytes
                }
                try {
                    if (batch.size > 1) {
                        addLog("[W#$workerId] batch x${batch.size}: ${batch.map { it.first.name }}")
                        val outcome = translateBatch(
                            store, outputDirUri,
                            batch.map { it.first.name to it.second },
                            ctx,
                            prevSourceTail = prevTailFor(fileIdx)
                        )
                        if (outcome.settled > 0) {
                            val done = completed.addAndGet(outcome.settled)
                            _state.update { it.copy(progress = done to total, fileName = fileName) }
                        }
                        // 保存系はstage内で完結するため、既存集合を実体と同期する
                        for (item in batch) {
                            val n = item.first.name
                            if (store.findChild(outputDirUri, n) != null) existing.add(n)
                            if (store.findChild(outputDirUri, "$n.failed") != null) existing.add("$n.failed")
                        }
                        if (outcome.configBlocked) return
                    } else {
                        addLog("[W#$workerId] single: $fileName (${contentBytes}B)")
                        when (val r = translateSingle(content, ctx, prevSourceTail = prevTailFor(fileIdx))) {
                            is SingleResult.Translated -> {
                                val out = store.findChild(outputDirUri, fileName)
                                    ?: store.createFile(outputDirUri, fileName, "text/plain")
                                if (out != null && store.writeText(out.uri, r.text)) {
                                    existing.add(fileName)
                                }
                            }
                            is SingleResult.Failed -> writeFailed(store, outputDirUri, fileName, content) { addLog(it) }
                            is SingleResult.ConfigOnly -> {
                                addLog("[W#$workerId] config only, skip .failed: $fileName")
                            }
                            is SingleResult.Stopped -> return
                        }
                        bump(fileName)
                    }
                    for (item in batch) rememberTail(item.first.name, item.second)
                } finally {
                    for (item in batch) unclaim(item.first.name)
                }
            } finally {
                unclaim(fileName)
            }
        }
    }
}
