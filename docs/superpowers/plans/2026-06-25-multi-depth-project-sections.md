# Multi-depth Project Sections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render a project's descendant projects as nested, collapsible sections to arbitrary depth on the Android (KMP `shared`) client, with a constant 16dp-per-level indent.

**Architecture:** Extract the section-tree logic into pure, testable functions in a new `ProjectSections.kt`. `ProjectViewModel` builds a nested `ProjectSection` tree from `ProjectRepository.getAll()` (instead of one-level `getChildren`) and routes toggle/move/drop through the pure helpers. `ProjectScreen` renders the tree depth-first via a recursive `LazyListScope` extension. No "Add Section" button is added; the per-section "Add Task" row stays at every depth.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `kotlinx.coroutines.flow`, `sh.calvin.reorderable`, JUnit (commonTest).

## Global Constraints

- Package root: `com.rendyhd.vicu`.
- `JAVA_HOME` must be set for Gradle: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
- No ktlint gate exists — verify with `compileDebugKotlin` / `assembleDebug` / `:shared:testDebugUnitTest`. Do NOT run `ktlintCheck`/`ktlintFormat`.
- Indentation step is a constant **16dp per nesting level**, applied additively (not multiplied by an already-indented container).
- Do **not** add an "Add Section" button. Keep the existing per-section "Add Task" row.
- Do **not** change `ProjectRepository`/DAO interfaces — `getAll()` already exposes every project.
- Reorder-drag stays within a single section's task list (existing `moveTaskInList` veto). No cross-section moves.
- Match the existing commonTest style: `org.junit.Test` + `org.junit.Assert.assertEquals` (see `shared/src/commonTest/.../util/TaskSortTest.kt`).

---

## File Structure

- **Create** `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectSections.kt` — the `ProjectSection` data class (moved out of the view model, gaining a `children` field) plus pure tree helpers. Single responsibility: section-tree data + transforms, no Compose, no coroutines.
- **Create** `shared/src/commonTest/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectSectionsTest.kt` — unit tests for the helpers.
- **Modify** `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectViewModel.kt` — remove the local `ProjectSection`, build the nested tree, recursive toggle/move/drop.
- **Modify** `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectScreen.kt` — recursive renderer + recursive empty check + toggle-by-id.

---

## Task 1: Pure section-tree helpers (`ProjectSections.kt`)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectSections.kt`
- Modify: `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectViewModel.kt:28-32` (remove the local `data class ProjectSection`)
- Test: `shared/src/commonTest/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectSectionsTest.kt`

**Interfaces:**
- Consumes: `com.rendyhd.vicu.domain.model.Project`, `com.rendyhd.vicu.domain.model.Task`, `com.rendyhd.vicu.util.moveTaskInList(tasks, fromId, toId): List<Task>?`.
- Produces (used by Tasks 2 & 3):
  - `data class ProjectSection(project: Project, tasks: List<Task>, children: List<ProjectSection> = emptyList(), isExpanded: Boolean = true)`
  - `fun collectDescendants(rootId: Long, projects: List<Project>): List<Project>`
  - `fun buildSectionTree(rootId: Long, projects: List<Project>, tasksByProject: Map<Long, List<Task>>): List<ProjectSection>`
  - `fun preserveExpansion(new: List<ProjectSection>, old: List<ProjectSection>): List<ProjectSection>`
  - `fun toggleSectionExpanded(sections: List<ProjectSection>, projectId: Long): List<ProjectSection>`
  - `fun moveTaskInSections(sections: List<ProjectSection>, fromId: Long, toId: Long): List<ProjectSection>?`
  - `fun findTaskGroup(sections: List<ProjectSection>, taskId: Long): ProjectSection?`
  - `fun hasAnyTask(sections: List<ProjectSection>): Boolean`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectSectionsTest.kt`:

```kotlin
package com.rendyhd.vicu.ui.screens.project

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectSectionsTest {

    private fun p(id: Long, parent: Long = 0L, position: Double = 0.0): Project =
        Project(id = id, title = "P$id", parentProjectId = parent, position = position)

    private fun t(id: Long, position: Double = 0.0): Task =
        Task(id = id, title = "t$id", position = position)

    // collectDescendants ---------------------------------------------------

    @Test
    fun `collectDescendants returns all levels under root in position order`() {
        val projects = listOf(
            p(1),
            p(10, parent = 1, position = 20.0),
            p(11, parent = 1, position = 10.0),
            p(100, parent = 10),
            p(2), // unrelated root
        )
        val ids = collectDescendants(1L, projects).map { it.id }
        // 11 (pos 10) before 10 (pos 20); 100 follows its parent 10
        assertEquals(listOf(11L, 10L, 100L), ids)
    }

    @Test
    fun `collectDescendants terminates on cyclic parents`() {
        val projects = listOf(p(1), p(2, parent = 3), p(3, parent = 2))
        // 2<->3 cycle is unreachable from root 1
        assertEquals(emptyList<Long>(), collectDescendants(1L, projects).map { it.id })
    }

    // buildSectionTree -----------------------------------------------------

    @Test
    fun `buildSectionTree nests children and attaches tasks`() {
        val projects = listOf(p(1), p(10, parent = 1), p(100, parent = 10))
        val tasks = mapOf(10L to listOf(t(5)), 100L to listOf(t(7), t(8)))
        val tree = buildSectionTree(1L, projects, tasks)

        assertEquals(listOf(10L), tree.map { it.project.id })
        val s10 = tree[0]
        assertEquals(listOf(5L), s10.tasks.map { it.id })
        assertEquals(listOf(100L), s10.children.map { it.project.id })
        assertEquals(listOf(7L, 8L), s10.children[0].tasks.map { it.id })
    }

    @Test
    fun `buildSectionTree orders siblings by position`() {
        val projects = listOf(p(1), p(10, parent = 1, position = 30.0), p(11, parent = 1, position = 5.0))
        val tree = buildSectionTree(1L, projects, emptyMap())
        assertEquals(listOf(11L, 10L), tree.map { it.project.id })
    }

    // toggleSectionExpanded ------------------------------------------------

    @Test
    fun `toggleSectionExpanded flips only the matching nested node`() {
        val tree = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), emptyList()))),
        )
        val toggled = toggleSectionExpanded(tree, 100L)
        assertTrue(toggled[0].isExpanded)               // unchanged
        assertFalse(toggled[0].children[0].isExpanded)  // flipped
    }

    // preserveExpansion ----------------------------------------------------

    @Test
    fun `preserveExpansion carries old collapsed state onto new tree by id`() {
        val old = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), emptyList(), isExpanded = false))),
        )
        val new = listOf(
            ProjectSection(p(10), listOf(t(1)), listOf(ProjectSection(p(100), listOf(t(2))))),
        )
        val merged = preserveExpansion(new, old)
        assertFalse(merged[0].children[0].isExpanded) // collapsed state preserved
        assertEquals(listOf(2L), merged[0].children[0].tasks.map { it.id }) // new tasks kept
    }

    // moveTaskInSections ---------------------------------------------------

    @Test
    fun `moveTaskInSections reorders within the section holding the task`() {
        val tree = listOf(
            ProjectSection(p(10), emptyList(), listOf(
                ProjectSection(p(100), listOf(t(1, 10.0), t(2, 20.0), t(3, 30.0))),
            )),
        )
        val moved = moveTaskInSections(tree, fromId = 3, toId = 1)
        assertEquals(listOf(3L, 1L, 2L), moved!![0].children[0].tasks.map { it.id })
    }

    @Test
    fun `moveTaskInSections returns null for a cross-section move`() {
        val tree = listOf(
            ProjectSection(p(10), listOf(t(1, 10.0))),
            ProjectSection(p(20), listOf(t(2, 10.0))),
        )
        assertNull(moveTaskInSections(tree, fromId = 1, toId = 2))
    }

    @Test
    fun `moveTaskInSections returns null when the task is absent`() {
        val tree = listOf(ProjectSection(p(10), listOf(t(1))))
        assertNull(moveTaskInSections(tree, fromId = 99, toId = 1))
    }

    // findTaskGroup --------------------------------------------------------

    @Test
    fun `findTaskGroup locates the nested section owning the task`() {
        val tree = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), listOf(t(7))))),
        )
        assertEquals(100L, findTaskGroup(tree, 7L)?.project?.id)
        assertNull(findTaskGroup(tree, 8L))
    }

    // hasAnyTask -----------------------------------------------------------

    @Test
    fun `hasAnyTask is true when only a deep child has tasks`() {
        val tree = listOf(
            ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), listOf(t(1))))),
        )
        assertTrue(hasAnyTask(tree))
    }

    @Test
    fun `hasAnyTask is false when the whole tree is empty`() {
        val tree = listOf(ProjectSection(p(10), emptyList(), listOf(ProjectSection(p(100), emptyList()))))
        assertFalse(hasAnyTask(tree))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :shared:testDebugUnitTest --tests "com.rendyhd.vicu.ui.screens.project.ProjectSectionsTest"
```
Expected: FAIL — compilation error, `ProjectSections.kt` does not exist yet (unresolved references `collectDescendants`, `buildSectionTree`, etc.).

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectSections.kt`:

```kotlin
package com.rendyhd.vicu.ui.screens.project

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.moveTaskInList

/**
 * One project section: a descendant project rendered as a collapsible group, its undone
 * tasks, and its own nested sub-sections. [isExpanded] is UI state preserved across emissions.
 */
data class ProjectSection(
    val project: Project,
    val tasks: List<Task>,
    val children: List<ProjectSection> = emptyList(),
    val isExpanded: Boolean = true,
)

/**
 * Every descendant project of [rootId] at any depth, depth-first, siblings ordered by
 * position. A `visited` guard terminates on pre-existing cyclic parent data (A->B->A).
 */
fun collectDescendants(rootId: Long, projects: List<Project>): List<Project> {
    val childMap = projects.groupBy { it.parentProjectId }
    val result = mutableListOf<Project>()
    val visited = mutableSetOf<Long>()
    fun recurse(parentId: Long) {
        childMap[parentId]?.sortedBy { it.position }?.forEach { child ->
            if (!visited.add(child.id)) return@forEach
            result.add(child)
            recurse(child.id)
        }
    }
    recurse(rootId)
    return result
}

/**
 * Nested section tree for the direct children of [rootId] (recursively). Children are ordered
 * by position; each node's tasks come from [tasksByProject] (already filtered to undone and
 * sorted by the caller), defaulting to empty.
 */
fun buildSectionTree(
    rootId: Long,
    projects: List<Project>,
    tasksByProject: Map<Long, List<Task>>,
): List<ProjectSection> {
    val childMap = projects.groupBy { it.parentProjectId }
    val visited = mutableSetOf<Long>()
    fun build(parentId: Long): List<ProjectSection> =
        childMap[parentId].orEmpty()
            .sortedBy { it.position }
            .mapNotNull { project ->
                if (!visited.add(project.id)) return@mapNotNull null
                ProjectSection(
                    project = project,
                    tasks = tasksByProject[project.id].orEmpty(),
                    children = build(project.id),
                )
            }
    return build(rootId)
}

/** Recursively carry [old]'s isExpanded onto [new], matched by project id (default true for new nodes). */
fun preserveExpansion(new: List<ProjectSection>, old: List<ProjectSection>): List<ProjectSection> {
    val expandedById = HashMap<Long, Boolean>()
    fun index(list: List<ProjectSection>) {
        list.forEach { expandedById[it.project.id] = it.isExpanded; index(it.children) }
    }
    index(old)
    fun apply(list: List<ProjectSection>): List<ProjectSection> = list.map { s ->
        s.copy(
            isExpanded = expandedById[s.project.id] ?: true,
            children = apply(s.children),
        )
    }
    return apply(new)
}

/** Recursively flip isExpanded on the node whose project id is [projectId]. */
fun toggleSectionExpanded(sections: List<ProjectSection>, projectId: Long): List<ProjectSection> =
    sections.map { s ->
        if (s.project.id == projectId) {
            s.copy(isExpanded = !s.isExpanded)
        } else {
            s.copy(children = toggleSectionExpanded(s.children, projectId))
        }
    }

/**
 * Reorder within whichever section's task list holds both [fromId] and [toId]: returns the
 * rebuilt tree, or null when no section can absorb the move (task absent, cross-section, or a
 * dated row — all vetoed by [moveTaskInList]).
 */
fun moveTaskInSections(
    sections: List<ProjectSection>,
    fromId: Long,
    toId: Long,
): List<ProjectSection>? {
    var moved = false
    fun recurse(list: List<ProjectSection>): List<ProjectSection> = list.map { s ->
        if (moved) return@map s
        val reordered = moveTaskInList(s.tasks, fromId, toId)
        if (reordered != null) {
            moved = true
            s.copy(tasks = reordered)
        } else {
            s.copy(children = recurse(s.children))
        }
    }
    val result = recurse(sections)
    return if (moved) result else null
}

/** The section whose own task list contains [taskId], searched recursively, or null. */
fun findTaskGroup(sections: List<ProjectSection>, taskId: Long): ProjectSection? {
    for (s in sections) {
        if (s.tasks.any { it.id == taskId }) return s
        findTaskGroup(s.children, taskId)?.let { return it }
    }
    return null
}

/** True when any section anywhere in the tree has at least one task. */
fun hasAnyTask(sections: List<ProjectSection>): Boolean =
    sections.any { it.tasks.isNotEmpty() || hasAnyTask(it.children) }
```

Then remove the now-duplicate declaration from `ProjectViewModel.kt`. Delete these lines (currently `ProjectViewModel.kt:28-32`):

```kotlin
data class ProjectSection(
    val project: Project,
    val tasks: List<Task>,
    val isExpanded: Boolean = true,
)
```

(`ProjectSection` is now in the same package, so the rest of `ProjectViewModel.kt` keeps compiling against the new definition; the `children` field defaults to empty.)

- [ ] **Step 4: Run the test to verify it passes**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :shared:testDebugUnitTest --tests "com.rendyhd.vicu.ui.screens.project.ProjectSectionsTest"
```
Expected: PASS — all 13 tests green, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectSections.kt \
        shared/src/commonTest/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectSectionsTest.kt \
        shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectViewModel.kt
git commit -m "feat: pure recursive section-tree helpers for nested project sections

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Wire `ProjectViewModel` to the nested tree

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectViewModel.kt`

**Interfaces:**
- Consumes (from Task 1): `collectDescendants`, `buildSectionTree`, `preserveExpansion`, `toggleSectionExpanded`, `moveTaskInSections`, `findTaskGroup`. Existing utils `sortProjectTasks`, `moveTaskInList`, `dropPositionFor`.
- Produces (used by Task 3): `fun toggleSection(projectId: Long)` (signature change from `Int` index). `ProjectUiState.sections` is now a nested tree.

- [ ] **Step 1: Replace the section-building flow**

In the `init { viewModelScope.launch { ... } }` block, replace the whole `combine(...).flatMapLatest { ... }.collect { ... }` body (currently `ProjectViewModel.kt:61-110`) with:

```kotlin
combine(
    projectRepository.getById(projectId),
    projectRepository.getAll(),
    taskRepository.getByProjectId(projectId),
) { project, allProjects, parentTasks ->
    Triple(project, allProjects, parentTasks)
}.flatMapLatest { (project, allProjects, parentTasks) ->
    val descendants = collectDescendants(projectId, allProjects)
    val unsectioned = sortProjectTasks(parentTasks.filter { !it.done })
    if (descendants.isEmpty()) {
        flowOf(
            ProjectUiState(
                project = project,
                sections = emptyList(),
                unsectionedTasks = unsectioned,
                isLoading = false,
            )
        )
    } else {
        // One task flow per descendant; combine rebuilds the tree whenever any changes.
        val taskFlows = descendants.map { descendant ->
            taskRepository.getByProjectId(descendant.id).map { tasks ->
                descendant.id to sortProjectTasks(tasks.filter { !it.done })
            }
        }
        combine(taskFlows) { pairs ->
            ProjectUiState(
                project = project,
                sections = buildSectionTree(projectId, allProjects, pairs.toMap()),
                unsectionedTasks = unsectioned,
                isLoading = false,
            )
        }
    }
}.collect { newState ->
    _uiState.update { current ->
        newState.copy(
            sections = preserveExpansion(newState.sections, current.sections),
            completedTaskIds = current.completedTaskIds,
        )
    }
}
```

Notes: `projectRepository.getChildren` is no longer used here (the import / call goes away). `combine(taskFlows)` yields an `Array<Pair<Long, List<Task>>>`; `pairs.toMap()` builds the per-project task map. Archived descendants are included exactly as the previous one-level code included archived direct children — no new filtering.

- [ ] **Step 2: Make `stateWithMove` recurse the tree**

Replace `stateWithMove` (currently `ProjectViewModel.kt:131-141`) with:

```kotlin
/** Returns [state] with the move applied, or null when the move is vetoed. */
private fun stateWithMove(state: ProjectUiState, fromId: Long, toId: Long): ProjectUiState? {
    moveTaskInList(state.unsectionedTasks, fromId, toId)?.let { reordered ->
        return state.copy(unsectionedTasks = reordered)
    }
    val movedSections = moveTaskInSections(state.sections, fromId, toId) ?: return null
    return state.copy(sections = movedSections)
}
```

- [ ] **Step 3: Make `onTaskDropped` find the group recursively**

Replace the `else` branch of `onTaskDropped` (currently `ProjectViewModel.kt:149-153`) so the section lookup is recursive:

```kotlin
fun onTaskDropped(taskId: Long) {
    val state = _uiState.value
    val inUnsectioned = state.unsectionedTasks.any { it.id == taskId }
    val (tasks, groupProjectId) = if (inUnsectioned) {
        state.unsectionedTasks to projectId
    } else {
        val section = findTaskGroup(state.sections, taskId) ?: return
        section.tasks to section.project.id
    }
    val newPosition = dropPositionFor(tasks, taskId) ?: return
    viewModelScope.launch {
        taskRepository.updatePosition(taskId, groupProjectId, newPosition)
    }
}
```

- [ ] **Step 4: Change `toggleSection` to key by project id**

Replace `toggleSection` (currently `ProjectViewModel.kt:160-170`) with:

```kotlin
fun toggleSection(projectId: Long) {
    _uiState.update { state ->
        state.copy(sections = toggleSectionExpanded(state.sections, projectId))
    }
}
```

- [ ] **Step 5: Add imports / remove dead ones**

Ensure these are imported at the top of `ProjectViewModel.kt`:

```kotlin
import com.rendyhd.vicu.ui.screens.project.collectDescendants   // same package — only if IDE flags it; usually unneeded
```

(They are in the same package as the view model, so no import is required. Remove any now-unused import such as an explicit `getChildren` reference — there is none to import, but confirm `Project`/`Task` imports remain since other code uses them.)

- [ ] **Step 6: Verify it compiles**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :shared:compileDebugKotlinAndroid :shared:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL. The Task 1 tests still pass. (The flow wiring is coroutine/Flow code verified by compilation + the pure-logic tests; `ProjectScreen` still references the old `toggleSection(Int)` and will fail to compile until Task 3 — so run only `:shared:compileDebugKotlinAndroid` for the file in isolation is not possible; expect the screen compile error here and resolve it in Task 3. If you prefer a green gate, do Steps in Task 3 before compiling the whole module.)

> Sequencing note: `toggleSection`'s signature change couples Tasks 2 and 3. Make the Task 3 edits before running the full-module compile. The single commit below covers Task 2's logic; the module is verified green at the end of Task 3.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectViewModel.kt
git commit -m "feat: build nested project-section tree in ProjectViewModel

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Recursive renderer in `ProjectScreen`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectScreen.kt`

**Interfaces:**
- Consumes (from Task 2): `ProjectUiState.sections` (nested), `viewModel.toggleSection(projectId: Long)`. From Task 1: `hasAnyTask`. Existing `ReorderableTaskRow` private composable, `CollapsibleSection`, `AddTaskButton`.
- Produces: none (terminal UI).

- [ ] **Step 1: Add a recursive `LazyListScope` extension**

Add this private extension at the bottom of `ProjectScreen.kt` (top-level, file scope), beside `ReorderableTaskRow`. It renders one section tree depth-first; indentation is `depth * 16.dp`.

```kotlin
private fun LazyListScope.projectSectionItems(
    sections: List<ProjectSection>,
    depth: Int,
    reorderableState: ReorderableLazyListState,
    completedTaskIds: Set<Long>,
    selectedIds: Set<Long>,
    selectionActive: Boolean,
    onSectionToggle: (Long) -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: (Task) -> Unit,
    onToggleDone: (Task) -> Unit,
    onRowClick: (Task) -> Unit,
    onSchedule: (Task) -> Unit,
    onLongClickToggle: (Task) -> Unit,
    onAddTask: (Long) -> Unit,
) {
    sections.forEach { section ->
        val sectionColor = parseSectionColor(section.project.hexColor)
        item(key = "section_${section.project.id}") {
            CollapsibleSection(
                title = section.project.title,
                color = sectionColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                taskCount = section.tasks.size,
                isExpanded = section.isExpanded,
                onToggle = { onSectionToggle(section.project.id) },
                modifier = Modifier.padding(start = (depth * 16).dp),
            )
        }

        if (section.isExpanded) {
            items(section.tasks, key = { it.id }) { task ->
                val displayTask = if (task.id in completedTaskIds) task.copy(done = true) else task
                val canDrag = !selectionActive &&
                    task.id !in completedTaskIds &&
                    isManuallyOrdered(task)
                ReorderableTaskRow(
                    reorderableState = reorderableState,
                    task = task,
                    displayTask = displayTask,
                    canDrag = canDrag,
                    selectionActive = selectionActive,
                    selected = task.id in selectedIds,
                    onDragStarted = onDragStarted,
                    onDragStopped = { onDragStopped(task) },
                    onToggleDone = { onToggleDone(task) },
                    onClick = { onRowClick(task) },
                    onSchedule = { onSchedule(task) },
                    onLongClick = if (canDrag) null else ({ onLongClickToggle(task) }),
                    contentStartPadding = ((depth + 1) * 16).dp,
                )
            }

            item(key = "add_task_section_${section.project.id}") {
                AddTaskButton(
                    onClick = { onAddTask(section.project.id) },
                    modifier = Modifier.padding(start = ((depth + 1) * 16).dp),
                )
            }

            projectSectionItems(
                sections = section.children,
                depth = depth + 1,
                reorderableState = reorderableState,
                completedTaskIds = completedTaskIds,
                selectedIds = selectedIds,
                selectionActive = selectionActive,
                onSectionToggle = onSectionToggle,
                onDragStarted = onDragStarted,
                onDragStopped = onDragStopped,
                onToggleDone = onToggleDone,
                onRowClick = onRowClick,
                onSchedule = onSchedule,
                onLongClickToggle = onLongClickToggle,
                onAddTask = onAddTask,
            )
        }
    }
}
```

- [ ] **Step 2: Add the color-parse helper**

The current code parses the section color inline inside a `try/catch` in the composable. Compose forbids try/catch around composable calls and the recursion needs it as a plain function, so extract it. Add this top-level private function in `ProjectScreen.kt`:

```kotlin
private fun parseSectionColor(hex: String): Color? =
    try {
        if (hex.isNotBlank()) {
            Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
```

- [ ] **Step 3: Replace the inline section loop with the extension call**

In the `LazyColumn { ... }` body, replace the entire `state.sections.forEachIndexed { index, section -> ... }` block (currently `ProjectScreen.kt:189-262`) with:

```kotlin
projectSectionItems(
    sections = state.sections,
    depth = 0,
    reorderableState = reorderableState,
    completedTaskIds = state.completedTaskIds,
    selectedIds = selectedIds,
    selectionActive = selectionActive,
    onSectionToggle = { pid -> viewModel.toggleSection(pid) },
    onDragStarted = { dragMoved = false },
    onDragStopped = { task ->
        if (dragMoved) viewModel.onTaskDropped(task.id) else selectionVm.toggle(task.id)
    },
    onToggleDone = { task ->
        if (task.id in state.completedTaskIds) viewModel.undoComplete(task) else viewModel.toggleDone(task)
    },
    onRowClick = { task ->
        if (selectionActive) selectionVm.toggle(task.id) else onTaskClick(task.id)
    },
    onSchedule = { task -> viewModel.scheduleTask(task) },
    onLongClickToggle = { task -> selectionVm.toggle(task.id) },
    onAddTask = { pid -> onShowTaskEntry(pid, null) },
)
```

- [ ] **Step 4: Make the empty check recursive**

Replace the `allEmpty` line (currently `ProjectScreen.kt:125-126`):

```kotlin
val allEmpty = state.unsectionedTasks.isEmpty() &&
    state.sections.all { it.tasks.isEmpty() }
```

with:

```kotlin
val allEmpty = state.unsectionedTasks.isEmpty() && !hasAnyTask(state.sections)
```

- [ ] **Step 5: Fix imports**

Add any imports the IDE flags. Likely needed: none beyond what's present (`Modifier`, `padding`, `dp`, `Color`, `MaterialTheme`, `CollapsibleSection`, `AddTaskButton`, `isManuallyOrdered` are already imported). `ProjectSection`, `hasAnyTask` are same-package — no import. Confirm `ReorderableLazyListState` import remains (it is already imported for `ReorderableTaskRow`).

- [ ] **Step 6: Verify the whole module compiles and tests pass**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :shared:testDebugUnitTest assembleDebug
```
Expected: BUILD SUCCESSFUL. Task 1 tests pass; app assembles. No `toggleSection(Int)` references remain.

- [ ] **Step 7: Manual smoke test (device/emulator)**

Build + install, open a project that has child projects with their own grandchild projects:
```bash
./gradlew installDebug
```
Verify: grandchild sections appear nested under their parent section, indented one extra step (16dp) per level; collapsing a parent section hides its sub-sections; dragging an undated task within a (nested) section reorders it; "Add Task" appears under every section; no "Add Section" button anywhere.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/project/ProjectScreen.kt
git commit -m "feat: render nested project sub-sections recursively with depth indent

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage:** recursive data model (Task 1 `ProjectSection`/`buildSectionTree`); tree construction from `getAll()` (Task 2 Step 1); recursive toggle/move/drop (Task 1 helpers + Task 2 Steps 2-4); recursive renderer + 16dp indent + recursive empty check (Task 3); no Add Section button, per-section Add Task kept at depth (Task 3 Step 1); testing (Task 1 unit tests + build verification). All spec sections map to a task.
- **Type consistency:** `toggleSection(projectId: Long)` defined in Task 2 Step 4 and consumed in Task 3 Step 3. `ProjectSection(project, tasks, children, isExpanded)` constructor used consistently in tests and renderer. Helper names (`collectDescendants`, `buildSectionTree`, `preserveExpansion`, `toggleSectionExpanded`, `moveTaskInSections`, `findTaskGroup`, `hasAnyTask`) identical across Tasks 1-3.
- **Sequencing:** the `toggleSection` signature change couples Task 2 and Task 3; the full-module green gate lands at Task 3 Step 6, as flagged in Task 2 Step 6.
