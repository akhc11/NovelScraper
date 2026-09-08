package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.ProviderDescriptor
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.domain.resolveOpenRouterParams
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.infra.GeminiHandler
import com.example.novelscraper.translation.v2.infra.OpenRouterHandler
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_1_ZH
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_2_EN
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_3_KO
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_4_NSFW
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_5_LITERAL
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_6_READABLE
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_7_RETRY
import com.example.novelscraper.translation.v2.pipeline.detectLanguage
import com.example.novelscraper.translation.v2.pipeline.resolvePromptOrder
import com.example.novelscraper.translation.v2.pipeline.splitSingleTextFile
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class EngineOptions(
    val batchMaxFiles: Int = 3,
    /** チャンク翻訳に渡す上限。これを超える入力はスキップ確定（.failed保存）する */
    val maxInputBytes: Int = 1_000_000,
    val kanaFloor: Double = 0.2,
    val markerEnabled: Boolean = true,
    val maxSameRetries: Int = 2,
    val switchCooldownSec: Int = 15,
    /** 非管理のみ構成のエポック間冷却（テストは0にして高速化する） */
    val unmanagedCooldownSec: Int = 30,
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
    private val descriptors: Map<ProviderId, ProviderDescriptor> = mapOf(
        ProviderId.GEMINI to GEMINI_DESCRIPTOR,
        ProviderId.OPENROUTER to OPENROUTER_DESCRIPTOR
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
        profiles.filter { it.providerId.toProviderId() == ProviderId.GEMINI }.map { it.model }

    private fun hasUsableKey(settings: V2Settings): Boolean {
        val profiles = settings.profiles
        if (profiles.isEmpty()) return false
        if (profiles.any { it.providerId.toProviderId() == ProviderId.GEMINI } && settings.geminiKeys.any { it.isNotBlank() }) return true
        if (profiles.any { it.providerId.toProviderId() == ProviderId.OPENROUTER } && settings.openRouterKey.isNotBlank()) return true
        return false
    }

    private fun hasFallbackKey(settings: V2Settings): Boolean {
        return settings.profiles.any { it.providerId.toProviderId() == ProviderId.OPENROUTER } && settings.openRouterKey.isNotBlank()
    }

    /** 中止判定：Gemini枠の枯渇（OpenRouter代替なし）または辞書用Geminiモデルの枯渇。OpenRouter単独構成はここでは止めない */
    private suspend fun shouldAbort(pool: QuotaPool, settings: V2Settings): Boolean {
        val gemini = geminiModels(settings.profiles)
        if (gemini.isNotEmpty() && pool.isExhausted(gemini) && !hasFallbackKey(settings)) return true
        if (settings.dict.enabled && settings.dict.providerId.toProviderId() == ProviderId.GEMINI) {
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
        return when (profile.providerId.toProviderId()) {
            ProviderId.GEMINI -> GeminiHandler(apiKey = key)
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

    /** 事前物理分割の結果。skipped は文字化け確定で翻訳対象外にするファイル名。 */
    private data class PreSplitOutcome(val done: Int, val total: Int, val skipped: Set<String>)

    /**
     * Physical pre-split pass. Returns accumulated (done, total) when at least
     * one part was translated (parent direct translation is then skipped),
     * or null when there is nothing to translate this way (normal path continues,
     * e.g. no raw files). Files quarantined as mojibake are reported in [PreSplitOutcome.skipped]
     * and must be excluded from the normal path (they are never translated).
     * One blocked novel never stops the others: each part file translates and
     * resumes independently, so no combine step can get stuck mid-file.
     */
    private suspend fun processPreSplit(
        folderUri: String,
        folderName: String,
        settings: V2Settings,
        pool: QuotaPool,
        meter: CostMeter
    ): PreSplitOutcome? {
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
        val skipped = mutableSetOf<String>()
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
                log = { addLog(it) },
                onSkipped = { skipped.add(raw.name) }
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
        return PreSplitOutcome(done, total, skipped)
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
        // 技術的根拠1行：文字化け確定ファイルは通常経路でも訳さない（検査すり抜けの完成を防ぐ）。
        var splitSkipped: Set<String> = emptySet()
        if (settings.split.enabled && inheritedLang == null) {
            val splitResult = processPreSplit(folderUri, folderName, settings, pool, meter)
            if (splitResult != null) {
                if (splitResult.total > 0) return splitResult.done to splitResult.total
                splitSkipped = splitResult.skipped
                if (splitSkipped.isNotEmpty()) {
                    addLog("⏭️ 文字化けのため翻訳しません: ${splitSkipped.sorted().joinToString(", ")}")
                }
            }
        }
        val outSubDir = settings.limits.outputSubDir.ifBlank { "翻訳完了_LLM" }
        _state.update { it.copy(statusText = "📁 フォルダ内を検索中: $folderName") }
        val files = store.children(folderUri)
            .filter {
                !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) &&
                    !it.name.endsWith(".failed", ignoreCase = true) && it.name !in splitSkipped
            }
            .sortedBy { it.name }
        if (files.isEmpty()) {
            if (splitSkipped.isNotEmpty()) {
                addLog("⚠️ 全件が文字化けのためスキップ: $folderName")
                _state.update { it.copy(statusText = "⚠️ 文字化けのためスキップ: $folderName") }
            } else {
                addLog("⚠️ 対象ファイルなし: $folderName (直下に.txtファイルがありません)")
                _state.update { it.copy(statusText = "⚠️ .txtファイルがありません: $folderName") }
            }
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
            novelDict = DictionaryBuilder.parseDictJson(dictJson)
            if (novelDict == null) {
                novelDict = DictionaryBuilder(
                    store = store,
                    buildHandler = { s, p, k -> buildHandler(s, p, k) },
                    stopped = { stopFlag.get() },
                    log = { addLog(it) }
                ).build(folderUri, files, settings, pool)
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
        val workerCount = settings.limits.parallelWorkers.coerceIn(
            TranslationLimits.WORKER_COUNT_RANGE.first,
            TranslationLimits.WORKER_COUNT_RANGE.last
        )
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

        // 技術的根拠1行：経路選択を実行時分岐にせず型で固定する（S-2のプール誤用を構造的に防止）。
        // なおワーカー群はsupervisorScopeで隔離し、想定外例外が兄弟ワーカーや親スコープへ波及しないようにする。
        supervisorScope {
            for (wId in 1..workerCount) {
                val claimed = if (profiles.any { it.providerId.toProviderId() == ProviderId.GEMINI }) {
                    pool.claimNew(geminiModels(profiles))
                } else {
                    0 to ""
                }
                if (claimed == null) {
                    addLog("worker #$wId skipped (no key)")
                    continue
                }
                val router: PromptRouter = if (profiles.any { it.providerId.toProviderId() == ProviderId.GEMINI }) {
                    Rotation(
                        workerId = wId,
                        profiles = profiles,
                        pool = pool,
                        keyIndex = claimed.first,
                        key = claimed.second,
                        descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR, ProviderId.OPENROUTER to OPENROUTER_DESCRIPTOR),
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
                } else {
                    UnmanagedRotation(
                        workerId = wId,
                        profiles = profiles,
                        key = settings.openRouterKey,
                        descriptors = mapOf(ProviderId.GEMINI to GEMINI_DESCRIPTOR, ProviderId.OPENROUTER to OPENROUTER_DESCRIPTOR),
                        handlerFactory = { profile, key -> buildHandler(settings, profile, key) },
                        cooldownSec = options.unmanagedCooldownSec,
                        maxSameRetries = options.maxSameRetries,
                        sendGate = sendGate,
                        sendGateIntervalMs = if (options.minSendIntervalMs == 0L) settings.limits.requestDelaySec * 1000L else maxOf(settings.limits.requestDelaySec * 1000L, options.minSendIntervalMs),
                        stopped = { stopFlag.get() },
                        meter = meter,
                        log = { addLog("[W#$wId] $it") }
                    )
                }
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        if (wId > 1 && options.workerStaggerSec > 0) {
                            delay(options.workerStaggerSec * 1000L * (wId - 1))
                        }
                        runWorker(
                            wId, files, outputDir.uri, existing, claims,
                            settings, router, novelDict,
                            completed, total,
                            batchMaxBytes, splitThresholdBytes, chunkSizeBytes,
                            sourceLang, promptOrder,
                            profiles, profilePromptOrders,
                            contextTracker
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        addLog("❌ [W#$wId] worker failed: ${e.message}")
                    } finally {
                        router.release()
                    }
                }
            }
        }
        return completed.get() to total
    }

    private suspend fun runWorker(
        workerId: Int,
        files: List<com.example.novelscraper.translation.v2.infra.VDoc>,
        outputDirUri: String,
        existing: MutableSet<String>,
        claims: MutableSet<String>,
        settings: V2Settings,
        router: PromptRouter,
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
        WorkerRunner(
            workerId = workerId,
            store = store,
            settings = settings,
            options = options,
            router = router,
            novelDict = novelDict,
            completed = completed,
            total = total,
            batchMaxBytes = batchMaxBytes,
            splitThresholdBytes = splitThresholdBytes,
            chunkSizeBytes = chunkSizeBytes,
            sourceLang = sourceLang,
            promptOrder = promptOrder,
            profiles = profiles,
            profilePromptOrders = profilePromptOrders,
            contextTracker = contextTracker,
            files = files,
            outputDirUri = outputDirUri,
            existing = existing,
            claims = claims,
            stopped = { stopFlag.get() },
            onFileStart = { fileName, done, tot ->
                _state.update { it.copy(fileName = fileName, statusText = "翻訳中: $fileName ($done/$tot)") }
            },
            onProgress = { done, tot, fileName ->
                _state.update { it.copy(progress = done to tot, fileName = fileName) }
            },
            onChunkProgress = { cur, tot ->
                _state.update { it.copy(chunkProgress = cur to tot) }
            },
            log = { addLog(it) }
        ).run()
    }

}
