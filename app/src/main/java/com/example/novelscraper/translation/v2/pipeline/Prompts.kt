package com.example.novelscraper.translation.v2.pipeline

/**
 * Base prompt texts 1-7 (ported content, frozen spec).
 * The mechanism around them (resolution order, validation, tests) is v2 design.
 */
const val V2_PROMPT_1_ZH = """あなたはプロの小説翻訳家です。以下のルールを厳守し、次の中国語を自然な日本語に翻訳してください。中国語（簡体字・繁体字・中国語特有の表現）が絶対に残留しないように全て完全に翻訳してください。

【厳格な翻訳ルール】
1. 中国語残留の完全禁止（最重要）:
   - 登場人物名、地名、固有名詞、効果音・擬音語、感嘆詞を含め、すべての中国語を1文字も残さず自然な日本語に翻訳・音訳すること。
   - 純粋な簡体字（说、这、个、着、们 など）をそのまま残すことは厳禁。日本の常用漢字またはカタカナに変換すること。
   - カッコ書き等で原文の中国語を併記することは厳禁。
2. 固有名詞・人名の表記ルール（作品のジャンルに左右されず、名前自体のルーツで厳格に判定すること）:
   作品全体のジャンルにかかわらず、登場人物名・固有名詞はその語源（ルーツ）に応じて個別に以下のように表記を統一すること。
   - 【西洋風・外国語の音訳名 ➔ カタカナ】:
     漢字で書かれていても、英語・西洋名・架空ファンタジー名の当て字（音訳）である場合は、漢字のまま残さず必ず自然なカタカナに音訳すること。
     [具体例]
     ・克莱恩 → クライン (Klein)
     ・奥黛丽 → オードリー (Audrey)
     ・爱丽丝 → アリス (Alice)
     ・阿尔杰 → アルジャー (Alger)
     ・罗恩 → ロン (Ron)
     ・贝克兰德 → バックランド (Backlund / 地名)
     ※「克莱恩」「奥黛麗」のように漢字のまま残すことは厳禁。
   - 【中華・東洋伝統の姓名 ➔ 日本の常用漢字】:
     漢民族・東洋伝統の姓名（姓1文字＋名1〜2文字等）は、カタカナ音読み（リー・ユン等）に崩さず、日本の漢字で表記すること。
     その際、中国の簡体字は日本の常用漢字・新字体に必ず復元・変換すること。
     [具体例]
     ・李云 → 李雲 (「云」は簡体字。「リー・ユン」とカタカナ化せず「李雲」と日本の漢字にすること)
     ・叶凡 → 葉凡 (簡体字「叶」は伝統姓の「葉」)
     ・林动 → 林動 (簡体字「动」→ 新字体「動」)
     ・萧炎 → 蕭炎 (簡体字「萧」→ 伝統漢字「蕭」)
     ・张楚岚 → 張楚嵐 (簡体字「张/岚」→ 新字体「張/嵐」)
     ・王铁柱 → 王鉄柱 (簡体字「铁」→ 新字体「鉄」)
3. 完全な翻訳:
   - すべての文を省略せず、一文ずつ丁寧に意訳すること。
   - 登場人物の感情や情景描写のニュアンスを正確に日本語で表現すること。
4. 出力制約:
   - 翻訳した日本語本文のみを出力すること。前後の挨拶、解説、注釈は一切含めないこと。
   - 本文を ``` などのマークダウンのコードブロックで囲まないこと（指示された構造タグやマーカーがある場合は、それを削除せず正しく出力すること）。
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble.
- Do not wrap in code fences.
"""

const val V2_PROMPT_2_EN = """あなたはプロの小説翻訳家です。以下のルールを厳守し、次の英語を自然な日本語に翻訳してください。英語の文章やフレーズが絶対に残留しないように全て完全に翻訳してください。

【厳格な翻訳ルール】
1. 英語残留の完全禁止（最重要）:
   - 登場人物名、地名、固有名詞、効果音・擬音語、感嘆詞を含め、未翻訳の英文を一切残さず自然な日本語（カタカナまたは漢字）に翻訳・音訳すること。
   - 一般的な英単語の略称（HP、MP、OK、アイテムの型番等）を除き、英文や英語フレーズがそのまま残ることは厳禁。
   - カッコ書き等で原文の英語を併記することは厳禁。
2. 完全な翻訳:
   - すべての文を省略せず、一文ずつ丁寧に意訳すること。直訳にならず、自然な日本語のリズムと語順に再構成すること。
   - 登場人物の感情や情景描写のニュアンスを正確に日本語で表現すること。
3. 表記の統一:
   - 人名や用語は、提供された人名辞書や作品の世界観に合わせて一貫したカタカナ／漢字表記にすること。
   - 西洋名やカタカナ語は、一般的な日本の表記規則に従って自然なカタカナで統一すること。
4. 出力制約:
   - 翻訳した日本語本文のみを出力すること。前後の挨拶、解説、注釈は一切含めないこと。
   - 本文を ``` などのマークダウンのコードブロックで囲まないこと（指示された構造タグやマーカーがある場合は、それを削除せず正しく出力すること）。
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble.
- Do not wrap in code fences.
"""

const val V2_PROMPT_3_KO = """あなたはプロの小説翻訳家です。以下のルールを厳守し、韓国語テキストを日本語小説として自然で躍動感のある高品質な文章に完全翻訳してください。

【最重要・必須ルール】
1. ハングル残留の完全禁止:
   - 人名、地名、固有名詞、効果音・擬音語、感嘆詞を含め、すべてのハングルを1文字も残さず自然な日本語に翻訳・音訳すること。
   - カッコ書き等で原文ハングルを併記することは厳禁。
2. 小説としての自然な文体とテンポ:
   - 「〜ということだ」「〜なのだ」「〜することができる」等の直訳特有の単調な語尾の連続を禁止し、文脈に応じた多彩で自然な文末表現にすること。
   - 一人称（나/저）や二人称（너/당신）は、文脈から登場人物の性別・年代・関係性を読み取って自然な日本語（俺、僕、私、お前等）に統一し、作品内でブレさせないこと。
   - 韓国特有のスラング、若者言葉、慣用句、言葉遊びは、直訳せず日本の自然な口語・俗語に的確にローカライズすること。
3. 表記の統一と人名ルール:
   - 人名や固有名詞は、漢字語（Hanja origin）に明確なルーツを持つものは自然な漢字、固有語や西洋名はカタカナで統一すること。
4. 特殊レイアウト・ステータス画面の保持:
   - `[...]` などの角括弧、コロン `:`、ステータス窓、システム通知、引用符の形式は、原文のレイアウトと記号構造を1文字も崩さず維持すること。
5. 完全翻訳と出力制約:
   - 省略や要約、勝手な設定改変は一切行わず、一文ずつ丁寧にすべて翻訳すること。
   - 翻訳した日本語本文のみを出力すること。前後の挨拶、解説、コードフェンス（```）は絶対に含めないこと。
- OUTPUT ONLY: Return only the translated Japanese text. No explanations, notes, or preamble.
- Do not wrap in code fences.
"""

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
 * 文脈注入は単一化：直前訳文末尾があればそれのみ、なければ直前原文末尾。重ね注入はしない。
 * （単体・バッチ先頭・チャンク先頭＝原文末尾、チャンク後続＝訳文末尾。呼出側で択一して渡す）
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

    // 訳文末尾がある場合は原文末尾を重ねない（トークン浪費・重複翻訳の防止）
    val effectiveSourceTail = if (previousTranslatedTail.isNullOrBlank()) previousSourceTail else null
    if (!effectiveSourceTail.isNullOrBlank()) {
        sb.append("\n\n=== PREVIOUS TEXT (context only — do NOT translate or repeat this) ===\n")
        sb.append("...").append(effectiveSourceTail).append("\n")
        sb.append("================================================================\n")
    }

    if (dictionaryEntries.isNotEmpty()) {
        // 技術的根拠1行：ユーザー定義プロンプトの表記指定を破壊しないよう、システム側での勝手な表記スタイル（カタカナ等）の決め打ち固定を廃止し、対応表の指定のみを厳守させる
        sb.append("\n\n[登場人物対応表]\n※以下の登場人物名は、対応表に指定された表記をそのまま厳守して翻訳してください。\n")
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

/**
 * Batch framing block for structured output (JSON mode).
 * `buildBatchJsonSchema()` と対になる指示文。応答は必ず `{"translations": [{"id": N, "text": "..."}]}` 形式。
 */
fun buildBatchJsonFormat(fileCount: Int): String {
    return "\n\nBATCH OUTPUT FORMAT (JSON mode — this overrides any 'output only the translation' instruction above for framing only):\n" +
        "- The input holds $fileCount document(s) wrapped as " +
        "<documents><doc id=\"N\">...</doc></documents>. " +
        "These tags are structural delimiters, NOT text to translate.\n" +
        "- Translate each document separately. Output exactly one JSON object and nothing else:\n" +
        "{\"translations\": [{\"id\": 1, \"text\": \"(Japanese translation of document 1)\"}, ...]} for ids 1..$fileCount.\n" +
        "- Include every id 1..$fileCount. Do NOT skip any id. " +
        "Do NOT add text before the opening brace or after the closing brace.\n" +
        "- REMINDER: your whole response must be exactly one JSON object and nothing else."
}

/** 辞書の1行書式（完全一致・参考例で共通）。旧版の対応表形式を継承 */
private fun formatDictEntry(key: String, value: String, genders: Map<String, String>?): String {
    val gender = genders?.get(key)?.trim()?.takeIf { it.isNotBlank() && it != "不明" }
    return if (gender != null) "・$key → $value (性別: $gender)" else "・$key → $value"
}

/** 辞書照合：本文に現れる見出しのみ抽出する（上限付き） */
fun matchDictionaryEntries(
    sourceText: String,
    characters: Map<String, String>,
    genders: Map<String, String>? = null,
    limit: Int = 200
): List<String> {
    if (characters.isEmpty() || sourceText.isEmpty()) return emptyList()
    val entries = mutableListOf<String>()
    for ((key, value) in characters) {
        if (entries.size >= limit) break
        if (!sourceText.contains(key)) continue
        entries.add(formatDictEntry(key, value, genders))
    }
    return entries
}


/**
 * プロファイル固有のプロンプトを組み立てる（純粋関数）。
 * 渡されたプロンプト（文脈・辞書・バッチ枠・マーカー等の付帯指示込み）の先頭が基底プロンプトに一致すれば
 * ターゲットのプロンプト番号（例: 7番 RETRY）の基底文に差し替える。
 * 一致しない場合（カスタム上書き等）は最初の空行以降を付帯指示とみなして結合するため、
 * 基底文内に空行がある構成では切り分け位置がずれることがある。
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

