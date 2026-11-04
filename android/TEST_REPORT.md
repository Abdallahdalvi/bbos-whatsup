# Q20 Messenger 0.14.0-beta test report

Date: 2026-11-04. Android versionCode 14, minimum/target API 18. This release is an Android-only update for the already deployed Nokia4Ever 0.13 backend.

## Build and automated checks

- Signed APK built successfully as `Q20Messenger.apk` (15,463,502 bytes). The project build script validated its APK signature and API 18 manifest settings.
- Unit tests: 35 passed, 1 skipped, 0 failed (Robolectric; API 19 runtime). The skipped test captures a native screenshot that is unsupported on this Windows host.
- Android lint passed.
- Navigation checks cover top-positioned tabs, focus limited to one chat row, trackpad axis handling, and switching tabs only after moving focus to the tab bar.
- Cache and notification checks cover immediate local cached-chat display, bounded cache invalidation, new-message deduplication, suppressing alerts for old/history-imported and own messages, current-chat suppression, silent/vibrate/normal sound modes, incoming-call Answer/Decline actions, and not auto-answering a call before Answer is chosen.
- The SQLite database remains the full local history store. The in-memory preview cache is bounded (6 MiB, recent 100-message pages); it does not preload every message or media file into RAM.

## Background behavior and limits

The app starts a foreground listener with an ongoing notification. When enabled, it polls for calls and runs the existing sync coordinator while the app is in the background. New messages generate Android notifications and incoming calls generate an actionable notification/full-screen activity request. Sound/vibration follow the phone's ringer mode, and notifications are suppressed for the chat currently open. The listener restarts after boot or package replacement when the user has not stopped it.

This was verified by source-level/Robolectric tests, not on a physical Q20. Android may defer or suppress full-screen presentation, background activity launches, sound, or service restarts depending on the BlackBerry Android Runtime, device settings, process termination, and network reachability. A force-stopped app cannot receive broadcasts until opened again. Physical Q20 acceptance is still required for closed-app ringing, message alert sound, trackpad feel, battery use, and resource pressure.

## Backend/history state

No server source, container, WhatsApp authentication, or database was changed for 0.14. The connected server was reported at 0.13 before this Android-only build. The latest server history status reported 445 conversations total, 417 completed, 8,006 messages imported, 27 waiting for phone history, and one retry/error; these are server-reported counts, not a claim of complete account history. No additional server sync was performed by this build.

## Device acceptance recommended

Install the APK over the existing app. Leave background listening enabled, exit the app, then test incoming call and message notifications; separately test notification sound in normal/vibrate/silent modes. Check tab focus and trackpad scrolling at the physical square display. Confirm app remains responsive after opening chats with long histories. Report any failures with the listener notification state and server version.
