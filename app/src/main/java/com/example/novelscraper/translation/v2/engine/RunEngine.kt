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
import com.example.novelscraper.translation.v2.domain.resolveOpenRouterParams
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
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
    /** 現在未使用（しきい値は目標出力からの逆算値を用いる）。将来の固定値運用のために残す */
    val splitThresholdBytes: Int = 30000,
    val batchMaxFiles: Int = 3,
    /** 現在未使用（チャンク幅は逆算値の90%を用いる）。将来の固定値運用のために残す */
    val chunkSizeBytes: Int = 27000,
    /** チャンク翻訳に渡す上限。これを超える入力はスキップ確定（.failed保存）する */
    val maxInputBytes: Int = 1_000_000,
    /** 現在未使用（末尾行数は設定の前文脈行数を用いる）。将来の固定値運用のために残す */
    val tailLines: Int = 20,
    /** 現在未使用（サイズ比は設定の言語別比率を用いる）。将来の固定値運用のために残す */
    val sizeMinPct: Int = 50,
    val sizeMaxPct: Int = 300,
    val kanaFloor: Double = 0.2,
    val markerEnabled: Boolean = true,
    val maxSameRetries: Int = 2,
    val switchCooldownSec: Int = 15,
    val workerStaggerSec: Long = 0,
    /** 送信間隔の下限。0時は設定の要求間隔（requestDelaySec）をそのまま使う */
    val minSendIntervalMs: Long = 0L,
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
 * v2実行エンジン。フォルダ巡回・早期スキップ・事前物理分割・辞書・ワーカー分配・中止判定を担う。
 *
 * 【物理事前分割と実行時チャンク分割の役割分担（設計根拠）】:
 * ・事前物理分割 (PreSplit): 超巨大な生テキストファイルを適度なサイズ（例: 7,000文字単位）に物理分割する。
 *   実行時チャンク分割は1チャンクでも失敗（.failed）すると結合されずファイル全体が未完了となるため、
 *   あらかじめ物理分割しておくことで、万一の失敗の影響をそのパート単体に局所化し、
 *   他のパートは確実に完了・保存できるようにする（耐障害性と進捗保護の担保）。
 * ・実行時チャンク分割 (translateLarge): 分割後のパートや中規模ファイルが目標出力からの逆算上限
 *   （言語・モデルにより約26〜45KB）を超える場合に、安全にインメモリ分割翻訳してストリーミング結合する。
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
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _state.update { it.copy(logs = (it.logs + "[$time] $message").takeLast(200)) }
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

    /** 中止判定：Gemini枠の枯渇（OpenRouter代替なし）または辞書用Geminiモデルの枯渇。OpenRouter単独構成はここでは止めない */
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
            else -> {
                val resolved = resolveOpenRouterParams(profile)
                OpenRouterHandler(
                    apiKey = key,
                    endpoint = settings.openRouterEndpoint,
                    reasoningEffort = resolved.reasoningEffort,
                    reasoningEnabled = resolved.reasoningEnabled,
                    providerOrder = resolved.providerOrder,
                    providerAllowFallbacks = resolved.providerAllowFallbacks
                )
            }
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

    companion object {
        fun extractReadableFolderName(uriString: String): String {
            return try {
                val decoded = java.net.URLDecoder.decode(uriString, "UTF-8")
                val afterTree = if (decoded.contains("/tree/")) decoded.substringAfter("/tree/") else decoded
                val afterDoc = if (afterTree.contains("/document/")) afterTree.substringAfter("/document/") else afterTree
                val clean = afterDoc.substringAfterLast(':').substringAfterLast('/')
                clean.ifBlank { "フォルダ" }
            } catch (_: Exception) {
                uriString.substringAfterLast('/').substringAfterLast(':').ifBlank { "フォルダ" }
            }
        }
    }

    suspend fun run(folderUris: List<String>, settings: V2Settings): RunSummary {
        return runWithNames(folderUris.map { it to extractReadableFolderName(it) }, settings)
    }

    suspend fun runWithNames(folderItems: List<Pair<String, String>>, settings: V2Settings): RunSummary {
        stopFlag.set(false)
        _state.update {
            it.copy(isRunning = true, statusText = "開始準備中...", logs = emptyList())
        }
        val meter = CostMeter(settings.cost.maxTokens, settings.cost.maxCost)
        val pool = QuotaPool(settings.geminiKeys.filter { it.isNotBlank() })
        var foldersDone = 0
        var filesDone = 0
        var filesTotal = 0
        try {
            if (!hasUsableKey(settings)) {
                addLog("abort: no usable key (skip split/dict/workers)")
                _state.update { it.copy(statusText = "⚠️ 利用可能なAPIキーがありません") }
                return RunSummary(0, 0, 0, aborted = true)
            }
            for ((folderIndex, item) in folderItems.withIndex()) {
                val (folderUri, designatedName) = item
                if (stopFlag.get() || !coroutineContext.isActive) break
                if (shouldAbort(pool, settings)) {
                    addLog("abort: quota exhausted (skip split/dict/workers)")
                    _state.update { it.copy(statusText = "⚠️ クォータ上限に達しました") }
                    stopFlag.set(true)
                    break
                }
                val folderName = designatedName.ifBlank { extractReadableFolderName(folderUri).ifBlank { "folder${folderIndex + 1}" } }
                _state.update { it.copy(folderName = folderName, statusText = "フォルダ「$folderName」を開始") }
                addLog("folder start: $folderName")
                val (done, total) = processFolder(folderUri, folderName, settings, pool, meter)
                filesDone += done
                filesTotal += total
                foldersDone++
            }
        } catch (e: CancellationException) {
            addLog("stopped by user")
            _state.update { it.copy(statusText = "停止しました") }
        } catch (e: Exception) {
            addLog("unexpected: ${e.message}")
            _state.update { it.copy(statusText = "⚠️ エラー: ${e.message}") }
        } finally {
            pool.reset()
            _state.update { it.copy(isRunning = false, statusText = "完了・待機中", fileName = "", chunkProgress = 0 to 0) }
        }
        return RunSummary(foldersDone, filesDone, filesTotal, aborted = stopFlag.get())
    }

    private suspend fun sampleTextForLanguage(
        files: List<VDoc>,
        targetChars: Int = 1200,
        maxFiles: Int = 5
    ): String {
        val sb = StringBuilder()
        for (f in files.take(maxFiles)) {
            val text = store.readText(f.uri) ?: continue
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

    private suspend fun detectOrLoadLanguage(
        outputDirUri: String,
        files: List<VDoc>,
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
        val sample = sampleTextForLanguage(files)
        val detected = detectLanguage(sample)
        addLog("detected language: ${detected.language} (${detected.reason})")
        val doc = cache ?: store.createFile(outputDirUri, ".lang_cache", "text/plain")
        if (doc != null) store.writeText(doc.uri, detected.language.name)
        return detected.language
    }

    /**
     * Physical pre-split pass. Returns accumulated (done, total) when at least
     * one part was translated (parent direct translation is then skipped),
     * or null when there is nothing to translate this way (normal path continues,
     * e.g. no raw files, or raw files produced no translatable parts).
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
                addLog("❌ 「分割済み」フォルダの作成に失敗しました: $folderName")
                return null
            }
        var done = 0
        var total = 0
        for ((rawIndex, raw) in rawFiles.withIndex()) {
            if (stopFlag.get() || !coroutineContext.isActive) break
            _state.update { it.copy(statusText = "📄 物理分割中: ${raw.name} (${rawIndex + 1}/${rawFiles.size})") }
            addLog("📄 [物理分割開始] (${rawIndex + 1}/${rawFiles.size}) ${raw.name}")
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
                val subName = result.novelName
                _state.update { it.copy(statusText = "🚀 分割完了・翻訳開始: $subName") }
                addLog("🚀 [翻訳開始] サブフォルダ: $subName (${result.partCount} パート)")
                val (d, t) = processFolder(result.subfolderUri, subName, settings, pool, meter, lang)
                done += d
                total += t
            }
        }
        return if (total > 0) (done to total) else null
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
            addLog("⚠️ 利用可能なキー枠が枯渇したためフォルダ処理を中止: $folderName")
            stopFlag.set(true)
            return 0 to 0
        }
        // Physical pre-split first (same order as the frozen spec):
        // each raw file is split and its subfolder translated immediately,
        // direct translation of the parent is skipped afterwards.
        if (settings.split.enabled && inheritedLang == null) {
            val splitResult = processPreSplit(folderUri, folderName, settings, pool, meter)
            if (splitResult != null && splitResult.second > 0) return splitResult
        }
        val outSubDir = settings.limits.outputSubDir.ifBlank { "翻訳完了_LLM" }
        _state.update { it.copy(statusText = "📁 フォルダ内を検索中: $folderName") }
        val files = store.children(folderUri)
            .filter { !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) && !it.name.endsWith(".failed", ignoreCase = true) }
            .sortedBy { it.name }
        if (files.isEmpty()) {
            addLog("⚠️ 対象ファイルなし: $folderName (直下に.txtファイルがありません)")
            _state.update { it.copy(statusText = "⚠️ .txtファイルがありません: $folderName") }
            return 0 to 0
        }
        val outputDir = store.findChild(folderUri, outSubDir)
            ?: store.createDir(folderUri, outSubDir)
            ?: run {
                addLog("❌ 出力フォルダ作成失敗: $outSubDir")
                _state.update { it.copy(statusText = "⚠️ 出力フォルダ作成失敗: $outSubDir") }
                return 0 to 0
            }

        val existing = Collections.synchronizedSet(mutableSetOf<String>())
        var zeroBytes = 0
        for (doc in store.children(outputDir.uri)) {
            if (!doc.isDirectory && doc.name.endsWith(".txt", ignoreCase = true) && doc.length == 0L) {
                zeroBytes++
                continue
            }
            existing.add(doc.name)
        }
        if (zeroBytes > 0) addLog("ℹ️ 0バイトの既存ファイルを再翻訳対象に含めます: ${zeroBytes}件")

        val total = files.size
        var preCompleted = 0
        for (f in files) {
            val hasWork = existing.contains(".parts_${f.name}")
            if (!hasWork && (existing.contains(f.name) || existing.contains("${f.name}.failed"))) {
                preCompleted++
            }
        }
        if (preCompleted >= total) {
            _state.update { it.copy(progress = total to total, statusText = "✅ 全件翻訳済み: $folderName") }
            addLog("✅ 全件翻訳済みのためスキップ: $folderName ($total/$total 件)")
            return total to total
        }

        _state.update { it.copy(statusText = "🔍 言語判定中: $folderName") }
        val sourceLang = detectOrLoadLanguage(outputDir.uri, files, inheritedLang)

        // 辞書
        var novelDict: NovelDict? = null
        if (settings.dict.enabled) {
            _state.update { it.copy(statusText = "📖 登場人物辞書を生成中: $folderName") }
            val existingDict = store.findChild(folderUri, "dictionary.json")
            val dictJson = existingDict?.let { store.readText(it.uri) } ?: ""
            novelDict = parseDictJson(dictJson)
            if (novelDict == null) {
                novelDict = buildDictionary(folderUri, files, settings, pool, meter)
            }
            if (novelDict == null) {
                addLog("dict incomplete, skip folder: $folderName")
                _state.update { it.copy(statusText = "⚠️ 辞書生成が未完了のためスキップ: $folderName") }
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
        // 参加プロファイルの最小 maxOutputChars を採用（小型モデルへのローテーション時にもトークン溢れを完全防止）
        val targetOutputChars = profiles.minOfOrNull { it.maxOutputChars } ?: 15000
        val optimalInputBytes = V2Settings.calculateInputLimitBytes(sourceLang, targetOutputChars)
        val splitThresholdBytes = optimalInputBytes
        val chunkSizeBytes = (optimalInputBytes * 0.9).toInt().coerceAtLeast(3000)
        val batchMaxBytes = (optimalInputBytes * 0.85).toInt().coerceAtLeast(3000)
        addLog("capacity: limit=${optimalInputBytes}B, chunk=${chunkSizeBytes}B, batch=${batchMaxBytes}B (source=${sourceLang.name}, maxOutput=${targetOutputChars} chars)")

        val contextTracker = SourceContextTracker(
            files = files,
            store = store,
            contextLines = settings.prevContext.lines,
            enabled = settings.prevContext.enabled
        )

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
                switchCooldownSec = options.switchCooldownSec,
                maxSameRetries = options.maxSameRetries,
                sendGate = sendGate,
                sendGateIntervalMs = if (options.minSendIntervalMs == 0L) settings.limits.requestDelaySec * 1000L else maxOf(settings.limits.requestDelaySec * 1000L, options.minSendIntervalMs),
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
                        completed, total,
                        batchMaxBytes, splitThresholdBytes, chunkSizeBytes,
                        sourceLang, promptOrder,
                        profiles, profilePromptOrders,
                        contextTracker
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
                com.example.novelscraper.translation.v2.engine.resolveProfileOptions(
                    profile.copy(thinkingLevel = dict.thinkingLevel),
                    mapOf("gemini" to GEMINI_DESCRIPTOR, "openrouter" to OPENROUTER_DESCRIPTOR)[dict.providerId]
                )
            )
            if (dict.providerId == "openrouter") {
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
                if (stopFlag.get()) null
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

    /**
     * 【前文末尾注入の統一仕様】
     * 1. 単体翻訳:
     *    直前話（i - 1）の【原文末尾（設定行数）】を注入。
     *    全ワーカー共有キャッシュ（ConcurrentHashMap）により、ワーカー数に関わらず直前話を特定する（設定無効・先頭話は注入なし）。
     * 2. バッチ翻訳:
     *    ・一括送信時: バッチ先頭ファイルに対する直前話（i - 1）の【原文末尾（設定行数）】を注入。
     *    ・単体フォールバック時: 各話に対して直前話（i - 1）の【原文末尾（設定行数）】を注入（誤訳伝染防止）。
     * 3. チャンク翻訳（大ファイル分割翻訳）:
     *    ・先頭の未処理チャンクのみ直前話（i - 1）の【原文末尾（設定行数）】を注入。
     *    ・後続チャンクは同一エピソード内の接続のため、直前チャンクの【翻訳後訳文末尾（設定行数）】を数珠つなぎ注入。
     *    （訳文末尾と原文末尾の重ね注入はしない）
     *
     * 全ワーカー共有の原文コンテキスト管理（スレッドセーフ）。
     * 並行ワーカー間での文脈欠落（null化）を抑止し、ファイル順（インデックス順）で直前話（i - 1）の
     * クレンジング済み原文末尾N行を提供する（前文脈設定が無効の場合は提供しない）。
     */
    private inner class SourceContextTracker(
        private val files: List<com.example.novelscraper.translation.v2.infra.VDoc>,
        private val store: com.example.novelscraper.translation.v2.infra.FileStore,
        private val contextLines: Int,
        private val enabled: Boolean
    ) {
        private val cache = java.util.concurrent.ConcurrentHashMap<Int, String>()

        suspend fun getPrevSourceTail(index: Int): String? {
            if (!enabled || index <= 0 || index >= files.size) return null
            val targetIdx = index - 1
            val cached = cache[targetIdx]
            if (cached != null) return cached.ifEmpty { null }

            val file = files.getOrNull(targetIdx) ?: return null
            val raw = store.readText(file.uri) ?: return null
            val clean = cleanseBasic(raw)
            if (clean.isBlank()) return null
            val tail = clean.lines().takeLast(contextLines.coerceIn(1, 100)).joinToString("\n")
            cache[targetIdx] = tail
            return tail.ifEmpty { null }
        }

        fun putSource(index: Int, content: String) {
            if (!enabled || index < 0 || index >= files.size) return
            val tail = content.lines().takeLast(contextLines.coerceIn(1, 100)).joinToString("\n")
            cache[index] = tail
        }
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
        splitThresholdBytes: Int,
        chunkSizeBytes: Int,
        sourceLang: SourceLang,
        promptOrder: List<Int>,
        profiles: List<V2ModelProfile>,
        profilePromptOrders: Map<String, List<Int>>,
        contextTracker: SourceContextTracker
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
        // 技術的根拠: 構造化出力の適用範囲をバッチ枠に限定するため、枠種別で送信bindingを使い分ける
        val useJsonBatch = profiles.any { profile ->
            profile.useJsonSchema && when (profile.providerId) {
                "gemini" -> GEMINI_DESCRIPTOR.capabilitiesFor(profile.model).structuredOutput
                "openrouter" -> OPENROUTER_DESCRIPTOR.capabilitiesFor(profile.model).structuredOutput
                else -> false
            }
        }
        fun bindCall(forBatch: Boolean): suspend (String, String, String) -> LlmResult {
            return { _, prompt, source ->
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
                rotation.execute(listOf(prompt), source, profilePrompts, forBatch)
            }
        }
        val ctx = TranslateContext(
            basePrompts = allBasePrompts,
            promptOrder = listOf(primaryPromptNum), // 技術的根拠: プロンプト順巡回はRotation側に一本化し、外側attemptDriversとの二重ループ(NxN)を防止
            driverNames = listOf("w$workerId"),
            dictionary = novelDict,
            verify = verify,
            call = bindCall(false),
            callBatch = bindCall(true),
            batchJsonFormat = useJsonBatch,
            prevContextLines = settings.prevContext.lines,
            prevContextEnabled = settings.prevContext.enabled,
            maxSameRetries = 0,
            stopped = { stopFlag.get() },
            meter = null,
            log = { addLog("[W#$workerId] $it") }
        )
        for ((fileIdx, file) in files.withIndex()) {
            if (stopFlag.get() || rotation.exhausted) break
            val fileName = file.name
            if (existing.contains(fileName) || existing.contains("${fileName}.failed")) continue
            if (!claim(fileName)) continue
            _state.update { it.copy(fileName = fileName, statusText = "翻訳中: $fileName (${completed.get()}/$total)") }

            try {
                val raw = store.readText(file.uri)
                if (raw == null) {
                    addLog("❌ [W#$workerId] ファイル読込失敗: $fileName")
                    writeFailed(store, outputDirUri, fileName, "unreadable file")
                    existing.add("${fileName}.failed")
                    bump(fileName)
                    continue
                }
                val content = cleanseBasic(raw)
                contextTracker.putSource(fileIdx, content)
                if (content.isBlank()) {
                    val out = store.findChild(outputDirUri, fileName)
                        ?: store.createFile(outputDirUri, fileName, "text/plain")
                    if (out != null && store.writeText(out.uri, "")) existing.add(fileName)
                    bump(fileName)
                    continue
                }
                val contentBytes = utf8Bytes(content)

                if (contentBytes > splitThresholdBytes) {
                    if (contentBytes > options.maxInputBytes) {
                        // 技術的根拠1行：上限超えの巨大入力は実行時分割も事前分割への自動回送もせず、その場でスキップ確定する
                        addLog("⏭️ [W#$workerId] 上限超過のためスキップ: $fileName (${contentBytes}B)")
                        writeFailed(store, outputDirUri, fileName, content) { addLog(it) }
                        existing.add("$fileName.failed")
                        bump(fileName)
                        continue
                    }
                    val workDir = store.findChild(outputDirUri, ".parts_${fileName}")
                        ?: store.createDir(outputDirUri, ".parts_${fileName}")
                    if (workDir == null) {
                        addLog("❌ [W#$workerId] 大ファイル用作業フォルダ作成失敗: $fileName")
                    } else {
                        addLog("📦 [W#$workerId] 大ファイル分割翻訳開始: $fileName (${contentBytes}B)")
                        val ok = translateLarge(
                            store, workDir.uri, outputDirUri, fileName, content, ctx,
                            LargeOptions(
                                chunkSizeBytes = chunkSizeBytes,
                                tailLines = settings.prevContext.lines.coerceIn(1, 100),
                                maxInputBytes = options.maxInputBytes
                            ),
                            prevSourceTail = contextTracker.getPrevSourceTail(fileIdx),
                            onChunkProgress = { cur, total ->
                                _state.update { it.copy(chunkProgress = cur to total) }
                            }
                        )
                        _state.update { it.copy(chunkProgress = 0 to 0) }
                        if (ok) {
                            existing.add(fileName)
                            addLog("✅ [W#$workerId] 大ファイル翻訳完了: $fileName")
                        }
                    }
                    bump(fileName)
                    continue
                }

                // バッチ束ね（後続のみ・最大件数・合計バイト上限）
                val batch = mutableListOf(file to content)
                // 各話の直前話末尾（全体文脈基準。フォールバック時の単体翻訳と同一にする）
                val batchPrevTails = mutableListOf(contextTracker.getPrevSourceTail(fileIdx))
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
                    contextTracker.putSource(nextIdx, nextClean)
                    if (nextClean.isBlank()) {
                        val out = store.findChild(outputDirUri, nextName)
                            ?: store.createFile(outputDirUri, nextName, "text/plain")
                        if (out != null && store.writeText(out.uri, "")) existing.add(nextName)
                        bump(nextName)
                        unclaim(nextName)
                        continue
                    }
                    val nextBytes = utf8Bytes(nextClean)
                    // 技術的根拠: バッチ束ね判定でも固定デフォルト値 options.splitThresholdBytes ではなく最小モデル連動の動的 splitThresholdBytes を適用しトークン溢れを防止
                    if (nextBytes > splitThresholdBytes || batchBytes + nextBytes > batchMaxBytes) {
                        unclaim(nextName)
                        break
                    }
                    batch.add(next to nextClean)
                    batchPrevTails.add(contextTracker.getPrevSourceTail(nextIdx))
                    batchBytes += nextBytes
                }
                try {
                    if (batch.size > 1) {
                        addLog("📦 [W#$workerId] バッチ翻訳開始 (${batch.size}件): ${batch.map { it.first.name }.joinToString(", ")}")
                        val outcome = translateBatch(
                            store, outputDirUri,
                            batch.map { it.first.name to it.second },
                            ctx,
                            prevSourceTail = batchPrevTails.firstOrNull(),
                            itemPrevTails = batchPrevTails
                        )
                        if (outcome.settled > 0) {
                            val done = completed.addAndGet(outcome.settled)
                            _state.update { it.copy(progress = done to total, fileName = fileName) }
                            addLog("✅ [W#$workerId] バッチ翻訳完了 (${outcome.savedFiles.size}件保存)")
                        }
                        // 保存系はstage内で完結するため、結果から既存集合を直接同期（不要なfindChild Binder IPCクエリを全廃）
                        // 技術的根拠: SAFの findChild は O(N) の線形ディレクトリ走査・IPCクエリを伴うため、translateBatch の確定ファイル名で1工程同期する
                        existing.addAll(outcome.savedFiles)
                        if (outcome.configBlocked) return
                    } else {
                        addLog("📝 [W#$workerId] 翻訳中: $fileName")
                        when (val r = translateSingle(content, ctx, prevSourceTail = contextTracker.getPrevSourceTail(fileIdx))) {
                            is SingleResult.Translated -> {
                                val out = store.findChild(outputDirUri, fileName)
                                    ?: store.createFile(outputDirUri, fileName, "text/plain")
                                if (out != null && store.writeText(out.uri, r.text)) {
                                    existing.add(fileName)
                                    addLog("✅ [W#$workerId] 翻訳完了・保存: $fileName")
                                }
                            }
                            is SingleResult.Failed -> writeFailed(store, outputDirUri, fileName, content) { addLog(it) }
                            is SingleResult.ConfigOnly -> {
                                addLog("⚠️ [W#$workerId] 設定エラーのためスキップ: $fileName")
                            }
                            is SingleResult.Stopped -> return
                        }
                        bump(fileName)
                    }
                } finally {
                    for (item in batch) unclaim(item.first.name)
                }
            } finally {
                unclaim(fileName)
            }
        }
    }
}
