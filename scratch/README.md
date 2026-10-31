# Codec capabilities offline check

Purpose: exercise the Kotlin regression suite and compile the Android integration without a phone.
Prerequisites: Bun, the project's Java 21/Gradle toolchain, Android SDK, and cached Gradle dependencies.
Run from the repository root:

```sh
ANDROID_HOME=/path/to/android/sdk bun scratch/check-codec-capabilities.ts
```

Expected: `BUILD SUCCESSFUL`; `AvcFormatPolicyTest` covers selected versus other codec
limits, rotated portrait dimensions, joint size/rate limits, alignment, exact-aspect
fallback and invalid inputs. Existing `CameraGlPipeTest` covers sensor/user rotation.
Tests use synthetic capability predicates, not a device's MediaCodec implementation.

Output: JUnit XML in `app/build/test-results/testDebugUnitTest/`, HTML in
`app/build/reports/tests/testDebugUnitTest/`, APKs in `app/build/outputs/apk/`.
All are ignored build artifacts; `./gradlew clean` removes them. No installation or
camera/network viewer is part of this script.

## Validation recorded 2026-10-30

On branch `fix/codec-capabilities`, base `644fe6a`, ran:

```sh
ANDROID_HOME=/Users/clemens/Library/Android/sdk bun scratch/check-codec-capabilities.ts
```

Result: `BUILD SUCCESSFUL`; 85 JVM tests across 11 suites, zero failures/errors/skips,
including seven new `AvcFormatPolicyTest` regressions. Debug APK compilation succeeded;
no APK was installed. `git diff --check` passed and `git ls-files -u` was empty.
Gradle needed sandbox access to its existing cache; all dependency resolution was offline.
Existing AGP/Java deprecation warnings remain.

The source defect was the use of unrelated encoder maxima and independent dimension
clamps. Validation now uses the opened codec instance, including the complete configured
format, and logs its identity and effective output. `/info.json` adds `h264Encoder`
(`codec`, `width`, `height`, configured `fps`, `error`); dimensions are null without an
active encoder. Preferences remain in the existing settings fields. Camera size choices
are capture candidates, not promises of codec support. Surface paths explicitly allow
an aligned, exact-aspect downscale at the same rate; YUV input is validated without
silently resampling. Missing support produces a configuration error.

Limitations: synthetic capability tests and offline builds do not validate vendor codec
behavior, camera negotiation, actual FPS, or recording output on a device. The MIME
selection policy is retained; this change validates that selected codec rather than
searching for another encoder. Exact-aspect fallback can fail when no supported aligned
pair exists. No evidence establishes the historical 960×720 file's cause. No main or
sibling worktree changes, commits, publication, or upstream messages were made. Sibling
integration/merge was not attempted; shared service files may overlap during integration.
