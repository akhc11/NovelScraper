package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.LlmResult
import com.example.novelscraper.translation.v2.domain.isDeterministic
import com.example.novelscraper.translation.v2.domain.isQuotaLike
import com.example.novelscraper.translation.v2.infra.FileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NovelDict(
    val style: String = "カタカナ",
    val characters: Map<String, String> = emptyMap(),
    val genders: Map<String, String> = emptyMap()
)

@Serializable
data class DictBuildManifest(val version: Int = 1, val batches: Map<String, String> = emptyMap())

data class DictPrompts(
    val batch: String = "Extract person names from the novel text below and output JSON only: " +
        "{\"style\":\"katakana|kanji|hybrid\",\"characters\":{\"original\":\"Japanese\"}," +
        "\"genders\":{\"original\":\"男|女\"}}. No explanations.",
    val merge: String = "Merge the dictionary fragments below into one complete JSON dictionary " +
        "(same shape). Remove non-person nouns. Output JSON only.",
    val review: String = "Review the merged dictionary below. Remove place/organization names, " +
        "titles and common nouns. Unify notation. Output JSON only."
)

data class DictOptions(
    val model: String = "",
    val mergeModel: String = "",
    val thinkingLevel: String? = null,
    val maxFiles: Int = 100,
    val uniformSample: Boolean = true,
    val maxBatchBytes: Int = 100000,
    val maxTotalScanBytes: Int = 10000000,
    /** 既定8。エンジン経路では辞書設定（worker×同時実行数、上限30）で上書きする */
    val parallelism: Int = 8,
    val maxRetriesPerBatch: Int = 4,
    val mergeRetries: Int = 3,
    val reviewRetries: Int = 2,
    val prompts: DictPrompts = DictPrompts()
)

fun sha256Hex(text: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** 部分マージ可否。1件以上の成功があり、欠けが確定的失敗のみの場合のみ真 */
fun mergeDecision(totalBatches: Int, completedCount: Int, hasTransientFailure: Boolean): Boolean {
    return totalBatches > 0 && completedCount in 1 until totalBatches && !hasTransientFailure
}

private val dictJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun parseNovelDict(rawJson: String): NovelDict? {
    return try {
        var text = rawJson.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first != -1 && last > first) text = text.substring(first, last + 1)
        val decoded = dictJson.decodeFromString(NovelDict.serializer(), text)
        if (decoded.characters.isEmpty()) null else decoded
    } catch (_: Exception) {
        null
    }
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
 * 辞書生成stage。`call` は巡回・待機を適用済みの呼出側 binding を受け取る。
 * 失敗種別：BLOCKED/CONFIG＝確定的、QUOTA/RETRYABLE/FATAL＝一時的。
 * 技術的根拠1行：本文はファイル名のサンプリング後に1件ずつ遅延読込し、全文リストをメモリに抱えない。
 */
suspend fun generateDictionary(
    store: FileStore,
    workDirUri: String,
    fileNames: List<String>,
    readText: suspend (name: String) -> String?,
    call: suspend (model: String, prompt: String, text: String) -> LlmResult,
    options: DictOptions,
    log: (String) -> Unit = {},
    /** 待機の注入口（既定は実delay。テストは記録式fakeを渡す） */
    sleeper: suspend (Long) -> Unit = { delay(it) }
): NovelDict? = coroutineScope {
    val sampledNames = selectSampleFiles(fileNames, options.maxFiles, options.uniformSample)
    if (sampledNames.isEmpty()) {
        log("dict: no sample files")
        return@coroutineScope null
    }

    // バッチ化（合計スキャン上限付き）
    val batches = mutableListOf<String>()
    val buffer = StringBuilder()
    var bufferBytes = 0
    var scannedBytes = 0
    for (name in sampledNames) {
        if (scannedBytes >= options.maxTotalScanBytes) {
            log("dict: scan cap reached")
            break
        }
        val clean = readText(name)?.trim() ?: continue
        if (clean.isEmpty()) continue
        val bytes = utf8Bytes(clean)
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
        buffer.append(clean)
        bufferBytes += bytes
    }
    if (buffer.isNotEmpty()) batches.add(buffer.toString())
    if (batches.isEmpty()) {
        log("dict: no batches")
        return@coroutineScope null
    }
    val totalBatches = batches.size

    // manifest読込
    val manifestName = "manifest.json"
    val manifestCache = mutableMapOf<String, String>()
    runCatching {
        val doc = store.findChild(workDirUri, manifestName)
        val raw = doc?.let { store.readText(it.uri) }
        if (!raw.isNullOrBlank()) {
            val parsed = dictJson.decodeFromString(DictBuildManifest.serializer(), raw)
            manifestCache.putAll(parsed.batches)
        }
    }
    val manifestMutex = Mutex()
    suspend fun persistManifest() {
        try {
            val doc = store.findChild(workDirUri, manifestName)
                ?: store.createFile(workDirUri, manifestName, "application/json")
            if (doc != null) {
                store.writeText(
                    doc.uri,
                    dictJson.encodeToString(DictBuildManifest.serializer(), DictBuildManifest(batches = manifestCache.toMap()))
                )
            }
        } catch (e: Exception) {
            log("dict: manifest save failed (${e.message})")
        }
    }
    fun cacheFresh(batchFileName: String, batchText: String): Boolean {
        if (manifestCache.isEmpty()) return true
        val expected = manifestCache[batchFileName] ?: return true
        return expected == sha256Hex(batchText)
    }

    val deterministicFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    val transientFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    val semaphore = Semaphore(options.parallelism.coerceIn(1, 30))

    val results = batches.mapIndexed { index, batchText ->
        val batchNum = index + 1
        val batchFileName = "batch_" + String.format("%04d", batchNum) + ".json"
        async {
            semaphore.withPermit {
                val existing = store.findChild(workDirUri, batchFileName)
                val cached = existing?.let { store.readText(it.uri) }
                if (!cached.isNullOrBlank()) {
                    val parsed = parseNovelDict(cached)
                    if (parsed != null && parsed.characters.isNotEmpty()) {
                        if (cacheFresh(batchFileName, batchText)) {
                            log("dict: batch $batchNum/$totalBatches cached, skip")
                            return@withPermit cached
                        }
                        log("dict: batch $batchNum hash mismatch, refetch")
                    } else {
                        log("dict: batch $batchNum broken cache, refetch")
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
                    when (val result = call(options.model, options.prompts.batch, batchText)) {
                        is LlmResult.Success -> {
                            val doc = store.findChild(workDirUri, batchFileName)
                                ?: store.createFile(workDirUri, batchFileName, "application/json")
                            if (doc != null) store.writeText(doc.uri, result.text.trim())
                            manifestMutex.withLock {
                                manifestCache[batchFileName] = sha256Hex(batchText)
                                persistManifest()
                            }
                            log("dict: batch $batchNum/$totalBatches done")
                            return@withPermit result.text
                        }
                        is LlmResult.Failure -> {
                            if (result.failure.kind.isDeterministic()) {
                                log("dict: batch $batchNum deterministic fail, skip retries")
                                break
                            }
                            // 技術的根拠1行：FATAL（JSON崩れ等）は再送するが全体保留にはしない（他バッチで部分マージ可）。
                            if (result.failure.kind.isQuotaLike()) {
                                sawTransient = true
                            }
                            if (retry >= maxRetries) break
                            sleeper(1000L * (retry + 1))
                        }
                    }
                }
                if (sawTransient) transientFailed.add(batchNum) else deterministicFailed.add(batchNum)
                null
            }
        }
    }.awaitAll()

    val completed = results.filterNotNull()
    if (completed.size < totalBatches &&
        !mergeDecision(totalBatches, completed.size, transientFailed.isNotEmpty())
    ) {
        log("dict: incomplete (${completed.size}/$totalBatches), hold for next time")
        return@coroutineScope null
    }
    if (completed.size < totalBatches) {
        val skipped = deterministicFailed.sorted().joinToString(",")
        log("dict: partial merge (${completed.size}/$totalBatches), skipped deterministic batches [$skipped]")
    }

    // 【辞書生成の2段階設計（マージ ＆ レビューの意図的分離）】:
    // マージ（各バッチ断片の統合・粗抽出）とレビュー（地名・組織名・肩書・一般名詞の除去および表記揺れの統一）は
    // 目的と評価軸が全く異なるため、あえて2回に分けてLLMを呼び出すことで人名抽出精度を最大限に高めている。
    // （1工程にまとめると一般名詞の誤混入や表記ブレが劇的に増大するため、2工程で精度を担保する）。
    val reviewModel = options.mergeModel.ifBlank { options.model }
    val merged: NovelDict? = if (completed.size == 1) {
        parseNovelDict(completed.first())
    } else {
        val mergeInput = completed.mapIndexed { i, json -> "[part${i + 1}]\n$json" }.joinToString("\n\n")
        var parsed: NovelDict? = null
        for (retry in 0 until options.mergeRetries.coerceIn(1, 5)) {
            when (val r = call(reviewModel, options.prompts.merge, mergeInput)) {
                is LlmResult.Success -> {
                    parsed = parseNovelDict(r.text)
                    if (parsed != null) break
                }
                is LlmResult.Failure -> {
                    if (retry + 1 < options.mergeRetries) sleeper(1000L * (retry + 1))
                }
            }
        }
        parsed
    }
    if (merged == null) {
        log("dict: merge failed, hold for next time")
        return@coroutineScope null
    }

    // レビュー（失敗時はマージ結果で確定する）
    var reviewed: NovelDict = merged
    val reviewInput = dictJson.encodeToString(NovelDict.serializer(), merged)
    for (retry in 0 until options.reviewRetries.coerceIn(1, 3)) {
        when (val r = call(reviewModel, options.prompts.review, reviewInput)) {
            is LlmResult.Success -> {
                val parsed = parseNovelDict(r.text)
                if (parsed != null) {
                    reviewed = parsed
                    break
                }
            }
            is LlmResult.Failure -> {
                if (retry + 1 < options.reviewRetries) sleeper(1000L)
            }
        }
    }

    // 確定保存＋作業所掃除
    val dictDoc = store.findChild(workDirUri, "dictionary.json")
        ?: store.createFile(workDirUri, "dictionary.json", "application/json")
    if (dictDoc != null && store.writeText(
            dictDoc.uri,
            dictJson.encodeToString(NovelDict.serializer(), reviewed)
        )
    ) {
        log("dict: finalized (${reviewed.characters.size} names)")
        return@coroutineScope reviewed
    }
    log("dict: save failed, hold")
    return@coroutineScope null
}
