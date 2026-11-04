# History import report — 0.13

Read-only pre-update check: the owner's 0.12 server reported 167 messages across 42 conversations and phase=idle for its history importer. Synchronizing this SQLite database alone could not show older history that had never been imported.

0.13 automatically starts the linked-session importer from the Android synchronization flow and shows its progress. It scans loaded messages and earlier pages, requests additional phone history through the pinned client's syncHistory method where eligible, and retries pending/failed chats. Restart resumes from persisted completed-chat state; requests are throttled per chat across restarts. Unique WhatsApp IDs prevent repeated inserts. Phone requests never send a chat message or place a call.

Each server batch is committed before loading more. Browser calls have bounded waits, one pending page loader per chat, and repeated-page detection. Node receives only batches; the WhatsApp browser can retain loaded models. Known native conversations appear before message backfill is complete.

Android bootstrap/updates synchronize every server row using a durable server-ID cursor. Open conversations use chronological timestamp/ID pages, including messages sharing a timestamp, and fetch older pages from the server after cached history runs out. Imported older rows do not displace the newest chat summary. Existing auth/session and legacy routes remain intact.

Staging fixtures passed a simulated interruption, deduplicated resume, persisted waiting state across restart, a delayed phone-history page, request throttling, and high-ID old-message pagination. These are synthetic checks, not a claim of actual account backfill.

Actual post-0.13 imported message/conversation totals: not yet measured. The owner must deploy the matching updater and open the app; no separate manual import command is needed. Keep the main phone connected while requested transfers finish. WhatsApp Web may expose less history than the primary phone; messages it cannot provide cannot be reconstructed by the client.

References: [WhatsApp linked-device history](https://faq.whatsapp.com/653480766448040/), [whatsapp-web.js history API](https://docs.wwebjs.dev/Client.html#syncHistory).
