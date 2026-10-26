# AKZSA Writing Mode

## Included features

1. **One-tap tools:** fix grammar, professional, friendly, shorter, longer, and translate to a selected language.
2. **Three reply suggestions:** short/direct, friendly, and professional options. The user selects one before it becomes the preview.
3. **Before/after preview:** the original remains available; the generated result is editable. Users can apply/copy, cancel, retry, or restore the original in the preview.
4. **Saved templates:** templates are stored locally in app preferences and can be loaded, edited, replaced by title, or deleted. Conversation history is not stored by this feature.
5. **Personal writing style:** tone and preferred output language are stored locally and can be reset.
6. **Regenerate/undo:** retry sends the original text again; restore puts the original into the preview. The feature avoids replacing the original while a request is being generated.
7. **Natural voice editing:** Android speech recognition captures an explicit editing instruction; the app sends the instruction and text to the backend and displays a preview. The spoken command is not directly inserted into the message.
8. **Reliability:** the backend validates request size and action, validates translation language and voice-edit instructions, returns structured results, checks exactly three reply suggestions, and uses timeout/provider error messages that preserve the original input.

## Using it

- Open AKZSA and choose **Writing Mode**. Paste or type the text, select a tool, and review the result before using it.
- On Android apps that expose `ACTION_PROCESS_TEXT`, select editable text and choose AKZSA Writing Mode from the selection menu. After review, **Apply to selected text** returns the result to the calling app. Incoming messages can also be shared to AKZSA through apps that support Android `ACTION_SEND`. If the caller marks text read-only or does not support text processing, use **Apply / Copy** and paste manually.
- To generate replies, paste the incoming message, tap **Generate 3 replies**, choose one option, then review it before copying/applying.
- Save a reusable template by entering a title and message, then tapping **Save current text**. Templates and writing-style preferences are stored on the device.
- Voice edit uses the device's available Android speech-recognition service. Availability and whether that service processes audio on-device depend on the device/provider.

## Backend configuration

The Android client calls `POST /v1/writing/generate` at the configured backend base URL. The server requires the configured client token in production and uses `GROQ_API_KEY` for provider requests. Do not put the provider API key in the Android app. Keep it in the server environment.

The backend returns a proposal only; it never inserts text into another app. Non-reply actions return a non-empty `text` and no suggestions. `reply_suggestions` returns an empty `text` and exactly three suggestions. Provider errors, invalid structured responses, and timeouts return a clear error instead of replacing user text.

## Privacy and limits

- Writing text is sent to the configured backend only when a writing action is requested. Do not submit passwords, authentication codes, financial details, or other sensitive text.
- Templates and tone/language preferences are stored locally. This feature does not save conversation history by default.
- Android text-selection integration depends on the calling app supporting `ACTION_PROCESS_TEXT`; apps that do not support it require manual paste/copy.
- A preview and copy path is available as a fallback. Direct replacement across every third-party editor cannot be guaranteed by a standalone activity.
- Backend tests and static/XML checks can be run using `bash scripts/verify.sh`. Full Gradle compilation, APK creation, and physical-device checks must be run on a machine with Android Studio/SDK and working Gradle dependency access.
