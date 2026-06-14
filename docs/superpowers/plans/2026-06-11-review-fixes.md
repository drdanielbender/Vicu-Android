# Comprehensive Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the P0/P1/P2 findings (plus cheap P3s) from `docs/reviews/2026-06-11-comprehensive-review.md` — offline-queue data loss, task-detail state corruption, dead custom-list settings, frozen day boundaries, snooze loss, sync hygiene, and small polish.

**Architecture:** All fixes are surgical changes inside the existing MVVM/offline-first structure: Room stays the source of truth, the PendingAction queue gains create-merging semantics, ViewModels get proper flow cancellation (`flatMapLatest` / tracked Jobs), and AlarmScheduler separates the reminder sweep from snooze lifecycle. No schema changes (Room stays at version 1 — none of these tasks add entities or columns; the new snooze persistence uses DataStore, not Room).

**Tech Stack:** Kotlin 2.1, Jetpack Compose, Room (KSP), Hilt, WorkManager, DataStore, kotlinx-serialization, JUnit4 unit tests.

---

## Context for a zero-context engineer

- Repo: `C:\Users\rendy\vscode\vicu-android`. Work on the current branch `beta3-implementation`. Commit after every task; plain-text commit messages, no emojis.
- Every gradle command needs: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (bash) first.
- Build gates: `./gradlew compileDebugKotlin` (compile), `./gradlew testDebugUnitTest` (JVM unit tests), `./gradlew assembleDebug` (full build). There is NO ktlint task in this project — ignore CLAUDE.md's mention of it.
- Unit tests live under `app/src/test/java/com/rendyhd/vicu/...` (JUnit4, plain `org.junit.Assert`).
- Vikunja API conventions: PUT = create, POST = update. Task updates must always send the COMPLETE task object (Go zero-value problem). Null date sentinel is `0001-01-01T00:00:00Z`.
- The review report (`docs/reviews/2026-06-11-comprehensive-review.md`) is the spec; finding IDs (NEW-x, PERF-x, WIRE-x, PAR-x) below refer to it.

**Out of scope** (deliberately not in this plan): PAR-1/PAR-2 cross-platform semantic alignment (needs a product decision plus desktop-side changes), the remaining swipe-layer UX pass (needs device-feel tuning), incremental sync (PERF-1's larger half), NEW-11 assignees field (needs server verification), PAR-5/PAR-7 cosmetic labels, NEW-14 pull-to-refresh feedback.

---

## Task 1: Offline queue — stop destroying pending creates (NEW-1, P0)

An offline-created task has a temp negative id and a queued `create` action. Any later edit/toggle/delete calls `replaceForEntity`, which deletes ALL pending rows for that entity — including the create — so the task never reaches the server. Fix: fold later edits into the create's payload; a delete of an unsynced create drops everything.

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/data/local/dao/QueueMerge.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/data/local/dao/PendingActionDao.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt` (queueTaskAction, ~line 83)
- Modify: `app/src/main/java/com/rendyhd/vicu/notification/NotificationActionReceiver.kt` (~line 90)
- Modify: `app/src/main/java/com/rendyhd/vicu/widget/ToggleTaskCallback.kt` (~line 95)
- Modify: `app/src/main/java/com/rendyhd/vicu/worker/SyncWorker.kt` ("create" branch, ~line 123)
- Test: `app/src/test/java/com/rendyhd/vicu/data/local/dao/QueueMergeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMergeTest {

    private fun action(id: Long, type: String, payload: String = "{}") = PendingActionEntity(
        id = id,
        entityType = "task",
        entityId = -42L,
        actionType = type,
        payload = payload,
        createdAt = "2026-06-11T10:00:00Z",
        updatedAt = "2026-06-11T10:00:00Z",
    )

    @Test
    fun `no pending create - replace rows for entity`() {
        val op = resolveTaskQueueMerge(listOf(action(1, "update")), "toggle_done", "{new}")
        assertEquals(QueueMergeOp.ReplaceForEntity, op)
    }

    @Test
    fun `empty queue - replace (plain insert path)`() {
        val op = resolveTaskQueueMerge(emptyList(), "update", "{new}")
        assertEquals(QueueMergeOp.ReplaceForEntity, op)
    }

    @Test
    fun `pending create plus update - fold payload into the create`() {
        val op = resolveTaskQueueMerge(listOf(action(7, "create", "{old}")), "update", "{new}")
        assertEquals(QueueMergeOp.UpdateCreatePayload(7, "{new}"), op)
    }

    @Test
    fun `pending create plus toggle_done - fold payload into the create`() {
        val op = resolveTaskQueueMerge(listOf(action(7, "create", "{old}")), "toggle_done", "{done}")
        assertEquals(QueueMergeOp.UpdateCreatePayload(7, "{done}"), op)
    }

    @Test
    fun `pending create plus delete - drop everything`() {
        val op = resolveTaskQueueMerge(listOf(action(7, "create", "{old}")), "delete", "")
        assertTrue(op is QueueMergeOp.DropAll)
    }
}
```

NOTE: check `PendingActionEntity`'s constructor (`app/src/main/java/com/rendyhd/vicu/data/local/entity/PendingActionEntity.kt`) — if `id` is not a constructor parameter with a default, adapt the helper (e.g. `.copy(id = id)` after construction). Keep the test's intent identical.

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.data.local.dao.QueueMergeTest"`
Expected: FAIL — unresolved reference `resolveTaskQueueMerge`.

- [ ] **Step 3: Create the pure merge-decision function**

Create `app/src/main/java/com/rendyhd/vicu/data/local/dao/QueueMerge.kt`:

```kotlin
package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.PendingActionEntity

sealed class QueueMergeOp {
    /** No pending create for this entity — normal dedup: replace its rows with the new action. */
    object ReplaceForEntity : QueueMergeOp()

    /** A pending create exists — fold the new full-task payload into the create row. */
    data class UpdateCreatePayload(val createActionId: Long, val newPayload: String) : QueueMergeOp()

    /** A pending create exists and the entity was deleted — the server never needs to know. */
    object DropAll : QueueMergeOp()
}

/**
 * Decide how to queue a non-create task action when the entity may already have a pending
 * "create" (an offline-created task with a temp id). Both create and update/toggle payloads
 * are the complete serialized Task, so folding is a straight payload swap; SyncWorker's
 * create replay handles the done flag separately (CreateTaskDto has no done field).
 */
fun resolveTaskQueueMerge(
    existing: List<PendingActionEntity>,
    actionType: String,
    payload: String,
): QueueMergeOp {
    val create = existing.firstOrNull { it.actionType == "create" }
        ?: return QueueMergeOp.ReplaceForEntity
    return when (actionType) {
        "update", "toggle_done" -> QueueMergeOp.UpdateCreatePayload(create.id, payload)
        "delete" -> QueueMergeOp.DropAll
        else -> QueueMergeOp.ReplaceForEntity
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.data.local.dao.QueueMergeTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Add the DAO query + transaction method**

In `PendingActionDao.kt`, add inside the interface (next to `replaceForEntity`):

```kotlin
@Query("SELECT * FROM pending_actions WHERE entityType = :entityType AND entityId = :entityId AND status IN ('pending', 'failed', 'processing')")
suspend fun getActiveByEntity(entityType: String, entityId: Long): List<PendingActionEntity>

@Transaction
suspend fun queueTaskActionMerging(action: PendingActionEntity) {
    val existing = getActiveByEntity(action.entityType, action.entityId)
    when (val op = resolveTaskQueueMerge(existing, action.actionType, action.payload)) {
        QueueMergeOp.ReplaceForEntity -> replaceForEntity(action.entityType, action.entityId, action)
        is QueueMergeOp.UpdateCreatePayload -> remapEntity(op.createActionId, action.entityId, op.newPayload, "pending")
        QueueMergeOp.DropAll -> deleteByEntity(action.entityType, action.entityId)
    }
}
```

(`remapEntity` and `deleteByEntity` already exist in this DAO. `QueueMergeOp`/`resolveTaskQueueMerge` are in the same package — no import needed.)

- [ ] **Step 6: Route all three call sites through the merging method**

In `TaskRepositoryImpl.queueTaskAction` (~line 92), replace:

```kotlin
        if (actionType == "create") {
            pendingActionDao.insert(action)
        } else {
            pendingActionDao.replaceForEntity("task", entityId, action)
        }
```

with:

```kotlin
        if (actionType == "create") {
            pendingActionDao.insert(action)
        } else {
            pendingActionDao.queueTaskActionMerging(action)
        }
```

In `NotificationActionReceiver.handleComplete` (~line 90), replace
`pendingActionDao.replaceForEntity("task", taskId, action)` with
`pendingActionDao.queueTaskActionMerging(action)`.

In `ToggleTaskCallback.onAction` (~line 95), replace
`pendingActionDao.replaceForEntity("task", taskId, action)` with
`pendingActionDao.queueTaskActionMerging(action)`.

- [ ] **Step 7: Replay the done flag after a create (folded toggle_done has done=true, but CreateTaskDto has no done field)**

In `SyncWorker.kt`, add `import com.rendyhd.vicu.util.DateUtils` to the imports, then in `processTaskAction`'s `"create"` branch, replace:

```kotlin
                taskDao.deleteById(action.entityId)
                taskDao.upsert(responseEntity)
                val created = with(taskMapper) { responseEntity.toDomain() }
                alarmScheduler.scheduleForTask(created)
```

with:

```kotlin
                taskDao.deleteById(action.entityId)
                taskDao.upsert(responseEntity)
                // A create that was completed while still offline carries done=true in its
                // folded payload; CreateTaskDto cannot express it, so replay it as a
                // follow-up complete-object update.
                var finalEntity = responseEntity
                if (task.done) {
                    val toggled = with(taskMapper) { responseEntity.toDomain() }.copy(
                        done = true,
                        doneAt = task.doneAt.ifBlank { DateUtils.nowIso() },
                    )
                    val doneDto = api.updateTask(responseEntity.id, with(taskMapper) { toggled.toDto() })
                    finalEntity = with(taskMapper) { doneDto.toEntity() }
                    taskDao.upsert(finalEntity)
                }
                val created = with(taskMapper) { finalEntity.toDomain() }
                if (created.done) {
                    alarmScheduler.cancelForTask(created.id)
                } else {
                    alarmScheduler.scheduleForTask(created)
                }
```

- [ ] **Step 8: Compile + full unit tests**

Run: `./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Fix offline queue destroying pending creates on later edits (review NEW-1)"
```

---

## Task 2: Dedup guard for create replays (NEW-19, P2)

A timeout is classified retriable, but the server may have committed the create before the client gave up — replay then duplicates the task.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/worker/SyncWorker.kt`

- [ ] **Step 1: Add the duplicate finder and use it in the create branch**

In `SyncWorker.kt`, add `import com.rendyhd.vicu.data.remote.api.TaskDto` to the imports. Add to the companion object:

```kotlin
        private const val DUPLICATE_WINDOW_SECS = 900L
```

Add this private method to the class:

```kotlin
    /**
     * The original create request may have timed out AFTER the server committed it
     * (timeouts are retriable, so the action got queued anyway). Before re-creating,
     * look for a server-side task with the same title in the same project created
     * around the time the action was queued. Best-effort: any failure means "no match".
     */
    private suspend fun findRecentDuplicate(task: Task): TaskDto? = try {
        val queuedAt = com.rendyhd.vicu.util.DateUtils.parseIsoDate(task.created)
        if (queuedAt == null) {
            null
        } else {
            api.getAllTasks(mapOf("s" to task.title, "filter" to "project_id = ${task.projectId}"))
                .firstOrNull { dto ->
                    dto.title == task.title &&
                        dto.projectId == task.projectId &&
                        com.rendyhd.vicu.util.DateUtils.parseIsoDate(dto.created)
                            ?.isAfter(queuedAt.minusSeconds(DUPLICATE_WINDOW_SECS)) == true
                }
        }
    } catch (e: Exception) {
        null
    }
```

In the `"create"` branch, replace:

```kotlin
                val task = json.decodeFromString<Task>(action.payload)
                val createDto = with(taskMapper) { task.toCreateDto() }
                val responseDto = api.createTask(task.projectId, createDto)
```

with:

```kotlin
                val task = json.decodeFromString<Task>(action.payload)
                val responseDto = findRecentDuplicate(task)
                    ?: api.createTask(task.projectId, with(taskMapper) { task.toCreateDto() })
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/worker/SyncWorker.kt
git commit -m "Guard offline create replay against timeout-after-commit duplicates (review NEW-19)"
```

---

## Task 3: Broaden offline detection (NEW-9, P2)

`SocketException` ("Network is unreachable"), `InterruptedIOException`, OkHttp stream resets etc. currently take the hard-failure path (rollback + error) instead of queueing.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/util/RetryableException.kt`
- Test: `app/src/test/java/com/rendyhd/vicu/util/RetryableExceptionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rendyhd.vicu.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class RetryableExceptionTest {

    private fun http(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())))

    @Test fun `unknown host is retriable`() =
        assertTrue(isRetriableNetworkError(UnknownHostException("x")))

    @Test fun `socket timeout is retriable`() =
        assertTrue(isRetriableNetworkError(SocketTimeoutException("x")))

    @Test fun `plain socket exception is retriable`() =
        assertTrue(isRetriableNetworkError(SocketException("Network is unreachable")))

    @Test fun `interrupted io is retriable`() =
        assertTrue(isRetriableNetworkError(InterruptedIOException("x")))

    @Test fun `generic io exception is retriable`() =
        assertTrue(isRetriableNetworkError(IOException("unexpected end of stream")))

    @Test fun `wrapped io cause is retriable`() =
        assertTrue(isRetriableNetworkError(RuntimeException(IOException("x"))))

    @Test fun `ssl handshake is NOT retriable`() =
        assertFalse(isRetriableNetworkError(SSLHandshakeException("bad cert")))

    @Test fun `wrapped ssl cause is NOT retriable`() =
        assertFalse(isRetriableNetworkError(IOException(SSLHandshakeException("bad cert"))))

    @Test fun `http 500 and 429 are retriable`() {
        assertTrue(isRetriableNetworkError(http(500)))
        assertTrue(isRetriableNetworkError(http(429)))
    }

    @Test fun `http 404 and 400 are NOT retriable`() {
        assertFalse(isRetriableNetworkError(http(404)))
        assertFalse(isRetriableNetworkError(http(400)))
    }

    @Test fun `non-network exception is NOT retriable`() =
        assertFalse(isRetriableNetworkError(IllegalStateException("x")))
}
```

NOTE on `wrapped ssl cause`: an `IOException` whose CAUSE is SSL must be non-retriable — this ordering matters in the implementation.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.RetryableExceptionTest"`
Expected: FAIL — `plain socket exception is retriable`, `interrupted io is retriable`, `generic io exception is retriable`, `wrapped ssl cause is NOT retriable` (the old implementation gets these wrong).

- [ ] **Step 3: Replace the implementation**

Replace the body of `app/src/main/java/com/rendyhd/vicu/util/RetryableException.kt` with:

```kotlin
package com.rendyhd.vicu.util

import retrofit2.HttpException
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Transient failures that should be queued/retried offline. Any IOException counts as
 * transient connectivity EXCEPT SSL problems (configuration errors, retrying won't help) —
 * note SSLException IS an IOException, so the SSL check must come first.
 */
fun isRetriableNetworkError(e: Exception): Boolean {
    if (e is HttpException) {
        val code = e.code()
        return code in 500..599 || code == 429
    }
    if (e is SSLException || e.cause is SSLException) return false
    if (e is IOException) return true
    if (e.cause is IOException) return true
    return false
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.RetryableExceptionTest"`
Expected: PASS (12 tests). Also run the full suite (`./gradlew testDebugUnitTest`) — no regressions.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Treat all non-SSL IOExceptions as retriable offline errors (review NEW-9)"
```

---

## Task 4: Recover actions stuck in 'processing' (NEW-7, P2)

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/local/dao/PendingActionDao.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/worker/SyncWorker.kt`

- [ ] **Step 1: Add the reset query to the DAO**

```kotlin
@Query("UPDATE pending_actions SET status = 'pending' WHERE status = 'processing'")
suspend fun resetProcessingToPending()
```

- [ ] **Step 2: Call it at the top of SyncWorker.doWork**

In `SyncWorker.doWork`, directly after `authManager.ensureInitializedAndGetToken()`, add:

```kotlin
        // Recover actions stranded in 'processing' by a process death mid-run.
        // Replay is at-least-once by design (see findRecentDuplicate for creates).
        pendingActionDao.resetProcessingToPending()
```

- [ ] **Step 3: Compile + commit**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "Reset pending actions stranded in processing at sync start (review NEW-7)"
```

---

## Task 5: TaskDetailViewModel — cancel stale collectors (NEW-2, P1)

The detail ViewModel is activity-scoped (the screen is a boolean-controlled overlay in VicuApp), and `loadTask` launches new Room collectors per task without cancelling the previous task's — stale collectors overwrite the open task's labels/subtasks/relations/attachments, and during the reset window can display the wrong task entirely.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailViewModel.kt` (loadTask, ~lines 74-173)

- [ ] **Step 1: Track and cancel load jobs**

Add `import kotlinx.coroutines.Job` to the imports. Next to `private var taskIdLoaded = 0L`, add:

```kotlin
    /** Collectors started by loadTask; cancelled when a different task is loaded so a
     *  previously opened task's Room emissions can't overwrite the current task's state. */
    private val loadJobs = mutableListOf<Job>()
```

In `loadTask(taskId: Long)`, right after the `if (taskId == taskIdLoaded) return` guard and the `taskIdLoaded = taskId` assignment, add:

```kotlin
        loadJobs.forEach { it.cancel() }
        loadJobs.clear()
```

Then change every `viewModelScope.launch { ... }` inside `loadTask` (there are six: the task collector, projects collector, inbox id, labels collector, attachments collector, attachments refresh) to be tracked:

```kotlin
        loadJobs += viewModelScope.launch {
            // (existing body unchanged)
        }
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification note (record in commit message body if tested)**

On a device: open task A, close, open task B, then trigger a sync (pull-to-refresh on the list behind it or edit B). B must keep its own labels/subtasks/attachments. Before this fix, A's data could appear on B.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailViewModel.kt
git commit -m "Cancel previous task collectors when loading a new task in detail (review NEW-2)"
```

---

## Task 6: Custom lists — includeDone, sort, and collector hygiene (NEW-3/4/5, PERF-8, P1)

Three bugs share one root: `loadTasks` hardcodes the open-tasks source, never sorts, and spawns a new infinite collector per store emission. Rebuild the pipeline with `flatMapLatest`.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/domain/repository/TaskRepository.kt` (add `getAllTasks`)
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt` (implement it)
- Modify: `app/src/main/java/com/rendyhd/vicu/data/local/dao/TaskDao.kt` (add query)
- Modify: `app/src/main/java/com/rendyhd/vicu/util/CustomListFilterBuilder.kt` (add `sortTasks`)
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/customlist/CustomListViewModel.kt` (rebuild init/loadTasks)
- Test: `app/src/test/java/com/rendyhd/vicu/util/CustomListSortTest.kt`

- [ ] **Step 1: Write the failing sort test**

```kotlin
package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomListSortTest {

    private fun task(id: Long, due: String = "", priority: Int = 0, title: String = "t", updated: String = "") =
        Task(id = id, title = title, dueDate = due, priority = priority, updated = updated)

    @Test
    fun `due_date asc puts null dates last`() {
        val tasks = listOf(
            task(1, due = "0001-01-01T00:00:00Z"),
            task(2, due = "2026-06-12T00:00:00Z"),
            task(3, due = "2026-06-10T00:00:00Z"),
        )
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "due_date", "asc")
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `priority desc puts urgent first`() {
        val tasks = listOf(task(1, priority = 1), task(2, priority = 4), task(3, priority = 0))
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "priority", "desc")
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.id })
    }

    @Test
    fun `title asc is case-insensitive`() {
        val tasks = listOf(task(1, title = "banana"), task(2, title = "Apple"))
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "title", "asc")
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `unknown sort key falls back to updated desc`() {
        val tasks = listOf(task(1, updated = "2026-01-01T00:00:00Z"), task(2, updated = "2026-06-01T00:00:00Z"))
        val sorted = CustomListFilterBuilder.sortTasks(tasks, "bogus", "whatever")
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }
}
```

NOTE: check `Task`'s constructor (`domain/model/Task.kt`) — all fields have defaults except possibly a few; adapt the `task()` helper to whatever minimal set is required, keeping the asserted fields.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.CustomListSortTest"`
Expected: FAIL — unresolved reference `sortTasks`.

- [ ] **Step 3: Implement `sortTasks` in CustomListFilterBuilder**

Add to `CustomListFilterBuilder.kt` (inside the object):

```kotlin
    /** A date key that sorts the null sentinel and blanks after every real date. */
    private fun sortableDate(value: String): String =
        if (DateUtils.isNullDate(value)) "9999-12-31T23:59:59Z" else value

    /**
     * Applies the custom list's configured sort client-side. The displayed list comes from
     * Room (not the API response), so the API-side sort_by/order_by alone has no effect on
     * what the user sees — this is the authoritative ordering.
     */
    fun sortTasks(tasks: List<Task>, sortBy: String, orderBy: String): List<Task> {
        val comparator: Comparator<Task> = when (sortBy) {
            "due_date" -> compareBy { sortableDate(it.dueDate) }
            "created" -> compareBy { it.created }
            "updated" -> compareBy { it.updated }
            "priority" -> compareBy { it.priority }
            "title" -> compareBy { it.title.lowercase() }
            "done_at" -> compareBy { sortableDate(it.doneAt) }
            "position" -> compareBy { it.position }
            else -> return tasks.sortedByDescending { it.updated }
        }
        val sorted = tasks.sortedWith(comparator)
        return if (orderBy.equals("desc", ignoreCase = true)) sorted.reversed() else sorted
    }
```

- [ ] **Step 4: Run the sort test — PASS**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.CustomListSortTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Add the done-inclusive query through the stack**

`TaskDao.kt` (next to `getAllOpenTasks`):

```kotlin
    @Query("SELECT * FROM tasks")
    fun getAllTasksFlow(): Flow<List<TaskEntity>>
```

`domain/repository/TaskRepository.kt` (next to `getAllOpenTasks`):

```kotlin
    fun getAllTasks(): Flow<List<Task>>
```

`TaskRepositoryImpl.kt` (next to `getAllOpenTasks`):

```kotlin
    override fun getAllTasks(): Flow<List<Task>> =
        taskDao.getAllTasksFlow().map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }
```

- [ ] **Step 6: Rebuild the CustomListViewModel pipeline**

In `CustomListViewModel.kt`: add imports `kotlinx.coroutines.ExperimentalCoroutinesApi`, `kotlinx.coroutines.flow.flatMapLatest`, `kotlinx.coroutines.flow.flowOf`, `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.distinctUntilChanged`, and annotate the class with `@OptIn(ExperimentalCoroutinesApi::class)`. Replace the entire `init { ... }` block AND delete the `private fun loadTasks(...)` function, with:

```kotlin
    init {
        // Render from Room with client-side filter + sort. flatMapLatest cancels the previous
        // collector when the list config changes (the old code leaked one collector per edit).
        viewModelScope.launch {
            customListStore.getById(listId)
                .flatMapLatest { customList ->
                    if (customList == null) {
                        flowOf<Pair<CustomList?, List<Task>>>(null to emptyList())
                    } else {
                        val source = if (customList.filter.includeDone) {
                            taskRepository.getAllTasks()
                        } else {
                            taskRepository.getAllOpenTasks()
                        }
                        source.map { tasks ->
                            val filtered = CustomListFilterBuilder.applyClientSideFilters(tasks, customList.filter)
                            customList to CustomListFilterBuilder.sortTasks(
                                filtered,
                                customList.filter.sortBy,
                                customList.filter.orderBy,
                            )
                        }
                    }
                }
                .collect { (customList, tasks) ->
                    _uiState.update { it.copy(customList = customList, tasks = tasks, isLoading = false) }
                }
        }
        // Background network refresh, once per distinct filter config (Room paints first).
        viewModelScope.launch {
            customListStore.getById(listId)
                .map { it?.filter }
                .distinctUntilChanged()
                .collect { filter ->
                    if (filter != null) {
                        taskRepository.refreshAll(CustomListFilterBuilder.buildQueryParams(filter))
                    }
                }
        }
        viewModelScope.launch {
            val inboxId = authManager.getInboxProjectId() ?: 0L
            _uiState.update { it.copy(inboxProjectId = inboxId) }
        }
    }
```

- [ ] **Step 7: Apply the same sort in the widget's custom-list branch**

In `app/src/main/java/com/rendyhd/vicu/widget/TaskWidgetWorker.kt`, `queryTasks` CUSTOM_LIST branch, replace:

```kotlin
                    val filtered = CustomListFilterBuilder.applyClientSideFilters(
                        domainTasks, customList.filter
                    )
```

with:

```kotlin
                    val filtered = CustomListFilterBuilder.sortTasks(
                        CustomListFilterBuilder.applyClientSideFilters(domainTasks, customList.filter),
                        customList.filter.sortBy,
                        customList.filter.orderBy,
                    )
```

- [ ] **Step 8: Compile + full unit tests**

Run: `./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Custom lists: honor includeDone and sort, fix leaked collectors (review NEW-3/4/5)"
```

---

## Task 7: Remove phantom strikethrough on toggle failure (NEW-6, P2)

Every list ViewModel adds the task id to `completedTaskIds` before calling `toggleDone`, but the Error branch never removes it — a hard failure leaves the row struck through.

**Files (exact locations of the optimistic add, found via `grep "completedTaskIds + task.id"`):**
- Modify: `ui/screens/today/TodayViewModel.kt` (~line 99)
- Modify: `ui/screens/inbox/InboxViewModel.kt` (~line 87)
- Modify: `ui/screens/upcoming/UpcomingViewModel.kt` (~line 98)
- Modify: `ui/screens/anytime/AnytimeViewModel.kt` (~line 169)
- Modify: `ui/screens/project/ProjectViewModel.kt` (~line 149)
- Modify: `ui/screens/tag/TagViewModel.kt` (~line 85)
- Modify: `ui/screens/customlist/CustomListViewModel.kt` (~line 119, shifted by Task 6)
- Modify: `ui/screens/search/SearchViewModel.kt` (~line 72)
- Modify: `ui/screens/logbook/LogbookViewModel.kt` (~line 49 — reversed variant, see below)

- [ ] **Step 1: Apply the standard replacement in the first eight files**

In each `toggleDone`, the `when (val result = taskRepository.toggleDone(task))` block's Error branch currently reads:

```kotlin
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
```

Replace it with:

```kotlin
                is NetworkResult.Error -> {
                    // Hard failure: revert the optimistic strikethrough along with surfacing
                    // the error, otherwise the row stays visually completed.
                    _uiState.update {
                        it.copy(
                            error = result.message,
                            completedTaskIds = it.completedTaskIds - task.id,
                        )
                    }
                }
```

If a file's Error branch has slightly different surrounding code, keep its existing behavior and only ADD the `completedTaskIds = it.completedTaskIds - task.id` line to the `copy(...)`.

- [ ] **Step 2: Apply the reversed variant in LogbookViewModel**

Logbook un-completes tasks and tracks `uncompletedTaskIds`. In its toggle function's Error branch, add the reversal line:

```kotlin
                            uncompletedTaskIds = it.uncompletedTaskIds - task.id,
```

into the existing `it.copy(...)` alongside the error message.

- [ ] **Step 3: Verify coverage**

Run: `grep -rn "completedTaskIds + task.id" app/src/main/java` — every file that appears must also contain a matching `- task.id` in its Error branch (CustomListViewModel included).

- [ ] **Step 4: Compile + commit**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "Revert optimistic strikethrough when toggleDone fails hard (review NEW-6)"
```

---

## Task 8: Today/Upcoming day boundary recomputes after midnight (NEW-17, P2)

`getTodayTasks()`/`getUpcomingTasks()` bake `getEndOfToday()` into the Room flow once at construction; long-lived ViewModels keep yesterday's boundary.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/util/DateUtils.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt` (getTodayTasks/getUpcomingTasks, ~lines 110-118)
- Test: `app/src/test/java/com/rendyhd/vicu/util/DateUtilsMidnightTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rendyhd.vicu.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DateUtilsMidnightTest {

    @Test
    fun `one hour before midnight`() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val now = ZonedDateTime.of(2026, 6, 11, 23, 0, 0, 0, zone)
        assertEquals(60L * 60 * 1000, DateUtils.millisUntilNextMidnight(now))
    }

    @Test
    fun `dst spring-forward day is one hour shorter`() {
        // Europe/Amsterdam springs forward on 2026-03-29 (02:00 -> 03:00).
        val zone = ZoneId.of("Europe/Amsterdam")
        val now = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, zone)
        assertEquals(22L * 60 * 60 * 1000, DateUtils.millisUntilNextMidnight(now))
    }

    @Test
    fun `at exact midnight returns a full day, never zero`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 6, 11, 0, 0, 0, 0, zone)
        assertEquals(24L * 60 * 60 * 1000, DateUtils.millisUntilNextMidnight(now))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.DateUtilsMidnightTest"`
Expected: FAIL — unresolved reference `millisUntilNextMidnight`.

- [ ] **Step 3: Add the boundary helpers to DateUtils**

Add imports to `DateUtils.kt`: `java.time.Duration`, `java.time.ZonedDateTime`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.flow`. Add inside the object:

```kotlin
    /** Milliseconds from [now] to the next local midnight; never less than one second. */
    fun millisUntilNextMidnight(now: ZonedDateTime = ZonedDateTime.now(localZone)): Long {
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L)
    }

    /**
     * Emits the current end-of-today boundary immediately, then again just after each local
     * midnight. Lets day-bounded Room flows re-query when the date rolls over instead of
     * keeping the boundary captured at ViewModel creation.
     */
    fun endOfTodayFlow(): Flow<String> = flow {
        while (true) {
            emit(getEndOfToday())
            delay(millisUntilNextMidnight() + 1_000L)
        }
    }
```

- [ ] **Step 4: Run the test — PASS**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.DateUtilsMidnightTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Rewire the repository flows**

In `TaskRepositoryImpl.kt`, replace:

```kotlin
    override fun getTodayTasks(): Flow<List<Task>> =
        taskDao.getTodayTasks(DateUtils.getEndOfToday()).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }

    override fun getUpcomingTasks(): Flow<List<Task>> =
        taskDao.getUpcomingTasks(DateUtils.getEndOfToday()).map { entities ->
            entities.map { with(taskMapper) { it.toDomain() } }
        }
```

with:

```kotlin
    override fun getTodayTasks(): Flow<List<Task>> =
        DateUtils.endOfTodayFlow()
            .distinctUntilChanged()
            .flatMapLatest { endOfToday ->
                taskDao.getTodayTasks(endOfToday).map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }

    override fun getUpcomingTasks(): Flow<List<Task>> =
        DateUtils.endOfTodayFlow()
            .distinctUntilChanged()
            .flatMapLatest { endOfToday ->
                taskDao.getUpcomingTasks(endOfToday).map { entities ->
                    entities.map { with(taskMapper) { it.toDomain() } }
                }
            }
```

(`flatMapLatest`, `distinctUntilChanged`, and `map` are already imported in this file.)

- [ ] **Step 6: Compile + full tests + commit**

Run: `./gradlew compileDebugKotlin testDebugUnitTest` — BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "Recompute Today/Upcoming day boundary after midnight (review NEW-17)"
```

---

## Task 9: Persistent, sweep-proof snoozes (NEW-8, P2)

Snooze currently lives at alarm index 99, which `cancelForTask`'s 0..99 sweep kills on every sync (`rescheduleAll`), and it does not survive reboots.

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/data/local/SnoozeStore.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/notification/AlarmScheduler.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/notification/AlarmReceiver.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/notification/NotificationActionReceiver.kt` (handleSnooze)
- Modify: `app/src/main/java/com/rendyhd/vicu/notification/BootReceiver.kt`

- [ ] **Step 1: Create SnoozeStore**

```kotlin
package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SnoozeEntry(val taskId: Long, val title: String, val triggerAtMillis: Long)

private val Context.snoozeDataStore: DataStore<Preferences> by preferencesDataStore(name = "snooze_prefs")

/**
 * Persists pending snoozes so they survive reboots and are independent of the reminder
 * alarm sweep (which cancels and re-registers reminders on every sync).
 */
@Singleton
class SnoozeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    companion object {
        private val KEY_SNOOZES = stringPreferencesKey("snoozes_json")
    }

    private val serializer = ListSerializer(SnoozeEntry.serializer())

    private fun decode(raw: String?): List<SnoozeEntry> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    suspend fun all(): List<SnoozeEntry> =
        decode(context.snoozeDataStore.data.first()[KEY_SNOOZES])

    suspend fun put(entry: SnoozeEntry) {
        context.snoozeDataStore.edit { prefs ->
            val updated = decode(prefs[KEY_SNOOZES]).filterNot { it.taskId == entry.taskId } + entry
            prefs[KEY_SNOOZES] = json.encodeToString(serializer, updated)
        }
    }

    suspend fun remove(taskId: Long) {
        context.snoozeDataStore.edit { prefs ->
            val updated = decode(prefs[KEY_SNOOZES]).filterNot { it.taskId == taskId }
            prefs[KEY_SNOOZES] = json.encodeToString(serializer, updated)
        }
    }
}
```

- [ ] **Step 2: Rework AlarmScheduler**

In `AlarmScheduler.kt`:

(a) Add imports `com.rendyhd.vicu.data.local.SnoozeStore` and `com.rendyhd.vicu.data.local.SnoozeEntry`, and inject the store:

```kotlin
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao,
    private val taskMapper: TaskMapper,
    private val snoozeStore: SnoozeStore,
) {
```

(b) Rename the existing `fun cancelForTask(taskId: Long)` (the 0..99 sweep) to `private fun cancelReminders(taskId: Long)` — body unchanged. In `scheduleForTask`, change the first line `cancelForTask(task.id)` to `cancelReminders(task.id)`.

(c) Add the new public surface (replacing the old `scheduleSnooze`):

```kotlin
    /** Cancels everything for a task, including a pending snooze (task done/deleted). */
    suspend fun cancelForTask(taskId: Long) {
        cancelReminders(taskId)
        cancelSnooze(taskId)
    }

    suspend fun scheduleSnooze(taskId: Long, taskTitle: String, triggerAtMillis: Long) {
        snoozeStore.put(SnoozeEntry(taskId, taskTitle, triggerAtMillis))
        registerSnoozeAlarm(taskId, taskTitle, triggerAtMillis)
    }

    suspend fun cancelSnooze(taskId: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(taskId),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
        snoozeStore.remove(taskId)
    }

    /** Re-registers persisted snoozes after a reboot; past-due ones fire one minute out. */
    suspend fun rescheduleSnoozes() {
        val now = System.currentTimeMillis()
        snoozeStore.all().forEach { entry ->
            registerSnoozeAlarm(entry.taskId, entry.title, maxOf(entry.triggerAtMillis, now + 60_000L))
        }
    }

    /** Snoozes live in their own request-code space so the reminder sweep can't hit them. */
    private fun snoozeRequestCode(taskId: Long): Int = "snooze:$taskId".hashCode()

    private fun registerSnoozeAlarm(taskId: Long, taskTitle: String, triggerAtMillis: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_TASK_TITLE, taskTitle)
            putExtra(AlarmReceiver.EXTRA_IS_SNOOZE, true)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
    }
```

`cancelForTask` becoming `suspend` is safe: every caller (TaskRepositoryImpl, SyncWorker, NotificationActionReceiver's coroutine, ToggleTaskCallback) already runs in a suspend context — the compiler will confirm.

- [ ] **Step 3: Consume the snooze on fire (AlarmReceiver)**

In `AlarmReceiver.kt` companion object add:

```kotlin
        const val EXTRA_IS_SNOOZE = "is_snooze"
```

Add `@Inject lateinit var snoozeStore: SnoozeStore` (import `com.rendyhd.vicu.data.local.SnoozeStore`). In `onReceive`, right after `taskId`/`taskTitle` are read (before the prefs gate), add:

```kotlin
        if (intent.getBooleanExtra(EXTRA_IS_SNOOZE, false)) {
            runBlocking { snoozeStore.remove(taskId) }
        }
```

- [ ] **Step 4: Make handleSnooze async (NotificationActionReceiver)**

Replace `handleSnooze` with:

```kotlin
    private fun handleSnooze(context: Context, taskId: Long, intent: Intent) {
        val taskTitle = intent.getStringExtra(AlarmReceiver.EXTRA_TASK_TITLE) ?: "Task Reminder"
        val triggerAt = System.currentTimeMillis() + 15 * 60 * 1000
        Log.d(TAG, "Snoozing task $taskId for 15 minutes")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                alarmScheduler.scheduleSnooze(taskId, taskTitle, triggerAt)
            } finally {
                pendingResult.finish()
            }
        }
    }
```

(`CoroutineScope`, `Dispatchers`, `launch` are already imported in this file.)

- [ ] **Step 5: Re-register snoozes on boot**

In `BootReceiver.kt`, after `alarmScheduler.rescheduleAll()`, add:

```kotlin
                alarmScheduler.rescheduleSnoozes()
```

- [ ] **Step 6: Compile + commit**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL. If any `cancelForTask` caller fails to compile because it is not in a suspend context, wrap that single call site in the smallest appropriate coroutine scope and note it in the commit message.

```bash
git add -A
git commit -m "Persist snoozes and exempt them from the reminder alarm sweep (review NEW-8)"
```

---

## Task 10: Sync hygiene — diff before writing, batch deletes, gate alarm rescheduling (PERF-2, PERF-3, P2)

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/local/dao/TaskDao.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt` (refreshAll, deleteLocalByIds)
- Modify: `app/src/main/java/com/rendyhd/vicu/worker/SyncWorker.kt` (refreshAllFromServer)

- [ ] **Step 1: Add DAO methods**

```kotlin
    @Query("SELECT * FROM tasks")
    suspend fun getAllSync(): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
```

- [ ] **Step 2: Batch deleteLocalByIds**

In `TaskRepositoryImpl.kt`, replace:

```kotlin
    override suspend fun deleteLocalByIds(ids: Set<Long>) {
        ids.forEach { taskDao.deleteById(it) }
    }
```

with:

```kotlin
    override suspend fun deleteLocalByIds(ids: Set<Long>) {
        if (ids.isNotEmpty()) taskDao.deleteByIds(ids.toList())
    }
```

- [ ] **Step 3: Diff-before-upsert and gated rescheduling in refreshAll**

In `TaskRepositoryImpl.refreshAll`, replace the block from `val entities = allTasks.map ...` through `alarmScheduler.rescheduleAll()` with:

```kotlin
            val entities = allTasks.map { with(taskMapper) { it.toEntity() } }
            // Skip tasks with pending local modifications to avoid overwriting unsynced changes
            val pendingTaskIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
            val existingById = taskDao.getAllSync().associateBy { it.id }
            val safeEntities = entities.filter { it.id !in pendingTaskIds }
            // Only write rows that actually changed — unconditional upserts invalidate every
            // observing Flow and re-trigger full-list JSON decoding on all alive screens.
            val changed = safeEntities.filter { existingById[it.id] != it }
            taskDao.upsertAll(changed)
            var alarmsTouched = changed.any { e ->
                val old = existingById[e.id]
                old == null || old.remindersJson != e.remindersJson ||
                    old.dueDate != e.dueDate || old.done != e.done
            }
            // Only prune on a FULL fetch — a filtered fetch returns a subset, so deleting
            // "missing" tasks would wrongly drop everything outside the filter. Keep pending
            // (e.g. locally-created temp-id) tasks regardless.
            if (filters.isEmpty()) {
                val serverTaskIds = allTasks.map { it.id }.toSet() + pendingTaskIds
                val deletedIds = existingById.keys - serverTaskIds
                if (deletedIds.isNotEmpty()) {
                    taskDao.deleteNotIn(serverTaskIds)
                    alarmsTouched = true
                }
            }
            // Alarm registration costs ~100 PendingIntent ops per reminder-task — only pay it
            // when reminder-relevant fields actually changed.
            if (alarmsTouched) alarmScheduler.rescheduleAll()
```

Keep the `WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)` line and the success log that follow (update the log to reference `changed.size` instead of `safeEntities.size` if it mentions counts).

- [ ] **Step 4: Mirror the same change in SyncWorker.refreshAllFromServer**

Replace the section from `val taskEntities = allTasks.map ...` through `alarmScheduler.rescheduleAll()` with:

```kotlin
            val taskEntities = allTasks.map { with(taskMapper) { it.toEntity() } }
            val pendingTaskIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
            val existingById = taskDao.getAllSync().associateBy { it.id }
            val safeEntities = taskEntities.filter { it.id !in pendingTaskIds }
            val changed = safeEntities.filter { existingById[it.id] != it }
            taskDao.upsertAll(changed)
            var alarmsTouched = changed.any { e ->
                val old = existingById[e.id]
                old == null || old.remindersJson != e.remindersJson ||
                    old.dueDate != e.dueDate || old.done != e.done
            }
            val serverTaskIds = allTasks.map { it.id }.toSet() + pendingTaskIds
            val deletedIds = existingById.keys - serverTaskIds
            if (deletedIds.isNotEmpty()) {
                taskDao.deleteNotIn(serverTaskIds)
                alarmsTouched = true
            }
            if (alarmsTouched) alarmScheduler.rescheduleAll()
```

- [ ] **Step 5: Add distinctUntilChanged to the hot repository flows**

In `TaskRepositoryImpl.kt`, for each of these functions, insert `.distinctUntilChanged()` between the DAO call and `.map`: `getInboxTasks` (inner flow inside flatMapLatest), `getAnytimeTasks`, `getLogbookTasks` (inner flow), `getByProjectId`, `getAllOpenTasks`, `getAllTasks` (from Task 6), and the inner flows of `getTodayTasks`/`getUpcomingTasks` (from Task 8). Pattern:

```kotlin
        taskDao.getAnytimeTasks(inboxProjectId).distinctUntilChanged().map { entities ->
```

(`distinctUntilChanged` is already imported.)

- [ ] **Step 6: Compile + full tests + commit**

Run: `./gradlew compileDebugKotlin testDebugUnitTest` — BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "Sync hygiene: diff before upsert, batch deletes, gate alarm rescheduling (review PERF-2/3)"
```

---

## Task 11: Cheap position anchor for new tasks (PERF-4, NEW-18, P3)

`anchorNewTaskAtEnd` fetches a whole page of view tasks to compute the max position, and only the FIRST page — wrong anchor in projects with more than 50 tasks.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt` (anchorNewTaskAtEnd, ~lines 65-81)

- [ ] **Step 1: Replace the fetch with a single position-sorted row**

Replace:

```kotlin
            val existing = api.getViewTasks(projectId, listView.id)
            val maxPos = existing.maxOfOrNull { it.position } ?: 0.0
```

with:

```kotlin
            // One row, highest position — avoids paging the whole view (and the old code
            // only read page 1 anyway, which anchored mid-list in projects with >50 tasks).
            val existing = api.getViewTasks(
                projectId,
                listView.id,
                mapOf("sort_by" to "position", "order_by" to "desc", "per_page" to "1"),
            )
            val maxPos = existing.firstOrNull()?.position ?: 0.0
```

- [ ] **Step 2: Compile + commit**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL.

Manual check (needs a server): in a project with >50 tasks, create a task in-app and confirm it lands at the BOTTOM of the project list (web UI or after refresh). If it lands at the top, the server ignored `order_by` on the view endpoint — fall back to `per_page=250` plus `maxOfOrNull`, and note it in the commit.

```bash
git add app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt
git commit -m "Fetch only the highest-position row when anchoring new tasks (review PERF-4/NEW-18)"
```

---

## Task 12: Widget worker cleanups (PERF-6, PERF-7, P3)

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/widget/TaskWidgetWorker.kt` (~lines 167-169)
- Modify: `app/src/main/java/com/rendyhd/vicu/widget/WidgetUpdateScheduler.kt`

- [ ] **Step 1: Delete the debug full-table query**

In `TaskWidgetWorker.queryTasks`, delete these lines:

```kotlin
        // Debug: count all tasks in Room
        val allCount = taskDao.getAllOpenTasksSync(999).size
        Log.d(TAG, "queryTasks: total open tasks in Room=$allCount")
```

- [ ] **Step 2: Make immediate widget updates unique work**

In `WidgetUpdateScheduler.kt`, add `import androidx.work.ExistingWorkPolicy` and replace `enqueueImmediateUpdateAll`:

```kotlin
    fun enqueueImmediateUpdateAll(context: Context) {
        val request = OneTimeWorkRequestBuilder<TaskWidgetWorker>()
            .setInputData(workDataOf("update_all" to true))
            .build()
        // Unique + REPLACE: rapid mutations collapse into one refresh instead of queueing
        // a redundant worker per repository write.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "widget_immediate_refresh",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
```

- [ ] **Step 3: Compile + commit**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "Widget: drop debug query, dedupe immediate refresh work (review PERF-6/7)"
```

---

## Task 13: Bang-today consistency (PAR-6, P3)

The NLP parser sets "!"-tasks due at start of day (00:00, matching desktop); the parser-disabled fallback in save() uses 23:59:59. Align on start of day.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskentry/TaskEntryViewModel.kt` (~line 365)

- [ ] **Step 1: One-line change**

In `save()`'s bang-today fallback block, replace:

```kotlin
                dueDate = DateUtils.todayEndIso()
```

with:

```kotlin
                // Match the NLP parser and desktop: bang-today means start of today.
                dueDate = DateUtils.todayStartIso()
```

- [ ] **Step 2: Compile + commit**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/taskentry/TaskEntryViewModel.kt
git commit -m "Align bang-today fallback with parser semantics (review PAR-6)"
```

---

## Task 14: Notifications-disabled banner (WIRE-3, P3)

If POST_NOTIFICATIONS is denied, every notification toggle silently does nothing. Mirror the existing ExactAlarmBanner pattern.

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/ui/components/settings/NotificationsDisabledBanner.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsScreen.kt` (~line 1591, next to `ExactAlarmBanner()`)

- [ ] **Step 1: Create the banner**

First open `ui/components/settings/ExactAlarmBanner.kt` and mirror its Card styling and lifecycle-recheck pattern. Reference implementation:

```kotlin
package com.rendyhd.vicu.ui.components.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Shown when system notifications are disabled for the app: reminders and summaries cannot
 * fire, so every toggle below is inert until the user re-enables them in system settings.
 * Re-checks on resume so it disappears as soon as the user comes back from settings.
 */
@Composable
fun NotificationsDisabledBanner() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (notificationsEnabled) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Notifications are turned off",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "Reminders and daily summaries cannot be shown until notifications are enabled for Vicu in system settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }) {
                Text("Open settings")
            }
        }
    }
}
```

If `LocalLifecycleOwner` from `androidx.compose.ui.platform` is deprecated in this Compose BOM, use `androidx.lifecycle.compose.LocalLifecycleOwner` instead (whichever ExactAlarmBanner uses).

- [ ] **Step 2: Place it in Settings**

In `SettingsScreen.kt`, directly above the `ExactAlarmBanner()` call (~line 1591), add:

```kotlin
            NotificationsDisabledBanner()
```

and add the import `com.rendyhd.vicu.ui.components.settings.NotificationsDisabledBanner`.

- [ ] **Step 3: Compile + commit**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "Show a banner in Settings when notifications are system-disabled (review WIRE-3)"
```

---

## Task 15: Dead code removal (WIRE-1 + dead param, P3)

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/navigation/Routes.kt` (line 16)
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/components/task/TaskItem.kt` (line 73)

- [ ] **Step 1: Remove TaskDetailRoute**

Verify it is unused: `grep -rn "TaskDetailRoute" app/src/main/java` must only match Routes.kt. Then delete the line:

```kotlin
@Serializable data class TaskDetailRoute(val taskId: Long)
```

- [ ] **Step 2: Remove the dead onSchedule parameter from TaskItem**

Verify no caller passes it: `grep -rn "onSchedule" app/src/main/java/com/rendyhd/vicu/ui` — `TaskItem.kt`'s declaration must be the only `onSchedule` associated with `TaskItem(` call sites (SwipeableTaskItem has its own real `onSchedule`, which stays). Then delete from `TaskItem`'s signature:

```kotlin
    onSchedule: (() -> Unit)? = null,
```

If any call site DOES pass `onSchedule` to `TaskItem`, leave the parameter alone and instead note it in the commit message — do not break the swipe accessibility plan.

- [ ] **Step 3: Compile + commit**

Run: `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "Remove dead TaskDetailRoute and unused TaskItem onSchedule param (review WIRE-1)"
```

---

## Final verification gate

- [ ] **Full build + tests:** `./gradlew testDebugUnitTest assembleDebug` — all suites green (including the 4 new test classes: QueueMergeTest, RetryableExceptionTest, CustomListSortTest, DateUtilsMidnightTest), BUILD SUCCESSFUL.

- [ ] **Device verification (install with `./gradlew installDebug`):**
  1. NEW-1: airplane mode ON; create a task, then edit its title, then toggle it done — all offline. Airplane mode OFF, wait for sync. The task must exist on the server (web UI), with the edited title, completed. Before this plan, it never reached the server.
  2. NEW-2: open task A, close, open task B, pull-to-refresh the list behind it. B keeps its own labels/subtasks/attachments.
  3. NEW-3/4: create a custom list with "include completed" ON — completed tasks render; set sort to due date ascending — order matches; edit the list filter — the list updates once, no flicker between old/new filters.
  4. NEW-6: with the server unreachable but wifi up (e.g. wrong port via a test URL — or skip if impractical), toggling a task surfaces an error AND the strikethrough reverts.
  5. NEW-8: trigger a reminder, snooze it, then pull-to-refresh a few times within 15 minutes — the snoozed notification still fires. Reboot mid-snooze — it still fires.
  6. NEW-17: with the app open, change the device date to tomorrow (or wait past midnight) — Today/Upcoming re-bucket without navigating away.
  7. Task 11: in a >50-task project, a newly created task lands at the bottom.
  8. WIRE-3: revoke notifications in system settings — the Settings Notifications tab shows the banner; re-enable — it disappears on return.

- [ ] **Push:** `git push origin beta3-implementation` (no release is triggered; CI only builds on published GitHub releases).
