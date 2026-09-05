package com.example.novelscraper.translation.common

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * あらゆる海外・国内小説の生テキストに対応する万能文字コード自動判別エンジン。
 * 
 * 対応エンコーディング:
 * - Unicode: UTF-8 (BOMあり/なし), UTF-16LE, UTF-16BE
 * - 韓国語: x-windows-949 (CP949 / MS949, ハングル11,172文字完全網羅), EUC-KR
 * - 中国語: GB18030, GBK, Big5 (繁体字)
 * - 日本語: Shift_JIS (Windows-31J / CP932), EUC-JP
 * - 西欧・トルコ・ベトナム: windows-1252, windows-1254, windows-1258
 * - キリル (露・ウクライナ等): windows-1251
 * - ギリシャ語: windows-1253 / ヘブライ語: windows-1255
 * - アラビア語: windows-1256 / タイ語: TIS-620
 * - エスケープ式 (旧メール時代): ISO-2022-JP, ISO-2022-KR, ISO-2022-CN
 *
 * 対象外 (頻度表が必要な近縁対立のため将来課題。取りこぼしはU+FFFDガード側で検出):
 * - 中欧 windows-1250/ISO-8859-2 (1252との厳密な区別に頻度モデルが必要)
 * - KOI8-R/ISO-8859-5 (1251との厳密な区別に頻度モデルが必要)
 * 
 * 判定方式:
 * 1. BOM解析による決定論的判定
 * 2. ISO-2022エスケープ式の事前検出 (7ビットのためUTF-8検証より先)
 * 3. NIO による厳格な UTF-8 検証 (REPORT)
 * 4. 各レガシー候補の厳格デコード + 文字種ブロック scoring
 *    (単バイト族内の近縁対立はバイト頻度モデルで再判定。ASCII過多のCJK誤判定は排除)
 * 5. Tier2A: 全候補の厳格デコードが失敗した時のみ REPLACE寛容デコードで再判定。
 *    UTF-8寛容も候補に加え、UTF-8＋ゴミ混入のファイルをレガシーが奪わないようにする。
 *    単バイトTier1勝者にもUTF-8寛容チャレンジを行う (真の単バイト文はFFFD洪水で負けるため安全)。
 * 6. Tier2B: 韓国語救済。混入ゴミ (poison byte) でCP949厳格が脱落し他候補が勝った場合、
 *    CP949寛容デコードが3 gate (ハングル量・FFFD予算・常用音節被覆率) を満たす時のみ覆す。
 *    較正値の根拠: 陽性 (CP949実ファイル FFFD 0.003%/被覆39%、クリーン文 0%/25%)、
 *    陰性 (露阿希伯泰仏独土越日中: FFFD≧2.2%か被覆≦10%。日はFFFD 0%だが被覆0%で阻止)。
 */
object UniversalCharsetDetector {

    private fun charsetOrUtf8(vararg names: String): Charset {
        for (name in names) {
            try {
                return Charset.forName(name)
            } catch (_: Exception) {
            }
        }
        return StandardCharsets.UTF_8
    }

    // 韓国語 (俗語・NSFWハングル全11,172文字を網羅する x-windows-949 を最優先)
    private val CP949: Charset by lazy { charsetOrUtf8("x-windows-949", "MS949", "EUC-KR") }
    private val EUC_KR: Charset by lazy {
        try { Charset.forName("EUC-KR") } catch (_: Exception) { CP949 }
    }

    // 中国語 (簡体字国家標準 GB18030 / GBK および 繁体字 Big5)
    private val GB18030: Charset by lazy { charsetOrUtf8("GB18030", "GBK") }
    private val BIG5: Charset by lazy { charsetOrUtf8("Big5") }

    // 日本語 (Shift_JIS / Windows-31J および EUC-JP)
    private val SHIFT_JIS: Charset by lazy { charsetOrUtf8("Windows-31J", "Shift_JIS") }
    private val EUC_JP: Charset by lazy { charsetOrUtf8("EUC-JP") }

    private val LEGACY_CANDIDATES: List<Charset> by lazy {
        listOf(
            SHIFT_JIS, CP949, GB18030, EUC_KR, BIG5, EUC_JP,
            W1252, W1254, W1258, W1251, W1253, W1255, W1256, TIS620,
            ISO2022JP, ISO2022KR, ISO2022CN
        )
            .distinct()
            .filter { it != StandardCharsets.UTF_8 }
    }

    // 単バイト系 (JVM内蔵。欠落時はUTF-8番兵となり候補から除外される)
    private val W1252: Charset by lazy { charsetOrUtf8("windows-1252") }
    private val W1254: Charset by lazy { charsetOrUtf8("windows-1254") }
    private val W1258: Charset by lazy { charsetOrUtf8("windows-1258") }
    private val W1251: Charset by lazy { charsetOrUtf8("windows-1251") }
    private val W1253: Charset by lazy { charsetOrUtf8("windows-1253") }
    private val W1255: Charset by lazy { charsetOrUtf8("windows-1255") }
    private val W1256: Charset by lazy { charsetOrUtf8("windows-1256") }
    private val TIS620: Charset by lazy { charsetOrUtf8("TIS-620", "windows-874") }
    // エスケープ式 (旧メール・ネットニュース時代)。ESCなし平文には一切マッチしないため安全
    private val ISO2022JP: Charset by lazy { charsetOrUtf8("ISO-2022-JP") }
    private val ISO2022KR: Charset by lazy { charsetOrUtf8("ISO-2022-KR") }
    private val ISO2022CN: Charset by lazy { charsetOrUtf8("ISO-2022-CN") }

    private const val LF_BYTE: Byte = 0x0A
    private const val MIN_SAFE_SAMPLE_BYTES = 4096
    private const val MAX_TAIL_TRIM_BYTES = 4
    // 単バイト族の重み: CJK既存重みを守るため低めにし、族内識別は第2段階のバイト頻度モデルで行う
    private const val SB_BLOCK_WEIGHT = 4
    private const val STAGE2_MARGIN = 1.2
    private const val CJK_SUSPICIOUS_ASCII_RATIO = 0.8
    // Tier2B韓国語救済の gate (KDoc記載の測定で較正。不正救済には全gate同時突破が必要)
    private const val RESCUE_MIN_HANGUL = 50
    private const val RESCUE_MAX_FFFD_ABS = 8
    private const val RESCUE_MAX_FFFD_RATIO = 0.001
    private const val RESCUE_MIN_TOP_COVERAGE = 0.15
    // 韓国語自然文の高頻出音節 (助詞・語尾・代名詞・助数詞中心)。偶然の対からはほぼ出ない
    private val TOP_KOREAN_SYLLABLES: Set<Char> by lazy {
        setOf(
            '은', '는', '이', '가', '을', '를', '에', '의', '도', '로', '와', '과',
            '다', '고', '한', '하', '지', '어', '있', '없', '것', '수', '들', '만',
            '나', '너', '말', '일', '라', '마', '바', '사', '아', '자', '차', '카',
            '타', '파', '거', '서', '시'
        )
    }

    /**
     * InputStream からバイト列を読み取り、万能文字コード自動判別でデコードする。
     */
    fun readTextAutoDetect(inputStream: InputStream): String {
        val bytes = inputStream.readBytes()
        return decodeBytes(bytes)
    }

    /**
     * InputStream から先頭 sampleSizeBytes (デフォルト64KB) を読み取って文字コードを自動判別する。
     *
     * 64KB境界でマルチバイト文字が分断されると厳格デコードが全候補で失敗し
     * UTF-8へ誤フォールバックするため、直近改行 (全対象Encodingで1バイト境界保証)
     * まで切り詰めてから判定する。改行なし超長行の場合は末尾0〜4バイト削りを試す。
     */
    fun detectCharsetFromStream(inputStream: InputStream, sampleSizeBytes: Int = 65536): Charset {
        val buf = ByteArray(sampleSizeBytes)
        var totalRead = 0
        while (totalRead < sampleSizeBytes) {
            val read = inputStream.read(buf, totalRead, sampleSizeBytes - totalRead)
            if (read == -1) break
            totalRead += read
        }
        val sample = if (totalRead == sampleSizeBytes) buf else buf.copyOf(totalRead)
        // ファイル全体が収まった場合は切断がないためそのまま判定
        if (totalRead < sampleSizeBytes) return detectCharset(sample)
        val lastNewline = sample.lastIndexOf(LF_BYTE)
        if (lastNewline >= MIN_SAFE_SAMPLE_BYTES) {
            return detectCharset(sample.copyOf(lastNewline + 1))
        }
        return detectWithTailTrim(sample)
    }

    /**
     * 改行なし超長行のエッジケース用: 末尾0〜4バイト削りのうち最も確からしい判定を採用する。
     * UTF-8厳格検証が通る切り詰め位置を優先し、通らない場合は第1段階 (＋Tier2B救済・
     * 単バイト族内再判定) で比較する。第1段階が全部の切り詰め位置で全滅の時だけ
     * 第2パスでTier2A寛容再判定を切り詰め位置ごとに比較する (同スケール同士のため公平)。
     */
    private fun detectWithTailTrim(sample: ByteArray): Charset {
        for (trim in 0..MAX_TAIL_TRIM_BYTES) {
            if (isStrictUtf8(sample.copyOf(sample.size - trim))) return StandardCharsets.UTF_8
        }
        // 切り詰め位置ごとに第1段階で比較し、最良の切り詰め位置のバイト列で第2段階まで行う
        var best: Pair<Charset, Int>? = null
        var bestBytes = sample
        for (trim in 0..MAX_TAIL_TRIM_BYTES) {
            val candidate = sample.copyOf(sample.size - trim)
            val scored = stage1Best(candidate) ?: continue
            if (best == null || scored.second > best.second) {
                best = scored
                bestBytes = candidate
            }
        }
        if (best != null) return finishLegacy(bestBytes, best.first, best.second)
        var tolerantBest: Pair<Charset, Int>? = null
        var tolerantBytes = sample
        for (trim in 0..MAX_TAIL_TRIM_BYTES) {
            val candidate = sample.copyOf(sample.size - trim)
            val scored = stage2BestTolerant(candidate) ?: continue
            if (tolerantBest == null || scored.second > tolerantBest.second) {
                tolerantBest = scored
                tolerantBytes = candidate
            }
        }
        val (charset, score) = tolerantBest ?: return StandardCharsets.UTF_8
        return finishLegacy(tolerantBytes, charset, score)
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: Exception) {
        false
    }

    private fun tryDecodeStrict(charset: Charset, bytes: ByteArray): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    private fun tryDecodeReplace(charset: Charset, bytes: ByteArray): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    /**
     * ISO-2022エスケープ式の事前検出。7ビットのみで構成されるため厳格UTF-8検証より
     * 先に見る必要がある (通常文にESC(0x1B)は現れないため誤検出はほぼない)。
     */
    private fun sniffIso2022(bytes: ByteArray): Charset? {
        val limit = bytes.size.coerceAtMost(65536)
        var sawJp = false
        var sawKr = false
        var sawCn = false
        var i = 0
        while (i < limit) {
            if (bytes[i] != 0x1B.toByte()) {
                i++
                continue
            }
            if (i + 2 < limit) {
                val a = bytes[i + 1]
                val b = bytes[i + 2]
                // ESC $ B / ESC $ @ / ESC ( B / ESC ( J -> JIS (日本語)
                if ((a == 0x24.toByte() && (b == 0x42.toByte() || b == 0x40.toByte())) ||
                    (a == 0x28.toByte() && (b == 0x42.toByte() || b == 0x4A.toByte()))
                ) {
                    sawJp = true
                }
            }
            if (i + 3 < limit) {
                val a = bytes[i + 1]
                val b = bytes[i + 2]
                val c = bytes[i + 3]
                // ESC $ ) C -> KS X 1001 (韓国語)
                if (a == 0x24.toByte() && b == 0x29.toByte() && c == 0x43.toByte()) sawKr = true
                // ESC $ ) A / ESC $ ( A -> GB2312 (中国語)
                if (a == 0x24.toByte() && (b == 0x29.toByte() || b == 0x28.toByte()) && c == 0x41.toByte()) sawCn = true
            }
            i++
        }
        return when {
            sawKr -> ISO2022KR
            sawCn -> ISO2022CN
            sawJp -> ISO2022JP
            else -> null
        }
    }

    /**
     * バイト配列から最適な文字コード（Charset）を自動判別する。
     */
    fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.isEmpty()) return StandardCharsets.UTF_8

        // 1. BOM チェック (UTF-8, UTF-16LE, UTF-16BE)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return StandardCharsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return StandardCharsets.UTF_16BE
        }

        // 2. ISO-2022エスケープ式 (7ビットのためUTF-8検証より先に見る)
        sniffIso2022(bytes)?.let { return it }

        // 3. 厳格な UTF-8 デコードを試行 (不正バイトがあれば判定に進む)
        if (isStrictUtf8(bytes)) return StandardCharsets.UTF_8

        // 4. レガシーエンコーディング候補のスコアリング評価
        return selectBestLegacyCharset(bytes) ?: StandardCharsets.UTF_8
    }

    /**
     * レガシー候補から最適な文字コードを選ぶ (第1段階: 文字種ブロック scoring、
     * 第2段階: 単バイト族内の近縁対立はバイト頻度モデルで再判定)。
     * Tier2A (厳格全滅時の寛容再判定) と Tier2B (韓国語救済) を含む。
     * 確信ある判定がない場合は null。
     */
    private fun selectBestLegacyCharset(bytes: ByteArray): Charset? {
        if (bytes.isEmpty()) return null
        val (winner, winnerScore) = stage1Best(bytes)
            ?: run {
                // Tier2A: 厳格全滅時のみ寛容再判定 (UTF-8寛容を含む)
                val (tolerant, tolerantScore) = stage2BestTolerant(bytes) ?: return null
                return finishLegacy(bytes, tolerant, tolerantScore)
            }
        return finishLegacy(bytes, winner, winnerScore)
    }

    /**
     * Tier1勝者の後処理を一本化する。Tier2A-2 (単バイト勝者へのUTF-8寛容チャレンジ)、
     * Tier2B (韓国語救済) の順に試し、どちらも不発なら従来の確定処理を行う。
     */
    private fun finishLegacy(bytes: ByteArray, winner: Charset, winnerScore: Int): Charset {
        if (isSingleByteCharset(winner)) {
            utf8Challenge(bytes, winnerScore)?.let { return it }
        }
        if (!isKoreanCharset(winner)) {
            rescueKorean(bytes)?.let { return it }
        }
        if (!isSingleByteCharset(winner)) return winner
        return resolveSingleByteByByteModel(bytes, winner)
    }

    /** 第1段階: 全候補を厳格デコード＋文字種ブロック scoring して最良を返す。 */
    private fun stage1Best(bytes: ByteArray): Pair<Charset, Int>? {
        var best: Pair<Charset, Int>? = null
        for (charset in LEGACY_CANDIDATES) {
            val text = tryDecodeStrict(charset, bytes) ?: continue
            val score = calculateNaturalnessScore(text, charset)
            if (best == null || score > best.second) best = charset to score
        }
        return best?.takeIf { it.second > 0 }
    }

    /**
     * Tier2A: 全候補の厳格デコードが失敗した時だけ REPLACE寛容デコードで再判定する。
     * 混入ゴミで正解候補が脱落する事故の救済用。FFFDは既存スコアの制御文字ペナルティ
     * (1個-100、全体2%超で-100000) で差し引かれるため追加定数なしで正解が勝つ。
     * UTF-8寛容も候補に加え、UTF-8＋ゴミ混入のファイルをレガシーが奪わないようにする
     * (CP949等のバイト列に対するUTF-8寛容はFFFD洪水で自然に負ける)。
     */
    private fun stage2BestTolerant(bytes: ByteArray): Pair<Charset, Int>? {
        var best: Pair<Charset, Int>? = null
        val utf8Text = tryDecodeReplace(StandardCharsets.UTF_8, bytes)
        if (utf8Text != null) {
            val score = calculateNaturalnessScore(utf8Text, StandardCharsets.UTF_8)
            if (score > 0) best = StandardCharsets.UTF_8 to score
        }
        for (charset in LEGACY_CANDIDATES) {
            val text = tryDecodeReplace(charset, bytes) ?: continue
            val score = calculateNaturalnessScore(text, charset)
            if (best == null || score > best.second) best = charset to score
        }
        return best?.takeIf { it.second > 0 }
    }

    /**
     * Tier2A-2: 単バイトTier1勝者へのUTF-8寛容チャレンジ。UTF-8＋ゴミ混入ファイルを救済する。
     * 真の単バイト文のUTF-8寛容デコードはFFFD洪水 (制御ペナルティ＋2%トリップワイヤ) で
     * 勝てず、同一ASCII基盤では latin×4＋FFFD×100 の差で単バイトが必ず残るため、
     * スコア比較だけで安全 (UTF-8らしい構造＝CJK/かなの gain がある時だけ覆る)。
     */
    private fun utf8Challenge(bytes: ByteArray, winnerScore: Int): Charset? {
        val text = tryDecodeReplace(StandardCharsets.UTF_8, bytes) ?: return null
        val score = calculateNaturalnessScore(text, StandardCharsets.UTF_8)
        if (score > 0 && score > winnerScore) return StandardCharsets.UTF_8
        return null
    }

    private fun isKoreanCharset(charset: Charset): Boolean {
        val name = charset.name().uppercase()
        return name.contains("949") || name.contains("EUC-KR")
    }

    /**
     * Tier2B: 韓国語救済。poison byte でCP949厳格が脱落し非韓国語候補が勝った場合、
     * CP949寛容デコードが3 gate (ハングル量・FFFD予算・常用音節被覆率) を全て満たす時のみ覆す。
     * スコア比較はしない (重み25対4では盗みも救済も区別できないため、言語 gate で判定する)。
     */
    private fun rescueKorean(bytes: ByteArray): Charset? {
        val text = tryDecodeReplace(CP949, bytes) ?: return null
        val sampleLen = text.length
        if (sampleLen == 0) return null
        var fffd = 0
        var hangul = 0
        var top = 0
        for (i in 0 until sampleLen) {
            val ch = text[i]
            if (ch == '�') {
                fffd++
            } else if (ch in '가'..'힣') {
                hangul++
                if (ch in TOP_KOREAN_SYLLABLES) top++
            }
        }
        if (hangul < RESCUE_MIN_HANGUL) return null
        if (fffd > maxOf(RESCUE_MAX_FFFD_ABS.toDouble(), sampleLen * RESCUE_MAX_FFFD_RATIO).toInt()) return null
        if (top.toDouble() / hangul < RESCUE_MIN_TOP_COVERAGE) return null
        return CP949
    }

    private fun isSingleByteCharset(charset: Charset): Boolean {
        val name = charset.name().uppercase()
        return name.contains("1252") || name.contains("1254") || name.contains("1258") ||
            name.contains("1251") || name.contains("1253") || name.contains("1255") ||
            name.contains("1256") || name.contains("TIS")
    }

    /**
     * 第2段階: 単バイト族内では同一バイト位置に別文字が載るため文字種計数では
     * 同点になりやすい。各言語のバイト出現頻度モデル (Zipf側が高得点) で再判定する。
     * 僅差の場合は第1段階の判定を維持する。
     */
    private fun resolveSingleByteByByteModel(bytes: ByteArray, fallback: Charset): Charset {
        var first: Charset? = null
        var firstAvg = 0.0
        var secondAvg = 0.0
        for (charset in LEGACY_CANDIDATES) {
            if (!isSingleByteCharset(charset)) continue
            val table = byteTableFor(charset) ?: continue
            var sum = 0L
            for (b in bytes) sum += table[b.toInt() and 0xFF]
            val avg = sum.toDouble() / bytes.size
            if (first == null || avg > firstAvg) {
                secondAvg = firstAvg
                firstAvg = avg
                first = charset
            } else if (avg > secondAvg) {
                secondAvg = avg
            }
        }
        if (first != null && firstAvg > 0 && firstAvg >= secondAvg * STAGE2_MARGIN) return first
        return fallback
    }

    private fun byteTableFor(charset: Charset): IntArray? = when (charset) {
        W1252 -> LANG_WESTERN
        W1254 -> LANG_TURKISH
        W1258 -> LANG_VIET
        W1251 -> LANG_CYRILLIC
        W1253 -> LANG_GREEK
        W1255 -> LANG_HEBREW
        W1256 -> LANG_ARABIC
        TIS620 -> LANG_THAI
        else -> null
    }

    private fun byteTable(vararg pairs: Pair<Int, Int>): IntArray {
        val table = IntArray(256)
        for ((byte, weight) in pairs) table[byte] = weight
        return table
    }

    // 各言語の高頻度バイト (言語内分布を合計1000に正規化。分布の近さで競わせる)
    private val LANG_CYRILLIC: IntArray by lazy {
        byteTable(
            0xEE to 120, 0xE5 to 112, 0xF0 to 106, 0xEA to 70, 0xE8 to 63, 0xEB to 63,
            0xE2 to 49, 0xED to 41, 0xF2 to 35, 0xF1 to 35, 0xE0 to 35, 0xE4 to 35,
            0xEC to 27, 0xF8 to 27, 0xFB to 20, 0xE9 to 20, 0xEF to 13, 0xFF to 13,
            0xE7 to 13, 0xE3 to 13, 0xF6 to 13, 0xFC to 13, 0xCF to 6, 0xDD to 6
        )
    }
    private val LANG_WESTERN: IntArray by lazy {
        // ‘’“”–—…«»€は西欧文に特徴的 (他言語の通常文にはほぼ出ない)
        byteTable(
            0xFC to 199, 0xDF to 129, 0xE9 to 101, 0xF3 to 97, 0xF2 to 64, 0xF1 to 64,
            0xE8 to 32, 0xE0 to 32, 0xEC to 32, 0xF9 to 32, 0xC4 to 32, 0xD6 to 32,
            0xF6 to 32, 0xE7 to 23, 0xEF to 23, 0x9C to 23, 0xEA to 23, 0xF4 to 23,
            0x91 to 30, 0x92 to 60, 0x93 to 40, 0x94 to 40, 0x96 to 15, 0x97 to 15,
            0x85 to 15, 0xAB to 15, 0xBB to 15, 0x80 to 5
        )
    }
    private val LANG_TURKISH: IntArray by lazy {
        // üはドイツ語と共有のため低重み。トルコ語の識別は ı/ş/ğ/İ が担う (他言語にほぼ出ない)
        byteTable(
            0xFC to 30, 0xFD to 300, 0xE7 to 126, 0xFE to 150, 0xDD to 80, 0xF0 to 40
        )
    }
    private val LANG_VIET: IntArray by lazy {
        byteTable(
            0xCC to 367, 0xFD to 202, 0xEC to 80, 0xF5 to 80, 0xE2 to 80, 0xF4 to 37,
            0xD2 to 37, 0xEA to 37, 0xF0 to 37, 0xF2 to 37
        )
    }
    private val LANG_ARABIC: IntArray by lazy {
        byteTable(
            0xC7 to 163, 0xE1 to 163, 0xD1 to 100, 0xC8 to 81, 0xED to 72, 0xDA to 44,
            0xE3 to 35, 0xD5 to 35, 0xCA to 35, 0xCD to 26, 0xDF to 26, 0xE5 to 17,
            0xE4 to 17, 0xCC to 17, 0xC9 to 17, 0xD3 to 17, 0xD8 to 17, 0xE6 to 17,
            0xEC to 17, 0xD0 to 8, 0xDB to 8, 0xCE to 8, 0xD2 to 8, 0xC1 to 8
        )
    }
    private val LANG_GREEK: IntArray by lazy {
        byteTable(
            0xEF to 98, 0xE9 to 81, 0xE1 to 81, 0xF4 to 81, 0xE5 to 64, 0xF3 to 64,
            0xEA to 64, 0xED to 64, 0xEB to 48, 0xFC to 40, 0xEC to 32, 0xE7 to 32,
            0xDC to 32, 0xF5 to 23, 0xDF to 23, 0xE3 to 23, 0xF2 to 23, 0xDD to 16,
            0xE4 to 16, 0xF0 to 16, 0xE8 to 16, 0xC3 to 7, 0xC1 to 7, 0xF7 to 7
        )
    }
    private val LANG_HEBREW: IntArray by lazy {
        byteTable(
            0xE4 to 98, 0xE1 to 98, 0xEC to 88, 0xE5 to 88, 0xE9 to 88, 0xF2 to 66,
            0xE3 to 66, 0xF8 to 66, 0xF7 to 44, 0xF9 to 32, 0xE2 to 32, 0xEE to 32,
            0xED to 22, 0xE6 to 22, 0xE8 to 22, 0xF1 to 22, 0xFA to 22, 0xEA to 22,
            0xEF to 22, 0xE0 to 22, 0xF0 to 10, 0xEB to 10
        )
    }
    private val LANG_THAI: IntArray by lazy {
        byteTable(
            0xD2 to 96, 0xD1 to 72, 0xC7 to 64, 0xB9 to 64, 0xC3 to 64, 0xCA to 47,
            0xCD to 47, 0xBA to 47, 0xC5 to 40, 0xA1 to 40, 0xB7 to 31, 0xE0 to 31,
            0xA7 to 31, 0xB4 to 23, 0xE8 to 23, 0xCB to 23, 0xD0 to 23, 0xBB to 23,
            0xD5 to 15, 0xAA to 15, 0xA4 to 15, 0xA2 to 15, 0xB5 to 15, 0xE2 to 7
        )
    }

    /**
     * バイト配列から最適な文字コードを自動判別して文字列に変換する。
     */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. BOM チェック (UTF-8, UTF-16LE, UTF-16BE)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        // 2. ISO-2022エスケープ式 (7ビットのためUTF-8検証より先に見る)
        sniffIso2022(bytes)?.let { charset ->
            return tryDecodeStrict(charset, bytes) ?: fallbackReplaceDecode(bytes)
        }

        // 3. 厳格な UTF-8 デコードを試行 (不正バイトがあれば即座に例外をスロー)
        try {
            val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = utf8Decoder.decode(ByteBuffer.wrap(bytes))
            return charBuffer.toString()
        } catch (_: CharacterCodingException) {
            // UTF-8 ではない -> レガシーエンコーディング候補のスコアリングへ移行
        } catch (_: Exception) {}

        // 4. レガシーエンコーディング候補のスコアリング評価 (Tier1＋Tier2A＋Tier2B)
        val bestCharset = selectBestLegacyCharset(bytes)
        if (bestCharset != null) {
            // Tier2AでUTF-8等が勝った場合の厳格失敗に備え、同一charsetの寛容デコードを挟む
            // (従来経路は厳格が必ず通るため挙動不変)
            return tryDecodeStrict(bestCharset, bytes)
                ?: tryDecodeReplace(bestCharset, bytes)
                ?: fallbackReplaceDecode(bytes)
        }

        // 5. 最終フォールバック (置換文字を許容してUTF-8またはCP949でデコード)
        return fallbackReplaceDecode(bytes)
    }

    private fun fallbackReplaceDecode(bytes: ByteArray): String {
        return try {
            CP949.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    /**
     * デコードされた文字列の自然さ (言語固有の文字比率と制御文字ペナルティ) をスコアリング。
     * エンコーディングと文字種の排他性（例: CP949でひらがな、Shift_JISでハングル、GB18030でハングル等は不正化け）を厳格判定。
     */
    private fun calculateNaturalnessScore(text: String, charset: Charset): Int {
        if (text.isEmpty()) return 0
        var score = 0
        var controlCharCount = 0
        var hiraganaKatakanaCount = 0
        var hangulCount = 0
        var cjkCount = 0
        var asciiCount = 0
        var cyrillicCount = 0
        var arabicCount = 0
        var hebrewCount = 0
        var greekCount = 0
        var thaiCount = 0
        var latinExtCount = 0
        var combiningCount = 0
        val sampleLen = text.length.coerceAtMost(50000)

        for (i in 0 until sampleLen) {
            val ch = text[i]
            when {
                // ひらがな・カタカナ -> 日本語固有
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> {
                    hiraganaKatakanaCount++
                }
                // ハングル (完成型・拡張完成型・字母) -> 韓国語固有
                ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F' -> {
                    hangulCount++
                }
                // CJK統合漢字 -> 中国語・日本語・韓国語
                ch in '\u4E00'..'\u9FFF' -> {
                    cjkCount++
                }
                // キリル文字 (露・ウクライナ等)
                ch in '\u0400'..'\u04FF' -> {
                    cyrillicCount++
                }
                // アラビア文字
                ch in '\u0600'..'\u06FF' -> {
                    arabicCount++
                }
                // ヘブライ文字
                ch in '\u0590'..'\u05FF' -> {
                    hebrewCount++
                }
                // ギリシャ文字
                ch in '\u0370'..'\u03FF' -> {
                    greekCount++
                }
                // タイ文字
                ch in '\u0E00'..'\u0E7F' -> {
                    thaiCount++
                }
                // ラテン拡張・ベトナム語拡張
                ch in '\u00A0'..'\u024F' || ch in '\u1E00'..'\u1EFF' -> {
                    latinExtCount++
                }
                // 結合分音記号 -> windows-1258ベトナム語の声調記号 (他候補は出力しない)
                ch in '\u0300'..'\u036F' -> {
                    combiningCount++
                }
                // 一般的な英数字・記号・改行・スペース
                ch in ' '..'~' || ch == '\n' || ch == '\r' || ch == '\t' -> {
                    asciiCount++
                }
                // 制御文字や未定義・私用領域 -> 強力なペナルティ
                ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t' -> {
                    controlCharCount++
                }
                ch in '\uE000'..'\uF8FF' || ch == '\uFFFD' -> {
                    controlCharCount++
                }
            }
        }

        // 制御文字が多すぎる場合は明らかな文字化け
        if (controlCharCount > sampleLen * 0.02) {
            return -100000
        }

        val csName = charset.name().uppercase()
        val isJapanese = csName.contains("SJIS") || csName.contains("SHIFT_JIS") || csName.contains("932") || csName.contains("31J") || csName.contains("EUC-JP")
        val isKorean = csName.contains("949") || csName.contains("EUC-KR")
        val isChinese = csName.contains("GB") || csName.contains("BIG5")

        // 言語固有文字の不整合判定（別言語文字コードでデコードしたことによる化けを完全排除）
        if (isKorean && hiraganaKatakanaCount > 0) {
            return -100000
        }
        if (isChinese && (hangulCount > 0 || hiraganaKatakanaCount > 0)) {
            return -100000
        }
        if (isJapanese && hangulCount > 0) {
            return -100000
        }
        // 漢字圏デコーダがアラビア・ヘブライ・タイ文字を含むのは誤判定 (各規格に存在しない)
        if ((isJapanese || isKorean || isChinese) && (arabicCount > 0 || hebrewCount > 0 || thaiCount > 0)) {
            return -100000
        }
        // 漢字圏デコーダでASCIIが8割超は西欧文の誤デコード (自然な小説文では起こらない)
        if ((isJapanese || isKorean || isChinese) &&
            (cjkCount + hangulCount + hiraganaKatakanaCount > 0) &&
            asciiCount.toDouble() / sampleLen > CJK_SUSPICIOUS_ASCII_RATIO
        ) {
            return -100000
        }

        // 正当な文字へのスコア加算
        score += hiraganaKatakanaCount * 100
        score += hangulCount * 25
        score += cjkCount * 20
        // 単バイト族はCJK重みを守るため低めにする。族内の近縁対立は第2段階のバイト頻度モデルで解く
        score += cyrillicCount * SB_BLOCK_WEIGHT
        score += arabicCount * SB_BLOCK_WEIGHT
        score += hebrewCount * SB_BLOCK_WEIGHT
        score += greekCount * SB_BLOCK_WEIGHT
        score += thaiCount * SB_BLOCK_WEIGHT
        score += latinExtCount * SB_BLOCK_WEIGHT
        score += combiningCount * SB_BLOCK_WEIGHT
        score += asciiCount * 1
        score -= controlCharCount * 100

        return score
    }
}