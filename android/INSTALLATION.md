# Install Q20 Messenger 0.17.0-beta

Install `Q20Messenger.apk` over the existing app. This release is Android-only; keep the Nokia4Ever server on its current 0.13 version. Do not upload or run the server updater for this release. The APK keeps the existing app signing key and upgrades the local database in place.

The app uses the API token already embedded in this personalized single-user APK as its initial setting. If the saved server URL/token are already working, leave them as-is. On the Q20, confirm the server address is reachable (usually `http://192.168.0.107:8034`) and background listening is enabled in Settings. The ongoing listener notification indicates that it is active. Stopping the listener disables its automatic restart until you enable it again.

The listener restarts after reboot/package replacement when enabled. BlackBerry's Android Runtime and phone power settings can still limit background notification delivery; verify closed-app incoming calls/messages on the device. Android force-stop disables background startup until the app is launched again.

## Download

Use `output/Q20Messenger.apk`. Its SHA-256 is recorded in the release summary when packaged. No server patch is required for 0.17.

## Source

`Q20Messenger-source.zip` contains Android source, tests, build scripts and reports without the private API token. The personalized server patch/updater archives are retained for the existing 0.13 backend only and are not part of this Android installation step.

After installation, open the app once and confirm Settings > Background calls and messages is enabled. Keep the ongoing listening notification active. Test a call with the screen on, then another with the phone asleep.




For background calling, press Back from the home chat list to minimize the task, then lock the phone. Keep Background calls and messages enabled. Settings shows the last server-check timestamp and last poll error. Fully closing/force-stopping the BlackBerry Android runtime can still prevent background alerts. Incoming server Runtime/call-function timeouts remain unresolved; this APK is not proof of a server call-engine repair.

