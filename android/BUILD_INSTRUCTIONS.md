# Q20 Messenger command-line build

Native Java client for BlackBerry 10 Android Runtime. Minimum and target SDK are 18; compile SDK is 34 because AGP 8.2.2 requires a current compile platform. No Android Studio is needed.

Requirements: JDK 17, Android SDK Platform 34 and Build Tools 34.0.0. The wrapper downloads pinned Gradle 8.2.1 and AGP 8.2.2 on the first run.

Windows PowerShell: `./build_apk.ps1`

Linux/macOS/Git Bash: set `ANDROID_SDK_ROOT` to the SDK directory, then run `./build_apk.sh`.

Each script checks Java/SDK, runs a clean Java/resource/APK build, verifies the debug APK signature, checks manifest SDK levels with `aapt`, and copies the result to `output/Q20Messenger.apk`.

For a single-user personalized APK, set `Q20_API_TOKEN` in the build process before running the script. `AppPrefs` uses that value as its initial token, while the user can still edit it in Settings. This embeds a bearer secret in the APK, where it can be extracted; do not share a personalized APK. The source ZIP contains only the build-time wiring, not the token. If the environment value is omitted, enter the token in Settings after installation.

## Server connection

The rebuilt client uses the authenticated `/api/v2` contract in `API_DOCUMENTATION.md`. Review and deploy `Q20Messenger-server-v2-patch.zip` separately and configure `Q20_API_TOKEN` on the server. The app stores the matching token locally in private preferences. The live server was not patched by this build. See `INSTALLATION.md` for setup.

## Separate staging client

Set Q20_STAGING_BUILD=true in the build environment to produce Q20 Messenger TEST with application ID com.aghani.q20messenger.staging and default port 8035. Leave the variable unset for the original application ID and port 8034. The TEST app installs alongside the original and has separate private history/settings. Include Q20_API_TOKEN for a personalized build; never publish that APK.

