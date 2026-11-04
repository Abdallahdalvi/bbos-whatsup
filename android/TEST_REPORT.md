# Q20 Messenger 0.15.0-beta validation

Android-only update for the existing Nokia4Ever 0.13 backend. Minimum and target SDK: 18.

Changes: explicit header/search/tab/list focus transitions, focusable tabs in touch mode, per-row highlight backgrounds, native incoming ringtone, Wi-Fi retention during listening, a restart watchdog, timed screen wake, one-second call polling with bounded network waits, and ringtone stop on Answer/Decline. Chat opening and list resumption no longer query SQLite on the UI thread; emoji index initialization and preference disk writes run asynchronously. Cached history reads retain alias resolution and handle database read failures without throwing out of AsyncTask.

The regression suite covers navigation out of the settings/search header, cache behavior, call notification intents, silent mode, Answer/Decline flow, boot startup, existing history/document/media behavior and PCM resampling. Full regression run: 37 passed, 1 native screenshot test skipped, zero failures. Lint and APK assembly passed. The final targeted background/navigation run also passed (10 tests); lint and assembly passed again after the idle-vibration correction.

The server's read-only diagnostics contained older 0.12 physical-Q20 toast crashes, already addressed by the custom toast implementation, and test-runtime records. There was no new physical-Q20 0.14 crash report. The owner reports random termination across actions and suspects RAM use. Memory pressure/process termination is plausible, but no device heap measurement or system kill log is available; resolution is not claimed as device-verified.

Physical Q20 verification remains required for tap-to-screen latency, trackpad feel, incoming ringing while the app is visible/backgrounded and while the phone sleeps. No device is attached. Keeping Wi-Fi/CPU awake increases battery use. Android force-stop still prevents background delivery until the app opens again. The APK uses the existing development signing key and configured private API token; personalized APKs are not uploaded to the public repository.

Memory follow-up: chat thumbnail cache reduced to 2 MiB; at most eight thumbnail requests are queued per chat, targets are bounded, and queued downloads are cancelled/purged on screen destruction. All thumbnail variants are decoded with sampling and RGB565. A regression image checks oversized-thumbnail decoding. Recent-page cache reduced to 3 MiB with eight preloaded chats and trimming on memory callbacks. History browsing keeps a rolling window of 400 messages while retaining all rows in SQLite; use Refresh or reopen to return to the latest messages. Network polling pauses while browsing older history.



