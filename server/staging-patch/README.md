# Nokia4Ever backend 0.18 staging patch

Use this patch with the existing Q20 Messenger 0.17 APK. It targets the known 0.13 server source. It does not update the phone.

Extract this archive on the host. First create an isolated source copy that is not mounted by the live Docker container. Then run from the extracted `server/staging-patch` directory:

```sh
node apply-staging.js --staging /absolute/path/to/isolated-staging-copy --check
node apply-staging.js --staging /absolute/path/to/isolated-staging-copy
```

Replace the example path with the actual staging source directory. Do not point this at `/app`, a live bind mount, or the live Nokia4Ever source. The tool validates all base/payload hashes and JavaScript syntax before changing anything. A source copy whose files differ from the known base is rejected for review. The tool creates source backups inside that staging directory and is idempotent.

This changes four application source files. It does not touch the legacy `messageRouter.js`, `server.js`, dependencies, browser configuration, authentication, databases or media, and does not restart a process. Start/restart only your staging service with its existing configuration. No new npm dependency is required.

Validate `/api/v2/health` reports 0.18.0. Check `/api/v2/contacts` returns promptly; `contact_cache` describes browser/stored data, staleness, pending refresh and backoff. `/api/v2/calls/diagnostics` now has a 4.5-second response deadline and returns `browser_error`/`browser_pending` when the existing browser job is stuck. Neither `connected:true` nor cached contacts establishes working calls.

A staging browser that is already stuck may still need its own process restarted using its existing linked test session. The patch intentionally does not restart the live browser or reset authentication. Real incoming/outgoing ringing and audio must pass on staging before deployment is considered ready.

The full source and tests are available in the GitHub repository. See `server/TEST_REPORT_018.md` there (included here as `TEST_REPORT_018.md`). This is not a Docker image or an automatic live-container updater.
