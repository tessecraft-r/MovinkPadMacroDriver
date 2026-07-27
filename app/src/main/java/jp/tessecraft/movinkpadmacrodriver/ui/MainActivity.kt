package jp.tessecraft.movinkpadmacrodriver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import jp.tessecraft.movinkpadmacrodriver.macro.KeyAssignmentStore
import jp.tessecraft.movinkpadmacrodriver.macro.KeyAssignments
import jp.tessecraft.movinkpadmacrodriver.macro.KeyShortcut
import jp.tessecraft.movinkpadmacrodriver.macro.PenButtonTrigger
import jp.tessecraft.movinkpadmacrodriver.service.PenButtonMonitorSnapshot
import jp.tessecraft.movinkpadmacrodriver.service.PenButtonMonitorState
import jp.tessecraft.movinkpadmacrodriver.service.PenButtonService
import jp.tessecraft.movinkpadmacrodriver.shizuku.ShizukuManager
import jp.tessecraft.movinkpadmacrodriver.ui.theme.MovinkPadMacroDriverTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var assignmentStore: KeyAssignmentStore
    private var status by mutableStateOf(ShizukuManager.Status.BINDER_UNAVAILABLE)
    private var monitorState by mutableStateOf(PenButtonMonitorState.snapshot)
    private var assignments by mutableStateOf(KeyAssignments.DEFAULT)

    private val monitorStateListener: (PenButtonMonitorSnapshot) -> Unit =
        { snapshot -> runOnUiThread { monitorState = snapshot } }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener { refreshStatus() }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener { refreshStatus() }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == ShizukuManager.PERMISSION_REQUEST_CODE) {
                refreshStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assignmentStore = KeyAssignmentStore(this)
        assignments = assignmentStore.load()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        PenButtonMonitorState.addListener(monitorStateListener)
        refreshStatus()

        setContent {
            MovinkPadMacroDriverTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        PenButtonMonitorState.removeListener(monitorStateListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        // Foreground ServiceはActivity終了後も意図的に継続する。
        super.onDestroy()
    }

    @Composable
    private fun MainScreen() {
        var editingTrigger by remember {
            mutableStateOf<PenButtonTrigger?>(null)
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            val wideLayout = maxWidth >= 760.dp
            val contentModifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())

            if (wideLayout) {
                Row(
                    modifier = contentModifier,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    StatusPanel(Modifier.weight(1f))
                    AssignmentPanel(
                        modifier = Modifier.weight(1.35f),
                        onEdit = { editingTrigger = it }
                    )
                }
            } else {
                Column(
                    modifier = contentModifier,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    StatusPanel(Modifier.fillMaxWidth())
                    AssignmentPanel(
                        modifier = Modifier.fillMaxWidth(),
                        onEdit = { editingTrigger = it }
                    )
                }
            }
        }

        editingTrigger?.let { trigger ->
            ShortcutEditorDialog(
                trigger = trigger,
                initial = assignments[trigger],
                onDismiss = { editingTrigger = null },
                onConfirm = { shortcut ->
                    updateAssignment(trigger, shortcut)
                    editingTrigger = null
                }
            )
        }
    }

    @Composable
    private fun StatusPanel(modifier: Modifier = Modifier) {
        Card(modifier = modifier) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("監視状態", style = MaterialTheme.typography.headlineSmall)
                Text(statusText(), style = MaterialTheme.typography.bodyLarge)

                if (status == ShizukuManager.Status.PERMISSION_REQUIRED) {
                    Button(onClick = ShizukuManager::requestPermission) {
                        Text("Shizukuの利用を許可")
                    }
                }

                Text(
                    monitorStatusText(),
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = {
                        if (monitorState.serviceActive) {
                            stopMonitoringService()
                        } else {
                            startMonitoringService()
                        }
                    },
                    enabled =
                        monitorState.serviceActive ||
                            status == ShizukuManager.Status.READY,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (monitorState.serviceActive) {
                            "常駐監視を停止"
                        } else {
                            "常駐監視を開始"
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun AssignmentPanel(
        modifier: Modifier = Modifier,
        onEdit: (PenButtonTrigger) -> Unit
    ) {
        Card(modifier = modifier) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "キー割り当て",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "変更は次のボタン操作からすぐに反映されます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AssignmentRow(
                    title = "ボタン1",
                    subtitle = "BTN_STYLUS",
                    shortcut = assignments.button1,
                    onClick = { onEdit(PenButtonTrigger.BUTTON_1) }
                )
                AssignmentRow(
                    title = "ボタン2",
                    subtitle = "BTN_STYLUS2",
                    shortcut = assignments.button2,
                    onClick = { onEdit(PenButtonTrigger.BUTTON_2) }
                )
                AssignmentRow(
                    title = "ボタン3",
                    subtitle = "ボタン1＋ボタン2の同時押し",
                    shortcut = assignments.simultaneous,
                    onClick = { onEdit(PenButtonTrigger.SIMULTANEOUS) }
                )

                TextButton(
                    onClick = {
                        assignmentStore.reset()
                        assignments = KeyAssignments.DEFAULT
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("初期設定に戻す")
                }
            }
        }
    }

    @Composable
    private fun AssignmentRow(
        title: String,
        subtitle: String,
        shortcut: KeyShortcut,
        onClick: () -> Unit
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    shortcut.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("変更")
            }
        }
    }

    @Composable
    private fun ShortcutEditorDialog(
        trigger: PenButtonTrigger,
        initial: KeyShortcut,
        onDismiss: () -> Unit,
        onConfirm: (KeyShortcut) -> Unit
    ) {
        var selectedModifiers by remember(initial) {
            mutableStateOf(initial.modifiers.toSet())
        }
        var selectedKey by remember(initial) { mutableStateOf(initial.key) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text("${trigger.displayName} の割り当て")
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "修飾キー（複数選択可）",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(
                            rememberScrollState()
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MODIFIER_OPTIONS.forEach { option ->
                            FilterChip(
                                selected = option.keyCode in selectedModifiers,
                                onClick = {
                                    selectedModifiers =
                                        if (option.keyCode in selectedModifiers) {
                                            selectedModifiers - option.keyCode
                                        } else {
                                            selectedModifiers + option.keyCode
                                        }
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }

                    Text(
                        "メインキー",
                        style = MaterialTheme.typography.titleMedium
                    )
                    KEY_GROUPS.forEach { group ->
                        Text(
                            group.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        group.options.chunked(6).forEach { rowOptions ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.spacedBy(6.dp)
                            ) {
                                rowOptions.forEach { option ->
                                    val selected = option.keyCode == selectedKey
                                    OutlinedButton(
                                        onClick = {
                                            selectedKey = option.keyCode
                                        },
                                        modifier = Modifier.width(72.dp),
                                        border = if (selected) {
                                            BorderStroke(
                                                2.dp,
                                                MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            null
                                        }
                                    ) {
                                        Text(option.label)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirm(
                            KeyShortcut(
                                modifiers = MODIFIER_OPTIONS
                                    .map(KeyOption::keyCode)
                                    .filter { it in selectedModifiers },
                                key = selectedKey
                            )
                        )
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("キャンセル")
                }
            }
        )
    }

    private fun updateAssignment(
        trigger: PenButtonTrigger,
        shortcut: KeyShortcut
    ) {
        assignments = when (trigger) {
            PenButtonTrigger.BUTTON_1 ->
                assignments.copy(button1 = shortcut)

            PenButtonTrigger.BUTTON_2 ->
                assignments.copy(button2 = shortcut)

            PenButtonTrigger.SIMULTANEOUS ->
                assignments.copy(simultaneous = shortcut)
        }
        assignmentStore.save(assignments)
    }

    private fun refreshStatus() {
        runOnUiThread {
            status = ShizukuManager.getStatus()
        }
    }

    private fun startMonitoringService() {
        if (
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, PenButtonService::class.java)
        )
    }

    private fun stopMonitoringService() {
        stopService(Intent(this, PenButtonService::class.java))
    }

    private fun statusText(): String {
        return when (status) {
            ShizukuManager.Status.BINDER_UNAVAILABLE ->
                "Shizukuに接続できません。\nShizukuが起動しているか確認してください。"

            ShizukuManager.Status.PERMISSION_REQUIRED ->
                "Shizukuの利用許可が必要です。"

            ShizukuManager.Status.PERMISSION_DENIED ->
                "Shizukuの利用が拒否されています。\nShizukuアプリから許可してください。"

            ShizukuManager.Status.READY -> {
                val uid = ShizukuManager.getServerUid()
                when (uid) {
                    2000 -> "Shizuku接続成功\nUID: 2000（shell）"
                    0 -> "Shizuku接続成功\nUID: 0（root）"
                    null -> "Shizuku接続済み\nUIDの取得に失敗しました"
                    else -> "Shizuku接続成功\nUID: $uid"
                }
            }
        }
    }

    private fun monitorStatusText(): String = buildString {
        append(
            when (monitorState.phase) {
                PenButtonMonitorSnapshot.Phase.STOPPED ->
                    "常駐監視: 停止中"

                PenButtonMonitorSnapshot.Phase.WAITING_FOR_SHIZUKU ->
                    "常駐監視: Shizuku待機中"

                PenButtonMonitorSnapshot.Phase.MONITORING ->
                    "常駐監視中: ${
                        monitorState.devicePath ?: "入力デバイス確認中"
                    }"

                PenButtonMonitorSnapshot.Phase.ERROR ->
                    "常駐監視: エラー"
            }
        )
        append("\nBTN_STYLUS: ${monitorState.stylusCount}回")
        append("\nBTN_STYLUS2: ${monitorState.stylus2Count}回")
        append("\nコマンド発火: ${monitorState.actionCount}回")

        monitorState.lastEvent?.let {
            append("\n最後のイベント: $it")
        }
        monitorState.lastAction?.let {
            append("\n最後の操作: $it")
        }
        monitorState.error?.let {
            append("\n状態: $it")
        }
    }

    private data class KeyOption(
        val label: String,
        val keyCode: String
    )

    private data class KeyGroup(
        val title: String,
        val options: List<KeyOption>
    )

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002

        val MODIFIER_OPTIONS = listOf(
            KeyOption("Ctrl", "KEYCODE_CTRL_LEFT"),
            KeyOption("Shift", "KEYCODE_SHIFT_LEFT"),
            KeyOption("Alt", "KEYCODE_ALT_LEFT"),
            KeyOption("Meta", "KEYCODE_META_LEFT")
        )

        val KEY_GROUPS = listOf(
            KeyGroup(
                "英字",
                ('A'..'Z').map {
                    KeyOption(it.toString(), "KEYCODE_$it")
                }
            ),
            KeyGroup(
                "数字",
                ('0'..'9').map {
                    KeyOption(it.toString(), "KEYCODE_$it")
                }
            ),
            KeyGroup(
                "記号",
                listOf(
                    KeyOption(",", "KEYCODE_COMMA"),
                    KeyOption(".", "KEYCODE_PERIOD"),
                    KeyOption("-", "KEYCODE_MINUS"),
                    KeyOption("=", "KEYCODE_EQUALS"),
                    KeyOption("[", "KEYCODE_LEFT_BRACKET"),
                    KeyOption("]", "KEYCODE_RIGHT_BRACKET"),
                    KeyOption("\\", "KEYCODE_BACKSLASH"),
                    KeyOption(";", "KEYCODE_SEMICOLON"),
                    KeyOption("'", "KEYCODE_APOSTROPHE"),
                    KeyOption("/", "KEYCODE_SLASH"),
                    KeyOption("`", "KEYCODE_GRAVE"),
                    KeyOption("+", "KEYCODE_PLUS"),
                    KeyOption("*", "KEYCODE_STAR"),
                    KeyOption("#", "KEYCODE_POUND"),
                    KeyOption("@", "KEYCODE_AT")
                )
            ),
            KeyGroup(
                "操作",
                listOf(
                    KeyOption("Space", "KEYCODE_SPACE"),
                    KeyOption("Tab", "KEYCODE_TAB"),
                    KeyOption("Enter", "KEYCODE_ENTER"),
                    KeyOption("Esc", "KEYCODE_ESCAPE"),
                    KeyOption("Back", "KEYCODE_DEL")
                )
            )
        )
    }
}
