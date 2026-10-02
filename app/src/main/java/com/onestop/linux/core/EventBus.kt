package com.onestop.linux.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 终端 / 网页 / 桌面三视图之间的事件总线（方案 §5.3）。 */
sealed interface AppEvent {
    data class ServiceUp(val found: ServiceDetector.Found) : AppEvent
    data object ServiceDown : AppEvent
    data class ContainerState(val message: String, val ready: Boolean) : AppEvent
    /** 环境（bootstrap + rootfs）已就绪 —— 终端会话必须等到它之后才能启动，否则 proot 找不到 /bin/bash。 */
    data object EnvironmentReady : AppEvent
    data class GpuMode(val hardware: Boolean, val detail: String) : AppEvent
}

object EventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = _events
    fun emit(e: AppEvent) { _events.tryEmit(e) }
}
