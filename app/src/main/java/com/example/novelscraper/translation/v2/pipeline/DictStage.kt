package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.isDeterministic
import com.example.novelscraper.translation.v2.domain.isQuotaLike
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.settings.V2DictPrompts
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
    val genders: Map<String, String> = emptyMap(),
    /** 生成時文面のハッシュ。空＝旧形式（照合時は不一致扱いで作り直す） */
    val promptsHash: String = ""
)

@Serializable
data class ExtractedNames(
    val names: List<String> = emptyList(),
    /** 作者署名・注記・宣伝文由来の名前。名寄せ・命名には使わず機械除外する。欠落時は空扱い。 */
    val authors: List<String> = emptyList()
)

@Serializable
data class DictBuildManifest(val version: Int = 1, val batches: Map<String, String> = emptyMap(), val model: String = "", val promptsHash: String = "")

data class DictPrompts(
    val batch: String = "Extract person names (pure character names) from the novel text below.\n" +
        "Output ONLY valid JSON matching this exact schema: {\"names\":[\"Name1\",\"Name2\"],\"authors\":[\"Author1\"]}.\n\n" +
        "【厳格な抽出ルール】\n" +
        "1. 抽出対象 (純粋な人名・固有名詞のみ):\n" +
        "   - 〇 抽出する: 登場人物のフルネーム、姓、名、愛称、ファーストネーム (原文表記のまま)\n" +
        "   - ✕ 抽出禁止 (厳禁): 役職・肩書 (隊長、長老、宗主、社長、師兄、ハンター等)、代名詞 (彼、彼女、黒衣人、老人、少年等)、一般名詞 (システム、精霊、魔獣、スキル名、アイテム等)、地名・組織名・ギルド名・門派名\n" +
        "   - 作者署名・作者注記・宣伝文の中の名前はnamesに入れずauthorsに入れること (例: 作者：○○→authors。物語本文中の登場人物は通常通りnamesへ。両方に出る名前はnamesを優先する)。\n" +
        "   - 1字だけの名前は抽出しないこと (2文字以上のみ。1字は一般語と区別できないため)。\n" +
        "   - 韓国語は助詞・語尾を剥がして素形で抽出すること (例: 「사재혁이」→「사재혁」。文法部品は人名にしない。例: 「물이나」の「이나」は文法なので抽出しない)。\n" +
        "2. 日本語訳は絶対に含めないこと:\n" +
        "   - 原文テキストに登場する表記そのままで配列に格納してください (訳語・読み仮名の付与は厳禁)。\n" +
        "3. 類似名・同姓同名の厳格な分離:\n" +
        "   - 「李云」「李云龙」「李云天」のように似ていても、それぞれ別人であるため、絶対に1つに統合せず別々の名前として漏れなく抽出してください。\n" +
        "4. 出力フォーマット:\n" +
        "   - 解説・挨拶・マークダウン記号は一切不要。純粋なJSONのみを出力してください。\n\n" +
        "Example: {\"names\":[\"克莱恩\",\"周明瑞\",\"李云龙\",\"Arthur\",\"사재혁\",\"목진우\"],\"authors\":[]}",

    val merge: String = "Merge the dictionary names below into one clean JSON list. The input is already deduplicated by exact match.\n" +
        "Output ONLY valid JSON matching this exact schema: {\"names\":[\"Name1\",\"Name2\"]}.\n\n" +
        "【厳格な名寄せ・重複排除ルール】\n" +
        "1. 重複排除 & 短形の保持:\n" +
        "   - 完全一致の重複は1つにまとめる。\n" +
        "   - 「·」区切りのフルネームとその構成要素の短形（例: 「李维·史奈克」と「李维」）は【フルネーム】に統一する（短形の訳は適用時に部品から復元するため、ここでは落としてよい）。\n" +
        "   - 「·」区切りのないペア（例: 「太郎」と「田中太郎」、「李云」と「李云龙」）は別人である可能性があるため、絶対に統合せず両方とも維持する。\n" +
        "   - 1字の名前は登録しないこと（2文字以上のみ）。\n" +
        "2. ノイズの徹底削除:\n" +
        "   - 誤って混入した一般名詞・肩書・役職（隊長、長老、宗主、社長、システム等）、地名・組織名があれば完全に削除する。\n" +
        "3. 日本語訳は絶対に含めないこと:\n" +
        "   - 必ず原文表記の配列として出力してください。\n" +
        "4. 出力フォーマット:\n" +
        "   - 出力は指定のJSON形式のみ (前後の解説・マークダウン記号は一切不要)。\n\n" +
        "Example: {\"names\":[\"克莱恩\",\"周明瑞\",\"李云龙\",\"Arthur\"]}",

    val translate: String = "Review the merged character names below and create a complete Japanese translation dictionary.\n" +
        "Output ONLY valid JSON matching this exact schema (no markdown, no explanations): " +
        "{\"style\":\"カタカナ|漢字|ハイブリッド\",\"characters\":{\"OriginalName\":\"JapaneseName\"}}.\n\n" +
        "【厳格な命名・翻訳ルール】（言語別基準：中国語＝漢字優先、韓国語＝カタカナ既定）\n" +
        "1. 表記スタイルの自動判定 & 作品全体での統一:\n" +
        "   中国語名は語源で判定し、迷う場合は漢字表記を優先すること。明らかな西洋音訳名のみカタカナに音訳し（克莱恩→クライン等）、中華名・意味の取れる複合名（黑山→黒山等）は日本の常用漢字・新字体に復元すること（李云→李雲等）。西洋と断定できない中国語名は漢字にすること。\n" +
        "   韓国語名はカタカナを既定とし（사재혁→サ・ジェヒョク等）、漢字ルーツが明確で日本語として自然な場合のみ漢字可。迷う場合はカタカナにすること。\n" +
        "2. 1対1の正確な対応（名前の取り違え・混同は厳禁）:\n" +
        "   - 入力されたすべての原文名をキーとし、それぞれに正確に対応する自然な日本語訳を値として設定してください。\n" +
        "   - 似た名前同士（例: 「李云」と「李云龙」）で訳語が入れ替わったり混ざったりしないよう、厳密に対応させてください。\n" +
        "3. 最終ノイズ除去:\n" +
        "   - 地名、組織名、役職、一般名詞が残っている場合は除外（キーに含めない）してください。\n" +
        "4. すべての値は自然な日本語（カタカナまたは漢字）であること。\n" +
        "5. 出力フォーマット:\n" +
        "   - 純粋なJSONのみを出力すること (解説・挨拶・コードブロック記号は一切不要)。\n\n" +
        "Example: {\"style\":\"ハイブリッド\",\"characters\":{\"克莱恩\":\"クライン\",\"奥黛丽\":\"オードリー\",\"李云\":\"李雲\",\"李云龙\":\"李雲龍\",\"黑山\":\"黒山\",\"김민준\":\"キム・ミンジュン\",\"사재혁\":\"サ・ジェヒョク\"}}",

    // 後方互換用エイリアス
    val review: String = translate
)

/**
 * 辞書用プロンプトの解決（唯一の入口）。空・空白は既定文に落とす。3文独立。
 * 技術的根拠1行：解決則の二重実装は必ず乖離するため、設定→実行文の変換はここだけに置く。
 */
fun resolveDictPrompts(custom: V2DictPrompts, defaults: DictPrompts = DictPrompts()): DictPrompts {
    fun pick(raw: String, fallback: String): String {
        val cap = TranslationLimits.MAX_DICT_PROMPT_CHARS
        val t = if (raw.length > cap) raw.substring(0, cap) else raw
        return t.ifBlank { fallback }
    }
    return DictPrompts(
        batch = pick(custom.batch, defaults.batch),
        merge = pick(custom.merge, defaults.merge),
        translate = pick(custom.translate, defaults.translate)
    )
}

/** 文面ハッシュ（台帳照合用）。先頭16桁で辞書の作り直し判定に使う。 */
fun dictPromptsHash(prompts: DictPrompts): String =
    sha256Hex(prompts.batch + "\n" + prompts.merge + "\n" + prompts.translate).take(16)

data class DictOptions(
    val model: String = "",
    val mergeModel: String = "",
    val maxFiles: Int = 100,
    val uniformSample: Boolean = true,
    val maxBatchBytes: Int = 100000,
    val maxTotalScanBytes: Int = 10000000,
    /** 既定8。エンジン経路では辞書設定（worker×同時実行数、上限30）で上書きする */
    val parallelism: Int = 8,
    val maxRetriesPerBatch: Int = 4,
    val mergeRetries: Int = 3,
    val reviewRetries: Int = 2,
    val prompts: DictPrompts = DictPrompts(),
    /** 文面ハッシュ。台帳照合用（呼出側が解決済み文面から算出して渡す） */
    val promptsHash: String = ""
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
 * 辞書デコードの単一実装。厳格→変形の順で試し、空の扱いだけ切替える。
 * 技術的根拠1行：二関数の分岐差は空許容の1条件のみのため、分岐自体を一本化して乖離をなくす。
 */
private fun decodeNovelDict(text: String, allowEmpty: Boolean): NovelDict? {
    decodeNovelDictStrict(text)?.takeIf { allowEmpty || it.characters.isNotEmpty() }?.let { return it }
    return decodeNovelDictFlexible(text, allowEmpty)
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
        val promptsHash = (element["promptsHash"] as? JsonPrimitive)?.contentOrNull ?: ""
        val charMap = mutableMapOf<String, String>()
        val genderMap = mutableMapOf<String, String>()
        // 技術的根拠1行: charactersオブジェクト内外での同一パース処理のコピペ重複を排除し保守性を向上させる。
        val extractEntry = { k: String, v: kotlinx.serialization.json.JsonElement ->
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
        val charsObj = element["characters"]
        if (charsObj is JsonObject) {
            for ((k, v) in charsObj) extractEntry(k, v)
        } else {
            for ((k, v) in element) {
                if (k == "style" || k == "genders" || k == "characters") continue
                extractEntry(k, v)
            }
        }
        val gendersObj = element["genders"]
        if (gendersObj is JsonObject) {
            for ((k, v) in gendersObj) {
                if (v is JsonPrimitive) v.contentOrNull?.let { genderMap[k] = it }
            }
        }
        if (!allowEmpty && charMap.isEmpty()) return null
        NovelDict(style = style, characters = charMap, genders = genderMap, promptsHash = promptsHash)
    } catch (_: Exception) {
        null
    }
}

/**
 * 原文名リスト（{"names": ["..."]}）のパース。
 * 素の配列（["..."]）や旧形式（{"characters": {...}}）も救済して原文名リストを復元する。
 * authors欠落時（旧形式）は空扱いとする。
 */
private fun stringListOf(element: kotlinx.serialization.json.JsonObject, key: String): List<String> {
    val arr = element[key] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }.distinct()
}

fun parseExtractedNames(rawJson: String): ExtractedNames? {
    return try {
        val text = extractJsonObject(rawJson)
        try {
            val direct = dictJson.decodeFromString(ExtractedNames.serializer(), text)
            if (direct.names.isNotEmpty()) return direct
        } catch (_: Exception) {}

        val element = dictJson.parseToJsonElement(text)
        if (element is JsonObject) {
            val namesArr = element["names"] as? kotlinx.serialization.json.JsonArray
            if (namesArr != null) {
                val list = namesArr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }
                return ExtractedNames(names = list.distinct(), authors = stringListOf(element, "authors"))
            }
            val chars = element["characters"]
            if (chars is JsonObject) {
                val list = chars.keys.map { it.trim() }.filter { it.isNotBlank() }
                return ExtractedNames(names = list.distinct(), authors = stringListOf(element, "authors"))
            } else if (chars is kotlinx.serialization.json.JsonArray) {
                val list = chars.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }
                return ExtractedNames(names = list.distinct(), authors = stringListOf(element, "authors"))
            }
            // 技術的根拠1行：名簿の構造がない応答は無効とし、空辞書としての誤採用（永久キャッシュ化）を防ぐ。
            return null
        }
        val topArray = element as? kotlinx.serialization.json.JsonArray
        if (topArray != null) {
            val list = topArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }
            return ExtractedNames(names = list.distinct())
        }
        return null
    } catch (_: Exception) {
        try {
            val trimmed = rawJson.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val first = trimmed.indexOf('[')
            val last = trimmed.lastIndexOf(']')
            if (first != -1 && last > first) {
                val arr = dictJson.parseToJsonElement(trimmed.substring(first, last + 1)) as? kotlinx.serialization.json.JsonArray
                if (arr != null) {
                    val list = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }
                    return ExtractedNames(names = list.distinct())
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

fun parseNovelDict(rawJson: String): NovelDict? {
    return try {
        decodeNovelDict(extractJsonObject(rawJson), allowEmpty = false)
    } catch (_: Exception) {
        null
    }
}

/** 確定辞書の書出し（唯一の口）。読込側（lenient含む）と形式を合わせる。 */
fun encodeNovelDict(dict: NovelDict): String =
    dictJson.encodeToString(NovelDict.serializer(), dict)

/** 構文妥当性の判定用。人名ゼロでも有効とみなす（空辞書の完成判定に使う）。 */
fun parseNovelDictLenient(rawJson: String): NovelDict? {
    return try {
        decodeNovelDict(extractJsonObject(rawJson), allowEmpty = true)
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
 * 失敗種別：BLOCKED/CONFIG＝確定的、QUOTA/RETRYABLE＝一時的。FATALは上限内で再送し、尽きたら確定側へ倒す。
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
        // 技術的根拠1行：物理分割OFF時などの大ファイルでバッチ上限（トークン溢れ・413エラー）を超えないようチャンク分割して詰める。
        val pieces = if (utf8Bytes(clean) > options.maxBatchBytes) {
            splitIntoChunks(clean, options.maxBatchBytes)
        } else {
            listOf(clean)
        }
        var reachedScanLimit = false
        for (piece in pieces) {
            if (scannedBytes >= options.maxTotalScanBytes) {
                log("📖 辞書生成: 走査上限に達しました（残りは対象外）")
                reachedScanLimit = true
                break
            }
            val bytes = utf8Bytes(piece)
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
            buffer.append(piece)
            bufferBytes += bytes
        }
        if (reachedScanLimit) break
    }
    if (buffer.isNotEmpty()) batches.add(buffer.toString())
    if (batches.isEmpty()) {
        log("📖 辞書生成: 有効な本文がないため中止します")
        return@supervisorScope null
    }
    val totalBatches = batches.size
    val effectiveParallelism = options.parallelism.coerceIn(1, 30)
    log("📖 辞書生成 開始（対象:${sampledNames.size}ファイル / 全${totalBatches}バッチ[上限:${options.maxBatchBytes}B] / 並列${effectiveParallelism} / モデル:${options.model}）")

    // manifest読込（項目検証つき自己修復。不正エントリは落として作り直す）
    val manifestName = "manifest.json"
    val manifestCache = mutableMapOf<String, String>()
    var manifestModel = ""
    var manifestPromptsHash = ""
    runCatching {
        val doc = store.findChild(workDirUri, manifestName)
        val raw = doc?.let { store.readText(it.uri) }
        if (!raw.isNullOrBlank()) {
            val parsed = dictJson.decodeFromString(DictBuildManifest.serializer(), raw)
            manifestModel = parsed.model
            manifestPromptsHash = parsed.promptsHash
            manifestCache.putAll(parsed.batches)
        }
    }.onFailure {
        log("📖 辞書生成: 宣言書が壊れているため作り直します")
    }
    // 技術的根拠1行：宣言書は原文のハッシュを持つため、実ファイルの有無だけを照合する（内容照合はcacheFresh側の責務）。
    var manifestHealed = false
    runCatching {
        val dropped = manifestCache.keys.filter { name ->
            store.findChild(workDirUri, name)?.takeIf { !it.isDirectory } == null
        }
        for (name in dropped) {
            manifestCache.remove(name)
            log("📖 辞書生成: 保存分のない宣言を落として作り直します ($name)")
        }
        if (dropped.isNotEmpty()) manifestHealed = true
    }
    val manifestMutex = Mutex()
    suspend fun persistManifest() {
        try {
            val doc = findOrCreateFile(store, workDirUri, manifestName, "application/json")
            if (doc != null) {
                store.writeText(
                    doc.uri,
                    dictJson.encodeToString(
                        DictBuildManifest.serializer(),
                        DictBuildManifest(batches = manifestCache.toMap(), model = options.model, promptsHash = options.promptsHash)
                    )
                )
            }
        } catch (e: Exception) {
            log("📖 辞書生成: 進捗保存に失敗しました（${e.message}）")
        }
    }
    // 技術的根拠1行: manifest欠損時やエントリ不在時に古い誤キャッシュを採用しないようFail-Closed（false返却）とする。
    // 技術的根拠1行：モデル変更時は本文一致でも取り直す（別モデルの抽出結果の混用を防ぐ）。
    // 技術的根拠1行：文面変更時も取り直す（古い文面の抽出結果の混用を防ぐ）。
    fun cacheFresh(batchFileName: String, batchText: String): Boolean {
        if (manifestModel != options.model) return false
        if (manifestPromptsHash != options.promptsHash) return false
        val expected = manifestCache[batchFileName] ?: return false
        return expected == sha256Hex(batchText)
    }
    if (manifestHealed) persistManifest()

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
                        // 技術的根拠1行: 人名0件の正常な空バッチの誤破棄（毎回の再取得ループ）を防ぐためExtractedNamesパースで検証する。
                        val parsed = parseExtractedNames(cached)
                        if (parsed != null) {
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
                            log("  📖 辞書生成: バッチ$batchNum/$totalBatches 原文人名抽出中...")
                        }
                        when (val result = call(options.model, options.prompts.batch, batchText)) {
                            is LlmResult.Success -> {
                                val parsed = parseExtractedNames(result.text)
                                if (parsed == null) {
                                    // 技術的根拠1行：解析不能な成功は確定させず、上限内で取り直す（ゴミの完成計上と空確定の連鎖を断つ）。
                                    if (retry >= maxRetries) break
                                    log("  🔄 辞書生成: バッチ$batchNum/$totalBatches 解析失敗のため再試行[${retry + 1}/$maxRetries]")
                                    continue
                                }
                                val jsonToSave = dictJson.encodeToString(ExtractedNames.serializer(), parsed)
                                val doc = findOrCreateFile(store, workDirUri, batchFileName, "application/json")
                                if (doc != null) store.writeText(doc.uri, jsonToSave)
                                manifestMutex.withLock {
                                    manifestCache[batchFileName] = sha256Hex(batchText)
                                    persistManifest()
                                }
                                log("  📖 辞書生成: バッチ$batchNum/$totalBatches 抽出完了（${parsed.names.size}名）")
                                return@withPermit jsonToSave
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

    // 全バッチからの抽出人名リスト（重複排除）
    val parsedBatches = completed.mapNotNull { parseExtractedNames(it) }
    val allExtractedNames = parsedBatches.flatMap { it.names }.distinct()
    // 技術的根拠1行：作者由来名の除外は名寄せの判断に委ねず、抽出時の区分で機械的に落とす（names優先で誤除外を防ぐ）。
    val excludedAuthors = (parsedBatches.flatMap { it.authors }.distinct() - allExtractedNames.toSet())
    if (excludedAuthors.isNotEmpty()) {
        log("📖 辞書生成: 作者として除外 (${excludedAuthors.size}名: ${excludedAuthors.take(3).joinToString(",")})")
    }
    if (allExtractedNames.isEmpty()) {
        // 技術的根拠1行：検証済みの空（全件正常・人名なし）のみ空確定し、未確定分がある場合は持ち越す（空の誤確定・永久化を防ぐ）。
        if (deterministicFailed.isEmpty() && transientFailed.isEmpty()) {
            log("✅ 辞書確定: 人名なし（空で確定）")
            val emptyDict = NovelDict()
            val dictDoc = findOrCreateFile(store, workDirUri, "dictionary.json", "application/json")
            if (dictDoc != null && store.writeText(dictDoc.uri, dictJson.encodeToString(NovelDict.serializer(), emptyDict))) {
                return@supervisorScope emptyDict
            }
            return@supervisorScope null
        }
        log("⚠️ 辞書生成: 未確定バッチがあるため空確定せず次回に持ち越します")
        return@supervisorScope null
    }

    // 【Step 2: 名寄せ・重複排除（マージ）】
    // 原文表記のままで重複や·式略称を統合し、純粋な原文名リストを確定する（訳語がないため混ざる余地がない）。
    // ·無しペアは統合せず残し、·式短形は適用時の部品展開で復元する。
    val reviewModel = options.mergeModel.ifBlank { options.model }
    val effectiveMergeRetries = options.mergeRetries.coerceIn(1, 5)
    val mergedNames: List<String> = if (completed.size == 1) {
        allExtractedNames
    } else {
        // 技術的根拠1行：完全一致の重複は送る前に落とし、AIには曖昧な名寄せ・ノイズ除去だけさせる（送信量削減・混同防止）。
        val mergeInput = dictJson.encodeToString(ExtractedNames.serializer(), ExtractedNames(names = allExtractedNames))
        var namesFromLlm: List<String>? = null
        for (retry in 0 until effectiveMergeRetries) {
            if (retry > 0) {
                log("  🔄 辞書名寄せ: 再試行[$retry/$effectiveMergeRetries]")
            } else {
                log("🔄 辞書名寄せ: 実行中（${completed.size}断片 / 延べ${allExtractedNames.size}名）...")
            }
            when (val r = call(reviewModel, options.prompts.merge, mergeInput)) {
                is LlmResult.Success -> {
                    val parsed = parseExtractedNames(r.text)
                    if (parsed != null && parsed.names.isNotEmpty()) {
                        namesFromLlm = parsed.names
                        break
                    }
                }
                is LlmResult.Failure -> {
                    // 技術的根拠1行：巡回・待機は呼出側callの責務のため、ステージ側での二重待機は行わない。
                }
            }
        }
        // LLM名寄せに失敗した場合は、ローカルの単純distinctリストを安全にフォールバック利用
        namesFromLlm ?: allExtractedNames
    }

    log("📖 辞書名寄せ完了: ${mergedNames.size}名 確定")

    // 【Step 3: 命名・翻訳・レビュー（一括日本語付与）】
    // 確定した原文名一覧を受け取り、世界観判定とスタイル統一を行って正確な日本語訳を付与する。
    // 技術的根拠1行：1字名は一般語に誤爆するため登録しない（文面迂回の単一バッチ経路もここで閉じる）。
    val registrableNames = mergedNames.filter { it.length >= 2 }
    if (registrableNames.size < mergedNames.size) {
        log("📖 辞書名寄せ: 1字の名前を${mergedNames.size - registrableNames.size}件除外します")
    }
    val effectiveTranslateRetries = options.reviewRetries.coerceIn(1, 5)
    val translateInput = dictJson.encodeToString(ExtractedNames.serializer(), ExtractedNames(names = registrableNames))
    var translatedDict: NovelDict? = null
    var sawNonJapanese = false

    for (retry in 0 until effectiveTranslateRetries) {
        if (retry > 0) {
            log("  🔄 辞書命名・翻訳: 再試行[$retry/$effectiveTranslateRetries]")
        } else {
            log("🔍 辞書命名・翻訳: 実行中（${registrableNames.size}名）...")
        }
        when (val r = call(reviewModel, options.prompts.translate, translateInput)) {
            is LlmResult.Success -> {
                val rawParsed = parseNovelDict(r.text)
                if (rawParsed != null) {
                    val sanitized = sanitizeNovelDict(rawParsed)
                    val dropped = rawParsed.characters.size - sanitized.characters.size
                    if (dropped > 0) {
                        val sample = (rawParsed.characters.keys - sanitized.characters.keys).take(3).joinToString(",")
                        log("⚠️ 辞書生成: 日本語でない${dropped}件を除外 (例: ${sample})")
                        sawNonJapanese = true
                    }
                    if (sanitized.characters.isNotEmpty()) {
                        translatedDict = sanitized
                        break
                    }
                }
                log("  ⚠️ 辞書命名・翻訳: 結果が空のため再試行します")
            }
            is LlmResult.Failure -> {
                // 技術的根拠1行：巡回・待機は呼出側callの責務のため、ステージ側での二重待機は行わない。
            }
        }
    }

    if (translatedDict == null) {
        if (sawNonJapanese) {
            log("⚠️ 辞書生成: 使える項目ゼロのため保留します（次回再挑戦）")
        } else {
            log("⚠️ 辞書生成: 命名・翻訳に失敗したため保留します（次回再挑戦）")
        }
        return@supervisorScope null
    }

    val reviewed = translatedDict
    val dictDoc = findOrCreateFile(store, workDirUri, "dictionary.json", "application/json")
    if (dictDoc != null && store.writeText(
            dictDoc.uri,
            dictJson.encodeToString(NovelDict.serializer(), reviewed)
        )
    ) {
        log("✅ 辞書確定: ${reviewed.characters.size}名（スタイル: ${reviewed.style}）")
        return@supervisorScope reviewed
    }
    log("⚠️ 辞書生成: 保存に失敗したため保留します")
    return@supervisorScope null
}
