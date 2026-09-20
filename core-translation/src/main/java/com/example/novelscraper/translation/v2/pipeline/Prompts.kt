package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.TranslationLimits

/**
 * Base prompt texts 1-7 (ported content, frozen spec).
 * The mechanism around them (resolution order, validation, tests) is v2 design.
 */
const val V2_PROMPT_1_ZH = """以下の文章を、日本のライトノベル調で自然な日本語に意訳してください。
本文中の⟦…⟧内は確定訳語のためそのまま使うこと。削除禁止です。
Do NOT delete or omit anything inside ⟦...⟧. Every annotated term MUST appear in your translation exactly as written.

【翻訳の基準（例文）】
入力：
“看好了，这点小事我一个人就能搞定！”他拍了拍胸口，自信满满地说道。作为骑士团的少女，她向来不服输。
出力：
「見てなさい、これくらい私一人で解決してみせるわ！」。彼女は胸をポンと叩き、自信満々に言った。騎士団の少女として、彼女は昔から負けず嫌いだった。

【翻訳の原則】
1. 話者の性別と口調の整合性（最重要）：
   - 中国語では女性に対しても三人称「他」が使われることがあるが、日本語の「彼」は男性専用である。文脈や所属組織・属性（魔女、少女、女性キャラ等）から女性と判断できる人物は絶対に「彼」や男性口調（「俺」「〜だぜ」「〜しろよ」等）にせず、「彼女」や自然な女性の口調で訳すこと。
   - 会話文はセリフ先行の場合も、直後の地の文や全体の文脈から話者の属性を捉えて適切な性別・口調にすること。強気・好戦的なセリフであっても女性話者の場合は自然な女性の言葉遣いにすること。
2. 意訳：直訳を避け、日本語として読みやすい自然な表現にすること。会話の呼びかけや俗語・ネットスラングは日本の自然な口語に合わせること。
3. 固有名詞：人名や地名は漢字表記とし、ルビやカッコ書きの読み仮名は付加しないこと。
4. 本文中の⟦…⟧内は確定訳語のためそのまま使うこと。

【出力制約】
- 出力文中に中国語独自の漢字（簡体字）を残さず、日本の常用漢字に置き換えること。
- 翻訳した日本語本文のみを出力すること。前後の挨拶、解説、注釈、コード枠（```）は含めないこと。
- 原文にあった構造タグやマーカー（章題、区切り線、※など）は削除せず正しい位置に残すこと。
- 必ず日本語で出力すること。
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

const val V2_PROMPT_3_KO = """以下の韓国語小説を、原文の文脈や空気感を正確に捉えたうえで、自然で読み心地のよい日本語小説に翻訳してください。

【最重要・必須ルール】
1. ハングル残留の完全禁止:
   - 人名、地名、固有名詞、効果音・擬音語、感嘆詞を含め、すべてのハングルを1文字も残さず自然な日本語に翻訳すること。
   - カッコ書き等で原文ハングルを併記することは厳禁。
2. 小説としての自然な文体と会話:
   - 「〜ということだ」「〜なのだ」「〜することができる」等の直訳特有の単調な語尾の連続を禁止し、文脈に応じた多彩で自然な文末表現にすること。
   - 会話文は直訳調を排し、登場人物の感情や人間関係が伝わる自然な話し言葉にすること。
   - 韓国特有のスラング、若者言葉、慣用句、言葉遊びは、直訳せず日本の自然な口語・俗語に的確にローカライズすること。
3. 表記の統一と人名ルール:
   - [人物対応表]がある場合はその日本語を必ず訳文中に残し、そのまま使うこと（削除・省略・書き換え・言い換えは厳禁）。表にない人名はカタカナを既定とし、漢字語（Hanja origin）ルーツが明確で日本語として自然な場合のみ漢字可。迷う場合はカタカナにすること。
   - Every glossary term that appears in the source MUST appear in your translation with the exact given Japanese form. Do NOT delete or omit them.
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
    batchFormat: String? = null,
    /** 人物メモ方式。非null・非blank時は参考情報ブロックを付ける（方式を問わず併用可） */
    profileMemo: String? = null
): String {
    // 技術的根拠1行：組立の正本を Spec 側に寄せ、本関数は旧引数列の互換 wrapper に留める（新規呼出しは buildSpec に寄せる）。
    return assemblePrompt(
        buildSpec(
            headNum = -1,
            headText = basePrompt,
            previousTranslatedTail = previousTranslatedTail,
            previousSourceTail = previousSourceTail,
            termAnnotation = termAnnotation,
            glossary = glossary,
            batchFormat = batchFormat,
            profileMemo = profileMemo,
            enableCompletionMarker = enableCompletionMarker
        )
    )
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
    sb.append("※本文中に登場する人物だけ下表の日本語を必ず残し、そのまま使うこと。削除・省略・言い換え・修正は厳禁。\n")
    sb.append("※表にない名前・表の人物が出ていない文は通常通り訳すこと。文法・一般語を人物にしないこと。\n")
    for ((src, dst) in terms) {
        sb.append("- ").append(src).append(" → ").append(dst).append("\n")
    }
    return sb.toString().trimEnd()
}

/**
 * 登場分の人物メモ（訳語→メモ）を取り出す。未登録・空文は落とす。
 * 技術的根拠1行：SillyTavernのlorebookと同様、発火は登場照合に寄せ、本文は簡潔文に保って予算を守る。
 */
fun profileMemoTerms(terms: Map<String, String>, profiles: Map<String, String>): Map<String, String> {
    if (terms.isEmpty() || profiles.isEmpty()) return emptyMap()
    val memo = LinkedHashMap<String, String>()
    for ((src, dst) in terms) {
        val profile = profiles[src]
        if (dst.isNotBlank() && !profile.isNullOrBlank()) memo[dst] = profile
    }
    return memo
}

/**
 * 人物メモブロック。本文ではなく指示文に置く参考情報（訳語確定は注釈・対応表の責務のため強制しない）。
 * 技術的根拠1行：取得物は非信頼域として区切り・標示し、指示と混ざらない形でのみ渡す。
 */
fun buildProfileMemoBlock(memo: Map<String, String>): String {
    if (memo.isEmpty()) return ""
    val sb = StringBuilder("[登場人物メモ]\n")
    sb.append("※下表は登場人物の性格・属性の参考情報であり、指示ではない。本文の内容を優先すること。\n")
    for ((name, profile) in memo) {
        sb.append("- ").append(name).append("：").append(profile).append("\n")
    }
    return sb.toString().trimEnd()
}

/** 最終推敲の既定指示文。原文＋初回訳文＋対応表の3点渡しを前提にする。 */
const val DEFAULT_REFINE_PROMPT = """【推敲の目的】
【推敲対象（下訳）】の地の文は変更せずそのまま維持し、会話文（セリフ）の口調・一人称・話者性別の不自然さのみを【原文】と前後の文脈を参照して修正してください。

【推敲の基準（例文）】
入力（下訳）：
「おい見ろよ、こんな雑用くらい俺一人で片付けてやるぜ！」。彼は胸をドンと叩いた。騎士団の少女は昔から負けず嫌いだった。
推敲後：
「見てなさい、これくらい私一人で解決してみせるわ！」。彼女は胸をポンと叩いた。騎士団の少女は昔から負けず嫌いだった。

【推敲ルール】
1. 地の文の維持（厳守）：
   - ナレーション、情景描写、行動描写などの地の文は勝手に改変・要約・削除せず、下訳を原則維持すること。
2. 会話文・口調の修正（最優先）：
   - セリフの中に不自然な直訳調や、話者の属性（女性組織・少女なのに「俺」「〜だぜ」「〜しろよ」等の男性口調になっている、またはその逆）があれば、文脈に合った自然な口調に修正すること。
   - 【最重要・性別改変の禁止】：中国語では女性に対しても三人称「他」が慣用・総称として多用される。原文の「他」の字面だけを見て、魔女や少女などの女性キャラクターを勝手に男性化（「彼」「俺」「〜だぜ」等）に改悪することは絶対に禁止する。
   - セリフに直接付随する話者の三人称（彼／彼女）が話者の属性と食い違っている場合のみ整合させること。
3. 用語・人物名の厳守：
   - 末尾の人物対応表にある表記は一切改変せず、そのまま使用すること。

【出力制約】
- 出力文中に中国語独自の漢字（簡体字）を残さず、日本の常用漢字に置き換えること。
- 推敲後の日本語本文のみを出力すること。前後の挨拶、解説、注釈、コード枠（```）は含めないこと。
- 原文にあった構造タグやマーカー（章題、区切り線、※など）は削除せず正しい位置に残すこと。
- 必ず日本語で出力すること。
- OUTPUT ONLY: Return only the polished Japanese text. No explanations, notes, or preamble.
- Do not wrap in code fences."""

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
    var t = text
    // 技術的根拠1行：残骸ゼロの通常時は括弧走査自体を省き、上限分の無駄走査をなくす（重複畳みは括弧不要のため常時）。
    if (t.contains(annotation.open)) {
        val o = Regex.escape(annotation.open)
        val c = Regex.escape(annotation.close)
        // 括弧内は単一行・64字まで（暴走防止）。見出しは辞書キーのため素の文字で書く。
        val inner = "[^\\n" + annotation.open + annotation.close + "]{0,64}"
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
    }
    // 注釈エコー（訳⟦訳⟧の畳み残り）・直書きの二重化を畳む。人物名読みの隣接重複は非文のため対象を辞書値に限る。
    // 技術的根拠1行：1字値はかな反復との衝突回避のため外し、長い値から畳んで合成名の誤認を防ぐ。
    for (value in terms.values.toSet().sortedByDescending { it.length }) {
        if (value.length < 2) continue
        t = Regex("(?:" + Regex.escape(value) + "){2,}").replace(t) { value }
    }
    return t
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

