# Q20 Messenger 0.16.0-beta validation

Android-only update for Nokia4Ever 0.13. Minimum/target API 18.

The double-tap regression came from making controls focusable in touch mode: Android can use the first tap to acquire focus without clicking. Standard text/image buttons now acquire focus on touch Down, so touch Up can click once. Trackpad focus remains available. A real Down/Up regression test verifies one click from one tap.

Returning home no longer serializes both complete chat-summary lists on the UI thread. Leaving a chat releases its retired audio player on a cleanup worker and ignores late callbacks. This avoids waiting for native audio teardown before navigation can finish.

XLS/XLSX preview now uses a native canvas grid that draws visible cells, with horizontal/vertical scrolling and existing page/sheet controls. It does not start or destroy a WebView. PDF rendering remains on a background-priority worker; each RGB565 page is bounded to 800x1100 pixels rather than 1400x2000 (maximum pixel storage approximately 1.76 MB rather than 5.6 MB). This reduces preview detail at high zoom; original saved files remain unchanged. Image decoding also runs in the background.

Automated checks: 40 passed, 1 native screenshot test skipped, zero failures. Android lint and APK assembly passed. Tests cover first-touch activation, release away from the UI thread, native spreadsheet page release, and previous navigation/background/media/document/history behavior. The build is signed with the existing development key and keeps the configured private API token; personalized APKs are not uploaded to the public repository.

No physical Q20 is connected. The observed three-second delay and process-memory usage cannot be timed or measured here; device confirmation remains necessary. Existing video-view cleanup uses Android's native VideoView lifecycle. No server source, live container, WhatsApp authentication, or database was changed for this release.
