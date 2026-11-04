# Nokia4Ever API and source inspection notes

This is derived from the supplied `messageRouter.js`, `WhatsappClient.js`, `connect.js`, `server.js`, and package lock. It describes the supplied source, not a hypothetical API. The server source package does not include its data directory, linked-session files, or a live SQLite database.

## Express routes

| Route | Method | Request and response behavior |
|---|---|---|
| `/` | GET | Serves the web `index.html`. |
| `/download/:filename` | GET | Downloads a file under the source directory; used by the website/J2ME client, not this Android app. |
| `/api/mediafile/:filename` | GET | Downloads `media/<filename>`. Accepts a restricted filename character set, strips legacy `;interface=wifi`, returns JSON 400/404/500 errors. |
| `/login` | GET | Serves `login.html`. |
| `/login-status/:phoneNumber` | GET | JSON status from the one in-memory WhatsApp client: `isAuthenticated`, `qr`, `pushname`, `user`, `platform`. Does not start a new client. |
| `/:country/:phoneNumber/start` | GET | Validates a number, calls `startClient`, returns `{valid, formatted}`. In this source `startClient` deliberately does not create another client/session. |
| `/api/login/:user` | GET | Returns `{pushname,user,platform}` for the existing client, or an error object. |
| `/api/chats/:receiver` | GET | JSON `{chats:[...]}`; query accepts `page` and `page_size`, but the supplied implementation ignores both and returns at most 20 rows where `chats.receiver` equals the requested account. It misses outbound-only threads. |
| `/api/contacts/:user` | GET | JSON `{contacts:[{id,name}],count}`; query accepts `search_term`, `page`, `page_size`. Current filter excludes LID-only and business contacts. |
| `/api/messages/:receiver/:sender` | GET | JSON `{messages:[...]}`; query accepts `page`/`page_size`, but the supplied implementation returns 20 newest rows. Direct chats also try `getContactLidAndPhone` mappings; group JIDs ending in `@g.us` are kept intact. Both incoming and outgoing directions are queried. |
| `/api/messages` and `/api/messages/:id` | POST | JSON body `{sender,receiver,message}`. Calls `client.sendMessage`, then inserts history in `chats` and `messages`; response is `{message:"message sent successfully"}`. |
| `/api/allchats/:user` | GET | JSON `{chats:[...]}` from `client.getChats()`. |
| `/api/allmessages/:user/:chatId` | GET | JSON `{messages:[...]}`; current code fetches at most two from WhatsApp Web. |
| `/api/upload` and `/api/upload/:id` | POST | Multipart fields `sender`, `receiver`, and file field `media`; success JSON has `statusCode:"000"` and `statusDesc`. Optional `:id` is unused. Current MIME allowlist is JPEG, PNG, WebP, MP4, MP3. MP3 is converted to Opus/Ogg and sent as a voice note. |
| `/listusers` | GET | Returns an HTML account/platform table for the current single session. |

The source mounts `express-fileupload`, JSON parsing and static files before the router. `multer` is imported by the router but not used on the media upload route.

## Database and media

`connect.js` creates `users`, `chats`, and `messages` tables in `data/mydata.db`. All have integer autoincrement `_id` and timestamp; chats/messages store sender, receiver, message, integer status, sender name, chat type and device type. `users` stores uuid, pushname, user and platform. No foreign keys or query indexes are defined in the supplied schema.

Incoming image/audio/video rows are inserted into `messages`, and their media is written as `<messages._id>.<extension>` under `media/`: images use `.jpg` or `.webp`; incoming audio and voice notes keep `.ogg` and start an asynchronous `.mp3` conversion; incoming videos keep `.mp4` and start asynchronous `.3gp` conversion. The conversion callbacks do not block the message response. This explains why the app tries `.3gp` first for video, and why downloads can temporarily fall back to `.mp4` while conversion is running. The current API does not return MIME type, filename, file size, media URL, or conversion state.

The source handles direct phone JIDs (`@c.us`), linked IDs (`@lid`) during message lookup, and group IDs (`@g.us`) in inbound messages. There is no group creation/admin route. Existing text send history appends `@c.us` to both sender and receiver after only removing `@c.us`; this corrupts group IDs in history. The original media normalizer also contains an over-escaped JID regex. Both are corrected in the optional patch. The original chat-list query only selects rows addressed to the account.

## Dependency lock

Top-level locked versions include Express 4.17.3, express-fileupload 1.5.2, fluent-ffmpeg 2.1.3, sqlite 5.1.1, sqlite3 5.1.6 and whatsapp-web.js 1.34.7. The Android app has no third-party Gradle dependencies.

## Risks and unsupported server features

The supplied API has no app authentication or authorization, and `server.js` configures `express-fileupload` without an explicit size limit. Keep the server on a trusted LAN/VPN. The media upload handler only supports the listed MIME types; PDFs, arbitrary documents, stickers, contact cards and locations have no API. Pagination parameters are accepted by some routes but ignored for chats/messages. The schema has no indexes. The optional patch is provided separately and is never applied to a running server by this build.
