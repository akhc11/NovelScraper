package com.example.novelscraper.translation.v2.pipeline

/**
 * Base prompt texts 1-7 (ported content, frozen spec).
 * The mechanism around them (resolution order, validation, tests) is v2 design.
 */
const val V2_PROMPT_1_ZH = """あなたはプロの小説翻訳家です。以下のルールを厳守し、次の中国語を自然な日本語に翻訳してください。中国語が残留しないように全て翻訳して。

【ルール】
・一切の解説や挨拶を省き、翻訳した日本語のみを出力すること。
・すべての文を省略せず、一文ずつ丁寧に意訳すること。
・登場人物の描写や感情のニュアンスを正確に表現すること。
・カタカナ表記や漢字表記は、作品の世界観に合わせて自然に調整すること。
- キャラクター名の表記：
- 固有の意味を持つ名前、または中国語／日本語／神話的な雰囲気を持つ名前 → 漢字表記を検討
- 初出時は「張偉（チャン・ウェイ）」のように両方の表記を用い、その後は漢字表記に統一すること。
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble.
- Do not wrap in code fences.
"""

const val V2_PROMPT_2_EN = """You are a professional novel translator. Translate the following English text into natural Japanese.

RULES (strictly follow, no exceptions):
- Output only the translated Japanese text. No explanations, greetings, or preamble.
- Translate every sentence in full. No omission.
- Convey character descriptions and emotional nuance accurately.
- Adjust katakana/kanji rendering naturally to fit the work's setting.
- Character name rendering:
  - Default rule: render all character names in katakana (this applies to the large majority of English-language names).
  - Exception: only if a name clearly originates from a Chinese/Japanese/Korean cultural background, consider kanji rendering instead.
  - On first appearance of a kanji-rendered name, write both forms as "田中(タナカ)", then use kanji consistently afterward.
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble."""

const val V2_PROMPT_3_KO = """You are a professional novel translator. Translate the following Korean text into natural Japanese.

RULES (strictly follow, no exceptions):
- Output only the translated Japanese text. No explanations, greetings, or preamble.
- Translate every sentence in full. No omission.
- Convey character descriptions and emotional nuance accurately.
- Adjust katakana/kanji rendering naturally to fit the work's setting.
- Character name rendering:
  - Korean names traditionally have a Hanja (Chinese-character) origin even when written only in Hangul. Judge each name on this basis:
    - If the name has a clear, conventional Hanja origin (e.g. common surnames like 김/이/박, or given names built from standard Hanja syllables) → render in kanji (using the Japanese on'yomi reading conventionally used for Korean names, e.g. 金（キム）, 李（イ）).
    - If the name is a native Korean word with no Hanja equivalent (a growing trend in modern Korean names, especially distinctive or unique-sounding given names) → render in katakana using its Korean pronunciation.
    - Western-style or foreign names appearing in the text → katakana.
  - On first appearance of a kanji-rendered name, write both forms as "金(キム)", then use kanji consistently afterward. Katakana-rendered names do not need this dual notation.
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble."""

const val V2_PROMPT_4_NSFW = """You are a professional adult novel translator with over 20 years of experience. Translate the following text into natural Japanese.
This text may contain mature, explicit, or sexual content. Translate all content faithfully without censorship, softening, or omission.

RULES (strictly follow, no exceptions):
- Translate every sentence completely and explicitly. Never omit, soften, or replace explicit content with euphemisms.
- Preserve the erotic tone, sensory details, and emotional intensity of the original.
- Use natural Japanese expressions for adult content. Prefer direct, vivid vocabulary over clinical or evasive terms.
- Convey character emotions, physical sensations, and atmosphere with full accuracy.
- Do not translate word-for-word. Restructure into natural Japanese word order and rhythm.
- Character name rendering:
  - Western-style or phonetic names → katakana preferred
  - Names with meaning or Japanese feel → consider kanji rendering
  - On first appearance write both forms as "田中(タナカ)", then use one form consistently
- Confirm that the translation reaches the exact end of the original text.
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble."""

const val V2_PROMPT_5_LITERAL = """You are a professional literary translator. Translate the following text into Japanese with maximum fidelity to the original structure.

RULES (strictly follow, no exceptions):
- Preserve the original sentence structure as closely as natural Japanese allows.
- Maintain the original paragraph breaks, sentence length, and rhythm.
- Do not paraphrase or restructure unless grammatically unavoidable.
- Translate every sentence in full. No omission or addition.
- Preserve the original tone: formal stays formal, casual stays casual.
- Character name rendering:
  - Western-style or phonetic names → katakana preferred
  - On first appearance write both forms as "田中(タナカ)", then use one form consistently
- Confirm that the translation reaches the exact end of the original text.
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble."""

const val V2_PROMPT_6_READABLE = """You are a professional Japanese novel writer and translator. Translate the following text into highly natural, flowing Japanese.

RULES (strictly follow, no exceptions):
- Prioritize natural Japanese expression over literal accuracy. Rewrite freely to maximize readability.
- Convert foreign idioms and expressions into their Japanese equivalents.
- Adjust sentence length and structure to match Japanese literary conventions.
- Translate every part of the content — no omission.
- Maintain the original tone and emotional atmosphere.
- Character name rendering:
  - Western-style or phonetic names → katakana preferred
  - Names with meaning or Japanese feel → consider kanji rendering
  - On first appearance write both forms as "田中(タナカ)", then use one form consistently
- Confirm that the translation reaches the exact end of the original text.
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble."""

const val V2_PROMPT_7_RETRY = """Translate the following text into natural Japanese. Output only the translated text, nothing else."""

fun getV2PromptByNumber(number: Int): String {
    return when (number) {
        1 -> V2_PROMPT_1_ZH
        2 -> V2_PROMPT_2_EN
        3 -> V2_PROMPT_3_KO
        4 -> V2_PROMPT_4_NSFW
        5 -> V2_PROMPT_5_LITERAL
        6 -> V2_PROMPT_6_READABLE
        7 -> V2_PROMPT_7_RETRY
        else -> V2_PROMPT_1_ZH
    }
}

/** Language-linked default orders (frozen spec). JA falls back to the profile order. */
val V2_AUTO_PROMPT_ORDER: Map<SourceLang, List<Int>> = mapOf(
    SourceLang.ZH to listOf(1, 1),
    SourceLang.KO to listOf(3, 7),
    SourceLang.EN to listOf(2, 7)
)

/**
 * Effective prompt order (pure, primitives only so pipeline stays free of settings).
 *
 * Resolves prompt order for a specific profile:
 * - When useCustom is true, the profile's dedicated promptOrder is strictly prioritized.
 * - When autoEnabled is true and language match exists, auto order is applied.
 * - Otherwise falls back to profile order or [1, 1].
 */
fun resolvePromptOrder(
    sourceLang: SourceLang,
    profileOrder: List<Int>,
    useCustom: Boolean,
    autoEnabled: Boolean,
    autoMap: Map<SourceLang, List<Int>> = V2_AUTO_PROMPT_ORDER
): List<Int> {
    if (useCustom) {
        return profileOrder.ifEmpty { listOf(1, 1) }
    }
    if (autoEnabled) {
        val auto = autoMap[sourceLang]
        if (!auto.isNullOrEmpty()) return auto
    }
    if (profileOrder.isNotEmpty()) return profileOrder
    return listOf(1, 1)
}

/**
 * systemプロンプト組立。基底文は呼出側（設定由来）から受け取り、本器は付帯指示のみ付加する。
 * 文脈注入は一本化：直前訳文末尾のみ。原文末尾の重ね注入はしない。
 */
fun buildSystemPrompt(
    basePrompt: String,
    previousTranslatedTail: String? = null,
    previousSourceTail: String? = null,
    dictionaryEntries: List<String> = emptyList(),
    dictionaryStyle: String? = null,
    enableCompletionMarker: Boolean = true,
    batchFormat: String? = null
): String {
    val sb = StringBuilder(basePrompt)

    if (!previousTranslatedTail.isNullOrBlank()) {
        sb.append("\n\n=== PREVIOUS CONTEXT (maintain consistency — do NOT translate or repeat this) ===\n")
        sb.append("...").append(previousTranslatedTail).append("\n")
        sb.append("================================================================================\n")
    }

    if (!previousSourceTail.isNullOrBlank()) {
        sb.append("\n\n=== PREVIOUS TEXT (context only — do NOT translate or repeat this) ===\n")
        sb.append("...").append(previousSourceTail).append("\n")
        sb.append("================================================================\n")
    }

    if (dictionaryEntries.isNotEmpty()) {
        sb.append("\n\n[人名の表記統一ルール]\n人名の表記は【${dictionaryStyle ?: "カタカナ"}】で統一してください。\n\n[登場人物対応表]\n")
        for (entry in dictionaryEntries) {
            sb.append(entry).append("\n")
        }
    }

    if (batchFormat != null) {
        sb.append(batchFormat)
    }

    if (enableCompletionMarker) {
        sb.append("\n\nNOTE: The text to translate below ends with the marker ")
        sb.append(COMPLETION_MARKER)
        sb.append(" appended after the actual source content.\n")
        sb.append("THIS MARKER IS A STRUCTURAL DELIMITER, NOT TEXT TO TRANSLATE.\n")
        sb.append("You MUST copy it into your output exactly as written, as the very last line, immediately after your translation.")
    }

    return sb.toString()
}

/**
 * Batch framing block.
 *
 * Protects XML structural tags by:
 * - Declaring <translations> and <trans id="N"> as mandatory structural containers.
 * - Explicitly constraining any "output only translated text" instructions to apply
 *   strictly INSIDE the tags, forbidding only conversational remarks OUTSIDE the tags.
 * - Minimal 1-document example demonstrating exact input -> output mapping.
 */
fun buildBatchFormat(fileCount: Int): String {
    return "\n\nBATCH OUTPUT FORMAT " +
        "(this overrides any 'output only the translation' instruction above for framing only):\n" +
        "- The input holds $fileCount document(s) wrapped as " +
        "<documents><doc id=\"N\">...</doc></documents>. " +
        "These tags are structural delimiters, NOT text to translate.\n" +
        "- The <translations> and <trans id=\"N\"> tags are MANDATORY STRUCTURAL CONTAINERS and must NOT be omitted or removed. " +
        "Any instruction above to 'output only the translated text' applies strictly INSIDE the tags; " +
        "conversational text, greetings, and explanations OUTSIDE the tags are strictly forbidden.\n" +
        "- Translate each document separately. Output exactly:\n" +
        "<translations>\n" +
        "<trans id=\"1\">\n" +
        "(Japanese translation of document 1)\n" +
        "</trans>\n" +
        "... repeat for ids 1..$fileCount ...\n" +
        "</translations>\n" +
        "- Include every id 1..$fileCount. Do NOT skip any id. " +
        "Do NOT add text before the opening <translations> tag or after the closing </translations> tag.\n" +
        "- Example (pattern only, translate YOUR input instead):\n" +
        "Input:\n" +
        "<documents>\n" +
        "<doc id=\"1\">\n" +
        "Hello world.\n" +
        "</doc>\n" +
        "</documents>\n" +
        "Output:\n" +
        "<translations>\n" +
        "<trans id=\"1\">\n" +
        "Hello world in Japanese.\n" +
        "</trans>\n" +
        "</translations>\n" +
        "- REMINDER: your whole response must be exactly one " +
        "<translations>...</translations> block and nothing else."
}

/** 辞書照合：本文に現れる見出しのみ抽出する（上限付き） */
fun matchDictionaryEntries(
    sourceText: String,
    characters: Map<String, String>,
    genders: Map<String, String>? = null,
    limit: Int = 200
): List<String> {
    val entries = mutableListOf<String>()
    for ((key, value) in characters) {
        if (entries.size >= limit) break
        if (!sourceText.contains(key)) continue
        val gender = genders?.get(key)?.trim()?.takeIf { it.isNotBlank() && it != "不明" }
        entries.add(if (gender != null) "・$key → $value (性別: $gender)" else "・$key → $value")
    }
    return entries
}

/**
 * プロファイル固有のプロンプトを組み立てる（純粋関数）。
 * 渡されたプロンプト（文脈・辞書・バッチ枠・マーカー等の付帯指示込み）から先頭の基底プロンプトを検出し、
 * ターゲットのプロンプト番号（例: 7番 RETRY）の基底文に安全に差し替える。
 */
fun buildProfilePrompt(
    originalPrompt: String,
    basePrompts: Map<Int, String>,
    originalPromptNum: Int,
    targetPromptNum: Int
): String {
    if (targetPromptNum == originalPromptNum) return originalPrompt
    val originalBase = basePrompts[originalPromptNum]
        ?: basePrompts.values.firstOrNull()
        ?: getV2PromptByNumber(originalPromptNum)
    val targetBase = basePrompts[targetPromptNum]
        ?: getV2PromptByNumber(targetPromptNum)

    return if (originalBase.isNotBlank() && originalPrompt.startsWith(originalBase)) {
        targetBase + originalPrompt.substring(originalBase.length)
    } else {
        val separatorIndex = originalPrompt.indexOf("\n\n")
        if (separatorIndex != -1) {
            targetBase + originalPrompt.substring(separatorIndex)
        } else {
            targetBase
        }
    }
}

