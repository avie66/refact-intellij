package com.smallcloud.codify.listeners

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.smallcloud.codify.modes.ModeProvider

class GlobalCaretListener : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) {
        Logger.getInstance("CaretListener").debug("caretPositionChanged")
        val provider = ModeProvider.getOrCreateModeProvider(event.editor)
        provider.onCaretChange(event)
    }
}
