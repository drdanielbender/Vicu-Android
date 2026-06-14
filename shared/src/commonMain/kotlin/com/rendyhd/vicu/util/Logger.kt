package com.rendyhd.vicu.util

expect object Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable? = null)
}
