package com.rendyhd.vicu.util

import android.util.Log

actual object Logger {
    actual fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    actual fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    actual fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    actual fun e(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) {
            Log.e(tag, msg, tr)
        } else {
            Log.e(tag, msg)
        }
    }
}
