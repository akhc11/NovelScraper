package com.example.novelscraper

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * ViewModel→UIへの一方向イベント配送（UiEventはMainUiState.ktの定義を再利用）。
 * Toast等の表示はUI層の責務のため、ViewModelはContextを掴まずイベントで通知する。
 * 収集は Activity の repeatOnLifecycle(STARTED) 1箇所に限定する。
 */
class UiEventBus {
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    suspend fun send(event: UiEvent) {
        _events.send(event)
    }
}
