package com.kantv.app.ui

/** Accepts a TV remote whose first directional DOWN may already have repeatCount=1. */
internal class TvDirectionalKeyGate {
    private var downKey: Int? = null

    fun shouldHandleDown(keyCode: Int, repeatCount: Int): Boolean {
        if (repeatCount == 0 || downKey != keyCode) {
            downKey = keyCode
            return true
        }
        return false
    }

    fun release(keyCode: Int) {
        if (downKey == keyCode) downKey = null
    }
}
