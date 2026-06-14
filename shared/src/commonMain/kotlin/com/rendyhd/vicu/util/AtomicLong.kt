package com.rendyhd.vicu.util

expect class AtomicLong(initialValue: Long) {
    fun get(): Long
    fun set(newValue: Long)
    fun getAndIncrement(): Long
    fun decrementAndGet(): Long
}
