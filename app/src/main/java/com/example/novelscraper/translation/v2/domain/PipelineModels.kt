package com.example.novelscraper.translation.v2.domain

import com.example.novelscraper.translation.v2.pipeline.SourceLang

/**
 * 翻訳パイプラインの最小実行単位（各パートまたは章ファイル）。
 * ディスクのファイル走査結果をイミュータブルに保持し、各ステージに渡す。
 */
data class WorkUnit(
    val index: Int,
    val name: String,
    val uri: String,
    val length: Long = 0L,
    val isCompleted: Boolean = false
)

/**
 * パイプラインを流れる確定コンテキスト。
 * ステージ間で暗黙的なディスク探索を行わず、型安全にメタデータを引き渡す。
 */
data class NovelPipelineContext(
    val novelName: String,
    val workingFolderUri: String,
    val sourceLang: SourceLang,
    val sampleText: String = "",
    val declaredEncoding: V2DeclaredEncoding? = null
)

/**
 * 物理分割ステージ（PartitionStage）の結果。
 */
data class PartitionOutcome(
    val workingFolderUri: String,
    val novelName: String,
    val sampleText: String,
    val units: List<WorkUnit>,
    val skippedFiles: Set<String> = emptySet()
)

/**
 * パイプラインが処理する単一小説のターゲット情報。
 * 生テキストから物理分割されたサブフォルダ、または既存の小説フォルダを表す。
 */
data class NovelTarget(
    val novelName: String,
    val folderUri: String,
    val isPreSplit: Boolean = false,
    val files: List<com.example.novelscraper.translation.v2.infra.VDoc> = emptyList(),
    val sampleText: String = ""
)

/**
 * フォルダ全体のターゲット抽出・物理分割の結果。
 */
data class PartitionBatchResult(
    val targets: List<NovelTarget>,
    val skippedFiles: Set<String> = emptySet()
)

/**
 * 辞書ステージ（DictionaryStage）の解決結果。
 * 辞書あり・辞書なし（無効化時を含む）は Ready（後者は dict=null）、生成失敗など翻訳中断を要する場合は Aborted を返す。
 */
sealed class DictResolveResult {
    data class Ready(val dict: com.example.novelscraper.translation.v2.pipeline.NovelDict?) : DictResolveResult()
    data class Aborted(val reason: String) : DictResolveResult()
}