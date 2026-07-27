package jp.tessecraft.movinkpadmacrodriver.shizuku

import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Shizuku 13.x に残っている remote process API の最小ラッパー。
 *
 * この API は Shizuku API 14 で削除予定のため、UserService が動作しない端末向けの
 * 互換経路としてのみ使用する。
 */
object ShellProcessRunner {

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    fun runBlocking(command: Array<String>): CommandResult {
        val process = createRemoteProcess(command)
        val streamExecutor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "shizuku-shell-stream").apply {
                isDaemon = true
            }
        }

        return try {
            // 両方のパイプを同時に消費し、出力量が多い場合の停止を防ぐ。
            val stdout = streamExecutor.submit<String> {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderr = streamExecutor.submit<String> {
                process.errorStream.bufferedReader().use { it.readText() }
            }
            val exitCode = process.waitFor()

            CommandResult(
                exitCode = exitCode,
                stdout = stdout.get().trim(),
                stderr = stderr.get().trim()
            )
        } finally {
            streamExecutor.shutdownNow()
            process.destroy()
        }
    }

    fun start(command: Array<String>): Process {
        return createRemoteProcess(command)
    }

    @Suppress("UNCHECKED_CAST")
    private fun createRemoteProcess(command: Array<String>): Process {
        check(Shizuku.pingBinder()) {
            "Shizuku Binderに接続されていません"
        }

        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true

        return method.invoke(
            null,
            command,
            null,
            null
        ) as Process
    }
}
