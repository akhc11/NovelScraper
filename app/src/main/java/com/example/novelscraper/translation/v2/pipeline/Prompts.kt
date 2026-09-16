package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.TranslationLimits

/**
 * Base prompt texts 1-7 (ported content, frozen spec).
 * The mechanism around them (resolution order, validation, tests) is v2 design.
 */
const val V2_PROMPT_1_ZH = """あなたは中国語→日本語の文芸翻訳を20年手がけるプロの小説翻訳家です。出版レベルの訳文を作ります。機械翻訳的な直訳や、意味を汲んだだけの要約は成果物として認められません。以下のルールを厳守し、次の中国語を自然な日本語に翻訳してください。中国語（簡体字・繁体字・中国語特有の表現）が絶対に残留しないように全て完全に翻訳してください。

【厳格な翻訳ルール】
1. 省略・要約・圧縮の完全禁止:
   - 複数の台詞や文を一つにまとめたり、細かい心理・情景・戦闘・技術説明を大雑把に丸め込む「パラフレーズ圧縮」は一切禁止します。
   - 原文の一行、一文、一言の台詞、内面の独白、周囲の反応、状況描写を一行たりとも省略せず、すべて余すところなく日本語として書き出してください。
   - 途中で止めず、与えられたテキストの最後の一文まで訳し切る。
2. 固有名詞・人名の表記ルール（漢字優先）:
   人名・固有名詞は語源で判定し、迷う場合は漢字表記を優先すること。明らかな西洋音訳名のみカタカナに音訳し（克莱恩→クライン等）、中華名・意味の取れる複合名（黑山→黒山等）は日本の常用漢字・新字体に復元すること（李云→李雲等）。西洋と断定できない名前は漢字にすること。
   本文中の⟦…⟧内は確定訳語。そのまま使うこと（言い換え・修正は厳禁）。
3. 自然で流麗な日本語小説文（直訳の排除）:
   - 「省略しない」ことは「ぎこちない逐語訳にする」ことではありません。
   - 中国語特有の文体や硬直した構文を解きほぐし、日本語の小説として自然で息遣いが伝わる情景・心理描写に昇華させてください。
   - 登場人物の感情や情景描写のニュアンスを正確に日本語で表現すること。
4. 出力制約:
   - 翻訳した日本語本文のみを出力すること。前後の挨拶、解説、注釈は一切含めないこと。
   - 本文を ``` などのマークダウンのコードブロックで囲まないこと。
   - 原文にあった構造タグやマーカー（章題、区切り線、※など）は削除せず正しい位置に残すこと。
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
[人物対応表挿入位置]
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
   - [人物対応表]がある場合はその日本語をそのまま使うこと（カタカナ・漢字の書き換え、言い換えは厳禁）。表にない人名はカタカナを既定とし、漢字語（Hanja origin）ルーツが明確で日本語として自然な場合のみ漢字可。迷う場合はカタカナにすること。
   - 助詞・文法部品を人名と混同しないこと（例：「물이나」の「이나」は文法なので人名にしない）。
[人物対応表挿入位置]
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
[人物対応表挿入位置]
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
[人物対応表挿入位置]
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
[人物対応表挿入位置]
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
 * 対応表の挿入位置指定。基底文中のこの行がある位置に対応表ブロックを混ぜ込む。
 * 技術的根拠1行：始めと終わりが強く中間が落ちる位置バイアスのため、用語表は人名規則の隣に置く（無ければ従来通り末尾に付ける）。
 */
const val GLOSSARY_SLOT = "[人物対応表挿入位置]"

/**
 * systemプロンプト組立。基底文は呼出側（設定由来）から受け取り、本器は付帯指示のみ付加する。
 * 文脈注入は単一化：直前訳文末尾があればそれのみ、なければ直前原文末尾。重ね注入はしない。
 * （単体・バッチ先頭・チャンク先頭＝原文末尾、チャンク後続＝訳文末尾。呼出側で択一して渡す）
 */
fun buildSystemPrompt(
    basePrompt: String,
    previousTranslatedTail: String? = null,
    previousSourceTail: String? = null,
    /** 注釈方式の区切り。非null時は対応確定の1行指示を付ける */
    termAnnotation: TermAnnotation? = null,
    /** 対応表方式（韓国語用）。非null・非empty時は人物対応表ブロックを付ける。注釈方式とは択一 */
    glossary: Map<String, String>? = null,
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

    if (termAnnotation != null) {
        // 技術的根拠1行：適用保証をモデルの遵守から機械的な写し作業に移すため、対応確定の1行にする（括弧除去は剥離の責務のため指示しない）。
        sb.append("\n\n[確定訳語]\n※本文中の ${termAnnotation.open}…${termAnnotation.close} 内は確定訳語。そのまま使うこと。言い換え・修正は厳禁。\n")
    }

    if (!glossary.isNullOrEmpty()) {
        val block = buildGlossaryBlock(glossary)
        val at = sb.indexOf(GLOSSARY_SLOT)
        if (at >= 0) {
            sb.replace(at, at + GLOSSARY_SLOT.length, block)
        } else {
            sb.append("\n\n")
            sb.append(block)
            sb.append("\n")
        }
    } else {
        // 技術的根拠1行：表なし時に指定行を残すと宙ぶらりんの指示になるため消す（前後の空行は害がないため残す）。
        var at = sb.indexOf(GLOSSARY_SLOT)
        while (at >= 0) {
            sb.replace(at, at + GLOSSARY_SLOT.length, "")
            at = sb.indexOf(GLOSSARY_SLOT)
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

/**
 * 辞書照合の単一正本：本文に現れる見出し→訳語の対応だけを抜き出す（上限付き）。
 * 技術的根拠1行：注釈付与の抽出はここだけに置き、呼び側での個別走査による一致漏れをなくす。
 */
fun matchDictionaryMap(
    sourceText: String,
    characters: Map<String, String>,
    limit: Int = TranslationLimits.DICT_MATCH_LIMIT
): Map<String, String> {
    if (characters.isEmpty() || sourceText.isEmpty()) return emptyMap()
    val matched = LinkedHashMap<String, String>()
    for ((key, value) in characters) {
        if (matched.size >= limit) break
        if (key.isNotBlank() && sourceText.contains(key)) matched[key] = value
    }
    return matched
}

/**
 * 韓国語用の文節先頭照合。分かち書きのかたまりの先頭にある名前だけ拾う。
 * 技術的根拠1行：韓国語は助詞・語尾が名前に直接くっつく膠着語のため、単純な含む探しでは文法・動詞・季節の中身に誤爆する（例：水でもの中の人名、悩んでいたの中の人名、晩夏の中の人名、フルネーム＋印の末尾が別人に見える）。左がハングルでない位置だけ採用し、右（助詞側）は問わない。
 * 動作例：原文「水でも汲んでこい」は人名にしない、原文「イナが叱った」は人名にする。
 */
fun matchDictionaryMapKo(
    sourceText: String,
    characters: Map<String, String>,
    limit: Int = TranslationLimits.DICT_MATCH_LIMIT
): Map<String, String> {
    if (characters.isEmpty() || sourceText.isEmpty()) return emptyMap()
    val matched = LinkedHashMap<String, String>()
    for ((key, value) in characters) {
        if (matched.size >= limit) break
        if (key.isBlank()) continue
        var from = 0
        var hit = false
        while (from <= sourceText.length - key.length) {
            val idx = sourceText.indexOf(key, from)
            if (idx < 0) break
            val leftOk = idx == 0 || !ScriptKinds.isHangul(sourceText[idx - 1].code)
            if (leftOk) {
                hit = true
                break
            }
            from = idx + 1
        }
        if (hit) matched[key] = value
    }
    return matched
}

private fun isAsciiLetter(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z'

/**
 * 英語用の語境界照合。単語として独立した名前だけ拾う（大文字小文字は区別）。
 * 技術的根拠1行：英語は空白で切れるため膠着語の癒着はないが、人名と一般語の同形（例：人名MarkとMarket、文頭の助動詞Willと人物Will）に単純な含む探しが誤爆する。左右とも英字でない位置だけ採用する。
 * 動作例：原文「Market opens」は人名にしない、原文「Mark came」・「John's book」は人名にする。
 */
fun matchDictionaryMapEn(
    sourceText: String,
    characters: Map<String, String>,
    limit: Int = TranslationLimits.DICT_MATCH_LIMIT
): Map<String, String> {
    if (characters.isEmpty() || sourceText.isEmpty()) return emptyMap()
    val matched = LinkedHashMap<String, String>()
    for ((key, value) in characters) {
        if (matched.size >= limit) break
        if (key.isBlank()) continue
        var from = 0
        var hit = false
        while (from <= sourceText.length - key.length) {
            val idx = sourceText.indexOf(key, from)
            if (idx < 0) break
            val leftOk = idx == 0 || !isAsciiLetter(sourceText[idx - 1])
            val end = idx + key.length
            val rightOk = end >= sourceText.length || !isAsciiLetter(sourceText[end])
            if (leftOk && rightOk) {
                hit = true
                break
            }
            from = idx + 1
        }
        if (hit) matched[key] = value
    }
    return matched
}

/**
 * 人物対応表ブロック。原文は改変せず指示だけ別枠で渡す。
 * 技術的根拠1行：割り込み注釈は語形を壊し汚染・剥離失敗を招く言語があるため、対応表で原文を温存し語形合わせをモデルに委ねる（無関係行は無視してよい旨を明記し誤爆時の強制汚染を防ぐ）。
 */
fun buildGlossaryBlock(terms: Map<String, String>): String {
    if (terms.isEmpty()) return ""
    val sb = StringBuilder("[人物対応表]\n")
    sb.append("※本文中に登場する人物だけ下表の日本語を使うこと。言い換え・修正は厳禁。\n")
    sb.append("※表にない名前・表の人物が出ていない文は通常通り訳すこと。文法・一般語を人物にしないこと。\n")
    for ((src, dst) in terms) {
        sb.append("- ").append(src).append(" → ").append(dst).append("\n")
    }
    return sb.toString().trimEnd()
}

/** 最終推敲の既定指示文。原文＋初回訳文＋対応表の3点渡しを前提にする。 */
const val DEFAULT_REFINE_PROMPT = """あなたはプロの翻訳校閲者です。
以下の原文と翻訳済みテキストを比べ、誤り・不自然さを正した最終訳文を作ってください。

### 校正ルール
1. **内容の維持（厳守）:**
   - 原文の意味内容を変えないこと。省略・要約・創作の追加は厳禁。
2. **用語・人物名の厳守:**
   - 末尾の人物対応表にある表記は一切改変せず、このまま使うこと。
   - 表にない名前は通常通り扱い、文法・一般語を人物にしないこと。
3. **自然さと一貫性:**
   - 直訳特有のぎこちない言い回しを、原文の文体・雰囲気に合った自然な日本語に直すこと。
   - 登場人物の口調（語尾・一人称）にブレがあれば統一すること。
- 出力は推敲後の日本語本文のみ。前後の挨拶・解説・コード枠は含めないこと。
- OUTPUT ONLY: Return only the polished Japanese text. No explanations, notes, or preamble."""

/**
 * 推敲指示文の解決（唯一の入口）。空・空白は既定文に落とす。
 * 技術的根拠1行：解決則の二重実装は必ず乖離するため、設定→実行文の変換はここだけに置く。
 */
fun resolveRefinePrompt(custom: String): String {
    val cap = TranslationLimits.MAX_DICT_PROMPT_CHARS
    val t = if (custom.length > cap) custom.substring(0, cap) else custom
    return t.ifBlank { DEFAULT_REFINE_PROMPT }
}

/**
 * 推敲入力の組み立て（唯一の入口）。原文・初回訳文・対応表を区切り付きで束ねる。
 * 技術的根拠1行：3点の並べ方を1箇所にし、単品・束ねでの渡し違いをなくす。
 */
fun buildRefineInput(sourceText: String, firstTranslation: String, terms: Map<String, String>): String {
    val sb = StringBuilder()
    sb.append("=== SOURCE（原文・書き換え対象ではない）===\n")
    sb.append(sourceText.trim()).append("\n\n")
    sb.append("=== TRANSLATION（推敲対象）===\n")
    sb.append(firstTranslation.trim())
    if (terms.isNotEmpty()) {
        sb.append("\n\n")
        sb.append(buildGlossaryBlock(terms))
    }
    return sb.toString()
}

/** 注釈・検証の対象可否。値同一は恒等写像、1字見出しは一般語（离开・尘土等）に誤爆するため外す。 */
internal fun isAnnotatableTerm(key: String, value: String): Boolean = key != value && key.length >= 2

/** ·区切り名の分解用区切り（原文側・訳文側で共有）。 */
private val DICT_PART_SEPARATORS = charArrayOf('·', '・', '∙', '•', ' ', '　')

/**
 * 別名展開：·区切りフルネームの構成要素→対応部分訳。全体照合で拾えない短形の適用用。
 * 技術的根拠1行：短形の有無をLLMの名寄せ判断に委ねると消滅・ブレるため、確定分解できる分だけ機械的に復元する。
 * 対象可否は [isAnnotatableTerm] に一本化する（二重実装は必ず乖離するため）。
 */
fun matchDictionaryAliases(
    sourceText: String,
    characters: Map<String, String>,
    exclude: Set<String> = emptySet(),
    limit: Int = TranslationLimits.DICT_MATCH_LIMIT,
    onConflict: (String) -> Unit = {}
): Map<String, String> {
    if (characters.isEmpty() || sourceText.isEmpty() || limit <= 0) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for ((key, value) in characters) {
        if (result.size >= limit) break
        val keyParts = key.split(*DICT_PART_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (keyParts.size < 2) continue
        val valParts = value.split(*DICT_PART_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (valParts.size != keyParts.size) continue
        for ((kp, vp) in keyParts.zip(valParts)) {
            if (result.size >= limit) break
            if (!isAnnotatableTerm(kp, vp)) continue
            val prior = result[kp]
            if (prior != null) {
                if (prior != vp) onConflict(kp)
                continue
            }
            if (kp in exclude) continue
            if (!sourceText.contains(kp)) continue
            result[kp] = vp
        }
    }
    return result
}

/** 注釈区切り。候補から本文に無い組を選ぶ */
data class TermAnnotation(val open: String, val close: String)

/** 剥離に渡す用語指定（注釈対象分＋使用区切り） */
data class DictStrip(val terms: Map<String, String>, val annotation: TermAnnotation)

/** 注釈区切りの候補（本文に無い最初の組を使う）。いずれも本文混入時は注釈を見送る */
val TERM_ANNOTATION_CANDIDATES = listOf(
    "⟦" to "⟧", "⦅" to "⦆", "❰" to "❱",
    "⦃" to "⦄", "⦑" to "⦒", "⟪" to "⟫"
)

/**
 * 注釈区切りの選択。本文に候補組の片方でもあれば使わず、全部ある場合は注釈自体を見送る。
 * 技術的根拠1行：区切り衝突は注釈破壊に直結するため、候補枯渇時は辞書なし素通しを呼出側に委ねる。
 */
fun selectTermAnnotation(text: String): TermAnnotation? {
    val pair = TERM_ANNOTATION_CANDIDATES.firstOrNull { (open, close) ->
        !text.contains(open) && !text.contains(close)
    } ?: return null
    return TermAnnotation(pair.first, pair.second)
}

/**
 * 注釈付与。長い名前から1回走査し、短名による長い名前の破壊を防ぐ。
 * 技術的根拠1行：短い方から置換すると長い名前が壊れるため、長さ降順の1回走査を構造にする。
 */
fun annotateSourceTerms(text: String, terms: Map<String, String>, annotation: TermAnnotation): String {
    val ordered = terms.keys.filter { it.isNotBlank() }.sortedByDescending { it.length }
    if (ordered.isEmpty()) return text
    val pattern = Regex(ordered.joinToString("|") { Regex.escape(it) })
    return pattern.replace(text) { m ->
        val key = m.value
        key + annotation.open + (terms[key] ?: key) + annotation.close
    }
}

/**
 * 注釈の畳み込み。`原文⟦確定訳⟧`→確定訳（値が違っても辞書値に正す）、単独`⟦確定訳⟧`→確定訳。
 * 見出し完全一致で置換するため直前文を食べない。未知の残骸は括弧だけ外して本文を残す。
 * 技術的根拠1行：剥離口を検証の唯一の入口に集約し、単品・束ね・大での扱い違いをなくす。
 */
fun stripTermAnnotations(text: String, terms: Map<String, String>, annotation: TermAnnotation): String {
    if (terms.isEmpty()) return text
    // 技術的根拠1行：残骸ゼロの通常時は走査自体を省き、上限分の無駄走査をなくす。
    if (!text.contains(annotation.open)) return text
    val o = Regex.escape(annotation.open)
    val c = Regex.escape(annotation.close)
    // 括弧内は単一行・64字まで（暴走防止）。見出しは辞書キーのため素の文字で書く。
    val inner = "[^\\n" + annotation.open + annotation.close + "]{0,64}"
    var t = text
    val ordered = terms.keys.filter { it.isNotBlank() }.sortedByDescending { it.length }
    for (key in ordered) {
        val value = terms[key] ?: continue
        // 技術的根拠1行：置換文字列はラムダ形にし、値中の `$`・`\` の誤展開をなくす。
        t = Regex(Regex.escape(key) + o + inner + c).replace(t) { value }
    }
    for (value in terms.values.toSet()) {
        if (value.isNotBlank()) {
            t = Regex(o + Regex.escape(value) + c).replace(t) { value }
        }
    }
    // モデル由来の未知残骸は中身を残して括弧だけ外す（本文欠落より可視ゴミ残存の方が害が小さい）。
    t = Regex(o + "(" + inner + ")" + c).replace(t) { it.groupValues[1] }
    return t
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

/** 対応表検査で必須にする見出しの最小文字数。短い見出しは一般語と同形で誤爆するため警告止まりにする。 */
const val GLOSSARY_STRICT_MIN_LEN = 3

/**
 * 検証条件ひとまとまり（注釈方式・対応表方式の共通口）。必須語が空＝検査なし。
 * 技術的根拠1行：注釈用・対応表用の2口は必ず乖離するため、検査条件はここに一本化する。
 */
data class DictCheck(
    /** 対応表・磨き入力に使う全語 */
    val terms: Map<String, String> = emptyMap(),
    /** 非null時は注釈残骸を確定訳へ畳んでから確かめる */
    val strip: DictStrip? = null,
    /** 訳文に必須の語。空＝検査なし */
    val required: Map<String, String> = emptyMap(),
    /** 必須語キー→許容読み（短形等）。値・許容のいずれかがあれば合格 */
    val alternates: Map<String, List<String>> = emptyMap()
)

/** 指示の組み立て結果（送信用本文＋付帯物）。注釈方式と対応表方式のどちらか片方だけが入る。 */
data class DictPrepared(
    val sendText: String,
    val annotation: TermAnnotation? = null,
    val glossary: Map<String, String>? = null
)

/**
 * 言語別の辞書方式。探す・別名・指示・必須語の4役を1組にする。
 * 技術的根拠1行：単品・束ねの散在分岐は3言語目で3倍化するため、選択を入口の1回に寄せる（密封＋網羅検査で混入を検出）。
 */
sealed interface DictPolicy {
    fun matchTerms(sourceText: String, characters: Map<String, String>, limit: Int = TranslationLimits.DICT_MATCH_LIMIT): Map<String, String>
    fun matchAliases(
        sourceText: String,
        characters: Map<String, String>,
        exclude: Set<String> = emptySet(),
        limit: Int = TranslationLimits.DICT_MATCH_LIMIT,
        onConflict: (String) -> Unit = {}
    ): Map<String, String>
    fun prepare(content: String, terms: Map<String, String>): DictPrepared
    fun check(terms: Map<String, String>, annotation: TermAnnotation?): DictCheck
    /** 必須語の許容読み。既定はなし */
    fun alternates(terms: Map<String, String>): Map<String, List<String>> = emptyMap()
    fun logLabel(): String

    /** 中国語等の既定方式。原文に確定訳を写す注釈方式。 */
    data object Default : DictPolicy {
        override fun matchTerms(sourceText: String, characters: Map<String, String>, limit: Int) =
            matchDictionaryMap(sourceText, characters, limit)

        override fun matchAliases(
            sourceText: String,
            characters: Map<String, String>,
            exclude: Set<String>,
            limit: Int,
            onConflict: (String) -> Unit
        ) = matchDictionaryAliases(sourceText, characters, exclude, limit, onConflict)

        override fun prepare(content: String, terms: Map<String, String>): DictPrepared {
            if (terms.isEmpty()) return DictPrepared(content)
            val annotation = selectTermAnnotation(content) ?: return DictPrepared(content)
            return DictPrepared(annotateSourceTerms(content, terms, annotation), annotation, null)
        }

        override fun check(terms: Map<String, String>, annotation: TermAnnotation?): DictCheck {
            if (terms.isEmpty() || annotation == null) return DictCheck(terms)
            return DictCheck(terms, DictStrip(terms, annotation), terms)
        }

        override fun logLabel() = "注釈"
    }

    /**
     * 対応表方式の共通実装。原文温存・長い名のみ必須。
     * 技術的根拠1行：対応表の扱いは言語で変えないため、探し方だけ変えて残りは共用する（コピペ重複を作らない）。
     */
    sealed class GlossaryPolicy : DictPolicy {
        final override fun matchAliases(
            sourceText: String,
            characters: Map<String, String>,
            exclude: Set<String>,
            limit: Int,
            onConflict: (String) -> Unit
        ) = emptyMap<String, String>()

        final override fun prepare(content: String, terms: Map<String, String>): DictPrepared {
            if (terms.isEmpty()) return DictPrepared(content)
            return DictPrepared(content, null, terms)
        }

        final override fun check(terms: Map<String, String>, annotation: TermAnnotation?): DictCheck {
            if (terms.isEmpty()) return DictCheck(terms)
            val strict = terms.filter { (key, value) -> value.isNotBlank() && key.length >= GLOSSARY_STRICT_MIN_LEN }
            return DictCheck(terms, null, strict, alternates(terms))
        }

        /**
         * 韓国式短形の許容読み。3文字名は姓＋名前とみなし、下2文字→訳の後半を許容する。
         * 技術的根拠1行：章内のフルネームと短形の混在（37話の진예서4回・예서単独3回）にモデルが短形へ寄せても別人でないため、章内で衝突のない短形だけ許容する。探し方・指示文は変えない。
         * 動作例：진예서→ジン・イェソはイェソでも合格、短形名の本人が章内にいる時・別読みが2者ある時は不採用。
         */
        final override fun alternates(terms: Map<String, String>): Map<String, List<String>> {
            val claims = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for ((key, value) in terms) {
                if (key.length != 3 || value.isBlank()) continue
                val parts = value.split('・').map { it.trim() }
                if (parts.size != 2 || parts.any { it.isBlank() }) continue
                claims.getOrPut(key.drop(1)) { mutableListOf() }.add(key to parts[1])
            }
            val out = mutableMapOf<String, List<String>>()
            for ((shortKey, list) in claims) {
                if (shortKey in terms) continue
                val readings = list.map { it.second }.distinct()
                if (readings.size != 1) continue
                for ((reqKey, _) in list) {
                    out[reqKey] = listOf(readings[0])
                }
            }
            return out
        }

        final override fun logLabel() = "対応表"
    }

    /**
     * 韓国語方式。文節先頭照合＋対応表方式。
     * 根拠実測：フルネーム＋印の末尾が別人に見える（11話13件・15話15件）、文法・動詞・季節の中身に誤爆。
     */
    data object Ko : GlossaryPolicy() {
        override fun matchTerms(sourceText: String, characters: Map<String, String>, limit: Int) =
            matchDictionaryMapKo(sourceText, characters, limit)
    }

    /**
     * 英語方式。語境界照合＋対応表方式。
     * 根拠：人名と一般語の同形（Mark／Market、文頭の助動詞Will／人物Will）に単純な含む探しが誤爆する。
     */
    data object En : GlossaryPolicy() {
        override fun matchTerms(sourceText: String, characters: Map<String, String>, limit: Int) =
            matchDictionaryMapEn(sourceText, characters, limit)
    }

    companion object {
        /** 方針選択の唯一の入口。韓・英は対応表方式、他は注釈方式。 */
        fun forLang(sourceLang: SourceLang): DictPolicy =
            when (sourceLang) {
                SourceLang.KO -> Ko
                SourceLang.EN -> En
                else -> Default
            }
    }
}

