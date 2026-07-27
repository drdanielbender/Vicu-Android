# Vikunja REST API v2 migration report

Authority: the instance OpenAPI 3.1 specification at
`https://vikunja.skippynet.xyz/api/v2/openapi.json`, reporting Vikunja API
`v2.4.0`.

## Architecture reconnaissance

- Kotlin Multiplatform shared module, with Android as the primary application target.
- Ktor client with OkHttp on Android and Darwin on iOS.
- `kotlinx.serialization` JSON models.
- Room is the offline source of truth.
- `PendingActionEntity`, `PendingActionDao`, `SyncEngine`, and Android WorkManager
  provide offline mutation replay.
- `AuthManager` injects Bearer credentials, serializes refresh attempts with a
  mutex, preserves rotating refresh cookies, and falls back to a stored API token.
- Koin provides dependency injection.
- All Vikunja HTTP calls are centralized in `VikunjaApiService`.

## Endpoint inventory

Paths in the first column include the old base prefix. Paths in the v2 column
are relative to the new `/api/v2/` base URL.

| Operation | v1 method and path | Source call sites | v2 method and path | Request model | Response model | Migration |
|---|---|---|---|---|---|---|
| List/search tasks | `GET /api/v1/tasks` | `TaskRepositoryImpl`, `SyncEngine` | `GET tasks` | Query: `page`, `per_page`, `q`, `filter`, `filter_timezone`, `filter_include_nulls`, `sort_by`, `order_by`, `expand`, `format` | `PaginatedResponse<TaskDto>` → `List<TaskDto>` | Envelope, `q`, and `total_pages` paging |
| Read task | `GET /api/v1/tasks/{id}` | `TaskRepositoryImpl`, `LabelRepositoryImpl` | `GET tasks/{id}` | Path id | `TaskDto` | Path unchanged |
| Create task | `PUT /api/v1/projects/{project}/tasks` | `TaskRepositoryImpl`, `SyncEngine` | `POST projects/{project}/tasks` | `CreateTaskDto` | `TaskDto`, HTTP 201 | Verb and status |
| Update task | `POST /api/v1/tasks/{id}` | `TaskRepositoryImpl`, `SyncEngine`, notification action | `PATCH tasks/{id}` | Writable-only `JsonObject` merge patch | `TaskDto` | Merge patch content type; changed fields only |
| Delete task | `DELETE /api/v1/tasks/{id}` | `TaskRepositoryImpl`, `SyncEngine` | `DELETE tasks/{id}` | Path id | Empty HTTP 204 | No JSON decode |
| List projects | `GET /api/v1/projects` | `ProjectRepositoryImpl`, setup | `GET projects` | `page`, `per_page`, optional v2 filters | `PaginatedResponse<ProjectDto>` → `List<ProjectDto>` | Envelope and all-page fetch |
| Read project | `GET /api/v1/projects/{id}` | Service/live validation | `GET projects/{id}` | Path id | `ProjectDto` | Path unchanged |
| Create project | `PUT /api/v1/projects` | `ProjectRepositoryImpl` | `POST projects` | `CreateProjectDto` | `ProjectDto`, HTTP 201 | Verb and status |
| Update project | `POST /api/v1/projects/{id}` | `ProjectRepositoryImpl` | `PATCH projects/{id}` | Writable-only `JsonObject` merge patch | `ProjectDto` | Partial update |
| Delete project | `DELETE /api/v1/projects/{id}` | `ProjectRepositoryImpl` | `DELETE projects/{id}` | Path id | Empty HTTP 204 | No JSON decode |
| List labels | `GET /api/v1/labels` | `LabelRepositoryImpl`, `SyncEngine` | `GET labels` | `page`, `per_page`, `q`, `format` | `PaginatedResponse<LabelDto>` → `List<LabelDto>` | Envelope and all-page fetch |
| Read label | `GET /api/v1/labels/{id}` | Service/live validation | `GET labels/{id}` | Path id | `LabelDto` | Path unchanged |
| Create label | `PUT /api/v1/labels` | `LabelRepositoryImpl`, `SyncEngine` | `POST labels` | `CreateLabelDto` | `LabelDto`, HTTP 201 | Verb, status, create-only DTO |
| Update label | `POST /api/v1/labels/{id}` | `LabelRepositoryImpl`, `SyncEngine` | `PATCH labels/{id}` | Writable-only `JsonObject` merge patch | `LabelDto` | Partial update |
| Delete label | `DELETE /api/v1/labels/{id}` | `LabelRepositoryImpl`, `SyncEngine` | `DELETE labels/{id}` | Path id | Empty HTTP 204 | No JSON decode |
| List task labels | Not declared in the old client | Data source support | `GET tasks/{task}/labels` | `page`, `per_page`, `q` | `PaginatedResponse<LabelDto>` → `List<LabelDto>` | Added v2 envelope support |
| Attach task label | `PUT /api/v1/tasks/{task}/labels` | `LabelRepositoryImpl`, `SyncEngine` | `POST tasks/{task}/labels` | `LabelTaskDto` | Ignored `LabelTask`, HTTP 201 | Verb and status |
| Detach task label | `DELETE /api/v1/tasks/{task}/labels/{label}` | `LabelRepositoryImpl`, `SyncEngine` | `DELETE tasks/{task}/labels/{label}` | Path ids | Empty HTTP 204 | No JSON decode |
| List attachments | `GET /api/v1/tasks/{task}/attachments` | `AttachmentRepositoryImpl` | `GET tasks/{task}/attachments` | `page`, `per_page`, `q` | `PaginatedResponse<AttachmentDto>` → `List<AttachmentDto>` | Envelope and all-page fetch |
| Upload attachment | `PUT /api/v1/tasks/{task}/attachments` | `AttachmentRepositoryImpl` | `POST tasks/{task}/attachments` | Multipart `files` parts | Ignored `AttachmentUploadResult`, HTTP 201 | Verb, multipart field, status |
| Download attachment | `GET /api/v1/tasks/{task}/attachments/{attachment}` | `AttachmentRepositoryImpl`, image UI | Same relative path under v2 | Path ids, optional `preview_size` | Binary `ByteArray` | Explicit binary response |
| Delete attachment | `DELETE /api/v1/tasks/{task}/attachments/{attachment}` | `AttachmentRepositoryImpl` | Same relative path under v2 | Path ids | Empty HTTP 204 | No JSON decode |
| Create relation | `PUT /api/v1/tasks/{task}/relations` | `TaskRepositoryImpl` | `POST tasks/{task}/relations` | `CreateRelationDto` | Ignored `TaskRelation`, HTTP 201 | Verb and status |
| Delete relation | `DELETE /api/v1/tasks/{task}/relations/{kind}/{other}` | `TaskRepositoryImpl` | Same relative path under v2 | Path values | Empty HTTP 204 | No JSON decode |
| List project views | `GET /api/v1/projects/{project}/views` | `TaskRepositoryImpl` | `GET projects/{project}/views` | `page`, `per_page`, `q` | `PaginatedResponse<ProjectViewDto>` → `List<ProjectViewDto>` | Envelope and all-page fetch |
| List view tasks | `GET /api/v1/projects/{project}/views/{view}/tasks` | `TaskRepositoryImpl` | Same relative path under v2 | Task list query parameters | `PaginatedResponse<TaskDto>` | Envelope and `total_pages` |
| Update task position | `POST /api/v1/tasks/{task}/position` | `TaskRepositoryImpl` | `PUT tasks/{task}/position` | `TaskPositionDto` | Ignored `TaskPosition`, HTTP 200 | Verb |
| Password login | `POST /api/v1/login` | `PasswordLoginHandler` | `POST login` | `LoginRequestDto` | `TokenResponseDto` plus refresh cookie | Problem code 1017 drives TOTP prompt |
| Server info/auth discovery | `GET /api/v1/info` | Setup | `GET info` | None | `ServerInfoDto` | Enforces Vikunja ≥ 2.4.0 |
| Current user | `GET /api/v1/user` | Setup, settings | `GET user` | Bearer token | `UserDto` | Path unchanged |
| OIDC provider list | `GET /api/v1/auth/openid/callback` | Old unused service declaration | Removed | None | Providers are in `ServerInfoDto.auth.openid_connect` | Removed nonexistent v2 call |
| OIDC callback | `POST /api/v1/auth/openid/{provider}/callback` | `OidcHandler` | `POST auth/openid/{provider}/callback` | `OidcCallbackDto` | `TokenResponseDto` plus refresh cookie | Problem details retained |
| API-token routes | `GET /api/v1/routes` | `AuthManager` | `GET routes` | None | Route permission map | Path unchanged |
| List API tokens | `GET /api/v1/tokens` | `AuthManager` | `GET tokens` | `page`, `per_page`, `q`, `owner_id` | `PaginatedResponse<ApiTokenDto>` → `List<ApiTokenDto>` | Envelope and all-page fetch |
| Create API token | `PUT /api/v1/tokens` | `AuthManager` | `POST tokens` | `ApiTokenRequestDto` | `ApiTokenResponseDto`, HTTP 201 | Verb and status |
| Delete API token | `DELETE /api/v1/tokens/{id}` | `AuthManager` | `DELETE tokens/{id}` | Path id | Empty HTTP 204 | No JSON decode |
| Legacy JWT renew | `POST /api/v1/user/token` | Old Ktor auth fallback | Removed for user JWTs | None | None | Removed; v2-only refresh |
| Refresh JWT | `POST /api/v1/user/token/refresh` | `AuthManager` | `POST user/token/refresh` | Rotating refresh cookie | `TokenResponseDto` plus new cookie | Auth injection skipped; no recursive refresh |
| Logout | `POST /api/v1/user/logout` | `AuthManager` | `POST logout` | Bearer token | Ignored logout body, HTTP 200 | v2 path exception |

## Implementation summary

- The production base path is `/api/v2/`; no production source contains a v1
  request path.
- Create calls use POST and require HTTP 201. Delete calls require HTTP 204 and
  never decode a body.
- `PaginatedResponse<T>` handles every used list route. Complete-collection
  methods fetch through `total_pages`, even when the server caps `per_page`.
- Task, project, and label updates use JSON Merge Patch. Task patch construction
  includes only writable fields and can encode explicit `false`, zero, empty
  string, and JSON `null`.
- Pending offline updates store and merge patch objects. Old queued full-object
  payloads are normalized to writable-only v2 patches during replay. Creates
  retain duplicate detection after ambiguous timeouts.
- RFC 9457 problem responses expose `title`, `status`, `detail`, `code`, and
  validation `errors`. Repository-facing messages prefer `detail`.
- TOTP handling uses Vikunja error code 1017 instead of treating every 412 as a
  TOTP response.
- Refresh requests carry the rotating cookie, skip Bearer injection, and cannot
  recursively invoke refresh. HTTP logging is limited to request/response
  metadata, and OkHttp redacts authorization and cookie headers.

## Files changed

### Build and Android configuration

- `gradle/libs.versions.toml`
- `shared/build.gradle.kts`
- `app/schemas/.gitkeep`
- `shared/src/androidMain/AndroidManifest.xml`
- `app/src/main/java/com/rendyhd/vicu/di/AppModule.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/di/KoinModules.kt`

### Networking, DTOs, and errors

- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/BaseUrlHolder.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/KtorClientFactory.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/api/VikunjaApiService.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/api/PaginatedResponse.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/api/VikunjaProblem.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/api/MergePatches.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/api/AuthDtos.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/api/LabelDto.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/remote/api/TaskDto.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/util/Constants.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/util/RetryableException.kt`

### Authentication

- `shared/src/commonMain/kotlin/com/rendyhd/vicu/auth/AuthManager.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/auth/OidcHandler.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/auth/PasswordLoginHandler.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/auth/RefreshCookieExtractor.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/setup/SetupViewModel.kt`

### Repositories, queueing, and Android mutation entry points

- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/mapper/LabelMapper.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/repository/ProjectRepositoryImpl.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/repository/LabelRepositoryImpl.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/local/dao/PendingActionDao.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/data/local/dao/QueueMerge.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/worker/SyncEngine.kt`
- `app/src/main/java/com/rendyhd/vicu/notification/NotificationActionReceiver.kt`
- `app/src/main/java/com/rendyhd/vicu/widget/ToggleTaskCallback.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/components/task/DescriptionField.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/components/task/ImageViewerDialog.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/search/SearchViewModel.kt`
- `shared/src/commonMain/kotlin/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailViewModel.kt`

### Tests

- `shared/src/commonTest/kotlin/com/rendyhd/vicu/data/remote/api/MergePatchesTest.kt`
- `shared/src/commonTest/kotlin/com/rendyhd/vicu/data/remote/api/VikunjaApiServiceV2Test.kt`
- `shared/src/commonTest/kotlin/com/rendyhd/vicu/ui/screens/setup/VikunjaVersionTest.kt`
- `app/src/test/java/com/rendyhd/vicu/data/local/dao/QueueMergeTest.kt`
- `app/src/androidTest/java/com/rendyhd/vicu/ExampleInstrumentedTest.kt`

## Verification

- Unit tests: all passing, including v2 methods/statuses, pagination, `q`
  search, API-token envelope unwrapping, RFC 9457/422 errors, writable-only
  patches, explicit false/zero/null, issue #24 patch shape, and old queue
  normalization.
- Android lint: passing.
- Debug APK: built successfully.
- Instrumented tests: passing on an Android 16 emulator.
- Security audit: no credential-like token or Bearer literal is present in
  tracked or untracked source files.

## Live validation and cleanup

Live tests used uniquely named temporary resources only. The following passed:

- Temporary project create (201), merge patch, fresh read, and delete (204).
- Project-view pagination and view-task pagination.
- Issue #24: task create with title, description, priority, and due date;
  description/priority-only merge patch; fresh read proving the two edits
  persisted while title and due date stayed unchanged; task delete (204).
- Label create (201), patch/read, attach (201), paginated list, detach (204),
  and delete (204).
- Task relation create (201) and delete (204).
- Task-position update with PUT.
- Attachment POST multipart upload (201), paginated list, binary download byte
  comparison, and delete (204).

All temporary tasks, labels, attachments, relations, and the temporary project
were deleted. No existing user data was modified or removed.

## Not validated end to end

- Password login, wrong-password handling, TOTP-required/invalid flows, and OIDC
  callback were not exercised because no password or interactive IdP session
  was provided.
- JWT refresh-cookie rotation and logout were not exercised live because the
  supplied credential was an API token; invalidating or replacing it was
  intentionally avoided.
- WorkManager-driven background replay, process death, and an actual network
  timeout after create were covered at the queue/protocol level but not forced
  end to end on a device.
- iOS native compilation was not available on the Windows build host.
