# Q20 Messenger 0.17.0-beta

Reduce animations and idle sync work; distinguish protocol records from user messages; preserve available business-template content; minimize Home on Back for background listening; add restart broadcast and last-check/error status in Settings. Increase WebSocket connection allowance during network wake-up. The server Runtime/call-function timeout remains under investigation. See TEST_REPORT.md.

# Q20 Messenger 0.16.0-beta

Fix first-tap focus consumption, remove home-list JSON serialization on the UI thread, release retired audio players asynchronously, replace spreadsheet WebView with a native visible-cell grid, reduce PDF page buffers, and decode images away from the UI thread. See TEST_REPORT.md for test results and device limits.

Memory follow-up in 0.15: bounded thumbnail queue/targets, sampled decoding, cache release on chat destruction and system memory callbacks, 3 MiB recent-page cache, and a 400-message history window backed by SQLite.

# Q20 Messenger 0.15.0-beta

- Explicit Down routes from header to tabs and then chats; search can return to chats. Controls accept trackpad focus after touch input, and a deliberate vertical turn can escape an earlier horizontal gesture.
- Individual solid chat-row selection replaces shared row selector state. Header focus uses an outline.
- Remove SQLite reads from chat creation and list resumption; initialize the emoji index asynchronously, and write settings without waiting for disk.
- Incoming listener retains Wi-Fi, polls every second with short timeouts and uses an alarm watchdog. Incoming alert wakes the screen for ten seconds and uses a native ringtone/vibration with explicit stop on answer/decline.
- Database read errors in cached chat loading leave an empty/offline result instead of escaping the worker as an unhandled exception.

See TEST_REPORT.md for device-validation limits and the unconfirmed crash report.
# Q20 Messenger 0.14.0-beta — Android changes

- Move the compact tab row below the app header and above search/chat content. Trackpad motion now follows its dominant axis; horizontal gestures while in the chat list do not change tabs. Move focus up to the tab bar, then use left/right to switch.
- Keep focus styling on the selected chat row instead of tinting the whole list. Preserve row selection and scroll position across refreshes.
- Render cached chats and summaries immediately, then refresh from the server asynchronously. Separate local SQLite reads from network/synchronization work. Keep an LRU cache capped at 6 MiB with recent message pages; persistent SQLite retains the full local history without loading all messages/media into memory.
- Add an optional persistent foreground background listener for incoming calls and message notifications. It can restart after reboot/package update, respects normal/vibrate/silent ringer modes, suppresses alerts for the currently open conversation, and filters own/older imported messages.
- Incoming-call alerts offer Answer and Decline; answering is an explicit action. Message alerts are grouped by chat.

This APK is compatible with the already installed Nokia4Ever 0.13 backend and does not require a server update. UI behavior, closed-app notifications, ringing and battery/resource use still require physical Q20 verification; automated tests cannot establish the BlackBerry runtime's background-delivery policy.

Earlier release implementation notes follow.
# 0.13 update — 2026-11-04

- In-app PDF page viewing and read-only XLS/XLSX worksheets, with row/column paging and sheet selection. Save preserves originals; no external viewer is needed for these supported types. See TEST_REPORT.md for limits.
- Dark theme enabled by default with a light-mode setting; compact icon tabs, revised type/spacing, date separators and no per-bubble focus borders.
- Persistent chat selection even for mouse-like trackpad events, up/down row selection, left/right tab switching and keyboard access to message poll/CTA actions.
- Automatic linked-session history import with home-screen progress, phone-history requests where supported, persisted resume/throttling, bounded browser waits and non-progress detection. Known conversations show before backfill completes.
- Chronological timestamp/ID pagination fetches older server history and avoids skipping equal-timestamp messages. Earlier imported records cannot replace the latest chat summary.
- Native programmatic toasts avoid the observed BB10 AppCompat system-toast XML crash.
- Existing successful call transport retained. Native PDF rendering and new UI behavior still require physical Q20 acceptance.

## Earlier implementation notes

# Version 0.12 current changes

- Preserve the working 0.11 call bridge; add a durable Calls tab for this client's new calls.
- Correct chronological chat ordering and unread badges; preserve latest summaries during old-history import.
- Consolidate phone/LID history, names and read positions; prefer saved contact names with bulk mapping.
- Find legacy media without filename metadata; separate thumbnails from long video conversions; enlarge the video play overlay.
- Use a legacy-native media screen, safe open/error paths and owned-server diagnostics; retain explicit document Open/Save actions and readable filename cards.
- Reduce UI database work, contact refreshes and unnecessary chat redraws; isolate worker pools and preserve focus/scroll position.
- Add standard focus, trackball, mouse-wheel and selected-message actions for navigation without touch.
# Version 0.11 current changes

- Display the real WhatsApp business-call notice on the Q20 with Continue/Cancel, resume through a request-bound endpoint, bound setup time and cancel pending requests on hang-up.
- Reduce browser capture blocks to 32ms and bound live audio queues; use smaller device-supported Android buffers. Actual latency improvement needs device confirmation.
- Single-tap photos/videos open a cached 480-pixel copy. Original/Save sit at the viewer top; Save keeps original quality.
- Recover document filenames in background, show useful fallback type labels, write downloads atomically and retain duplicate filenames without overwriting.
- Give the audio timer more space and replace the large seek thumb that overlapped it.
# Version 0.10 current improvements

- Inline play/pause, seeking and actual elapsed/total audio duration in compact chat bubbles; one player per conversation, no generic audio-viewer detour.
- Circular mic/send composer action, switching with text input; timed voice recording retains Done/review/Cancel.
- Smaller image/video placeholders and aspect-preserving previews capped to half the square screen; redundant Photo/Video headings removed; captions retained.
- Explicit Document attachment picker, compact filename/type/size card, direct original download, filename preservation, Downloads save and content-URI sharing with document apps. Built-in text/JSON preview; installed viewers handle PDF/Office.
- Separate voice-note conversion from ordinary audio uploads and preserve original names/types for files sent as documents.
- Traced the real 502 to WhatsApp call_blocked before VoIP readiness, with browser/account eligibility checks passing. Chrome in Docker exposed no media devices. Added a virtual headless media device and native call initialization/error tracing; successful calls remain to be verified.

The following sections describe earlier releases.
# Q20 Messenger improvements

## Version 0.9.0-beta square-screen and voice work

- AppCompat Android widgets/theme, Google Material icon images for visible search/settings/call controls, and PhotoView pinch/double-tap image zoom. Compact 44dp header, 40dp bottom navigation, 64dp conversation rows, adaptive bubble widths, no repeated sender label in private messages, separate recording row and keyboard resize behavior.
- Call button places a direct voice call. Call-link choices are removed from the main flow; the legacy server endpoint remains. Its known lazy module-loading failure is addressed using the upstream PR #201950 loader pattern.
- Call model duplicate-event handling, canonical phone/LID selection, registration grace, microphone fallback, sample-rate conversion, background listening and actionable failure reports.
- Foreground listener keeps a partial wake lock for incoming-call polling, with an ongoing notification and Stop listening action. It intentionally favors call availability over battery use.
- Optional browser/runtime compatibility is now part of the complete updater: Chrome 156 is installed and launched against a scratch profile first, then the original stopped WhatsApp browser profile is backed up before the linked session is reused. Chrome 146 remains on disk. Actual real-account calls still need validation.

## Version 0.8.0-beta

- White app header, green accents, rounded search and selected bottom navigation, larger chat names and avatars, lighter chat bubbles. Design reference: https://www.meta.com/design-at-meta/blog/whatsapp-user-interface-update/ (design principles, not a claim of exact 2026 parity).
- Media menus offer download/view and explicit save to Downloads/Q20Messenger for images, audio, voice notes, video and documents. Video originals remain separately downloadable; a Q20 playback copy uses H.264 baseline/AAC, up to 480 pixels per side and 24 fps without stretching portrait video.
- Video preview loading no longer downloads the full original to the handset; it requests a small JPEG preview. Playback still opens a fully downloaded local file.
- Media viewer has a Save action and releases/pauses playback when leaving the screen. Concurrent original downloads use unique temporary files.
- Direct calling dependency now installs through an exact HTTPS tarball, and health exposes whether the call methods exist. Complete server updater includes all helper modules, private token configuration and dependency installation.
- Local FFmpeg conversion and fresh dependency installation passed; physical Q20 performance and real WhatsApp calling remain unverified.

## Follow-up name and call diagnostics

- Server metadata refresh now rejects phone-number-only labels, looks up saved contact/profile names using WhatsApp contact metadata, resolves LID aliases, and labels unresolved newsletter chats as "Channel" instead of showing the raw JID.
- The Android chat list also rejects number/JID labels as names and uses a friendly fallback if the server cannot provide a profile or saved contact name. This can only show the actual person's name when WhatsApp exposes it to the linked account; unsaved contacts without a WhatsApp profile name remain "WhatsApp contact".
- Call-link failures now return and display the server's error stage, separating link generation from sending the invitation. The Call menu offers a regular phone dialer directly for chats with a resolved phone number.

## Version 0.5.0-beta follow-up

- Android sync now pages through the linked WhatsApp contacts before refreshing chat summaries. Resolved contact names update chats matched by phone JID, including local conversations whose primary JID is a LID.
- Image/video/GIF previews now use a larger aspect-ratio-aware stage with reduced message row and bubble padding.
- Voice recording shows an elapsed timer. Done creates a reviewable voice-note draft; the user can Send it or Cancel and discard it.
- GitHub call research found direct `whatsapp-web.js` APIs in open pull requests (#201825, #201948, #201949), including call placement, accept/end, and audio streams. Those changes are still unmerged experimental work; the current backend remains on stable 1.34.7 and continues to create call-link invitations. A real in-app call needs a bidirectional audio transport and a tested server fork, so it is not represented as fixed by this APK.

## Version 0.3.0-beta update

- Updated the conversation list styling, row spacing, tab emphasis, and unread badge presentation to follow current WhatsApp layout conventions while staying compatible with the BlackBerry 10 Android Runtime.
- Added server-provided participant names to the message response and local SQLite store, with a non-identifying fallback for unresolved LIDs. Existing local databases migrate additively.
- Group and contact placeholders no longer replace a previously resolved display name. The server now labels unresolved groups as groups instead of returning the misleading "WhatsApp contact" fallback.
- Unread counts are maintained locally from each chat's last-read server message ID. Existing history is marked read once on first sync; future incoming messages appear as badges until the chat is opened.
- Chats render their saved messages immediately. A lightweight per-chat refresh replaces the previous full-account sync on every chat open, and refresh errors leave cached content visible.
- D-pad/Enter events activate conversation rows and message items for physical navigation. This maps the Android key events exposed by compatible Q20 runtimes; hardware behavior still needs a device check.
- Removed the repeated "Tap to open" copy from media cards. Portrait and landscape video now size into a centered viewer stage using the actual source dimensions.
- If the server cannot create a WhatsApp voice-call link, a private chat falls back to the native phone dialer. This is a regular carrier call requiring user confirmation, not a WhatsApp call.
- Increased the Android version to 0.3.0-beta (versionCode 3).

## Implemented in the matching Android/server staging sources

- Group/peer labels now refresh from the current WhatsApp Web chat list into server conversation metadata and the Android local chat table. LID-only values use cached phone mappings or a friendly fallback label instead of showing the opaque `@lid` identifier.
- Image, video, and GIF messages lazily download a thumbnail for an inline preview. Tapping the bubble still opens the media viewer.
- Document messages show the original filename and size, and open the downloaded document through an Android app registered for that type. Existing PDF/Office/text/ZIP upload and retrieval paths are retained.
- The Android client tries original MP4 before the legacy 3GP rendition. The old 3GP conversion preserves aspect ratio with padding instead of stretching portrait video to a square-looking frame.
- The video picker offers normal video sending or sending as a document for original resolution/aspect ratio. The latter avoids WhatsApp's ordinary video transcoding, at the cost of appearing to the recipient as a file attachment.
- Server uploads spool to disk with a 512 MiB cap instead of keeping the whole multipart file in a Multer memory buffer.
- A Call action creates a WhatsApp voice/video call link, sends it to the current chat, and offers to open the link.

## Version 0.6.0-beta direct voice calling

- Added an in-call screen with mute, speaker/earpiece toggle, duration timer, and hang-up controls.
- Added device microphone capture and speaker playback using mono 16 kHz PCM, bridged over an authenticated WebSocket to the existing WhatsApp Web client.
- Added outbound one-to-one voice calls and foreground incoming-call answer/decline prompts. The call link remains as a fallback.
- The server pins an open experimental `whatsapp-web.js` fork commit because native call placement/audio methods are not merged in upstream. One WhatsApp session can handle one call at a time. Background incoming-call notifications are not included.
- The reverse proxy must pass WebSocket upgrades and the Authorization header to `/api/v2/calls/stream`.

## Validation

Android compilation and signing, server syntax, HTTP contracts, and a mocked duplex PCM call-bridge test passed. The bridge test verifies both byte directions and hang-up lifecycle using a fake WhatsApp call. Real ringing/answer/audio quality still requires two live WhatsApp accounts and the target Q20; this environment has neither, so the call feature is not claimed as live-account verified. See `TEST_REPORT.md`.





