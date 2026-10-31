# Capture FPS configuration

`stream_fps` now reaches both capture backends, rather than only the encoder/GL limiter.
Selection uses only advertised ranges, preferring an exact fixed rate, then a containing
range with the smallest upper bound and highest lower bound. Unsupported targets fall
back to the highest range below the target, or the lowest range above it if necessary.
Selection is independent of advertisement order. Variable ranges still permit exposure
and capture cadence to vary within the range.

CameraX Preview use cases receive `setTargetFrameRate` before binding so CameraX can
negotiate resolution and rate for the entire bound combination. The resolution selector
prefers capture rate over high-resolution-only output modes. The negotiated capture
request's AE range is observed through a Camera2 callback; no raw override defeats
CameraX's session fallback. This does not implement a constrained high-speed session.
A native camera app recording 60 FPS does not prove this generic combination of preview,
YUV analysis, and still capture can sustain 60 FPS.

When previews are shed or absent, CameraX 1.4 has no public ImageAnalysis FPS negotiation
API. This implementation conservatively limits that path to advertised ranges at or below
30 FPS and the analysis output's minimum-frame-duration bound. Missing duration metadata
is treated as unknown, with a 30 FPS ceiling. If no suitable range is known or the request
fails, automatic CameraX AE remains available. Existing no-frame detection and Camera1
fallback remain in place. Camera1 selects in milli-FPS, reads back the accepted range,
and retries with its original automatic range if the HAL rejects the chosen parameters.

`/info.json` retains `settings.fps` as the preference and adds `captureFps` containing
`requested`, `range`, and `state`. `range` is null until known. These describe capture
configuration, not measured sensor, encoder, network, or recording throughput. Variable
ranges and lower fallback ranges must not be presented as a fixed delivery guarantee.

FPS preference changes (remote controls or direct preference writes) restart an active
capture/encoder pipeline when recording is neither starting nor active. During recording,
the preference remains pending until the next capture start; `settings.fps` can therefore
differ from `captureFps.requested`. There is no automatic post-recording segment transition.

## Restart branch integration

The FPS listener and `fpsChangeOnly` guard are deliberately narrow. The recording restart
branch must route this entry point through its serialized configuration transaction,
coalesce rapid changes with other settings, snapshot the rate consistently for capture,
GL and encoder, and define readiness/failure reporting. Recording startup, pending
resolution restarts, and segment transitions remain owned by that branch. This change
does not resolve those existing lifecycle races. Expected overlapping edits are in
`StreamingService.startCamera`, backend construction, and recording/start coordination;
no sibling branch has been merged or conflict-tested here.

## Validation

Eight JVM regression tests cover fixed/variable ranges, unsupported targets, session
ceilings, missing/invalid metadata, milli-FPS, rounded frame durations, and all permutations
of an advertised range list. Run offline:

```sh
ANDROID_HOME=/path/to/android/sdk ./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Offline results: all 86 JVM tests passed (including eight new FPS cases), and the debug
APK built successfully. `lintDebug` remains blocked by eight existing API-level errors
in unchanged calls (physical-camera selection, MediaMuxer, notification channels, and
a camera-characteristic key); the Camera2 interop opt-in annotations in this file now
use AndroidX annotations so the added calls introduce no opt-in lint errors.

No phone access, APK installation, or hardware-throughput test is part of this change.
Device verification must check requested and negotiated ranges, dimensions and frame
count over wall time, supported/unsupported rates, low light, both backends, and preview
combinations, while enumerating independent viewers. The original source defect is
proven; improved delivered FPS and legacy HAL compatibility remain unverified on hardware.
