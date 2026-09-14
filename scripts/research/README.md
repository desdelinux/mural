# Research scripts

These scripts support investigations that are not part of the shipped apps. They are not run in CI.

`chatgpt_subscription_probe.py` checks voice calls and text requests through a ChatGPT subscription, using the sign-in session Codex CLI stores on this machine. Findings are in `docs/research/chatgpt-subscription.md`.

The backend it calls is undocumented, and OpenAI has not published terms for third-party use of the ChatGPT sign-in. Use it only with your own account. Every connected call counts against that account's Codex usage limits.
