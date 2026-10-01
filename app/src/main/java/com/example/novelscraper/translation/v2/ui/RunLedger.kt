package com.example.novelscraper.translation.v2.ui

import com.example.novelscraper.translation.v2.engine.EngineState

/** 画面用の実行1件表示。stateは当該runのEngineState。 */
data class RunView(
    val runId: String,
    val label: String,
    val slot: Int,
    val active: Boolean,
    val state: EngineState
)

/**
 * 同時実行台帳の純粋部。Android非依存・JVM試験可。
 * 技術的根拠1行：判定・集計は純粋関数に寄せ、ロック・副作用・Android接着は呼出側(ViewModel)単一に残す。
 */

/** 空きslot割当。上限内であることは呼出側ガード(canStartRun)の責務。 */
fun nextFreeSlot(usedSlots: Set<Int>, maxRuns: Int): Int =
    (0 until maxRuns).firstOrNull { it !in usedSlots } ?: 0

/** 世代の枝刈り。keep以外を捨て無制限肥大を作らない。 */
fun <T> pruneToIds(map: Map<String, T>, keepIds: Set<String>): Map<String, T> =
    map.filterKeys { it in keepIds }

/** 画面一覧の組み立て。実行中優先・新しい順。meta欠けは落とす。 */
fun buildRunViews(
    states: Map<String, EngineState>,
    meta: Map<String, Pair<String, Int>>,
    activeIds: Set<String>
): List<RunView> = states.mapNotNull { (id, st) ->
    val (label, slot) = meta[id] ?: return@mapNotNull null
    RunView(id, label, slot, id in activeIds, st)
}.sortedWith(compareByDescending<RunView> { it.active }.thenByDescending { it.runId })

/** Main用の集約。消費者はisRunning・文面のみ。 */
fun aggregateEngineState(views: List<RunView>): EngineState {
    val running = views.filter { it.active && it.state.isRunning }
    val base = running.lastOrNull()?.state ?: views.lastOrNull()?.state ?: EngineState()
    return EngineState(
        isRunning = running.isNotEmpty(),
        statusText = base.statusText,
        folderName = base.folderName,
        fileName = base.fileName,
        progress = base.progress,
        chunkProgress = base.chunkProgress,
        logs = base.logs
    )
}
