# Conversation and reliability review

This combines #41, #48, #49, #57, #61 and #62. The earlier fixes for #50, #53 and #54 are already on main. Issue #32 and PR #44 are deferred at the owner's request; the new persona/accent instructions have been removed. Existing regional guidance and the OpenAI `marin` voice remain.

## UI and conversation changes

| Change | Before | Now |
| --- | --- | --- |
| Quiet voice sessions | Could remain open for two minutes | One gentle check-in after 15 seconds; closure after 30 seconds, with bounded grace for speech, typing and pending answers |
| Countdown | Normal status until closure | “Ending in 5s” above “Reply to continue,” centered beneath the orb in the existing secondary color. Stable spacing, tabular digits and scalable text; Android preserves room for Report |
| Speaking pace | Limited initial delivery guidance | Short, unhurried opening replies; temporary delivery adapts to validated independent spoken answers. Help simplifies immediately. Saved proficiency is unaffected |
| Conversation direction | Could leave the next step to the learner | One relevant follow-up or concrete choice; learner topic changes remain welcome |
| Failed typed reply | Draft could disappear | Sheet stays open, draft and error remain, and a successful retry creates one transcript row. Send stays reachable with keyboard and large text |
| End reason | Could be replaced by a usage notice | Inactivity and time-limit explanations remain visible |
| Errors | Several unrelated failures shared advice | Distinct credit, rate, service, connection and hosted-helper advice, safe support references and existing account/key recovery actions |
| Captions and vocabulary | Fragment boundaries could lose spaces or valid evidence | Shared Unicode-aware joining preserves punctuation, Mandarin boundaries and cross-fragment vocabulary evidence |
| Meanings | Only the last 2,200 characters were sent | Complete caption is sent. A failed full translation clears an earlier partial meaning. Hosted captions above 24,576 UTF-8 bytes show a clear limit without a futile retry; the next reply resumes meanings |
| iPhone network loss | A disconnected call could look active | Eight seconds to recover; sustained loss uses the existing restart error. Closing cancels recovery callbacks |

No new navigation, panel or color scheme is introduced. The countdown and error copy change; typed-reply sheets gain scrolling and retained errors through #57. All screenshots use synthetic learning content.

## Tests and release

See [validation.md](validation.md) for exact results and remaining limits. Two live iPhone calls confirmed captions, speaker output, successful closure and audio-session release. Deterministic tests cover countdown timing, typing/speech grace, network callback lifecycle, retries and learning evidence. These tests do not rate pronunciation or guarantee model pacing on every reply.

Android preview 8 retains the direct-download configuration and package. Its [release notes](../../release/android/notes-v8.md) describe user outcomes. The Play submission and server deployment are separate.

## Countdown review captures

- [Android, normal text](android-countdown.png)
- [Android, Spanish at 2× text](android-countdown-large-es.png)
- [iPhone, normal text](ios-countdown.png)
- [iPhone, largest accessibility text](ios-countdown-largest-text.png)

The large-text layouts wrap and scroll rather than shrink the user's chosen text size. Tests confirm that the countdown stays readable, typing clears it, and the Android report action remains separate.
