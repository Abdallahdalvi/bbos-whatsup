# Q20 Messenger v2 API contract

Base path: `/api/v2`. All v2 routes require `Authorization: Bearer <Q20_API_TOKEN>`. The token is read from a nonempty environment value or otherwise from `q20-private-config.json` beside the server, and compared with a timing-safe comparison. The personalized patch includes that private file. If neither source is configured, the v2 API returns HTTP 503. Legacy routes remain mounted with their original paths and request/response shapes.

JSON errors use `{ "error": "..." }`; successful send/upload responses are documented below. The server must run the additive schema migration before listening. JIDs are preserved as provided; private IDs may end in `@c.us` or `@lid`, and group IDs remain `@g.us`.

## Routes

Version 0.9 adds authenticated `GET /calls/diagnostics` (browser/web versions, required calling module presence, injected helper presence, and last failure) and `POST /calls/report` with `{message}` for device audio/transport errors. `GET /calls/status` includes `last_error:{at,stage,message}` even when inactive. These routes never place a call. Call errors remain visible on the phone instead of being immediately dismissed by socket closure.

Version 0.8 health adds `server_version` and `capabilities.direct_voice_calls`, `call_status`, `media_download`, `q20_video_copy`. These are installed implementation capabilities; they do not prove a successful live call. A missing direct-call library returns HTTP 503 with code `CALL_DEPENDENCY_MISSING`.

Media variants now include `original` (stored original), `thumbnail` (cached JPEG) and `q20-480` (separate cached H.264 baseline/AAC MP4, up to 480px per side, 24fps, no upscale). Conversion runs in one bounded concurrent FFmpeg worker, deduplicates same-message jobs and preserves originals. The handset fully downloads files before playback and can save them to shared Downloads/Q20Messenger. FFmpeg with libx264 and AAC is required for derivatives.

| Method and path | Contract |
|---|---|
| `GET /health` | `{ok, connected}` for the existing single WhatsApp client. Read-only; does not launch a client or request a QR. |
| `GET /account` | `{connected:true,account:{pushname,user,platform}}`; HTTP 503 if the existing session is not ready. |
| `GET /chats` | `{chats:[{chat_jid,name,is_group,is_newsletter,phone_jid,last_message,last_type,timestamp_ms,status}],total}` derived from all rows in `messages`, deduplicated by chat JID. Refreshes names from the already-linked WhatsApp Web chat and contact lists at most once every five minutes. Number-only names are resolved from contact/profile metadata or shown as a friendly fallback; newsletter IDs are labeled as channels. Add `?type=groups` to return only groups. |
| `GET /contacts?search=&limit=200&page=0` | `{contacts,count,page,has_more}`. Limit is capped at 200. Contacts are read from the existing WhatsApp Web client. |
| `GET /sync/bootstrap?after_id=0&limit=200&snapshot_max_id=...` | `{account,snapshot_max_id,total_messages,total_chats,messages,next_after_id,has_more,history_limit}`. Limit is clamped to 1–200. The first response captures a max server row ID; pass it on later pages. |
| `GET /sync/updates?after_id=<cursor>&limit=200` | `{messages,next_after_id,has_more}` for server SQLite rows after the cursor. |
| `GET /chats/:chatJid/messages?before_id=<id>&limit=100` | `{messages,has_more,next_before_id}`. Returns one chat page, consulting a cached `@lid` mapping when available. It does not call Puppeteer on each history request. |
| `POST /messages` | JSON `{chat_jid,message}`. Returns `{accepted:true,result:{message:"message sent successfully"}}` after the WhatsApp send call resolves with a message. Send errors return a non-2xx JSON error. |
| `POST /calls/link` | JSON `{chat_jid,type}` where type is `voice` or `video`. Creates a WhatsApp call link and sends it to that chat as a message; returns `{link,call_type,invitation_sent,message}`. If WhatsApp creates the link but sending the message fails, returns the link with `invitation_sent:false` and a `warning`. Link-generation errors return HTTP 502 with an `error` and `stage` so they can be diagnosed. This is a call-link invitation, not native in-app call signaling or answering. |
| `POST /calls/start` | JSON `{chat_jid}`; starts a direct one-to-one voice call through the pinned call-enabled WhatsApp Web fork; HTTP 202 `{call_id,peer,state,audio}`. HTTP 409 if another call is active. |
| `GET /calls/status` | `{active,call_id,peer,video,incoming}` for the single active call. Q20 Messenger polls this while in the foreground to offer incoming call answer/decline. |
| `POST /calls/end` | JSON `{call_id}`; hangs up the matching active call and returns `{ended:true}`. |
| `POST /calls/reject` | JSON `{call_id}`; rejects a pending incoming call and returns `{rejected:true}`. |
| `WS /api/v2/calls/stream?call_id=...` | Upgrade requires the same `Authorization: Bearer <Q20_API_TOKEN>` header. JSON frames carry call state; binary frames carry raw signed 16-bit little-endian, mono, 16 kHz PCM in both directions. Reverse proxies must allow WebSocket upgrades and preserve the Authorization header. |
| `POST /media` | Multipart `chat_jid`, optional `send_as_document=true`, and file field `media`; uploads up to 512 MiB to a temporary disk file. Supports JPEG/PNG/WebP/GIF, MP4, MP3, 3GPP/AMR, PDF/TXT/CSV/DOC/DOCX/XLS/XLSX/PPT/PPTX/ZIP. Video sent as a document keeps the source file and avoids WhatsApp's normal video compression; recipients see a file attachment. |
| `GET /media/:messageId?variant=mp4` | Returns a binary attachment with `Content-Disposition`. Downloads an existing media file or retrieves it through the already-linked WhatsApp Web client when available. MP4 is retained for the Q20 client; legacy 3GP copies are fitted to their frame without stretching and remain available for older clients. |
| `POST /history/import` | Starts/resumes an additional import from history exposed by the current `whatsapp-web.js` session. Optional JSON `{per_chat_limit:n}`; omit or use 0 to import all session-available history. Returns `{started,status}` with HTTP 202 when started. Reuses the existing client. |
| `GET /history/import/status` | `{running,completed,phase,conversations_total,conversations_done,messages_seen,messages_imported,errors,started_at,completed_at}`. Counts describe the WhatsApp Web import run, not SQLite bootstrap totals. |

Every returned message includes server `_id`, WhatsApp message ID when known, `chat_jid`, sender/receiver/participant, `from_me`, body/type/status/time, media metadata, reply ID and link metadata.

The Q20 app now fetches chat summaries during sync to refresh names, uses cached LID-to-phone mappings for labels, and downloads original MP4 before any legacy 3GP rendition. Image/video/GIF bubbles load inline thumbnails on demand; document bubbles show the original filename and open the cached file with a compatible Android app. Voice calls use the device microphone and speaker through the authenticated PCM WebSocket bridge. Incoming call prompts require the app to be in the foreground; background ringing notifications are not implemented.

## Migration and boundaries

Version 0.7 adds `rich_json` on server and device messages, containing whitelisted captions, poll definitions and CTA actions. `POST /polls/:messageId/vote` accepts JSON `{options:["option name"]}`, validates the live poll options and submits the vote through the linked WhatsApp message. Empty options withdraw a vote. Unsupported business reply/list/Flow actions are identified, not simulated as plain text.

`q20SchemaV2.js` adds columns to `messages` only when missing, plus indexes and `q20_jid_mappings`, `q20_conversations`, and `sync_state` tables. It does not drop existing rows. Existing rows get a derived chat JID when read. The optional history importer saves per-chat progress, inserts each bounded batch before loading more, resumes unfinished chats after interruption, and deduplicates by WhatsApp message ID.

The importer uses the `WAWebChatLoadMessages.loadEarlierMsgs` hook used by `whatsapp-web.js` 1.34.7's own `Chat.fetchMessages` implementation. That hook is an internal WhatsApp Web module and can change independently of the npm package; validate it against the linked server session before deployment. The importer keeps each returned page bounded in Node memory, while WhatsApp Web itself retains loaded message models in its browser page.

Bootstrap covers rows currently in the server `messages` table. The source ZIP does not include the live DB. The pre-0.13 read-only check returned 167 stored messages and 42 conversations. WhatsApp Web history import only returns messages the linked client exposes; it does not promise the whole cloud archive. The owner deployed 0.12; the 0.13 payload remains staged for deployment.

## 0.11 outgoing call confirmation

POST /api/v2/calls/start returns HTTP 202 with either the existing ringing/call_id response or {state:"confirmation_required",request_id:"opaque-id",confirmation:{title,message,button}}. The client displays the native business-call notice. An explicit Continue sends POST /api/v2/calls/confirm with {request_id:"opaque-id"}; this resumes that pending call and returns the normal ringing response. Unknown/expired confirmation returns 409. POST /api/v2/calls/end with an empty call_id cancels a pending start. All routes require the configured bearer token. The server does not automatically acknowledge the business notice. Normal setup is limited to 25 seconds; a displayed confirmation expires after 90 seconds.
## 0.12 client diagnostics

Authenticated POST /api/v2/client/diagnostics accepts bounded event, stack, version, sdk, model and screen fields from the Q20 application. GET on the same route returns the last ten reports. The server stores a small q20-client-diagnostics.json under its data directory. Reports are not included in distributed source archives. Existing calling routes and PCM transport are unchanged.
## 0.13 history and chronological chat pages

The Android client checks /history/import/status and starts POST /history/import automatically when idle. It retries unfinished work after five minutes and refreshes a completed available-history pass after six hours. The status is restored from SQLite after restart. running=true imports loaded/earlier pages in bounded batches; phase=waiting_for_phone reports a transfer request still pending. phase=available_history_imported means the exposed linked-session history was imported, not that the primary phone's complete archive was recovered. phone_requests, waiting_chats, last_error and scope clarify progress. Successful phone-history requests use the pinned client's syncHistory method and are throttled per chat in SQLite for six hours. Each browser operation is timeout-bounded and can be retried without duplicate WhatsApp IDs.

GET /chats includes q20_conversations entries even before their first message has arrived. GET /chats/:chatJid/messages accepts optional before_timestamp_ms together with before_id, orders by timestamp and ID, and returns next_before_timestamp_ms/next_before_id. Equal-timestamp messages are not skipped. Omitting before_timestamp_ms preserves the legacy ID-based pagination contract.
