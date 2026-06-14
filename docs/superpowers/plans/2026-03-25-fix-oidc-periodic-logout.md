# Fix OIDC Periodic Logout — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate periodic logouts for OIDC users by ensuring a backup API token always exists as a safety net, and adding diagnostic logging to the auth lifecycle.

**Architecture:** The root cause is that `createBackupApiToken()` runs once during setup, fails silently, and is never retried. Without it, users rely solely on JWT + refresh token — when the refresh token expires (every few days), they're logged out. The fix adds a self-healing check in `AuthManager.initialize()` that detects a missing API token and creates one while the user still has a valid session. A periodic `TokenRefreshWorker` keeps the refresh token alive between app opens.

**Tech Stack:** Kotlin, Hilt, WorkManager, DataStore, OkHttp, Retrofit

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `auth/AuthManager.kt` | Modify | Add `ensureBackupApiToken()`, diagnostic logging in `initialize()` |
| `auth/SecureTokenStorage.kt` | Modify | Add `hasApiToken()` non-decrypting check |
| `worker/TokenRefreshWorker.kt` | Create | WorkManager job for periodic token refresh |
| `worker/TokenRefreshScheduler.kt` | Create | Schedule/cancel the periodic refresh |
| `ui/screens/setup/SetupViewModel.kt` | Modify | Verify API token after creation, remove duplicated logic |
| `MainActivity.kt` | Modify | Schedule token refresh worker on authenticated start |

---

### Task 1: Add `hasApiToken()` to SecureTokenStorage

A lightweight check that avoids decryption — just checks if the encrypted value exists in DataStore.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/auth/SecureTokenStorage.kt`

- [ ] **Step 1: Add `hasApiToken()` method**

In `SecureTokenStorage.kt`, add after the existing `getApiTokenExpiry()` method (after line 174):

```kotlin
/**
 * Quick check whether an API token is stored (without decrypting).
 * Used by AuthManager to decide whether to attempt backup token creation.
 */
suspend fun hasApiToken(): Boolean {
    val prefs = context.authDataStore.data.first()
    return prefs[Keys.API_TOKEN] != null
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/auth/SecureTokenStorage.kt
git commit -m "Add hasApiToken() check to SecureTokenStorage"
```

---

### Task 2: Add diagnostic logging and backup API token self-healing to AuthManager

This is the core fix. `initialize()` gets logging at every decision point, and a new `ensureBackupApiToken()` method that fires when authenticated but missing an API token.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/auth/AuthManager.kt`

- [ ] **Step 1: Add `ensureBackupApiToken()` method**

Add this method after `performV2Refresh()` (after line 304), before `isExpired()`:

```kotlin
/**
 * If authenticated but no backup API token exists, create one.
 * This self-heals the case where initial token creation failed during setup.
 * Runs in appScope so it doesn't block initialize().
 */
private fun ensureBackupApiToken() {
    appScope.launch {
        try {
            if (tokenStorage.hasApiToken()) return@launch

            Log.w(TAG, "No backup API token found — attempting to create one")
            val expiry = java.time.Instant.now().plusSeconds(365L * 24 * 60 * 60)
            val expiryStr = java.time.format.DateTimeFormatter.ISO_INSTANT.format(expiry)
            val request = com.rendyhd.vicu.data.remote.api.ApiTokenRequestDto(
                title = "Vicu Android Backup",
                expiresAt = expiryStr,
            )
            val response = apiServiceProvider.get().createApiToken(request)
            if (response.token.isNotBlank()) {
                tokenStorage.storeApiToken(response.token, expiry.epochSecond)
                Log.i(TAG, "Backup API token created successfully (self-healed)")
            } else {
                Log.w(TAG, "Backup API token creation returned empty token")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backup API token self-heal failed (will retry next launch)", e)
        }
    }
}
```

- [ ] **Step 2: Add diagnostic logging to `initialize()` and call `ensureBackupApiToken()`**

Replace the `initialize()` method body (lines 79-133) with this version that adds logging at every branch:

```kotlin
suspend fun initialize() {
    try {
        val url = tokenStorage.getVikunjaUrl()
        if (url.isNullOrBlank()) {
            Log.d(TAG, "initialize: no Vikunja URL stored → Unauthenticated")
            _authState.value = AuthState.Unauthenticated
            isInitialized = true
            return
        }

        isServerV2Cached = tokenStorage.getServerIsV2()

        val jwt = tokenStorage.getJwt()
        val jwtExpiry = tokenStorage.getJwtExpiry()
        val apiToken = tokenStorage.getApiToken()
        val hasRefresh = tokenStorage.getRefreshToken() != null

        Log.d(TAG, "initialize: jwt=${jwt != null}, jwtExpired=${jwt != null && isExpired(jwtExpiry)}, apiToken=${apiToken != null}, refreshToken=$hasRefresh, isV2=$isServerV2Cached")

        when {
            jwt != null && !isExpired(jwtExpiry) -> {
                Log.d(TAG, "initialize: JWT valid → Authenticated")
                cachedToken = jwt
                cachedJwtExpiry = jwtExpiry
                _authState.value = AuthState.Authenticated
                if (isServerV2Cached) {
                    scheduleProactiveRefresh()
                }
                ensureBackupApiToken()
            }
            apiToken != null -> {
                Log.d(TAG, "initialize: JWT missing/expired, using API token → Authenticated")
                cachedToken = apiToken
                _authState.value = AuthState.Authenticated
            }
            jwt != null -> {
                // JWT expired, no API token — try quick refresh or force re-auth
                Log.d(TAG, "initialize: JWT expired, no API token — attempting V2 refresh")
                cachedToken = jwt
                cachedJwtExpiry = jwtExpiry
                val refreshed = if (isServerV2Cached) {
                    performV2Refresh()
                } else {
                    false // Legacy renewal requires a valid JWT — can't auto-recover
                }
                if (refreshed) {
                    Log.d(TAG, "initialize: V2 refresh succeeded → Authenticated")
                    _authState.value = AuthState.Authenticated
                    scheduleProactiveRefresh()
                    ensureBackupApiToken()
                } else {
                    Log.w(TAG, "initialize: V2 refresh failed, no API token → NeedsReAuth")
                    cachedToken = null
                    _authState.value = AuthState.NeedsReAuth
                }
            }
            else -> {
                Log.d(TAG, "initialize: no tokens at all → Unauthenticated")
                _authState.value = AuthState.Unauthenticated
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "initialize() failed — tokens may be corrupted, forcing re-auth", e)
        _authState.value = AuthState.Unauthenticated
    }
    isInitialized = true
}
```

- [ ] **Step 3: Add import for ApiTokenRequestDto**

At the top of `AuthManager.kt`, add:

```kotlin
import com.rendyhd.vicu.data.remote.api.ApiTokenRequestDto
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/auth/AuthManager.kt
git commit -m "Add API token self-healing and diagnostic logging to AuthManager

When initialize() detects the user is authenticated but has no backup
API token, it creates one asynchronously. This self-heals the case where
the initial token creation during setup failed silently.

Also adds logging at every decision point in initialize() so future
auth issues can be diagnosed from logcat."
```

---

### Task 3: Create TokenRefreshWorker

A WorkManager periodic job that keeps the refresh token alive even when the app process is dead. This prevents the "refresh token expired between app opens" failure mode.

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/worker/TokenRefreshWorker.kt`

- [ ] **Step 1: Create the worker**

```kotlin
package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.SecureTokenStorage
import com.rendyhd.vicu.data.remote.interceptor.BaseUrlHolder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic WorkManager job that refreshes the JWT token.
 * Keeps the refresh token alive even when the app process is dead,
 * preventing "refresh token expired between app opens" logouts.
 */
@HiltWorker
class TokenRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authManager: AuthManager,
    private val tokenStorage: SecureTokenStorage,
    private val baseUrlHolder: BaseUrlHolder,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TokenRefreshWorker"
    }

    override suspend fun doWork(): Result {
        // Ensure base URL is set (cold start)
        val url = tokenStorage.getVikunjaUrl()
        if (url.isNullOrBlank()) {
            Log.d(TAG, "No Vikunja URL configured, skipping refresh")
            return Result.success()
        }
        baseUrlHolder.ensureInitialized(url)

        // Only refresh if we have a refresh token
        val hasRefreshToken = tokenStorage.getRefreshToken() != null
        if (!hasRefreshToken) {
            Log.d(TAG, "No refresh token available, skipping")
            return Result.success()
        }

        // Ensure AuthManager is initialized
        authManager.ensureInitializedAndGetToken()

        return try {
            val success = authManager.withRefreshLock {
                authManager.performV2Refresh()
            }
            if (success) {
                Log.d(TAG, "Periodic token refresh succeeded")
                Result.success()
            } else {
                Log.w(TAG, "Periodic token refresh failed, will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Periodic token refresh exception", e)
            Result.retry()
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/worker/TokenRefreshWorker.kt
git commit -m "Add TokenRefreshWorker for periodic JWT refresh via WorkManager"
```

---

### Task 4: Create TokenRefreshScheduler

Manages scheduling/cancelling the periodic refresh worker.

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/worker/TokenRefreshScheduler.kt`

- [ ] **Step 1: Create the scheduler**

```kotlin
package com.rendyhd.vicu.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object TokenRefreshScheduler {

    private const val TAG = "TokenRefreshScheduler"
    private const val WORK_NAME = "token_refresh_periodic"

    /**
     * Schedule periodic token refresh every 6 hours.
     * Requires network. Uses KEEP policy so it doesn't reset the timer
     * if already scheduled.
     */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<TokenRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "Periodic token refresh scheduled (every 6 hours)")
    }

    /**
     * Cancel periodic refresh (e.g., on logout).
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.d(TAG, "Periodic token refresh cancelled")
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/worker/TokenRefreshScheduler.kt
git commit -m "Add TokenRefreshScheduler for periodic background token refresh"
```

---

### Task 5: Wire up TokenRefreshScheduler and integrate with auth lifecycle

Schedule the worker when authenticated, cancel on logout. Also check if `BaseUrlHolder` has the `ensureInitialized` method the worker needs.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/MainActivity.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/auth/AuthManager.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/data/remote/interceptor/BaseUrlHolder.kt`

- [ ] **Step 1: Add `ensureInitialized()` to BaseUrlHolder**

Read `BaseUrlHolder.kt` first to check its current API. If it doesn't have an `ensureInitialized(url)` method, add one:

```kotlin
fun ensureInitialized(url: String) {
    if (baseUrl.isBlank()) {
        baseUrl = url
    }
}
```

- [ ] **Step 2: Schedule token refresh in MainActivity after auth init**

In `MainActivity.kt`, inside the `lifecycleScope.launch` block in `onCreate()` (line 52-58), add the scheduling call after `authManager.initialize()`:

```kotlin
lifecycleScope.launch {
    val storedUrl = authManager.getVikunjaUrl()
    if (!storedUrl.isNullOrBlank()) {
        baseUrlHolder.baseUrl = storedUrl
    }
    authManager.initialize()
    // Schedule periodic token refresh if authenticated
    if (authManager.authState.value == com.rendyhd.vicu.auth.AuthState.Authenticated) {
        TokenRefreshScheduler.schedule(this@MainActivity)
    }
}
```

Add import at top:
```kotlin
import com.rendyhd.vicu.worker.TokenRefreshScheduler
```

- [ ] **Step 3: Cancel token refresh on logout**

In `AuthManager.logout()` (line 222-236), add cancellation. This requires an `appContext` reference. Since `AuthManager` doesn't have a Context, instead add the cancel to `SettingsViewModel.logout()`.

In `SettingsViewModel.kt`, inside `logout()` (around line 222), add before `authManager.logout()`:

```kotlin
fun logout() {
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        database.clearAllTables()
        customListStore.clear()
        bottomBarPrefsStore.clear()
        TokenRefreshScheduler.cancel(appContext)
        authManager.logout() // calls POST /user/logout internally
    }
}
```

Add import:
```kotlin
import com.rendyhd.vicu.worker.TokenRefreshScheduler
```

Note: `SettingsViewModel` already has `@ApplicationContext appContext: Context` — verify this by reading the constructor. If it doesn't have a Context, inject one.

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/MainActivity.kt \
       app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsViewModel.kt \
       app/src/main/java/com/rendyhd/vicu/data/remote/interceptor/BaseUrlHolder.kt
git commit -m "Wire up TokenRefreshScheduler: schedule on auth, cancel on logout"
```

---

### Task 6: Clean up SetupViewModel's backup token creation

Now that `AuthManager.ensureBackupApiToken()` handles retry, `SetupViewModel.createBackupApiToken()` should verify success instead of silently ignoring failures.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/setup/SetupViewModel.kt`

- [ ] **Step 1: Add logging to `createBackupApiToken()`**

Replace the existing `createBackupApiToken()` method (lines 297-312) with:

```kotlin
private suspend fun createBackupApiToken() {
    try {
        val expiry = Instant.now().plusSeconds(365L * 24 * 60 * 60)
        val expiryStr = DateTimeFormatter.ISO_INSTANT.format(expiry)
        val request = ApiTokenRequestDto(
            title = "Vicu Android Backup",
            expiresAt = expiryStr,
        )
        val response = apiService.get().createApiToken(request)
        if (response.token.isNotBlank()) {
            authManager.onApiTokenSaved(response.token, expiry.epochSecond)
            Log.i(TAG, "Backup API token created successfully during setup")
        } else {
            Log.w(TAG, "Backup API token creation returned empty token — AuthManager will retry on next launch")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Backup API token creation failed during setup — AuthManager will retry on next launch", e)
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/setup/SetupViewModel.kt
git commit -m "Improve backup API token logging in SetupViewModel

Now logs success explicitly and notes that AuthManager will self-heal
on next launch if creation fails."
```

---

### Task 7: Add logging to TokenAuthenticator for diagnostics

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/remote/interceptor/TokenAuthenticator.kt`

- [ ] **Step 1: Add logging at key decision points**

In `authenticate()` (line 31), add logging after the refresh lock is acquired and at the "all options exhausted" point. Replace the method body inside `withRefreshLock`:

```kotlin
return runBlocking {
    withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
        authManager.withRefreshLock {
            // Check if another thread already refreshed the token
            val currentToken = authManager.getBestTokenSync()
            val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentToken != null && currentToken != failedToken) {
                Log.d(TAG, "Another thread already refreshed the token")
                return@withRefreshLock response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            // Try refresh based on server version
            if (authManager.isServerV2Cached) {
                val v2Result = tryV2Refresh(response)
                if (v2Result != null) {
                    Log.d(TAG, "V2 refresh succeeded in authenticator")
                    return@withRefreshLock v2Result
                }
                Log.d(TAG, "V2 refresh failed in authenticator")
            } else {
                val legacyResult = tryLegacyRefresh(response)
                if (legacyResult != null) {
                    Log.d(TAG, "Legacy refresh succeeded in authenticator")
                    return@withRefreshLock legacyResult
                }
                Log.d(TAG, "Legacy refresh failed in authenticator")
            }

            // Fall back to API token
            val apiToken = authManager.getBestToken()
            if (apiToken != null && apiToken != failedToken) {
                Log.d(TAG, "Falling back to API token in authenticator")
                return@withRefreshLock response.request.newBuilder()
                    .header("Authorization", "Bearer $apiToken")
                    .build()
            }

            // All options exhausted
            Log.w(TAG, "All token options exhausted — apiToken=${apiToken != null}, sameAsFailed=${apiToken == failedToken}")
            authManager.setNeedsReAuth()
            null
        }
    }
}
```

- [ ] **Step 2: Add non-debug Log import** (remove BuildConfig guards since these are important diagnostic logs)

The existing code guards logs with `if (BuildConfig.DEBUG)`. Since these auth logs are critical for diagnosing production issues, use `Log.d/Log.w` directly (they're already stripped by ProGuard in release builds if configured, and even if not, auth diagnostics are worth having).

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/data/remote/interceptor/TokenAuthenticator.kt
git commit -m "Add diagnostic logging to TokenAuthenticator refresh flow"
```

---

### Task 8: Final integration test

- [ ] **Step 1: Build the complete app**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run existing unit tests**

Run: `./gradlew test 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 3: Verify via logcat on device**

Install on device/emulator and verify the auth logging appears:
```bash
./gradlew installDebug
adb logcat -s AuthManager TokenAuthenticator TokenRefreshWorker TokenRefreshScheduler SetupViewModel
```

Expected log output on app open (authenticated user):
```
D/AuthManager: initialize: jwt=true, jwtExpired=false, apiToken=true, refreshToken=true, isV2=true
D/AuthManager: initialize: JWT valid → Authenticated
D/TokenRefreshScheduler: Periodic token refresh scheduled (every 6 hours)
```

Or for a user missing the API token:
```
D/AuthManager: initialize: jwt=true, jwtExpired=false, apiToken=false, refreshToken=true, isV2=true
D/AuthManager: initialize: JWT valid → Authenticated
W/AuthManager: No backup API token found — attempting to create one
I/AuthManager: Backup API token created successfully (self-healed)
```

- [ ] **Step 4: Commit all remaining changes**

```bash
git status  # Verify no uncommitted changes
```
