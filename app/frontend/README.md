# Cards — frontend (web)

Plain HTML/CSS/JS, no build step. Loads decks and cards from the Java backend (`app/backend`);
if the backend is unreachable it falls back to the bundled cards in `data/cards.js`.

```bash
cd app/frontend
npm start        # http://localhost:8081  (any static server works)
npm test         # engine / API-mapping tests (Node 18+)
```
Start the backend first for live data (`app/backend`, default http://localhost:8080). CORS for `http://localhost:8081`
is already allowed in the backend's `local` profile.

## Configuration
`config.js` — `apiBase` is the backend URL. Edit it per environment (or generate the file at deploy time).

## How data flows
- `js/api.js` — `GET /api/decks`, `GET /api/decks/{id}` (stat definitions), `GET /api/decks/{id}/cards`, mapped into one deck model:
  `{ stats: [{key, label, higherWins}], cards: [{ id, name, img, brand, stats: { key: { value, display } } }] }`.
  `display` is the server's `displayValue` (e.g. `6'1"`, `240lbs`), so the frontend never formats values itself.
- `js/engine.js` — pure game rules, driven by the deck's stat definitions (works for any deck, not just wrestling).
- `js/offline.js` — builds the same deck model from `data/cards.js` when the backend is down (shows a banner with Retry).
- `js/app.js` — screens: splash/boot, menu, **Gallery** (the list of public decks) → deck (cards + **Create game** panel) → **lobby** (join code), login, game, game over.
- The card component renders whatever stats the deck defines; the look matches the Android card.

## Live lobby and table (multiplayer)
Contract: `app/backend/LIVE.md`. The lobby and the table are the same screen, `#/lobby/<code>`; `js/live.js` holds the pure parts (reducer, phase wording, reconnecting client) with tests in `test/live.test.js`.
- **Lobby (WAITING).** Seated players get updates over a WebSocket (`ws(s)://<backend>/ws/games/<code>`, session cookie), so joins, leaves and CPU changes show up at once; the connection chip says ● Live / Reconnecting… / Offline.
  Visitors without a seat, and anyone whose socket is down, fall back to polling `GET /api/games/<code>` every 4 s.
- **Host controls:** add a CPU (easy / medium / hard), remove a CPU seat (✕), **Start game** (needs 2 seats; seats are renumbered). Everyone seated can **Leave game**.
- **Table (IN_PROGRESS).** A full-screen game screen, designed in `docs/gameplay-design/` (spec + interactive mockup): a thin HUD (leave, round, pool, connection), one players strip (a side rail on wide screens: name, cards left, who calls, who is in a clash, who is out), one **focus zone** that holds only the current decision with its countdown ring, and two drawers: *Event log* and *My pile* (bottom sheets on phones, a right drawer from 700 px, docked as tabs from 1100 px). Reloading rejoins: the server sends the lobby and your view again.
- **Connection handling:** back-off reconnects (0.5 s … 30 s, jittered), a ping every 25 s, and no retry loop when the server refuses for good: signed out → straight to sign-in and back to the table; not seated / game gone → "Offline" with the reason.
- **Playing** (all moves are REST calls, `POST /api/games/{code}/call` and `/choose`; the answer is your new `view`, and an older push never overwrites a newer view). The focus zone is one of these states (`tableKind` in `js/table.js`; copy is in the design spec):
  - **My turn** (`canCall`): your card and one button per stat with its value (`Rank ↓` = lower wins; keys 1–6 on a keyboard). After a tap the chosen stat stays marked while the server answers.
  - **Others calling / choosing:** who we are waiting for and the time left, with your card visible. "No time limit" when the game has none.
  - **Round result:** after every round the server holds the result (`view.phase === "ROUND_RESULT"` with a `deadline`; length = the game option `resultHoldSeconds`, default 6 s, 3 s longer after a clash or an elimination). The screen shows the winner, the stat, every revealed card (best first, winner tagged), the cards taken (*the pool* after a clash), eliminations and big-card chips. **Continue** is client-side only: it collapses the result into a calm "Next round in N" until the server's phase changes ("Show the result again" brings it back). The **last round** has no server hold: the same result stays up for the same time (`holdSeconds`) before the ranking.
  - **Clash race** (`CLASH_AWAIT_CALL`): an amber bar and a big ring count down `clashCallSeconds`; the stat buttons are amber; the first tap wins ("ann called Weight first." if you lost the race). Players outside the clash just watch the race.
  - **CHOOSE** (a player with 2 to `chooseThreshold` cards, **the caller too**): a tray of your cards (`TOP` = what plays if time runs out, `BEST` = best for the called stat). A caller picks the card **first** (the tiles show each card's best stat), then gets the stat buttons for it: this is what the server's `AWAIT_CHOOSE_CALLER` view offers.
  - **Out:** "You're out", your final place, who is acting, the last round's cards, Event log and Leave. **Game over:** the winner, a ranking with your row outlined, the last round next to it on wide screens, "New game with this deck" and "Back to decks".
  - **Drawers:** *Event log* (newest first, grouped by round, a dot on the button for new lines; texts from `eventText`) and *My pile* (the card in play, then your cards in the order they come up from `ownPile`, with a RAW/SD badge). Esc closes a sheet. **Reduce motion** can be switched in the log drawer (it follows the system setting too): no animation, the countdown moves in whole seconds.
  - **Connection:** the HUD chip says Live / Offline; while reconnecting an amber banner shows the attempt number, the screen dims and ignores taps, and the next `view` is the truth.
  - **Clash wins** say how many cards came out of the pool: "ann won the clash and takes the pool: 6 cards".
  - **Big cards.** Winning or losing one of the 10 strongest cards of the deck is announced with a banner (green and a pop for a win, red and a shake for a loss, "You won Atlas Wolfe") and a chip on the result ("Big card: you won Atlas Wolfe"); solo games against the CPU get it on the result banner. *Strength* (`js/strength.js`) = for each stat, the share of the other cards a card beats (ties count half), averaged over the stats;
    a stat called `rank` is left out on purpose, so the number printed as "rank" does not decide it. At most 10 cards count, and never more than half the deck, so "big" still means something in a small deck.
    A clash winner is told about big cards from every level of the clash; a round nobody wins (the pool carries over) gives no notice.
  - Refused moves (not your turn, too late in a clash race…) show the server's message and leave the screen usable. The pure logic (`tableKind`, `resultHead`, `seatChips`, `logGroups`, `timerModel`, `holdSeconds`…) is in `js/table.js` with tests in `test/table.test.js`; the DOM is `js/tableui.js` and the styles `table.css` (everything scoped under `.tbl`; the card keeps its own styles from `style.css`).

## Navigation, the Back button and leaving a game
Screens are hash routes (`js/nav.js`, wired up in `js/app.js`), so the browser Back/Forward buttons and the phone's Back gesture move between screens
instead of leaving the site: `#/` menu · `#/decks` gallery · `#/deck/<id>` · `#/deck/<id>/card/<n>` · `#/lobby/<code>` · `#/play` · `#/signin` · `#/username` · `#/account`.
- **A game in progress is never left silently.** Back (or the Quit button, or editing the URL) shows **"Leave this game?" — Stay / Leave**.
  Stay keeps the exact game (the CPU waits while the dialog is open); Leave goes to the menu. Reload and tab close show the browser's own warning (`beforeunload`).
- **The same guard covers a multiplayer lobby or live table where you hold a seat** ("Leave this lobby?" / "Leave this game?"). A lobby opened from a shared link gets the gallery put underneath it in the history, so Back stays in the app and meets the guard. Just looking at a lobby (no seat) never asks.
- The routing/guard rules are pure functions (`backDecision`, `needsUnloadGuard`, `redirectFor`, `upAction`) with unit tests in `test/nav.test.js`.
- `leaveGame()` in `app.js` is the single place a game ends: a solo game just ends; a waiting lobby calls `POST /api/games/{code}/leave` (the host passes to the next player); a running live game only disconnects (it cannot be left on the server yet, you stay seated and can rejoin with the code).
- Up-buttons (← Decks, ← Menu…) go Back when the previous screen is the parent, otherwise they replace the current entry, so the history does not pile up.
- Sign-in and username screens return to where the user came from (`afterAuth`); `#/play` without a running game (a reload) goes to the menu.
- Shared links `?join=CODE` still work: they are turned into `#/lobby/CODE` on load (other query params such as `?api=` are kept).

## Sign-in (Google)
Creating (and, soon, joining) a multiplayer game needs an account; solo play vs the CPU, the deck gallery and join-link previews do not.
- `config.js` → `googleClientId` (public OAuth web client ID). The backend needs the same value as `GOOGLE_CLIENT_ID`, and the
  frontend origin must be an *authorized JavaScript origin* of that client in Google Cloud.
- Flow: `POST /api/auth/nonce` → Google Identity Services button (`js/app.js`, `signIn`) → `POST /api/auth/google {credential}` →
  session **cookie** (HttpOnly; the page never sees the token). On load the app asks `GET /api/me` who is signed in.
- First sign-in asks for a username (`PUT /api/me`, 1–8 of `a–z 0–9 _`). **Account** screen: change username, sign out, delete account.
- Every API call sends `credentials: 'include'` and `X-Requested-With: cards` (see `js/api.js`); a 401 from the server sends the user back to sign-in.
- **Local development without Google:** when the API is on `localhost`, the sign-in screen shows a "Local development only" form that calls
  `POST /api/dev/login` (the server only enables it in its `local` profile).

## Creating a game (multiplayer lobby)
1. **Gallery** lists every public deck (`GET /api/decks`, with `cardCount`). Tap one to see its cards.
2. In the deck, pick the number of players (2–8, capped by the deck's card count) and tap **Create game**.
   If you are not signed in you are sent to sign-in first.
3. `POST /api/games {deckId, maxPlayers}` (the host is the signed-in player) returns a `joinCode` and `joinUrl`. The **lobby** shows the code, a link, **Copy code / Copy link / Share**
   and the seat list, which refreshes every 4 s.
4. The link is `<frontend>/?join=CODE`; opening it shows that game's lobby (`GET /api/games/{code}`, public) with **Sign in to join** when signed out
   and **Join game** when signed in (`POST /api/games/{code}/join`: lowest free seat; idempotent). The lobby marks your seat ("· you"), shows
   "This game is full" when it is, and handles the server's errors: 401 → sign in again, 400 "Choose a username first" → username screen,
   409 full / already started → refresh the lobby.

Dev tip: open the app with `?api=http://localhost:9090` to point it at a different backend (e.g. a mock).

## Production bundle (one origin)
`mvn -Pbundle-frontend package` in `app/backend` copies this folder into the jar (without `config.js`, `test/`, `scripts/`, `package*.json`, `README.md`).
The backend then serves `/config.js` itself as `window.CARDS_CONFIG = {"apiBase": "", "googleClientId": "…"}`, so the code must keep
reading `CARDS_CONFIG.apiBase` (empty string = same origin) and `CARDS_CONFIG.googleClientId` — don't hard-code either.

## Card data (offline fallback only)
`data/cards.js` holds placeholder wrestlers; `npm run gen-cards` regenerates them. Real cards live in the backend database.
Login is still stored locally until the backend has players (phase 3 in `app/backend/PLAN.md`).
