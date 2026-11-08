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
- `js/app.js` — screens: splash/boot, menu (deck picker when there is more than one public deck), gallery, login, game, game over.
- The card component renders whatever stats the deck defines; the look matches the Android card.

## Card data (offline fallback only)
`data/cards.js` holds placeholder wrestlers; `npm run gen-cards` regenerates them. Real cards live in the backend database.
Login is still stored locally until the backend has players (phase 3 in `app/backend/PLAN.md`).
