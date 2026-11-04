# Q20 Messenger

Q20 Messenger is an Android client for the BlackBerry Q20 Android Runtime, paired with the Nokia4Ever WhatsApp bridge. The Android source and proposed 0.18 staging server source are included here. Release at this commit: Android 0.17.0-beta; server 0.18 staging proposal.

## Build Android

Use JDK 17 and Android SDK Platform 34 / Build Tools 34.0.0. From `android/`, run `./gradlew testDebugUnitTest lintDebug assembleDebug` or use `build_apk.ps1` on Windows. The app targets API 18. Set `Q20_API_TOKEN` at build time for a private personalized APK, or enter the token in the app's Settings. Never commit a real token or a personalized APK.

## Server

The `server/` directory preserves the legacy API router and adds the Q20 v2 endpoints. Install dependencies with `npm ci`, configure `Q20_API_TOKEN` through the deployment environment/secret manager, and use the server's existing persistent auth/database mounts. Do not put WhatsApp auth state, SQLite databases, media, or credentials in Git.

Android 0.17 works with the deployed 0.13 backend. The 0.18 server contacts/browser fix is proposed for isolated staging; see server/staging-patch/README.md and server/TEST_REPORT_018.md. See `android/TEST_REPORT.md`, `android/API_DOCUMENTATION.md`, and `android/INSTALLATION.md` for behavior, API details, and Q20 validation limits.




