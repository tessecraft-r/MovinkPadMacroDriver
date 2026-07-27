package jp.tessecraft.movinkpadmacrodriver.macro

import android.content.Context
import androidx.core.content.edit

enum class PenButtonTrigger(
    val displayName: String
) {
    BUTTON_1("BTN_STYLUS"),
    BUTTON_2("BTN_STYLUS2"),
    SIMULTANEOUS("同時押し")
}

data class KeyShortcut(
    val modifiers: List<String>,
    val key: String
) {
    init {
        require(key.startsWith(KEYCODE_PREFIX)) {
            "キーコードはKEYCODE_で始まる必要があります: $key"
        }
        require(modifiers.all { it.startsWith(KEYCODE_PREFIX) }) {
            "修飾キーはKEYCODE_で始まる必要があります: $modifiers"
        }
    }

    val displayName: String
        get() = (modifiers.map(::keyCodeDisplayName) + keyCodeDisplayName(key))
            .joinToString("+")

    fun toInputCommand(): Array<String> {
        return if (modifiers.isEmpty()) {
            arrayOf(
                "/system/bin/input",
                "keyevent",
                key
            )
        } else {
            (
                listOf(
                    "/system/bin/input",
                    "keycombination"
                ) + modifiers + key
                ).toTypedArray()
        }
    }

    private companion object {
        const val KEYCODE_PREFIX = "KEYCODE_"

        fun keyCodeDisplayName(keyCode: String): String {
            return when (keyCode) {
                "KEYCODE_CTRL_LEFT", "KEYCODE_CTRL_RIGHT" -> "Ctrl"
                "KEYCODE_SHIFT_LEFT", "KEYCODE_SHIFT_RIGHT" -> "Shift"
                "KEYCODE_ALT_LEFT", "KEYCODE_ALT_RIGHT" -> "Alt"
                "KEYCODE_META_LEFT", "KEYCODE_META_RIGHT" -> "Meta"
                else -> keyCode.removePrefix(KEYCODE_PREFIX)
            }
        }
    }
}

data class KeyAssignments(
    val button1: KeyShortcut,
    val button2: KeyShortcut,
    val simultaneous: KeyShortcut
) {
    operator fun get(trigger: PenButtonTrigger): KeyShortcut {
        return when (trigger) {
            PenButtonTrigger.BUTTON_1 -> button1
            PenButtonTrigger.BUTTON_2 -> button2
            PenButtonTrigger.SIMULTANEOUS -> simultaneous
        }
    }

    companion object {
        val DEFAULT = KeyAssignments(
            button1 = KeyShortcut(
                modifiers = listOf("KEYCODE_CTRL_LEFT"),
                key = "KEYCODE_Z"
            ),
            button2 = KeyShortcut(
                modifiers = listOf("KEYCODE_CTRL_LEFT"),
                key = "KEYCODE_Y"
            ),
            simultaneous = KeyShortcut(
                modifiers = emptyList(),
                key = "KEYCODE_C"
            )
        )
    }
}

class KeyAssignmentStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): KeyAssignments {
        return KeyAssignments(
            button1 = loadShortcut("button_1", KeyAssignments.DEFAULT.button1),
            button2 = loadShortcut("button_2", KeyAssignments.DEFAULT.button2),
            simultaneous = loadShortcut(
                "simultaneous",
                KeyAssignments.DEFAULT.simultaneous
            )
        )
    }

    fun save(assignments: KeyAssignments) {
        preferences.edit {
            putShortcut("button_1", assignments.button1)
            putShortcut("button_2", assignments.button2)
            putShortcut("simultaneous", assignments.simultaneous)
        }
    }

    fun reset() {
        preferences.edit { clear() }
    }

    private fun loadShortcut(
        prefix: String,
        default: KeyShortcut
    ): KeyShortcut {
        return runCatching {
            KeyShortcut(
                modifiers = preferences
                    .getString("${prefix}_modifiers", null)
                    ?.split(MODIFIER_SEPARATOR)
                    ?.filter(String::isNotEmpty)
                    ?: default.modifiers,
                key = preferences.getString("${prefix}_key", default.key)
                    ?: default.key
            )
        }.getOrDefault(default)
    }

    private fun android.content.SharedPreferences.Editor.putShortcut(
        prefix: String,
        shortcut: KeyShortcut
    ): android.content.SharedPreferences.Editor {
        return putString(
            "${prefix}_modifiers",
            shortcut.modifiers.joinToString(MODIFIER_SEPARATOR)
        ).putString("${prefix}_key", shortcut.key)
    }

    private companion object {
        const val PREFERENCES_NAME = "key_assignments"
        const val MODIFIER_SEPARATOR = "|"
    }
}
