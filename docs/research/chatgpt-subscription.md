# Mural through a ChatGPT subscription

Status: abandoned on 2026-09-14 and kept as an archive. Voice and text helpers both work through a ChatGPT Plus subscription, and this branch has an experimental Android provider built on these findings. It was not merged because voice was unusable on a real phone; see [Outcome](#outcome).

Measured on 2026-09-14 with a Plus account and Codex CLI 0.154.0's sign-in session. This is research for a personal, experimental option. OpenAI has not published terms that allow third-party apps to use the ChatGPT sign-in of Codex CLI. The backend below is undocumented and can change or close without notice.

## Why

Mural's voice conversations use `gpt-live-1` through the OpenAI API, and voice is the largest part of a learner's API bill. A ChatGPT subscription does not include API credit, but Codex CLI can open realtime voice calls with the ChatGPT sign-in. The question was whether Mural can do the same, and what it costs against the plan's limits.

## Sign-in

Codex CLI signs in with OAuth 2.0 authorization code and PKCE against `https://auth.openai.com`, using its public client id and a loopback redirect on port 1455. The access token carries the ChatGPT account id and plan type in the `https://api.openai.com/auth` claim. Calls to the ChatGPT backend send the access token as a bearer token plus `chatgpt-account-id` and `originator` headers.

The probe reads the token Codex CLI already stores and never refreshes it. A refresh rotates the refresh token and would sign Codex CLI out.

## Call creation

`POST https://chatgpt.com/backend-api/codex/realtime/calls` takes JSON, not the multipart body of the public API.

```json
{
  "sdp": "<WebRTC offer>",
  "session": {
    "type": "realtime",
    "model": "gpt-realtime",
    "instructions": "...",
    "audio": { "output": { "voice": "marin" } }
  }
}
```

A `201` returns the SDP answer as the body and the call id in `Location`.

| Request | Result |
| --- | --- |
| `gpt-realtime`, realtime session | `201` with SDP answer |
| `gpt-live-1`, realtime session | `400` model not supported in realtime mode |
| `session.delegation` on a realtime session | `400` unknown parameter |
| Raw `application/sdp` body | `400` unsupported content type |
| GPT-Live path: `?intent=quicksilver&architecture=avas`, `openai-alpha: quicksilver=v2`, `gpt-live-1-codex` | `403` voice session access denied |
| GPT-Live path with any other model or none | `400` model not allowed for this Codex realtime session |

The GPT-Live path is the one Codex CLI uses by default and speaks the same `session.*` events as Mural. The backend recognizes it but denies it for this Plus account. It may be limited to other plans or still rolling out.

## End-to-end calls

Each call used a real WebRTC peer, the `oai-events` data channel and a `session.update` after the channel opened.

| Capability Mural needs | Result |
| --- | --- |
| Spoken reply in Spanish | Works. First audio about 4 s after starting, including ICE gathering and the create request. |
| Instructions changed during the call | Works through `session.update`. |
| Learner audio transcribed | Works with server VAD and `gpt-4o-mini-transcribe`. Three spoken sentences became three accurate transcripts. |
| Delegation to the app | Works as a function tool. The model called it for a news question, received the app's result and read it aloud. |
| Prior conversation history | `conversation.item.create` items are accepted before audio starts. |
| Five minutes connected | Stayed connected and kept speaking. |

Server events are the Realtime API set, for example `session.created`, `response.output_audio_transcript.delta`, `conversation.item.input_audio_transcription.completed`, `input_audio_buffer.speech_started`, `response.function_call_arguments.done` and `response.done` with token usage. Mural's Android transport expects GPT-Live events such as `session.started`, `session.output_transcript.delta` and `session.delegation.created`.

## Plan usage

The plan exposes a five-hour window and a weekly window as whole percentages. Background usage stayed flat for three minutes before the measured calls.

| Call | Length | Assistant speech | Tokens | Five-hour window | Weekly window |
| --- | --- | --- | --- | --- | --- |
| Short reply | 25 s | 2 s | 206 | +2 | 0 |
| Story | 2 min | 72 s | 23,150 | +2 | +1 |
| Delegation | 15 s | 2 s | 360 | +2 | 0 |
| Refused read-aloud | 12 s | 7 s | 325 | +2 | 0 |
| Microphone, no audio sent | 30 s | 0 s | not recorded | +2 | +1 |
| Microphone with history | 35 s | 2 s | 313 | +2 | 0 |
| Story | 5 min | 182 s | 100,740 | +2 | 0 |

Every connected call cost about two points of the five-hour window, whatever its length or token count. Seven calls moved the weekly window by two points. Create requests rejected with `400` or `403` did not count. Calls longer than five minutes were not measured.

At this rate a Plus plan allows roughly 50 voice conversations in five hours. The weekly window is shared with all Codex use on the same account.

## Text helpers

`POST https://chatgpt.com/backend-api/codex/responses` takes the Responses API body with the same headers as call creation plus `Accept: text/event-stream`. `GET codex/models?client_version=…` lists `gpt-5.6-luna`, the model Mural's helpers already use with an API key.

| Request with `gpt-5.6-luna` | Result |
| --- | --- |
| Message input, `store: false`, `stream: true` | Works, about 1.5 s |
| Plain string content | Works |
| `reasoning.effort: low` | Works |
| Strict `json_schema` text format | Works and returns schema-valid JSON |
| `web_search` tool | Works, with `url_citation` annotations |
| `max_output_tokens` | `400` unsupported parameter |
| `stream: false` | `400` stream must be true |

The `response.completed` event arrives with an empty `output`. The items come only in `response.output_item.done` events, so a client rebuilds the final response from them before reading text, refusals, citations and web search calls.

Ten text requests, including two web searches, did not move the five-hour window.

## What it means for Mural

A learner can use Mural on Android with only a ChatGPT subscription, without a Mural server. The experimental provider needs:

1. A ChatGPT sign-in on the device, with the tokens stored like the API key.
2. A voice provider that creates the call with the JSON body above.
3. An adapter between Realtime events and the GPT-Live events the transport and view model already handle, with delegation expressed as a function tool.
4. A text client that streams helper requests and rebuilds the final response from output items.

On the API 35 emulator, the app's own voice client and adapter opened a call, spoke the greeting with a subtitle and closed with usage.

If the GPT-Live path opens for the account later, the adapter is unnecessary and the existing transport can be reused.

## Outcome

On an OPPO CPH2599 with Android 16, the provider signed in and started voice conversations, but the model heard itself and kept answering itself. The phone reports no hardware echo cancellation and routes the call to the loudspeaker, so some of the model's audio reaches the microphone. The Realtime server's voice detection treats that echo as the learner speaking and starts a new reply.

The same phone and audio stack hold balanced five-minute conversations with `gpt-live-1` through an API key.

| Conversation on that phone | Length | Learner fragments | Assistant fragments |
| --- | --- | --- | --- |
| API key, `gpt-live-1` | 4 min 43 s | 170 | 249 |
| API key, `gpt-live-1` | 4 min 47 s | 139 | 268 |
| Subscription, `gpt-realtime` | 22 s | 1 | 106 |
| Subscription, `gpt-realtime` | 19 s | 1 | 118 |

A fix would mute the microphone while the model's audio plays, using `output_audio_buffer.started` and `stopped`, and clear the input buffer afterwards. That removes interruptions, which `gpt-live-1` supports. Together with the plan's per-call cost and the unapproved sign-in, this was judged not worth pursuing.

The work is worth revisiting if the GPT-Live path opens to ChatGPT plans, because the existing transport would then need no adapter.

## Open questions

- Whether `gpt-live-1-codex` becomes available to Plus accounts.
- Whether calls longer than five minutes keep the flat cost, and the maximum call length.
- Whether a token from Mural's own sign-in behaves like Codex CLI's token. Both use the same client id.
- How `gpt-realtime` teaches compared with `gpt-live-1` under Mural's prompts.

## Reproduce

The `call` subcommand needs `aiortc` and `numpy`. Output goes to `~/.cache/mural/chatgpt-voice/`. Every connected call counts against the plan.

```sh
python3 scripts/research/chatgpt_subscription_probe.py usage
python3 scripts/research/chatgpt_subscription_probe.py create
python3 scripts/research/chatgpt_subscription_probe.py call --scenario delegate
python3 scripts/research/chatgpt_subscription_probe.py call --scenario story --seconds 120
python3 scripts/research/chatgpt_subscription_probe.py clip ~/.cache/mural/chatgpt-voice/story/reply.wav /tmp/mic.wav
python3 scripts/research/chatgpt_subscription_probe.py call --scenario listen --mic-wav /tmp/mic.wav --seconds 35
python3 scripts/research/chatgpt_subscription_probe.py models
python3 scripts/research/chatgpt_subscription_probe.py text --model gpt-5.6-luna
```
