# Server plan

Goal: replace the Firebase pieces of the Android app (card data, usernames) and give the web
frontend real data, accounts and a leaderboard — then make games server-authoritative.

## Stack
Java 21 · Spring Boot 3.5 · `JdbcTemplate` (plain SQL, easy to learn; JPA is an option later) ·
Flyway migrations · Neon Postgres · JUnit 5. Host: Render / Railway / Fly.io (any container host).

## Data model (V1__init.sql)
| table | purpose | notes |
|---|---|---|
| `cards` | wrestler cards | `rank` unique, lower wins; `brand` is `raw`/`sd` (card colour) |
| `players` | accounts | username unique; add `password_hash` in V2 when auth lands |
| `games` | finished games | result, rounds, difficulty → feeds the leaderboard |

## API (in build order)
1. `GET /api/health` — returns `{"status":"ok"}` (+ db ping)
2. `GET /api/cards` — all cards, same JSON shape as `window.CARDS` in the frontend
   `{ id, name, brand, img, rank, fight, chest, biceps, feet, inches, weight }`
3. `POST /api/players` `{username}` — create (409 if taken, max 8 chars, lowercased like the Android app)
4. `POST /api/games` `{difficulty, result, rounds}` — record a finished game *(trusting the client — phase 1 only)*
5. `GET /api/leaderboard` — wins, games, win rate per player
6. Phase 3: `POST /api/games/start`, `POST /api/games/{id}/play {stat}` → server deals, resolves, runs the CPU

## Phases
- [ ] **0 Connect** — follow README, see "Database connected" *(connector is provided)*
- [ ] **1 Cards** — seed `cards` (export Firebase `Data`, or insert the placeholder 54), `CardRepository`, `GET /api/cards`, CORS for the frontend origin
- [ ] **2 Frontend uses it** — frontend fetches `/api/cards` (keep `data/cards.js` as offline fallback)
- [ ] **3 Players + results** — usernames, `POST /api/games`, leaderboard screen
- [ ] **4 Auth** — pick one: (a) username + BCrypt password + JWT, (b) anonymous device token. Start with (b), it's ~1 hour
- [ ] **5 Server-authoritative games** — port `engine.js` to Java (`Engine`, same rules, same 12 tests in JUnit); game state in memory or a `game_state` row
- [ ] **6 Optional PvP** — WebSocket lobby, two humans instead of CPU

## Game rules to port (from `app/frontend/js/engine.js`)
- Deck dealt alternately; winner takes both cards + any pot and keeps the turn; tie ("clash") → both cards to the pot, turn unchanged; a side with no cards loses; both empty on a clash = draw.
- Height compares total inches (feet*12+inches). Rank: lower wins. Everything else: higher wins.
- CPU: for its top card, compute for each stat the fraction of the deck that card beats; pick the best (Easy = random, Normal = best + noise, Hard = best).

## Testing
Unit-test the engine (no Spring). Use `@JdbcTest` or Testcontainers-less approach: point tests at a **Neon branch**
(`Branches → Create branch`) so tests never touch real data.

## Environments
Neon branches: `main` (prod) and `dev` (copy-on-write, free). One `DATABASE_URL` per environment.

## Security checklist
No secrets in git (`.env` ignored) · validate/length-limit usernames · CORS limited to the frontend origin ·
rate-limit write endpoints · don't log connection strings · hash passwords with BCrypt if you add them.
