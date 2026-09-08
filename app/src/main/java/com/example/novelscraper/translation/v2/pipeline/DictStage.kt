package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.isDeterministic
import com.example.novelscraper.translation.v2.domain.isQuotaLike
import com.example.novelscraper.translation.v2.infra.FileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class NovelDict(
    val style: String = "カタカナ",
    val characters: Map<String, String> = emptyMap(),
    val genders: Map<String, String> = emptyMap()
)

@Serializable
data class DictBuildManifest(val version: Int = 1, val batches: Map<String, String> = emptyMap())

data class DictPrompts(
    val batch: String = "Extract person names from the novel text below and output JSON only matching this schema: " +
        "{\"style\":\"カタカナ|漢字|ハイブリッド\",\"characters\":{\"original\":\"Japanese\"}}.\n\n" +
        "【厳格な抽出・判定ルール】\n" +
        "1. 表記スタイルの自動判定 (ハイブリッド対応):\n" +
        "   作品の世界観および人名のルーツから最適なスタイル (\"カタカナ\", \"漢字\", または \"ハイブリッド\") を判定してください。\n" +
        "   - 【カタカナ】: 西洋ファンタジー、現代SF、ゲーム転生、サイバーパンク、韓国現代ドラマ、学園、現代ハンター物\n" +
        "     (※中国語作品であっても、人名が西洋名の音訳「克莱恩 → クライン」「爱丽丝 → アリス」等の場合)\n" +
        "   - 【漢字】: 東洋武侠 (中国武侠・韓国ムヒョプ)、仙侠、修仙、歴史時代劇、三国志系、中華伝統の姓名 (「李云 → 李雲」)\n" +
        "     (※韓国語作品であっても、人名が東洋伝統の漢字名「청명 → 青明」「진무원 → 陳武遠」等の場合)\n" +
        "   - 【ハイブリッド】: 西洋人・東洋人・現代ハンターが混在する作品では、各人名のルーツに合わせて個別に最適な表記 (西洋名はカタカナ、東洋名は漢字/自然な読み) を割り当ててください。\n" +
        "2. 抽出対象 (純粋な人名・固有名詞のみ):\n" +
        "   - 〇 抽出する: 姓名、フルネーム、愛称、ファーストネーム\n" +
        "   - ✕ 抽出しない (厳禁): 役職・肩書 (隊長、師兄、長老、宗主、社長、S級ハンター)、代名詞 (彼、彼女、黒衣人、老者、少年)、一般名詞 (システム、精霊、魔獣)、地名・組織名\n" +
        "3. 類似名・同姓同名の厳格な分離:\n" +
        "   - 「李云」「李云龙」「李云天」「李云海」のように字面が似ていても、それぞれ別人であるため、絶対に1つに統合せず別々のキーとして正確に抽出してください。\n" +
        "4. 出力フォーマット:\n" +
        "   - \"characters\": 原文表記 -> 日本語訳\n" +
        "   - 出力は必ず指定JSON形式のみとすること (前後の解説・挨拶・マークダウン記号は一切不要)。\n\n" +
        "Example: {\"style\":\"ハイブリッド\",\"characters\":{\"김민준\":\"金\",\"하늘\":\"ハヌル\",\"Arthur\":\"アーサー\"}}",
    val merge: String = "Merge the dictionary fragments below into one complete JSON dictionary matching this schema: " +
        "{\"style\":\"カタカナ|漢字|ハイブリッド\",\"characters\":{\"original\":\"Japanese\"}}.\n\n" +
        "【厳格な統合ルール】\n" +
        "1. 世界観の総合判定 & スタイル統一:\n" +
        "   小説全体の舞台設定（西洋ファンタジー・現代・東洋武侠・仙侠等）を深く推論し、作品全体で最も支配的かつ最適な表記スタイル (\"カタカナ\", \"漢字\", または \"ハイブリッド\") を1つ決定してください。\n" +
        "2. 重複排除 & フルネーム優先:\n" +
        "   - 同一人物の表記ゆれ（中黒の有無、長音の違い）は最も自然な1つに統一する。\n" +
        "   - 略称（名前のみ）とフルネーム（姓名）がある場合は【フルネーム】を最優先する。\n" +
        "3. ノイズ削除:\n" +
        "   - 誤って混入した一般名詞・肩書・役職（隊長、師兄、長老、システム等）があれば完全に削除する。\n" +
        "4. 日本語見出し語の維持:\n" +
        "   - 値は必ず自然な日本語（漢字またはカタカナ）を維持し、日本語でないエントリは除外する。\n" +
        "5. 出力フォーマット:\n" +
        "   - 出力は必ず指定JSON形式のみとすること (前後の解説・挨拶・マークダウン記号は一切不要)。",
    val review: String = "Review the merged dictionary below. " +
        "Output ONLY valid JSON matching this exact schema (no markdown, no explanations): " +
        "{\"style\":\"カタカナ|漢字|ハイブリッド\",\"characters\":{\"original\":\"Japanese\"}}.\n\n" +
        "【最終チェック基準】\n" +
        "1. 地名・組織名（門派名、ギルド名、都市名、国名）が混ざっていないか？ ➔ あれば完全に削除\n" +
        "2. 役職・肩書（隊長、師兄、長老、宗主、社長、S級ハンター）が混ざっていないか？ ➔ あれば完全に削除\n" +
        "3. 一般名詞・システム（システムメッセージ、精霊、魔獣、アイテム名）が混ざっていないか？ ➔ あれば完全に削除\n" +
        "4. 表記ゆれの統一・補正:\n" +
        "   - 作品全体で家族名や共通の表記規則が合致しているか確認し、不自然な日本語表記や長音のブレを修正する。\n" +
        "5. すべての値が正しい日本語見出し語（カタカナまたは漢字）になっていることを確認する。\n" +
        "6. 出力は純粋なJSONのみ (前後の説明・コードブロック記号は一切不要)。"
)

data class DictOptions(
    val model: String = "",
    val mergeModel: String = "",
    val thinkingLevel: String? = null,
    val maxFiles: Int = 100,
    val uniformSample: Boolean = true,
    val maxBatchBytes: Int = 100000,
    val maxTotalScanBytes: Int = 10000000,
    /** 既定8。エンジン経路では辞書設定（worker×同時実行数、上限30）で上書きする */
    val parallelism: Int = 8,
    val maxRetriesPerBatch: Int = 4,
    val mergeRetries: Int = 3,
    val reviewRetries: Int = 2,
    val prompts: DictPrompts = DictPrompts()
)

fun sha256Hex(text: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** 部分マージ可否。1件以上の成功があり、欠けが確定的失敗のみの場合のみ真 */
fun mergeDecision(totalBatches: Int, completedCount: Int, hasTransientFailure: Boolean): Boolean {
    return totalBatches > 0 && completedCount in 1 until totalBatches && !hasTransientFailure
}

private val dictJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun extractJsonObject(rawJson: String): String {
    var text = rawJson.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val first = text.indexOf('{')
    val last = text.lastIndexOf('}')
    if (first != -1 && last > first) text = text.substring(first, last + 1)
    return text
}

private fun decodeNovelDictStrict(text: String): NovelDict? {
    return try {
        dictJson.decodeFromString(NovelDict.serializer(), text)
    } catch (_: Exception) {
        null
    }
}

/**
 * 変形JSONの救済（旧版の柔軟パーサー復活）。
 * {"名前": {"name"|"trans"|"japanese": "読み", "gender"|"sex": "男"}} 形式も拾う。
 */
private fun decodeNovelDictFlexible(text: String, allowEmpty: Boolean): NovelDict? {
    return try {
        val element = dictJson.parseToJsonElement(text)
        if (element !is JsonObject) return null
        val style = (element["style"] as? JsonPrimitive)?.contentOrNull ?: "カタカナ"
        val charMap = mutableMapOf<String, String>()
        val genderMap = mutableMapOf<String, String>()
        val charsObj = element["characters"]
        if (charsObj is JsonObject) {
            for ((k, v) in charsObj) {
                when (v) {
                    is JsonPrimitive -> v.contentOrNull?.let { charMap[k] = it }
                    is JsonObject -> {
                        val nameVal = v["name"] ?: v["trans"] ?: v["japanese"]
                        if (nameVal is JsonPrimitive) nameVal.contentOrNull?.let { charMap[k] = it }
                        val gVal = v["gender"] ?: v["sex"]
                        if (gVal is JsonPrimitive) gVal.contentOrNull?.let { genderMap[k] = it }
                    }
                    else -> {}
                }
            }
        } else {
            for ((k, v) in element) {
                if (k == "style" || k == "genders" || k == "characters") continue
                if (v is JsonPrimitive) {
                    v.contentOrNull?.let { charMap[k] = it }
                } else if (v is JsonObject) {
                    val nameVal = v["name"] ?: v["trans"] ?: v["japanese"]
                    if (nameVal is JsonPrimitive) nameVal.contentOrNull?.let { charMap[k] = it }
                    val gVal = v["gender"] ?: v["sex"]
                    if (gVal is JsonPrimitive) gVal.contentOrNull?.let { genderMap[k] = it }
                }
            }
        }
        val gendersObj = element["genders"]
        if (gendersObj is JsonObject) {
            for ((k, v) in gendersObj) {
                if (v is JsonPrimitive) v.contentOrNull?.let { genderMap[k] = it }
            }
        }
        if (!allowEmpty && charMap.isEmpty()) return null
        NovelDict(style = style, characters = charMap, genders = genderMap)
    } catch (_: Exception) {
        null
    }
}

fun parseNovelDict(rawJson: String): NovelDict? {
    return try {
        val text = extractJsonObject(rawJson)
        decodeNovelDictStrict(text)?.takeIf { it.characters.isNotEmpty() }
            ?: decodeNovelDictFlexible(text, allowEmpty = false)
    } catch (_: Exception) {
        null
    }
}

/** 構文妥当性の判定用。人名ゼロでも有効とみなす（空辞書の完成判定に使う）。 */
fun parseNovelDictLenient(rawJson: String): NovelDict? {
    return try {
        val text = extractJsonObject(rawJson)
        decodeNovelDictStrict(text) ?: decodeNovelDictFlexible(text, allowEmpty = true)
    } catch (_: Exception) {
        null
    }
}

private fun isJapaneseHeadword(text: String): Boolean {
    for (ch in text) {
        when (ch) {
            in '\u3040'..'\u309F', in '\u30A0'..'\u30FF', in '\uFF61'..'\uFF9F',
            in '\u4E00'..'\u9FFF', in '\u3400'..'\u4DBF' -> return true
        }
    }
    return false
}

/**
 * 日本語見出しでない項目の除去。抽出が原文表記をそのまま値にした場合（韓国語のまま等）を
 * 黙って採用しないための検査。性別表も生き残り項目に連動して刈る。
 */
fun sanitizeNovelDict(dict: NovelDict): NovelDict {
    if (dict.characters.isEmpty()) return dict
    val kept = dict.characters.filterValues { isJapaneseHeadword(it) }
    if (kept.size == dict.characters.size) return dict
    return dict.copy(characters = kept, genders = dict.genders.filterKeys { it in kept })
}

fun selectSampleFiles(fileNames: List<String>, maxFiles: Int, uniform: Boolean): List<String> {
    if (fileNames.isEmpty()) return emptyList()
    if (maxFiles <= 0 || fileNames.size <= maxFiles) return fileNames
    if (!uniform) return fileNames.take(maxFiles)
    val headCount = (maxFiles * 0.5).toInt().coerceAtLeast(1)
    val midCount = (maxFiles * 0.25).toInt().coerceAtLeast(1)
    val tailCount = (maxFiles - headCount - midCount).coerceAtLeast(1)
    val total = fileNames.size
    val indices = linkedSetOf<Int>()
    for (i in 0 until headCount.coerceAtMost(total)) indices.add(i)
    val midStart = (total / 2 - midCount / 2).coerceIn(0, total - 1)
    for (i in midStart until (midStart + midCount).coerceIn(0, total)) indices.add(i)
    for (i in (total - tailCount).coerceIn(0, total) until total) indices.add(i)
    var cursor = 0
    while (indices.size < maxFiles && cursor < total) {
        indices.add(cursor++)
    }
    return indices.sorted().take(maxFiles).map { fileNames[it] }
}

/**
 * 辞書生成stage。`call` は巡回・待機を適用済みの呼出側 binding を受け取る。
 * 失敗種別：BLOCKED/CONFIG＝確定的、QUOTA/RETRYABLE/FATAL＝一時的。
 * 技術的根拠1行：本文はファイル名のサンプリング後に1件ずつ遅延読込し、全文リストをメモリに抱えない。
 */
suspend fun generateDictionary(
    store: FileStore,
    workDirUri: String,
    fileNames: List<String>,
    readText: suspend (name: String) -> String?,
    call: suspend (model: String, prompt: String, text: String) -> LlmResult,
    options: DictOptions,
    log: (String) -> Unit = {}
): NovelDict? = supervisorScope {
    val sampledNames = selectSampleFiles(fileNames, options.maxFiles, options.uniformSample)
    if (sampledNames.isEmpty()) {
        log("📖 辞書生成: 対象ファイルがないため中止します")
        return@supervisorScope null
    }

    // バッチ化（合計スキャン上限付き）
    val batches = mutableListOf<String>()
    val buffer = StringBuilder()
    var bufferBytes = 0
    var scannedBytes = 0
    for (name in sampledNames) {
        if (scannedBytes >= options.maxTotalScanBytes) {
            log("📖 辞書生成: 走査上限に達しました（残りは対象外）")
            break
        }
        val clean = readText(name)?.trim() ?: continue
        if (clean.isEmpty()) continue
        val bytes = utf8Bytes(clean)
        scannedBytes += bytes
        if (bufferBytes + bytes > options.maxBatchBytes && buffer.isNotEmpty()) {
            batches.add(buffer.toString())
            buffer.clear()
            bufferBytes = 0
        }
        if (buffer.isNotEmpty()) {
            buffer.append("\n\n")
            bufferBytes += 2
        }
        buffer.append(clean)
        bufferBytes += bytes
    }
    if (buffer.isNotEmpty()) batches.add(buffer.toString())
    if (batches.isEmpty()) {
        log("📖 辞書生成: 有効な本文がないため中止します")
        return@supervisorScope null
    }
    val totalBatches = batches.size
    val effectiveParallelism = options.parallelism.coerceIn(1, 30)
    log("📖 辞書生成 開始（対象:${sampledNames.size}ファイル / 全${totalBatches}バッチ[上限:${options.maxBatchBytes}B] / 並列${effectiveParallelism} / モデル:${options.model}）")

    // manifest読込
    val manifestName = "manifest.json"
    val manifestCache = mutableMapOf<String, String>()
    runCatching {
        val doc = store.findChild(workDirUri, manifestName)
        val raw = doc?.let { store.readText(it.uri) }
        if (!raw.isNullOrBlank()) {
            val parsed = dictJson.decodeFromString(DictBuildManifest.serializer(), raw)
            manifestCache.putAll(parsed.batches)
        }
    }
    val manifestMutex = Mutex()
    suspend fun persistManifest() {
        try {
            val doc = findOrCreateFile(store, workDirUri, manifestName, "application/json")
            if (doc != null) {
                store.writeText(
                    doc.uri,
                    dictJson.encodeToString(DictBuildManifest.serializer(), DictBuildManifest(batches = manifestCache.toMap()))
                )
            }
        } catch (e: Exception) {
            log("📖 辞書生成: 進捗保存に失敗しました（${e.message}）")
        }
    }
    fun cacheFresh(batchFileName: String, batchText: String): Boolean {
        if (manifestCache.isEmpty()) return true
        val expected = manifestCache[batchFileName] ?: return true
        return expected == sha256Hex(batchText)
    }

    val deterministicFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    val transientFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    val semaphore = Semaphore(effectiveParallelism)

    val results = batches.mapIndexed { index, batchText ->
        val batchNum = index + 1
        val batchFileName = "batch_" + String.format("%04d", batchNum) + ".json"
        async {
            try {
                semaphore.withPermit {
                    val existing = store.findChild(workDirUri, batchFileName)
                    val cached = existing?.let { store.readText(it.uri) }
                    if (!cached.isNullOrBlank()) {
                        val parsed = parseNovelDict(cached)
                        if (parsed != null && parsed.characters.isNotEmpty()) {
                            if (cacheFresh(batchFileName, batchText)) {
                                log("  📖 辞書生成: バッチ$batchNum/$totalBatches（完了済み/スキップ）")
                                return@withPermit cached
                            }
                            log("  📖 辞書生成: バッチ$batchNum/$totalBatches（内容変更のため再取得）")
                        } else {
                            log("  📖 辞書生成: バッチ$batchNum/$totalBatches（保存分が壊れているため再取得）")
                        }
                        existing?.let { store.deleteFile(it.uri) }
                    }
                    if (batchText.isBlank()) {
                        deterministicFailed.add(batchNum)
                        return@withPermit null
                    }

                    val maxRetries = options.maxRetriesPerBatch.coerceIn(0, 8)
                    var sawTransient = false
                    for (retry in 0..maxRetries) {
                        if (retry == 0) {
                            log("  📖 辞書生成: バッチ$batchNum/$totalBatches 抽出中...")
                        }
                        when (val result = call(options.model, options.prompts.batch, batchText)) {
                            is LlmResult.Success -> {
                                val doc = findOrCreateFile(store, workDirUri, batchFileName, "application/json")
                                if (doc != null) store.writeText(doc.uri, result.text.trim())
                                manifestMutex.withLock {
                                    manifestCache[batchFileName] = sha256Hex(batchText)
                                    persistManifest()
                                }
                                log("  📖 辞書生成: バッチ$batchNum/$totalBatches 完了")
                                return@withPermit result.text
                            }
                            is LlmResult.Failure -> {
                                if (result.failure.kind.isDeterministic()) {
                                    log("  ⚠️ 辞書生成: バッチ$batchNum 確定失敗のため再試行しません（${result.failure.kind}）")
                                    break
                                }
                                // 技術的根拠1行：FATAL（JSON崩れ等）は再送するが全体保留にはしない（他バッチで部分マージ可）。
                                if (result.failure.kind.isQuotaLike()) {
                                    sawTransient = true
                                }
                                if (retry >= maxRetries) break
                                log("  🔄 辞書生成: バッチ$batchNum/$totalBatches 再試行[${retry + 1}/$maxRetries]")
                            }
                        }
                    }
                    if (sawTransient) transientFailed.add(batchNum) else deterministicFailed.add(batchNum)
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("  ⚠️ 辞書生成: バッチ$batchNum 処理中にエラー発生 (${e::class.java.simpleName}: ${e.message})")
                deterministicFailed.add(batchNum)
                null
            }
        }
    }.awaitAll()

    val completed = results.filterNotNull()
    if (completed.size < totalBatches &&
        !mergeDecision(totalBatches, completed.size, transientFailed.isNotEmpty())
    ) {
        log("⚠️ 辞書生成: 未完了バッチあり（${completed.size}/$totalBatches）。確定を保留し次回に持ち越します")
        return@supervisorScope null
    }
    if (completed.size < totalBatches) {
        val skipped = deterministicFailed.sorted().joinToString(",")
        log("📖 辞書生成: 一部確定（${completed.size}/$totalBatches）。確定失敗バッチ[$skipped]を除いて統合します")
    }

    // 【辞書生成の2段階設計（マージ ＆ レビューの意図的分離）】:
    // マージ（各バッチ断片の統合・粗抽出）とレビュー（地名・組織名・肩書・一般名詞の除去および表記揺れの統一）は
    // 目的と評価軸が全く異なるため、あえて2回に分けてLLMを呼び出すことで人名抽出精度を最大限に高めている。
    // （1工程にまとめると一般名詞の誤混入や表記ブレが劇的に増大するため、2工程で精度を担保する）。
    val reviewModel = options.mergeModel.ifBlank { options.model }
    val effectiveMergeRetries = options.mergeRetries.coerceIn(1, 5)
    val mergedOrNullInitial: NovelDict? = if (completed.size == 1) {
        parseNovelDict(completed.first())
    } else {
        val mergeInput = completed.mapIndexed { i, json -> "[part${i + 1}]\n$json" }.joinToString("\n\n")
        var parsed: NovelDict? = null
        for (retry in 0 until effectiveMergeRetries) {
            if (retry > 0) {
                log("  🔄 辞書統合: 再試行[$retry/$effectiveMergeRetries]")
            } else {
                log("🔄 辞書統合: 実行中（${completed.size}断片）...")
            }
            when (val r = call(reviewModel, options.prompts.merge, mergeInput)) {
                is LlmResult.Success -> {
                    parsed = parseNovelDict(r.text)
                    if (parsed != null) break
                }
                is LlmResult.Failure -> {
                    // 技術的根拠1行：巡回・待機は呼出側callの責務のため、ステージ側での二重待機は行わない。
                }
            }
        }
        parsed
    }
    var mergedOrNull = mergedOrNullInitial
    if (mergedOrNull == null) {
        // 空辞書の確定：全断片が構文上有効かつ人名ゼロなら空のまま完成扱いにする。
        // 技術的根拠1行：人名なし書籍では空が正解であり、保留にするとフォルダ全体が永久停止するため。
        val lenient = completed.mapNotNull { parseNovelDictLenient(it) }
        if (lenient.size == completed.size && lenient.isNotEmpty() && lenient.all { it.characters.isEmpty() }) {
            log("✅ 辞書確定: 人名なし（空で確定）")
            mergedOrNull = NovelDict(style = lenient.first().style)
        }
    }
    val merged = mergedOrNull
    if (merged == null) {
        log("⚠️ 辞書生成: 統合に失敗したため保留します（次回再挑戦）")
        return@supervisorScope null
    }

    // 技術的根拠1行：原文表記のままの値（韓国語のまま等）を黙って採用しない。落とした分は大声で記録する。
    // 空確定分（人名ゼロ）は検査対象外でそのまま通す。
    var reviewed: NovelDict = merged
    if (merged.characters.isNotEmpty()) {
        val sanitized = sanitizeNovelDict(merged)
        val dropped = merged.characters.size - sanitized.characters.size
        if (dropped > 0) {
            val sample = (merged.characters.keys - sanitized.characters.keys).take(3).joinToString(",")
            log("⚠️ 辞書生成: 日本語でない${dropped}件を除外 (例: ${sample})")
        }
        if (sanitized.characters.isEmpty()) {
            log("⚠️ 辞書生成: 使える項目ゼロのため保留します（次回再挑戦）")
            return@supervisorScope null
        }
        reviewed = sanitized
    }
    if (reviewed.characters.isNotEmpty()) {
        val effectiveReviewRetries = options.reviewRetries.coerceIn(1, 3)
        val reviewInput = dictJson.encodeToString(NovelDict.serializer(), reviewed)
        for (retry in 0 until effectiveReviewRetries) {
            if (retry > 0) {
                log("  🔄 辞書レビュー: 再試行[$retry/$effectiveReviewRetries]")
            } else {
                log("🔍 辞書生成: 最終レビュー中（${reviewed.characters.size}件）...")
            }
            when (val r = call(reviewModel, options.prompts.review, reviewInput)) {
                is LlmResult.Success -> {
                    val parsed = parseNovelDict(r.text)?.let { sanitizeNovelDict(it) }
                    if (parsed != null && parsed.characters.isNotEmpty()) {
                        reviewed = parsed
                        break
                    }
                    log("  ⚠️ 辞書レビュー: 結果が空のため採用しません")
                }
                is LlmResult.Failure -> {
                    // 技術的根拠1行：巡回・待機は呼出側callの責務のため、ステージ側での二重待機は行わない。
                }
            }
        }
    }

    // 確定保存＋作業所掃除
    val dictDoc = findOrCreateFile(store, workDirUri, "dictionary.json", "application/json")
    if (dictDoc != null && store.writeText(
            dictDoc.uri,
            dictJson.encodeToString(NovelDict.serializer(), reviewed)
        )
    ) {
        log("✅ 辞書確定: ${reviewed.characters.size}名")
        return@supervisorScope reviewed
    }
    log("⚠️ 辞書生成: 保存に失敗したため保留します")
    return@supervisorScope null
}
