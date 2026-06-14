# Beta Feedback Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the four open beta-feedback items: the missing in-field pill for the `!` (bang-today) shortcut, the NLP autocomplete popup covering the title field, the project picker showing sub-projects detached from their parents, the swipe gesture still committing on edge flicks — plus add long-press drag-to-reorder for tasks in project views.

**Architecture:** All fixes are local to existing components. The bang fix adds token emission in the pure parser (unit-tested). The picker fix extracts the existing `buildProjectTree` from Settings into a shared util (unit-tested). The swipe fix adds a drag-distance guard and an edge dead-zone to `SwipeableTaskItem`. Reorder reuses the `sh.calvin.reorderable` library already used by the drawer, with pure list/position math in a testable util; positions write optimistically to Room and best-effort to the Vikunja view-position API (same policy as `anchorNewTaskAtEnd`).

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Room, Retrofit, `sh.calvin.reorderable` 2.4.3 (already a dependency), JUnit 4 for unit tests.

---

## Decisions already made (do not revisit)

- **Sheet "opens in two steps" is WON'T-FIX.** The sheet-then-keyboard sequencing in `TaskEntrySheet.kt:116-122` is the deliberate fix for the open-animation stutter. Leave it.
- **Title wrapping is ALREADY DONE** (entry sheet `maxLines = 3`, detail `maxLines = 3`, rows `maxLines = 2`). No task for it.
- **Reorder affordance: long-press lifts the row** (Things-3 style), per user decision. A long-press that never moves the row falls through to multi-select, so selection mode keeps working in project views.
- **Reorder scope: project screens only.** Inbox stays `created DESC`; the Inbox conversion (review PAR-1) is a separate future plan.
- **Only undated tasks are draggable.** `sortProjectTasks` (`util/TaskSort.kt:13`) pins dated tasks first by due date; manual position order only governs the undated block. Dragging dated tasks is vetoed.
- **Offline reorder is best-effort.** Optimistic Room write + remote POST that swallows failures (mirrors `anchorNewTaskAtEnd`, `TaskRepositoryImpl.kt:65-87`). A reorder made offline can be reverted by the next refresh. Accepted limitation; do NOT build a pending-action queue type for positions.

## Known limitation (document, don't fix)

In projects whose tasks all have server position `0.0` (created before position anchoring existed), dropping a task *between* two zero-position rows computes `0.0` and the order won't visibly change. Dropping at the end assigns `prev + 65536` and works, which progressively heals such lists.

## Build & test commands

Run via the Bash tool (JAVA_HOME must be exported in each command):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "<pattern>"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDebug
```

There is no ktlint task in this project. Verification = compile + unit tests (+ manual device steps listed per task).

## File structure

| File | Change |
|---|---|
| `app/src/main/java/com/rendyhd/vicu/util/parser/ExtractDates.kt` | Add `BangForm` enum; `BangTodayResult` gains a `form` field |
| `app/src/main/java/com/rendyhd/vicu/util/parser/TaskParser.kt` | Step 7 emits a DATE token for the bang; gate on DATE suppression |
| `app/src/main/java/com/rendyhd/vicu/ui/screens/taskentry/TaskEntryViewModel.kt` | Save-time bang fallback respects DATE suppression |
| `app/src/test/java/com/rendyhd/vicu/util/parser/TaskParserTest.kt` | New bang-token tests |
| `app/src/main/java/com/rendyhd/vicu/ui/components/task/NlpAutocompleteDropdown.kt` | Popup offset below the anchor field |
| `app/src/main/java/com/rendyhd/vicu/ui/components/task/TaskEntrySheet.kt` | Measure title field size, pass to dropdown |
| `app/src/main/java/com/rendyhd/vicu/util/ProjectTree.kt` | NEW — shared `buildProjectTree` (moved from SettingsScreen) |
| `app/src/test/java/com/rendyhd/vicu/util/ProjectTreeTest.kt` | NEW — tree tests |
| `app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsScreen.kt` | Delete private `buildProjectTree` (lines ~2317-2335), import shared |
| `app/src/main/java/com/rendyhd/vicu/ui/components/picker/ProjectPickerDialog.kt` | Tree-ordered, depth-indented list |
| `app/src/main/java/com/rendyhd/vicu/ui/components/task/SwipeableTaskItem.kt` | Drag-distance guard + edge dead-zone |
| `app/src/main/java/com/rendyhd/vicu/util/ReorderLogic.kt` | NEW — pure move/position math |
| `app/src/test/java/com/rendyhd/vicu/util/ReorderLogicTest.kt` | NEW — reorder math tests |
| `app/src/main/java/com/rendyhd/vicu/data/local/dao/TaskDao.kt` | Add `updatePosition` query |
| `app/src/main/java/com/rendyhd/vicu/domain/repository/TaskRepository.kt` | Add `updatePosition` |
| `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt` | Implement `updatePosition` |
| `app/src/main/java/com/rendyhd/vicu/ui/screens/project/ProjectViewModel.kt` | `onTaskMoved` / `onTaskDropped` |
| `app/src/main/java/com/rendyhd/vicu/ui/screens/project/ProjectScreen.kt` | `ReorderableItem` wiring, long-press handle |

---

### Task 1: Bang-today emits an in-field DATE pill

The `!` → today shortcut (`TaskParser.kt:66-72`) sets the due date but never adds a `ParsedToken`, so `NlpVisualTransformation` never highlights the `!`. Emit a token by mapping the bang back to its raw-input index. Also gate the bang on DATE suppression so dismissing the "Today" chip actually sticks (today it silently reappears).

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/util/parser/ExtractDates.kt:14-17, 60-94`
- Modify: `app/src/main/java/com/rendyhd/vicu/util/parser/TaskParser.kt:65-72`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskentry/TaskEntryViewModel.kt` (~line 361, the save-time fallback)
- Test: `app/src/test/java/com/rendyhd/vicu/util/parser/TaskParserTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `TaskParserTest.kt` (inside the class, after the existing tests; `todoist` config and imports already exist in the file):

```kotlin
    // ─── Bang-today token ─────────────────────────────────────

    @Test
    fun `trailing bang emits a DATE token at the bang position`() {
        val r = TaskParser.parse("buy milk !", todoist)
        assertEquals("buy milk", r.title)
        assertNotNull(r.dueDate)
        val token = r.tokens.single { it.type == TokenType.DATE }
        assertEquals(9, token.start)
        assertEquals(10, token.end)
        assertEquals("!", token.raw)
    }

    @Test
    fun `leading bang emits a DATE token at index zero`() {
        val r = TaskParser.parse("! buy milk", todoist)
        assertEquals("buy milk", r.title)
        val token = r.tokens.single { it.type == TokenType.DATE }
        assertEquals(0, token.start)
        assertEquals(1, token.end)
    }

    @Test
    fun `standalone bang emits a DATE token`() {
        val r = TaskParser.parse("!", todoist)
        assertEquals("", r.title)
        assertNotNull(r.dueDate)
        val token = r.tokens.single { it.type == TokenType.DATE }
        assertEquals(0, token.start)
        assertEquals(1, token.end)
    }

    @Test
    fun `bang token maps to raw input even after a consumed label`() {
        // raw: "buy milk ! @work" — the label is consumed, bang is at raw index 9
        val r = TaskParser.parse("buy milk ! @work", todoist)
        assertEquals("buy milk", r.title)
        assertEquals(listOf("work"), r.labels)
        val token = r.tokens.single { it.type == TokenType.DATE }
        assertEquals(9, token.start)
        assertEquals(10, token.end)
    }

    @Test
    fun `priority bang does not emit a DATE token`() {
        val r = TaskParser.parse("task !1", todoist)
        assertEquals(4, r.priority)
        assertNull(r.dueDate)
        assertTrue(r.tokens.none { it.type == TokenType.DATE })
    }

    @Test
    fun `suppressing DATE disables the bang-today shortcut`() {
        val cfg = todoist.copy(suppressTypes = setOf(TokenType.DATE))
        val r = TaskParser.parse("buy milk !", cfg)
        assertNull(r.dueDate)
        assertEquals("buy milk !", r.title)
        assertTrue(r.tokens.none { it.type == TokenType.DATE })
    }
```

Note: check the existing priority tests for what `"!1"` maps to (Vikunja `!1` = highest). If the existing test asserts a different number for `!1`, match that number in the `priority bang` test — the assertion that matters is `tokens.none { DATE }`.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.parser.TaskParserTest"
```

Expected: the four token tests FAIL (`tokens.single` throws — no DATE token), the suppression test FAILS (dueDate is set). Existing tests still pass.

- [ ] **Step 3: Add `BangForm` to ExtractDates.kt**

Replace the `BangTodayResult` declaration (`ExtractDates.kt:14-17`) with:

```kotlin
enum class BangForm { NONE, STANDALONE, LEADING, TRAILING }

data class BangTodayResult(
    val title: String,
    val dueDate: LocalDateTime?,
    val form: BangForm = BangForm.NONE,
)
```

Then tag the three success returns in `extractBangToday` (lines 60-94):
- standalone: `return BangTodayResult("", LocalDate.now().atStartOfDay(), BangForm.STANDALONE)`
- trailing: add `BangForm.TRAILING` as the third argument
- leading: add `BangForm.LEADING` as the third argument
- the final `return BangTodayResult(input, null)` stays as-is (defaults to `NONE`).

- [ ] **Step 4: Emit the token in TaskParser step 7**

Replace `TaskParser.kt:65-72` (the step-7 block) with:

```kotlin
        // 7. Leading/trailing ! → today (when enabled, no date found, and DATE not suppressed —
        // the suppression gate makes dismissing the Today chip stick for bang-created dates)
        if (config.bangToday && result.dueDate == null && TokenType.DATE !in suppress) {
            val bang = extractBangToday(result.title)
            if (bang.dueDate != null) {
                result.title = bang.title
                result.dueDate = bang.dueDate
                // The bang was found in the rebuilt title; map it back to the raw input as
                // the first (leading) or last (trailing/standalone) non-consumed,
                // non-whitespace character so the field highlights it like other tokens.
                val bangIndex = when (bang.form) {
                    BangForm.LEADING -> rawInput.indices.firstOrNull { i ->
                        !rawInput[i].isWhitespace() && consumed.none { r -> i in r }
                    }
                    else -> rawInput.indices.lastOrNull { i ->
                        !rawInput[i].isWhitespace() && consumed.none { r -> i in r }
                    }
                }
                if (bangIndex != null && rawInput[bangIndex] == '!') {
                    result.tokens.add(
                        ParsedToken(
                            type = TokenType.DATE,
                            start = bangIndex,
                            end = bangIndex + 1,
                            value = bang.dueDate,
                            raw = "!",
                        ),
                    )
                }
            }
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.parser.TaskParserTest"
```

Expected: PASS, including all pre-existing tests.

- [ ] **Step 6: Make the save-time fallback respect suppression**

In `TaskEntryViewModel.kt`, Read the file and find the save-time fallback (around line 361):

```kotlin
        // Bang-today fallback (works even when parser disabled)
        if (config.bangToday && (dueDate.isBlank() || DateUtils.isNullDate(dueDate))) {
```

Change the condition to:

```kotlin
        // Bang-today fallback (works even when parser disabled; skipped when the user
        // dismissed the Today chip — DATE is then in suppressTypes)
        if (config.bangToday && TokenType.DATE !in config.suppressTypes &&
            (dueDate.isBlank() || DateUtils.isNullDate(dueDate))
        ) {
```

`TokenType` is already imported in this file.

- [ ] **Step 7: Compile and run the full unit test suite**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/util/parser/ExtractDates.kt app/src/main/java/com/rendyhd/vicu/util/parser/TaskParser.kt app/src/main/java/com/rendyhd/vicu/ui/screens/taskentry/TaskEntryViewModel.kt app/src/test/java/com/rendyhd/vicu/util/parser/TaskParserTest.kt
git commit -m "Highlight the bang-today shortcut as an in-field pill (beta feedback)"
```

---

### Task 2: Anchor the autocomplete popup below the title field

`NlpAutocompleteDropdown.kt:54` uses a bare `Popup` with no offset, so it renders at the top-left of the Box wrapping the title field — directly on top of it. Measure the field and offset the popup below it, matching the field's width.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/components/task/NlpAutocompleteDropdown.kt:34-90`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/components/task/TaskEntrySheet.kt:152-191`

- [ ] **Step 1: Add an anchor-size parameter to the dropdown**

In `NlpAutocompleteDropdown.kt`, change the composable signature and Popup:

```kotlin
@Composable
fun NlpAutocompleteDropdown(
    inputValue: String,
    cursorPosition: Int,
    prefixes: SyntaxPrefixes,
    projects: List<Project>,
    labels: List<Label>,
    enabled: Boolean,
    onSelect: (newText: String, newCursor: Int) -> Unit,
    anchorSize: IntSize = IntSize.Zero,
) {
    if (!enabled) return

    val suggestions by remember(inputValue, cursorPosition, prefixes, projects, labels) {
        derivedStateOf {
            computeSuggestions(inputValue, cursorPosition, prefixes, projects, labels)
        }
    }

    if (suggestions.isEmpty()) return

    val density = LocalDensity.current
    Popup(
        // Place the suggestion list just below the anchor (title field) instead of on
        // top of it — the default Popup position covers the field being typed in.
        offset = IntOffset(0, anchorSize.height),
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shadowElevation = 4.dp,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            LazyColumn(
                modifier = Modifier
                    .then(
                        if (anchorSize.width > 0) {
                            Modifier.width(with(density) { anchorSize.width.toDp() })
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .heightIn(max = 200.dp),
            ) {
```

(The `items { ... }` body is unchanged.) Add imports:

```kotlin
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
```

- [ ] **Step 2: Measure the title field in TaskEntrySheet**

In `TaskEntrySheet.kt`, inside the `Box` (line 152), track the field size and pass it down. Above the `OutlinedTextField`:

```kotlin
            Box {
                var fieldSize by remember { mutableStateOf(IntSize.Zero) }
                OutlinedTextField(
```

Add to the text field's modifier chain (line 161-163):

```kotlin
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onSizeChanged { fieldSize = it },
```

And pass it to the dropdown call (line 176):

```kotlin
                NlpAutocompleteDropdown(
                    inputValue = textFieldValue.text,
                    cursorPosition = textFieldValue.selection.start,
                    prefixes = getPrefixes(state.parserConfig.syntaxMode),
                    projects = state.allProjects,
                    labels = state.allLabels,
                    enabled = state.parserConfig.enabled,
                    onSelect = { newText, newCursor ->
                        textFieldValue = TextFieldValue(
                            text = newText,
                            selection = TextRange(newCursor),
                        )
                        viewModel.setTitle(newText)
                    },
                    anchorSize = fieldSize,
                )
```

Add imports to `TaskEntrySheet.kt`:

```kotlin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
```

- [ ] **Step 3: Compile**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual device check**

Install (`./gradlew installDebug`), open the new-task sheet, type `buy milk @` — the suggestion list must appear *below* the title field, not over it, and match the field's width. Tap a suggestion to confirm selection still works.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/components/task/NlpAutocompleteDropdown.kt app/src/main/java/com/rendyhd/vicu/ui/components/task/TaskEntrySheet.kt
git commit -m "Anchor the NLP autocomplete popup below the title field (beta feedback)"
```

---

### Task 3: Project picker nests sub-projects under their parents

`ProjectPickerDialog.kt:45-52` sorts by raw `parentProjectId`, dumping all children at the bottom. Extract the existing tree-flattening logic from `SettingsScreen.kt:2317-2335` into a shared util and use it in the picker with depth-based indentation.

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/util/ProjectTree.kt`
- Create: `app/src/test/java/com/rendyhd/vicu/util/ProjectTreeTest.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsScreen.kt` (delete lines ~2317-2335, add import)
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/components/picker/ProjectPickerDialog.kt:45-60`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rendyhd/vicu/util/ProjectTreeTest.kt`:

```kotlin
package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectTreeTest {

    private fun p(id: Long, parent: Long = 0L): Project =
        Project(id = id, title = "P$id", parentProjectId = parent)

    @Test
    fun `children follow their parent depth-first`() {
        val tree = buildProjectTree(listOf(p(1), p(2), p(10, parent = 1)))
        assertEquals(listOf(1L, 10L, 2L), tree.map { it.first.id })
        assertEquals(listOf(0, 1, 0), tree.map { it.second })
    }

    @Test
    fun `nested children increase depth`() {
        val tree = buildProjectTree(listOf(p(1), p(2, parent = 1), p(3, parent = 2)))
        assertEquals(listOf(1L, 2L, 3L), tree.map { it.first.id })
        assertEquals(listOf(0, 1, 2), tree.map { it.second })
    }

    @Test
    fun `orphans land at root level`() {
        val tree = buildProjectTree(listOf(p(1), p(5, parent = 99)))
        assertEquals(listOf(1L, 5L), tree.map { it.first.id })
        assertEquals(listOf(0, 0), tree.map { it.second })
    }

    @Test
    fun `cyclic parents terminate`() {
        // A->B->A: neither reachable from root; both land via the orphan pass.
        val tree = buildProjectTree(listOf(p(1, parent = 2), p(2, parent = 1)))
        assertEquals(setOf(1L, 2L), tree.map { it.first.id }.toSet())
    }

    @Test
    fun `sibling input order is preserved`() {
        val tree = buildProjectTree(listOf(p(3), p(1), p(2)))
        assertEquals(listOf(3L, 1L, 2L), tree.map { it.first.id })
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.ProjectTreeTest"
```

Expected: FAIL to compile — `buildProjectTree` unresolved in `com.rendyhd.vicu.util`.

- [ ] **Step 3: Create the shared util**

Create `app/src/main/java/com/rendyhd/vicu/util/ProjectTree.kt` — this is a verbatim move of the Settings implementation:

```kotlin
package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project

/**
 * Flatten projects into depth-first tree order: each parent immediately followed by its
 * children, paired with the nesting depth. Sibling order follows the input list order.
 * Orphans (parent not in the list) land at root level; the visited guard terminates on
 * pre-existing cyclic parent data (A->B->A).
 */
fun buildProjectTree(projects: List<Project>): List<Pair<Project, Int>> {
    val childrenMap = projects.groupBy { it.parentProjectId }
    val result = mutableListOf<Pair<Project, Int>>()
    val visited = mutableSetOf<Long>()
    fun addChildren(parentId: Long, depth: Int) {
        childrenMap[parentId]?.forEach { project ->
            if (!visited.add(project.id)) return@forEach
            result.add(project to depth)
            addChildren(project.id, depth + 1)
        }
    }
    addChildren(0L, 0)
    // Add any orphans (parent not in list) at root level
    val addedIds = result.map { it.first.id }.toSet()
    projects.filter { it.id !in addedIds }.forEach { result.add(it to 0) }
    return result
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.ProjectTreeTest"
```

Expected: PASS.

- [ ] **Step 5: Point SettingsScreen at the shared util**

In `SettingsScreen.kt`: delete the entire `private fun buildProjectTree(...)` (lines ~2317-2335 — Read the file first; line numbers may have drifted) and add the import:

```kotlin
import com.rendyhd.vicu.util.buildProjectTree
```

The call site at `SettingsScreen.kt:1329` (`buildProjectTree(state.projects)`) stays unchanged.

- [ ] **Step 6: Use the tree in ProjectPickerDialog**

In `ProjectPickerDialog.kt`, replace the `sorted` computation (lines 45-52) with:

```kotlin
    val sorted = remember(projects, inboxProjectId) {
        val visible = projects
            .filter { !it.isArchived }
            .sortedWith(compareBy({ it.id != inboxProjectId }, { it.position }, { it.title }))
        buildProjectTree(visible)
    }
```

(The pre-sort orders siblings by position and pins the inbox project first at root level; `groupBy` inside the tree builder preserves that order per parent.)

Then update the list rendering. Replace:

```kotlin
                items(sorted, key = { it.id }) { project ->
                    val isSelected = project.id == selectedProjectId
                    val indent = if (project.parentProjectId > 0) 24.dp else 0.dp
```

with:

```kotlin
                items(sorted, key = { it.first.id }) { (project, depth) ->
                    val isSelected = project.id == selectedProjectId
                    val indent = (depth * 16).coerceAtMost(48).dp
```

The rest of the row body is unchanged. Add the import:

```kotlin
import com.rendyhd.vicu.util.buildProjectTree
```

- [ ] **Step 7: Compile and run all unit tests**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest
```

Expected: PASS (and `compileDebugKotlin` implicitly verifies the SettingsScreen edit).

- [ ] **Step 8: Manual device check**

Open the new-task sheet → Project chip. Sub-projects must appear directly under their parents, indented; the inbox project first.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/util/ProjectTree.kt app/src/test/java/com/rendyhd/vicu/util/ProjectTreeTest.kt app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsScreen.kt app/src/main/java/com/rendyhd/vicu/ui/components/picker/ProjectPickerDialog.kt
git commit -m "Project picker: nest sub-projects under their parents (beta feedback)"
```

---

### Task 4: Swipe — drag-distance guard and edge dead-zone

Two gaps remain from the swipe pass: (a) M3's `SwipeToDismissBox` commits on fling velocity regardless of `positionalThreshold`, so a fast flick still completes a task below the 50% mark; (b) there is no edge dead-zone, so swipes that begin in the system-gesture region fight OS back and the drawer-open gesture.

Design notes for (b): do NOT consume edge events — that would also block the drawer-open gesture over task rows. Instead, observe the down position in the Initial pointer pass and disable the dismiss directions for that gesture; the drawer/system then handle it.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/components/task/SwipeableTaskItem.kt:46-108`

- [ ] **Step 1: Rewrite the state + box section**

Replace the body of `SwipeableTaskItem` from `val haptic = ...` (line 46) through the end of the `SwipeToDismissBox` call (line 108) with:

```kotlin
    val haptic = LocalHapticFeedback.current

    // SwipeToDismissBox commits on fling velocity regardless of positionalThreshold, so a
    // quick flick could still trigger below the 50% mark. Track the live offset (Ref dance:
    // confirmValueChange is created before the state exists) and only fire when the row was
    // actually dragged at least half way.
    var rowWidthPx by remember { mutableStateOf(0f) }
    var dismissStateRef by remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
        confirmValueChange = { value ->
            val draggedFraction = dismissStateRef
                ?.let { state -> runCatching { abs(state.requireOffset()) }.getOrNull() }
                ?.let { offset -> if (rowWidthPx > 0f) offset / rowWidthPx else 1f }
                ?: 1f
            if (draggedFraction >= 0.5f) {
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> onToggleDone()
                    SwipeToDismissBoxValue.EndToStart -> onSchedule()
                    SwipeToDismissBoxValue.Settled -> {}
                }
            }
            // Always spring back: the undo pattern relies on the row remaining visible.
            false
        },
    )
    SideEffect { dismissStateRef = dismissState }

    // One haptic per threshold crossing (edge-triggered via targetValue).
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue }
            .collect { target ->
                if (target != SwipeToDismissBoxValue.Settled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
    }

    // Swipe is disabled while selecting (or when explicitly disabled): render the plain row,
    // which still carries the long-press-to-select and selection checkbox.
    if (!enabled || selectionActive) {
        TaskItem(
            task = task,
            onToggleDone = onToggleDone,
            onClick = onClick,
            modifier = modifier.padding(start = contentStartPadding),
            selectionActive = selectionActive,
            selected = selected,
            onLongClick = onLongClick,
        )
        return
    }

    // Edge dead-zone: gestures that begin inside the system-gesture insets (24dp minimum)
    // belong to OS back / the nav drawer, not the row swipe. The dismiss directions are
    // disabled for that gesture instead of consuming its events, so the drawer edge-swipe
    // still works on top of task rows.
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures.asPaddingValues()
    val leftDeadZone = max(gestureInsets.calculateLeftPadding(layoutDirection), 24.dp)
    val rightDeadZone = max(gestureInsets.calculateRightPadding(layoutDirection), 24.dp)
    var gestureFromEdge by remember { mutableStateOf(false) }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .onSizeChanged { rowWidthPx = it.width.toFloat() }
            .pointerInput(leftDeadZone, rightDeadZone) {
                val leftPx = leftDeadZone.toPx()
                val rightPx = rightDeadZone.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    gestureFromEdge = down.position.x < leftPx ||
                        down.position.x > size.width - rightPx
                }
            },
        backgroundContent = {
            SwipeBackground(
                dismissDirection = dismissState.dismissDirection,
                progress = dismissState.progress,
            )
        },
        enableDismissFromStartToEnd = !task.done && !gestureFromEdge,
        enableDismissFromEndToStart = !task.done && !gestureFromEdge,
    ) {
        TaskItem(
            task = task,
            onToggleDone = onToggleDone,
            onClick = onClick,
            modifier = Modifier.padding(start = contentStartPadding),
            onLongClick = onLongClick,
        )
    }
```

- [ ] **Step 2: Add the imports**

Add to `SwipeableTaskItem.kt`:

```kotlin
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.max
import kotlin.math.abs
```

(`getValue` is already imported; `SwipeBackground` is unchanged.)

- [ ] **Step 3: Compile**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual device check**

Install and verify, on a task list:
1. A slow drag past half the row width completes / schedules — unchanged behavior.
2. A short fast flick (well under half) does NOT trigger the action (the row springs back, possibly after the preview haptic — acceptable).
3. A swipe starting at the very left edge opens the drawer (or triggers OS back in gesture nav) and does NOT complete the task.
4. A swipe starting away from the edges still works.
5. Vertical scrolling starting at the screen edge still scrolls.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/components/task/SwipeableTaskItem.kt
git commit -m "Swipe: ignore edge-started gestures and velocity-only commits (beta feedback)"
```

---

### Task 5: Pure reorder math (move + drop position)

All reorder decisions live in a pure, unit-tested util: which moves are legal (undated tasks only, same list), and what position a dropped task gets (midpoint between neighbors, Vikunja 65536 spacing at the edges).

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/util/ReorderLogic.kt`
- Create: `app/src/test/java/com/rendyhd/vicu/util/ReorderLogicTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/rendyhd/vicu/util/ReorderLogicTest.kt`:

```kotlin
package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReorderLogicTest {

    private fun t(id: Long, dueDate: String = "", position: Double = 0.0): Task =
        Task(id = id, title = "t$id", dueDate = dueDate, position = position)

    // ─── moveTaskInList ───────────────────────────────────────

    @Test
    fun `moves undated task down the list`() {
        val list = listOf(t(1), t(2), t(3))
        val moved = moveTaskInList(list, fromId = 1, toId = 3)
        assertEquals(listOf(2L, 3L, 1L), moved!!.map { it.id })
    }

    @Test
    fun `moves undated task up the list`() {
        val list = listOf(t(1), t(2), t(3))
        val moved = moveTaskInList(list, fromId = 3, toId = 1)
        assertEquals(listOf(3L, 1L, 2L), moved!!.map { it.id })
    }

    @Test
    fun `vetoes moving a dated task`() {
        val list = listOf(t(1, dueDate = "2026-06-12T00:00:00Z"), t(2), t(3))
        assertNull(moveTaskInList(list, fromId = 1, toId = 3))
    }

    @Test
    fun `vetoes moving onto a dated task`() {
        val list = listOf(t(1, dueDate = "2026-06-12T00:00:00Z"), t(2), t(3))
        assertNull(moveTaskInList(list, fromId = 3, toId = 1))
    }

    @Test
    fun `vetoes ids missing from the list`() {
        val list = listOf(t(1), t(2))
        assertNull(moveTaskInList(list, fromId = 1, toId = 99))
        assertNull(moveTaskInList(list, fromId = 99, toId = 1))
    }

    @Test
    fun `null-date sentinel counts as undated`() {
        val list = listOf(t(1, dueDate = "0001-01-01T00:00:00Z"), t(2))
        val moved = moveTaskInList(list, fromId = 1, toId = 2)
        assertEquals(listOf(2L, 1L), moved!!.map { it.id })
    }

    // ─── dropPositionFor ──────────────────────────────────────

    @Test
    fun `drop between neighbors takes the midpoint`() {
        val list = listOf(t(1, position = 10.0), t(2), t(3, position = 30.0))
        assertEquals(20.0, dropPositionFor(list, 2)!!, 0.0)
    }

    @Test
    fun `drop at the top takes half of next`() {
        val list = listOf(t(2), t(1, position = 10.0))
        assertEquals(5.0, dropPositionFor(list, 2)!!, 0.0)
    }

    @Test
    fun `drop at the end adds one step past prev`() {
        val list = listOf(t(1, position = 10.0), t(2))
        assertEquals(10.0 + 65_536.0, dropPositionFor(list, 2)!!, 0.0)
    }

    @Test
    fun `dated previous neighbor is ignored`() {
        // A dated row's position is meaningless for the undated ordering: treat as top.
        val list = listOf(
            t(1, dueDate = "2026-06-12T00:00:00Z", position = 999.0),
            t(2),
            t(3, position = 40.0),
        )
        assertEquals(20.0, dropPositionFor(list, 2)!!, 0.0)
    }

    @Test
    fun `single item gets the default step`() {
        assertEquals(65_536.0, dropPositionFor(listOf(t(1)), 1)!!, 0.0)
    }

    @Test
    fun `missing id returns null`() {
        assertNull(dropPositionFor(listOf(t(1)), 99))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.ReorderLogicTest"
```

Expected: compile FAILURE — `moveTaskInList` / `dropPositionFor` unresolved.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/rendyhd/vicu/util/ReorderLogic.kt`:

```kotlin
package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task

/** Vikunja position spacing used when appending to the end of a list view. */
const val POSITION_STEP = 65_536.0

private fun isDated(t: Task): Boolean =
    t.dueDate.isNotBlank() && !DateUtils.isNullDate(t.dueDate)

/** Undated tasks form the manually orderable block; dated rows are pinned by due-date sort. */
fun isManuallyOrdered(t: Task): Boolean = !isDated(t)

/**
 * Move [fromId] to the slot currently occupied by [toId]. Returns null (move vetoed)
 * when either id is missing or either task is dated: sortProjectTasks pins dated tasks
 * first by due date, so a cross-block move would just snap back on the next emission.
 */
fun moveTaskInList(tasks: List<Task>, fromId: Long, toId: Long): List<Task>? {
    val fromIdx = tasks.indexOfFirst { it.id == fromId }
    val toIdx = tasks.indexOfFirst { it.id == toId }
    if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return null
    if (isDated(tasks[fromIdx]) || isDated(tasks[toIdx])) return null
    return tasks.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
}

/** Position halfway between neighbors; half of next at the top; one step past prev at the end. */
fun computeDropPosition(prev: Double?, next: Double?): Double = when {
    prev != null && next != null -> (prev + next) / 2.0
    next != null -> next / 2.0
    prev != null -> prev + POSITION_STEP
    else -> POSITION_STEP
}

/**
 * New position for [taskId] given its neighbors in [tasks] (the already-reordered,
 * as-displayed list). A dated previous row is ignored — its position is meaningless
 * for the undated ordering.
 */
fun dropPositionFor(tasks: List<Task>, taskId: Long): Double? {
    val idx = tasks.indexOfFirst { it.id == taskId }
    if (idx < 0) return null
    val prev = tasks.getOrNull(idx - 1)?.takeUnless { isDated(it) }?.position
    val next = tasks.getOrNull(idx + 1)?.position
    return computeDropPosition(prev, next)
}
```

- [ ] **Step 4: Run to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.ReorderLogicTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/util/ReorderLogic.kt app/src/test/java/com/rendyhd/vicu/util/ReorderLogicTest.kt
git commit -m "Add pure move and drop-position helpers for task reordering"
```

---

### Task 6: Position update through DAO and repository

Optimistic Room write so the list resorts instantly, then a best-effort POST to the Vikunja view-position endpoint (the API method and DTO already exist; pattern mirrors `anchorNewTaskAtEnd` at `TaskRepositoryImpl.kt:65-87`).

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/local/dao/TaskDao.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/domain/repository/TaskRepository.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt`

- [ ] **Step 1: Add the DAO query**

In `TaskDao.kt` (Read it first to match formatting), add alongside the other `@Query` methods:

```kotlin
    @Query("UPDATE tasks SET position = :position WHERE id = :taskId")
    suspend fun updatePosition(taskId: Long, position: Double)
```

- [ ] **Step 2: Add the interface method**

In `TaskRepository.kt`, after `suspend fun moveToProject(...)` (line 25):

```kotlin
    /** Manual reorder: optimistic local position write + best-effort remote view-position POST. */
    suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double)
```

- [ ] **Step 3: Implement in TaskRepositoryImpl**

Add to `TaskRepositoryImpl` (near `anchorNewTaskAtEnd`):

```kotlin
    override suspend fun updatePosition(taskId: Long, projectId: Long, newPosition: Double) {
        // Optimistic: Room first so the list resorts immediately.
        taskDao.updatePosition(taskId, newPosition)
        // Remote is best-effort, like anchorNewTaskAtEnd: positions are per-view and
        // re-sync on the next refresh, so a failure only loses the manual order.
        try {
            val views = api.getProjectViews(projectId)
            val listView = views.firstOrNull { it.viewKind == "list" } ?: return
            api.updateTaskPosition(
                taskId,
                TaskPositionDto(position = newPosition, projectViewId = listView.id),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "updatePosition failed (non-fatal) for task=$taskId", e)
        }
    }
```

All names used (`taskDao`, `api`, `TaskPositionDto`, `CancellationException`, `Log`, `TAG`) already exist in this file.

- [ ] **Step 4: Compile**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. (Room validates the query at compile time via KSP.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/data/local/dao/TaskDao.kt app/src/main/java/com/rendyhd/vicu/domain/repository/TaskRepository.kt app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt
git commit -m "Add optimistic task position update with best-effort remote sync"
```

---

### Task 7: ProjectViewModel reorder handlers

Two thin handlers over the Task-5 utils: `onTaskMoved` applies the in-memory reorder while the finger drags (returns whether the move was accepted, so the UI can decide haptics), `onTaskDropped` computes the final position and persists it.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/project/ProjectViewModel.kt`

- [ ] **Step 1: Add the handlers**

Add to `ProjectViewModel` (after `toggleSection`):

```kotlin
    /**
     * Live reorder while dragging: move [fromId] into the slot of [toId] within its group
     * (unsectioned list or one section). Cross-group and dated-task moves are vetoed by
     * moveTaskInList. Returns true when a move was applied.
     */
    fun onTaskMoved(fromId: Long, toId: Long): Boolean {
        var moved = false
        _uiState.update { state ->
            moveTaskInList(state.unsectionedTasks, fromId, toId)?.let { reordered ->
                moved = true
                return@update state.copy(unsectionedTasks = reordered)
            }
            val idx = state.sections.indexOfFirst { s -> s.tasks.any { it.id == fromId } }
            if (idx < 0) return@update state
            val reordered = moveTaskInList(state.sections[idx].tasks, fromId, toId)
                ?: return@update state
            moved = true
            val sections = state.sections.toMutableList()
            sections[idx] = sections[idx].copy(tasks = reordered)
            state.copy(sections = sections)
        }
        return moved
    }

    /** Drag released: persist the dropped task's new position from its current neighbors. */
    fun onTaskDropped(taskId: Long) {
        val state = _uiState.value
        val inUnsectioned = state.unsectionedTasks.any { it.id == taskId }
        val (tasks, groupProjectId) = if (inUnsectioned) {
            state.unsectionedTasks to projectId
        } else {
            val section = state.sections.firstOrNull { s -> s.tasks.any { it.id == taskId } }
                ?: return
            section.tasks to section.project.id
        }
        val newPosition = dropPositionFor(tasks, taskId) ?: return
        viewModelScope.launch {
            taskRepository.updatePosition(taskId, groupProjectId, newPosition)
        }
    }
```

Add the imports:

```kotlin
import com.rendyhd.vicu.util.dropPositionFor
import com.rendyhd.vicu.util.moveTaskInList
```

- [ ] **Step 2: Compile**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/project/ProjectViewModel.kt
git commit -m "ProjectViewModel: in-memory reorder and drop-position persistence"
```

---

### Task 8: ProjectScreen drag wiring

Wrap task rows in `ReorderableItem` with a long-press handle on the whole row. A long-press that lifts but never moves falls through to selection mode, preserving multi-select. Dated rows, completed-strikethrough rows, and selection mode keep the old long-press behavior. Remove `.animateItem()` from reorderable rows — it fights the library's drag offset.

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/project/ProjectScreen.kt`

- [ ] **Step 1: Create the reorder state**

In `ProjectScreen`, after `val listState = rememberLazyListState()` (line 56), add:

```kotlin
    val haptic = LocalHapticFeedback.current
    // True once the current long-press drag has actually displaced the row. A lift that
    // never moves falls through to selection mode (multi-select keeps its entry point).
    var dragMoved by remember { mutableStateOf(false) }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        if (fromId != null && toId != null && viewModel.onTaskMoved(fromId, toId)) {
            dragMoved = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
```

- [ ] **Step 2: Wrap the unsectioned task rows**

Replace the unsectioned `items` block (`ProjectScreen.kt:113-133`) with:

```kotlin
                    items(state.unsectionedTasks, key = { it.id }) { task ->
                        val displayTask = if (task.id in state.completedTaskIds) task.copy(done = true) else task
                        val canDrag = !selectionActive &&
                            task.id !in state.completedTaskIds &&
                            isManuallyOrdered(task)
                        ReorderableItem(reorderableState, key = task.id) { isDragging ->
                            val elevation by animateDpAsState(
                                if (isDragging) 4.dp else 0.dp,
                                label = "dragElevation",
                            )
                            Surface(
                                shadowElevation = elevation,
                                color = if (isDragging) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    Color.Transparent
                                },
                            ) {
                                SwipeableTaskItem(
                                    task = displayTask,
                                    onToggleDone = {
                                        if (task.id in state.completedTaskIds) {
                                            viewModel.undoComplete(task)
                                        } else {
                                            viewModel.toggleDone(task)
                                        }
                                    },
                                    onClick = {
                                        if (selectionActive) selectionVm.toggle(task.id) else onTaskClick(task.id)
                                    },
                                    onSchedule = { viewModel.scheduleTask(task) },
                                    selectionActive = selectionActive,
                                    selected = task.id in selectedIds,
                                    // Draggable rows enter selection via lift-without-move
                                    // (onDragStopped below); the rest keep plain long-press.
                                    onLongClick = if (canDrag) null else ({ selectionVm.toggle(task.id) }),
                                    modifier = if (canDrag) {
                                        Modifier.longPressDraggableHandle(
                                            onDragStarted = {
                                                dragMoved = false
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = {
                                                if (dragMoved) {
                                                    viewModel.onTaskDropped(task.id)
                                                } else {
                                                    selectionVm.toggle(task.id)
                                                }
                                            },
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                    }
```

- [ ] **Step 3: Wrap the section task rows**

Apply the same transformation to the section `items` block (`ProjectScreen.kt:176-197`): same `canDrag` / `ReorderableItem` / `Surface` / `longPressDraggableHandle` wrapping, with the two differences that block keeps — `contentStartPadding = 16.dp` stays on the `SwipeableTaskItem`, and the tasks come from `section.tasks`. Full replacement:

```kotlin
                            items(section.tasks, key = { it.id }) { task ->
                                val displayTask = if (task.id in state.completedTaskIds) task.copy(done = true) else task
                                val canDrag = !selectionActive &&
                                    task.id !in state.completedTaskIds &&
                                    isManuallyOrdered(task)
                                ReorderableItem(reorderableState, key = task.id) { isDragging ->
                                    val elevation by animateDpAsState(
                                        if (isDragging) 4.dp else 0.dp,
                                        label = "dragElevation",
                                    )
                                    Surface(
                                        shadowElevation = elevation,
                                        color = if (isDragging) {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        } else {
                                            Color.Transparent
                                        },
                                    ) {
                                        SwipeableTaskItem(
                                            task = displayTask,
                                            onToggleDone = {
                                                if (task.id in state.completedTaskIds) {
                                                    viewModel.undoComplete(task)
                                                } else {
                                                    viewModel.toggleDone(task)
                                                }
                                            },
                                            onClick = {
                                                if (selectionActive) selectionVm.toggle(task.id) else onTaskClick(task.id)
                                            },
                                            onSchedule = { viewModel.scheduleTask(task) },
                                            selectionActive = selectionActive,
                                            selected = task.id in selectedIds,
                                            onLongClick = if (canDrag) null else ({ selectionVm.toggle(task.id) }),
                                            contentStartPadding = 16.dp,
                                            modifier = if (canDrag) {
                                                Modifier.longPressDraggableHandle(
                                                    onDragStarted = {
                                                        dragMoved = false
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDragStopped = {
                                                        if (dragMoved) {
                                                            viewModel.onTaskDropped(task.id)
                                                        } else {
                                                            selectionVm.toggle(task.id)
                                                        }
                                                    },
                                                )
                                            } else {
                                                Modifier
                                            },
                                        )
                                    }
                                }
                            }
```

Note: `.animateItem()` is intentionally gone from both blocks (it conflicts with the library's drag placement). Section headers and `AddTaskButton` items are untouched — they are not `ReorderableItem`s, and cross-group drags are vetoed in the ViewModel anyway.

- [ ] **Step 4: Add the imports**

```kotlin
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.Surface
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.rendyhd.vicu.util.isManuallyOrdered
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
```

(`mutableStateOf`/`remember`/`getValue`/`setValue` are already imported in this file.)

- [ ] **Step 5: Compile and build**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual device check**

Install on a device, open a project with several undated tasks (and at least one dated task and one section):
1. Long-press an undated task: haptic + row lifts. Drag vertically: rows displace with haptics; release: order persists, survives leaving and reopening the screen, and (when online) survives pull-to-refresh.
2. Long-press and release WITHOUT moving: the task enters selection mode (checkbox appears, selection top bar shows).
3. Long-press a DATED task: no lift; selection mode toggles as before.
4. Drag an undated task toward the dated block at the top: it refuses to displace dated rows.
5. Drag toward another section: rows in the other section refuse to displace; release: the task stays in its own group.
6. Horizontal swipe (complete/schedule) and tap-to-open still work on draggable rows.
7. Reorder, then check the desktop app (or Vikunja web): the new order shows there too.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/project/ProjectScreen.kt
git commit -m "Project screen: long-press drag to reorder undated tasks"
```

---

### Task 9: Final verification

- [ ] **Step 1: Full unit test suite**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Full debug build**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Combined manual smoke pass (one install)**

1. New task sheet: type `call mom !` — the `!` shows a highlighted pill in the field AND a "Today" chip below; dismissing the chip removes the date and it stays dismissed through save.
2. Type `task @` — suggestions appear below the field.
3. Project chip — sub-projects nested under parents, indented.
4. Swipe behaviors per Task 4 step 4.
5. Reorder behaviors per Task 8 step 6.

- [ ] **Step 4: Report**

Summarize what was verified (with command output) and list the two intentionally-unfixed items for the release notes: sheet two-step open (by design), Inbox reorder (deferred, PAR-1).
