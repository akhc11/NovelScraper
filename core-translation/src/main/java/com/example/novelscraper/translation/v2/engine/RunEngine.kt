package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.CostMeter
import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.ProviderDescriptor
import com.example.novelscraper.translation.v2.domain.ProviderHandler
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.ProviderRegistry
import com.example.novelscraper.translation.v2.domain.QuotaPool
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import com.example.novelscraper.translation.v2.domain.V2SendGate
import com.example.novelscraper.translation.v2.domain.NovelTarget
import com.example.novelscraper.translation.v2.domain.DictResolveResult
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.NovelDict
import com.example.novelscraper.translation.v2.pipeline.BundlePool
import com.example.novelscraper.translation.v2.pipeline.planBundles
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_1_ZH
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_2_EN
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_3_KO
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_4_NSFW
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_5_LITERAL
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_6_READABLE
import com.example.novelscraper.translation.v2.pipeline.V2_PROMPT_7_RETRY
import com.example.novelscraper.translation.common.ingest.IngestResult
import com.example.novelscraper.translation.common.ingest.TextIngest
import com.example.novelscraper.translation.v2.pipeline.detectLanguage
import com.example.novelscraper.translation.v2.pipeline.resolvePromptOrder
import com.example.novelscraper.translation.v2.pipeline.splitSingleTextFile
import com.example.novelscraper.translation.v2.settings.V2ModelProfile
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.buildRefineProfile
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
    val maxInputBytes: Int = 10_000_000,
    val kanaFloor: Double = 0.2,
    val markerEnabled: Boolean = true,
    val maxSameRetries: Int = 2,
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

/**
 * 同時実行の開始可否（pure）。null＝可、非null＝拒否理由。
 * 技術的根拠1行：上限と重複の判定を純粋関数に寄せ、UIとテストで同一判定を使う。
 */
fun canStartRun(
    activeRuns: Int,
    maxRuns: Int,
    activeFolderUris: Set<String>,
    newFolderUris: Set<String>
): String? = when {
    activeRuns >= maxRuns -> "同時実行は${maxRuns}件までです"
    newFolderUris.any { it in activeFolderUris } -> "実行中のフォルダと重複しています"
    else -> null
}

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
 * ブラウザ選択の確定単位。空のfileUrisは絞り込みなし(全件・従来動作)。
 * 非空時は選択時点のスナップショットのみ翻訳し、指定外の親同胞を巻き込まない。
 * 技術的根拠1行：列挙は実行時再取得のためURI一致で突合せ、改名時の取違えを防ぐ。
 */
data class FolderTarget(
    val folderUri: String,
    val displayName: String,
    val fileUris: Set<String> = emptySet()
)

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
    // 技術的根拠1行：記述子既定値を登録簿に一本化し、新会社追加は登録簿の1行で追従する（既存2社の内容同一）。
    private val descriptors: Map<ProviderId, ProviderDescriptor> = ProviderRegistry.descriptors,
    /** テスト用の差し替え口。null時は内蔵生成を使う */
    private val handlerFactory: ((V2Settings, V2ModelProfile, String) -> ProviderHandler)? = null,
    /**
     * 並行実行時の共有送信ゲート。null時は自前（単独実行の従来動作）。
     * 技術的根拠1行：ゲートは送信間隔の共有財のため、複数エンジンでも合計並列の上限だけ共有し他はrun別に保つ。
     */
    sharedSendGate: V2SendGate? = null
) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val stopFlag = AtomicBoolean(false)

    /** Shared send gate, engine lifetime (parity with the old global gate). */
    private val sendGate = sharedSendGate ?: V2SendGate()

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
        // 技術的根拠1行：生成分岐をDefaultHandlerFactoryに一本化し、判定仕様は変えない（外部振る舞い不変）。
        return defaultHandlerFor(profile, key, settings)
    }

    companion object {
        fun extractReadableFolderName(uriString: String): String {
            return try {
                // 技術的根拠1行：非推奨のString charset overloadをStandardCharsets版に置換し、復号結果は同一にする（外部振る舞い不変）。
                val decoded = java.net.URLDecoder.decode(uriString, java.nio.charset.StandardCharsets.UTF_8)
                val afterTree = if (decoded.contains("/tree/")) decoded.substringAfter("/tree/") else decoded
                val afterDoc = if (afterTree.contains("/document/")) afterTree.substringAfter("/document/") else afterTree
                val clean = afterDoc.substringAfterLast(':').substringAfterLast('/')
                clean.ifBlank { "フォルダ" }
            } catch (_: Exception) {
                uriString.substringAfterLast('/').substringAfterLast(':').ifBlank { "フォルダ" }
            }
        }

        /**
         * 選択スナップショットの純粋フィルタ(JVMテスト可)。空=全件。
         * 技術的根拠1行：実行時列挙との突合せはURI完全一致に寄せ、改名時の誤翻訳を作らない。
         */
        internal fun applyFileFilter(
            listed: List<com.example.novelscraper.translation.v2.infra.VDoc>,
            allowFileUris: Set<String>
        ): List<com.example.novelscraper.translation.v2.infra.VDoc> {
            if (allowFileUris.isEmpty()) return listed
            return listed.filter { it.uri in allowFileUris }
        }
    }

    suspend fun run(folderUris: List<String>, settings: V2Settings): RunSummary {
        return runWithNames(folderUris.map { it to extractReadableFolderName(it) }, settings)
    }

    suspend fun runWithNames(folderItems: List<Pair<String, String>>, settings: V2Settings): RunSummary {
        return runWithTargets(folderItems.map { FolderTarget(it.first, it.second) }, settings)
    }

    /**
     * 選択ファイル絞り込み付き実行。辞書・分割・保存の本体は変えず、入力列挙だけ絞る。
     * 技術的根拠1行：絞り込み責務をprocessFolder入口の1箇所に寄せ、辞書は絞り後の同一リストでそのまま作る(辞書改変なし)。
     */
    suspend fun runWithTargets(
        targets: List<FolderTarget>,
        settings: V2Settings
    ): RunSummary {
        stopFlag.set(false)
        // 技術的根拠1行：履歴はViewModelのruns一覧が担うため、エンジン側で世代保持しない（履歴の二重管理防止）。
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
            for ((folderIndex, item) in targets.withIndex()) {
                val folderUri = item.folderUri
                val designatedName = item.displayName
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
                val (done, total) = processFolder(folderUri, folderName, settings, pool, meter, item.fileUris)
                filesDone += done
                filesTotal += total
                foldersDone++
            }
        } catch (e: CancellationException) {
            addLog("stopped by user")
            _state.update { it.copy(statusText = "停止しました") }
        } catch (e: Throwable) {
            val errType = e::class.java.simpleName
            val errMsg = e.message ?: e.cause?.message ?: "原因不明のエラー"
            addLog("unexpected ($errType): $errMsg")
            _state.update { it.copy(statusText = "⚠️ エラー ($errType): $errMsg") }
        } finally {
            pool.reset()
            _state.update { it.copy(isRunning = false, statusText = "完了・待機中", fileName = "", chunkProgress = 0 to 0) }
        }
        return RunSummary(foldersDone, filesDone, filesTotal, aborted = stopFlag.get())
    }

    /** 読み取り専用の言語判定。キャッシュへの書き込みは行わない。 */
    private suspend fun detectLanguage(
        folderUri: String,
        files: List<VDoc>,
        inherited: SourceLang? = null,
        outputDirUri: String? = null
    ): SourceLang {
        return LanguageDetectStage.detectLanguage(
            store = store,
            inputFolderUri = folderUri,
            files = files,
            outputDirUri = outputDirUri,
            inherited = inherited,
            onLog = { addLog(it) }
        )
    }

    /**
     * 単一小説のオンデマンド物理分割。
     * 翻訳直前にこの1ファイルのみ分割し、分割後サブフォルダをNovelTargetとして返す。
     * 契約: 一件ずつ分割→即翻訳すること。全件先行分割に戻さないこと（未翻訳の無駄I/O・残骸を作らない）。
     * 技術的根拠1行：翻訳しない小説まで先行分割しないよう分割と翻訳を1件ずつ直列化し、Web側オンデマンドと同型の単一パイプラインにする。
     */
    private suspend fun partitionOne(
        raw: VDoc,
        splitRootUri: String,
        rawIndex: Int,
        rawTotal: Int,
        settings: V2Settings,
        onSkipped: (String) -> Unit
    ): NovelTarget? {
        _state.update { it.copy(statusText = "📄 物理分割中: ${raw.name} (${rawIndex + 1}/$rawTotal)") }
        addLog("📄 [物理分割開始] (${rawIndex + 1}/$rawTotal) ${raw.name}")
        val result = splitSingleTextFile(
            store = store,
            fileUri = raw.uri,
            fileName = raw.name,
            splitRootUri = splitRootUri,
            splitSizeChars = settings.split.splitSizeChars,
            declared = V2DeclaredEncoding.parseOrNull(settings.split.inputEncoding),
            stopped = { stopFlag.get() || !scope.isActive },
            sourceSizeBytes = raw.length,
            log = { addLog(it) },
            onSkipped = { onSkipped(raw.name) }
        )
        if (result == null || stopFlag.get() || !coroutineContext.isActive) return null
        val subFiles = store.children(result.subfolderUri)
            .filter {
                !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) &&
                    !LangCacheStore.isCacheFileName(it.name)
            }
            .sortedBy { it.name }
        return NovelTarget(
            novelName = result.novelName,
            folderUri = result.subfolderUri,
            isPreSplit = true,
            files = subFiles,
            sampleText = result.sampleText
        )
    }

    /**
     * 単一小説ターゲットに対する直線パイプライン実行。
     * [完了判定 ➔ 辞書ロード/生成 ➔ 並行翻訳ワーカー実行] を一本道で処理する。
     */
    private suspend fun executeNovelPipeline(
        target: NovelTarget,
        sourceLang: SourceLang,
        settings: V2Settings,
        pool: QuotaPool,
        meter: CostMeter
    ): Pair<Int, Int> {
        val folderUri = target.folderUri
        val folderName = target.novelName
        val files = target.files
        if (files.isEmpty()) {
            addLog("⚠️ 対象ファイルなし: $folderName (直下に.txtファイルがありません)")
            _state.update { it.copy(statusText = "⚠️ .txtファイルがありません: $folderName") }
            return 0 to 0
        }

        val outSubDir = settings.limits.outputSubDir.ifBlank { "翻訳完了_LLM" }
        _state.update { it.copy(statusText = "📁 フォルダ内を検索中: $folderName") }
        val outputDir = store.findChild(folderUri, outSubDir)
            ?: store.createDir(folderUri, outSubDir)
            ?: run {
                addLog("❌ 出力フォルダ作成失敗: $outSubDir")
                _state.update { it.copy(statusText = "⚠️ 出力フォルダ作成失敗: $outSubDir") }
                return 0 to 0
            }

        // outputDir 確保直後に言語キャッシュを正本化（唯一の正規書き込み経路）。
        // 技術的根拠1行：出力側の固定値（ピン）を検出値より優先し、旧入力配置は正本確定後に限り削除することで三重・二重配置に収束させる。
        val pinnedLang = LangCacheStore.load(store, outputDir.uri) { addLog(it) }
        val effectiveLang = pinnedLang ?: sourceLang
        if (pinnedLang == null) {
            if (LangCacheStore.save(store, outputDir.uri, sourceLang) { addLog(it) }) {
                LangCacheStore.migrateFromInput(store, folderUri, outputDir.uri) { addLog(it) }
            }
        } else {
            if (pinnedLang != sourceLang) {
                addLog("🌐 言語キャッシュを再利用: ${pinnedLang.name}（今回検出=${sourceLang.name}より固定値を優先）")
            }
            LangCacheStore.sweepDuplicates(store, outputDir.uri) { addLog(it) }
            LangCacheStore.migrateFromInput(store, folderUri, outputDir.uri) { addLog(it) }
        }

        val existing = Collections.synchronizedSet(mutableSetOf<String>())
        var zeroBytes = 0
        for (doc in store.children(outputDir.uri)) {
            if (LangCacheStore.isCacheFileName(doc.name)) continue
            if (!doc.isDirectory && doc.name.endsWith(".txt", ignoreCase = true) && doc.length == 0L) {
                zeroBytes++
                continue
            }
            existing.add(doc.name)
        }
        if (zeroBytes > 0) addLog("ℹ️ 0バイトの既存ファイルを再翻訳対象に含めます: ${zeroBytes}件")

        // 試し読み上限（小説あたり先頭N件）。0＝無制限（従来通り完走）。
        // 技術的根拠1行：完走強制の無駄打ちを排するため判定はこの単一箇所に寄せ、束計画・作業者への二重分岐は作らない。
        val trialLimit = settings.limits.filesPerFolder.coerceIn(
            TranslationLimits.TRIAL_FILES_RANGE.first,
            TranslationLimits.TRIAL_FILES_RANGE.last
        )
        val scopedFiles = if (trialLimit > 0) files.take(trialLimit) else files
        if (trialLimit > 0 && scopedFiles.size < files.size) {
            addLog("試し読み上限: 先頭${scopedFiles.size}/${files.size}件まで翻訳し残りは次へ ($folderName)")
        }

        val total = files.size
        val scopeTotal = scopedFiles.size
        var preCompleted = 0
        for (f in scopedFiles) {
            val hasWork = existing.contains(".parts_${f.name}")
            if (!hasWork && (existing.contains(f.name) || existing.contains("${f.name}.failed"))) {
                preCompleted++
            }
        }
        if (preCompleted >= scopeTotal && scopeTotal > 0) {
            if (scopeTotal < total) {
                _state.update { it.copy(progress = preCompleted to total, statusText = "試し読み上限内で全件済み: $folderName ($scopeTotal/$total 件)→次へ") }
                addLog("試し読み上限内で全件済みのため次へ: $folderName ($scopeTotal/$total 件)")
                return preCompleted to total
            }
            _state.update { it.copy(progress = total to total, statusText = "✅ 全件翻訳済み: $folderName") }
            addLog("✅ 全件翻訳済みのためスキップ: $folderName ($total/$total 件)")
            return total to total
        }

        // 辞書ステージ
        // 技術的根拠1行：辞書中は翻訳進捗が止まるため、前小説の残留値（試し読み40/40等）で固まって見えるのを新小説の基準値で上書きする。
        _state.update {
            it.copy(
                statusText = if (settings.dict.enabled) "📖 登場人物辞書を生成中: $folderName" else it.statusText,
                progress = preCompleted to total
            )
        }
        val dictResult = DictionaryStage.resolveDictionary(
            store = store,
            folderUri = folderUri,
            folderName = folderName,
            // 技術的根拠1行：辞書は再開時に作り直されない確定物(同一文面ハッシュは再利用)のため、試し読み時も全件対象で作り、41件目以降の人名欠落を防ぐ。節約は本文側の絞りで担う。
            files = files,
            settings = settings,
            pool = pool,
            buildHandler = { s, p, k -> buildHandler(s, p, k) },
            stopped = { stopFlag.get() },
            onLog = { addLog(it) }
        )
        val novelDict = when (dictResult) {
            is DictResolveResult.Ready -> dictResult.dict
            is DictResolveResult.Aborted -> {
                addLog(dictResult.reason)
                _state.update { it.copy(statusText = "⚠️ 辞書未完成のため中断: $folderName") }
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
                sourceLang = effectiveLang,
                profileOrder = profile.promptOrder,
                useCustom = profile.useCustomPromptOrder,
                autoEnabled = settings.promptSelection.autoEnabled,
                autoMap = autoMap
            )
            profile.id to order
        }
        val promptOrder = profilePromptOrders[profiles.first().id] ?: listOf(1, 1)
        val workerCount = settings.limits.parallelWorkers.coerceIn(
            TranslationLimits.WORKER_COUNT_RANGE.first,
            TranslationLimits.WORKER_COUNT_RANGE.last
        )
        // 参加プロファイルの最小 maxOutputChars を採用（小型モデルへのローテーション時のトークン溢れを抑える）
        val targetOutputChars = profiles.minOfOrNull { it.maxOutputChars } ?: 15000
        // 技術的根拠1行：文字上限とトークン上限の突合せは CapacityPlanner に一任し、二重管理しない。
        fun modelMaxOf(profile: V2ModelProfile): Int =
            profile.providerId.toProviderId()?.let { descriptors[it] }
                ?.capabilitiesFor(profile.model)?.maxOutputTokens ?: 65536
        val modelMaxTokens = profiles.minOfOrNull { modelMaxOf(it) } ?: 65536
        val effectiveTokens = profiles.minOfOrNull { V2Settings.resolveMaxTokens(it.maxOutputTokens, modelMaxOf(it)) }
            ?: modelMaxTokens
        val plan = V2Settings.planCapacity(effectiveLang, targetOutputChars, effectiveTokens, modelMaxTokens)
        val optimalInputBytes = plan.inputLimitBytes
        val splitThresholdBytes = plan.splitThresholdBytes
        val chunkSizeBytes = plan.chunkSizeBytes
        val batchMaxBytes = plan.batchMaxBytes
        addLog("capacity: limit=${optimalInputBytes}B, chunk=${chunkSizeBytes}B, batch=${batchMaxBytes}B (source=${effectiveLang.name}, maxOutput=${plan.targetOutputChars} chars)")

        val contextTracker = SourceContextTracker(
            files = scopedFiles,
            store = store,
            contextLines = settings.prevContext.lines,
            enabled = settings.prevContext.enabled
        )
        // 技術的根拠1行：束ね決定権は計画＋プールに一本化し、作業者は束単位で受け取る（奪い合いなし・連番維持）。
        val bundlePool = BundlePool(
            planBundles(
                files = scopedFiles,
                isSkipped = { f -> existing.contains(f.name) || existing.contains("${f.name}.failed") },
                forceSingle = { f -> existing.contains(".parts_${f.name}") },
                batchMaxFiles = options.batchMaxFiles,
                batchMaxBytes = batchMaxBytes,
                splitThresholdBytes = splitThresholdBytes
            )
        )

        // 技術的根拠1行：専用判定はワーカー共通のため、文面解決と開始記録は束ね前に1回に寄せる（ worker 毎の再計算・多重記録をなくす）。
        val refineProfile = if (settings.refine.enabled) buildRefineProfile(settings.refine) else null
        if (refineProfile != null) {
            addLog("✨ 推敲専用モデル: ${refineProfile.providerId}/${refineProfile.model}（翻訳とは別経路）")
        }
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
                val router = createRouter(wId, profiles, pool, claimed, settings, meter)
                // 技術的根拠1行：専用モデルは翻訳巡回に載せると訳文側に混入するため、同一資格情報で単一巡回器を別建てする（解放は冪等のため二重でも安全）。
                val refineRouter = refineProfile?.let {
                    createRouter(wId, listOf(it), pool, claimed, settings, meter)
                }
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        if (wId > 1 && options.workerStaggerSec > 0) {
                            patientSleep(
                                options.workerStaggerSec * 1000L * (wId - 1),
                                { stopFlag.get() },
                                { kotlinx.coroutines.delay(it) }
                            )
                            if (stopFlag.get()) return@launch
                        }
                        runWorker(
                            wId, scopedFiles, outputDir.uri, existing, bundlePool,
                            settings, router, novelDict,
                            completed, total,
                            splitThresholdBytes, chunkSizeBytes,
                            effectiveLang, promptOrder,
                            profiles, profilePromptOrders,
                            contextTracker,
                            refineRouter = refineRouter
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        addLog("❌ [W#$wId] worker failed: ${t.message}")
                    } finally {
                        router.release()
                        refineRouter?.release()
                    }
                }
            }
        }
        return completed.get() to total
    }

    private suspend fun processFolder(
        folderUri: String,
        folderName: String,
        settings: V2Settings,
        pool: QuotaPool,
        meter: CostMeter,
        allowFileUris: Set<String> = emptySet()
    ): Pair<Int, Int> {
        if (shouldAbort(pool, settings)) {
            addLog("⚠️ 利用可能なキー枠が枯渇したためフォルダ処理を中止: $folderName")
            stopFlag.set(true)
            return 0 to 0
        }

        val listed = store.children(folderUri)
            .filter {
                !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) &&
                    !LangCacheStore.isCacheFileName(it.name)
            }
            .sortedBy { it.name }
        // 技術的根拠1行：選択スナップショット外の親同胞を翻訳しないよう、実行時列挙を実行時URI一致で絞る(空=従来通り全件)。
        val allTxtFiles = applyFileFilter(listed, allowFileUris)
        if (allowFileUris.isNotEmpty()) {
            addLog("絞り込み: $folderName (選択${allowFileUris.size}件→対象${allTxtFiles.size}件)")
        }
        val outSubDirName = settings.limits.outputSubDir.ifBlank { "翻訳完了_LLM" }
        if (allTxtFiles.isEmpty()) {
            addLog("⚠️ 対象ファイルなし: $folderName (直下に.txtファイルがありません)")
            _state.update { it.copy(statusText = "⚠️ .txtファイルがありません: $folderName") }
            // 小説ファイルが無い場合も出力正本が確定済みなら旧入力配置だけ収束させる（値喪失なし）。
            // 技術的根拠1行：正本参照のみで旧配置を消すため、空フォルダの残骸も翻訳実行なしに単一化できる。
            try {
                val peeked = store.findChild(folderUri, outSubDirName)?.takeIf { it.isDirectory }
                if (peeked != null) LangCacheStore.migrateFromInput(store, folderUri, peeked.uri) { addLog(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                addLog("⚠️ 旧言語キャッシュの移行に失敗: ${t.message}")
            }
            return 0 to 0
        }

        // 技術的根拠1行：既存outputDirがあればその固定値を優先し、無ければ読み取り専用で判定する。書込はexecuteNovelPipeline内でoutputDir作成後に単一所有者が行う。
        _state.update { it.copy(statusText = "🔍 言語判定中: $folderName") }
        val peekedOutputUri = try {
            store.findChild(folderUri, outSubDirName)?.takeIf { it.isDirectory }?.uri
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val sourceLang = detectLanguage(folderUri, allTxtFiles, outputDirUri = peekedOutputUri)

        // オンデマンド物理分割＋直線パイプライン: 翻訳する小説だけ一件ずつ分割し、即座に翻訳して次へ進む。
        // 契約: 一件ずつ分割→即翻訳すること。全件分割してから全件翻訳に戻さないこと。
        // 技術的根拠1行：全件先行分割の無駄I/Oと未翻訳残骸を排し、分割→翻訳を1件ずつ直列化して単一パイプラインにする。
        val rawFiles = allTxtFiles.filter { !it.name.startsWith("part_", ignoreCase = true) }
        if (!settings.split.enabled || rawFiles.isEmpty()) {
            val single = NovelTarget(
                novelName = folderName,
                folderUri = folderUri,
                isPreSplit = false,
                files = allTxtFiles
            )
            val (done, total) = executeNovelPipeline(single, sourceLang, settings, pool, meter)
            return done to total
        }

        val splitRoot = store.findChild(folderUri, "分割済み")
            ?: store.createDir(folderUri, "分割済み")
            ?: run {
                addLog("❌ 「分割済み」フォルダの作成に失敗しました: $folderName")
                return 0 to 0
            }
        val skipped = mutableSetOf<String>()
        val translatedTargets = mutableListOf<NovelTarget>()
        var doneTotal = 0
        var filesTotal = 0
        for ((rawIndex, raw) in rawFiles.withIndex()) {
            if (stopFlag.get() || !coroutineContext.isActive) break
            val target = partitionOne(raw, splitRoot.uri, rawIndex, rawFiles.size, settings) { skipped.add(it) }
            if (target == null) continue
            translatedTargets.add(target)
            val subName = target.novelName
            _state.update { it.copy(statusText = "🚀 分割完了・翻訳開始: $subName") }
            addLog("🚀 [翻訳開始] サブフォルダ: $subName (${target.files.size} パート)")
            val (done, total) = executeNovelPipeline(target, sourceLang, settings, pool, meter)
            doneTotal += done
            filesTotal += total
        }
        if (skipped.isNotEmpty()) {
            addLog("⏭️ 文字化けのため翻訳しません: ${skipped.sorted().joinToString(", ")}")
        }

        if (translatedTargets.isEmpty()) {
            return 0 to 0
        }

        // 事前分割時は親直下の旧キャッシュを、全サブ出力の正本確定後に限り掃除する。
        // 技術的根拠1行：正本未確定のまま親の旧配置を消すと値を失うため、全出力に固定値がある場合のみ収束させる。
        // 技術的根拠1行：中断時は未翻訳の小説が残るため親キャッシュ掃除を見送り、再開時の言語再判定を可能にする。
        if (!stopFlag.get() && coroutineContext.isActive && translatedTargets.any { it.isPreSplit }) {
            try {
                val allPinned = translatedTargets.all { t ->
                    val out = store.findChild(t.folderUri, outSubDirName)?.takeIf { it.isDirectory }
                    out != null && LangCacheStore.load(store, out.uri) != null
                }
                if (allPinned) LangCacheStore.clearInputCaches(store, folderUri) { addLog(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                addLog("⚠️ 親フォルダの旧言語キャッシュ掃除に失敗: ${t.message}")
            }
        }
        return doneTotal to filesTotal
    }

    private suspend fun runWorker(
        workerId: Int,
        files: List<com.example.novelscraper.translation.v2.infra.VDoc>,
        outputDirUri: String,
        existing: MutableSet<String>,
        bundlePool: BundlePool,
        settings: V2Settings,
        router: PromptRouter,
        novelDict: NovelDict?,
        completed: AtomicInteger,
        total: Int,
        splitThresholdBytes: Int,
        chunkSizeBytes: Int,
        sourceLang: SourceLang,
        promptOrder: List<Int>,
        profiles: List<V2ModelProfile>,
        profilePromptOrders: Map<String, List<Int>>,
        contextTracker: SourceContextTracker,
        refineRouter: PromptRouter? = null
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
            splitThresholdBytes = splitThresholdBytes,
            chunkSizeBytes = chunkSizeBytes,
            sourceLang = sourceLang,
            promptOrder = promptOrder,
            profiles = profiles,
            profilePromptOrders = profilePromptOrders,
            contextTracker = contextTracker,
            refineRouter = refineRouter,
            files = files,
            outputDirUri = outputDirUri,
            existing = existing,
            pool = bundlePool,
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

    private fun createRouter(
        workerId: Int,
        profiles: List<V2ModelProfile>,
        pool: QuotaPool,
        claimed: Pair<Int, String>,
        settings: V2Settings,
        meter: CostMeter
    ): PromptRouter {
        // 技術的根拠1行：外側部品（Handler・盤・計量・待機）の束縛はこの Composition Root に集約し、中心部は口だけに依存させる。
        val descriptors = ProviderRegistry.descriptors
        val handlerFactory: (V2ModelProfile, String) -> ProviderHandler = { profile, key ->
            buildHandler(settings, profile, key)
        }
        val sendInterval = if (options.minSendIntervalMs == 0L) {
            settings.limits.requestDelaySec * 1000L
        } else {
            maxOf(settings.limits.requestDelaySec * 1000L, options.minSendIntervalMs)
        }
        val isGemini = profiles.any { it.providerId.toProviderId() == ProviderId.GEMINI }

        return if (isGemini) {
            Rotation(
                workerId = workerId,
                profiles = profiles,
                pool = pool,
                keyIndex = claimed.first,
                key = claimed.second,
                descriptors = descriptors,
                handlerFactory = handlerFactory,
                openRouterKey = settings.openRouterKey,
                geminiCooldownSec = settings.geminiCooldownSec,
                transientRetryDelaySec = settings.transientRetryDelaySec,
                maxSameRetries = options.maxSameRetries,
                sendGate = sendGate,
                sendGateIntervalMs = sendInterval,
                stopped = { stopFlag.get() },
                meter = meter,
                log = { addLog("[W#$workerId] $it") }
            )
        } else {
            UnmanagedRotation(
                workerId = workerId,
                profiles = profiles,
                key = settings.openRouterKey,
                descriptors = descriptors,
                handlerFactory = handlerFactory,
                cooldownSec = settings.geminiCooldownSec,
                transientRetryDelaySec = settings.transientRetryDelaySec,
                maxSameRetries = options.maxSameRetries,
                sendGate = sendGate,
                sendGateIntervalMs = sendInterval,
                stopped = { stopFlag.get() },
                meter = meter,
                log = { addLog("[W#$workerId] $it") }
            )
        }
    }

}
