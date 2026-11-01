# Mochi phone

A small Android app (Android 12 or newer) for Mochi, December's assistant on the xarvis-core server.

- **Mochi calls you.** When Mochi hits a major decision, it rings your phone like a normal incoming
  call, with a full-screen ring on the lock screen and Answer/Decline. Answering opens a live voice call
  in the chat where Mochi was working, so it already knows what it's asking about. After you hang up,
  the chat continues with what you decided.
- **You call Mochi.** "Call Mochi" starts a live voice call any time. It's the same voice as the desktop
  app (Gemini Live through OpenHuman), and you can talk over Mochi to interrupt it. "New conversation"
  starts fresh; otherwise calls continue the last one.

Everything goes over Tailscale. The app talks only to your own server:

| What | Address |
|---|---|
| live voice + chat (OpenHuman core) | `https://<server>:8446` (`/ws/live-voice`, `/rpc`) |
| incoming calls (`mochi-ring`) | `https://<server>:8448/events` |

Both use Mochi's token (the OpenHuman core token). The app keeps it in its private storage on the phone.
The server side (`mochi-ring`, `mochi-call`) and when Mochi decides to call are in the private
`tiny-setup` repo, README section "Mochi phone".

## Install

1. Install Tailscale on the phone and sign in to the same tailnet. Keep it connected.
2. Download `mochi-phone-vN.apk` from this repo's releases (tags `phone-v*`). Open it and allow
   installs from your browser.
3. Open Mochi. Enter the server (`xarvis-core.<tailnet>.ts.net`) and Mochi's token, then Save.
4. Tap each button the app shows: notifications, full-screen calls and background running. Without
   them, Android may silence or delay the ring.
5. The main screen should say "Connected. Mochi can call you."

## Build

CI builds it (`.github/workflows/mochi-phone.yml`). Each push builds a debug APK as a check, and a tag
`phone-vN` publishes a signed release. Locally: `gradle -p mochi-phone assembleDebug` (JDK 17 + Android SDK).

Release signing needs two repository secrets, made from the key on the server (`/etc/mochi-phone`,
root-only, included in the encrypted daily backup):

```sh
sudo base64 -w0 /etc/mochi-phone/signing.p12   # -> secret MOCHI_KEYSTORE_B64
sudo cat /etc/mochi-phone/signing-password      # -> secret MOCHI_KEYSTORE_PASSWORD
```

Android installs an update only if it's signed with the same key. If the key is ever lost, uninstall
the app and install the new build.

## Code

Plain Android APIs plus OkHttp; no other libraries.

| File | What |
|---|---|
| `Mochi.kt` | settings, server calls, notification channels |
| `RingService.kt` | keeps the connection to `mochi-ring` open, rings, handles missed calls |
| `Call.kt` | one live voice call: WebSocket + mic (16 kHz PCM16, echo cancellation) + speaker |
| `CallService.kt` | holds the microphone while a call runs, so the call keeps going with the screen off |
| `CallActivity.kt` | incoming-call and in-call screen |
| `MainActivity.kt` | setup, Call Mochi, permission shortcuts |
| `Receivers.kt` | Decline button, start after reboot |
