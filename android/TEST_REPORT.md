# Q20 Messenger 0.17.0-beta validation

Android-only update for the existing Nokia4Ever 0.13 backend. Minimum/target API 18.

## Changes

- Remove activity transition animations and animated trackpad scrolling. Disable list animation caches/fading edges, slow the home sync tick to five seconds, avoid duplicate recent syncs, and avoid SQLite transactions for empty update pages. Background message sync runs every ten seconds; call checks remain every second.
- Keep protocol-only e2e_notification, protocol and notification records in SQLite but exclude them before message-page LIMIT is applied. They cannot consume message pages or unread counts/alerts. GP2 group records display a compact Group updated notice and do not increase unread counts. Template captions/actions are retained when supplied. A template with no content is marked unavailable; missing content is not fabricated. Ciphertext is shown as waiting for WhatsApp rather than silently discarded.
- Back on the home screen minimizes the task when background calls are enabled. The listener is explicitly independent of task removal, with an explicit broadcast alarm to restart it when Android permits. User-disabled listening remains disabled. Settings shows the last server poll time and last poll error.
- Increase the call audio WebSocket connection allowance to 25 seconds for network wake-up, with a 15-second write timeout. This is a connection robustness change, not a fix for a stalled server WhatsApp browser.

## Validation

44 tests passed, 1 native screenshot test skipped, zero failures. Lint and APK assembly passed.

Tests include message pagination across protocol records without deleting stored rows, correct unread counts, template-caption preservation, readable system labels, listener restart honoring the disabled preference, and Back minimizing the home task. Existing first-touch, document/media, memory-bound decode, history, PCM and navigation checks also run. APK signature/API levels are verified during packaging.

## Unresolved call failure and device limits

Read-only checks returned connected=true from the 0.13 server, and call status responded in approximately 234 ms. A browser-diagnostics request exceeded an eight-second deadline. This does not prove the cause of the incoming-call failure, but confirms that a simple HTTP status response alone cannot establish a healthy call engine. SSH read-only access was denied. Exact incoming-call error and current container logs/resource statistics have been requested from the owner. The runtime/call-function timeout is not declared fixed by this release.

No physical Q20 is attached. Incoming popup/ringing while minimized or asleep still requires physical validation. Standard Android foreground services support work while the user leaves the UI, but the BlackBerry runtime may suspend or terminate the Android process. This APK cannot guarantee alerts after the user closes/force-stops the runtime. Use Back from the home screen to minimize, keep background calls enabled and check the listener timestamp in Settings. See https://developer.android.com/develop/background-work/services/fgs for the general Android foreground-service model; it is not BlackBerry-device validation.

No live container, WhatsApp authentication, server source or database was changed for this release.

