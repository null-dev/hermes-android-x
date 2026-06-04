# hermes-android-x

Give your Hermes agent hands on a real Android phone.

Re-write of https://github.com/raulvidis/hermes-android, with the goal of improving stability and performance.

## How it works
The phone runs a token-authenticated HTTP server (`0.0.0.0:8765`); the agent connects
directly. All actions serialize through one command queue for reliability.

## Setup
1. Build/install the app (`mise run build-apk`; `adb install ...`).
2. Enable the accessibility service; optionally grant SMS/Call/Contacts/Location,
   screen recording, and Notification access.
3. Tap **Start bridge**; copy the URL + token shown.
4. Set `ANDROID_BRIDGE_URL` and `ANDROID_BRIDGE_TOKEN` in the agent env.
5. `android_ping` to confirm.

## Develop
- `mise run test-py` — Python unit tests
- `mise run test-android` — Kotlin unit tests
- `mise run test-device` — instrumented tests on a connected phone
- `mise run build-release-apk` — assemble the release APK
- `mise run build-debug-apk` — assemble the debug APK