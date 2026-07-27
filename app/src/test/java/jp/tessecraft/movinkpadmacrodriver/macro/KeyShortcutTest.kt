package jp.tessecraft.movinkpadmacrodriver.macro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyShortcutTest {

    @Test
    fun createsSingleKeyCommand() {
        val shortcut = KeyShortcut(
            modifiers = emptyList(),
            key = "KEYCODE_C"
        )

        assertEquals("C", shortcut.displayName)
        assertArrayEquals(
            arrayOf("/system/bin/input", "keyevent", "KEYCODE_C"),
            shortcut.toInputCommand()
        )
    }

    @Test
    fun createsKeyCombinationCommandInModifierOrder() {
        val shortcut = KeyShortcut(
            modifiers = listOf(
                "KEYCODE_CTRL_LEFT",
                "KEYCODE_SHIFT_LEFT"
            ),
            key = "KEYCODE_Z"
        )

        assertEquals("Ctrl+Shift+Z", shortcut.displayName)
        assertArrayEquals(
            arrayOf(
                "/system/bin/input",
                "keycombination",
                "KEYCODE_CTRL_LEFT",
                "KEYCODE_SHIFT_LEFT",
                "KEYCODE_Z"
            ),
            shortcut.toInputCommand()
        )
    }
}
