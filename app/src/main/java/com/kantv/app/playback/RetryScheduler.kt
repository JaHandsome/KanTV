package com.kantv.app.playback

import android.os.Handler
import android.os.Looper

interface RetryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancelAll()
}

class MainThreadRetryScheduler : RetryScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableSetOf<Runnable>()

    override fun schedule(delayMs: Long, action: () -> Unit) {
        lateinit var callback: Runnable
        callback = Runnable {
            callbacks.remove(callback)
            action()
        }
        callbacks += callback
        handler.postDelayed(callback, delayMs)
    }

    override fun cancelAll() {
        callbacks.forEach(handler::removeCallbacks)
        callbacks.clear()
    }
}
