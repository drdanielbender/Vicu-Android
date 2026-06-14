# Restore Task Detail Bottom Sheet (Without the Jiggle) — Findings + Implementation Plan

> **STATUS (decided 2026-06-11): DEFERRED.** The task detail screen stays full-screen for now.
> Do not execute the 1.3.2 downgrade or the nested-scroll shim. Trigger to execute this plan:
> **material3 1.5.0 stable ships in a Compose BOM** (watch
> https://developer.android.com/jetpack/androidx/releases/compose-material3 and
> https://developer.android.com/develop/ui/compose/bom/bom-mapping). At that point: bump the BOM,
> skip Tasks 3-5 (the workaround rungs), execute Task 1 (restore the sheet wrapper) and Task 2's
> on-device verification matrix, and only fall back to Tasks 3-5 if the jiggle somehow survives
> 1.5.0. Re-check this plan's version facts at execution time; they were gathered 2026-06-11.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring back the ModalBottomSheet presentation of the task detail screen with drag-to-dismiss, without the drag/fling jiggle that forced the full-screen conversion in 5ea505d.

**Architecture:** Restore the sheet wrapper in TaskDetailScreen.kt (the conversion only changed the wrapper; all item content is byte-identical), then fix the jiggle at its source: the regression lives in material3 1.4.0's rewritten sheet drag internals, not in our layout. Primary fix is pinning material3 back to 1.3.2 (last pre-regression stable); fallback is an app-side NestedScrollConnection shim that cuts the buggy nested-scroll bridge between the sheet and its LazyColumn.

**Tech Stack:** Jetpack Compose (BOM 2026.01.01), Material 3, Kotlin 2.2.10.

---

## Part 1: Findings

### 1.1 The symptom and its history

- The task detail screen was a `ModalBottomSheet` whose content is a `LazyColumn` (title, description, labels, due date, subtasks, relations, attachments...). During drag-to-dismiss and during fast scrolls hitting the list boundary, the content shook/sprang ("jiggle").
- Commit `ca0317c` (2026-06-04) removed a `heightIn(max = 0.9 * screenHeight)` cap on the LazyColumn, theorizing that a variable content-derived height made the sheet re-derive its expanded anchor every frame. Directionally sensible (variable sheet height does force anchor re-derivation, and an even earlier version used a fixed 0.85 fraction that was worse), but it did not fully fix the jiggle, because the remaining jiggle is a library bug, not an app layout problem.
- Commit `5ea505d` (2026-06-11) gave up and converted to a full-screen Scaffold + TopAppBar, citing issuetracker.google.com/issues/486562294.
- The diff between the ca0317c sheet version and the current full-screen version touches ONLY the wrapper (ModalBottomSheet vs Scaffold/TopAppBar/BackHandler), the loading-indicator container, and the LazyColumn modifier chain. Every `item { }` block is identical. Since 5ea505d, only TaskDetailViewModel.kt changed, not the screen. Restoring the sheet is therefore a small, well-bounded wrapper swap.
- The `logcat.txt` / `logcat2.txt` files in the repo root are from a February 27 OIDC-login debugging session (OidcLoginActivity launches, ImeTracker, mDNS noise). They contain zero sheet/anchor/jank/Compose signals and are irrelevant to this problem.

### 1.2 Why TaskEntrySheet does not jiggle (the key clue)

`TaskEntrySheet` (ui/components/task/TaskEntrySheet.kt) is also a `ModalBottomSheet` with the same `sheetState` config (`skipPartiallyExpanded = true`), same `VicuDragHandle`, same `imePadding()`. The one structural difference: its content is a plain, non-scrollable `Column`.

This matters because ModalBottomSheet has two independent drag paths:

1. **Direct drag**: the sheet surface itself carries the anchored-draggable modifier. Touches on the drag handle or any non-scrollable content drive the sheet directly. This path works correctly on material3 1.4.0 — TaskEntrySheet proves it daily.
2. **Nested-scroll bridge**: when the content contains a scrollable (our LazyColumn), touch input goes to the scrollable, and the sheet only moves via the `NestedScrollConnection` the sheet installs above it (leftover drag deltas in onPostScroll, leftover fling velocity in onPreFling/onPostFling). **The 1.4.0 bug lives entirely in this bridge.** TaskEntrySheet never engages it because a plain Column dispatches no nested-scroll events.

So the jiggle is not about lazy vs eager measurement, sheet height, or imePadding — it is exclusively about having a scrollable child routed through the sheet's nested-scroll connection on material3 1.4.0.

### 1.3 The upstream bug (b/486562294)

- Title: "ModalBottomSheet nested scroll has jitter and overscroll issues", filed ~2026-02-23. The issue page itself is JS/login-gated, so comments and status could not be read directly; the facts below come from search snippets, the public repro repo, and the material3 release notes.
- Public repro: github.com/prof18/bottomsheet-nested-scroll-bug — ModalBottomSheet containing a LazyColumn; fling fast so the list hits its boundary; the sheet briefly drags toward dismiss and springs back. This is exactly our symptom.
- **Regression point: material3 1.4.0.** The repro README documents that Compose BOM 2025.08.01 (material3 1.3.2) is clean, and the bug appears when material3 1.4.0 is pulled in (directly via BOM 2026.02.00, or transitively by adding material3-window-size-class 1.4.0, which version-aligns the whole material3 group to 1.4.0). Material3 1.4.0 rewrote the sheet drag internals (AnchoredDraggable rework, with follow-up feature flags named `ComposeMaterial3Flags#isAnchoredDraggable...`), and the related bug numbers below all postdate it.
- **No stable fix exists.** There are no 1.4.x patch releases at all; every BOM from 2025.10.00 through the current 2026.06.00 ships material3 1.4.0. Fix work landed across the 1.5.0 alpha line:
  - 1.5.0-alpha14 (2026-02-11): "Fixed a bug in BottomSheetScaffold, ModalBottomSheet ... where anchors were not recalculated in some cases" (b/478210200), plus a strict-offset-check flag.
  - 1.5.0-alpha15 (2026-02-25): bottom sheets respect motionScheme during nested scroll and drag (b/452071842, b/384959324 — the "ModalBottomSheet overscroll issue with LazyColumn" bug).
  - 1.5.0-alpha16 (2026-03-25): `isAnchoredDraggableComponentsAnchorRecoveryEnabled` flag introduced "for draggable components experiencing ambiguous target errors" (b/487941042, b/478210200).
  - 1.5.0-alpha21 (2026-06-03): PartiallyExpanded anchor handling made deterministic (no longer programmatically removed based on layout conditions); the anchor-recovery flag was removed, i.e. the behavior is now default.
- No androidx Gerrit change references 486562294 directly (verified via the Gerrit REST API: zero results), so it is almost certainly tracked as a duplicate of the b/478210200 / b/487941042 family above.

### 1.4 Can we just pin a newer material3 over the BOM?

No — not cheaply. `androidx.compose.material3:material3:1.5.0-alpha21` declares hard dependencies on compose **runtime/ui/foundation 1.12.0-alpha03** (verified from its POM). Pinning it drags the entire Compose stack of the whole app from 1.11.0 stable onto 1.12 alphas. On top of that, the alpha line deprecates `rememberModalBottomSheetState` (alpha20) and reworks anchor semantics (alpha21) — exactly the APIs we use. That is a disproportionate risk for a beta app to fix one screen. It becomes the right move only when material3 1.5.0 reaches stable and lands in a BOM.

### 1.5 Candidate approaches evaluated

Requirements: keeps drag-to-dismiss; eliminates the jiggle for a mechanistic reason; low risk to the sheet's editable content (IME/keyboard).

**A. Downgrade material3 to 1.3.2 (strict pin over the BOM) — RECOMMENDED FIRST**
- Mechanism: 1.3.2 predates the AnchoredDraggable rework that introduced the bug. The public repro demonstrates the identical scenario (sheet + nested LazyColumn, fling at boundary) is clean on 1.3.2 and broken on 1.4.0. The 1.3.x sheet implementation also went through its own fling-jump fix cycle back in 2023/2024 (b/285847707 era, default animation changed to tween), so 1.3.2 is the most-fixed pre-rework stable.
- Keeps full gesture behavior: handle drag, content drag-at-top dismiss, fling handoff.
- Code impact: two files use `ExposedDropdownMenuAnchorType` (added in 1.4.0); swap to `MenuAnchorType` (present in 1.3.x, already imported elsewhere in the app). Grep found no other 1.4-only material3 API usage (no expressive APIs, no LoadingIndicator/WideNavigationRail/etc.). Everything else the app imports (PullToRefreshBox, SwipeToDismissBox, PrimaryTabRow, SegmentedButton, MenuAnchorType...) exists in 1.3.2.
- Risk: material3 1.3.2 was compiled against compose ~1.7; we run foundation/ui 1.11.0 from the BOM. AndroidX keeps binary shims for the experimental foundation APIs older material releases use, so this normally works, but it is not contractually guaranteed for experimental APIs. Detection is cheap and immediate: a NoSuchMethodError/AbstractMethodError crash the moment any sheet opens. The plan verifies this on-device before committing.
- Note: requires Gradle `strictly`, because a plain version declaration loses conflict resolution against the BOM's higher 1.4.0.

**B. App-side shim, variant 1: block fling residue from reaching the sheet — FALLBACK 1**
- Mechanism: insert our own `NestedScrollConnection` on the LazyColumn (it then sits between the list and the sheet's connection). It consumes fling-sourced leftover deltas (`NestedScrollSource.SideEffect`) and all leftover fling velocity (`onPostFling`), so the sheet never receives the fling residue that triggers the buggy settle ("drags toward dismiss and springs back"). Slow drag deltas still pass through, so dragging the list down from its top still dismisses the sheet.
- Keeps all of drag-to-dismiss. Zero dependency changes. Removable in one line when the library fix ships.
- Risk: only fixes the fling half with certainty. If the 1.4.0 anchor-recovery bug also jitters during slow drags (plausible per the issue title and our own observations), variant 1 is insufficient — escalate to variant 2.

**C. App-side shim, variant 2: fully isolate the list from the sheet — FALLBACK 2 (highest mechanical confidence)**
- Mechanism: same connection, but consume ALL leftovers (drag and fling). The sheet's nested-scroll bridge then never fires; the only way the sheet moves is the direct-drag path (handle and non-scrollable areas) — which is exactly TaskEntrySheet's interaction model, proven jiggle-free on 1.4.0. This is as close to a guarantee as a workaround gets: if the list cannot feed the sheet, the sheet cannot twitch during list interaction.
- Trade-off: swiping down on the LIST content no longer dismisses the sheet (the gesture dead-ends; no overscroll stretch either since we consume before the overscroll effect). Dismissal remains via drag handle, scrim tap, and system back. This is a real but modest UX reduction; Plan A and B preserve the full gesture.

**D. Pin material3 1.5.0-alpha21 (compose 1.12.0-alpha03 stack)** — rejected for now (see 1.4). Becomes the permanent resolution when 1.5.0 goes stable: remove whatever pin/shim this plan added, bump the BOM, re-run the verification matrix.

**E. `sheetGesturesEnabled = false`** — exists on 1.4.0's ModalBottomSheet and would eliminate the bug, but it disables ALL sheet gestures including the drag handle. Fails the keep-drag-to-dismiss requirement; strictly worse than C. Rejected.

**F. Custom anchoredDraggable sheet** — full control, but we would hand-roll scrim, predictive back, IME handling, a11y, and animation. Weeks of risk to fix one screen. Rejected.

### 1.6 Recommendation and confidence

Execute as a ladder, verifying on-device at each rung:

1. Restore the sheet wrapper (Task 1) — independent of the fix choice.
2. Plan A: pin material3 1.3.2 (Tasks 3). Expected outcome: full fix, full gestures.
3. If A fails (compile blowup beyond the two known files, runtime crash on sheet open, or jiggle persists): revert the pin, apply Plan B (Task 4).
4. If fling jiggle is gone but drag jiggle remains: escalate to Plan C (Task 5) — one-line change.

Confidence that the recommended ladder eliminates the jiggle:

- Plan A alone: ~75%. Strong empirical evidence (public repro: same scenario clean on 1.3.2), but our exact symptom set is not provably identical to the repro's, and the foundation-1.11 binary-compat question is open until tested.
- Plan C alone: ~90%. Mechanistic argument, not testimony: TaskEntrySheet already demonstrates on THIS app, THIS material3 version, THIS device that the direct-drag path is clean; Plan C reduces the detail sheet to that exact interaction model.
- Ladder overall (A, else B, else C): ~95%. The residual 5% covers the possibility that some jiggle component is unrelated to the nested-scroll bridge (e.g. IME-driven anchor re-derivation while dragging) — the verification matrix below is designed to expose that case specifically (keyboard-open drags).

What could NOT be verified during research, stated plainly: the actual status/comments of b/486562294 (login-gated); whether any 1.5.0 alpha is confirmed to fix that exact issue number (no release note cites it — the "fixed in alpha" claim in 5ea505d is inference from the related fixes listed in 1.3). This is why every rung of the plan ends in on-device verification rather than trusting the version bump.

---

## Part 2: Implementation Plan

Work on the current branch (`beta3-implementation`). `JAVA_HOME` must be set for Gradle: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` (bash). There is no ktlint gate in this repo; verification is `assembleDebug` + `testDebugUnitTest` + the on-device matrix. The jiggle is a touch-gesture artifact that no JVM/unit test can capture, so the test discipline for this plan is: compile + existing unit suite per task, plus the scripted manual matrix in Task 2 as the acceptance test, re-run at every rung.

### On-device verification matrix (used by Tasks 2, 3, 4, 5)

Device: physical phone preferred (emulator fling physics are forgiving). Use a task with enough content to scroll (10+ subtasks or a long description) AND a short task (content fits without scrolling). Run every gesture twice: keyboard closed, then keyboard open (tap the title field first).

- V1 Handle drag, slow: touch the drag handle, drag down ~30% and hold — the sheet must track the finger exactly, no oscillation. Release — settles back smoothly. Drag past ~60%, release — dismisses cleanly.
- V2 Content drag at top: with the list scrolled to the very top, slowly drag down on the list content. Plan A/B: the sheet follows the finger and can dismiss, no shake. Plan C: nothing moves (expected; dismiss via handle).
- V3 Fling at top boundary: with the list at top, flick down hard, 5 times in a row. The sheet must not twitch toward dismiss or spring back. THIS IS THE PRIMARY REPRO GESTURE.
- V4 Boundary stress: fling up hard to the bottom of the list, then immediately fling down hard to the top; repeat 5 times. No jitter at either boundary.
- V5 Mid-list scrolling: flick up/down repeatedly while mid-content. The sheet must stay anchored; only the list scrolls.
- V6 IME resize: tap the title field (keyboard opens) — the sheet must resize without a shake. Repeat V1, V3, V4 with the keyboard open. Dismiss the keyboard (back) — smooth resize back.
- V7 Short-content task: sheet wraps content (entry-sheet-like). V1 and V2 smooth.
- V8 Regressions: TaskEntrySheet (FAB) still smooth; all pickers (date, project, label, reminder, priority, relation) open and work above the sheet; auto-save still fires exactly once (edit the title, dismiss via drag, reopen — change persisted; check Vikunja that due date/labels were not clobbered); notification deep-link opens the sheet; delete task dismisses it.

### Task 1: Restore the ModalBottomSheet wrapper

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/VicuApp.kt:69,445-451`

Reference for cross-checking (the last working sheet version): `git show ca0317c:app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt`. Do NOT wholesale-copy that file — the wrapper swap below is the entire change; every `item { }` block stays untouched.

- [ ] **Step 1: Fix imports in TaskDetailScreen.kt**

Remove these four imports (only used by the full-screen wrapper):

```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
```

Add these three (alphabetical placement within their groups):

```kotlin
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
```

Keep `Surface` (still used for label chips) and `Box` (reused by the loading state below).

- [ ] **Step 2: Rename the composable and add the sheet state**

Replace:

```kotlin
fun TaskDetailScreen(
    taskId: Long,
    onDismiss: () -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
```

with:

```kotlin
fun TaskDetailSheet(
    taskId: Long,
    onDismiss: () -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
```

- [ ] **Step 3: Swap the wrapper opening**

Replace this block (currently around lines 143-191; the comment, BackHandler, Surface, Scaffold, loading state, and LazyColumn modifier):

```kotlin
    // Full-screen instead of a ModalBottomSheet: the M3 sheet's anchored-drag has a nested-scroll
    // anchor-recovery bug (issuetracker.google.com/issues/486562294, fixed only in alpha Compose)
    // that made a scrollable child shake/spring on drag. A plain screen has no drag-to-dismiss, so
    // the whole class of bugs is gone. Dismiss via the close icon or system back.
    BackHandler { onDismiss() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Edit task") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                )
            },
        ) { innerPadding ->
            val task = state.task

            if (state.isLoading || task == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            val imageTokenIds = remember(task.description) {
                ImageTokens.findImageRefs(task.description)
                    .filterIsInstance<ImageTokens.ImageRef.Image>()
                    .map { it.attachmentId }
                    .toSet()
            }
            val visibleAttachments = state.attachments.filter { it.id !in imageTokenIds }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
```

with:

```kotlin
    // ModalBottomSheet with a scrollable child jiggles on drag/fling with material3 1.4.0
    // (issuetracker.google.com/issues/486562294, an AnchoredDraggable-rework regression in the
    // sheet's nested-scroll bridge; no stable fix exists as of 2026-06). Fixed here per
    // docs/superpowers/plans/2026-06-11-restore-task-detail-sheet.md. When material3 1.5.0
    // stable lands in the BOM, drop the fix (version pin or nested-scroll shim) and re-verify.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { VicuDragHandle() },
    ) {
        val task = state.task

        if (state.isLoading || task == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }

        val imageTokenIds = remember(task.description) {
            ImageTokens.findImageRefs(task.description)
                .filterIsInstance<ImageTokens.ImageRef.Image>()
                .map { it.attachmentId }
                .toSet()
        }
        val visibleAttachments = state.attachments.filter { it.id !in imageTokenIds }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                // No explicit height: let the LazyColumn wrap its content and clamp to the
                // sheet's available area (like TaskEntrySheet). A fixed/variable height fights
                // the sheet's content-derived expanded anchor and shakes during drag-dismiss.
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
```

Everything between this `LazyColumn(...) {` and the end of the `// Error display` item is unchanged — leave it alone. (The 5ea505d conversion never re-indented the item blocks, so they are still at sheet-era depth; with the wrapper restored, their current indentation is correct again. Do not reindent anything.)

- [ ] **Step 4: Swap the wrapper closing**

At the end of the LazyColumn (after the `// Error display` item block), the current file closes with three braces before the `// Delete confirmation dialog` comment:

```kotlin
        }
        }
    }
```

Replace with two (LazyColumn, then ModalBottomSheet):

```kotlin
        }
    }
```

- [ ] **Step 5: Update the call site in VicuApp.kt**

Replace line 69:

```kotlin
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailScreen
```

with:

```kotlin
import com.rendyhd.vicu.ui.screens.taskdetail.TaskDetailSheet
```

Replace the block at lines 445-451:

```kotlin
    // Task Detail (full-screen edit)
    if (showTaskDetailSheet) {
        TaskDetailScreen(
            taskId = taskDetailTaskId,
            onDismiss = { showTaskDetailSheet = false },
        )
    }
```

with:

```kotlin
    // Task Detail (bottom sheet edit)
    if (showTaskDetailSheet) {
        TaskDetailSheet(
            taskId = taskDetailTaskId,
            onDismiss = { showTaskDetailSheet = false },
        )
    }
```

- [ ] **Step 6: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. If unresolved references appear, they will be in the two touched files only — fix imports per Step 1.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt app/src/main/java/com/rendyhd/vicu/ui/VicuApp.kt
git commit -m "Restore task detail as ModalBottomSheet (wrapper only, jiggle fix follows)"
```

### Task 2: Baseline on-device repro

**Files:** none (manual verification).

- [ ] **Step 1: Install**

Run: `./gradlew installDebug` (debug build installs alongside release thanks to the `.debug` suffix).

- [ ] **Step 2: Run the verification matrix**

Run V1-V7 from the matrix above on material3 1.4.0 as-is. Expected: jiggle reproduces, most reliably on V3/V4 (fling at boundary) and possibly V2 (slow content drag). Write down exactly which gestures jiggle — this is the baseline that later rungs are judged against. If V2 jiggles, note it specifically: that determines whether Plan B (fling-only shim) can ever be sufficient.

If, unexpectedly, nothing jiggles: stop here, run V8, commit nothing further, and report — the bug may be device/version dependent and the restore alone suffices.

### Task 3: Plan A — pin material3 1.3.2

**Files:**
- Modify: `gradle/libs.versions.toml:42` (the material3 library entry)
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/components/shared/CustomListDialog.kt:29,423,464`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/components/shared/ProjectEditDialog.kt:22,116`

- [ ] **Step 1: Pin with `strictly` in the version catalog**

In `gradle/libs.versions.toml`, replace:

```toml
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
```

with:

```toml
# Pinned below the BOM's 1.4.0: that release's AnchoredDraggable rework makes ModalBottomSheet
# jiggle with scrollable content (b/486562294); 1.3.2 is the last clean stable. strictly() is
# required because a plain version loses Gradle conflict resolution against the BOM constraint.
# Remove when material3 1.5.0 stable lands in the BOM, then re-run the sheet verification matrix.
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3", version = { strictly = "1.3.2" } }
```

- [ ] **Step 2: Replace the 1.4.0-only menu anchor type (2 files)**

In `CustomListDialog.kt`: replace the import `androidx.compose.material3.ExposedDropdownMenuAnchorType` with `androidx.compose.material3.MenuAnchorType`, and both usages:

```kotlin
.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
```

become:

```kotlin
.menuAnchor(MenuAnchorType.PrimaryNotEditable),
```

Same change in `ProjectEditDialog.kt` (one import, one usage).

- [ ] **Step 3: Compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Also confirm the resolution actually downgraded:
`./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep "material3:material3"`
Expected output contains `androidx.compose.material3:material3:{strictly 1.3.2} -> 1.3.2`.

ABANDON CONDITION: if compilation surfaces unresolved material3 references beyond the two files in Step 2, list them; if any lacks a 1.3.x equivalent, run `git checkout -- gradle/libs.versions.toml app/src/main/java/com/rendyhd/vicu/ui/components/shared/` and jump to Task 4.

- [ ] **Step 4: Unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: all pass (these don't exercise material3, so failures would indicate something unrelated broke — investigate before continuing).

- [ ] **Step 5: Install and verify on device**

Run: `./gradlew installDebug`, then the full matrix V1-V8, keyboard closed and open.

Watch logcat while opening BOTH sheets (detail and entry) and while dragging:
`adb logcat -s AndroidRuntime` — a `NoSuchMethodError` / `AbstractMethodError` mentioning `AnchoredDraggable`, `DraggableAnchors`, or `androidx.compose.material3.SheetState` means material3 1.3.2 is binary-incompatible with foundation 1.11.0.

Outcomes:
- All pass: proceed to Step 6.
- Crash on sheet open/drag (binary incompat), or jiggle persists on V2/V3/V4: revert (`git checkout -- gradle/libs.versions.toml app/src/main/java/com/rendyhd/vicu/ui/components/shared/`) and go to Task 4. Do not try a full-BOM downgrade to 2025.09.00 first — it rolls every Compose library back four months app-wide and is worse than the Task 4 shim.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/src/main/java/com/rendyhd/vicu/ui/components/shared/CustomListDialog.kt app/src/main/java/com/rendyhd/vicu/ui/components/shared/ProjectEditDialog.kt
git commit -m "Pin material3 to 1.3.2 to fix ModalBottomSheet nested-scroll jiggle (b/486562294)"
```

Plan complete if this rung passed — skip Tasks 4 and 5.

### Task 4: Plan B — block fling residue from reaching the sheet (only if Task 3 failed)

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt`

- [ ] **Step 1: Add the connection and imports**

New imports in TaskDetailScreen.kt:

```kotlin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
```

At file scope (below the imports, above `TaskDetailSheet`):

```kotlin
// material3 1.4.0's sheet nested-scroll bridge is buggy (b/486562294): fling residue handed
// up from a scrollable child makes the sheet twitch toward dismiss and spring back. This
// connection sits between the LazyColumn and the sheet, swallowing fling-driven leftovers so
// the sheet only moves from real drags. Remove when material3 ships the fix in stable.
private object BlockFlingToSheet : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.SideEffect) available else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}
```

- [ ] **Step 2: Attach it to the LazyColumn**

In the LazyColumn modifier chain from Task 1 Step 3, add `.nestedScroll(BlockFlingToSheet)` as the first modifier:

```kotlin
        LazyColumn(
            modifier = Modifier
                .nestedScroll(BlockFlingToSheet)
                .fillMaxWidth()
                // No explicit height: let the LazyColumn wrap its content and clamp to the
                // sheet's available area (like TaskEntrySheet). A fixed/variable height fights
                // the sheet's content-derived expanded anchor and shakes during drag-dismiss.
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
```

(Modifier order within the chain doesn't affect correctness here — any position in the user-supplied chain is upstream of the LazyColumn's internal scrollable and downstream of the sheet's connection — but first keeps it visible.)

- [ ] **Step 3: Build, install, verify**

Run: `./gradlew assembleDebug installDebug`, then the matrix. Expected: V3/V4 (fling) clean; V2 (slow content drag) still dismisses the sheet.
- All clean: commit (Step 4) and stop.
- V2 still jiggles during slow drag: proceed to Task 5 before committing.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt
git commit -m "Shield task detail sheet from fling residue (b/486562294 workaround)"
```

### Task 5: Plan C — fully isolate the list from the sheet (only if Task 4 left drag jiggle)

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt` (the object from Task 4)

- [ ] **Step 1: Consume all leftovers**

Replace the object body from Task 4 with:

```kotlin
// material3 1.4.0's sheet nested-scroll bridge is buggy (b/486562294): leftovers handed up
// from a scrollable child make the sheet jitter during drags and flings. Consume everything
// the list leaves over, so the sheet only moves via its direct drag path (handle and
// non-scrollable areas) -- the same interaction model as the jiggle-free TaskEntrySheet.
// Trade-off: swiping down on the list no longer dismisses; the handle, scrim, and back do.
// Remove when material3 ships the fix in stable.
private object BlockFlingToSheet : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}
```

- [ ] **Step 2: Build, install, verify**

Run: `./gradlew assembleDebug installDebug`, then the matrix. Expected: V2 now intentionally inert on list content (dismiss via handle — confirm the handle path V1 is smooth, since it is now the only drag-dismiss path); V3/V4/V5/V6 clean. If V1 ITSELF jiggles at this point, the problem is outside the nested-scroll bridge entirely — stop, capture a screen recording plus `adb logcat`, and re-open research (that would contradict the TaskEntrySheet evidence and suggest something detail-sheet-specific like IME interaction; test with a task while never focusing a text field to isolate).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt
git commit -m "Isolate task detail sheet from list nested scroll (b/486562294 workaround)"
```

### Task 6: Wrap-up

- [ ] **Step 1: Re-run the full suite**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Record the future cleanup trigger**

Whichever rung shipped, the durable note already lives in the code comments and this plan: when a Compose BOM ships material3 1.5.0 stable, remove the fix that was applied — the 1.3.2 pin (Task 3) and/or the `BlockFlingToSheet` object plus its `.nestedScroll(...)` line (Tasks 4/5) — bump, and re-run the verification matrix before trusting it. The MenuAnchorType swap from Task 3 can stay either way (MenuAnchorType still exists in 1.4+).
