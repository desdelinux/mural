#!/usr/bin/env python3
"""Probe realtime voice through a ChatGPT subscription session.

Uses the ChatGPT sign-in session that Codex CLI stores on this machine. The
file is only read; tokens are never refreshed here, because refreshing would
rotate the refresh token Codex CLI depends on.

Subcommands:
  usage    Print the plan's usage windows.
  create   Create calls with a static SDP offer (no media) for several shapes.
  call     Run a full WebRTC call. Requires `pip install aiortc numpy`.
  clip     Cut the first spoken seconds of a recorded reply into a mic WAV.
"""
import argparse
import asyncio
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
import wave

BACKEND = "https://chatgpt.com/backend-api/"
CALLS = BACKEND + "codex/realtime/calls"
NATIVE_CALLS = CALLS + "?intent=quicksilver&architecture=avas"
ORIGINATOR = "codex_cli_rs"
USER_AGENT = "codex_cli_rs/0.154.0 (mural-research)"
SAMPLE_RATE = 48_000
FRAME = 960
VOICED = 300

TUTOR = ("You are a friendly Spanish conversation tutor. Speak only Spanish, short sentences. "
         "When the learner asks about current news or facts you must verify, call the delegate tool "
         "instead of answering from memory, then use its result.")
STORY = ("You are a Spanish storyteller for language learners. Tell a long, simple story in Spanish "
         "about a trip to Sevilla. Keep talking; never ask the listener anything.")
DELEGATE_TOOL = {"type": "function", "name": "delegate",
                 "description": "Ask the app to look up current facts or news.",
                 "parameters": {"type": "object", "properties": {"request": {"type": "string"}},
                                "required": ["request"], "additionalProperties": False}}
FAKE_LOOKUP = "Test headline: it is raining in Madrid and the metro is free today."
HISTORY = (("user", "input_text", "Me llamo Ana y estoy aprendiendo español."),
           ("assistant", "output_text", "¡Encantada, Ana! Hablemos en español."))


def default_auth_file():
    return os.path.join(os.environ.get("CODEX_HOME", os.path.expanduser("~/.codex")), "auth.json")


def default_out(name):
    base = os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache"))
    return os.path.join(base, "mural", "chatgpt-voice", name)


def jwt_claims(token):
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def read_session(path):
    with open(path) as handle:
        tokens = json.load(handle).get("tokens") or {}
    token = tokens.get("access_token")
    if not token:
        sys.exit(f"No ChatGPT sign-in found in {path}. Run `codex login` first.")
    claims = jwt_claims(token)
    if claims.get("exp", 0) - time.time() < 120:
        sys.exit("The stored access token is expired. Use Codex CLI once so it refreshes, then retry.")
    auth = claims.get("https://api.openai.com/auth", {})
    return token, tokens.get("account_id") or auth.get("chatgpt_account_id"), auth.get("chatgpt_plan_type")


def request_headers(args, alpha=None):
    token, account, _ = read_session(args.auth_file)
    session_id = str(uuid.uuid4())
    headers = {"Authorization": f"Bearer {token}", "chatgpt-account-id": account, "originator": ORIGINATOR,
               "User-Agent": USER_AGENT, "Content-Type": "application/json",
               "x-session-id": session_id, "session-id": session_id}
    if alpha:
        headers["openai-alpha"] = alpha
    return headers


def http(url, headers, body=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method="GET" if body is None else "POST", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode("utf-8", "replace")


def usage_windows(args):
    headers = request_headers(args)
    headers.pop("Content-Type")
    status, _, text = http(BACKEND + "wham/usage", headers)
    if status != 200:
        return {"status": status}
    limits = json.loads(text).get("rate_limit") or {}
    return {name: (limits.get(key) or {}).get("used_percent")
            for name, key in (("five_hour", "primary_window"), ("weekly", "secondary_window"))}


def static_offer():
    fingerprint = ":".join(["AB"] * 32)

    def media(kind, mid, extra):
        proto = "UDP/TLS/RTP/SAVPF 111" if kind == "audio" else "UDP/DTLS/SCTP webrtc-datachannel"
        return (f"m={kind} 9 {proto}\r\nc=IN IP4 0.0.0.0\r\na=ice-ufrag:abcd\r\n"
                f"a=ice-pwd:abcdefghijklmnopqrstuvwx\r\na=fingerprint:sha-256 {fingerprint}\r\n"
                f"a=setup:actpass\r\na=mid:{mid}\r\n{extra}")
    return ("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0 1\r\n"
            + media("audio", "0", "a=sendrecv\r\na=rtcp-mux\r\na=rtpmap:111 opus/48000/2\r\n")
            + media("application", "1", "a=sctp-port:5000\r\n"))


def command_usage(args):
    _, _, plan = read_session(args.auth_file)
    print(json.dumps({"plan": plan, "used_percent": usage_windows(args)}, indent=1))


def command_create(args):
    """Each accepted shape opens a call that never connects; the plan still counts it."""
    sdp = static_offer()
    realtime = lambda model: {"type": "realtime", "model": model, "instructions": TUTOR,
                              "audio": {"output": {"voice": "marin"}}}
    native = lambda **extra: {"instructions": TUTOR, "audio": {"output": {"voice": "marin"}},
                              "delegation": {"type": "client"}, **extra}
    shapes = {
        "realtime-gpt-realtime": (CALLS, None, realtime("gpt-realtime")),
        "realtime-gpt-live-1": (CALLS, None, realtime("gpt-live-1")),
        "native-gpt-live-1-codex": (NATIVE_CALLS, "quicksilver=v2", native(model="gpt-live-1-codex")),
        "native-gpt-live-1": (NATIVE_CALLS, "quicksilver=v2", native(model="gpt-live-1")),
    }
    for name in args.only or shapes:
        url, alpha, session = shapes[name]
        status, headers, text = http(url, request_headers(args, alpha), {"sdp": sdp, "session": session})
        answer = "SDP answer" if text.startswith("v=0") else text.replace("\n", " ")[:200]
        print(json.dumps({"shape": name, "status": status,
                          "call_id_returned": bool(headers.get("location") or headers.get("Location")),
                          "body": answer}))


def command_clip(args):
    import numpy as np
    with wave.open(args.source) as source:
        if source.getframerate() != SAMPLE_RATE or source.getnchannels() != 1:
            sys.exit("The source must be 48 kHz mono.")
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype=np.int16)
    start = int(np.argmax(np.abs(samples) > VOICED))
    clip = samples[max(0, start - SAMPLE_RATE // 5): start + int(args.seconds * SAMPLE_RATE)]
    padded = np.concatenate([np.zeros(SAMPLE_RATE * 3 // 2, np.int16), clip, np.zeros(SAMPLE_RATE * 4, np.int16)])
    with wave.open(args.target, "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(SAMPLE_RATE)
        target.writeframes(padded.tobytes())
    print(f"{args.target}: {len(padded) / SAMPLE_RATE:.1f} s")


def command_call(args):
    try:
        import av
        import numpy as np
        from aiortc import MediaStreamTrack, RTCPeerConnection, RTCSessionDescription
    except ImportError:
        sys.exit("The call subcommand needs `pip install aiortc numpy`.")
    from fractions import Fraction

    class Microphone(MediaStreamTrack):
        """Sends silence until play() queues 48 kHz mono samples.

        aiortc stops sending audio after replaceTrack(), so one track feeds both.
        """
        kind = "audio"

        def __init__(self):
            super().__init__()
            self.timestamp, self.started, self.queue = 0, None, np.zeros(0, np.int16)

        def play(self, path):
            with wave.open(path) as source:
                if source.getframerate() != SAMPLE_RATE or source.getnchannels() != 1:
                    raise ValueError("Microphone WAV must be 48 kHz mono.")
                self.queue = np.frombuffer(source.readframes(source.getnframes()), dtype=np.int16)

        async def recv(self):
            self.started = self.started or time.monotonic()
            delay = self.started + self.timestamp / SAMPLE_RATE - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)
            chunk, self.queue = self.queue[:FRAME], self.queue[FRAME:]
            chunk = np.pad(chunk, (0, FRAME - len(chunk)))
            frame = av.AudioFrame.from_ndarray(chunk.reshape(1, -1), format="s16", layout="mono")
            frame.sample_rate, frame.pts, frame.time_base = SAMPLE_RATE, self.timestamp, Fraction(1, SAMPLE_RATE)
            self.timestamp += FRAME
            return frame

    async def run():
        out = os.path.abspath(args.out or default_out(args.scenario))
        os.makedirs(out, exist_ok=True)
        log = open(os.path.join(out, "events.jsonl"), "w")
        started = time.monotonic()
        clock = lambda: round(time.monotonic() - started, 2)
        summary = {"scenario": args.scenario, "model": args.model, "used_percent_before": usage_windows(args)}
        received, transcript, heard, counts = [], [], [], {}
        peer = RTCPeerConnection()
        microphone = Microphone()
        peer.addTrack(microphone)
        channel = peer.createDataChannel("oai-events")

        def send(event):
            channel.send(json.dumps(event))
            log.write(json.dumps({"t": clock(), "out": event["type"]}) + "\n")

        def say(role, kind, text):
            send({"type": "conversation.item.create",
                  "item": {"type": "message", "role": role, "content": [{"type": kind, "text": text}]}})

        @peer.on("track")
        def on_track(track):
            async def pump():
                while True:
                    try:
                        frame = await track.recv()
                    except Exception:
                        return
                    samples = frame.to_ndarray().reshape(-1)
                    if frame.layout.name == "stereo":
                        samples = samples.reshape(-1, 2).mean(axis=1)
                    samples = samples.astype(np.int16)
                    if "first_audio_s" not in summary and np.abs(samples).max() > VOICED:
                        summary["first_audio_s"] = clock()
                    received.append(samples)
            asyncio.ensure_future(pump())

        @channel.on("open")
        def on_open():
            send({"type": "session.update", "session": {
                "type": "realtime", "instructions": STORY if args.scenario == "story" else TUTOR,
                "tools": [DELEGATE_TOOL], "tool_choice": "auto",
                "audio": {"input": {"transcription": {"model": args.transcription_model},
                                    "turn_detection": {"type": "server_vad"}},
                          "output": {"voice": args.voice}}}})
            if args.scenario == "delegate":
                say("user", "input_text", "¿Cuáles son las noticias más importantes de hoy en España?")
                send({"type": "response.create"})
            elif args.scenario == "story":
                send({"type": "response.create"})
            else:
                for role, kind, text in HISTORY:
                    say(role, kind, text)

        @channel.on("message")
        def on_message(raw):
            event = json.loads(raw)
            kind = event.get("type", "?")
            counts[kind] = counts.get(kind, 0) + 1
            entry = {"t": clock(), "in": kind}
            if kind == "error":
                entry["error"] = event.get("error")
            elif kind == "session.updated":
                session = event.get("session") or {}
                entry["tools"] = len(session.get("tools") or [])
                if entry["tools"] and args.scenario == "listen" and "mic_started_s" not in summary:
                    summary["mic_started_s"] = clock()
                    microphone.play(args.mic_wav)
            elif kind == "response.output_audio_transcript.delta":
                transcript.append(event.get("delta", ""))
            elif kind == "conversation.item.input_audio_transcription.completed":
                heard.append(event.get("transcript"))
            elif kind == "response.function_call_arguments.done":
                entry["function"] = {"name": event.get("name"), "arguments": event.get("arguments")}
                summary["delegated"] = entry["function"]
                send({"type": "conversation.item.create", "item": {
                    "type": "function_call_output", "call_id": event.get("call_id"),
                    "output": json.dumps({"result": FAKE_LOOKUP})}})
                send({"type": "response.create"})
            elif kind == "response.done":
                entry["usage"] = (event.get("response") or {}).get("usage")
                if args.scenario == "story" and clock() < args.seconds - 15:
                    say("user", "input_text", "Continúa la historia, sin preguntas.")
                    send({"type": "response.create"})
            log.write(json.dumps(entry, ensure_ascii=False) + "\n")

        await peer.setLocalDescription(await peer.createOffer())
        while peer.iceGatheringState != "complete":
            await asyncio.sleep(0.05)
        body = {"sdp": peer.localDescription.sdp, "session": {
            "type": "realtime", "model": args.model, "instructions": TUTOR, "audio": {"output": {"voice": args.voice}}}}
        status, _, text = http(CALLS, request_headers(args), body)
        summary["create_status"] = status
        if status != 201:
            summary["create_error"] = text[:400]
            print(json.dumps(summary, indent=1))
            return
        await peer.setRemoteDescription(RTCSessionDescription(sdp=text, type="answer"))
        await asyncio.sleep(args.seconds)
        summary["connection"] = peer.connectionState
        await peer.close()
        log.close()

        audio = np.concatenate(received) if received else np.zeros(0, np.int16)
        with wave.open(os.path.join(out, "reply.wav"), "wb") as target:
            target.setnchannels(1)
            target.setsampwidth(2)
            target.setframerate(SAMPLE_RATE)
            target.writeframes(audio.tobytes())
        tokens = {"input": 0, "output": 0}
        for line in open(os.path.join(out, "events.jsonl")):
            usage = json.loads(line).get("usage") or {}
            tokens["input"] += usage.get("input_tokens", 0)
            tokens["output"] += usage.get("output_tokens", 0)
        await asyncio.sleep(3)
        summary.update({"voiced_seconds": round(int((np.abs(audio) > VOICED).sum()) / SAMPLE_RATE, 1),
                        "assistant_said": "".join(transcript)[:600], "learner_said": heard, "tokens": tokens,
                        "used_percent_after": usage_windows(args), "event_counts": counts, "output_dir": out})
        print(json.dumps(summary, indent=1, ensure_ascii=False))

    if args.scenario == "listen" and not args.mic_wav:
        sys.exit("The listen scenario needs --mic-wav (48 kHz mono); make one with the clip subcommand.")
    asyncio.run(run())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--auth-file", default=default_auth_file())
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("usage")
    create = commands.add_parser("create")
    create.add_argument("--only", nargs="*")
    call = commands.add_parser("call")
    call.add_argument("--scenario", choices=["delegate", "story", "listen"], default="delegate")
    call.add_argument("--seconds", type=int, default=25)
    call.add_argument("--model", default="gpt-realtime")
    call.add_argument("--voice", default="marin")
    call.add_argument("--transcription-model", default="gpt-4o-mini-transcribe")
    call.add_argument("--mic-wav")
    call.add_argument("--out")
    clip = commands.add_parser("clip")
    clip.add_argument("source")
    clip.add_argument("target")
    clip.add_argument("--seconds", type=float, default=7)
    args = parser.parse_args()
    {"usage": command_usage, "create": command_create, "call": command_call, "clip": command_clip}[args.command](args)


if __name__ == "__main__":
    main()
