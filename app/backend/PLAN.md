# Server plan

Goal: replace the Firebase pieces of the Android app (card data, usernames) and give the web
frontend real data, accounts and a leaderboard — then make games server-authoritative.

## Stack
Java 21 · Spring Boot 3.5 · `JdbcTemplate` (plain SQL, easy to learn; JPA is an option later) ·
Flyway migrations · Neon Postgres · JUnit 5. Host: Render / Railway / Fly.io (any container host).

## Data model (V1__init.sql — schema + built-in Wrestling deck)
Decks define their own stats; games are played with a deck.

| table | purpose | notes |
|---|---|---|
| `decks` | a set of cards that can be played | `name`, `description`, `visibility` (`public` listed · `unlisted` link-only · `private` owner-only), `owner_id` (null = built-in) |
| `deck_stats` | what each card in the deck holds | `key`, `label`, `unit`, `value_type`, **`comparison_strategy`** (`higher_wins`/`lower_wins`), `display_order` |
| `cards` | cards of a deck | `stats` jsonb `{"rank":12,"fight":44,…}` (keys must match the deck's `deck_stats`, validated in code); `meta` jsonb for display-only extras (e.g. `brand`) |
| `players` | accounts | username unique; add `password_hash` in a later migration when auth lands |
| `games` | one play-through with a deck | `join_code` (shareable), `deck_id`, `host_id`, `mode` (`cpu`/`pvp`), `difficulty` (cpu only), `status` (`waiting`/`in_progress`/`finished`/`abandoned`) |
| `game_players` | who is in a game | seat 1/2 + `result`; CPU opponent is implied by `mode`; feeds the leaderboard |

**Decisions**
- **Stats + comparison live on the deck**, not on a "game type": a deck fully describes itself, and two wrestling decks may differ.
  Adding a Cricket deck = insert a deck + its `deck_stats` + cards. No schema change, no Java class unless a stat needs a custom rule.
- **Join link:** only `join_code` (6–8 chars, `A–Z0–9`) is stored. The shareable URL is built in code as `<frontend base>/join/<join_code>`,
  because the domain differs per environment.
- **Visibility:** `public` decks appear in listings; `unlisted` work for anyone with the link; `private` only for the owner.
  Built-in decks have no owner.
- **Open question — editing a deck mid-game:** changing stats/cards of a deck with running games would break them. Simplest rule:
  block edits (or copy-on-write into a new deck) while any game on it is `waiting`/`in_progress`.
- Live round state (hands, pot, whose turn) is not in the DB yet — keep it in memory first; add `games.state jsonb` if you want resume.

## API (in build order)
1. `GET /api/health` — `{"status":"ok"}` (+ db ping)
2. `GET /api/decks` — public decks (+ the caller's own); `GET /api/decks/{id}` — deck with its stat definitions;
   `GET /api/decks/{id}/cards` — `{ id, name, img, stats: {…}, meta: {…} }`
3. `POST /api/players` `{username}` — create (409 if taken, max 8 chars, lowercased like the Android app)
4. `POST /api/games` `{deckId, mode, difficulty?}` → `{id, joinCode, joinUrl}`
5. `GET /api/games/{joinCode}` — status + deck; `POST /api/games/{joinCode}/join` — second player takes seat 2 (pvp)
6. `POST /api/games/{joinCode}/play {stat}` — server resolves the round (server-authoritative; CPU moves handled server-side)
7. `GET /api/leaderboard` — wins, games, win rate per player

## Phases
- [ ] **0 Connect** — follow README, see "Database connected" *(connector is provided)*
- [ ] **1 Decks + cards** — seed cards into the Wrestling deck (export Firebase `Data`, or the placeholder 54), repositories, `GET /api/decks…`, CORS for the frontend origin
- [ ] **2 Frontend uses it** — frontend fetches `/api/cards` (keep `data/cards.js` as offline fallback)
- [ ] **3 Players + games** — usernames, create/record games (`games`, `game_players`), leaderboard screen
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
