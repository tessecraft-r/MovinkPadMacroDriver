package jp.tessecraft.movinkpadmacrodriver.input

data class LinuxInputEvent(
    val type: Int,
    val code: Int,
    val value: Int
) {
    val isPressed: Boolean
        get() = value != 0

    companion object {
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val SYN_REPORT = 0x00
        const val BTN_STYLUS = 0x14b
        const val BTN_STYLUS2 = 0x14c
    }
}
