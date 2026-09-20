package com.example.novelscraper.translation.v2.pipeline

import java.security.MessageDigest

/**
 * 構造化プロンプト組立の型付き部品。順序は [buildSpec] が固定する。
 * 技術的根拠1行：順序をリスト型で固定し、indexOf/replace の位置推論を構造から消す。
 */
sealed interface PromptBlock {
    data class ContextTranslated(val tail: String) : PromptBlock
    data class ContextSource(val tail: String) : PromptBlock
    data class TermFix(val annotation: TermAnnotation) : PromptBlock
    data class Glossary(val terms: Map<String, String>) : PromptBlock
    data class Memo(val text: String) : PromptBlock
    data class Batch(val format: String) : PromptBlock
    data object CompletionMarker : PromptBlock
}

/**
 * 描画単位。headText は解決済み基底文（未解決 Map 引きはしない）。
 * headNum -1 は固定文（推敲等）で、頭差し替えの対象外。
 * 技術的根拠1行：欠番の沈黙フォールバックを温存しないため、head は生成時点で確定させる。
 */
data class PromptSpec(
    val headNum: Int,
    val headText: String,
    val blocks: List<PromptBlock>
)

/**
 * Spec 組立の唯一の入口。新規呼出しはここに寄せ、旧引数列への追加はしない。
 * 技術的根拠1行：文脈の択一・空表時の slot 消去を生成時に確定させ、描画を純粋に保つ。
 */
fun buildSpec(
    headNum: Int,
    headText: String,
    previousTranslatedTail: String? = null,
    previousSourceTail: String? = null,
    termAnnotation: TermAnnotation? = null,
    glossary: Map<String, String>? = null,
    batchFormat: String? = null,
    profileMemo: String? = null,
    enableCompletionMarker: Boolean = true
): PromptSpec {
    val blocks = mutableListOf<PromptBlock>()
    if (!previousTranslatedTail.isNullOrBlank()) {
        blocks.add(PromptBlock.ContextTranslated(previousTranslatedTail))
    }
    val effectiveSourceTail = if (previousTranslatedTail.isNullOrBlank()) previousSourceTail else null
    if (!effectiveSourceTail.isNullOrBlank()) {
        blocks.add(PromptBlock.ContextSource(effectiveSourceTail))
    }
    if (termAnnotation != null) {
        blocks.add(PromptBlock.TermFix(termAnnotation))
    }
    // 空表時も slot 消去を再現するため常時同梱する。
    blocks.add(PromptBlock.Glossary(glossary ?: emptyMap()))
    if (batchFormat != null) {
        blocks.add(PromptBlock.Batch(batchFormat))
    }
    if (!profileMemo.isNullOrBlank()) {
        blocks.add(PromptBlock.Memo(profileMemo.trim()))
    }
    if (enableCompletionMarker) {
        blocks.add(PromptBlock.CompletionMarker)
    }
    return PromptSpec(headNum, headText, blocks)
}

/**
 * 決定論・純粋な描画。現行 buildSystemPrompt とバイト同一になるよう順序を再現する。
 * 技術的根拠1行：描画順を blocks 順に固定し、文字列の prefix 照合・空行推論を介在させない。
 */
fun assemblePrompt(spec: PromptSpec): String {
    val sb = StringBuilder(spec.headText)
    for (block in spec.blocks) {
        when (block) {
            is PromptBlock.ContextTranslated -> {
                sb.append("\n\n=== PREVIOUS CONTEXT (maintain consistency — do NOT translate or repeat this) ===\n")
                sb.append("...").append(block.tail).append("\n")
                sb.append("================================================================================\n")
            }
            is PromptBlock.ContextSource -> {
                sb.append("\n\n=== PREVIOUS TEXT (context only — do NOT translate or repeat this) ===\n")
                sb.append("...").append(block.tail).append("\n")
                sb.append("================================================================\n")
            }
            is PromptBlock.TermFix -> {
                sb.append("\n\n[確定訳語]\n※本文中の ${block.annotation.open}…${block.annotation.close} 内は確定訳語。必ず訳文中に残し、そのまま使うこと。削除・省略・言い換え・修正は厳禁。注釈付きの語を落とさないこと。\n")
            }
            is PromptBlock.Glossary -> {
                if (block.terms.isNotEmpty()) {
                    val rendered = buildGlossaryBlock(block.terms)
                    val at = sb.indexOf(GLOSSARY_SLOT)
                    if (at >= 0) {
                        sb.replace(at, at + GLOSSARY_SLOT.length, rendered)
                    } else {
                        sb.append("\n\n")
                        sb.append(rendered)
                        sb.append("\n")
                    }
                } else {
                    var at = sb.indexOf(GLOSSARY_SLOT)
                    while (at >= 0) {
                        sb.replace(at, at + GLOSSARY_SLOT.length, "")
                        at = sb.indexOf(GLOSSARY_SLOT)
                    }
                }
            }
            is PromptBlock.Batch -> {
                sb.append(block.format)
            }
            is PromptBlock.Memo -> {
                sb.append("\n\n")
                sb.append(block.text)
                sb.append("\n")
            }
            is PromptBlock.CompletionMarker -> {
                sb.append("\n\nNOTE: The text to translate below ends with the marker ")
                sb.append(COMPLETION_MARKER)
                sb.append(" appended after the actual source content.\n")
                sb.append("THIS MARKER IS A STRUCTURAL DELIMITER, NOT TEXT TO TRANSLATE.\n")
                sb.append("You MUST copy it into your output exactly as written, as the very last line, immediately after your translation.")
            }
        }
    }
    return sb.toString()
}

/**
 * 基底文の解決。欠番は fail-fast し、沈黙の先頭値フォールバックをしない。
 * 技術的根拠1行：未知番号の黙殺は無言劣化のため、生成境界で即失敗させる。
 */
fun requireHeadText(basePrompts: Map<Int, String>, promptNum: Int): String =
    basePrompts[promptNum] ?: throw IllegalArgumentException("unknown prompt number: $promptNum")

/**
 * Spec 全体の版ハッシュ（順序含む）。辞書用 promptsHash とは別 key で運用する。
 * 技術的根拠1行：順序違いを同一版とみなさないため、描画順にハッシュ化する。
 */
fun promptSpecHash(spec: PromptSpec): String {
    val md = MessageDigest.getInstance("SHA-256")
    fun feed(s: String) = md.update(s.toByteArray(Charsets.UTF_8))
    feed(spec.headNum.toString())
    feed("\u0000")
    feed(spec.headText)
    for (block in spec.blocks) {
        feed("\u0000")
        when (block) {
            is PromptBlock.ContextTranslated -> {
                feed("ctxT:")
                feed(block.tail)
            }
            is PromptBlock.ContextSource -> {
                feed("ctxS:")
                feed(block.tail)
            }
            is PromptBlock.TermFix -> {
                feed("fix:")
                feed(block.annotation.open)
                feed(block.annotation.close)
            }
            is PromptBlock.Glossary -> {
                feed("glossary:")
                for ((k, v) in block.terms) {
                    feed(k)
                    feed("=")
                    feed(v)
                    feed(";")
                }
            }
            is PromptBlock.Memo -> {
                feed("memo:")
                feed(block.text)
            }
            is PromptBlock.Batch -> {
                feed("batch:")
                feed(block.format)
            }
            is PromptBlock.CompletionMarker -> feed("marker")
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
