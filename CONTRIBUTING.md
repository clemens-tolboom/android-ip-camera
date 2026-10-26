# Contributing to Android IP Camera

## Prerequisites

* **JDK 21** — do not install it manually. `gradle/gradle-daemon-jvm.properties` pins
  `toolchainVersion=21` and Gradle downloads the matching JDK via foojay on first build,
  for every platform. Any JDK 17+ (Gradle 9's minimum) can launch the wrapper; the launcher
  JVM and the build daemon are then different versions:

      $ ./gradlew --version
      Launcher JVM:  26.0.1 (Homebrew 26.0.1)
      Daemon JVM:    Compatible with Java 21, any vendor (from gradle/gradle-daemon-jvm.properties)
* **Android SDK** with platform **34**. Point Gradle at it with `ANDROID_HOME`, or copy
  a `local.properties` containing `sdk.dir=/path/to/Android/sdk` (gitignored).

Everything else (AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20) is pinned by the wrapper —
always build through `./gradlew`, never a system Gradle.

## Building

    ./gradlew assembleDebug      # debug APK
    ./gradlew assembleRelease    # release APKs (requires signing, see below)
    ./gradlew assembleRelease -PskipSigning   # unsigned release, for reproducible-build checks

ABI splits are enabled by default, so **both** debug and release builds produce
`armeabi-v7a`, `arm64-v8a` and a universal APK. Pass `-PenableAbiSplits=false` for a single
universal APK (recommended for F-Droid).

Docker build, if you would rather not set up a local SDK:

    docker build -t android-ip-camera:builder .
    docker run --rm -v "$PWD":/workspace -v "$PWD/.cache/gradle":/cache/gradle \
      android-ip-camera:builder debug

## Signing

`assembleDebug` works without any signing setup — it logs a warning and moves on.
`assembleRelease` fails hard unless either:

* `signing.properties` exists (copy `signing.properties.default` and fill in the keystore
  values, generating the keystore via Android Studio), or
* `-PskipSigning` is passed.

Neither `signing.properties` nor `*.jks` may be committed; both are already gitignored.

## Tests

    ./gradlew test

Plain JVM unit tests, no device or emulator required. Three suites live in
`app/src/test/kotlin/.../helpers/`:

| suite                    | tests |
|--------------------------|-------|
| `H264BitstreamTest`      |  10   |
| `FrameRateLimiterTest`   |   4   |
| `RecordingsHelperTest`   |   3   |

17 tests, no device required. There is no `app/src/androidTest` source set — the project
has no instrumented tests.

## Project layout

    app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/
      activities/     MainActivity, SettingsActivity
      helpers/        encoders, capture backends, server, storage, TLS
      StreamingService.kt, BootReceiver.kt
    app/src/main/assets/
      index.html      the entire web control panel, as one self-contained file
      files.html      recordings index page

`assets/index.html` is plain HTML/JS with no build step — edit it and reload the phone's
browser to test frontend changes. Note that the H.264 path uses `VideoDecoder`, which is a
`[SecureContext]` API, so it only runs over HTTPS. Generate a test certificate with
`./scripts/generate-certificate.sh`.

## Known issues

### `gradlew.bat` always shows as modified

On Linux and macOS, `git status` permanently reports ` M gradlew.bat` with a
112-line diff, on a clean working tree.

`.gitattributes` declares `*.bat text eol=crlf`, so git strips CR from the file before
comparing it to the index. The committed blob stores CRLF, so the comparison can never
match. `git checkout -- gradlew.bat` does not help — checkout only converts LF to CRLF,
and the blob already is CRLF, so it rewrites identical bytes.

The blame is Dependabot: it writes blobs through GitHub's API without running git's
clean filter, so every `Bump gradle-wrapper` PR reintroduces a CRLF blob. History:

| commit     | author          | blob   |
|------------|-----------------|--------|
| `baffa76`  | maintainer      | LF     |
| `048f7c1`  | dependabot[bot] | CRLF   |
| `5ac7c64`  | contributor     | LF     |
| `3568e9e`  | dependabot[bot] | CRLF   |

Workaround, without touching tracked files — add to `.git/info/attributes`:

    /gradlew.bat -text

Proper fix, one line in `.gitattributes`:

    -*.bat           text eol=crlf
    +*.bat           -text

This is permanent and needs no follow-up. Dependabot will keep writing CRLF blobs and
`git status` will stay clean, because `-text` means the committed bytes are used exactly
as-is. The absence of any warning afterwards is correct, not a missed alarm: under `-text`
a CRLF blob *is* the expected content for a batch file. The fix removes the rule that was
being violated rather than patching one instance of it.

Renormalizing instead — `git add --renormalize gradlew.bat` — is **not** durable. It keeps
`text eol=crlf`, so the next wrapper bump reintroduces a CRLF blob and the file goes dirty
again. That route only holds if the invariant is enforced in CI:

    git ls-files --eol | awk '$3 ~ /attr\/text/ && ($1=="i/crlf" || $1=="i/mixed") { print; f=1 } END { exit f }'

With `-text` applied, `/gradlew` is the only file still declaring `text` (`eol=lf`), and it
is currently stored correctly as LF.

### CI runs on JDK 17

`.github/workflows/reproducible-build.yml` sets `java-version: 17`, while
`gradle/gradle-daemon-jvm.properties` pins 21. This is intentional and harmless: 17
launches Gradle, which then provisions 21 to run the daemon. Do not "correct" either one.