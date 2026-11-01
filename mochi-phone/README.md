# Mochi phone

A small Android app (Android 12 or newer) for Mochi, December's assistant on the xarvis-core server.

- **Mochi calls you.** When Mochi hits a major decision, it rings your phone like a normal incoming
  call, with a full-screen ring on the lock screen and Answer/Decline. Answering opens a live voice call
  in the chat where Mochi was working, so it already knows what it's asking about. After you hang up,
  the chat continues with what you decided.
- **Chat and history.** "Chat with Mochi" is a typed chat (answered by Claude, like the desktop app) in the
  current conversation; "Conversations" lists past ones to chat or call in; "New conversation" starts one.
- **Approvals.** Mochi's browser requests (Allow / Deny / answer with a message) and calls you missed or
  declined that still need a decision (Call back / answer by typing / Dismiss) wait here until dealt with.
- **Notifications.** Reminders you set ("remind me at 5…") and Mochi's updates ("done: PR #29 opened") arrive
  as normal notifications; tapping one opens the chat.
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

## Battery

In the background the app keeps one quiet connection to `mochi-ring` (a keep-alive every 50 s), so calls and
notifications arrive. Everything else (chat refresh, the RAM label, the conversation list) runs only while a
Mochi screen is open, and the microphone only during a call. If the connection drops it reconnects within
about 2 minutes; a call made meanwhile still rings (it waits 30 minutes), and notices wait a day.

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
sudo base64 /etc/mochi-phone/signing.p12       # -> secret MOCHI_KEYSTORE_B64 (all 61 lines)
sudo cat /etc/mochi-phone/signing-password      # -> secret MOCHI_KEYSTORE_PASSWORD
```

Android installs an update only if it's signed with the same key. If the key is ever lost, uninstall
the app and install the new build.

## Code

Plain Android APIs plus OkHttp; no other libraries.

| File | What |
|---|---|
| `Mochi.kt` | settings, server calls, notification channels |
| `RingService.kt` | keeps the connection to `mochi-ring` open, rings, handles missed calls, shows notices (a browser request opens its approval page) |
| `Call.kt` | one live voice call: WebSocket + mic (16 kHz PCM16, echo cancellation) + speaker |
| `CallService.kt` | holds the microphone while a call runs, so the call keeps going with the screen off |
| `CallActivity.kt` | incoming-call and in-call screen |
| `ChatActivity.kt` | typed chat in the current conversation (re-reads it every 3 s) |
| `ConversationsActivity.kt` | past conversations: chat or call in one |
| `ApprovalsActivity.kt` | browser requests and missed/declined calls waiting for December |
| `MainActivity.kt` | setup, Call / Chat / Conversations, permission shortcuts, server RAM in the header (asked only while open) |
| `Receivers.kt` | Decline button, start after reboot |
