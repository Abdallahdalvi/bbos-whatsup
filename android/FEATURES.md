# Feature matrix — Q20 Messenger rebuild

| Feature | Implementation status | Runtime evidence |
|---|---|---|
| WhatsApp-inspired home tabs, chat list, generated initials avatars | Implemented in native XML and Java | Build verification pending; no device UI test |
| Contacts/groups tabs and search | Implemented against v2 contacts and local chats | Requires staged v2 server |
| Local SQLite message storage | Implemented with server-ID unique key and indexes | No live history import run |
| Bootstrap and incremental sync | Implemented in resumable 200-message pages | Requires staged v2 server |
| Existing server DB history | v2 bootstrap reads all stored message rows | Actual rows/counts unavailable in source ZIP |
| WhatsApp Web available history | Optional existing-client import route and progress status | Not run; cannot imply whole cloud archive |
| Text send/receive, group JIDs, cached LID aliases | v2 contract and additive backend patch | Not tested against a live account |
| Image/video/audio/document upload | Client picker and v2 upload contract include common types | Backend patch must be staged; media sends not run |
| Media download and document open | v2 route can fetch stored or exposed WhatsApp media | Not live-tested; compatible opener required for documents |
| Clickable links | Android Linkify in message text | Not device-tested |
| Cache controls and connection diagnostics | Settings actions implemented | Not device-tested |
| Notifications/background sync | Not implemented | No push API; no background reliability claim |
| Inline image thumbnails, playback progress, receipts | Not implemented | Server metadata/runtime support incomplete |
| Stickers/GIFs, vCards, locations, group administration | Not implemented | Requires additional backend support |
| Voice/video calls | Excluded | Explicitly out of scope |
