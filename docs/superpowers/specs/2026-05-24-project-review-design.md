# Project Review Tracking — Vicu Android

**Date:** 2026-05-24
**Status:** Design, ready for implementation plan
**Companion specs:**
- `vicu/docs/superpowers/specs/2026-05-24-project-review-design.md` — Desktop client (Electron + React)
- `vikunja-mcp/docs/superpowers/specs/2026-05-24-project-review-design.md` — MCP service + Claude Code skill

This spec is self-contained. The marker protocol (Section 4) is reproduced verbatim from the desktop spec — it is the source of truth and all three implementations MUST agree.

---

## 1. Goal

Add a GTD-style project review workflow to Vicu Android. The user reviews each Vikunja project on a configurable cadence (default 14 days) and marks it reviewed with a single tap. Review state is encoded as a footer in each project's `description`, the same format used by the desktop client and the MCP service, so review status syncs across all three.

## 2. Non-goals

- Per-task review state. Reviews are at the project level only.
- A guided review wizard.
- Push notifications when a project becomes overdue (deferred — see Section 12).
- Widgets, watch face, or quick-tile entry (deferred).
- Migration of descriptions written by other clients. Untracked projects stay untracked until first review.

## 3. Prerequisite refactor — fix partial-DTO project updates

**This must land before the review feature.**

`app/src/main/java/com/rendyhd/vicu/data/repository/ProjectRepositoryImpl.kt:48-57` currently sends an `UpdateProjectDto` (partial) on project update. This is unsafe for the same Go zero-value reason that `TaskRepositoryImpl.kt:171-179` explicitly works around (see the inline comment on line 178: "Remote update — send complete object (Go zero-value problem)").

Writing the review marker via the partial-update path will zero out other project fields the user has set on the Vikunja side (color, identifier, parent, archived flag, etc.). The fix is independent of the review feature but required for it.

### Refactor scope

- Delete or repurpose `UpdateProjectDto` (the partial DTO).
- Change `VikunjaApiService.updateProject` to accept a full `ProjectDto`.
- Change `ProjectRepositoryImpl.updateProject` to (a) read the current project from cache/Room, (b) merge the requested mutations, (c) call `api.updateProject(projectId, fullDto)`.
- Add the inline comment mirroring the Task repository: `// Remote update — send complete object (Go zero-value problem)`.
- Audit existing callers of `ProjectRepositoryImpl.updateProject` — they may currently rely on partial-update semantics implicitly. None should break, because Vikunja's POST `/projects/{id}` already does a full replace.

If the planner finds non-trivial callers that depend on partial semantics, route those through a dedicated `updateProjectFields(projectId, mutator: (Project) -> Project)` helper that does the read-modify-write merge internally.

## 4. Marker protocol (canonical spec — mirrored from desktop)

Review state is encoded as a plaintext footer at the end of `Project.description`.

### Grammar

```
{user's existing description, may be empty or multiline}

---
**Vicu review**: <value>
```

The marker block consists of exactly:
- A leading `\n---\n` separator on its own line.
- A single line: `**Vicu review**: ` followed by a value.
- No trailing content. The marker MUST be the last non-empty block.

### Values

| Form | Meaning |
|------|---------|
| `2026-05-24 · every 14 days` | Last reviewed on date; per-project cadence override of 14 days. |
| `2026-05-24` | Last reviewed on date; uses global default cadence. |
| `excluded` | Project opts out of review tracking. Never appears in the review list. |
| `never · every 7 days` | Tracked with cadence override but not yet reviewed. Treated as overdue immediately. |
| (no marker) | Default: tracked, never reviewed, uses global default cadence. Treated as overdue immediately. |

### Reserved tokens

- `**Vicu review**:` — exact prefix, bold, colon-space terminator. Case-sensitive.
- `·` (U+00B7 MIDDLE DOT) — separator between date and cadence. ASCII fallback: ` | ` (space-pipe-space) accepted on read, never written.
- `every N days` / `every N weeks` — cadence form. Canonical written form is `every N days`. On read, also accept `every Nd`, `N days`, `every N week(s)`.
- `never`, `excluded` — special date tokens, case-insensitive on read.

### Parsing rules (read)

1. Find the **last** occurrence of `\n---\n**Vicu review**: ` in `description`. If absent, marker = none → project is untracked-default.
2. Parse the value to end-of-string, trim whitespace.
3. Split on `·` or ` | ` (read-only fallback). Trim each part.
4. First part: ISO 8601 date (`YYYY-MM-DD`), or `never`, or `excluded`.
5. Optional second part: cadence (`every N days|weeks|d`).
6. Malformed marker: log a warning, treat as untracked-default, do NOT modify on next write — preserve the user's text.

### Serialization rules (write)

1. `upsertFooter(description, meta)`:
   - Strips any existing marker block (`\n---\n**Vicu review**: ...` to end).
   - Right-trims trailing whitespace from what remains.
   - Appends `\n\n---\n**Vicu review**: <canonical value>` (or `\n---\n...` if remainder already ends with newline; just `---\n...` if remainder is empty).
2. Canonical value formatting:
   - Date: `YYYY-MM-DD` in the user's device local timezone (Android: `LocalDate.now(ZoneId.systemDefault())`).
   - With cadence: `<date> · every <N> days` (always `days`, even if input said `weeks` — convert).
   - Excluded: `excluded` only, no cadence.
3. Never write if computed marker equals current marker (avoid no-op writes that bump Vikunja's `updated`).

### Disambiguation

The marker is recognized **only** when `---\n` is followed immediately by `**Vicu review**: `. Horizontal rules used elsewhere in the description are ignored.

If the user manually edits the marker, parse what's there and treat it as truth. Never silently "correct" user input.

## 5. Architecture

The feature follows the existing Vicu Android layering: data → domain → ui. No new modules required.

```
┌─────────────────────────────────────────────────────────────┐
│ UI (Compose)                                                │
│   review/ReviewScreen.kt                          ◄── new   │
│   review/ReviewListItem.kt                        ◄── new   │
│   review/ReviewSettingsSection.kt                 ◄── new   │
│                                                             │
│   navigation/Routes.kt                            ◄── edit  │
│   navigation/AppNavHost.kt                        ◄── edit  │
│   drawer/DrawerContent.kt                         ◄── edit  │
│   drawer/DrawerViewModel.kt                       ◄── edit  │
│   settings/SettingsScreen.kt                      ◄── edit  │
│   settings/SettingsViewModel.kt                   ◄── edit  │
│                                                             │
│ ViewModel                                                   │
│   review/ReviewViewModel.kt                       ◄── new   │
│                                                             │
│ Domain                                                      │
│   domain/review/ReviewMetadata.kt                 ◄── new   │
│   domain/review/ReviewStatus.kt                   ◄── new   │
│   domain/review/ReviewParser.kt                   ◄── new   │
│   domain/usecase/MarkProjectReviewedUseCase.kt    ◄── new   │
│   domain/usecase/SetReviewCadenceUseCase.kt       ◄── new   │
│   domain/usecase/ExcludeFromReviewUseCase.kt      ◄── new   │
│   domain/usecase/GetProjectsNeedingReviewUseCase  ◄── new   │
│                                                             │
│ Data                                                        │
│   data/repository/ProjectRepositoryImpl.kt        ◄── edit  │
│     (prerequisite: full-DTO update path)                    │
│   data/local/prefs/ReviewPrefsStore.kt            ◄── new   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

No changes to Room schema. The marker lives in `ProjectEntity.description` (already exists per `ProjectEntity.kt:10`).

## 6. Domain layer

### 6.1 `domain/review/ReviewMetadata.kt`

```kotlin
data class ReviewMetadata(
    val state: ReviewState,
    val lastReviewedAt: LocalDate?,       // null unless state == REVIEWED
    val cadenceDaysOverride: Int?,        // null = use global default
)

enum class ReviewState { NEVER, REVIEWED, EXCLUDED }
```

### 6.2 `domain/review/ReviewStatus.kt`

```kotlin
data class ReviewStatus(
    val metadata: ReviewMetadata,
    val effectiveCadenceDays: Int,
    val nextReviewAt: LocalDate?,          // null if excluded
    val isOverdue: Boolean,
    val daysSinceReviewed: Int?,           // null if never reviewed
    val daysUntilDue: Int?,                // negative if overdue; null if excluded
)
```

### 6.3 `domain/review/ReviewParser.kt`

Object (singleton) exposing pure functions. No DI, no IO.

```kotlin
object ReviewParser {
    const val MARKER_PREFIX = "**Vicu review**:"
    const val MARKER_SEPARATOR = "---"

    fun parse(description: String?): ReviewMetadata
    fun serialize(meta: ReviewMetadata): String
    fun upsertFooter(description: String?, meta: ReviewMetadata): String
    fun stripFooter(description: String?): String
    fun computeStatus(
        meta: ReviewMetadata,
        globalDefaultCadenceDays: Int,
        today: LocalDate = LocalDate.now()
    ): ReviewStatus
}
```

`today` parameter exists for testability. Production callers omit it.

Use `kotlinx.datetime.LocalDate` if it's already a transitive dependency via kotlinx-serialization; otherwise `java.time.LocalDate`. The Explore agent didn't confirm — the planner picks based on existing imports in the domain module.

### 6.4 Use cases

Each use case is a small class with a single `suspend operator fun invoke(...)`. Constructor-injected with `ProjectRepository`.

```kotlin
class MarkProjectReviewedUseCase(private val repo: ProjectRepository) {
    suspend operator fun invoke(projectId: Long): Result<Project>
    // 1. repo.getProject(projectId) — current state
    // 2. parse current marker
    // 3. new meta = current.copy(state=REVIEWED, lastReviewedAt=LocalDate.now())
    // 4. if upsertFooter(current.description, new) == current.description → return current (no-op)
    // 5. else: repo.updateProject(current.copy(description=newDescription))
}

class SetReviewCadenceUseCase(private val repo: ProjectRepository) {
    suspend operator fun invoke(projectId: Long, cadenceDays: Int?): Result<Project>
}

class ExcludeFromReviewUseCase(private val repo: ProjectRepository) {
    suspend operator fun invoke(projectId: Long, excluded: Boolean): Result<Project>
    // excluded=true: write marker with state=EXCLUDED, drop date and cadence.
    // excluded=false: revert to NEVER (no marker is the same as NEVER + default cadence).
    //   If user had a cadence override before excluding, it is lost — call SetReviewCadence after.
}

class GetProjectsNeedingReviewUseCase(
    private val repo: ProjectRepository,
    private val prefs: ReviewPrefsStore,
    private val configRepo: ConfigRepository, // for inbox project id
) {
    operator fun invoke(): Flow<List<ProjectWithStatus>>
    // combine(repo.observeAllProjects(), prefs.getPrefs(), configRepo.observeConfig()) { projects, p, cfg ->
    //   projects
    //     .filter { !it.isArchived }
    //     .filter { !(p.excludeInbox && it.id == cfg.inboxProjectId) }
    //     .map { it to ReviewParser.computeStatus(ReviewParser.parse(it.description), p.defaultCadenceDays) }
    //     .filter { (_, status) -> status.metadata.state != EXCLUDED && status.isOverdue }
    //     .sortedBy { (_, status) -> status.daysUntilDue ?: Int.MIN_VALUE }
    //     .map { (project, status) -> ProjectWithStatus(project, status) }
    // }
}

// Used by the Review screen's "All tracked" mode. Same filtering minus the
// overdue and excluded-state filters.
class GetAllTrackedProjectsUseCase(
    private val repo: ProjectRepository,
    private val prefs: ReviewPrefsStore,
    private val configRepo: ConfigRepository,
) {
    operator fun invoke(): Flow<List<ProjectWithStatus>>
    // Same shape as GetProjectsNeedingReviewUseCase, but:
    //   .filter { (_, status) -> status.metadata.state != EXCLUDED }   // keep non-overdue
    //   (no isOverdue filter)
}

data class ProjectWithStatus(val project: Project, val status: ReviewStatus)
```

## 7. Data layer

### 7.1 `ProjectRepositoryImpl.kt` edits

After the prerequisite refactor (Section 3), update mutations send full DTOs. The review use cases use this existing path — they call `updateProject(fullProject)`. No new repository methods needed.

### 7.2 `data/local/prefs/ReviewPrefsStore.kt`

Mirror `BehaviorPrefsStore.kt:27-62`: a singleton class wrapping DataStore.

```kotlin
@Singleton // or whatever DI annotation pattern the existing PrefsStores use
class ReviewPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class ReviewPrefs(
        val enabled: Boolean = true,
        val defaultCadenceDays: Int = 14,
        val excludeInbox: Boolean = true,
    )

    private val Context.dataStore by preferencesDataStore("review_prefs")

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val DEFAULT_CADENCE_DAYS = intPreferencesKey("default_cadence_days")
        val EXCLUDE_INBOX = booleanPreferencesKey("exclude_inbox")
    }

    fun getPrefs(): Flow<ReviewPrefs> = context.dataStore.data.map { ... }
    suspend fun setEnabled(enabled: Boolean) { ... }
    suspend fun setDefaultCadenceDays(days: Int) { ... }
    suspend fun setExcludeInbox(excluded: Boolean) { ... }
}
```

Pattern lifted directly from the existing `BehaviorPrefsStore`. Register in DI module alongside other stores.

## 8. UI

### 8.1 `review/ReviewScreen.kt`

```kotlin
@Composable
fun ReviewScreen(
    onProjectClick: (projectId: Long) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(), // or whatever DI pattern is in use
)
```

`ReviewUiState`:
```kotlin
enum class ReviewTab { DUE, ALL_TRACKED }

data class ReviewUiState(
    val isLoading: Boolean = false,
    val activeTab: ReviewTab = ReviewTab.DUE,
    val dueProjects: List<ProjectWithStatus> = emptyList(),
    val allTrackedProjects: List<ProjectWithStatus> = emptyList(),
    val error: String? = null,
)
```

Both flows are collected continuously; the UI picks one based on `activeTab`. Cheap because filtering is in-memory and the underlying Room flow is shared.

Layout:
- Top app bar: "Review" title.
- Tab row immediately below the app bar with two tabs: **Due** (default) and **All tracked**. Always visible.
- Body (Due tab):
  - LazyColumn of `ReviewListItem(project, status, onMarkReviewed, onSetCadence, onExclude, onClick)`, sourced from `GetProjectsNeedingReviewUseCase`.
  - Empty state when `projects.isEmpty()`: centered icon (Material Icons `CheckCircle`) + "All caught up — switch to All tracked to manage cadence on individual projects."
- Body (All tracked tab):
  - Same `ReviewListItem` component, sourced from `GetAllTrackedProjectsUseCase`. Sorted by next-review-date ascending (overdue first, then upcoming).
  - All controls available, including `Mark reviewed` (marking a not-yet-overdue project is valid; it resets the clock).

### 8.2 `review/ReviewListItem.kt`

Material 3 ListItem-style row:
- Leading: project color dot (24dp, hex from project.color).
- Headline: project title.
- Supporting text: `Reviewed 18 days ago` or `Never reviewed`.
- Trailing: a small chip showing status (`Overdue 4d`, `Never`, `Due today`) and an overflow IconButton (`MoreVert`).
- Primary action: a filled tonal button labelled `Mark reviewed`. Disabled during in-flight mutation; show CircularProgressIndicator (16dp) in place of text.
- Tap anywhere outside the button → `onProjectClick(project.id)`.

Overflow menu items:
- `Set cadence…` → opens a bottom sheet with a number input. Confirm → `onSetCadence(project.id, days)`. "Clear override" button restores `null` (use global default).
- `Exclude from review tracking` → confirmation `AlertDialog`. Confirm → `onExclude(project.id, true)`.

### 8.3 `review/ReviewViewModel.kt`

```kotlin
@HiltViewModel // or equivalent
class ReviewViewModel @Inject constructor(
    private val getProjectsNeedingReview: GetProjectsNeedingReviewUseCase,
    private val getAllTrackedProjects: GetAllTrackedProjectsUseCase,
    private val markReviewed: MarkProjectReviewedUseCase,
    private val setCadence: SetReviewCadenceUseCase,
    private val excludeFromReview: ExcludeFromReviewUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            getProjectsNeedingReview().collect { list ->
                _uiState.update { it.copy(dueProjects = list, isLoading = false) }
            }
        }
        viewModelScope.launch {
            getAllTrackedProjects().collect { list ->
                _uiState.update { it.copy(allTrackedProjects = list) }
            }
        }
    }

    fun onMarkReviewed(projectId: Long) { /* viewModelScope.launch { markReviewed(...) } */ }
    fun onSetCadence(projectId: Long, days: Int?) { ... }
    fun onExclude(projectId: Long, excluded: Boolean) { ... }
    fun onTabChanged(tab: ReviewTab) { _uiState.update { it.copy(activeTab = tab) } }
}
```

### 8.4 Drawer entry — `drawer/DrawerContent.kt` + `DrawerViewModel.kt`

Per Explore findings, the drawer has a displaced-smart-lists row at top (`DrawerContent.kt:66-100`). The Review entry follows the same shape as Today/Upcoming/Anytime/Logbook.

Edits:
- `DrawerContent.kt`: render a "Review" item between Anytime and Logbook in the smart-list rendering loop. With a badge showing overdue count when > 0.
- `DrawerViewModel.kt:72-117`: include `reviewCount: Int` in `DrawerUiState`. Derive it by combining `GetProjectsNeedingReviewUseCase()` into the same `combine()` block that produces `projectTree`.
- Hide the entry when `ReviewPrefsStore.getPrefs().enabled == false`.

### 8.5 Settings — `settings/SettingsScreen.kt` + `SettingsViewModel.kt`

Add a new section `ReviewSettingsSection` in the "Primary" tab.

Per `SettingsUiState:51-80`, add fields:
```kotlin
val reviewEnabled: Boolean = true,
val reviewDefaultCadenceDays: Int = 14,
val reviewExcludeInbox: Boolean = true,
```

Compose UI:
- Switch row "Enable project review tracking"
- Stepper/number input "Default review cadence (days)" with min 1, max 365
- Switch row "Exclude Inbox from review list" with helper text "Your Inbox is for capture, not for periodic review."

Wire to `ReviewPrefsStore` via `SettingsViewModel`.

## 9. Navigation

Per `Routes.kt:1-17`, add:

```kotlin
@Serializable object ReviewRoute
```

Per `AppNavHost.kt:30-115`, add:

```kotlin
composable<ReviewRoute> {
    ReviewScreen(onProjectClick = { id -> navController.navigate(ProjectRoute(id)) })
}
```

Drawer item navigates with `navController.navigate(ReviewRoute)`.

## 10. Offline behavior

Vicu Android is offline-first per `CLAUDE.md:200`. Room is the source of truth; `PendingActionEntity` queues mutations.

- `GetProjectsNeedingReviewUseCase` reads from Room (already does, via repository's Flow). Works offline immediately.
- Mark-reviewed / set-cadence / exclude mutations: write to Room first, then attempt remote update. On failure, enqueue a `PendingActionEntity` matching the existing pattern for task mutations.
- The marker is part of `ProjectEntity.description` — no schema change needed. Existing sync paths carry it.
- Conflict on later sync (description edited remotely between local mutation and drain): last-writer-wins. Acceptable. If we later observe lost markers, revisit with a merge step.

## 11. Edge cases

| Case | Behavior |
|------|----------|
| Project description is null / empty | Treat as empty string; on first write, description becomes `---\n**Vicu review**: ...` (no leading newlines). |
| Multiple `---` separators (horizontal rules) | Only final occurrence followed by `**Vicu review**:` matches. |
| Malformed marker | Log warning, treat as untracked-default, preserve text on next write replace. |
| Trailing whitespace after marker | Tolerated on parse; rewritten without trailing whitespace. |
| Archived project | Filtered out of review list. Marker preserved. Unarchive restores. |
| Inbox project | Filtered when `excludeInbox=true` (default). |
| Project with 0 description | First marker has no leading newlines. |
| User edits the marker manually | Parser respects it. Never silently "corrected." |
| Mark-reviewed twice in quick succession | Second call is a no-op (computed marker == current marker). |
| Mark-reviewed while offline | Optimistic update to Room; mutation enqueued. UI shows updated status immediately. |
| Network 5xx | Repository surfaces error; UseCase returns `Result.failure`. ViewModel shows a Snackbar with retry. |
| Date crosses midnight in user's TZ mid-review | First click sets today's date (correct). Second click in next day rewrites with new date (correct). |
| Very large project count (>1000) | The use case combines flows reactively; sorting is O(N log N) per emission. Acceptable. If profiling shows hot path, memoize parser output by `(projectId, description-hash)`. |

## 12. Out of scope (deferred)

- WorkManager-backed push notifications when projects become overdue.
- Home-screen widget showing overdue count + quick "Mark reviewed" actions.
- Quick Settings tile.
- Wear OS integration.
- Per-project notes / multi-entry review log.
- Bulk operations ("mark all selected reviewed").

## 13. Testing

The repo has Compose + Kotlin tooling but the Explore agent did not confirm an existing test setup. Recommended:

- **Unit tests** for `ReviewParser`: at minimum cover every row in Section 4 ("Values") plus the malformed-marker and disambiguation cases. Use `kotlin.test` or `JUnit5` depending on what `app/build.gradle.kts` already configures.
- **Unit tests** for `MarkProjectReviewedUseCase` and `GetProjectsNeedingReviewUseCase`: test repository invocation, no-op detection, sorting and filtering. Mock `ProjectRepository`.
- **Compose UI test** (instrumented) for `ReviewListItem`: button states, overflow menu actions.
- **Integration smoke** (manual or instrumented): mark a project reviewed → next pull from Vikunja shows the marker → desktop client sees the same state.

If no test framework is configured, the planner should propose adding JUnit5 + Mockk + Compose UI test artifacts. Parser tests are the highest-value first additions.

## 14. File-by-file change list

**New files:**
- `app/src/main/java/com/rendyhd/vicu/domain/review/ReviewMetadata.kt`
- `app/src/main/java/com/rendyhd/vicu/domain/review/ReviewStatus.kt`
- `app/src/main/java/com/rendyhd/vicu/domain/review/ReviewParser.kt`
- `app/src/main/java/com/rendyhd/vicu/domain/usecase/MarkProjectReviewedUseCase.kt`
- `app/src/main/java/com/rendyhd/vicu/domain/usecase/SetReviewCadenceUseCase.kt`
- `app/src/main/java/com/rendyhd/vicu/domain/usecase/ExcludeFromReviewUseCase.kt`
- `app/src/main/java/com/rendyhd/vicu/domain/usecase/GetProjectsNeedingReviewUseCase.kt`
- `app/src/main/java/com/rendyhd/vicu/domain/usecase/GetAllTrackedProjectsUseCase.kt`
- `app/src/main/java/com/rendyhd/vicu/data/local/prefs/ReviewPrefsStore.kt`
- `app/src/main/java/com/rendyhd/vicu/ui/review/ReviewScreen.kt`
- `app/src/main/java/com/rendyhd/vicu/ui/review/ReviewListItem.kt`
- `app/src/main/java/com/rendyhd/vicu/ui/review/ReviewViewModel.kt`
- `app/src/main/java/com/rendyhd/vicu/ui/settings/ReviewSettingsSection.kt`
- Unit test files mirroring the above under `app/src/test/...` (if test framework added)

**Edited files:**
- `app/src/main/java/com/rendyhd/vicu/data/repository/ProjectRepositoryImpl.kt` — **prerequisite refactor: full-DTO updates** (Section 3).
- `app/src/main/java/com/rendyhd/vicu/data/remote/api/VikunjaApiService.kt` — change `updateProject` signature if `UpdateProjectDto` is removed.
- `app/src/main/java/com/rendyhd/vicu/data/remote/dto/UpdateProjectDto.kt` — delete or repurpose per refactor choice.
- `app/src/main/java/com/rendyhd/vicu/ui/navigation/Routes.kt` — add `ReviewRoute`.
- `app/src/main/java/com/rendyhd/vicu/ui/navigation/AppNavHost.kt` — add `composable<ReviewRoute>`.
- `app/src/main/java/com/rendyhd/vicu/ui/drawer/DrawerContent.kt` — add Review entry with badge.
- `app/src/main/java/com/rendyhd/vicu/ui/drawer/DrawerViewModel.kt` — combine review count into `DrawerUiState`.
- `app/src/main/java/com/rendyhd/vicu/ui/settings/SettingsScreen.kt` — mount `ReviewSettingsSection`.
- `app/src/main/java/com/rendyhd/vicu/ui/settings/SettingsViewModel.kt` — fields + wiring.
- DI module(s) — register `ReviewPrefsStore`, use cases, new ViewModel.

(Exact package paths follow existing convention; planner should confirm against the actual tree.)

## 15. Open questions for the planner

- DI framework in use (Hilt vs Koin vs manual)? Annotations above assumed Hilt; adjust if different.
- `java.time.LocalDate` vs `kotlinx.datetime.LocalDate`. Pick whichever is already used in the domain layer.
- Whether the project's bottom-bar configurable smart lists (`BottomBarPrefsStore`, per Explore findings) should be extended to include Review. Recommended: yes, treat Review like any other smart list for bottom-bar eligibility. Cost: trivial — add the entry to the available list.
- Notification permission flow for the deferred WorkManager option — out of scope here.
