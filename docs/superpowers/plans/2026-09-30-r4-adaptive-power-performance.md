# R4 Adaptive Power & Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Giảm overhead CPU/wakeup của Manager, WebUI và Worker nền mà không thay đổi datapath mạng hoặc làm giảm throughput/latency.

**Architecture:** Track A giữ nguyên `netproxyctl -> sing-box -> eBPF cgroup` và chỉ tối ưu telemetry/maintenance. Android dùng một polling loop single-flight theo cadence EVENT → CONFIRM → WARM → IDLE, WebUI mở rộng poller hiện có theo idle state, và Native chỉ giữ Worker khi Wi-Fi policy hoặc subscription scheduling thực sự cần; Track B kernel fast-path hoàn toàn ngoài phạm vi plan này.

**Tech Stack:** Kotlin/Compose + libsu, TypeScript/Vite + Node test runner, Go, Shell contract tests, JDK 25.

**Spec:** `docs/superpowers/specs/2026-09-30-adaptive-power-performance-design.md`

## Global Constraints

- Không thay đổi packet forwarding, protocol processing, sing-box crypto hoặc baseline eBPF cgroup trong Track A.
- Không thêm daemon quản lý thứ hai, BPF loader mới, XDP, governor/cpufreq tuning hay model-specific OnePlus logic.
- `netproxyctl` tiếp tục là public management boundary; tránh thay đổi `schema=1`.
- Android generic phải giữ fallback an toàn; telemetry/animation/Worker failure không được làm sing-box ready chuyển failed.
- Dashboard cadence cố định: EVENT = refresh ngay; CONFIRM = đúng 1 lần sau 2s; sau đó WARM = 5s/lần khi idle `<30s`; IDLE = 15s/lần khi idle `>=30s`; hidden/background = OFF; Dashboard mở rồi để yên 5 phút phải tạo tối đa 25 lần `service status`.
- Local IPv4 cache TTL chính xác 30s; lỗi trả `--` nhưng không cache lỗi quá chu kỳ sau.
- About animation: ACTIVE tối đa 60 FPS trong 10s sau vào trang/tương tác; IDLE tối đa 30 FPS; hidden/background = OFF.
- WebUI: active 5s; idle `>=30s` 20s; hidden = OFF; giữ single-flight + revision semantics.
- `needWorker = WIFI_AUTO_SWITCH || hasScheduledSubscriptionWork`; trạng thái đọc lỗi phải fail-open để không làm mất scheduling hiện hữu.
- Track B capability cache / Enhanced profile không được triển khai trong plan này.
- Không cập nhật README/release claim về pin/nhiệt/W trước benchmark thiết bị thật đạt gate.
- Mỗi git mutation/commit trong giai đoạn execution phải có fresh current-turn authorization theo `AGENTS.md`.

## Review Focus

- Visibility flapping hoặc manual refresh khi status request đang chạy: chỉ một request được chạy, wake mới được coalesce và response cũ không tạo polling loop thứ hai. Pin bằng test ở Task 1.
- Wall-clock thay đổi khi người dùng chỉnh giờ/NTP: cadence idle phải dùng monotonic elapsed time, không dùng `System.currentTimeMillis()`. Pin bằng test ở Task 1.
- Local IPv4 lookup lỗi hoặc interface tạm mất: UI hiển thị `--`, lần poll sau được phép thử lại; cached value hợp lệ chỉ sống 30s. Pin bằng test ở Task 1.
- WebUI bị ẩn trong lúc request pending rồi hiện lại: response thuộc revision cũ không publish, visible lại refresh ngay và không chồng timer. Pin bằng test ở Task 3.
- Worker state mơ hồ do module/Catalog đọc lỗi: giữ/start Worker thay vì stop nhầm; child Worker không attach network watcher khi `WIFI_AUTO_SWITCH=0`. Pin bằng test ở Task 4.

---

### Task 1: Android Dashboard adaptive telemetry

**Files:**
- Create: `src/android/app/src/main/java/com/fanjv/netproxy/feature/dashboard/presentation/DashboardPollingPolicy.kt`
- Create: `src/android/app/src/main/java/com/fanjv/netproxy/feature/dashboard/presentation/LocalIpv4Cache.kt`
- Modify: `src/android/app/src/main/java/com/fanjv/netproxy/feature/dashboard/presentation/CatalogDashboardViewModel.kt`
- Test: `src/android/app/src/test/java/com/fanjv/netproxy/feature/dashboard/presentation/DashboardPollingPolicyTest.kt`
- Test: `src/android/app/src/test/java/com/fanjv/netproxy/feature/dashboard/presentation/LocalIpv4CacheTest.kt`

**Interfaces:**
- Produces: `internal object DashboardPollingPolicy { fun nextDelayMillis(idleMillis: Long, confirmPending: Boolean): Long }`; `confirmPending=true` returns `2_000`, otherwise `<25_000ms` returns `5_000` and `>=25_000ms` returns `15_000` so the next poll follows the approved `27s -> 42s` transition. EVENT refresh itself is immediate and outside this delay function.
- Produces: `internal class LocalIpv4Cache(private val ttlMillis: Long = 30_000L, private val loader: () -> String?)` with `fun read(nowElapsedMillis: Long, force: Boolean = false): String`.
- Consumes: existing `ServiceRepository.status()` and `DashboardSnapshotReducer`; no schema/API changes.

- [ ] **Step 1: Write failing cadence and cache tests**

`DashboardPollingPolicyTest` asserts `confirmPending=true -> 2_000`; với `confirmPending=false`, `0/29_999 -> 5_000`, `30_000+ -> 15_000`, negative idle clamps về WARM; đồng thời mô phỏng lịch EVENT tại `0s` rồi CONFIRM/WARM/IDLE để chứng minh 5 phút idle tạo tối đa 25 lần status (`0, 2, 7, 12, 17, 22, 27, 42, ...`). `LocalIpv4CacheTest` asserts first read loads, reads before 30s reuse value, 30s boundary reloads, `force=true` reloads immediately, and loader failure returns `--` without poisoning the next read.

- [ ] **Step 2: Run targeted tests and verify RED**

Run: `cd src/android && ./gradlew :app:testDebugUnitTest --tests 'com.fanjv.netproxy.feature.dashboard.presentation.DashboardPollingPolicyTest' --tests 'com.fanjv.netproxy.feature.dashboard.presentation.LocalIpv4CacheTest'`

Expected: FAIL because the new policy/cache types do not exist.

- [ ] **Step 3: Implement the two pure helpers**

`DashboardPollingPolicy.nextDelayMillis()` must be side-effect free. `LocalIpv4Cache.read()` caches only successful nonblank IPv4 strings; use elapsed-time values supplied by the caller so tests and wall-clock changes are deterministic.

- [ ] **Step 4: Replace fixed Dashboard polling with one conflated wake loop**

In `CatalogDashboardViewModel`, remove `uptimeJob/startUptimeTicker()`. Keep exactly one `refreshJob`; record interaction using `SystemClock.elapsedRealtime()`. Visibility entry, explicit refresh and completed service operations trigger EVENT: wake the single loop, refresh immediately, mark one CONFIRM pending, then schedule exactly one follow-up after 2s. After CONFIRM, schedule 5s while idle `<30s`, then 15s at `>=30s`. Use a conflated wake signal so repeated events coalesce, and never launch `refreshSnapshot()` concurrently from a second coroutine.

- [ ] **Step 5: Integrate 30s local-IP cache**

Move interface enumeration into `LocalIpv4Cache.loader`. Force refresh when Dashboard becomes visible and on explicit `refresh()`; regular polling calls `read(nowElapsedMillis)` and only enumerates after TTL expiry. Preserve `DashboardSnapshotReducer` uptime calculation from `readyAt` at each snapshot.

- [ ] **Step 6: Run Android Dashboard tests and full unit suite**

Run targeted command from Step 2, then `cd src/android && ./gradlew :app:testDebugUnitTest`.

Expected: PASS; no 1s uptime ticker remains and no second status-launch path remains in `CatalogDashboardViewModel`.

- [ ] **Step 7: Review Task 1 diff and commit with fresh authorization**

Run `git diff --check` and inspect only Dashboard-related files. After fresh user authorization, commit with `feat(android): giảm polling nền của bảng điều khiển` and a body noting cadence, IP cache, removed uptime ticker, and Android test result.

### Task 2: About page adaptive animation

**Files:**
- Create: `src/android/app/src/main/java/com/fanjv/netproxy/feature/about/presentation/effect/AboutAnimationPolicy.kt`
- Modify: `src/android/app/src/main/java/com/fanjv/netproxy/feature/about/presentation/effect/AboutBackground.kt`
- Modify: `src/android/app/src/main/java/com/fanjv/netproxy/feature/about/presentation/AboutMiuix.kt`
- Test: `src/android/app/src/test/java/com/fanjv/netproxy/feature/about/presentation/effect/AboutAnimationPolicyTest.kt`

**Interfaces:**
- Produces: `internal object AboutAnimationPolicy { fun targetFps(active: Boolean, idleMillis: Long): Int }` returning `0`, `60`, or `30`.
- `AboutBackground` gains `interactionRevision: Long = 0L`; a changed revision resets the 10s ACTIVE window without resetting visual animation time.
- `AboutScreenMiuix` supplies lifecycle activity and increments the revision when `LazyListState.isScrollInProgress` transitions to true; leaving the Activity resumed state sets `active=false`.

- [ ] **Step 1: Write failing frame-policy tests**

Assert inactive always `0`; active at `0` and `9_999ms` is `60`; active at `10_000ms+` is `30`; negative idle clamps to active/60.

- [ ] **Step 2: Run targeted test and verify RED**

Run: `cd src/android && ./gradlew :app:testDebugUnitTest --tests 'com.fanjv.netproxy.feature.about.presentation.effect.AboutAnimationPolicyTest'`

Expected: FAIL because `AboutAnimationPolicy` does not exist.

- [ ] **Step 3: Implement policy and lifecycle/interaction inputs**

Use `LifecycleResumeEffect` in `AboutScreenMiuix` to derive resumed visibility. Observe scroll-start events (false -> true) to increment `interactionRevision`; navigation away/dispose must result in `active=false` or node detach.

- [ ] **Step 4: Make `AboutBackgroundNode` schedule at policy FPS**

Track the last interaction time with a monotonic clock. `active=false` cancels `animationJob`; active idle <10s targets 60 FPS, >=10s targets 30 FPS. Preserve `animationTime` across ACTIVE↔IDLE and pause/resume; do not reset colors or shader state. Do not spin on every display frame merely to skip invalidation at 30 FPS.

- [ ] **Step 5: Verify static fallback is unchanged**

When `isRuntimeShaderSupported()` is false, `AboutBackground` must still return the static `Box` path without starting any animation policy/timer work.

- [ ] **Step 6: Run targeted + Android unit tests**

Run the targeted command from Step 2, then `cd src/android && ./gradlew :app:testDebugUnitTest`.

Expected: PASS; grep/review confirms hidden/background path cancels the frame loop and idle policy cannot exceed 30 FPS.

- [ ] **Step 7: Review Task 2 diff and commit with fresh authorization**

Run `git diff --check`. After fresh user authorization, commit with `feat(android): hạ nhịp hiệu ứng khi trang giới thiệu nhàn rỗi` and include lifecycle/FPS behavior plus tests in the body.

### Task 3: WebUI idle-aware polling

**Files:**
- Modify: `src/webui/src/polling.ts`
- Modify: `src/webui/src/main.ts`
- Modify: `src/webui/tests/runtime.test.mjs`

**Interfaces:**
- Extend `createPoller()` with options `{ activeInterval?: number; idleInterval?: number; idleAfter?: number; now?: () => number }`, defaults `5_000/20_000/30_000/Date.now`.
- Produces: poller method `markInteraction(): void` that records `now()`, bumps revision, clears the pending timer and causes one immediate refresh when active without overlapping an in-flight request.
- Existing `refresh()` remains an explicit immediate refresh primitive; `setActive(false)` clears timers and invalidates pending publish exactly as today.

- [ ] **Step 1: Extend the existing Node test with adaptive timing cases**

In `runtime.test.mjs`, add assertions that a fresh active poll schedules at 5s, after synthetic idle >=30s schedules at 20s, `markInteraction()` returns cadence to 5s and refreshes immediately, and hidden state creates zero future requests.

- [ ] **Step 2: Add the hidden-in-flight regression**

Start a pending request, call `setActive(false)`, advance time, re-enable and resolve the old request. Assert old data is not published, exactly one new request is created, and no timer/request overlap occurs.

- [ ] **Step 3: Run WebUI check and verify RED**

Run: `cd src/webui && npm run check`.

Expected: adaptive tests FAIL because `markInteraction` and adaptive options do not exist yet.

- [ ] **Step 4: Implement adaptive scheduling inside `createPoller`**

Preserve current `active`, `inFlight`, `requested`, `revision` and timeout-based single-flight structure. Choose next interval from `now() - lastInteraction`; coalesce repeated refresh/interaction while in-flight into one requested refresh.

- [ ] **Step 5: Wire user activity in `main.ts`**

Call `statusPoller.markInteraction()` after service commands and on meaningful terminal interaction (`keydown`/button click). `visibilitychange` continues to use `setActive(!document.hidden)`; becoming visible must cause immediate refresh.

- [ ] **Step 6: Verify WebUI**

Run: `cd src/webui && npm run check && npm run build`.

Expected: TypeScript + Node tests PASS and Vite output is rebuilt through the normal build path.

- [ ] **Step 7: Review Task 3 diff and commit with fresh authorization**

Run `git diff --check`. After fresh user authorization, commit with `feat(webui): giảm polling trạng thái khi giao diện nhàn rỗi` and record single-flight/visibility tests in the body.

### Task 4: Native Worker demand and watcher reconciliation

**Files:**
- Modify: `src/native/netproxy/internal/module/app.go`
- Modify: `src/native/netproxy/internal/module/config.go`
- Modify: `src/native/netproxy/internal/module/lifecycle.go`
- Modify: `src/native/netproxy/cmd/netproxyctl/internal_worker.go`
- Modify: `src/native/netproxy/internal/worker/process.go`
- Modify: `src/native/netproxy/internal/worker/process_unix.go`
- Modify: `src/native/netproxy/internal/worker/process_windows.go`
- Test: `src/native/netproxy/internal/module/service_test.go`
- Test: `src/native/netproxy/internal/module/config_apply_test.go`
- Test: `src/native/netproxy/internal/module/app_test.go`
- Test: `src/native/netproxy/cmd/netproxyctl/main_test.go`
- Test: `src/native/netproxy/internal/worker/worker_test.go`

**Interfaces:**
- Add internal `workerDemand(ctx context.Context, options Options) (needed bool, networkWatch bool, err error)` in module code; scheduled subscription demand is `catalog.Schedule(...).Nearest > 0`.
- Ambiguous module/Catalog reads return `needed=true` with an error so callers fail-open and never stop a possibly required Worker.
- Add `worker.Wake(options Options) error`; on Unix it sends `SIGUSR1` to a verified Worker PID, on non-Unix it may safely no-op when no running Worker exists.
- Change module reconciliation to distinguish Wi-Fi config changes from Catalog schedule changes: config change may restart an already-running Worker to change watcher attachment; Catalog schedule change wakes an already-running Worker, starts stopped Worker when needed, or stops it when demand disappears.

- [ ] **Step 1: Write failing Worker demand/state-transition tests**

Cover: Wi-Fi off + no auto subscription => no Worker; Wi-Fi on => Worker; Wi-Fi off + auto subscription => Worker; last auto subscription disabled/removed => stop; malformed config or Catalog read error => do not stop existing Worker.
- [ ] **Step 2: Add child-process watcher-gating regression**

In `main_test.go`, create module configs with `WIFI_AUTO_SWITCH=0` and `=1`, run `configureWorkerCallbacks`, and assert `NetworkWatchEnabled` matches config. Add malformed/missing config case asserting fail-open `true`; this pins the current bug where child `worker run` unconditionally sets watcher=true.

- [ ] **Step 3: Run targeted Go tests and verify RED**

Run: `cd src/native/netproxy && go test ./internal/module ./internal/worker ./cmd/netproxyctl`.

Expected: new demand/reconcile and child watcher tests FAIL against current code.

- [ ] **Step 4: Implement `workerDemand` and state-based reconciliation**

Use current module config plus `catalog.Schedule`. For `needed=false`, stop only a running Worker. For `needed=true` and stopped, call `worker.Start`. For Catalog scheduling changes while running, call `worker.Wake`; for Wi-Fi watcher configuration changes while running, restart once so child callbacks receive new watcher state. Reconcile errors are logged by callers and never change sing-box service state.

- [ ] **Step 5: Fix child `worker run` watcher configuration**

Replace unconditional `options.NetworkWatchEnabled = true` in `configureWorkerCallbacks` with `WIFI_AUTO_SWITCH` loaded from `module.conf`; retain fail-open `true` only when the config cannot be parsed/read. Do not read SSID or create the network watcher when the value is false.

- [ ] **Step 6: Reconcile after scheduling-relevant Catalog mutations**

`AddSubscription`: reconcile after group metadata is committed even if first download later fails. `EditSubscription`: reconcile when `AutoUpdate` or `UpdateInterval` is supplied and persisted. `RemoveSubscription`: reconcile after deletion. A manual `UpdateSubscription` that changes `next_update_at` must wake a running Worker so its timer is recalculated, but must not spawn one when no scheduling demand exists.

- [ ] **Step 7: Preserve existing config-apply behavior**

`ApplyConfig` must still reconcile only when `WIFI_AUTO_SWITCH` changes; unrelated module values must not restart/wake Worker. Update `config_apply_test.go` to assert this explicitly.

- [ ] **Step 8: Run Go verification for affected packages**

Run: `cd src/native/netproxy && go test ./internal/module ./internal/worker ./internal/catalog ./internal/subscription ./cmd/netproxyctl && go vet ./internal/module ./internal/worker ./cmd/netproxyctl`.

Expected: PASS with no Worker process needed for Wi-Fi off + no scheduled subscriptions and no watcher attached in the child process when Wi-Fi policy is disabled.

- [ ] **Step 9: Review Task 4 diff and commit with fresh authorization**

Run `git diff --check`, inspect Worker process/watcher and subscription mutation paths, then run targeted tests once more. After fresh user authorization, commit with `feat(native): chỉ giữ Worker khi còn tác vụ nền` and a body describing fail-open demand, Wi-Fi watcher gating, Catalog wake/reconcile behavior, and Go verification.

### Task 5: Cross-component regression and full VPS gates

**Files:**
- No product files should be added solely for this task.
- Generated WebUI/module/Android outputs are verification artifacts only and must not be committed.

**Interfaces:**
- Consumes the completed Task 1–4 commits.
- Produces a verified source SHA suitable for a device-test build; does not publish a release.

- [ ] **Step 1: Run whitespace and source-state checks**

Run: `git diff --check`, `git status --short`, and review `git diff <R3-source-or-plan-base>...HEAD` by component. Expected: only approved Track A code/tests plus plan/spec history; no credentials, APK, ZIP, local.properties, runtime logs, or generated module binaries tracked.

- [ ] **Step 2: Run full Go + repository contracts**

Run: `cd src/native/netproxy && go test ./... && go vet ./...`.

Run targeted race: `cd src/native/netproxy && go test -race ./internal/module ./internal/worker ./internal/catalog`.

Run: `sh tests/ci_verify.sh` from repo root with a fresh `NETPROXY_CI_BUILD_DIR`.

Expected: all PASS.

- [ ] **Step 3: Run full WebUI and docs gates**

Run: `cd src/webui && npm ci --no-audit --no-fund && npm run build`.

Run: `cd docs && npm ci --no-audit --no-fund && npm run build`.

Expected: TypeScript/tests/Vite and VitePress PASS; dependency warnings are reported, not hidden.

- [ ] **Step 4: Run full Android gates on JDK 25**

With `ANDROID_HOME=/srv/android-sdk` and JDK 25 active, run: `cd src/android && ./gradlew testDebugUnitTest lintDebug assembleDebug --rerun-tasks --no-daemon`.

Expected: BUILD SUCCESSFUL. Record actionable task count and any warnings; do not treat environment-only SDK/JAVA failures as source regressions without rerunning with the required environment.

- [ ] **Step 5: Verify Track A invariants from code/tests**

Confirm: background Dashboard polling OFF; EVENT refresh ngay + đúng một CONFIRM sau 2s + WARM 5s + IDLE 15s, với tối đa 25 status call trong 5 phút idle; no parallel status path; About hidden loop OFF and idle <=30 FPS; WebUI hidden polling OFF; Worker demand/watcher invariants; no `EBPF_PERFORMANCE_PROFILE`, second daemon or BPF loader added.

- [ ] **Step 6: Save verification evidence**

Record source SHA, commit count, toolchain versions (Go, Node 24, JDK 25, Android SDK), exact gate results and known non-blocking warnings in the session/checkpoint. Do not claim battery/thermal improvement yet.

### Task 6: Build an unpublished device-test artifact

**Files:**
- Generated only: temporary `src/module/bin/netproxyctl`, `src/module/webroot/netproxy/`, `src/module/NetProxy.apk`, ZIPs and hash manifest.
- Do not commit generated artifacts or temporary signing material.

**Interfaces:**
- Consumes the verified SHA from Task 5.
- Produces a standard ZIP, a `_with-manager.zip`, signed temporary CI Manager APK, and SHA256 manifest for device testing.

- [ ] **Step 1: Build Android `netproxyctl` and WebUI through the same paths as CI**

Use a fresh `NETPROXY_CI_BUILD_DIR` with `sh tests/ci_verify.sh`; install its Android `netproxyctl` into `src/module/bin/netproxyctl` only in the disposable build tree. Run `cd src/webui && npm ci --no-audit --no-fund && npm run build`.

- [ ] **Step 2: Derive build metadata without committing it**

Set `COMMIT_COUNT=$(git rev-list --count HEAD)`, read `version=` from `src/module/module.prop`, and write that count into the disposable module tree's `versionCode`. Manager build ID is the first 7 characters of the exact source SHA.

- [ ] **Step 3: Build and temporarily sign the CI Manager**

Run `:app:assembleRelease` with `-PnetproxyManagerCi=true`, `-PnetproxyManagerBuildId=<short-sha>` and `-PnetproxyManagerVersion=<version-without-v>` on JDK 25. Create a one-run PKCS12 key in a temporary directory, sign with Android build-tools 37 `apksigner` (`v4` disabled), verify signature, package name `com.fanjv.netproxy`, versionCode, versionName and locale resources, then copy only the signed APK into the disposable module tree.

- [ ] **Step 4: Package standard and with-manager ZIPs**

Run `.github/scripts/package-module.sh <disposable-module-dir> <artifact-dir> <standard-name> <manager-name>` using names `NetProxy_<version>_<commit-count>.zip` and `NetProxy_<version>_<commit-count>_with-manager.zip`.

- [ ] **Step 5: Verify the device-test artifacts**

Run `7z t` on both ZIPs, generate `SHA256SUMS.txt`, verify the standard ZIP excludes `NetProxy.apk`, the manager ZIP contains the exact signed APK, `module.prop` has expected version/versionCode/update feed, and packaged `netproxyctl` is Android aarch64 and matches the build output hash.

- [ ] **Step 6: Stop before publication**

Place test artifacts in a dated local VPS artifact directory and report filenames/SHA256 to the user. Do not create a tag, GitHub Release, update `latest`, or advertise R4 until Task 7 device benchmark passes and the user separately approves publication.

### Task 7: Real-device benchmark and acceptance gate

**Files:**
- No source mutation required for measurement.
- Store raw benchmark logs outside the repository or in ignored temporary paths; never commit device identifiers, subscription credentials or private logs.

**Interfaces:**
- Compares R3 baseline against the Task 6 R4 test build on the same device/kernel/node/network conditions.
- Produces a benchmark summary that decides what performance claims, if any, are allowed in R4 release notes.

- [ ] **Step 1: Capture immutable test context**

Record ROM/kernel build, NetProxy source/build, selected node, Wi-Fi identity in redacted form, brightness, charging state, battery level/temperature and background-app setup. Use the same setup for R3 and R4.

- [ ] **Step 2: Run the five workloads for R3 and R4**

For each build run at least 3 rounds with cooldown: (1) proxy ready + Manager background 30m; (2) Dashboard open idle 15m; (3) normal browsing/app use 15m; (4) sustained download 10m; (5) proxy OFF reference. Do not mix R3 and R4 conditions within a round.

- [ ] **Step 3: Collect the same metrics each round**

Collect sing-box and Manager CPU time, RSS, context-switch/wakeup counters when exposed, battery `current_now` and `voltage_now`, battery/thermal temperature, throughput, latency p50/p95 and Android frame/jank while Manager is visible. Normalize sysfs units before computing `|V × I|`; use medians, never a single instantaneous sample.

- [ ] **Step 4: Evaluate software invariants on-device**

Verify Manager background produces zero periodic status calls; Dashboard idle 5m produces <=25 `service status` calls; About idle stays <=30 FPS and stops in background; Worker PID is absent with Wi-Fi auto-switch OFF and no scheduled subscriptions; enabling a scheduled subscription starts/wakes Worker and disabling the last demand stops it.

- [ ] **Step 5: Apply acceptance thresholds**

PASS requires: Manager CPU/wakeup clearly lower for Dashboard-idle workload; median power not higher than R3; sustained thermal not worse beyond sensor noise; throughput loss <=5%; latency p95 increase <=5%; no meaningful new jank. Any failed gate blocks performance claims and triggers targeted debugging/rework before release.

- [ ] **Step 6: Decide allowed release wording**

If power/thermal improve across repeated runs, release notes may claim the corresponding measured improvement without extrapolating beyond the tested device/workload. If only Manager overhead improves, wording is limited to lower Manager/background polling overhead. If no measurable benefit remains, do not publish R4 as a performance release until the implementation is revised.

### Task 8: Conditional R4 documentation and release preparation

**Files:**
- Modify only after Task 7 PASS: `README_VI.md`
- Modify only after Task 7 PASS: `docs/changelog.md`
- Release notes file is generated outside source or added only if the established release process requires it.

**Interfaces:**
- Consumes verified benchmark facts and final source SHA.
- Produces Vietnamese user-facing claims bounded by measured evidence; publication remains a separate approval-gated action.

- [ ] **Step 1: Write benchmark-bounded Vietnamese documentation**

Document adaptive Manager/WebUI polling and Worker lifecycle. Include only battery/thermal/power claims actually supported by Task 7; retain `releases/latest` links rather than hard-coded stale build links.

- [ ] **Step 2: Run documentation checks**

Run: `cd docs && npm run build` plus the repository content-check path exercised by `tests/ci_verify.sh`.

Expected: PASS; no stale R3-only installation metadata is introduced.

- [ ] **Step 3: Review documentation diff and commit with fresh authorization**

Run `git diff --check`. After fresh user authorization, commit the benchmark-bounded docs with a conventional Chinese commit subject consistent with repo rules; the body must state exactly which claims are device-measured versus architectural facts.

- [ ] **Step 4: Rebuild final R4 artifacts from the final docs SHA**

Repeat Task 5 full source gates as required by impact, then Task 6 packaging from a clean disposable build tree so versionCode/Manager build ID match the final commit. Recompute and verify all SHA256 values; do not reuse the test-build hashes.

- [ ] **Step 5: Prepare Vietnamese R4 release metadata without publishing**

Prepare intended tag `v8.2.0-vi-battery-r4`, Vietnamese title/notes, `SHA256SUMS.txt`, and `update.json` pointing to the intended R4 standard ZIP. Release notes must mention adaptive polling/Worker changes and only the benchmark claims permitted by Task 7.

- [ ] **Step 6: Final verification and publication gate**

Verify repository clean state, final tag target SHA candidate, both ZIPs, APK signature/package/version, `module.prop`, update feed payload and Vietnamese release text. Report evidence to the user and request a fresh explicit approval before any tag creation, push not already authorized, GitHub Release creation or asset upload.

## Execution Order and Stop Conditions

Execute Tasks 1 → 4 as independent TDD changes, then Task 5. Task 6 produces only an unpublished test build. Task 7 requires the real Android device and is a hard gate: no R4 performance release preparation before benchmark evidence exists. Task 8 is conditional on Task 7 PASS.

If any optimization increases sustained power, thermal load, p95 latency >5%, throughput loss >5%, or causes service instability, stop at the failing task and debug/revise rather than compensating by weakening the benchmark or network behavior.

Track B is explicitly deferred. Any evidence that baseline eBPF itself remains the dominant bottleneck becomes input to a new Track B spec and plan; it must not expand this implementation plan in-place.
