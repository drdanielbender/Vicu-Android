package com.rendyhd.vicu.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

actual class AtomicLong actual constructor(initialValue: Long) {
    private val lock = SynchronizedObject()
    private var value = initialValue

    actual fun get(): Long = synchronized(lock) { value }
    
    actual fun set(newValue: Long) = synchronized(lock) {
        value = newValue
    }
    
    actual fun getAndIncrement(): Long = synchronized(lock) {
        val current = value
        value++
        current
    }
    
    actual fun decrementAndGet(): Long = synchronized(lock) {
        value--
        value
    }
}
