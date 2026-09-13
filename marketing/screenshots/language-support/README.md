# Language support screenshots

Three unedited captures of Mural's native Talk screen on an iPhone 16 Pro simulator running iOS 26.4.1, taken on 13 September 2026 from the app at `7ea5a8168d35894323ce0717ec8b2caee0e26191`.

| File | Language | Visible behavior |
| --- | --- | --- |
| `german.png` | German | German caption and optional English meaning |
| `brazilian-portuguese.png` | Brazilian Portuguese | Portuguese caption, accents and optional English meaning |
| `mandarin-pinyin.png` | Mandarin | Simplified Chinese caption, expanded pinyin help and English meaning |

The captures use the Debug preview's sample conversation data, with the conversation ended and microphone off. They demonstrate the interface; separate live checks on the owner's iPhone are recorded in [validation.md](../../../verification/validation.md).

To reproduce, install a Debug simulator build and launch it with `--preview --ended-conversation --preview-language=<de|pt|zh> -UIPreferredContentSizeCategoryName UICTContentSizeCategoryL`. Capture within 15 seconds, before the ended conversation resets. These images were taken after three seconds with `xcrun simctl io <device-id> screenshot <absolute-output-path>` and a 9:41 status bar override.
