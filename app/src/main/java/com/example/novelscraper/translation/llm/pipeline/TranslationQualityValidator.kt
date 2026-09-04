package com.example.novelscraper.translation.llm.pipeline

sealed class QualityValidationResult {
    object Success : QualityValidationResult()
    data class Failure(val reason: String) : QualityValidationResult()
}

object TranslationQualityValidator {

    /**
     * 言語別サイズ比既定値 (min %, max %) の単一管理点。
     * `LlmTranslationConfig` の既定値もここを参照し、二重定義を禁止する。
     * EN 上限 220% が正規 (旧 docs の 350% 記載は誤り)。
     */
    const val ZH_MIN_RATIO = 102
    const val ZH_MAX_RATIO = 200
    const val KO_MIN_RATIO = 102
    const val KO_MAX_RATIO = 150
    const val EN_MIN_RATIO = 105
    const val EN_MAX_RATIO = 220
    const val JA_MIN_RATIO = 100
    const val JA_MAX_RATIO = 200

    /** 150B未満の極小テキストは比率誤差が大きいため検査をバイパスする */
    const val MIN_BYTES_FOR_RATIO_CHECK = 150

    /**
     * 残留判定に必要な最小の文字種合計 (これ未満は判定不能として成功扱い)。
     * 日本語の文には必ずかなが含まれるため、30字以上のかなゼロは中国語/韓国語残留とみなせる。
     * 漢字のみの短い見出し (十数文字以下) は誤爆防止のため対象外になる。
     */
    const val MIN_CHARS_FOR_RESIDUAL_CHECK = 30

    /** 純粋簡体字の失敗閾値 (これ以上混入で残留疑い。かな率ゲートと併用) */
    const val MIN_PURE_SIMPLIFIED_COUNT = 10

    /** かな率ゲート (かな% がこれ未満の場合のみ純粋簡体字で落とす。通常日本語は30〜50%) */
    const val MAX_KANA_RATIO_PERCENT = 10

    /**
     * 中国語でしか使わない漢字 (純粋簡体字280字)。JIS X 0208 (6879字) との機械差分で検証済み。
     * 旧表に混入していた常用・人名・表外漢字 (万 区 医 昨 座 凶 乱 数 算 涛 郁 蛛 等89字) は除外済み。
     * web小説頻出の代表的簡体字 (剑 / 长 / 气) を含む。
     */
    private val PURE_SIMPLIFIED_CHARS: Set<Char> = (
        "这们么谁让过还现经动问应实话带见关门车电认书间亲乐热钱买卖发变师飞队风龙马鸟鱼觉" +
        "岁办帮场导敌读对饭记计检节结进紧宽蓝烂类离连联脸练两灵领轮论罗绿妈码骂吗难脑闹农" +
        "评颇铺齐骑启迁牵铅枪强墙抢桥倾庆穷权劝确扰荣赛杀伤设审诗时识势视试适树帅说丝肃诉" +
        "孙损态滩谈汤讨腾题铁厅统头图涂团违围为伟伪纬卫稳务误雾戏虾吓显险县线乡详响项销晓" +
        "协胁谢兴汹须绪续选悬寻训讯逊压鸦鸭哑严颜盐验阳养样谣摇药爷页业仪遗艺议译阴银饮营" +
        "蝇赢颖拥佣忧优邮诱渔娱屿语驭预园圆缘远约跃阅运匀杂灾载赞暂脏灶泽贼增赠闸债战张涨" +
        "帐胀赵针侦诊阵镇挣睁证织职执值侄质钟终种肿轴皱诸烛铸专砖转赚浊资总纵邹组钻" +
        "剑长气"
    ).toSet()

    /** 改行消失とみなす行数比 (訳文行数 < 原文行数 / LINE_LOSS_DIVISOR) */
    const val LINE_LOSS_DIVISOR = 3

    /** 行数比検査を適用する最小の原文行数 */
    const val MIN_LINES_FOR_LINE_CHECK = 5

    /**
     * 前口上1行の完全一致パターン (小文字化後に比較)
     */
    private val PREAMBLE_EXACT = setOf(
        "certainly", "certainly!", "okay", "okay.", "okay!",
        "翻訳しました。", "翻訳です。", "承知しました。",
        "承知いたしました。", "かしこまりました。",
        "翻译完成。", "翻译如下。", "번역했습니다.", "번역 결과입니다."
    )

    /**
     * 前口上行の前方一致パターン (小文字化後に比較)
     */
    private val PREAMBLE_PREFIXES = listOf(
        "certainly! here", "okay, here",
        "here is the translation", "here's the translation",
        "below is the translation", "this is the translation",
        "i have translated",
        "以下、翻訳", "以下は翻訳", "翻訳結果",
        "好的", "以下是", "这里是", "下面是", "这是翻译",
        "번역", "다음은 번역",
        "#", "```"
    )

    /**
     * 後口上行の判定 (末尾の非空行に対して完全一致または前方一致で比較)
     */
    private fun isPostambleLine(rawLine: String): Boolean {
        val line = rawLine.trim()
        if (line.isEmpty()) return false
        val lower = line.lowercase()
        if (lower == "```") return true
        if (line.startsWith("#")) return true
        val exact = setOf(
            "以上です。", "以上になります。", "お楽しみください。",
            "enjoy!", "happy reading!", "hope this helps!",
            "let me know if you need anything else."
        )
        if (exact.any { lower == it.lowercase() }) return true
        val prefixes = listOf(
            "以上", "备注", "注:", "注：", "ps:", "p.s.",
            "note:", "enjoy", "hope you enjoy"
        )
        return prefixes.any { lower.startsWith(it.lowercase()) }
    }

    /**
     * モデルの前口上1行を検知する判定関数
     */
    fun isPreambleLine(rawLine: String): Boolean {
        val line = rawLine.trim().lowercase()
        if (line.isEmpty()) return false
        if (PREAMBLE_EXACT.contains(line)) return true
        return PREAMBLE_PREFIXES.any { line.startsWith(it) }
    }

    /**
     * LLM出力のサニタイズ: 先頭/末尾コードフェンス剥離 → 先頭前口上除去 →
     * 末尾後口上除去 → trim。本文中のフェンスは温存する。
     * `CompletionMarkerHelper` 検証より前に適用すること。
     */
    fun stripPreamble(content: String): String {
        var text = content.trim()
        if (text.isEmpty()) return text

        // 先頭コードフェンス (``` / ```json 等の1行) を剥離
        var lines = text.lines().toMutableList()
        if (lines.first().trim().startsWith("```")) {
            lines = lines.drop(1).toMutableList()
        }
        // 末尾コードフェンスを剥離
        while (lines.isNotEmpty() && lines.last().trim() == "```") {
            lines = lines.dropLast(1).toMutableList()
        }
        // 先頭から連続する空行・前口上行をすべて除去
        var dropHead = 0
        for (line in lines) {
            if (line.isBlank() || isPreambleLine(line)) {
                dropHead++
            } else {
                break
            }
        }
        lines = lines.drop(dropHead).toMutableList()
        // 末尾から連続する空行・後口上行をすべて除去 (最大でも全文は削らない)
        var dropTail = 0
        for (line in lines.asReversed()) {
            if (line.isBlank() || isPostambleLine(line)) {
                dropTail++
            } else {
                break
            }
        }
        if (dropTail in 1..lines.size) {
            lines = lines.dropLast(dropTail).toMutableList()
        }
        return lines.joinToString("\n").trim()
    }

    /**
     * 原文と訳文の総合品質バリデーション
     * @param customMinRatio ユーザー設定の最小許容サイズ比率 (%)
     * @param customMaxRatio ユーザー設定の最大許容サイズ比率 (%)
     */
    fun validate(
        sourceText: String,
        translatedText: String,
        sourceLang: SourceLanguage,
        customMinRatio: Int? = null,
        customMaxRatio: Int? = null
    ): QualityValidationResult {
        val cleaned = stripPreamble(translatedText).trim()
        if (cleaned.isBlank()) {
            return QualityValidationResult.Failure("整形後の訳文が空です")
        }

        // 1. 原文コピー検出 (先頭500文字比較)
        val srcHead = sourceText.take(500).trim()
        val outHead = cleaned.take(500).trim()
        if (srcHead.isNotBlank() && srcHead == outHead) {
            return QualityValidationResult.Failure("翻訳されていません (原文コピー検出)")
        }

        // 2. 残留言語チェック (ZH / KO のみ)
        val langCheck = checkResidualLanguage(cleaned, sourceLang)
        if (langCheck is QualityValidationResult.Failure) {
            return langCheck
        }

        // 3. 行数比チェック (改行消失検出)
        val lineCheck = checkLineCount(sourceText, cleaned)
        if (lineCheck is QualityValidationResult.Failure) {
            return lineCheck
        }

        // 4. サイズ比チェック (設定値または言語別適正デフォルト)
        val sizeRatioCheck = checkSizeRatio(sourceText, cleaned, sourceLang, customMinRatio, customMaxRatio)
        if (sizeRatioCheck is QualityValidationResult.Failure) {
            return sizeRatioCheck
        }

        return QualityValidationResult.Success
    }

    /**
     * 残留言語チェック。
     * CJK統合漢字は日中で同一コードポイントのため、漢字単体では絶対に落とさない。
     * 日本語文には必ずかなが含まれることを利用し、かなゼロの高精度条件でのみ落とす。
     */
    private fun checkResidualLanguage(
        translatedText: String,
        sourceLang: SourceLanguage
    ): QualityValidationResult {
        if (sourceLang != SourceLanguage.ZH && sourceLang != SourceLanguage.KO) {
            return QualityValidationResult.Success
        }

        val detection = LanguageDetector.detect(translatedText, maxLines = Int.MAX_VALUE)
        val kanji = detection.kanjiCount
        val kana = detection.kanaCount
        val hangeul = detection.hangeulCount

        if (sourceLang == SourceLanguage.ZH) {
            val total = kanji + kana
            if (total > MIN_CHARS_FOR_RESIDUAL_CHECK && kana == 0) {
                return QualityValidationResult.Failure("中国語残留の疑い (漢字:$kanji かな:$kana)")
            }
            // 純粋簡体字が高頻度で混入し、かつかなが極端に少ない場合のみ落とす。
            // 通常日本語 (かな率30〜50%) は共有漢字が混じっても素通りする。
            if (total > MIN_CHARS_FOR_RESIDUAL_CHECK &&
                pureSimplifiedCount(translatedText) >= MIN_PURE_SIMPLIFIED_COUNT &&
                kana * 100 < total * MAX_KANA_RATIO_PERCENT
            ) {
                return QualityValidationResult.Failure(
                    "中国語(簡体字)残留を検出 (かな率:${kana * 100 / total}%)"
                )
            }
        } else {
            val total = hangeul + kana
            if (total > MIN_CHARS_FOR_RESIDUAL_CHECK && kana == 0 && hangeul > 0) {
                return QualityValidationResult.Failure("韓国語残留の疑い (ハングル:$hangeul かな:$kana)")
            }
        }

        return QualityValidationResult.Success
    }

    /**
     * 純粋簡体字の混入数を数える (閾値到達で早期終了)。
     */
    private fun pureSimplifiedCount(translatedText: String): Int {
        var count = 0
        for (ch in translatedText) {
            if (PURE_SIMPLIFIED_CHARS.contains(ch)) {
                count++
                if (count >= MIN_PURE_SIMPLIFIED_COUNT) break
            }
        }
        return count
    }

    /**
     * 行数比チェック。訳文の非空行数が原文の 1/[LINE_LOSS_DIVISOR] 未満なら改行消失とみなす。
     */
    private fun checkLineCount(
        sourceText: String,
        translatedText: String
    ): QualityValidationResult {
        val srcLines = sourceText.lines().filter { it.isNotBlank() }
        val outLines = translatedText.lines().filter { it.isNotBlank() }

        if (srcLines.size < MIN_LINES_FOR_LINE_CHECK || outLines.isEmpty()) {
            return QualityValidationResult.Success
        }

        if (outLines.size * LINE_LOSS_DIVISOR < srcLines.size) {
            return QualityValidationResult.Failure(
                "改行消失疑い (原文:${srcLines.size}行 翻訳後:${outLines.size}行)"
            )
        }

        return QualityValidationResult.Success
    }

    private fun checkSizeRatio(
        sourceText: String,
        translatedText: String,
        sourceLang: SourceLanguage,
        customMinRatio: Int? = null,
        customMaxRatio: Int? = null
    ): QualityValidationResult {
        val srcBytes = sourceText.toByteArray(Charsets.UTF_8).size
        val outBytes = translatedText.toByteArray(Charsets.UTF_8).size
        // 150B未満の極小テキスト (章タイトル・短い一文等) は比率誤差が大きいためバイパス
        if (srcBytes < MIN_BYTES_FOR_RATIO_CHECK) return QualityValidationResult.Success

        val (defaultMin, defaultMax) = when (sourceLang) {
            SourceLanguage.ZH -> ZH_MIN_RATIO to ZH_MAX_RATIO
            SourceLanguage.KO -> KO_MIN_RATIO to KO_MAX_RATIO
            SourceLanguage.EN -> EN_MIN_RATIO to EN_MAX_RATIO
            SourceLanguage.JA -> JA_MIN_RATIO to JA_MAX_RATIO
        }

        val minRatio = customMinRatio ?: defaultMin
        val maxRatio = customMaxRatio ?: defaultMax

        if (outBytes * 100 < srcBytes * minRatio) {
            return QualityValidationResult.Failure("サイズ比不足 ($outBytes B / $srcBytes B = ${outBytes * 100 / srcBytes}%) → 省略疑い [基準:${minRatio}%]")
        }
        if (outBytes * 100 > srcBytes * maxRatio) {
            return QualityValidationResult.Failure("サイズ比超過 ($outBytes B / $srcBytes B = ${outBytes * 100 / srcBytes}%) → 水増し疑い [基準:${maxRatio}%]")
        }

        return QualityValidationResult.Success
    }
}
