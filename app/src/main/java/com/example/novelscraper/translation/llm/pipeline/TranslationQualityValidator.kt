package com.example.novelscraper.translation.llm.pipeline

sealed class QualityValidationResult {
    object Success : QualityValidationResult()
    data class Failure(val reason: String) : QualityValidationResult()
}

object TranslationQualityValidator {

    /**
     * 日本語（JIS常用・人名漢字）に存在しない、中国語簡体字に特有の文字群（純粋簡体字）
     */
    private val SIMPLIFIED_CHINESE_CHARS = charArrayOf(
        '这', '们', '么', '谁', '让', '过', '还', '从', '现', '个',
        '经', '动', '问', '应', '实', '话', '带', '见', '关', '门',
        '车', '电', '认', '书', '写', '声', '间', '亲', '听', '乐',
        '热', '钱', '买', '卖', '发', '变', '师', '飞', '队', '风',
        '云', '龙', '马', '鸟', '鱼', '觉', '岁', '办', '帮', '场',
        '导', '敌', '读', '独', '断', '对', '饭', '记', '计', '检',
        '节', '结', '进', '紧', '宽', '况', '蓝', '烂', '累', '类',
        '离', '连', '联', '脸', '练', '凉', '两', '灵', '领', '乱',
        '轮', '论', '罗', '绿', '妈', '码', '骂', '吗', '难', '脑',
        '闹', '农', '评', '凭', '颇', '铺', '齐', '骑', '启', '气',
        '迁', '牵', '铅', '枪', '强', '墙', '抢', '桥', '倾', '庆',
        '穷', '区', '权', '劝', '确', '扰', '荣', '赛', '杀', '伤',
        '设', '审', '师', '诗', '时', '识', '势', '视', '试', '适',
        '树', '数', '帅', '双', '说', '丝', '肃', '诉', '算', '随',
        '孙', '损', '态', '滩', '谈', '汤', '涛', '讨', '腾', '题',
        '体', '铁', '厅', '统', '头', '图', '涂', '团', '湾', '万',
        '网', '违', '围', '为', '伟', '伪', '纬', '卫', '稳', '务',
        '误', '雾', '戏', '虾', '峡', '吓', '显', '险', '县', '线',
        '乡', '详', '响', '项', '销', '晓', '协', '胁', '携', '谢',
        '醒', '兴', '幸', '凶', '汹', '须', '叙', '蓄', '绪', '续',
        '选', '悬', '寻', '训', '讯', '逊', '压', '押', '鸦', '鸭',
        '哑', '严', '颜', '盐', '演', '验', '阳', '养', '样', '谣',
        '摇', '遥', '药', '爷', '页', '业', '医', '仪', '遗', '艺',
        '议', '译', '阴', '银', '饮', '营', '蝇', '赢', '颖', '拥',
        '佣', '涌', '踊', '忧', '优', '邮', '犹', '油', '游', '诱',
        '渔', '娱', '屿', '语', '驭', '吁', '郁', '预', '园', '圆',
        '缘', '远', '愿', '约', '跃', '阅', '岳', '粤', '运', '匀',
        '杂', '灾', '载', '赞', '暂', '脏', '灶', '泽', '贼', '增',
        '赠', '扎', '闸', '炸', '摘', '债', '战', '张', '涨', '掌',
        '帐', '胀', '障', '赵', '针', '侦', '诊', '阵', '镇', '争',
        '征', '挣', '睁', '筝', '蒸', '拯', '整', '证', '症', '织',
        '职', '植', '执', '值', '侄', '制', '质', '治', '致', '智',
        '钟', '终', '种', '肿', '轴', '昼', '皱', '诸', '猪', '蛛',
        '烛', '著', '助', '祝', '铸', '筑', '抓', '专', '砖', '转',
        '赚', '庄', '装', '壮', '状', '追', '准', '捉', '灼', '浊',
        '资', '姿', '滋', '紫', '总', '纵', '邹', '阻', '组', '祖',
        '钻', '嘴', '尊', '昨', '坐', '座', '做'
    ).toSet()

    /**
     * モデルの前口上1行を検知する判定関数 (bash v29.0.5.7.0 _is_preamble_line 準拠)
     *
     * 「以下」「了解」「承知」などの単語で始まるだけで削除してしまうと、
     * 小説本文の台詞や地の文 (例:「了解しました、隊長。」「以下は彼の手記である。」) まで
     * 誤って削除してしまうため、末尾が句点・コロン等で終わる短い決まり文句に限定して判定する。
     */
    fun isPreambleLine(rawLine: String): Boolean {
        val line = rawLine.trim().lowercase()
        return when {
            line == "certainly" || line == "certainly!" || line == "okay" || line == "okay." || line == "okay!" -> true
            line.startsWith("certainly! here") || line.startsWith("okay, here") -> true
            line.startsWith("here is the translation") || line.startsWith("here's the translation") -> true
            line.startsWith("below is the translation") || line.startsWith("this is the translation") -> true
            line.startsWith("i have translated") -> true
            line.startsWith("以下、翻訳") || line.startsWith("以下は翻訳") -> true
            line == "翻訳しました。" || line == "翻訳です。" || line.startsWith("翻訳結果") -> true
            line == "承知しました。" || line == "承知いたしました。" || line == "かしこまりました。" -> true
            else -> false
        }
    }

    /**
     * AIの前口上行を検知して除去する (先頭・末尾の空行整形を含む)
     */
    fun stripPreamble(content: String): String {
        val lines = content.lines()
        if (lines.isEmpty()) return content.trim()

        val firstLine = lines.first()
        return if (isPreambleLine(firstLine)) {
            lines.drop(1).joinToString("\n").trim()
        } else {
            content.trim()
        }
    }

    /**
     * 原文と訳文の総合品質バリデーション
     */
    fun validate(
        sourceText: String,
        translatedText: String,
        sourceLang: SourceLanguage
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

        // 3. 平均行長チェック (改行消失検出)
        val lineLenCheck = checkAverageLineLength(sourceText, cleaned)
        if (lineLenCheck is QualityValidationResult.Failure) {
            return lineLenCheck
        }

        // 4. サイズ比チェック (省略・水増し検出)
        val sizeRatioCheck = checkSizeRatio(sourceText, cleaned, sourceLang)
        if (sizeRatioCheck is QualityValidationResult.Failure) {
            return sizeRatioCheck
        }

        return QualityValidationResult.Success
    }

    private fun checkResidualLanguage(
        translatedText: String,
        sourceLang: SourceLanguage
    ): QualityValidationResult {
        if (sourceLang != SourceLanguage.ZH && sourceLang != SourceLanguage.KO) {
            return QualityValidationResult.Success
        }

        // ① 純粋な簡体字の直接検出 (中国語原文の場合: 3文字以上の混入でリジェクト)
        if (sourceLang == SourceLanguage.ZH) {
            val simplifiedMatches = mutableListOf<Char>()
            for (ch in translatedText) {
                if (SIMPLIFIED_CHINESE_CHARS.contains(ch)) {
                    simplifiedMatches.add(ch)
                    if (simplifiedMatches.size >= 3) break
                }
            }
            if (simplifiedMatches.size >= 3) {
                return QualityValidationResult.Failure("中国語(簡体字)残留を検出: 「${simplifiedMatches.joinToString("")}」")
            }
        }

        // ② 全文を対象とした文字種集計 (bash スクリプト _count_chars と完全同一の判定基準)
        val detection = LanguageDetector.detect(translatedText, maxLines = Int.MAX_VALUE)
        val kanji = detection.kanjiCount
        val kana = detection.kanaCount
        val ko = detection.hangeulCount

        if (sourceLang == SourceLanguage.ZH) {
            val total = kanji + kana
            // 漢字がかなの7倍超 (漢字比率87.5%超) → 中国語残留と判定
            if (total > 50 && kanji > kana * 7) {
                return QualityValidationResult.Failure("中国語残留の疑い (漢字:$kanji かな:$kana / 漢字比率:${kanji * 100 / total}%)")
            }
            // かなが極端に少ない → 日本語化不足
            if (total > 80 && kana < 8) {
                return QualityValidationResult.Failure("日本語化不足 (かな:$kana / 全体:$total)")
            }
        } else if (sourceLang == SourceLanguage.KO) {
            val total = ko + kana
            // ハングルがかなの5倍超 → 韓国語残留
            if (total > 30 && ko > kana * 5) {
                return QualityValidationResult.Failure("韓国語残留の疑い (ハングル:$ko かな:$kana)")
            }
            if (total > 50 && kana < 10) {
                return QualityValidationResult.Failure("日本語化不足 (かな:$kana)")
            }
        }

        return QualityValidationResult.Success
    }

    private fun checkAverageLineLength(
        sourceText: String,
        translatedText: String
    ): QualityValidationResult {
        val srcLines = sourceText.lines().filter { it.isNotBlank() }
        val outLines = translatedText.lines().filter { it.isNotBlank() }

        if (srcLines.size < 5 || outLines.isEmpty()) return QualityValidationResult.Success

        val srcBytes = sourceText.toByteArray(Charsets.UTF_8).size
        val outBytes = translatedText.toByteArray(Charsets.UTF_8).size

        val srcAvg = srcBytes / srcLines.size
        val outAvg = outBytes / outLines.size

        // 翻訳後平均行長が原文の4倍超なら改行消失 (AVG_LINE_LEN_RATIO = 4)
        if (srcAvg > 0 && outAvg > srcAvg * 4) {
            return QualityValidationResult.Failure("平均行長異常 (原文:${srcAvg}B/行 翻訳後:${outAvg}B/行) → 改行消失疑い")
        }

        return QualityValidationResult.Success
    }

    private fun checkSizeRatio(
        sourceText: String,
        translatedText: String,
        sourceLang: SourceLanguage
    ): QualityValidationResult {
        val srcBytes = sourceText.toByteArray(Charsets.UTF_8).size
        val outBytes = translatedText.toByteArray(Charsets.UTF_8).size
        // 150B未満の極小テキスト (章タイトル・短い一文等) は比率誤差が大きいためバイパス
        if (srcBytes < 150) return QualityValidationResult.Success

        val (minRatio, maxRatio) = when (sourceLang) {
            SourceLanguage.ZH -> 102 to 200
            SourceLanguage.KO -> 102 to 150
            SourceLanguage.EN -> 105 to 200
            SourceLanguage.JA -> 100 to 200
        }

        if (outBytes * 100 < srcBytes * minRatio) {
            return QualityValidationResult.Failure("サイズ比不足 ($outBytes B / $srcBytes B = ${outBytes * 100 / srcBytes}%) → 省略疑い")
        }
        if (outBytes * 100 > srcBytes * maxRatio) {
            return QualityValidationResult.Failure("サイズ比超過 ($outBytes B / $srcBytes B = ${outBytes * 100 / srcBytes}%) → 水増し疑い")
        }

        return QualityValidationResult.Success
    }
}