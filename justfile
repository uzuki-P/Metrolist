# Metrolist+ developer commands.

app_id := "metrolist-plus"
app_version := `sed -n 's/^appVersion=//p' gradle.properties`
# Upstream's Gradle toolchains need JDK 25.
java_home := env("JAVA_HOME_25", home_directory() / ".local/share/mise/installs/java/temurin-25")
# Terminals opened from AppImage-hosted editors inject LD_LIBRARY_PATH, which breaks Compose Desktop.
gradle := "env -u LD_LIBRARY_PATH JAVA_HOME=" + java_home + " ./gradlew"
apk_output := "androidApp/build/outputs/apk/release/androidApp-release.apk"

# List available commands.
default:
    @just --list

# Build the debug APK and install it on the connected device.
dev:
    {{gradle}} :androidApp:installDebug

# Run the desktop app from source.
dev-desktop:
    {{gradle}} :desktopApp:run

# Run the shared and desktop unit tests.
test:
    {{gradle}} --no-parallel :shared:desktopTest :desktopApp:test

# Build the release APK and stage a timestamped copy in _apk/.
stage-apk:
    {{gradle}} :androidApp:assembleRelease
    bash scripts/stage-artifact '{{apk_output}}' '{{app_id}}' '{{app_version}}' _apk

# Build and stage the release APK, then upload the staged copy.
build-apk: stage-apk
    #!/usr/bin/env bash
    set -euo pipefail
    # The newest staged copy of this version is the one stage-apk just wrote.
    staged="$(ls -t _apk/{{app_id}}-{{app_version}}_*.apk | head -1)"
    ~/docker/files/bin/share "$(pwd)/$staged"

# Wait for the android-apk CI build of the pushed HEAD and fetch it into _apk/.
[linux]
ci-apk:
    bash scripts/ci-apk

# Wait for the windows-exe CI build of the pushed HEAD and fetch it into _apk/.
[linux]
ci-exe:
    bash scripts/ci-exe
