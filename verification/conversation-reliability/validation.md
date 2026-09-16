# Combined validation — September 16, 2026

The combined source includes #41/#48/#49/#57/#61, the main-branch #50/#53/#54 fixes, and #62 with #32 excluded.

| Check | Result |
| --- | --- |
| Server suite with isolated PostgreSQL | 364 passed; none skipped |
| TypeScript check | Passed |
| Swift core | 98 passed |
| Android unit | 338 passed; none skipped |
| Android lint and app/test builds | Passed |
| Android native UI suite | 71 passed; none skipped |
| Repository Python checks | 54 passed |
| Cross-platform parity and Android content export | Passed |
| Live iPhone audio | Two calls passed: captions, speaker output, closure and audio release |
| iPhone UI | 26 passed in the final full rerun |
| 16 KB Android native UI and WebRTC | 71 passed on API 35, page size 16,384 |
| Android release lint and bundle validation | Passed, including native layout, manifest, assets and credential scan |
| Signed APK | v2/v3 signature and 16 KB ZIP alignment passed; same certificate as v7 |
| In-place v7 → v8 update | German practice, English meanings and completed onboarding preserved |
| Final large-text Android checks | Four Spanish tests passed at 2× text |

Server tests use an isolated UTF-8 PostgreSQL database and fake provider transports. They cover sanitized diagnostics, reference isolation, logging failures, provider rejection, closure and settlement alongside existing account, purchase and recovery tests. No production deployment was performed.

Core tests cover quiet-session boundaries, one check-in, bounded speech/typing/helper grace, temporary delivery adaptation, stale/duplicate/assisted evidence, Help during pending assessment, caption joining and learning evidence, complete translations, and safe error categories.

The full Android UI run includes retained typed drafts and retry without duplicate transcript rows, authentication recovery, long multilingual captions, oversized hosted-caption guidance with no dispatch/retry, recovery on the next reply, countdown/report geometry, navigation and existing account flows. Offline HTTP fixtures are used.

The iPhone's two live Spanish calls used the existing key and an in-memory learning store. The owner explicitly approved microphone audio being sent to OpenAI. Both returned captions and measurable output through the built-in speaker, retained the speaker preference, closed and released the audio session. The report contains no transcript, recorded audio or key. Bluetooth, cellular handoff and pronunciation quality were not measured. The simulator transport test drives real WebRTC delegate callbacks through disconnect, reconnect, close, teardown, stale callbacks and subsequent peers without making network calls.

## Reproduction

- `swift test --package-path apps/ios`
- `python3 scripts/check_cross_platform.py`
- `python3 scripts/export_android_content.py --check`
- `python3 -m unittest discover -s scripts/tests`
- In `services/api`: `npm run check` and `TEST_DATABASE_URL=<isolated UTF-8 test database> npm test`
- In `apps/android`, with JDK 17 and the Android SDK: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:connectedUiTestAndroidTest`
- iPhone simulator: `xcodebuild ... ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO test`

The initial combined iPhone run exposed a lost static-text accessibility trait in the revised countdown container. It was corrected before the final rerun; the failed run is not reported as passing. Android's first build exposed a duplicate style import, also fixed before the passing build and UI run.

## Preview 8 artifact

The exact signed APK is `Mural-Android-direct-v8.apk`, 59,713,683 bytes, SHA-256 `c644419d09e2541f427ddc26649bda368181e1faf9935ce947ce4b273b4e2bdc`. Its certificate SHA-256 is `16cc94553e43e0d9dfbc0ac72f162eb163e7d26d330bcae455e212c0a790c022`, matching v7. Package `chat.mural.android`, version code 8, version name 0.1, minimum SDK 26, target SDK 36; release manifest has no debuggable or test-only flag. Public configuration retains `https://api.mural.chat`, the existing Google client ID, Stripe channel and live purchase environment.

The APK was installed directly over published v7 on an isolated 16 KB emulator without uninstalling or clearing data. This installation contained selected language settings, not an existing learner's history or sign-in. Native repository/account tests cover persistence separately. The full native suite on the 16 KB runtime also creates a real WebRTC audio/data offer without microphone or internet.

Purchases, Play-signed login, Bluetooth and cellular handoff were not exercised in this release run. Their code/configuration is unchanged. The release remains a direct-download preview; no Play submission, paid transaction or server deployment is part of this task.

A release-version assertion initially still selected v7; it was updated to validate current v8 against historical v7/v4, and all 53 repository checks passed again. Integration with newly merged main changed no product-code files after the successful native runs.

Final review follow-ups corrected a documentation typo, made timeout parity compare fractional values, and recorded a close request after provider attachment fails. Concurrent close requests emit one diagnostic; missing or closed sessions emit none, and unresolved usage holds remain reserved. The full server suite passed again (364 tests), as did TypeScript and all 54 Python checks. These follow-ups changed no native app source or release binary.
