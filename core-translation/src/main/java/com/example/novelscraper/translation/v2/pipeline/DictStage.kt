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
    /** 人物メモ（原文名→40字以内の性格・不変身分）。空＝未設定（訳語のみ従来通り）。 */
    val profiles: Map<String, String> = emptyMap(),
    /** 生成時文面のハッシュ。空＝旧形式（照合時は不一致扱いで作り直す） */
    val promptsHash: String = ""
)

@Serializable
data class ExtractedNames(
    val names: List<String> = emptyList(),
    /** 作者署名・注記・宣伝文由来の名前。名寄せ・命名には使わず機械除外する。欠落時は空扱い。 */
    val authors: List<String> = emptyList(),
    /** 抽出断片で見た人物メモの素（原文名→候補文）。命名合成の根拠専用。欠落時は空扱い。 */
    val hints: Map<String, List<String>> = emptyMap()
)

@Serializable
data class DictBuildManifest(val version: Int = 1, val batches: Map<String, String> = emptyMap(), val model: String = "", val promptsHash: String = "")

data class DictPrompts(
    val batch: String = "Extract person names from the novel text below.\n" +
        "Output ONLY valid JSON: {\"names\":[\"Name1\"],\"authors\":[\"Author1\"],\"hints\":{\"Name1\":[\"memo\"]}}.\n" +
        "- names: characters only, original script, 2+ chars. Exclude titles, pronouns, common nouns, places, organizations.\n" +
        "- authors: names from author notes/ads only. If in both, keep in names.\n" +
        "- No Japanese translation.\n" +
        "- hints: listed names only, immutable trait/identity within 20 chars from this excerpt. Omit if no evidence, no guessing.\n" +
        "Example: {\"names\":[\"克莱恩\",\"李云龙\",\"Arthur\"],\"authors\":[],\"hints\":{\"李云龙\":[\"落ち着いた少年\"],\"Arthur\":[\"明るい少女\"]}}\n" +
        "Example: {\"names\":[\"克莱恩\",\"赛勒丝\"],\"authors\":[],\"hints\":{\"克莱恩\":[\"厳格な中年男性\"]}}",

    val merge: String = "Merge the dictionary names below into one clean JSON list.\n" +
        "Output ONLY valid JSON: {\"names\":[\"Name1\"]}.\n" +
        "- Keep full forms with ·, drop their short parts. Without ·, keep both (may be different people).\n" +
        "- Drop 1-char names, titles, common nouns, places, organizations. Original script only, no Japanese.\n" +
        "Example: {\"names\":[\"克莱恩\",\"李云龙\",\"Arthur\"]}\n" +
        "Example: [\"李维·史奈克\",\"李维\"] -> {\"names\":[\"李维·史奈克\"]}; [\"李云\",\"李云龙\"] -> {\"names\":[\"李云\",\"李云龙\"]}",

    val translate: String = "Review the merged character names below and create a Japanese dictionary.\n" +
        "Output ONLY valid JSON: {\"style\":\"カタカナ|漢字|ハイブリッド\",\"characters\":{\"Original\":\"Japanese\"},\"profiles\":{\"Original\":\"memo\"}}.\n" +
        "\n" +
        "【Input Data】\n" +
        "Input JSON format: {\"names\":[\"Name1\",\"Name2\"],\"hints\":{\"Name1\":[\"snippet1\",\"snippet2\"]}}.\n" +
        "- names: Character names to translate.\n" +
        "- hints: Trait and identity snippets for each character. Used as the factual basis to create character profiles.\n" +
        "\n" +
        "【Rules】\n" +
        "1. characters (Translation):\n" +
        "- Every input name in \"names\" must have exactly one Japanese reading. Do not swap similar names.\n" +
        "- Chinese: kanji first, katakana only for clear Western transliterations. Korean: katakana by default, kanji only if clearly natural. Keep one consistent style per work.\n" +
        "- Drop places, organizations, titles, and common nouns. Readings must be natural Japanese.\n" +
        "\n" +
        "2. profiles (Character Memos - MANDATORY when hints exist):\n" +
        "- For EVERY character present in input \"hints\", you MUST synthesize a concise Japanese memo (10 to 40 characters max) in \"profiles\".\n" +
        "- Synthesize the snippets into immutable traits (gender, age, identity, personality). Keep gender/age words verbatim.\n" +
        "- CRITICAL: If a character exists in input \"hints\", generating its profile in \"profiles\" is MANDATORY. Do NOT omit any hinted character, and NEVER return an empty \"profiles\": {} when \"hints\" contains entries.\n" +
        "- Only omit a character from \"profiles\" if it has no entries in \"hints\". If input \"hints\" is completely empty, \"profiles\" should be {}.\n" +
        "\n" +
        "【Examples】\n" +
        "Example 1 (Chinese, kanji style with hints):\n" +
        "Input: {\"names\":[\"李云\",\"王五\"],\"hints\":{\"李云\":[\"落ち着いた少年\",\"沈着冷静な剣士\"]}}\n" +
        "Output: {\"style\":\"漢字\",\"characters\":{\"李云\":\"李雲\",\"王五\":\"王五\"},\"profiles\":{\"李云\":\"落ち着いた少年、冷静な剣士\"}}\n" +
        "\n" +
        "Example 2 (Korean, katakana style with hints):\n" +
        "Input: {\"names\":[\"사재혁\",\"이지은\"],\"hints\":{\"사재혁\":[\"温厚な青年\"],\"이지은\":[\"聡明な少女\"]}}\n" +
        "Output: {\"style\":\"カタカナ\",\"characters\":{\"사재혁\":\"サ・ジェヒョク\",\"이지은\":\"イ・ジウン\"},\"profiles\":{\"사재혁\":\"温厚な青年\",\"이지은\":\"聡明な少女\"}}\n" +
        "\n" +
        "- REMINDER: For every name present in input \"hints\", you MUST generate a non-empty Japanese summary in \"profiles\". Never leave \"profiles\" empty when hints are given.",

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
    /** 命名・翻訳1回あたりの名前数上限。超過分は複数回に割って送る（弱いモデルの完走用）。 */
    val translateChunkNames: Int = TranslationLimits.DICT_TRANSLATE_CHUNK_NAMES,
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

/**
 * 断片群の人物メモ素を名寄せする（pure）。同名の候補を束ね、空・重複を落とし、上限で切る。
 * 名簿にない名の素は捨てる（ノイズの持ち込み防止）。
 * 技術的根拠1行：根拠は抽出断片にしかないため、束ねはここ1箇所に寄せて命名合成へ渡す。
 */
fun collectProfileHints(
    batches: List<ExtractedNames>,
    maxPerName: Int = TranslationLimits.MAX_HINTS_PER_NAME
): Map<String, List<String>> {
    val cap = maxPerName.coerceAtLeast(1)
    val table = LinkedHashMap<String, MutableList<String>>()
    for (batch in batches) {
        val known = batch.names.toSet()
        for ((name, hints) in batch.hints) {
            if (name !in known) continue
            val slot = table.getOrPut(name) { mutableListOf() }
            for (hint in hints) {
                if (hint.isNotBlank() && hint !in slot && slot.size < cap) {
                    slot.add(hint)
                }
            }
            // 技術的根拠1行：空スロットは「根拠あり」の誤認と後段の無駄走査を生むため残さない。
            if (slot.isEmpty()) table.remove(name)
        }
    }
    return table
}

/**
 * 落選名の候補を生存名へ移す（pure）。·式短形の落選分だけをフルネーム側へ畳む。
 * 技術的根拠1行：名寄せ則（·式はフルネーム統一）と同一則を機械側にも置き、短形断片の証拠を捨てない。
 */fun transferShortHints(
    survivors: Set<String>,
    dropped: Collection<String>,
    table: Map<String, List<String>>
): Map<String, List<String>> {
    if (table.isEmpty()) return table
    val cap = TranslationLimits.MAX_HINTS_PER_NAME.coerceAtLeast(1)
    val merged = table.mapValues { it.value.toMutableList() }.toMutableMap()
    for (name in dropped) {
        if (name in survivors) continue
        val full = survivors.firstOrNull { it.contains("·") && name in it.split("·") } ?: continue
        val slot = merged.getOrPut(full) { mutableListOf() }
        for (hint in table[name].orEmpty()) {
            if (hint !in slot && slot.size < cap) {
                slot.add(hint)
            }
        }
    }
    return merged
}

/**
 * 命名入力に載せる候補を総量上限で刈る（pure）。前方（名寄せ順）優先で詰め、超過分は後方切り捨て。
 * 技術的根拠1行：大名簿時の入力肥大は小規模モデルの精度・上限を直撃するため、総量だけ上限を置く（1名枠は別途）。
 */
fun selectHintsForTranslate(
    names: List<String>,
    table: Map<String, List<String>>,
    maxTotalChars: Int = TranslationLimits.MAX_TRANSLATE_HINTS_CHARS
): Map<String, List<String>> {
    val budget = maxTotalChars.coerceAtLeast(0)
    val out = LinkedHashMap<String, List<String>>()
    var used = 0
    for (name in names) {
        val hints = table[name].orEmpty()
        if (hints.isEmpty()) continue
        val cost = hints.sumOf { it.length }
        if (used + cost > budget) break
        out[name] = hints
        used += cost
    }
    return out
}

private val dictJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

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
 * {"名前": {"name"|"trans"|"japanese": "読み"}} 形式も拾う。旧"genders"キーは無視する。
 */
private fun decodeNovelDictFlexible(text: String, allowEmpty: Boolean): NovelDict? {
    return try {
        val element = dictJson.parseToJsonElement(text)
        if (element !is JsonObject) return null
        val style = (element["style"] as? JsonPrimitive)?.contentOrNull ?: "カタカナ"
        val promptsHash = (element["promptsHash"] as? JsonPrimitive)?.contentOrNull ?: ""
        val charMap = mutableMapOf<String, String>()
        val profileMap = mutableMapOf<String, String>()
        // 技術的根拠1行: charactersオブジェクト内外での同一パース処理のコピペ重複を排除し保守性を向上させる。
        val extractEntry = { k: String, v: kotlinx.serialization.json.JsonElement ->
            when (v) {
                is JsonPrimitive -> v.contentOrNull?.let { charMap[k] = it }
                is JsonObject -> {
                    val nameVal = v["name"] ?: v["trans"] ?: v["japanese"]
                    if (nameVal is JsonPrimitive) nameVal.contentOrNull?.let { charMap[k] = it }
                }
                else -> {}
            }
        }
        val charsObj = element["characters"]
        if (charsObj is JsonObject) {
            for ((k, v) in charsObj) extractEntry(k, v)
        } else {
            for ((k, v) in element) {
                if (k == "style" || k == "genders" || k == "characters" || k == "profiles") continue
                extractEntry(k, v)
            }
        }
        val profilesObj = element["profiles"]
        if (profilesObj is JsonObject) {
            for ((k, v) in profilesObj) {
                if (v is JsonPrimitive) v.contentOrNull?.let { profileMap[k] = it }
            }
        }
        if (!allowEmpty && charMap.isEmpty()) return null
        NovelDict(style = style, characters = charMap, profiles = profileMap, promptsHash = promptsHash)
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

/**
 * 文字列または文字列配列の値を持つオブジェクトの読取。両形式を受理する。
 * 技術的根拠1行：新旧・厳格・変形の3形式でhintsの載り方が違うため、読取だけ一本化して呼出側の分岐をなくす。
 */
private fun stringMapOf(element: kotlinx.serialization.json.JsonObject, key: String): Map<String, List<String>> {
    val obj = element[key] as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, List<String>>()
    for ((k, v) in obj) {
        val name = k.trim()
        if (name.isEmpty()) continue
        val values = when (v) {
            is JsonPrimitive -> listOfNotNull(v.contentOrNull?.trim()?.takeIf { it.isNotBlank() })
            is kotlinx.serialization.json.JsonArray ->
                v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }.distinct()
            else -> emptyList()
        }
        if (values.isNotEmpty()) out[name] = values
    }
    return out
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
                return ExtractedNames(names = list.distinct(), authors = stringListOf(element, "authors"), hints = stringMapOf(element, "hints"))
            }
            val chars = element["characters"]
            if (chars is JsonObject) {
                val list = chars.keys.map { it.trim() }.filter { it.isNotBlank() }
                return ExtractedNames(names = list.distinct(), authors = stringListOf(element, "authors"), hints = stringMapOf(element, "hints"))
            } else if (chars is kotlinx.serialization.json.JsonArray) {
                val list = chars.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }
                return ExtractedNames(names = list.distinct(), authors = stringListOf(element, "authors"), hints = stringMapOf(element, "hints"))
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
 * 黙って採用しないための検査。人物メモも生き残り項目に連動して刈り、超過・非日本語はその人物分だけ落とす。
 */
fun sanitizeNovelDict(dict: NovelDict): NovelDict {
    if (dict.characters.isEmpty()) return dict
    val kept = dict.characters.filterValues { isJapaneseHeadword(it) }
    val keptProfiles = dict.profiles.filterKeys { it in kept }
        .filterValues { it.length <= TranslationLimits.MAX_PROFILE_CHARS && isJapaneseHeadword(it) }
    if (kept.size == dict.characters.size && keptProfiles.size == dict.profiles.size) return dict
    return dict.copy(characters = kept, profiles = keptProfiles)
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
 * 1チャンク分の命名・翻訳。被覆率が全件になるまで上限内で取り直し、最良を返す。
 * 技術的根拠1行：判定・保持の単位をチャンクに寄せ、割った分の独立性を保つ。
 */
private suspend fun translateChunk(
    chunkNames: List<String>,
    chunkHints: Map<String, List<String>>,
    reviewModel: String,
    translatePrompt: String,
    maxRetries: Int,
    call: suspend (model: String, prompt: String, text: String) -> LlmResult,
    log: (String) -> Unit
): Pair<NovelDict?, Boolean> {
    val translateInput = dictJson.encodeToString(
        ExtractedNames.serializer(),
        ExtractedNames(names = chunkNames, hints = chunkHints)
    )
    // 技術的根拠1行：素の有無は送信文にしか残らないため、取り直し判定用に引数で受ける。
    val hintsSent = chunkHints.isNotEmpty()
    // 技術的根拠1行：素あり名数が分母のため、被覆率は送信束から数える（受信側の自己申告に寄せない）。
    val hintedCount = chunkHints.size
    val attempts = maxRetries.coerceIn(1, 5)
    var bestDict: NovelDict? = null
    var bestCovered = -1
    var sawNonJapanese = false
    for (retry in 0 until attempts) {
        if (retry > 0) {
            log("  🔄 辞書命名・翻訳: 再試行[$retry/$attempts]")
        }
        when (val r = call(reviewModel, translatePrompt, translateInput)) {
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
                    val droppedProfiles = rawParsed.profiles.size - sanitized.profiles.size
                    if (droppedProfiles > 0) {
                        log("⚠️ 辞書生成: 人物メモ${droppedProfiles}件を除外（40字超・非日本語の規格外）")
                    }
                    if (sanitized.characters.isNotEmpty()) {
                        // 技術的根拠1行：一部欠けの取り直しは最良保持と組にし、後退（良い回を悪い回で上書き）を構造的に防ぐ。
                        // 技術的根拠1行：1欠けは表記揺れ・落としと区別不能のため許容し、全件固執の送り直し浪費をなくす（本文品質の1件許容と同一則）。
                        val covered = sanitized.profiles.keys.count { it in chunkHints }
                        if (!hintsSent || covered + DICT_ALLOWED_MISSING >= hintedCount) {
                            if (hintsSent && covered < hintedCount) {
                                log("  ⚠️ 辞書命名・翻訳: 人物メモ${covered}/${hintedCount}件で確定します（1欠け許容）")
                            }
                            return sanitized to sawNonJapanese
                        }
                        if (covered > bestCovered) {
                            bestDict = sanitized
                            bestCovered = covered
                        }
                        if (retry + 1 < attempts) {
                            if (covered == 0) {
                                log("  ⚠️ 辞書命名・翻訳: 人物メモ0件のため再試行します")
                            } else {
                                log("  ⚠️ 辞書命名・翻訳: 人物メモ${covered}/${hintedCount}件のため再試行します")
                            }
                            continue
                        }
                        // 技術的根拠1行：被覆率は0以上で最良初期値-1を必ず上回るため、ここでは最良が入っている（到達不能な代替は置かない）。
                        return bestDict!! to sawNonJapanese
                    }
                }
                log("  ⚠️ 辞書命名・翻訳: 結果が空のため再試行します")
            }
            is LlmResult.Failure -> {
                // 技術的根拠1行：巡回・待機は呼出側callの責務のため、ステージ側での二重待機は行わない。
            }
        }
    }
    return null to sawNonJapanese
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
                        // 技術的根拠1行：完全一致の重複はプログラム側で保証し、AIの表記ゆれには触れない。
                        namesFromLlm = parsed.names.distinct()
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

    // 技術的根拠1行：根拠は抽出断片にしかないため、命名合成の入力に候補束を添えて当て推量にしない。
    val hintTable = transferShortHints(
        mergedNames.toSet(),
        allExtractedNames - mergedNames.toSet(),
        collectProfileHints(parsedBatches)
    )
    if (hintTable.isNotEmpty()) {
        log("📖 辞書生成: 人物メモの素あり（${hintTable.size}名）")
    }

    // 【Step 3: 命名・翻訳・レビュー（一括日本語付与）】
    // 確定した原文名一覧を受け取り、世界観判定とスタイル統一を行って正確な日本語訳を付与する。
    // 技術的根拠1行：1字名は一般語に誤爆するため登録しない（文面迂回の単一バッチ経路もここで閉じる）。
    val registrableNames = mergedNames.filter { it.length >= 2 }
    if (registrableNames.size < mergedNames.size) {
        log("📖 辞書名寄せ: 1字の名前を${mergedNames.size - registrableNames.size}件除外します")
    }
    val effectiveTranslateRetries = options.reviewRetries.coerceIn(1, 5)
    // 技術的根拠1行：弱いモデルでも完走できる分量にするため、命名は上限ずつに割って送る（量の責務をモデル性能から切り離す）。
    val nameChunks = registrableNames.chunked(options.translateChunkNames.coerceAtLeast(1))
    if (nameChunks.size > 1) {
        log("🔍 辞書命名・翻訳: ${nameChunks.size}分割で実行中（${registrableNames.size}名・${options.translateChunkNames}名ずつ）...")
    } else {
        log("🔍 辞書命名・翻訳: 実行中（${registrableNames.size}名）...")
    }
    val mergedCharacters = LinkedHashMap<String, String>()
    val mergedProfiles = LinkedHashMap<String, String>()
    val styleVotes = mutableListOf<String>()
    // 技術的根拠1行：素の有無は送信文にしか残らないため、警告判定用に送信束を束ねて保持する。
    val allSentHints = LinkedHashMap<String, List<String>>()
    var sawNonJapanese = false
    var chunkFailed = false

    for ((chunkIdx, chunkNames) in nameChunks.withIndex()) {
        if (nameChunks.size > 1) {
            log("🔍 辞書命名・翻訳: チャンク${chunkIdx + 1}/${nameChunks.size}（${chunkNames.size}名）...")
        }
        val chunkHints = selectHintsForTranslate(chunkNames, hintTable)
        allSentHints.putAll(chunkHints)
        val (chunkDict, chunkNonJapanese) = translateChunk(
            chunkNames = chunkNames,
            chunkHints = chunkHints,
            reviewModel = reviewModel,
            translatePrompt = options.prompts.translate,
            maxRetries = effectiveTranslateRetries,
            call = call,
            log = log
        )
        if (chunkNonJapanese) sawNonJapanese = true
        if (chunkDict == null) {
            // 技術的根拠1行：1チャンクの失敗で確定分を全破棄すると99名級で全滅ループになるため、温存して継続する。
            chunkFailed = true
            log("⚠️ 辞書命名・翻訳: チャンク${chunkIdx + 1}は失敗・確定分${mergedCharacters.size}名を温存して継続します")
            continue
        }
        for ((k, v) in chunkDict.characters) mergedCharacters.putIfAbsent(k, v)
        for ((k, v) in chunkDict.profiles) mergedProfiles.putIfAbsent(k, v)
        styleVotes.add(chunkDict.style)
    }

    if (mergedCharacters.isEmpty()) {
        if (sawNonJapanese) {
            log("⚠️ 辞書生成: 使える項目ゼロのため保留します（次回再挑戦）")
        } else {
            log("⚠️ 辞書生成: 命名・翻訳に失敗したため保留します（次回再挑戦）")
        }
        return@supervisorScope null
    }
    if (chunkFailed) {
        // 技術的根拠1行：部分確定でも翻訳は進める。ゼロ扱いの全中断より、訳語ありの進行を優先する。
        log("⚠️ 辞書生成: 一部チャンク失敗のため部分確定します（${mergedCharacters.size}名）")
    }

    // 技術的根拠1行：表記統一は作品単位のため、割った分の判定は多数決で1つに戻す（同数は先勝ち）。
    // 技術的根拠1行：異なり表の再計算は割れ判定・記録で三重になるため、1回の distinct に寄せる。
    val distinctStyles = styleVotes.distinct()
    val finalStyle = distinctStyles.maxByOrNull { s -> styleVotes.count { it == s } } ?: "カタカナ"
    if (distinctStyles.size > 1) {
        log("⚠️ 辞書生成: 表記スタイルが割れました（${distinctStyles.joinToString(",")}）→${finalStyle}に統一")
    }
    val reviewed = NovelDict(style = finalStyle, characters = mergedCharacters, profiles = mergedProfiles)
    // 技術的根拠1行：素ありのメモ欠け確定は次回作り直しの判断材料のため、件数と併せて警告に残す（訳語は温存する）。
    if (allSentHints.isNotEmpty()) {
        val covered = reviewed.profiles.keys.count { it in allSentHints }
        if (covered < allSentHints.size) {
            if (covered == 0) {
                log("⚠️ 辞書生成: 人物メモなしで確定します（素あり${hintTable.size}名・モデルが空返却）")
            } else {
                log("⚠️ 辞書生成: 人物メモ${covered}/${allSentHints.size}件で確定します（残りは素あり・モデルが未記入）")
            }
        }
    }
    val dictDoc = findOrCreateFile(store, workDirUri, "dictionary.json", "application/json")
    if (dictDoc != null && store.writeText(
            dictDoc.uri,
            dictJson.encodeToString(NovelDict.serializer(), reviewed)
        )
    ) {
        log("✅ 辞書確定: ${reviewed.characters.size}名（スタイル: ${reviewed.style}・人物メモ${reviewed.profiles.size}件）")
        return@supervisorScope reviewed
    }
    log("⚠️ 辞書生成: 保存に失敗したため保留します")
    return@supervisorScope null
}
