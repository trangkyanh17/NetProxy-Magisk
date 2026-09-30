package com.fanjv.netproxy.feature.dashboard.presentation

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanjv.netproxy.R
import com.fanjv.netproxy.core.module.ModuleEnvironment
import com.fanjv.netproxy.core.module.ServiceRepository
import com.fanjv.netproxy.core.module.ServiceStatusSnapshot
import com.fanjv.netproxy.core.ui.UiText
import com.fanjv.netproxy.core.ui.toUiText
import com.fanjv.netproxy.core.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface

internal data class CatalogDashboardUiState(
    val rootChecked: Boolean = false,
    val rootGranted: Boolean = false,
    val moduleInstalled: Boolean = false,
    val loading: Boolean = true,
    val serviceState: String = "stopped",
    val serviceError: String = "",
    val readyAt: Long = 0,
    val uptimeSeconds: Long = 0,
    val outboundMode: String = "unknown",
    val activeGroupId: String = "",
    val currentNode: String = "",
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val cpuUsage: Float = 0f,
    val memoryUsage: Float = 0f,
    val trafficSamples: List<TrafficSample> = emptyList(),
    val internalIp: String = "--",
    val operation: String = "",
    val notice: UiText = UiText.Empty,
    val noticeId: Long = 0
) {
    val isServiceTransitioning: Boolean
        get() = operation == "start" || operation == "stop"
    val isReady: Boolean
        get() = serviceState == "ready" && !isServiceTransitioning
    val isStarting: Boolean
        get() = operation == "start" || serviceState in setOf("preparing", "starting")
    val isStopping: Boolean
        get() = operation == "stop" || serviceState == "stopping"
    val isServiceControlBusy: Boolean
        get() = isStarting || isStopping || operation.isNotEmpty()
}

/** 仅消费 netproxyctl 与运行时 API 的仪表盘状态，不读取旧配置或 PID。 */
internal class CatalogDashboardViewModel(
    private val repository: ServiceRepository,
    private val environment: ModuleEnvironment
) : ViewModel() {
    private val _state = MutableStateFlow(CatalogDashboardUiState())
    val state: StateFlow<CatalogDashboardUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private val pollWake = Channel<Unit>(Channel.CONFLATED)
    private var forceIpOnNextSnapshot = false
    private var lastInteractionElapsedMillis = 0L
    private var visible = false
    private var serviceTransitionRevision = 0L
    private val totalMemoryBytes = environment.totalMemoryBytes
    private val snapshotReducer = DashboardSnapshotReducer(totalMemoryBytes)
    private val trafficReducer = TrafficTimelineReducer()
    private val localIpv4Cache = LocalIpv4Cache(loader = ::loadLocalIpv4Address)

    init {
        viewModelScope.launch {
            val availability = environment.availability()
            _state.update {
                it.copy(
                    rootChecked = true,
                    rootGranted = availability.rootGranted,
                    moduleInstalled = availability.moduleInstalled,
                    loading = availability.moduleInstalled
                )
            }
            if (availability.moduleInstalled && visible) requestPollingEvent(forceIp = true)
        }
    }

    fun refresh() {
        if (_state.value.moduleInstalled) requestPollingEvent(forceIp = true)
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (visible) {
            if (_state.value.moduleInstalled) requestPollingEvent(forceIp = true)
        } else {
            refreshJob?.cancel()
            refreshJob = null
            while (pollWake.tryReceive().isSuccess) {
                // 清除后台期间遗留的 UI 刷新信号。
            }
        }
    }

    fun toggleService() {
        if (!canControlService()) return
        val action = if (_state.value.serviceState in setOf("ready", "starting", "preparing")) {
            "stop"
        } else {
            "start"
        }
        runOperation(action) {
            repository.action(action)
            if (action == "start") {
                UiText.Resource(R.string.dashboard_service_started)
            } else {
                UiText.Resource(R.string.dashboard_service_stopped)
            }
        }
    }

    fun setMode(mode: String) {
        if (!canControlService()) return
        runOperation("mode") {
            repository.setMode(mode)
            UiText.Resource(R.string.dashboard_mode_changed)
        }
    }

    private fun canControlService(): Boolean =
        _state.value.rootGranted &&
            _state.value.moduleInstalled &&
            !_state.value.loading &&
            _state.value.operation.isEmpty()

    fun clearNotice() {
        _state.update { it.copy(notice = UiText.Empty) }
    }

    private fun requestPollingEvent(forceIp: Boolean) {
        lastInteractionElapsedMillis = SystemClock.elapsedRealtime()
        forceIpOnNextSnapshot = forceIpOnNextSnapshot || forceIp
        if (!visible || !_state.value.moduleInstalled) return
        if (refreshJob?.isActive == true) {
            pollWake.trySend(Unit)
            return
        }
        while (pollWake.tryReceive().isSuccess) {
            // 新 polling cycle không kế thừa wake cũ.
        }
        refreshJob = viewModelScope.launch {
            var confirmPending = true
            while (isActive && visible) {
                val forceIpRefresh = forceIpOnNextSnapshot
                forceIpOnNextSnapshot = false
                refreshSnapshot(forceIpRefresh)

                val idleMillis = SystemClock.elapsedRealtime() - lastInteractionElapsedMillis
                val waitMillis = DashboardPollingPolicy.nextDelayMillis(idleMillis, confirmPending)
                val woke = withTimeoutOrNull(waitMillis) {
                    pollWake.receive()
                    true
                } ?: false
                if (woke) {
                    confirmPending = true
                    continue
                }
                if (confirmPending) confirmPending = false
            }
        }
    }

    private suspend fun refreshSnapshot(forceIpRefresh: Boolean = false) {
        if (_state.value.isServiceTransitioning) return
        val requestRevision = serviceTransitionRevision

        runCatching { repository.status() }.onSuccess { service ->
            currentCoroutineContext().ensureActive()
            if (!shouldApplyDashboardSnapshot(
                    requestRevision = requestRevision,
                    currentRevision = serviceTransitionRevision,
                    operation = _state.value.operation
                )
            ) return@onSuccess

            val nowMillis = System.currentTimeMillis()
            val timeline = if (service.state == "ready") {
                trafficReducer.reduce(service, nowMillis)
            } else {
                trafficReducer.reset()
                TrafficTimelineState()
            }
            _state.update { current ->
                snapshotReducer.reduce(
                    current = current,
                    service = service,
                    nowMillis = nowMillis,
                    localAddress = localIpv4Cache.read(
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                        force = forceIpRefresh
                    )
                ).copy(
                    downloadBytesPerSecond = timeline.downloadBytesPerSecond,
                    uploadBytesPerSecond = timeline.uploadBytesPerSecond,
                    trafficSamples = timeline.samples
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (!shouldApplyDashboardSnapshot(
                    requestRevision = requestRevision,
                    currentRevision = serviceTransitionRevision,
                    operation = _state.value.operation
                )
            ) return@onFailure

            _state.update {
                it.copy(
                    loading = false,
                    serviceState = "failed",
                    serviceError = error.userMessage()
                )
            }
        }
    }

    private fun runOperation(name: String, action: suspend () -> UiText) {
        viewModelScope.launch {
            val changesServiceState = name == "start" || name == "stop"
            val previousServiceState = _state.value.serviceState
            if (changesServiceState) serviceTransitionRevision++
            _state.update { current ->
                current.copy(
                    operation = name,
                    serviceError = "",
                    serviceState = when (name) {
                        "start" -> "starting"
                        "stop" -> "stopping"
                        else -> current.serviceState
                    }
                )
            }
            runCatching { action() }
                .onSuccess { message ->
                    if (changesServiceState) serviceTransitionRevision++
                    _state.update {
                        it.copy(
                            operation = "",
                            serviceState = when (name) {
                                "start" -> "ready"
                                "stop" -> "stopped"
                                else -> it.serviceState
                            },
                            notice = message,
                            noticeId = it.noticeId + 1
                        )
                    }
                    requestPollingEvent(forceIp = false)
                }
                .onFailure { error ->
                    if (changesServiceState) serviceTransitionRevision++
                    _state.update {
                        it.copy(
                            operation = "",
                            serviceState = if (changesServiceState) {
                                previousServiceState
                            } else {
                                it.serviceState
                            },
                            notice = error.userMessage().toUiText(),
                            noticeId = it.noticeId + 1
                        )
                    }
                    requestPollingEvent(forceIp = false)
                }
        }
    }

    private fun loadLocalIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull()
}

/** 仅接受当前启停代次且不处于过渡操作中的服务快照。 */
internal fun shouldApplyDashboardSnapshot(
    requestRevision: Long,
    currentRevision: Long,
    operation: String
): Boolean = requestRevision == currentRevision && operation != "start" && operation != "stop"

/** 将持久选择状态转换为适合仪表盘展示的节点名称。 */
internal fun dashboardNodeName(service: ServiceStatusSnapshot): String {
    if (service.activeGroupNodeCount <= 0) return ""

    val groupName = service.activeGroupName.ifBlank { service.activeGroupId }
    val automatic = service.selectorMode == "urltest"
    if (automatic) {
        val selected = if (service.state == "ready") {
            runtimeSelectedNodeTag(
                service.activeGroupRuntimeTag,
                service.runtimeSelected
            )
        } else {
            ""
        }
        if (selected.isNotBlank()) return "$selected · Auto-Fastest"
        return "$groupName/Auto-Fastest"
    }
    val nodeName = service.selectedNodeRef
        .substringAfter('/', service.selectedNodeRef)
        .ifBlank { service.runtimeSelected.substringAfter('/', service.runtimeSelected) }
    return listOf(groupName, nodeName).filter(String::isNotBlank).joinToString("/")
}

internal fun runtimeSelectedNodeTag(runtimeTag: String, selected: String): String {
    if (runtimeTag.isBlank()) return ""
    val prefix = "$runtimeTag/"
    return selected.removePrefix(prefix)
        .takeIf { selected.startsWith(prefix) && it.isNotBlank() }
        .orEmpty()
}
