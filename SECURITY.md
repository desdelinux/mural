# Security

Mural’s current source version connects directly from the device to OpenAI using a key entered by the device’s owner. The key stays in Keychain and is not included in learning exports. A shared service key must never be embedded in a distributed app.

Android's experimental ChatGPT sign-in stores its session encrypted with a separate Android Keystore key, outside backups and learning exports. While signing in, the app listens on 127.0.0.1 only and accepts only the redirect carrying that login's state; the listener closes when sign-in succeeds, is cancelled or times out.

## Report a vulnerability

Use **Security → Report a vulnerability** in the GitHub repository when private vulnerability reporting is enabled. Include the affected version, reproduction steps and likely impact. Use synthetic conversations and redacted diagnostics; do not send a live credential or another person’s data.

Do not disclose an unpatched vulnerability or credential in a public issue. If the private reporting action is unavailable, email [hi@hackmamba.io](mailto:hi@hackmamba.io).

## Scope

Useful reports include credential exposure, unauthorized data access, unsafe backup import, content leaking between learners or languages, and ways to bypass future server-enforced usage limits. Incorrect model answers and pronunciation problems belong in ordinary bug reports unless they reveal a security issue.

Private vulnerability reporting, secret scanning and push protection are enabled. Dependency alerts and security updates are enabled too. CodeQL and the full-history secret-scan workflow check source changes. See the [September 12 review](release/security-audit-2026-09-12.md) for scope and limitations. A response-time commitment has not yet been established.
