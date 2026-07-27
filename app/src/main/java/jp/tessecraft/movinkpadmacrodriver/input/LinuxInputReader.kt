package jp.tessecraft.movinkpadmacrodriver.input

import jp.tessecraft.movinkpadmacrodriver.shizuku.ShellProcessRunner
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LinuxInputReader(
    private val devicePath: String,
    private val onEvents: (List<LinuxInputEvent>) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    @Volatile
    private var running = false

    @Volatile
    private var process: Process? = null

    private var readerThread: Thread? = null

    @Synchronized
    fun start() {
        if (running) {
            return
        }

        running = true
        readerThread = Thread(::readLoop, "linux-input-reader").apply {
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        process?.destroy()
        process = null
        readerThread?.interrupt()
        readerThread = null
    }

    private fun readLoop() {
        try {
            val remoteProcess = ShellProcessRunner.start(
                arrayOf("/system/bin/cat", devicePath)
            )
            process = remoteProcess

            if (!running) {
                remoteProcess.destroy()
                return
            }

            // stderrも消費し、リモートプロセス側のパイプ詰まりを防ぐ。
            Thread {
                remoteProcess.errorStream.use { it.copyTo(OutputStreamSink) }
            }.apply {
                name = "linux-input-stderr"
                isDaemon = true
                start()
            }

            remoteProcess.inputStream.use(::readEvents)
        } catch (error: Throwable) {
            if (running) {
                onError(error)
            }
        } finally {
            process?.destroy()
            process = null
            running = false
        }
    }

    private fun readEvents(input: InputStream) {
        val eventBytes = ByteArray(INPUT_EVENT_SIZE)
        val pendingButtonEvents = mutableListOf<LinuxInputEvent>()

        while (running) {
            readFully(input, eventBytes)
            if (!running) {
                break
            }

            val buffer = ByteBuffer.wrap(eventBytes)
                .order(ByteOrder.LITTLE_ENDIAN)

            // 64bit Androidのtimevalは8バイト×2。
            buffer.position(TYPE_OFFSET)

            val event = LinuxInputEvent(
                type = buffer.short.toInt() and 0xffff,
                code = buffer.short.toInt() and 0xffff,
                value = buffer.int
            )

            when {
                event.type == LinuxInputEvent.EV_KEY &&
                (
                    event.code == LinuxInputEvent.BTN_STYLUS ||
                        event.code == LinuxInputEvent.BTN_STYLUS2
                    ) -> pendingButtonEvents += event

                event.type == LinuxInputEvent.EV_SYN &&
                    event.code == LinuxInputEvent.SYN_REPORT -> {
                    if (pendingButtonEvents.isNotEmpty()) {
                        onEvents(pendingButtonEvents.toList())
                        pendingButtonEvents.clear()
                    }
                }
            }
        }
    }

    private fun readFully(input: InputStream, destination: ByteArray) {
        var offset = 0

        while (offset < destination.size && running) {
            val count = input.read(
                destination,
                offset,
                destination.size - offset
            )

            if (count < 0) {
                throw EOFException("入力デバイスのストリームが終了しました")
            }

            offset += count
        }
    }

    private companion object {
        const val INPUT_EVENT_SIZE = 24
        const val TYPE_OFFSET = 16

        val OutputStreamSink = object : java.io.OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
    }
}
