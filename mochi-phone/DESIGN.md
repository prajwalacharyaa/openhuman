# Mochi phone: design

The look and the rules every screen follows. Chosen with December on 2026-11-08 (direction "Soft Bento", tuned
for fewer taps); principles from the vetted design skill `ceorkm/mobile-app-ui-design` (8-point grid, ≤4 text
sizes / 2 weights, 60/30/10 colour, thumb zone, every state designed). Code: `app/src/main/kotlin/app/mochi/phone/ui/`.

## Goal
Everything December needs is one tap away, and the most common things take none:
- Home answers decisions on their card (Approve / Not now), starts a chat from its "Ask Mochi anything" bar, and
  has Call Mochi as the big bottom button.
- Mode (Quick / Normal / Deep) sits right above the message box; Stop replaces Send while Mochi replies.
- Outside the app: long-press shortcuts (Call Mochi, New chat), a Quick Settings tile, and Share to Mochi.

## Colour (`Theme.kt`)
Follows the phone's light/dark setting.

| Role | Light | Dark |
|---|---|---|
| Ground | `#FFFFFF` | `#111214` |
| Ink (text, primary buttons, user bubbles) | `#111111` | `#F1F2F4` |
| Muted text | `#5F6368` | `#A6ABB3` |
| Field / quiet surfaces | `#F2F3F5` | `#1E2024` |
| Mochi's bubbles | `#F7F7F8` | `#1A1C20` |
| Accent (Call Mochi, Stop) | `#E8613C` coral | same |

Tiles are colour-coded with a soft background and a matching ink: amber = waiting for you, blue = chats,
green = notices/reminders and finished steps, violet = memory. Strong colour is kept for meaning only.

## Type and space
- System sans; sizes 13 / 15 / 17 / 24 only, weights regular and semibold (`Type`).
- Spacing from the 8 dp grid: 4, 8, 12, 16, 24, 32 (`Space`).
- Corners: 24 dp tiles and cards, 22 dp bubbles, full-round buttons and chips.
- Touch targets ≥ 44 dp; primary actions 48–64 dp, at the bottom (thumb zone).

## Mochi (the mascot)
The portfolio site's Mochi (`portfolio-site/client/src/pages/bento/mascot.jsx`): rice-cake body `#FFFAF1`, ink
`#2A2522`, marigold leaf `#FFC53D`, cheeks `#FFB4A2`. Same shapes in `Mascot.kt` (Compose) and the launcher icon.
It breathes and blinks; a tap makes it hop. Moods: Idle, Listening (rosier cheeks), Thinking (looks up, bobs),
Speaking (mouth moves). Chat header: thinks while working, talks while the reply streams. Call screen: listens,
thinks and talks with the call.

## States
Every list has loading, empty (Mochi + one line of guidance) and error text; failed sends keep the attachments
and say why. No emoji in the interface; icons are line icons (`Icons2`, 2 px stroke).

## Don'ts
No gradients or glows, no extra font families, no more than one accent, no labels bigger than their values,
nothing important hidden behind a menu when it can sit on Home.
