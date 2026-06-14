# Vicu Android — Comprehensive Review (2026-06-11)

Scope: full-codebase review on branch `beta3-implementation`, four dimensions (bugs/correctness,
performance, wiring, desktop parity). Report only — no code changed. Every finding was re-read in
source before inclusion. Severity: P0 data loss / P1 broken feature / P2 quality / P3 polish.

Prior art: `docs/reviews/2026-06-04-beta3-feedback-review.md` + `-decisions.md`,
`docs/superpowers/plans/2026-05-31-*.md`. Known items are not re-reported except to mark
fixed/open.

Status: FINAL. Includes the cross-check addendum against the desktop review of the same date.
Implementation plan: `docs/superpowers/plans/2026-06-11-review-fixes.md`.

## Executive summary

The codebase is in substantially better shape than at the 2026-06-04 review: 25+ previously known
bugs are verified fixed, all five 2026-05-31 desktop-parity plans landed, and the auth, DI,
manifest, navigation, and settings layers came back clean. The remaining work clusters in four
places: (1) one P0 in the offline queue — editing an offline-created task destroys its queued
create, so the task never reaches the server; (2) the task-detail ViewModel accumulates stale Room
collectors and can show the wrong task's data; (3) custom lists ignore their "include completed"
and sort settings; (4) sync hygiene — full-history refetch, unconditional row rewrites, an alarm
resweep that also kills snoozes, and Today/Upcoming day boundaries frozen at ViewModel creation.
The final prioritized backlog (10 items + cheap polish) is at the end of this document.

---

## Dimension 1 — Bugs & correctness

### Prior-art items verified FIXED since 2026-06-04

| Item | Evidence |
|---|---|
| Recurrence dropped on create (Tier1 #1) | `CreateTaskDto` has `repeat_after`/`repeat_mode` (TaskDto.kt:44-45); mapped in `toCreateDto` (TaskMapper.kt:221-230) |
| Offline label queue clobbers itself (Tier1 #2) | `add_label`/`remove_label` now `insert()` unconditionally (LabelRepositoryImpl.kt:52-56) |
| Offline label add/remove never patches Room (#11) | `patchTaskLabelLocally` (LabelRepositoryImpl.kt:61-71) |
| `refreshAll()` never deletes server-removed tasks (#7) | `deleteNotIn` on full fetch (TaskRepositoryImpl.kt:530-533) |
| `update()` no rollback on hard failure (#8) | rollback to `previous` (TaskRepositoryImpl.kt:227-230) |
| Monthly recurrence never displays (#6) | `repeatMode == 1` gates in TaskItem.kt:150, TaskDetailScreen.kt:355, DateUtils.kt:135-137 |
| "At due time" reminder never fires (#5) | period-0 + relativeTo guard (AlarmScheduler.kt:131-141) |
| Alarm request-code overflow (#13) | `(taskId*100+index).hashCode()` (AlarmScheduler.kt:91-92). Residual: hash collisions are possible but unlikely (P3) |
| Pending queue missing ORDER BY (#10) | `ORDER BY createdAt ASC, id ASC` (PendingActionDao.kt:19) |
| `markReviewed` not optimistic (review §4.8) | ProjectRepositoryImpl.update is optimistic + rollback (ProjectRepositoryImpl.kt:48-64) |
| Daily summary slots identical / overdue leak (#12) | distinct headings + IDs, `getDueTodaySync` excludes overdue, per-type toggles (DailySummaryWorker.kt:40-94) |
| Custom-list today/this-week missing lower bound | `due_date >=` startOfToday present in all windows (CustomListFilterBuilder.kt:40-60) |
| Spinner decouple + staleness gating (Tier2 A) | `refresh(showSpinner=false)` default + `syncStaleness.isStale()` gate (TodayViewModel.kt:65, 78) |
| Save-task stutter (Tier2 C, partial) | dismiss immediately, `refreshAll()` fire-and-forget (TaskEntryViewModel.kt:438-443) |
| Detail sheet double auto-save | single `DisposableEffect(Unit){ onDispose { saveIfChanged() } }` (TaskDetailScreen.kt:137-141) |
| `onCreateLabel` no-op stub in add-task | label create wired + parsed @labels auto-create (TaskEntryViewModel.kt:270-278, 382-396) |
| Save button gating on parsed title | `effectiveTitle()` (TaskEntryViewModel.kt:282-289) |
| Token cleanup single-page (Plan 2) | pagination loop, maxPages=10 (AuthManager.kt:577-603) |
| Offline-created task: dependent-action remap | `tempIdMap` + `remapPendingDependents` (SyncWorker.kt:76-137, 201-219) — but see NEW-1, which undermines it |
| "Clear recurrence" missing | `clearRecurrence()` (TaskDetailViewModel.kt:203-205) |
| Default reminder offset (Plan 4) | synthesized via `DefaultReminder.build` on create (TaskEntryViewModel.kt:412-422) |

### Prior-art items still OPEN

- **Snoozed reminders lost on reboot** (low in prior art, worse than reported — see NEW-8).
- **Undo-after-refresh race** — `toggleDone` success still never upserts Room (TaskRepositoryImpl.kt:453-467).
  If a background `SyncWorker` refresh upserts `done=true` while the row is in its undo window, the
  struck row vanishes mid-screen; undo after that point writes the server but Room keeps `done=true`
  until the next refresh. Narrow window, unchanged from prior art (P3).
- **Recurring-complete leaves a stale Room row until refresh** — same root: success path of
  `toggleDone` intentionally skips the upsert, so the advanced recurring instance (new due date)
  only lands on the next refresh (P3).
- **DST drift in daily-summary period** and **widget periodic policy** — re-checked under
  Dimensions 2/3.

### NEW findings

**NEW-1 (P0) Offline create + offline edit destroys the queued create — task never reaches server.**
`TaskRepositoryImpl.queueTaskAction` (TaskRepositoryImpl.kt:83-98): any non-"create" action calls
`pendingActionDao.replaceForEntity("task", entityId, action)`, and `replaceForEntity`
(PendingActionDao.kt:66-70) deletes ALL pending rows for that entity — including the pending
"create" of an offline-created task (temp negative id). When it bites: create a task offline, then
(still offline) edit it, toggle it done, or complete it from a notification/widget
(NotificationActionReceiver.kt:90, ToggleTaskCallback.kt:95 use the same `replaceForEntity`).
On reconnect, SyncWorker replays "update"/"toggle_done" against the temp id → server 404 →
non-retriable → action marked `failed`. The task exists only locally; "Retry failed" can never
succeed; if failed actions are cleared, the next full refresh `deleteNotIn` prunes the local row —
the task is gone. Fix direction: in `queueTaskAction`, when a pending "create" exists for the
entity, merge the new state into the create's payload (keep actionType="create"); for "delete",
drop both rows.

**NEW-2 (P1) TaskDetailViewModel is activity-scoped and `loadTask` never cancels old collectors —
cross-task state corruption.** `TaskDetailScreen` is composed at the app level behind
`if (showTaskDetailSheet)` (VicuApp.kt:446-451), so `hiltViewModel()` resolves to the Activity and
the same `TaskDetailViewModel` lives forever. Every `loadTask(taskId)` (TaskDetailViewModel.kt:90-173)
launches new `getById(taskId).collect` + `attachments.collect` coroutines into `viewModelScope`
without cancelling the previous task's collectors. Room flows re-emit on every tasks-table write
(any edit or sync), so a stale collector for previously-opened task A fires while task B is open and
overwrites B's `labels`, `subtasks`, `relations` (else-branch, :132-139) and `attachments` (:164-168)
with A's data. Worse: during the reset window right after `loadTask(B)` sets `originalTask = null`
(:96-104), a stale A emission hits the `isFirstLoad` branch and displays task A wholesale in B's
sheet (e.g. when A's own dispose-time auto-save lands and invalidates the table). When it bites:
open task A, close, open task B, any sync/save occurs — intermittent wrong labels/subtasks/
attachments, occasionally the entire wrong task. Fix direction: keep collector `Job`s and cancel
them at the top of `loadTask`, or drive everything from a `MutableStateFlow<Long>` + `flatMapLatest`.

**NEW-3 (P1) Custom list "include completed" is dead.** `CustomListViewModel.loadTasks` observes
`taskRepository.getAllOpenTasks()` (done=0 only) and the `includeDone` branch is an admitted no-op
(CustomListViewModel.kt:83-88: "for includeDone we'd need a broader query"). The switch exists in
CustomListDialog (:339) and is persisted, but completed tasks never render. Fix: query a
done-inclusive DAO method when `filter.includeDone`.

**NEW-4 (P1) Custom list sort options are never applied to what's displayed.** `sortBy`/`orderBy`
are sent to the API (CustomListFilterBuilder.buildQueryParams), but the API result is discarded into
Room and the screen renders `getAllOpenTasks()` (hardcoded `ORDER BY updated DESC`, TaskDao.kt:71-72)
plus `applyClientSideFilters` which filters but never sorts (CustomListFilterBuilder.kt:104-187).
A user choosing "due date ascending" gets updated-desc. Fix: apply the configured sort client-side
after filtering.

**NEW-5 (P2) CustomListViewModel accumulates stale collectors on list edits.** `loadTasks` is called
from `customListStore.getById(listId).collect { ... }` (CustomListViewModel.kt:59-69) and each call
launches a new infinite `getAllOpenTasks().collect` without cancelling the previous one. After
editing the list's filter, two collectors race to set `uiState.tasks` (old filter vs new filter) on
every Room emission. Same `flatMapLatest`/job-cancel fix as NEW-2.

**NEW-6 (P2) toggleDone failure leaves a phantom strikethrough.** In every list VM (e.g.
TodayViewModel.kt:96-108), the task id is added to `completedTaskIds` before the call, but the
`NetworkResult.Error` branch only sets the error message — it never removes the id. On a hard
failure (4xx/5xx that isn't retriable) the row stays struck through although nothing was completed,
until navigation/refresh. Fix: remove the id from `completedTaskIds` in the Error branch (all list
VMs share this pattern: Today, Inbox, Upcoming, Anytime, Project, Tag, CustomList, Logbook, Search).

**NEW-7 (P2) Pending actions stuck in 'processing' are never retried.** SyncWorker sets
`status='processing'` before each action (SyncWorker.kt:82); if the process dies mid-action, the row
stays 'processing' forever — `getRetryable()` only selects 'pending' (PendingActionDao.kt:19) and
`retryAllFailed()` only covers 'failed'. The task row is protected from pruning (it counts as
pending) but the action never replays: the local change silently never syncs. Fix: on worker start,
reset `processing` → `pending` (single UPDATE), making replay at-least-once.

**NEW-8 (P2) Snooze is cancelled by any sync and lost on reboot.** Snooze schedules alarm index 99
(AlarmScheduler.kt:81-84); `cancelForTask` loops indices 0..99 (:46-62) and `scheduleForTask` always
calls `cancelForTask` first. `rescheduleAll()` runs after every `refreshAll()`
(TaskRepositoryImpl.kt:534) and every SyncWorker pass (SyncWorker.kt:245), so any sync within the
15-minute snooze window silently kills the snoozed reminder. BootReceiver also rebuilds only from
`task.reminders` (the fired reminder is in the past), so snoozes don't survive reboot either. Fix:
persist snoozes (e.g. a small table or DataStore entry consumed by rescheduleAll) and move snooze to
an index/key space `cancelForTask` doesn't sweep.

**NEW-9 (P2) Offline detection is too narrow — some offline failures take the hard-error path.**
`isRetriableNetworkError` (RetryableException.kt) recognizes UnknownHost/Connect/SocketTimeout (+
direct cause), HTTP 5xx/429. It misses `SocketException` ("Network is unreachable" / reset),
`InterruptedIOException`, `StreamResetException` and other IOExceptions OkHttp can surface when
connectivity drops mid-flight. Those edits get rolled back with an error instead of queueing —
device/timing dependent. Fix: treat `IOException` (excluding SSL errors) as retriable.

**NEW-10 (P3) Relative reminders always anchor to due_date.** `resolveReminderTime`
(AlarmScheduler.kt:124-144) ignores `relativeTo` — a reminder relative to `start_date`/`end_date`
(set by desktop/web) is scheduled off `due_date` (or not at all if due_date is the null sentinel
while start_date is set).

**NEW-11 (P3) Task updates can clear fields the DTO doesn't model.** `TaskDto` has no `assignees`
(or `cover_image_attachment_id`); a complete-object POST omits them, which Vikunja may treat as
"remove all assignees" on shared-project tasks. The desktop client omits them too (parity), so this
only matters if shared projects are ever used. Verify against the server before relying on it.

**NEW-12 (P3) SyncWorker's trailing refresh failure is silent.** `refreshAllFromServer` catches and
logs (SyncWorker.kt:253-255); the worker still returns success. Queue replay succeeded, but remote
changes may be stale without any indicator.

**NEW-13 (P3) At-least-once create can duplicate.** If the process dies between a successful
`api.createTask` and `updateStatus(completed)` (SyncWorker.kt:123-138), the next run re-creates the
task (server-side duplicate). Inherent to the design; worth a dedup heuristic only if observed.

**NEW-14 (P3) Pull-to-refresh failure is invisible.** List VMs ignore the `NetworkResult.Error`
returned by `taskRepository.refreshAll()` (e.g. TodayViewModel.kt:84); offline pull-to-refresh ends
with no feedback.

**NEW-15 (P3) Detail screen never adopts remote changes for a re-opened task.** Because the VM is
activity-scoped and `loadTask` early-returns for the same task id (TaskDetailViewModel.kt:91),
`originalTask` from the first open persists; later Room emissions only adopt `labels` (:132-139).
A title/date changed on another device won't show when re-opening the same task until a different
task is opened first. (Subsumed by the NEW-2 refactor.)

**NEW-16 (P3) Search LIKE wildcards unescaped.** `searchByTitle` (TaskDao.kt:65-69) interpolates the
query into LIKE; `%`/`_` in a search behave as wildcards. Cosmetic.

### Verified clean (Dimension 1)

- PUT=create / POST=update on every endpoint in VikunjaApiService.kt, including label update via
  POST — `docs.json` says PUT for `/labels/{id}` but the working desktop client uses POST
  (api-client.ts:400), so the swagger annotation is the outlier.
- Null-date sentinel `0001-01-01T00:00:00Z`: excluded in every dated DAO query (TaskDao.kt), every
  CustomListFilterBuilder window, `DateUtils.isNullDate` guards rendering; `dateOrNull`/
  `dateOrNullable` mapping handles both directions.
- Go zero-value: `update()`, `toggleDone`, `toggleSubtaskDone`, `applyScheduleAction`,
  `moveToProject`, notification Mark-Complete, widget toggle, and SyncWorker replay all send the
  complete Task via `toDto()`; detail auto-save re-merges preserved link HTML before saving.
  Project updates send the full UpdateProjectDto (favorites/identifier not modeled — see NEW-11).
- Today/Upcoming boundaries: end-of-today = local next-midnight as UTC instant, consistent `<=` /
  `>` split, DST-safe via `LocalDate.atStartOfDay(zone)`; dates normalized to UTC "Z" strings before
  Room string comparison (TaskMapper normalizes every dated field).
- Auth: refresh mutex + double-check after acquire, terminal 401-on-refresh-endpoint handling,
  typed backoff with connectivity reset, JWT→refresh→API-token fallback, backup-token self-heal,
  worker honors backoff. Minor notes only: legacy renew (`/user/token`) can recurse into the
  authenticator and stall up to ~15s on pre-2.0 servers; Tink keystore init catches GSE/InvalidKey
  but a RuntimeException from an OEM keystore would crash (rare).
- Offline queue ordering: creates first (stable sort), then createdAt/id order; temp-id remap for
  update/toggle/delete/add_label/remove_label both in-memory and persisted (SyncWorker.kt:201-219).
- NLP recurrence mapping (RecurrenceMap.kt) matches Vikunja semantics (monthly → repeat_mode 1).

---

## Dimension 2 — Performance

### Findings

**PERF-1 (P2) Every refresh re-fetches the entire task history and rewrites every row.**
`refreshAll()` (TaskRepositoryImpl.kt:503-542) and `SyncWorker.refreshAllFromServer`
(SyncWorker.kt:221-256) page through ALL tasks — including the full completed history — at
`per_page=50` (Constants.kt:5), then `upsertAll` every row unconditionally. A library with 1,000
lifetime tasks costs 20 sequential round-trips per refresh, and the unconditional upsert
invalidates the `tasks` table even when nothing changed. When it bites: grows linearly forever;
every staleness-gated navigation refresh (60s TTL), every pull, every SyncWorker pass. Fix
direction: raise per_page; skip upserts for unchanged rows (compare entity before writing);
longer-term, incremental sync on `updated >= lastSync` plus a periodic full reconcile.

**PERF-2 (P2) Room invalidation storm × JSON mapping cost.** Every list screen maps
`Flow<List<TaskEntity>> → List<Task>` where `toDomain()` runs four `json.decodeFromString` calls
per task (TaskMapper.kt:72-99). Room flows re-emit the full query result on ANY tasks-table write,
and several list VMs stay alive simultaneously (saved back-stack entries: Today/Upcoming/Anytime +
Drawer + widget queries). One toggle or sync therefore re-decodes JSON for (tasks × alive screens).
No `distinctUntilChanged` on the entity lists before the expensive map. Fix: add
`.distinctUntilChanged()` upstream of the mapping, batch multi-row writes in a transaction
(e.g. `deleteLocalByIds` loops per-id deletes, TaskRepositoryImpl.kt:499-501), and consider caching
decoded labels/reminders per (id, updated).

**PERF-3 (P2) Alarm rescheduling churn on every sync.** `refreshAll()` and every SyncWorker pass call
`alarmScheduler.rescheduleAll()` (TaskRepositoryImpl.kt:534, SyncWorker.kt:245). For each task with
reminders, `scheduleForTask` first runs `cancelForTask`, which performs 100
`PendingIntent.getBroadcast` lookups (AlarmScheduler.kt:46-62) — i.e. 100 binder calls per task per
sync, plus re-registering every alarm. With 20 reminder-tasks that is ~2,000 PendingIntent ops per
refresh. (Also the mechanism that kills snoozes — NEW-8.) Fix: reschedule only tasks whose
reminders/dueDate changed in the refresh, and cap the cancel loop at the actual reminder count
(persist the count, or cancel by exact prior reminder set).

**PERF-4 (P3) Task create costs 3 extra API round-trips.** `anchorNewTaskAtEnd`
(TaskRepositoryImpl.kt:65-81) fetches the project's views, then ALL tasks of the list view
(unbounded `getViewTasks`) just to compute max position, then posts the position. On large projects
the second call is heavy. Fix: request one task sorted by position desc (`per_page=1`), or cache
the list-view id per project.

**PERF-5 (P3) Per-row regex work in task rows.** `TaskLinkParser.hasNotesContent` compiles two
regexes inline on every call (TaskLinkParser.kt:66-67) and `TaskLinkIcons`/`hasNotesContent` re-run
several regexes per row on each recomposition. Hoist the inline `Regex(...)` to private vals and
`remember(task.description)` the result in `TaskItem`.

**PERF-6 (P3) Widget worker debug query.** Every widget refresh runs `getAllOpenTasksSync(999)`
purely for a log line (TaskWidgetWorker.kt:167-169) — a full-table query every 15 minutes and on
every mutation. Remove it. Related cosmetic bug: `totalCount = entities.size` where the query is
already LIMITed to 20 (:105-108), so the widget can never report more than 20.

**PERF-7 (P3) Widget update enqueue churn.** `enqueueImmediateUpdateAll` fires a NON-unique
OneTimeWorkRequest after every create/update/toggle/delete/refresh (a dozen call sites). Rapid
operations queue redundant workers, each iterating all widgets. The worker does short-circuit when
no widgets exist (TaskWidgetWorker.kt:47-50). Fix: `enqueueUniqueWork(..., REPLACE)`.

**PERF-8 (P3) Custom list first paint waits on the network.** `CustomListViewModel.loadTasks`
awaits `taskRepository.refreshAll(params)` before starting the Room collect
(CustomListViewModel.kt:76-92), so cached rows don't render until the network call returns (or
times out offline). Collect Room first, refresh in parallel — same pattern as the other lists.

**PERF-9 (P3) Each list refresh also re-fetches all projects and labels** (e.g.
TodayViewModel.refresh :84-86) even when only tasks are stale. Cheap calls, but they ride on every
60s-staleness navigation refresh.

### Verified clean (Dimension 2)

- LazyColumn `key = { it.id }` present in every task/project/label list (all screens checked).
- Room indices exist on the hot columns: projectId, done, dueDate (TaskEntity.kt:10-14).
- No main-thread Room access: all DAO methods are suspend or Flow; no `allowMainThreadQueries`.
- DescriptionField re-serialization (prior-art low) — FIXED: `snapshotFlow` + `debounce(150)`
  (DescriptionField.kt:93-96). Relation search debounced 250ms; search screen 300ms.
- Widget periodic policy (prior-art low) — FIXED: `ExistingPeriodicWorkPolicy.UPDATE`
  (WidgetUpdateScheduler.kt:23); 15-min period is the WorkManager floor.
- Startup: no blocking work in Application/MainActivity onCreate; auth init is async
  (MainActivity.kt:57-67); notification channels + one periodic enqueue only (VicuApplication.kt).
- Navigation refresh storms addressed by SyncStaleness 60s TTL (SyncStaleness.kt) + spinnerless
  init refresh — the prior-art "init refresh on every VM creation" complaint is fixed.
- AnimatedCheckbox animates via Canvas value reads (no per-frame recomposition of rows).

---

## Dimension 3 — Wiring

### Prior-art items verified FIXED

| Item | Evidence |
|---|---|
| `USE_EXACT_ALARM` declared (Tier1 #3) | removed — manifest declares only `SCHEDULE_EXACT_ALARM` (AndroidManifest.xml:6) |
| No exact-alarm prompt (Tier1 #4) | `ExactAlarmBanner` deep-links to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (ExactAlarmBanner.kt:67-72), placed in Settings (SettingsScreen.kt:1591); inexact `setAndAllowWhileIdle` fallback (AlarmScheduler.kt:107-114) |
| Dark-mode splash flash (Tier0 #10) | `values-night/themes.xml` with dark platform parent exists |
| Inbox-project delete guard (Tier1 #7) | `canDelete = project.id != state.inboxProjectId` (SettingsScreen.kt:1333) |
| Cyclic-parent StackOverflow (Tier1 #9) | `collectDescendantIds` with visited guard + descendant exclusion (ProjectEditDialog.kt:39-71) |
| DST drift in daily summary (low) | addressed with bounded drift: initial delay computed in system zone, re-anchored on every schedule()/boot (DailySummaryScheduler.kt:49-62) |
| Project color "None" default (Tier2 D) | new projects default to empty hex (ProjectEditDialog.kt:65-66) |
| FAB left/right setting (Tier3) | `fabAlignStart` pref → `LocalFabAlignStart` CompositionLocal (VicuApp.kt:410) |
| Keep-entry-open mode (Tier3) | `keepEntryOpen` pref consumed in TaskEntrySheet.kt:127 |
| Material You toggle (Tier3) | `useDeviceColors` pref → `VicuTheme(dynamicColor=...)` (MainActivity.kt:73-76) |

### Findings

**WIRE-1 (P3) `TaskDetailRoute` is dead code.** Defined in Routes.kt:16 but never registered in
AppNavHost nor navigated to — the detail screen is a boolean-controlled overlay in VicuApp
(:446-451). Either delete the route or promote the detail to a real destination (the latter would
also fix NEW-2's ViewModel scoping for free).

**WIRE-2 (P3) Room has no migration story for the future.** Schema is version 1 and unchanged since
v1.0.0 (verified via git), so nothing is broken today — but `DatabaseModule` configures neither
migrations nor `fallbackToDestructiveMigration`. The first future entity change will crash existing
installs at startup unless a migration ships with it. Worth a checklist note for the next schema
change.

**WIRE-3 (P3) No feedback when POST_NOTIFICATIONS is denied.** MainActivity requests the permission
once per launch (MainActivity.kt:199-207), but if the user denies it, every notification toggle in
Settings still appears functional while AlarmReceiver/DailySummaryWorker silently swallow the
SecurityException. Add an `areNotificationsEnabled()` banner next to ExactAlarmBanner.

**WIRE-4 (P3) Launch theme follows system dark mode, not the in-app theme pref.** values/values-night
fix the splash for system-mode users, but a user forcing dark in-app while their system is light
still gets a light pre-Compose frame. Standard limitation; would need the SplashScreen API +
activity recreation to fully fix.

**WIRE-5 (P3) CLAUDE.md drift.** Package tree says `com.vicu.app` (real: `com.rendyhd.vicu`),
`ktlintCheck`/`ktlintFormat` tasks don't exist, `di/AuthModule` is listed but absent (auth classes
are constructor-injected), and the Task Detail section still describes a ModalBottomSheet (now a
full-screen overlay). Update when convenient.

### Verified clean (Dimension 3)

- Hilt: all four repository interfaces bound once each (RepositoryModule); Network/Database/Coil
  modules complete; no duplicate bindings; workers use @HiltWorker + HiltWorkerFactory with the
  default initializer removed (manifest provider block); receivers use @AndroidEntryPoint
  (ToggleTaskCallback correctly uses an EntryPoint since Glance callbacks can't be injected).
- Manifest: AlarmReceiver, NotificationActionReceiver, BootReceiver (BOOT_COMPLETED filter),
  TaskWidgetReceiver (APPWIDGET_UPDATE + provider meta-data), WidgetConfigActivity
  (APPWIDGET_CONFIGURE), OidcLoginActivity all registered with sane exported flags;
  `windowSoftInputMode="adjustResize"` set; share intent filters for text/files/multiple;
  `appAuthRedirectScheme` placeholder set per build type (app/build.gradle.kts:33,67).
- Permissions: POST_NOTIFICATIONS declared + runtime-requested; SCHEDULE_EXACT_ALARM declared +
  banner + fallback; RECEIVE_BOOT_COMPLETED declared and receiver gated on the action.
- Navigation: every route in Routes.kt (except WIRE-1) has a destination and an entry point
  (drawer, bottom bar, or programmatic); notification tap (task_id extra) → detail overlay; widget
  header/task taps → navigate/new-task/detail via MainActivity extras handled in VicuApp
  LaunchedEffects with auth-state gating; detail overlay has its own BackHandler; multi-select
  exits via BackHandler on all list screens; setup flow navigates on auth-state changes with
  popUpTo(0).
- Settings round-trip: every field of all 10 stores (Behavior, Notification, Nlp, Theme, BottomBar,
  Widget, Review, Logbook, LabelOrder, CustomList) is written by a Settings control AND read by a
  consumer (AlarmReceiver gates on taskRemindersEnabled/soundEnabled; CompletionSoundPlayer on
  completionSoundEnabled/Uri; inbox query on inboxExcludeDated; scheduleAction in
  applyScheduleAction; keepEntryOpen in TaskEntrySheet; widget prefs in TaskWidgetWorker; review
  prefs in Review/Drawer; logbook retention in getLogbookTasks; defaultReminderOffset/relativeTo in
  TaskEntryViewModel). No dead settings or dead store fields found.
- DataStore key evolution: stable key names, defensive enum parsing (`runCatching` on
  ScheduleAction), JSON stores use `ignoreUnknownKeys`.

---

## Dimension 4 — Feature consistency vs desktop

Desktop source consulted at `C:\Users\rendy\vscode\vicu` (targeted reads: use-filters.ts,
date-utils.ts, recurrence.ts, task-parser/*, CustomListView.tsx, review-metadata.ts, config.ts,
task-sort.ts, default-reminder.ts, use-project-tasks.ts, InboxView/TodayView/UpcomingView/
AnytimeView/TagView).

### Known gaps (2026-05-31 plans + memory) — status

| Gap | Status |
|---|---|
| Task notes indicator (Plan 1) | CLOSED — `hasNotesContent` (TaskLinkParser.kt:63-69) wired into TaskItem.kt:142 |
| API token dedup pagination (Plan 2) | CLOSED — paginated cleanup (AuthManager.kt:577-603) |
| Task relations UI (Plan 3) | CLOSED — RelationKind, relations section + picker in task detail (TaskDetailViewModel.kt:110-112, 310-335) |
| Notification granularity (Plan 4) | CLOSED — afternoon slot (DailySummaryScheduler), per-type digest toggles (DailySummaryWorker.kt:50-52), default reminder offset (DefaultReminder.kt + TaskEntryViewModel.kt:412-422) |
| Project Review feature (Plan 5) | CLOSED — ReviewScreen/ViewModel/PrefsStore; metadata grammar, cadence math, and 14-day default verified identical to desktop review-metadata.ts |

Beta3-decisions spot-checks: multi-select CAB present on all list screens (selection VM +
BackHandler); Today/Upcoming project grouping present; created-date display present
(TaskDetailScreen.kt:566-571); real reminder display present (ReminderFormat util).
**Swipe-layer rework (Tier2 B) is PARTIAL**: done — threshold 0.35 to 0.5, M3 color roles,
progress-gated background icon, edge-triggered haptic (SwipeableTaskItem.kt:48-72, 111-158);
still missing — velocity cap, edge dead-zone (WindowInsets.systemGestures), snapTo(Settled)
(the post-swipe tap dead-zone fix), opposite-direction swipe-to-undo (both directions still
disabled when done, :98-99), and the TalkBack non-swipe schedule path — `TaskItem.onSchedule`
(TaskItem.kt:73) is a dead parameter, declared but never used.

### New divergence sweep

**PAR-1 (P2, divergent — Android behind) Inbox ordering.** Desktop Inbox is now a position-sorted
project list view with manual ordering (InboxView.tsx → use-project-tasks.ts → view API +
sortProjectTasks). Android Inbox is `ORDER BY created DESC` (TaskDao.kt:12-20) with no manual
reorder. CLAUDE.md documents the old created-desc behavior — desktop moved ahead.

**PAR-2 (P2, divergent) Custom-list date-window semantics.** Desktop: `today` = everything due
on or before end of today (overdue INCLUDED, no lower bound); `this_week` = to end of calendar
week (Sunday); `this_month` = to end of calendar month (CustomListView.tsx:10-36, 50-73).
Android: all three are rolling windows starting at start-of-today (overdue EXCLUDED; +7 days /
+1 month) (CustomListFilterBuilder.kt:40-60 + client-side :141-163). Android's lower bound was a
deliberate beta3 decision ("stop overdue leaking in"), but desktop never got the change and the
week/month window shapes (calendar vs rolling) differ too — the same saved list shows different
tasks on each platform. Decide one canonical semantic and port it.

**PAR-3 (P1, divergent — covered as NEW-3/NEW-4) Custom-list includeDone + sort.** Both work on
desktop (filter + server-side sort honored, CustomListView.tsx:42-44, 83-92); both are broken on
Android. Same fix as Dimension 1.

**PAR-4 (P3, divergent) Today view presentation.** Desktop splits "Overdue" and "Today" as
top-level headings with project groups inside each (TodayView.tsx:43-78); Android renders one
combined project-grouped list with no overdue separation.

**PAR-5 (P3, divergent) "From completion" recurrence label.** Desktop labels `repeat_mode == 2`
as "(from completion)" (recurrence.ts:38); Android's `formatRecurrence` ignores mode 2 — such a
task (set on web/desktop) shows a plain interval with no hint, though the repeat icon still shows
when repeat_after > 0.

**PAR-6 (P3, internal inconsistency) Bang-today due time.** The shared parser sets due = start of
today (ExtractDates.kt:60-94, identical to desktop), but the parser-disabled fallback in
`TaskEntryViewModel.save()` uses `DateUtils.todayEndIso()` (23:59:59 local)
(TaskEntryViewModel.kt:360-367). Same gesture, different stored time depending on whether NLP is
enabled; desktop always uses 00:00.

**PAR-7 (P3, divergent) Relative-date label for other years.** Desktop appends the year for dates
outside the current year (date-utils.ts:56-58); Android always renders "MMM d" (DateUtils.kt:98),
so a task due next January reads ambiguously.

### Intentional platform differences (not gaps)

- Desktop-only: print view, global quick-entry window, tray/badge icon, browser-host integration.
- Android-only: home-screen widget, AlarmManager reminders + notification channels, share-sheet
  intake, offline pending-action queue (desktop uses its own cache/undo-window model), swipe
  gestures, inbox "exclude dated" option (Things-3 behavior the desktop never had).

### Verified parity (clean)

- Smart-list semantics: Today (overdue + today, local zone), Upcoming (dated, after today),
  Anytime (open, inbox excluded, grouped by project, updated desc), Logbook (done_at desc), Tag
  (client-side label filter, project-grouped) — boundary math matches desktop's isToday/isOverdue.
- Recurrence NLP mapping byte-for-byte (monthly = repeat_mode 1 / repeat_after 0; multi-month
  approximated as 30-day intervals on both).
- Bang-today parser rules identical (trailing/leading/standalone "!", priority-token guard).
- Review metadata grammar, footer upsert, cadence math, 14-day default, exclude-inbox default.
- Project-view sort (dated-first by due date, then position) mirrored exactly.
- Default-reminder synthesis (0 = off, -1 = at due time, >0 = seconds before) identical.
- Completed-tasks undo window concept (overlay until navigation) on both platforms.
- Go zero-value discipline and POST-for-update verb choice match the desktop client.

---

## Prioritized backlog (FINAL — integrates the desktop cross-check addendum)

1. **NEW-1 (P0)** Offline create + offline edit/toggle/delete destroys the queued "create" —
   task never syncs and is eventually prunable. Merge later edits into the create's payload
   instead of `replaceForEntity`.
2. **NEW-2 (P1)** TaskDetailViewModel stale collectors / activity scoping — wrong labels,
   subtasks, attachments, occasionally the whole wrong task in the detail screen. Cancel prior
   jobs or refactor to `flatMapLatest` (or promote TaskDetailRoute to a real destination, WIRE-1).
3. **NEW-3 + NEW-4 + NEW-5 (P1, PAR-3)** Custom lists: "include completed" is a dead toggle, the
   chosen sort is never applied, and edits accumulate racing collectors. All work on desktop.
4. **NEW-17 (P2)** Today/Upcoming day boundary frozen at ViewModel creation — wrong lists after
   midnight for anyone keeping the app in recents overnight. Re-derive the boundary per emission.
5. **NEW-6 (P2)** toggleDone hard-failure leaves a phantom strikethrough across all nine list VMs —
   remove the id from `completedTaskIds` in the Error branch.
6. **NEW-8 (P2)** Snooze is silently cancelled by any sync (`cancelForTask` sweeps index 99) and
   lost on reboot — persist snoozes and move them out of the swept index space.
7. **NEW-7 (P2)** Pending actions stuck in 'processing' after process death are never retried —
   reset processing to pending at SyncWorker start.
8. **NEW-9 + NEW-19 (P2)** Offline detection misses SocketException/other IOExceptions (writes
   roll back instead of queueing), and a timeout-after-commit can duplicate a create on replay —
   broaden `isRetriableNetworkError` and add a replay dedup guard.
9. **PERF-1 + PERF-2 + PERF-3 (P2)** Sync hygiene: unconditional `upsertAll` + per-emission JSON
   decode across all alive screens + rescheduleAll's ~100 PendingIntent ops per reminder-task per
   sync — diff before writing, add `distinctUntilChanged`, gate alarm rescheduling on actual
   reminder changes. (NEW-18/PERF-4 position-anchor fix rides along.)
10. **PAR-1 + PAR-2 (P2)** Inbox ordering (position view vs created-desc) and custom-list
    date-window semantics diverge from desktop — product decision per item, then align both
    clients (desktop-side work included; not covered by the Android fix plan).

Honorable mentions (P3 but cheap): finish the swipe-pass leftovers (velocity cap, dead-zone,
snapTo, swipe-to-undo, dead `onSchedule` param), notifications-denied banner (WIRE-3), widget
debug query + unique work (PERF-6/7), bang-today time inconsistency (PAR-6), dead TaskDetailRoute
(WIRE-1), pull-to-refresh error feedback (NEW-14).

## Verified clean — summary

- PUT/POST endpoint conventions (desktop-confirmed for the label-update outlier in docs.json).
- Null-date sentinel handling across DAO queries, filter builders, mappers, and rendering.
- Go zero-value: complete-object updates on every write path, including queue replay, notification
  actions, and widget toggles.
- Date/timezone boundary math (Today/Upcoming/overdue, DST-safe local-zone day windows, UTC
  normalization before Room string comparison).
- Auth stack: refresh mutex + double-check, typed backoff with connectivity reset, fallback chain,
  backup-token self-heal, worker gating. (Minor 15s legacy-renew stall noted.)
- Offline queue ordering + temp-ID remapping machinery (undermined only by NEW-1).
- Hilt graph, manifest registrations, runtime permissions, OIDC redirect scheme.
- Navigation reachability, deep links (notification/widget/share), back-stack behavior.
- Settings round-trip for all 10 DataStore stores — no dead settings or dead fields.
- LazyColumn keys, Room indices, no main-thread queries, lean startup path.
- 19 prior-art bug fixes confirmed in place (Dimension 1 table), plus exact-alarm pair, splash,
  inbox-delete guard, cyclic-parent guard, DST re-anchoring (Dimension 3 table).
- Desktop parity of: smart-list semantics, recurrence mapping, bang-today parsing, review
  metadata, project sort, default reminders. All five 2026-05-31 parity plans landed.

---

## Addendum — cross-check against the desktop review (same date)

The desktop codebase received its own review on 2026-06-11. Each desktop finding was checked for
an Android analogue. Three are real on Android, one corrects a parity claim above; the rest have
no Android counterpart.

**NEW-17 (P2) Today/Upcoming day boundary is frozen at ViewModel creation** (analogue of desktop
M10, stale module-level TODAY). `getTodayTasks()`/`getUpcomingTasks()` evaluate
`DateUtils.getEndOfToday()` once when the flow is built (TaskRepositoryImpl.kt:110-118), and the
list VMs build that flow once in `init` (TodayViewModel.kt:44-66). The VMs survive long through
nav saveState/restoreState, so an app left open (or cached) across midnight keeps yesterday's
boundary: tasks due the new day stay out of Today, and Upcoming still includes them. Only VM
recreation or process death resets it. Fix: re-derive the boundary per emission — e.g. a
`flatMapLatest` over a midnight-tick flow, or pass the boundary per query from a clock-aware
source. (Custom lists are unaffected: their client-side filters recompute `LocalDate.now()` on
every emission. Widget and daily summary compute fresh per run.)

**NEW-18 (P3) New-task position anchor reads only the first page of view tasks** (same
single-page pattern as desktop H2). `anchorNewTaskAtEnd` calls
`api.getViewTasks(projectId, listView.id)` with no paging params (TaskRepositoryImpl.kt:68-75),
so the server returns its default page (50, position-ascending). In a project list view with more
than 50 tasks, `maxPos` is the 50th task's position and the new task is anchored mid-list instead
of at the end. Fix: request one task sorted by position desc (`per_page=1`) — same change PERF-4
already recommends for cost.

**NEW-19 (P3) Timeout-after-commit can duplicate a create** (analogue of desktop M1).
`SocketTimeoutException` is classified retriable (RetryableException.kt:12), but a timeout does
not mean the server didn't apply the write — OkHttp gives up waiting, the server may still commit.
`create()` then stores a temp task and queues a "create" replay (TaskRepositoryImpl.kt:176-189),
which on sync creates a second copy; `refreshAll` later pulls down both. Updates/toggles replay
idempotently (full object) — only create duplicates. Generalizes NEW-13 (process-death window) to
the much more common slow-server case. Fix direction: no clean idempotency key in the Vikunja API;
a pragmatic dedup is to check on replay whether a task with identical title+project created within
the timeout window already exists server-side.

**PAR-8 (P3, divergent — desktop bug) Anytime inbox exclusion.** Correction to the "Verified
parity" list above: Android excludes the inbox project from Anytime (TaskDao.kt:46-47), but the
desktop AnytimeView does not (it only uses inboxProjectId as the new-task target — desktop review
M9). Android matches the documented behavior; the fix belongs on the desktop side.

Cross-platform context (desktop-side issues, no Android action): the desktop's offline pending-
action queue is write-only — queued actions never replay (desktop H1) — so the "intentional
platform differences" line above overstates the desktop's offline model; Android's
SyncWorker-based replay has no such gap (its issues are NEW-1/NEW-7). Desktop's 50-task page cap
(H2) has no Android list-view analogue: `refreshAll`/SyncWorker paginate to exhaustion and screens
render from Room. Desktop's Quick View custom-list filter drop (M8) maps to the Android widget,
which applies the full client-side filter correctly but inherits NEW-3/NEW-4 (includeDone, sort).
No Android analogue found for desktop M4/M5 (covered differently as PERF-2), M6 (Android sections
are pure Room flows — ProjectViewModel.kt:59-114 is the clean flatMapLatest pattern the other VMs
should copy), M7 (DataStore is async), or the L-series items.

These addendum items are integrated into the final prioritized backlog above (NEW-17 at #4,
NEW-19 folded into #8, NEW-18 riding along with #9).
