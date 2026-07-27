package jp.tessecraft.movinkpadmacrodriver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import jp.tessecraft.movinkpadmacrodriver.input.InputDeviceScanner
import jp.tessecraft.movinkpadmacrodriver.input.LinuxInputEvent
import jp.tessecraft.movinkpadmacrodriver.input.LinuxInputReader
import jp.tessecraft.movinkpadmacrodriver.macro.KeyAssignmentStore
import jp.tessecraft.movinkpadmacrodriver.macro.MacroEngine
import jp.tessecraft.movinkpadmacrodriver.ui.MainActivity
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

data class PenButtonMonitorSnapshot(
    val serviceActive: Boolean = false,
    val phase: Phase = Phase.STOPPED,
    val stylusCount: Int = 0,
    val stylus2Count: Int = 0,
    val actionCount: Int = 0,
    val lastEvent: String? = null,
    val lastAction: String? = null,
    val devicePath: String? = null,
    val error: String? = null
) {
    enum class Phase {
        STOPPED,
        WAITING_FOR_SHIZUKU,
        MONITORING,
        ERROR
    }

    fun withEvents(events: List<LinuxInputEvent>): PenButtonMonitorSnapshot {
        val stylusPresses = events.count {
            it.code == LinuxInputEvent.BTN_STYLUS && it.value == 1
        }
        val stylus2Presses = events.count {
            it.code == LinuxInputEvent.BTN_STYLUS2 && it.value == 1
        }
        val last = events.lastOrNull() ?: return this
        val buttonName = when (last.code) {
            LinuxInputEvent.BTN_STYLUS -> "BTN_STYLUS"
            LinuxInputEvent.BTN_STYLUS2 -> "BTN_STYLUS2"
            else -> return this
        }
        val eventAction = if (last.isPressed) "DOWN" else "UP"

        return copy(
            stylusCount = stylusCount + stylusPresses,
            stylus2Count = stylus2Count + stylus2Presses,
            lastEvent = "$buttonName $eventAction",
            error = null
        )
    }
}

object PenButtonMonitorState {
    private val listeners =
        CopyOnWriteArraySet<(PenButtonMonitorSnapshot) -> Unit>()

    @Volatile
    var snapshot = PenButtonMonitorSnapshot()
        private set

    fun addListener(listener: (PenButtonMonitorSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun removeListener(listener: (PenButtonMonitorSnapshot) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun update(
        transform: (PenButtonMonitorSnapshot) -> PenButtonMonitorSnapshot
    ) {
        snapshot = transform(snapshot)
        listeners.forEach { it(snapshot) }
    }
}

class PenButtonService : Service() {

    private var inputReader: LinuxInputReader? = null
    private var scanInProgress = false
    private var scanGeneration = 0
    private var destroyed = false
    private lateinit var macroEngine: MacroEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryMonitoring = Runnable { startMonitoringIfAvailable() }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            startMonitoringIfAvailable()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            cancelDeviceScan()
            stopReader()
            setWaitingState("Shizukuが停止しました")
        }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Shizukuへの接続を確認中")
        )

        PenButtonMonitorState.update {
            PenButtonMonitorSnapshot(
                serviceActive = true,
                phase = PenButtonMonitorSnapshot.Phase.WAITING_FOR_SHIZUKU
            )
        }

        val assignmentStore = KeyAssignmentStore(this)
        macroEngine = MacroEngine(
            assignmentProvider = assignmentStore::load
        ) { result ->
            PenButtonMonitorState.update { state ->
                result.fold(
                    onSuccess = { executedMacro ->
                        state.copy(
                            actionCount = state.actionCount + 1,
                            lastAction = executedMacro.displayName,
                            error = null
                        )
                    },
                    onFailure = { error ->
                        state.copy(error = error.message ?: error.toString())
                    }
                )
            }
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        startMonitoringIfAvailable()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        startMonitoringIfAvailable()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        cancelDeviceScan()
        mainHandler.removeCallbacks(retryMonitoring)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        stopReader()
        macroEngine.close()

        PenButtonMonitorState.update {
            it.copy(
                serviceActive = false,
                phase = PenButtonMonitorSnapshot.Phase.STOPPED
            )
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    private fun startMonitoringIfAvailable() {
        if (inputReader != null || scanInProgress || destroyed) {
            return
        }

        if (
            !Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        ) {
            setWaitingState("Shizukuの起動または利用許可を待っています")
            return
        }

        scanInProgress = true
        val generation = ++scanGeneration
        PenButtonMonitorState.update {
            it.copy(
                serviceActive = true,
                phase = PenButtonMonitorSnapshot.Phase.WAITING_FOR_SHIZUKU,
                devicePath = null,
                error = null
            )
        }
        updateNotification("ペンデバイスを検索中")

        Thread {
            val result = runCatching(InputDeviceScanner::findPenDevice)
            synchronized(this) {
                if (generation != scanGeneration) {
                    return@synchronized
                }
                scanInProgress = false
                if (destroyed) {
                    return@synchronized
                }
                result.fold(
                    onSuccess = { startReader(it.path) },
                    onFailure = { error ->
                        PenButtonMonitorState.update {
                            it.copy(
                                phase = PenButtonMonitorSnapshot.Phase.ERROR,
                                devicePath = null,
                                error = error.message ?: error.toString()
                            )
                        }
                        updateNotification("ペンデバイスの検出に失敗")
                    }
                )
            }
        }.apply {
            name = "input-device-scanner"
            start()
        }
    }

    @Synchronized
    private fun startReader(devicePath: String) {
        if (destroyed || inputReader != null) {
            return
        }

        macroEngine.resetButtonState()
        PenButtonMonitorState.update {
            it.copy(
                serviceActive = true,
                phase = PenButtonMonitorSnapshot.Phase.MONITORING,
                devicePath = devicePath,
                error = null
            )
        }
        updateNotification("ペンボタンを監視中")

        inputReader = LinuxInputReader(
            devicePath = devicePath,
            onEvents = { events ->
                macroEngine.handle(events)
                PenButtonMonitorState.update { it.withEvents(events) }
            },
            onError = { error ->
                synchronized(this) {
                    inputReader = null
                }
                mainHandler.removeCallbacks(retryMonitoring)
                mainHandler.postDelayed(retryMonitoring, RETRY_DELAY_MS)
                PenButtonMonitorState.update {
                    it.copy(
                        phase = PenButtonMonitorSnapshot.Phase.ERROR,
                        error = error.message ?: error.toString()
                    )
                }
                updateNotification("監視エラー")
            }
        ).also { it.start() }
    }

    @Synchronized
    private fun stopReader() {
        inputReader?.stop()
        inputReader = null
        if (::macroEngine.isInitialized) {
            macroEngine.resetButtonState()
        }
    }

    @Synchronized
    private fun cancelDeviceScan() {
        scanGeneration++
        scanInProgress = false
    }

    private fun setWaitingState(message: String) {
        PenButtonMonitorState.update {
            it.copy(
                serviceActive = true,
                phase = PenButtonMonitorSnapshot.Phase.WAITING_FOR_SHIZUKU,
                error = message
            )
        }
        updateNotification(message)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "ペンボタン監視",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "MovinkPadのペンボタン監視状態を表示します"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("MovinkPad Macro Driver")
            .setContentText(message)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private companion object {
        const val RETRY_DELAY_MS = 2_000L
        const val NOTIFICATION_CHANNEL_ID = "pen_button_monitor"
        const val NOTIFICATION_ID = 1001
    }
}
