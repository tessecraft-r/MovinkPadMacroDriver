package jp.tessecraft.movinkpadmacrodriver.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDeviceScannerTest {

    @Test
    fun selectsDeviceThatHasBothPenButtons() {
        val output = """
            add device 1: /dev/input/event7
              vendor    0531
              product   0405
              name:     "hid-over-i2c 0107"
              events:
                KEY (0001): BTN_MISC
                ABS (0003): ABS_MISC
            add device 2: /dev/input/event12
              vendor    0531
              product   0405
              name:     "hid-over-i2c 0107"
              events:
                KEY (0001): BTN_TOUCH BTN_STYLUS BTN_STYLUS2
                ABS (0003): ABS_X ABS_Y ABS_PRESSURE ABS_TILT_X ABS_TILT_Y
              input props:
                INPUT_PROP_DIRECT
        """.trimIndent()

        val devices = InputDeviceScanner.parse(output)
        val selected = InputDeviceScanner.selectPenDevice(devices)

        assertEquals("/dev/input/event12", selected.path)
        assertEquals("hid-over-i2c 0107", selected.name)
        assertTrue(selected.supportsPenButtons)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsListWithoutBothPenButtons() {
        val output = """
            add device 1: /dev/input/event3
              name:     "touchscreen"
              events:
                KEY (0001): BTN_TOUCH
                ABS (0003): ABS_X ABS_Y
        """.trimIndent()

        InputDeviceScanner.selectPenDevice(
            InputDeviceScanner.parse(output)
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsEquallySuitableDevicesInsteadOfGuessing() {
        val output = """
            add device 1: /dev/input/event6
              events:
                KEY (0001): BTN_STYLUS BTN_STYLUS2
                ABS (0003): ABS_X ABS_Y ABS_PRESSURE
            add device 2: /dev/input/event9
              events:
                KEY (0001): BTN_STYLUS BTN_STYLUS2
                ABS (0003): ABS_X ABS_Y ABS_PRESSURE
        """.trimIndent()

        InputDeviceScanner.selectPenDevice(
            InputDeviceScanner.parse(output)
        )
    }
}
