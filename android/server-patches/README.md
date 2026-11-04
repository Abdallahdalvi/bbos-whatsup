# Backend 0.18 staging follow-up

The focused contacts/browser-timeout patch is in server/staging-patch in the repository and Q20Messenger-server-0.18-staging-patch.zip in the delivered output. Use its staging-only applier on an isolated source copy. Keep the 0.17 APK. The Docker updater template now requires an explicitly labelled staging container and refuses ordinary/live containers. The older updater workflow below is historical; use the focused staging applier for this follow-up.

Current CasaOS 0.13 deployment: transfer the matching Q20Messenger-update-server.sh and run sudo bash Q20Messenger-update-server.sh nokia4ever. It embeds the complete matching payload; install the 0.13 APK too. The source patch workflow below is for a staging source copy.

# Q20 Messenger Nokia4Ever v2 server integration

## Which ZIP to use

For an existing Nokia4Ever server, use **`Q20Messenger-server-v2-patch.zip`**. Extract it and run its `patch_whatsappclient.js` against a copy of your Nokia4Ever source used for staging. Do **not** upload `Q20Messenger-nokia4ever-v2-staging-source.zip` as a replacement server application; that ZIP is a reviewed source snapshot, not a drop-in Docker image or complete deployment bundle.

This is the reviewed backend source snapshot for the matching Q20 Android client. Apply it only to a staging copy of Nokia4Ever. It does not access the live Docker container, WhatsApp authentication, or session files. The original `messageRouter.js` is preserved as-is so existing NovelProfessor routes remain available.

## Apply to staging

Extract this archive and run the patcher against a local staging copy:

```sh
node patch_whatsappclient.js /path/to/nokia4ever-staging-copy
cd /path/to/nokia4ever-staging-copy
npm install
node --check server.js
node --check WhatsappClient.js
node --check messageRouterV2.js
node --check q20SchemaV2.js
node --check q20HistorySupport.js
node --check q20CallSupport.js
```

The script installs files from `payload/` and creates `.before-q20-v2-patch` backups. Review the diff and test staging before deployment. It never copies WhatsApp auth/session folders or databases.

## Important behavior

- This personalized patch includes `q20-private-config.json` containing the user's configured API token. Keep it beside `server.js` in the staged server. HTTP contacts and voice WebSockets now load that same file automatically when `Q20_API_TOKEN` is absent. An explicit environment token takes precedence. Keep the personalized patch ZIP and APK private. Public Android source and backend snapshot ZIPs omit the private config.
- Apply all patch files and restart/rebuild the staged Node process/container. Copying a ZIP to the server without extracting/applying it does not update the running process. A Docker build must COPY the private config into the app directory, or supply the matching environment token. No QR/authentication files need to be replaced.
- Direct voice calling uses the pinned open-source call-enabled `whatsapp-web.js` fork commit in `package.json`. This call implementation is experimental and not merged upstream. The server must be restarted after applying the ZIP; both devices need active internet, and port 80 (or your configured HTTPS reverse proxy) must permit WebSocket upgrades at `/api/v2/calls/stream` with the Authorization header forwarded. The app streams 16 kHz mono PCM to and from the linked WhatsApp session. A single linked account can only handle one active call at a time. The app also runs its incoming-call listener with an ongoing notification; keep it enabled for background ringing.
- `POST /api/v2/calls/link` remains available for voice/video call link invitations. It is a fallback, not the direct in-app call transport.
- The video picker offers normal WhatsApp video sending or sending as a file to preserve the original resolution/aspect ratio. Recipients see the original-quality option as a file attachment.
- Uploads are spooled to disk (512 MiB cap) instead of held in a Multer memory buffer. Provide enough free server disk space.
- The importer accesses WhatsApp Web's private `WAWebChatLoadMessages` module. It resumes and deduplicates in bounded batches, but that internal module can change with WhatsApp Web.
- Media thumbnails are fetched lazily. The app opens a cached Q20-friendly 480-height copy first, offers Original and Save, and preserves source aspect ratio. A legacy 3GP rendition may still exist for older Nokia clients.

See `API_DOCUMENTATION.md`, `HISTORY_IMPORT_REPORT.md`, and `TEST_REPORT.md` for endpoint details, boundaries, and local check results. Local stub tests do not prove real-account call link behavior, media quality, or delivery.


