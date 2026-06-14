package com.rendyhd.vicu.auth

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual object AuthDebugLog {

    private const val TAG = "AuthDebugLog"
    private const val FILE_NAME = "auth_debug.log"
    private const val MAX_LINES = 500

    @Volatile
    private var logFile: File? = null

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    actual fun init(context: Any?) {
        if (context is Context) {
            logFile = File(context.filesDir, FILE_NAME)
        }
    }

    actual fun log(event: String) {
        val timestamp = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        val line = "$timestamp [$thread] $event"
        Log.d(TAG, line)
        appendLine(line)
    }

    actual fun log(event: String, detail: String) {
        val timestamp = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        val line = "$timestamp [$thread] $event | $detail"
        Log.d(TAG, line)
        appendLine(line)
    }

    actual fun logError(event: String, error: Throwable) {
        val timestamp = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        val line = "$timestamp [$thread] ERROR $event | ${error::class.simpleName}: ${error.message}"
        Log.w(TAG, line, error)
        appendLine(line)
    }

    actual fun readLog(): String {
        val file = logFile ?: return "(logger not initialized)"
        return try {
            if (file.exists()) file.readText() else "(no log entries yet)"
        } catch (e: Exception) {
            "(error reading log: ${e.message})"
        }
    }

    actual fun clear() {
        try {
            logFile?.writeText("")
        } catch (_: Exception) {}
    }

    private fun appendLine(line: String) {
        val file = logFile ?: return
        try {
            file.appendText(line + "\n")
            trimIfNeeded(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write auth debug log", e)
        }
    }

    private fun trimIfNeeded(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                val trimmed = lines.takeLast(MAX_LINES)
                file.writeText(trimmed.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    actual fun tokenState(jwt: Boolean, jwtExpired: Boolean, apiToken: Boolean, refreshToken: Boolean, isV2: Boolean) {
        log("TOKEN_STATE", "jwt=$jwt jwtExpired=$jwtExpired apiToken=$apiToken refreshToken=$refreshToken isV2=$isV2")
    }

    actual fun authStateChanged(old: String, new: String, reason: String) {
        log("AUTH_STATE_CHANGE", "$old → $new ($reason)")
    }

    actual fun jwtExpiry(expiryEpoch: Long) {
        val now = System.currentTimeMillis() / 1000
        val remaining = expiryEpoch - now
        val expiryTime = dateFormat.format(Date(expiryEpoch * 1000))
        log("JWT_EXPIRY", "expires=$expiryTime remaining=${remaining}s (${remaining / 60}min)")
    }

    actual fun refreshAttempt(method: String) {
        log("REFRESH_ATTEMPT", method)
    }

    actual fun refreshResult(method: String, success: Boolean, detail: String) {
        log("REFRESH_RESULT", "$method success=$success $detail".trim())
    }

    actual fun lifecycle(event: String) {
        log("LIFECYCLE", event)
    }

    actual fun interceptor(event: String, path: String) {
        log("INTERCEPTOR", "$event path=$path")
    }
}
