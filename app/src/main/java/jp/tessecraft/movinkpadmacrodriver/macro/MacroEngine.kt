package jp.tessecraft.movinkpadmacrodriver.macro

import jp.tessecraft.movinkpadmacrodriver.input.LinuxInputEvent
import jp.tessecraft.movinkpadmacrodriver.shizuku.ShellProcessRunner
import java.util.concurrent.Executors

class MacroEngine(
    private val assignmentProvider: () -> KeyAssignments,
    private val onResult: (Result<ExecutedMacro>) -> Unit
) {
    data class ExecutedMacro(
        val trigger: PenButtonTrigger,
        val shortcut: KeyShortcut
    ) {
        val displayName: String
            get() = "${trigger.displayName} → ${shortcut.displayName}"
    }

    private val commandExecutor = Executors.newSingleThreadExecutor {
        Thread(it, "pen-macro-command")
    }
    private val pressedButtons = mutableSetOf<Int>()
    private var closed = false

    @Synchronized
    fun handle(events: List<LinuxInputEvent>) {
        if (closed) {
            return
        }

        val newlyPressed = mutableSetOf<Int>()

        events.forEach { event ->
            when (event.value) {
                0 -> pressedButtons.remove(event.code)

                1 -> {
                    // UPを受ける前の重複DOWNもロングプレスの一部として無視する。
                    if (pressedButtons.add(event.code)) {
                        newlyPressed += event.code
                    }
                }

                // value=2はカーネルのキーリピート。
                else -> Unit
            }
        }

        val trigger = when {
            LinuxInputEvent.BTN_STYLUS in newlyPressed &&
                LinuxInputEvent.BTN_STYLUS2 in newlyPressed ->
                PenButtonTrigger.SIMULTANEOUS

            LinuxInputEvent.BTN_STYLUS in newlyPressed ->
                PenButtonTrigger.BUTTON_1

            LinuxInputEvent.BTN_STYLUS2 in newlyPressed ->
                PenButtonTrigger.BUTTON_2

            else -> return
        }

        commandExecutor.execute {
            val result = runCatching {
                val shortcut = assignmentProvider()[trigger]
                val commandResult = ShellProcessRunner.runBlocking(
                    shortcut.toInputCommand()
                )
                check(commandResult.exitCode == 0) {
                    commandResult.stderr.ifEmpty {
                        "inputコマンドが終了コード${commandResult.exitCode}で失敗しました"
                    }
                }
                ExecutedMacro(trigger, shortcut)
            }
            onResult(result)
        }
    }

    @Synchronized
    fun resetButtonState() {
        pressedButtons.clear()
    }

    @Synchronized
    fun close() {
        if (closed) {
            return
        }
        closed = true
        pressedButtons.clear()
        commandExecutor.shutdownNow()
    }
}
