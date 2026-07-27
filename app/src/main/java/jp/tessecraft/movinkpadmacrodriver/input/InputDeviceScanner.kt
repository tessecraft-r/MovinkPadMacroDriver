package jp.tessecraft.movinkpadmacrodriver.input

import jp.tessecraft.movinkpadmacrodriver.shizuku.ShellProcessRunner

/**
 * event番号ではなく、デバイスが公開する入力能力からペンを特定する。
 */
object InputDeviceScanner {

    data class InputDevice(
        val path: String,
        val name: String?,
        val vendor: String?,
        val product: String?,
        val capabilities: Set<String>,
        val directInput: Boolean
    ) {
        val supportsPenButtons: Boolean
            get() =
                "BTN_STYLUS" in capabilities &&
                    "BTN_STYLUS2" in capabilities

        private val matchScore: Int
            get() =
                listOf(
                    "ABS_PRESSURE",
                    "ABS_X",
                    "ABS_Y",
                    "ABS_TILT_X",
                    "ABS_TILT_Y"
                ).count { it in capabilities } +
                    if (directInput) 2 else 0

        internal fun score(): Int = matchScore
    }

    fun findPenDevice(): InputDevice {
        val result = ShellProcessRunner.runBlocking(
            arrayOf("/system/bin/getevent", "-il")
        )
        check(result.exitCode == 0) {
            result.stderr.ifEmpty {
                "入力デバイス一覧の取得に失敗しました（終了コード: ${result.exitCode}）"
            }
        }

        return selectPenDevice(parse(result.stdout))
    }

    internal fun parse(output: String): List<InputDevice> {
        val blocks = mutableListOf<MutableDevice>()
        var current: MutableDevice? = null

        output.lineSequence().forEach { line ->
            DEVICE_HEADER.matchEntire(line.trim())?.let { match ->
                val device = MutableDevice(path = match.groupValues[1])
                current = device
                blocks += device
                return@forEach
            }

            val device = current ?: return@forEach
            val trimmed = line.trim()

            when {
                trimmed.startsWith("name:") ->
                    device.name = quotedValue(trimmed)

                trimmed.startsWith("vendor") ->
                    device.vendor = trimmed.substringAfter("vendor").trim()

                trimmed.startsWith("product") ->
                    device.product = trimmed.substringAfter("product").trim()

                trimmed == "INPUT_PROP_DIRECT" ->
                    device.directInput = true
            }

            CAPABILITY.findAll(trimmed).forEach {
                device.capabilities += it.value
            }
        }

        return blocks.map(MutableDevice::toInputDevice)
    }

    internal fun selectPenDevice(devices: List<InputDevice>): InputDevice {
        val candidates = devices.filter(InputDevice::supportsPenButtons)
        check(candidates.isNotEmpty()) {
            "BTN_STYLUS / BTN_STYLUS2を持つペンデバイスが見つかりません"
        }

        val bestScore = candidates.maxOf(InputDevice::score)
        val best = candidates.filter { it.score() == bestScore }
        check(best.size == 1) {
            "ペンデバイスを一意に特定できません: ${
                best.joinToString { it.path }
            }"
        }
        return best.single()
    }

    private fun quotedValue(line: String): String? {
        return QUOTED_VALUE.find(line)?.groupValues?.get(1)
    }

    private data class MutableDevice(
        val path: String,
        var name: String? = null,
        var vendor: String? = null,
        var product: String? = null,
        val capabilities: MutableSet<String> = mutableSetOf(),
        var directInput: Boolean = false
    ) {
        fun toInputDevice() = InputDevice(
            path = path,
            name = name,
            vendor = vendor,
            product = product,
            capabilities = capabilities,
            directInput = directInput
        )
    }

    private val DEVICE_HEADER =
        Regex("""add device \d+:\s+(/dev/input/event\d+)""")
    private val QUOTED_VALUE = Regex(""""([^"]*)"""")
    private val CAPABILITY = Regex(
        """\b(?:BTN_[A-Z0-9_]+|ABS_[A-Z0-9_]+)\b"""
    )
}
