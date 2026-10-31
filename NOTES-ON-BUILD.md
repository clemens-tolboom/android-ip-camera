# Notes on Building android-ip-camera

_Last updated: 2026-10-30_

Context: macOS on Apple Silicon (arm64), 8 GB RAM / 8 CPU cores, Podman 6.1.4, Docker CLI installed but its Colima daemon was not running.

## TL;DR

- Build with `./scripts/build debug` or `./scripts/build release`.
- The container **must run as `linux/amd64`**. Google's Linux AAPT2 and SDK build-tools are x86-64-only; an arm64 container fails under QEMU.
- The Podman machine needs **at least 4 GiB**. With 2 GiB the kernel OOM-kills the Gradle daemon.
- The `SDK XML versions up to 3 ... version 4` warning is **cosmetic** and does not stop the build.
- Release builds require `signing.properties` and `app/signing-key.jks`; debug does not.
- **Clemens must recheck:** the AGP 9 built-in Kotlin migration warning (`org.jetbrains.kotlin.android` is deprecated). Seen in both manual and Docker builds (details at the end).

## Image layout

The Dockerfile builds a **toolchain image only**. `.dockerignore` excludes everything except `Dockerfile` and `scripts/docker/`, so the repo is bind-mounted at runtime, not baked into the image.

| Path | Purpose |
|---|---|
| `/workspace` | Bind-mounted repo (runtime) |
| `/cache/gradle` | `GRADLE_USER_HOME`, bind-mounted from `.cache/gradle` |
| `/opt/android-sdk` | Android SDK; bind-mounted from `.cache/android-sdk` when present |
| `/usr/local/bin/apic-build` | `scripts/docker/entrypoint.sh` |

Image contents: `eclipse-temurin:21-jdk`, Android cmdline-tools `11076708`, `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`. Image is built as `linux/amd64` and is about 1.46 GB.

Entrypoint commands:

| Command | Action |
|---|---|
| `debug` | `./gradlew assembleDebug` |
| `release` | `./gradlew assembleRelease`, then `apksigner verify --print-certs` on every `*-release.apk` |
| `signing-info` | Verifies already-built release APKs |
| `install` | `./gradlew installRelease` (needs a reachable adb device) |
| anything else | Executed verbatim, e.g. `./gradlew test` or `/bin/bash` |

## `scripts/build` flow

1. `cd` to the repo root.
2. Parse `debug` or `release`.
3. Set `PLATFORM=linux/amd64` (overridable with `PLATFORM=...`).
4. Detect an engine: `docker info`, then `podman info`.
5. Build the image: `$ENGINE build --platform "$PLATFORM" -t android-ip-camera:builder .`
6. `mkdir -p .cache/android-sdk .cache/gradle`.
7. Seed the SDK cache with `sdkmanager --sdk_root=/cache/sdk --licenses` and install `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`.
8. Run the APK build with the repo and both caches mounted.

### `scripts/build` findings

| Finding | Detail | Status / impact |
|---|---|---|
| Podman machine is restarted every run | Lines 23-25 call `podman machine stop`, `set --memory 4096`, `start` inside the Podman branch | Slow; kills any container already running. Consider moving to one-time setup |
| Extra Gradle args were dropped | Usage hint mentions `-Dorg.gradle.jvmargs=... --max-workers=2`, but the final run only passed `"$TARGET"` | Fixed: added `shift` after the `case` and `"$@"` to the run command |
| Image is rebuilt every run | `$ENGINE build` always runs | Mostly cached, but still overhead |
| `EXPECTED_RELEASE_SHA256` cannot be overridden | Script does not pass `-e` through | Run the entrypoint manually or extend the script |
| Seeded SDK masks baked SDK | `.cache/android-sdk` is mounted at `/opt/android-sdk` | Cache must be seeded first; the script does this every run |
| Cache lacks cmdline-tools | Seed installs only platform-tools/platform/build-tools, not `cmdline-tools` | Fine for the APK build; `apksigner` and `adb` are present, `sdkmanager` is not needed |
| Rootful Docker ownership | No `--user` is passed | Outputs may be root-owned on rootful Docker/Linux |

## Build failures encountered and fixes

| Symptom | Cause | Fix |
|---|---|---|
| `failed to connect to the docker API at unix:///Users/.../.colima/default/docker.sock` during cache seeding | Seed step hardcoded `docker run` even though the selected engine was Podman | Changed to `"$ENGINE" run` |
| `Error: statfs .../.cache/gradle: no such file or directory` | Podman does not auto-create missing bind-mount source directories (Docker does) | `mkdir -p .cache/android-sdk .cache/gradle` |
| `qemu-x86_64-static: Could not open '/lib64/ld-linux-x86-64.so.2'` from AAPT2 | Image was `linux/arm64`, but AAPT2 is an x86-64 ELF binary | Build and run with `--platform linux/amd64` |
| `Gradle build daemon disappeared unexpectedly` | Podman VM had 2 GiB; kernel OOM-killed the Java process | Set machine memory to 4096 MiB; optionally cap Gradle workers |
| `This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered` | Old cmdline-tools (`11076708`) reading newer SDK repository metadata | Cosmetic; update cmdline-tools to 17+ (current build `15859902`) to silence it |

### Evidence for the QEMU/arch issue

- Image before the fix: `linux/arm64`, container `aarch64`.
- AAPT2 from Gradle cache: `ELF 64-bit LSB pie executable, x86-64`, interpreter `/lib64/ld-linux-x86-64.so.2`.
- Seeded SDK binaries (`aapt2`, `adb`) are also x86-64.
- The arm64 rootfs has no x86-64 loader, so QEMU cannot start AAPT2. This is an architecture mismatch, not a broken QEMU.

### Evidence for the OOM issue

Podman VM at 2 GiB:

```
Mem: 1944 total, 296 used, 1046 free, 674 buff/cache
Swap: 0
```

`dmesg` inside the VM:

```
Out of memory: Killed process ... (java) total-vm:6448388kB, anon-rss:1506796kB
Out of memory: Killed process ... (java) total-vm:6219668kB, anon-rss:1574600kB
Out of memory: Killed process ... (java) total-vm:8167388kB, anon-rss:1391092kB
```

Gradle daemon log showed `Starting build in new daemon [memory: 2 GiB]`, then the daemon disappeared. The host itself had ~6.95 GB of swap in use at the time, so 8 GiB for the VM is **not** advisable on an 8 GB host. Use 4 GiB and close memory-hungry apps if needed.

At 4 GiB the daemon was **still** OOM-killed:

```
Out of memory: Killed process 2102 (java) total-vm:8613560kB, anon-rss:2452572kB
```

The VM has ~3.8 GiB usable; Gradle's 2 GiB heap plus the Kotlin daemon, AAPT2, and the OS exceed it. The host swap was also nearly exhausted (8.6 GB used / 609 MB free). Do **not** raise the VM to 6-8 GiB on this 8 GB host. Instead, keep 4 GiB and reduce Gradle's footprint:

```bash
./scripts/build debug \
  -Dorg.gradle.jvmargs=-Xmx1536m \
  --max-workers=2 \
  -Dorg.gradle.parallel=false
```

## Dockerfile:86 cross-reference

`Dockerfile:86` says:

```dockerfile
# SDK components matched to .github/workflows/reproducible-build.yml
```

| Item | Dockerfile | `.github/workflows/reproducible-build.yml` | Match |
|---|---|---|---|
| `platform-tools` | line 111 | line 26 (`packages: 'platform-tools'`) | Yes |
| `platforms;android-34` | line 112 | line 30 | Yes |
| `build-tools;34.0.0` | line 113 | line 30 | Yes |
| JDK | line 78: `eclipse-temurin:21-jdk` | lines 20-21: Temurin **17** | No |
| Gradle flags | `./gradlew assembleRelease` with repo defaults (`org.gradle.parallel=true`, daemon) | `-PskipSigning --no-daemon --max-workers=1 -Dorg.gradle.parallel=false` | No |
| cmdline-tools | line 87: pinned `11076708` | `setup-android@v4`, unpinned | No |
| Signing | `signing.properties` + `app/signing-key.jks` | `-PskipSigning` (unsigned) | Expected difference |

The comment is accurate for the three SDK packages only. The workflow's reproducibility check compares two builds on the same runner, so it does not require Docker parity. If the container is meant to reproduce CI artifacts, align the JDK to 17 and add the CI Gradle flags.

## SDK XML v3/v4 warning

Exact message:

```
Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered. This can happen if you use versions of Android Studio and the command-line tools that were released at different times.
```

- Cosmetic warning; package installation continues.
- Google issue [384050899](https://issuetracker.google.com/issues/384050899) says updating the Android SDK Command-line Tools to version 17+ resolves it.
- Dockerfile currently pins `ANDROID_CMD_TOOLS_VERSION=11076708`.
- Current download (checked 2026-10-30) is `15859902`; bumping the ARG and rebuilding the image removes the warning.
- Not worth interrupting a build for; the rebuild of the SDK layers under QEMU is slow.

## Kotlin plugin deprecation warning (AGP 9 built-in Kotlin)

Seen in **both** the manual (non-Docker) build and the Docker build:

```
w: ⚠️ Deprecated 'org.jetbrains.kotlin.android' plugin usage
The 'org.jetbrains.kotlin.android' plugin in project ':app' is no longer required for Kotlin support since AGP 9.0.
Solution: Remove both `android.builtInKotlin=true` and `android.newDsl=false` from `gradle.properties`, then migrate to built-in Kotlin.
See https://kotl.in/gradle/agp-built-in-kotlin for more details.
```

- Warning only; the build continues.
- Cause: `app/build.gradle` applies `id 'org.jetbrains.kotlin.android'`, while AGP 9.x has built-in Kotlin support. `gradle.properties` also sets `android.builtInKotlin=false` and `android.newDsl=false`, keeping the legacy plugin path.
- Suggested migration: remove the Kotlin Android plugin and the two `android.builtInKotlin` / `android.newDsl` properties, then follow <https://kotl.in/gradle/agp-built-in-kotlin>. The `kotlinOptions` block may need to move to the built-in DSL.
- **Clemens must recheck:** the warning says to remove `android.builtInKotlin=true`, but this repo sets it to `false`. Confirm the correct migration before changing `gradle.properties`; this affects the Kotlin/AGP configuration, not just the warning.

## Toolchain and project versions

| Component | Version / value |
|---|---|
| Host | macOS arm64, 8 GB RAM, 8 cores |
| Podman | 6.1.4, machine 4 CPUs, 4096 MiB (set by script) |
| Docker | CLI present; Colima daemon not running |
| Builder image | `linux/amd64`, `eclipse-temurin:21-jdk`, ~1.46 GB |
| Gradle | 9.8.2 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin Gradle plugin | 2.4.21 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 |
| versionName / versionCode | 0.15.0 / 150 |
| AAPT2 | `9.4.1-15978811-linux` (x86-64) |
| cmdline-tools | `11076708` (old, XML v3) |

`gradle.properties` enables configuration cache, parallel builds, and build caching, with `-Xmx2048m`.

## Outputs

With `rootProject.name = "androidipcamera"` and version `0.15.0`:

```
app/build/outputs/apk/debug/androidipcamera-0.15.0-universal-debug.apk
app/build/outputs/apk/release/androidipcamera-0.15.0-universal-release.apk
app/build/outputs/apk/release/androidipcamera-0.15.0-arm64-v8a-release.apk
app/build/outputs/apk/release/androidipcamera-0.15.0-armeabi-v7a-release.apk
```

Default release builds include ABI splits plus a universal APK. Use `-PenableAbiSplits=false` for a single universal APK.

## Release signing

- `signing.properties` is missing in this checkout; `signing.properties.default` has `storeFile=signing-key.jks`, `keyAlias=key0`, and empty passwords.
- `file(...)` in `app/build.gradle` resolves relative to `app/`, so the keystore belongs at `app/signing-key.jks`.
- Release builds fail until both `signing.properties` and the keystore exist.
- The entrypoint verifies release APKs and compares the signer SHA-256 with `1111be81c861e199c6485d367c37680c4b778fba301980d2f0f9a2800f77f70a` (official key documented in `README.md`). Override with `-e EXPECTED_RELEASE_SHA256=...` when running the container directly.

## Other scripts

| Script | Purpose |
|---|---|
| `scripts/docker/entrypoint.sh` | Container command dispatcher; builds, verifies signing, prints cert info |
| `scripts/generate-certificate.sh` | Generates the app's **TLS server** certificate in `certificates/` (`personal_certificate.key`, `.crt`, `.p12`). Not the APK signing key |
| `scripts/generate_fastlane_metadata.py` | Generates F-Droid/fastlane metadata from the README; converts screenshots/icons; updates the Gradle wrapper SHA-256 |

Minor finding: `generate_fastlane_metadata.py` says it formats a 9-digit version code (`0.2.2 -> 000002002`), but `extract_version_name_as_code()` returns `int("022") == 22`, so the changelog file is `22.txt`, not `000002002.txt`. Not build-critical.

## Commands

```bash
# Build debug
./scripts/build debug

# Build signed release (requires signing.properties + app/signing-key.jks)
./scripts/build release

# Single universal release APK
./scripts/build release -PenableAbiSplits=false   # NOTE: script currently drops extra args

# Manual image build
podman build --platform linux/amd64 -t android-ip-camera:builder .

# Manual cache seed
mkdir -p .cache/android-sdk
podman run --platform linux/amd64 --rm \
  -v "$PWD/.cache/android-sdk":/cache/sdk \
  android-ip-camera:builder \
  sh -lc 'yes | sdkmanager --sdk_root=/cache/sdk --licenses >/dev/null \
          && sdkmanager --sdk_root=/cache/sdk --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null'

# Podman memory
podman machine stop
podman machine set --memory 4096
podman machine start
```

## Open items / Clemens must recheck

- **Clemens must recheck:** the AGP 9 built-in Kotlin deprecation warning. It appears in both the manual non-Docker build and the Docker build. The warning asks to remove `android.builtInKotlin=true` and `android.newDsl=false` and migrate off the `org.jetbrains.kotlin.android` plugin, but this repo sets `android.builtInKotlin=false`. Confirm the correct migration against <https://kotl.in/gradle/agp-built-in-kotlin> before changing the build.
- Confirm the reduced-heap build (`-Xmx1536m --max-workers=2 -Dorg.gradle.parallel=false`) completes; 4 GiB with the default 2 GiB heap still OOM'd.
- Decide whether `scripts/build` should really stop/restart the Podman machine on every invocation.
- Decide whether to forward extra Gradle args (`shift` + `"$@"`) so the documented `--max-workers` hint works.
- Decide whether to bump `ANDROID_CMD_TOOLS_VERSION` to `15859902` to remove the XML v3/v4 warning.
- Decide whether to align the Docker JDK and Gradle flags with CI (Temurin 17, `--no-daemon --max-workers=1 -Dorg.gradle.parallel=false`) for reproducible parity.
