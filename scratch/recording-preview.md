# Recording preview fallback and device validation

## Scope and source finding

Base: `644fe6a`. The original CameraX failure path discarded both display and encoder
Preview use cases and retained analysis plus still capture. The service's fallback callback
then cleared the surface encoder, enabling CPU-fed encoding from analysis frames. That
source behavior is established; its contribution to any particular measured FPS is not.

The new policy first tries the requested combination, including dual Preview when requested.
With both providers and still capture present, the priority is:

| Attempt | Display Preview | Encoder Preview | ImageCapture | ImageAnalysis |
| --- | --- | --- | --- | --- |
| 1 | yes | yes | yes | yes |
| 2 | no | yes | yes | yes |
| 3 | yes | yes | no | yes |
| 4 | no | yes | no | yes |
| 5 | yes | no | yes | yes |
| 6 | no | no | yes | yes |
| 7 | yes | no | no | yes |
| 8 | no | no | no | yes |

Absent providers are never introduced, and duplicate combinations are skipped. Existing
three-attempt teardown retries remain on the initial combination; each reduced combination
is tried once. The first successful bind wins. Total failure propagates; it does not claim
a selected session. Analysis is required throughout, so there is no still-only session that
cannot feed video. Startup transactions, latch timing, restart cancellation, capture FPS,
codec selection, and Camera1 behavior are outside this change.

The encoder fallback callback runs only after a successful selection actually drops the
encoder surface. If still capture is removed, snapshots immediately use the existing fresh
analysis-frame JPEG fallback (negotiated analysis resolution, not full sensor resolution).
Logs record selected display/encoder/still flags, required analysis, and snapshot source.
If requested display preview cannot be retained, a long on-phone message states that preview
is unavailable and capture continues. This is a one-time message for that bind, not a new
persistent status UI or GPU fan-out implementation. Existing UI behavior may also hide the
phone preview while recording/streaming; this change does not promise simultaneous preview
in every activity/lifecycle state or modify recording startup to force it.

## Offline checks

Run from the repository root with an installed JDK 21 and Android SDK configured:

```sh
bun test scratch/recording-preview-contract.test.ts
./gradlew --offline --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug
git diff --check
```

Kotlin unit tests execute the production selection/fallback policy with simulated supported
combinations, including supported dual Preview, dropping display only, dropping still only,
retaining encoder plus analysis, CPU fallback ordering, hidden/missing providers, analysis
alone, and total failure. Bun source contracts check Android adapter wiring: analysis stays
bound, callbacks are guarded by successful selection, snapshots use the cache, and display
loss is surfaced. They are structural checks, not execution of CameraX or the service.
Gradle reports go to `app/build/reports/tests/testDebugUnitTest/`; build output is under
`app/build/outputs/`. These checks require no phone or service and create no device state.

Validation on 2026-10-30: all 86 Android unit tests passed (including eight new policy tests),
all four Bun wiring contracts passed, `assembleDebug` succeeded, and `git diff --check` passed.
The build used the locally cached JDK 21/SDK and Gradle dependencies with `--offline`.
Gradle/AGP deprecation and existing Kotlin warnings remain. No APK was installed and no phone
was accessed. No merge was performed; there are no unresolved merge entries. The service
change is limited to a preview-fallback callback; recording-start/restart methods are untouched.

## Retained evidence and limitations

See local `clemens/camera-review.md` and
`/Users/clemens/orca/workspaces/stream-a-club/ingest-from-4444/RESULTS.md`, the accompanying
`scratch/app-comparison.md`, and `logs/app-comparison/` in that evidence checkout.
The earlier approximately 2-FPS local dump had JPEG + YUV camera outputs. The later
approximately 20-FPS dump had PRIVATE SurfaceTexture + JPEG + YUV. An independent reader
at `192.168.8.128` was detected during the later trials and could change whether display
preview was attached. Those observations fit different capture paths but do not prove a
preview-only FPS improvement. Two starts that became inactive after three seconds are
failed starts, not slow-recording samples. No device validation is claimed for this change.

## Future reader-isolated device test plan — not executed

Prerequisites: separate authorization and an exclusive phone test window after the concurrent
agents and independent viewer have finished. Do not install or access the phone as part of
the current offline task. Obtain explicit cooperation from owners before closing any viewer.
Retain outputs under a new `logs/app-comparison/recording-preview-<timestamp>/` directory in
the evidence project; preserve original clips, settings, and logs.

1. Record build/commit, device/OS/camera ID, backend preference, requested size/FPS, orientation,
   exposure/lighting, torch, battery/thermal state, and initial settings. Keep these fixed.
   Prepare settings first and let restarts settle before each recording; use the retained
   recorder helper's documented `--use-current-settings` procedure. Do not mix rapid
   settings/start races into this comparison; record them separately for the restart branch.
2. Inventory all HTTP video, MJPEG, RTSP, ONVIF-triggered, and browser/automation readers before,
   during, and after each trial, including `192.168.8.128`. Correlate app client logs/counters
   with socket observations; a socket command with permission/netlink errors cannot prove
   an empty inventory. Status/control-only requests must not open video endpoints. If the
   inventory cannot be established, label the trial confounded and do not use it as a clean A/B.
3. With zero remote video readers, compare foreground preview requested, user-hidden preview,
   and app background. Establish each state before recording; record what display is actually
   bound rather than inferring it from a button or settings. Match clip duration (at least
   15 seconds), conditions, and startup success checks; repeat and alternate trial order.
4. Retain selection/failure logs and camera stream dumps per trial. Confirm whether encoder
   PRIVATE surface and YUV analysis remain; a supported full combination should keep both
   previews. For reduced combinations verify display-loss message, ImageCapture versus cache
   snapshot behavior, and whether CPU fallback was actually selected. A bind success alone
   does not prove frame delivery; a no-frame session may still trigger existing Camera1 fallback.
5. Confirm recording remains active beyond three seconds and through the intended duration.
   Retain start/stop responses and URI, clip, full decode result, decoded frame count divided
   by duration, actual dimensions/rotation, and snapshots. Inspect the phone for live/frozen/
   absent display preview. Separate startup failures, frame delivery, encoder throughput, and
   resolution/orientation differences. Do not infer real FPS from the configured encoder rate.
6. Repeat with exactly one intentional identified reader, isolating it from the zero-reader
   runs. Then cover both a device supporting dual Preview and a constrained/legacy device;
   test still requests during surface recording and any naturally occurring CPU fallback.
   Record unexercised fallback combinations as missing hardware coverage, not tested successes.
7. Stop only recordings/readers created for the experiment, confirm inactive status, restore
   recorded settings/UI state, and preserve files. Never terminate unrelated viewers/services
   or delete user recordings as cleanup.

Accept the device change only if supported combinations preserve preview, constrained
combinations retain the encoder before CPU fallback, preview loss is reported, snapshots
remain usable, and clips decode without truncation. Report measured FPS independently;
the selection policy alone supplies no performance guarantee.
