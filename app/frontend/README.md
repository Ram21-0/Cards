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
