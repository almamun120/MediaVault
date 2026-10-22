# MediaVault

Android app (Kotlin, Compose, Material 3, Hilt, Room, CameraX) to capture photos, video and audio and save them to local or remote storage.

**Status: Phase 1 + early Phase 2/3. NOT yet compiled or run** — it was written in an environment without the Android SDK. Expect a few compile errors on first sync; fix them before building on top of it.

## Phase 1 — architecture and decisions
- Single `app` module with clean packages (`core/*`, `storage/*`, `feature/*`, `ui/*`, `di`). Storage protocols sit behind `storage/api/StorageProvider`, so capture code never depends on SMB/SFTP/etc. Split into Gradle modules once the providers exist.
- MVVM: Compose UI -> Hilt ViewModels -> Room / providers. Coroutines + Flow throughout.
- Photos/videos are written to a hidden `.tmp` file and renamed on success (atomic finalize). Failed or interrupted videos are kept and labelled `_INTERRUPTED`.
- Uploads (Phase 5) will use Room-backed jobs plus WorkManager unique work (name = file id + destination id) for idempotency.

### Platform limits (stated up front)
- **NFS:** Android has no non-root NFS client. Plan: a documented helper (e.g. a small relay service or an SMB/WebDAV gateway in front of the NFS export); the app will not claim native NFS.
- **Background recording:** camera/mic access in the background needs a foreground service with the right type and a notification; Android 14+ restricts starting these. Recording will not run indefinitely in the background.
- **Remote real-time saving:** video is recorded to a local temp file first, then uploaded. Direct streaming to SMB/SFTP is not safe against connection drops.
- **Not in the camera:** panorama (needs custom stitching), frame-rate selection and photo-during-video (CameraX controller doesn't expose them) are not implemented. 1:1 ratio is not offered by CameraX.
- HDR / Night / Portrait / Retouch only appear on devices whose manufacturer provides the CameraX extension.

### Versions (mutually compatible, but not the newest)
AGP 8.7.3, Kotlin 2.0.21 + KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, CameraX 1.4.1, Hilt 2.52, Room 2.6.1, WorkManager 2.10.0, Navigation 2.8.5. Bump together if Android Studio suggests.

## Checklist
| Item | State |
|---|---|
| Gradle, manifest, Hilt, Room, theme, 6-tab navigation | written, uncompiled |
| StorageProvider interface + LocalStorageProvider | written, uncompiled |
| Filename templates (+ unit tests) | written, tests not run |
| Photo camera (switch, flash, tap-focus, pinch/slider zoom, EV, timer, burst, grid, ratio, mirror, extensions) | written, untested on a device |
| Video (start/pause/resume/stop, mic toggle, quality, duration/size, torch) | written, untested on a device |
| Home screen listing captured files (Room) | written, uncompiled |
| Audio recorder (foreground service, pause/resume, quality from settings) | written, uncompiled, untested on a device |
| Media library (filters, search, sort, grid/list, thumbnails, photo/video/audio preview, confirmed delete) | written, uncompiled |
| Settings (filename templates with live preview, audio quality, default video quality; DataStore) | written, uncompiled |
| Rules engine (media type -> primary + backup destinations, folder/file templates, Wi-Fi/charging, conflict policy, delete-local-after-verified-upload) | written, router unit-tested logic, uncompiled |
| Upload queue: Room jobs + one unique WorkManager work each, exponential backoff, retry/pause/resume/cancel, duplicate prevention, reboot/restart reconcile, progress + notifications | written, uncompiled, never run on a device |
| Transfers screen (queued / completed / failed, progress, retry) and Home dashboard (queue, failures, destinations, free space) | written, uncompiled |
| Library: upload status per file, manual "Upload..." to a chosen destination, document (PDF) preview | written, uncompiled |
| Document scanner: camera + gallery import, auto page detection, draggable corners, perspective crop, rotate, 10 filters + brightness/contrast, multi-page reorder, A4/auto-size PDF, optional JPEG pages | written, algorithms checked with a Python port, never run on a device |
| GitHub Actions: debug + signed release APK/AAB on every push (see SIGNING.md) | written, never run |
| StorageProvider implementations: local, device/SD folder (SAF), SMB (smbj), FTP/FTPS (commons-net), SFTP (JSch fork, strict host-key pinning), WebDAV (OkHttp), Google Drive (REST, resumable) | written, uncompiled; **never run against a real server** |
| NFS | not supported on standard Android; provider returns a clear error, use SMB/WebDAV gateway |
| Credential store (AES-GCM key in Android Keystore) | written, uncompiled |
| Storage screen: add/edit/duplicate/remove, test connection, browse, SFTP host-key trust, FTP/HTTP warnings | written, uncompiled |
| Unit tests: local provider, WebDAV (MockWebServer), path helpers, templates | written, not run |
| Rules engine, upload queue, notifications | not started |

## Build
1. Open the folder in Android Studio (Ladybug or newer). It generates the Gradle wrapper jar and `local.properties`.
2. `./gradlew :app:assembleDebug`, then `./gradlew :app:testDebugUnitTest`.
3. Install: `./gradlew :app:installDebug` with a device or emulator attached (use a real device to test the camera properly).

Troubleshooting: Hilt/Room errors usually mean KSP/Kotlin versions are out of sync; use the pairing above. A missing launcher icon only affects appearance.


## Remote storage notes
- Run `./gradlew :app:testDebugUnitTest`; the WebDAV tests use MockWebServer and need no real server.
- **Needs external setup / real servers to verify:** SMB, FTP, FTPS, SFTP and WebDAV need a server you control; Google Drive needs an OAuth *Android* client (Cloud Console: package `com.mediavault` + your debug/release SHA-1, Drive API enabled). No secrets are stored in the app.
- Manual test checklist per protocol: Test connection -> Browse -> (after Phase 5) upload a photo -> verify size -> rename/delete -> wrong password shows an AUTH error -> unplug network shows a retryable NETWORK error.
- SFTP: Verify host key shows the SHA256 fingerprint before anything is sent; a changed key blocks the connection.
- SMB/SAF/Drive write the final file name directly (no atomic rename) and delete a partial file on failure. FTP, FTPS, SFTP and WebDAV use a `.part` file plus rename.
- Google Drive with the `drive.file` scope only sees files the app created; "folder choice" is a path below an app-created root folder. Resumable-upload sessions are not persisted across app restarts yet; a retry restarts the upload.
- Library API names for smbj, JSch (mwiede 0.2.20), commons-net and Play Services Authorization were written from knowledge without a compiler; expect small signature fixes at first build.
- Database moved to v2 with a pre-release destructive migration from v1 (media index only; files remain on disk).


## Upload queue behaviour (read before relying on it)
- A capture is always saved locally first. Rules (Settings -> Upload rules) then create one job per destination; nothing is uploaded without a rule or a manual "Upload...".
- Android decides when WorkManager runs: Wi-Fi-only/charging constraints and battery optimisation can delay uploads. Nothing is guaranteed to start immediately.
- Retries: temporary errors (network, server busy, SD card out) go back to the queue with exponential backoff, up to 8 attempts; auth, permission, storage-full and unsupported errors fail at once and show in Transfers (and as a notification if allowed).
- Duplicates: a unique index on (file, destination, folder) ignores a second identical job; WorkManager work is unique per job; before uploading, the worker lists the folder and treats "same name and same size" as already uploaded.
- Integrity: every provider verifies the uploaded size. Checksums are not used (FTP/WebDAV/SMB offer no common remote hash).
- "Delete local copy after upload" runs only when every job for that file is DONE; the file is then marked "Remote only" in the library.
- Not implemented: age-based local retention (e.g. delete after N days), resuming a partly uploaded file (except the Drive protocol within one attempt), per-capture destination override on the camera screen (use the library's manual upload).
- Interrupted recordings are never auto-uploaded.

## Scanner notes
- Page detection is a heuristic (Otsu threshold + largest blob); it works for a page on a contrasting surface. Otherwise drag the corners. No OCR / searchable PDF.
- Filters are our own implementations of the usual scanner looks (Magic Color, Lighten, Grayscale, B&W, B&W Soft, No Shadow, Eco, Sharpen, Whiteboard, Original), not any other app's algorithms.
- PDFs are written with Android's PdfDocument, which stores page images with little compression; B&W scans stay small, colour scans can be several MB per page.

## CI
- `.github/workflows/android.yml` builds on every push/PR: debug APK, release APK + AAB (signed if the secrets from SIGNING.md exist, otherwise clearly named UNSIGNED), and runs unit tests in a separate non-blocking job. Download the files from the run's Artifacts. Tag `v*` to publish a GitHub Release.
- The debug build uses the application id `com.mediavault.debug`; register that package (plus its SHA-1) as a second OAuth Android client if you test Google Drive with debug builds.
- R8 shrinking is off until verified; try `./gradlew assembleRelease -Pminify=true` and send me the log if it fails.
- Database is at v3 with a pre-release destructive migration: updating from an earlier build wipes saved destinations and rules (stored credentials become orphaned), media files stay on disk.
