# Isolated Q20 Messenger TEST client for backend 0.18

This uses the 0.17 Android code with Q20_STAGING_BUILD=true. It has application ID `com.aghani.q20messenger.staging`, a TEST launcher/header/listener label, and a default server address of http://192.168.0.107:8035. The normal build keeps its original application ID, labels and port 8034. The two installed applications have separate private settings, databases and cache directories; neither client automatically migrates or deletes the other's history. FileProvider authorities are derived from applicationId.

The personalized TEST APK contains the existing configured API token. It is delivered locally, not uploaded to the public repository. Enter the phone number linked to the staging session when prompted. For a call test, disable background listening in the original client and close it, then open TEST. This avoids competing live/staging call popups and reduces device memory pressure. Restore the original listener after testing.

Check one incoming and one outgoing call for ringing, answer, two-way audio and hang-up. Then minimize TEST with Back from its home screen and test an incoming call with the display asleep. No actual call has been initiated by the assistant. These remain device acceptance tests, not a completed calling claim.

Observed staging state: owner diagnostics report 0.18.0, connected=true, Chrome 156, calling enabled, no browser-support blocker and no initialization error. Initial contacts returned four stored names during refresh. A subsequent authenticated check returned 8,171 contacts from a fresh browser cache (stale=false, refreshing=false, error=null). This establishes successful contact snapshot completion on staging; it does not establish working call audio or sleep-time delivery.

Build validation: 44 tests passed, 1 native screenshot test skipped, zero failures; lint and TEST APK assembly passed. Signature, API 18 levels, TEST application ID/label, launch activity and separate FileProvider authority were inspected. Default-build metadata was regenerated with the staging flag disabled and retains the original application ID.

