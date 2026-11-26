package io.github.selclip.service

import android.util.Log

/**
 * Opt-in runtime diagnostic logger controlled strictly by Android's native log tag property:
 *   adb shell setprop log.tag.SelDiag DEBUG   (enable)
 *   adb shell setprop log.tag.SelDiag WARN    (disable)
 *
 * Completely silent and zero-allocation during normal operation in both Debug and Release APKs.
 */
object DiagLog {
    const val TAG = "SelDiag"

    val enabled: Boolean
        get() = Log.isLoggable(TAG, Log.DEBUG)

    inline fun d(msg: () -> String) {
        if (enabled) {
            Log.d(TAG, msg())
        }
    }
}
