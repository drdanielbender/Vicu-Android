package com.rendyhd.vicu.util

import platform.Foundation.NSLog

actual object Logger {
    actual fun d(tag: String, msg: String) {
        NSLog("[$tag] DEBUG: $msg")
    }

    actual fun i(tag: String, msg: String) {
        NSLog("[$tag] INFO: $msg")
    }

    actual fun w(tag: String, msg: String) {
        NSLog("[$tag] WARN: $msg")
    }

    actual fun e(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) {
            NSLog("[$tag] ERROR: $msg - ${tr.message}\n${tr.stackTraceToString()}")
        } else {
            NSLog("[$tag] ERROR: $msg")
        }
    }
}
