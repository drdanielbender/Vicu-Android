# Multi-depth project sections — Design

**Date:** 2026-06-25
**Status:** Approved (design)
**Scope:** Android (KMP `shared` module)

## Background

The desktop Vicu app recently gained arbitrarily-deep project sections (commits
`f42a561`, `0ed8335`, `bde0d20`, `a18983d` in `~/vscode/vicu`). Its `SectionData`
became a recursive tree (`children: SectionData[]`), sub-sections render
recursively with a constant 16px-per-level indent, and each section got an
"Add Section" button.

The Android app currently supports only a **single** level of sections: a
project's *direct* child projects are shown as collapsible sections, and any
deeper descendants are invisible. We want to reach parity on the **multi-depth
rendering**, but we are **not** porting the per-section "Add Section" button
(the Android app has never had one and the user does not want it).

## Goals

- Render a project's descendant projects as nested, collapsible sections to
  arbitrary depth.
- Indent each deeper level by a constant 16dp step (matches desktop's linear
  indent).
- Preserve all existing behavior: per-section "Add Task" row, drag-to-reorder
  within a section, optimistic completion (`completedTaskIds`) and undo.

## Non-goals

- No "Add Section" button.
- No change to the drawer's project tree (it remains one level deep; out of scope).
- No new repository methods or DAO queries — `ProjectRepository.getAll()`
  already exposes every project.
- No cross-section drag-and-drop (moving a task between sections); reorder stays
  within a single section, as today.

## Current state

`ProjectViewModel` combines `getById(projectId)`, `getChildren(projectId)`
(direct children only), and the parent's tasks, then `flatMapLatest` into a
`combine` of one task-flow per direct child, producing a flat
`List<ProjectSection>`. `ProjectScreen` renders that flat list in a single
`LazyColumn`: unsectioned parent tasks → optional parent "Add Task" →
for each section: `CollapsibleSection` header + its tasks + per-section
"Add Task" row.

Key existing helpers (unchanged):
- `moveTaskInList(tasks, fromId, toId)` — returns null (vetoes) when either id
  is missing from the *same* list or either task is dated. This is what keeps
  drags within one section.
- `dropPositionFor(tasks, taskId)` — new Vikunja position from neighbors.
- `sortProjectTasks`, `isManuallyOrdered`.

## Design

### 1. Recursive data model — `ProjectViewModel.kt`

`ProjectSection` gains a `children` field:

```kotlin
data class ProjectSection(
    val project: Project,
    val tasks: List<Task>,
    val children: List<ProjectSection> = emptyList(),
    val isExpanded: Boolean = true,
)
```

`ProjectUiState` keeps its current shape. `sections` now holds the **roots**
of the section tree (the project's direct children); each node carries its own
`children` recursively. `unsectionedTasks` (tasks directly in the project) is
unchanged.

### 2. Tree construction

Replace the one-level `getChildren(projectId)` flow with `getAll()`:

1. From the full `List<Project>`, compute every descendant of `projectId` by
   walking `parentProjectId` (a project is a descendant if its parent chain
   reaches `projectId`). Build a `parentId -> List<Project>` child map, sorted
   by `position`.
2. If there are no descendants, take the existing fast path: emit a state with
   `sections = emptyList()` and the sorted, undone parent tasks. (Avoids
   `combine(emptyList())`, which never emits.)
3. Otherwise, `combine` one `getByProjectId(descendant.id)` flow per descendant
   (each mapped to its sorted, undone task list), then assemble the tree with a
   recursive `buildTree(parentId): List<ProjectSection>` that reads tasks for
   each node from the combined results and recurses on the child map.

Expansion-state preservation across emissions becomes a **recursive** merge:
walk the freshly built tree and, for each node, copy `isExpanded` from the node
with the same `project.id` in the previous tree (default `true` for new nodes).

### 3. Recursive view-model operations

- `toggleSection(projectId: Long)` — replaces the index-based
  `toggleSection(sectionIndex: Int)`. Recursively rebuilds the tree, flipping
  `isExpanded` on the node whose `project.id == projectId`.
- `stateWithMove(state, fromId, toId)` — first tries the unsectioned list (as
  today), then recursively searches the section tree for the node whose `tasks`
  contains `fromId`; applies `moveTaskInList` to that node's tasks and rebuilds
  the immutable tree along the path. Returns null (no move) when not found or
  vetoed. The CAS loop in `onTaskMoved` is unchanged.
- `onTaskDropped(taskId)` — recursively locates the group (unsectioned list or a
  section node) containing `taskId`, computes `dropPositionFor`, and calls
  `taskRepository.updatePosition(taskId, groupProjectId, newPosition)`.

Helper functions (private, in the view model): `findSection(sections, predicate)`
and `mapSection(sections, projectId, transform)` style recursion to keep the
above concise and tested in isolation.

### 4. Rendering — `ProjectScreen.kt`

Introduce a recursive `LazyListScope` extension, e.g.
`fun LazyListScope.sectionItems(sections, depth, ...)`, that for each section:

- emits the `CollapsibleSection` header with `Modifier.padding(start = depth * 16.dp)`;
- if expanded, emits its `tasks` as `ReorderableTaskRow`s with
  `contentStartPadding = (depth + 1) * 16.dp`;
- if expanded, emits the per-section "Add Task" row at the matching indent;
- if expanded, recurses into `section.children` at `depth + 1`.

`onToggle` calls `viewModel.toggleSection(section.project.id)`. Section color
parsing is unchanged. `LazyColumn` item keys stay `"section_<id>"`,
`<taskId>`, and `"add_task_section_<id>"`; task rows keep the task `id` as the
reorderable key so drag works across the flattened list.

`allEmpty` becomes a recursive check: the parent is empty only when
`unsectionedTasks` is empty **and** no section anywhere in the tree has tasks.

### 5. Testing

- Pure-logic unit tests on `ProjectViewModel` collaborators: tree build from a
  flat project list (2–3 levels), recursive `toggleSection`, recursive
  `stateWithMove` (within-section reorder + cross-section veto). Where the tree
  helpers are free functions they are tested directly; otherwise via the view
  model with fake repositories.
- Build/compile verification: `./gradlew assembleDebug` and
  `./gradlew testDebugUnitTest` (the project has no ktlint gate).

## Files touched

- `shared/.../ui/screens/project/ProjectViewModel.kt`
- `shared/.../ui/screens/project/ProjectScreen.kt`
- New test file under `shared/src/.../test/.../project/` for the tree logic.

No new production files, no repository/DAO changes.

## Risks / notes

- `combine` over a per-descendant flow list rebuilds on any task change in any
  descendant; acceptable (same pattern as today, just more flows).
- Deep nesting on a narrow screen consumes horizontal space; 16dp/level is the
  agreed step and task text still wraps. No depth cap.
- Indentation is applied additively per level (constant step), not multiplied by
  an already-indented container, so it grows linearly — mirroring the desktop
  fix in `bde0d20`.
