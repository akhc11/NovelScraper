package com.example.novelscraper.translation.common.ingest

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 取込 (Ingest)。バイト列→検証済みテキストの唯一入口。
 *
 * 実行順序 (宣言路→署名路→均一仮説採点→確信度ゲート):
 * 1. 宣言路: ユーザー・フォルダ指定があれば寛容復号＋FFFD予算で検証する
 * 2. 署名路: BOM は決定論的。ESC 式は厳格に通れば確定、通らなければ隔離
 * 3. 厳格 UTF-8 (現代文の最短路)
 * 4. 均一仮説採点 (HypothesisScorer.decide)。僅差・全滅は隔離
 *
 * fail-closed: 成功以外はテキストを返さない。呼び元は Quarantined を
 * 理由付きスキップ、Failed を理由付き失敗として扱う (無言で落とさない)。
 */
object TextIngest {

    const val MAX_INGEST_BYTES: Int = 64 * 1024 * 1024

    fun ingest(stream: InputStream, declared: DeclaredEncoding? = null): IngestResult {
        return try {
            val bytes = stream.readBytesCapped(MAX_INGEST_BYTES + 1)
            if (bytes.size > MAX_INGEST_BYTES) {
                return IngestResult.Quarantined(
                    QuarantineReason.TOO_LARGE,
                    "size > $MAX_INGEST_BYTES bytes"
                )
            }
            ingest(bytes, declared)
        } catch (e: IOException) {
            IngestResult.Failed(e)
        } catch (e: Exception) {
            IngestResult.Failed(IOException("ingest failed", e))
        }
    }

    fun ingest(bytes: ByteArray, declared: DeclaredEncoding? = null): IngestResult {
        if (bytes.isEmpty()) {
            return IngestResult.Success(
                "",
                Provenance(
                    StandardCharsets.UTF_8, HypothesisId.UTF8, 100,
                    DetectionMethod.EMPTY, 0
                )
            )
        }

        // 1. 宣言路
        if (declared != null) {
            return ingestDeclared(bytes, declared)
        }

        // 2a. BOM 署名路 (決定論的)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            val text = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
            return IngestResult.Success(
                text,
                Provenance(
                    StandardCharsets.UTF_8, HypothesisId.UTF8, 100,
                    DetectionMethod.BOM, text.count { it == '�' }
                )
            )
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            return IngestResult.Success(
                text,
                Provenance(
                    StandardCharsets.UTF_16LE, HypothesisId.UTF8, 100,
                    DetectionMethod.BOM, 0
                )
            )
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            return IngestResult.Success(
                text,
                Provenance(
                    StandardCharsets.UTF_16BE, HypothesisId.UTF8, 100,
                    DetectionMethod.BOM, 0
                )
            )
        }

        // 2b. ESC 式署名路 (7ビットのため UTF-8 検証より先)。厳格に通らなければ隔離
        sniffIso2022(bytes)?.let { id ->
            val charset = HypothesisScorer.resolveCharset(id) ?: StandardCharsets.UTF_8
            val text = HypothesisScorer.strictDecode(charset, bytes)
            if (text != null) {
                return IngestResult.Success(
                    text,
                    Provenance(charset, id, 95, DetectionMethod.ESC_2022, 0)
                )
            }
            return IngestResult.Quarantined(
                QuarantineReason.CORRUPT_ESCAPE,
                "ESC signature for $id but strict decode failed"
            )
        }

        // 3. 厳格 UTF-8
        val strictUtf8 = HypothesisScorer.strictDecode(StandardCharsets.UTF_8, bytes)
        if (strictUtf8 != null) {
            return IngestResult.Success(
                strictUtf8,
                Provenance(
                    StandardCharsets.UTF_8, HypothesisId.UTF8, 100,
                    DetectionMethod.STRICT_UTF8, 0
                )
            )
        }

        // 4. 均一仮説採点
        return when (val decision = HypothesisScorer.decide(bytes)) {
            is HypothesisScorer.Decision.Adopt -> {
                val w = decision.winner
                val method = if (w.id == HypothesisId.UTF8) {
                    DetectionMethod.HYPOTHESIS_TOLERANT
                } else {
                    DetectionMethod.HYPOTHESIS
                }
                IngestResult.Success(
                    w.text,
                    Provenance(
                        w.charset, w.id, confidenceOf(decision.margin),
                        method, w.counts.fffd
                    )
                )
            }
            is HypothesisScorer.Decision.Reject ->
                IngestResult.Quarantined(decision.reason, decision.evidence)
        }
    }

    /**
     * 宣言路。指定 charset で寛容復号し、FFFD 予算＋宣言言語の証拠の
     * 両方を満たす時のみ採用する (予算だけでは GB 系のような寛容な
     * charset の誤読を通すため)。UTF-8 宣言は厳格通過を優先する。
     */
    private fun ingestDeclared(bytes: ByteArray, declared: DeclaredEncoding): IngestResult {
        val id = when (declared) {
            DeclaredEncoding.CP949 -> HypothesisId.CP949
            DeclaredEncoding.GB18030 -> HypothesisId.GB18030
            DeclaredEncoding.BIG5 -> HypothesisId.BIG5
            DeclaredEncoding.SJIS -> HypothesisId.SJIS
            DeclaredEncoding.EUC_JP -> HypothesisId.EUC_JP
            DeclaredEncoding.UTF8 -> HypothesisId.UTF8
            DeclaredEncoding.W1252 -> HypothesisId.W1252
        }
        val charset = HypothesisScorer.resolveCharset(id) ?: StandardCharsets.UTF_8
        if (id == HypothesisId.UTF8) {
            val strict = HypothesisScorer.strictDecode(charset, bytes)
            if (strict != null) {
                return IngestResult.Success(
                    strict,
                    Provenance(charset, id, 95, DetectionMethod.DECLARED, 0)
                )
            }
        }
        val text = HypothesisScorer.tolerantDecode(charset, bytes)
            ?: return IngestResult.Quarantined(
                QuarantineReason.DECLARED_MISMATCH,
                "declared $declared undecodable"
            )
        val fffd = text.count { it == '�' }
        val budget = maxOf(
            HypothesisScorer.TOLERANT_FFFD_ABS.toDouble(),
            text.length * HypothesisScorer.TOLERANT_FFFD_RATIO
        ).toInt()
        if (fffd > budget) {
            return IngestResult.Quarantined(
                QuarantineReason.DECLARED_MISMATCH,
                "declared $declared fffd=$fffd/${text.length} over budget=$budget"
            )
        }
        val counts = HypothesisScorer.countScripts(text)
        val evidenced = when (id) {
            HypothesisId.CP949 -> HypothesisScorer.koreanEvidence(text, counts)
            HypothesisId.GB18030, HypothesisId.BIG5 -> HypothesisScorer.chineseEvidence(text, counts)
            HypothesisId.SJIS, HypothesisId.EUC_JP -> HypothesisScorer.japaneseEvidence(text, counts)
            HypothesisId.W1252 -> HypothesisScorer.latinEvidence(counts, text.length)
            HypothesisId.UTF8 -> HypothesisScorer.koreanEvidence(text, counts) ||
                HypothesisScorer.chineseEvidence(text, counts) ||
                HypothesisScorer.japaneseEvidence(text, counts) ||
                HypothesisScorer.englishEvidence(text, counts)
            else -> false
        }
        if (!evidenced) {
            return IngestResult.Quarantined(
                QuarantineReason.DECLARED_MISMATCH,
                "declared $declared without $id language evidence"
            )
        }
        return IngestResult.Success(
            text,
            Provenance(charset, id, 95, DetectionMethod.DECLARED, fffd)
        )
    }

    private fun confidenceOf(margin: Double): Int = when {
        margin.isInfinite() -> 90
        margin >= 3.0 -> 95
        margin >= 2.0 -> 85
        else -> 75
    }

    /** ESC $ B/@、ESC ( B/J → JP。ESC $ ) C → KR。ESC $ ) A / ESC $ ( A → CN。 */
    fun sniffIso2022(bytes: ByteArray): HypothesisId? {
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
                if (a == 0x24.toByte() && b == 0x29.toByte() && c == 0x43.toByte()) sawKr = true
                if (a == 0x24.toByte() && (b == 0x29.toByte() || b == 0x28.toByte()) && c == 0x41.toByte()) sawCn = true
            }
            i++
        }
        return when {
            sawKr -> HypothesisId.I2022KR
            sawCn -> HypothesisId.I2022CN
            sawJp -> HypothesisId.I2022JP
            else -> null
        }
    }

    private fun InputStream.readBytesCapped(cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(8192)
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = this.read(buf, 0, buf.size.coerceAtMost(cap - total))
            if (read == -1) break
            out.write(buf, 0, read)
            total += read
            if (total >= cap) break
        }
        return out.toByteArray()
    }
}
