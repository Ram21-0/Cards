# Server plan

Goal: replace the Firebase pieces of the Android app (card data, usernames) and give the web
frontend real data, accounts and a leaderboard — then make games server-authoritative.

## Stack
Java 21 · Spring Boot 3.5 · `JdbcTemplate` (plain SQL, easy to learn; JPA is an option later) ·
Liquibase changelogs (YAML) · Neon Postgres · JUnit 5. Host: Render / Railway / Fly.io (any container host).

## Data model (db/changelog/changes/001-init.yaml — schema + built-in Wrestling deck)
Decks define their own stats; games are played with a deck.

| table | purpose | notes |
|---|---|---|
| `decks` | a set of cards that can be played | `name`, `description`, `visibility` (`public` listed · `unlisted` link-only · `private` owner-only), `owner_id` (null = built-in) |
| `deck_stats` | what each card in the deck holds | `key`, `label`, `unit`, `value_type`, **`comparison_strategy`** (`higher_wins`/`lower_wins`), `display_order` |
| `cards` | cards of a deck | `stats` jsonb `{"rank":12,"fight":44,…}` (keys must match the deck's `deck_stats`, validated in code); `meta` jsonb for display-only extras (e.g. `brand`) |
| `players` | accounts | username unique; add `password_hash` in a later migration when auth lands |
| `games` | one play-through with a deck, 2–8 seats | `join_code` (shareable), `deck_id`, `host_id`, `max_players`, `turn_timeout_seconds` (null = none), `status` (`waiting` lobby → `in_progress` → `finished`/`abandoned`) |
| `game_players` | the seats of a game | `seat`, `kind` (`HUMAN`/`CPU`), `player_id` (humans), `difficulty` (CPUs), `placement` (1 = winner); feeds the leaderboard |

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

## Multiplayer rules (2–8 seats, humans and optional CPUs)
Defaults — change any of them in one place (the rules engine):
- **Seats:** the host creates a game with `max_players`; people join with the `join_code`; the host can add CPU seats (each with its own difficulty) and starts the game once there are **≥ 2 seats**.
- **Deal:** shuffle, deal round-robin. The deck need not divide evenly — some players start with one more card.
- **Round:** every player still holding cards reveals their top card. The **caller** picks a stat from their own card (first caller: random).
  The best value under the stat's `comparison_strategy` wins **all revealed cards plus the pot** and becomes the next caller.
- **Tie for best:** all revealed cards go to the pot and the caller stays. (Alternative later: only the tied players play a tie-break.)
- **Out:** a player with no cards is eliminated and gets the next-worst `placement`. If the caller is eliminated, the next seat clockwise calls.
- **End:** one player holds every card → they get `placement` 1. If the last players run out together, they share the placement (draw).
- **Hidden information:** the server never sends a card to anyone but its holder until it is revealed in a round. Never trust the client for hands.
- **Turn timeout:** if `turn_timeout_seconds` is set and the caller is slow (or has disconnected), the server picks the stat for them (CPU "Hard" logic).
- **CPU seats:** the server picks a stat for them using the existing strength-percentile logic; they reveal and play like anyone else.

## API (in build order)
1. `GET /api/health` — `{"status":"ok"}` (+ db ping)
2. `GET /api/decks` — public decks (+ the caller's own); `GET /api/decks/{id}` — deck with stat definitions;
   `GET /api/decks/{id}/cards` — `{ id, name, img, stats: { height: { value: 73, displayValue: "6'1\"" }, … }, meta: {…} }`
   (stats are in the deck's `display_order`; `displayValue` is formatted server-side from the stat's `display_format`, so clients just render it)
3. `POST /api/players` `{username}` — create (409 if taken, max 8 chars, lowercased like the Android app)
4. **Lobby:** `POST /api/games` `{deckId, maxPlayers, turnTimeoutSeconds?}` → `{id, joinCode, joinUrl}` (host takes seat 1);
   `GET /api/games/{joinCode}` — status, deck, seats; `POST /api/games/{joinCode}/join` — take the next free seat;
   `POST /api/games/{joinCode}/cpu` `{difficulty}` — host adds a CPU seat; `DELETE …/seats/{n}` — host removes one;
   `POST /api/games/{joinCode}/start` — host only, needs ≥ 2 seats
5. **Play:** `POST /api/games/{joinCode}/play` `{stat}` — only the current caller; the server resolves the round (CPUs and timeouts handled server-side)
6. **Live updates:** a WebSocket (or SSE) channel per game pushes lobby changes, "round resolved" events, and each player's own next card.
   REST for actions, push for state. `spring-boot-starter-websocket` when you get there.
7. `GET /api/leaderboard` — wins (`placement = 1`), games, win rate per player

## Phases
- [x] **0 Connect** — local Postgres (5433) works; Neon dev next
- [ ] **1 Decks + cards** — seed cards into the Wrestling deck (export Firebase `Data`, or the placeholder 54), repositories/row mappers for the model classes, `GET /api/decks…`, CORS for the frontend origin
- [ ] **2 Frontend uses it** — frontend fetches decks/cards (keep `data/cards.js` as offline fallback) and reads stat definitions instead of the hardcoded `STATS`
- [ ] **3 Players** — usernames / anonymous device token, `POST /api/players`
- [ ] **4 Rules engine (pure Java, N players)** — `Engine` with the multiplayer rules above, CPU seat logic, placements; unit-test it hard (3-way ties, caller eliminated, uneven deal, simultaneous elimination, conservation of cards)
- [ ] **5 Lobby + games** — create/join/add CPU/start, persistence of `games` / `game_players`
- [ ] **6 Live play** — WebSocket events, turn timeout, reconnect (rebuild a player's view from server state), hidden-information check
- [ ] **7 Leaderboard**
- [ ] **8 Frontend: lobby + multiplayer table** — share link, seats list, "add CPU", up to 8 players' card counts; the card component stays as is

Live round state (hands, pot, caller) lives in memory first; add `games.state jsonb` if you want a game to survive a server restart.

## Rules to carry over from the 2-player frontend engine (`app/frontend/js/engine.js`)
- Rank-like stats use `lower_wins`; ties go to the pot; the CPU judges each stat by the share of the deck its card beats (Easy = random, Normal = best + noise, Hard = best).

## Testing
Unit-test the engine (no Spring). Use `@JdbcTest` or Testcontainers-less approach: point tests at a **Neon branch**
(`Branches → Create branch`) so tests never touch real data.

## Environments
Neon branches: `main` (prod) and `dev` (copy-on-write, free). One `DATABASE_URL` per environment.

## Security checklist
No secrets in git (`.env` ignored) · validate/length-limit usernames · CORS limited to the frontend origin ·
rate-limit write endpoints · don't log connection strings · hash passwords with BCrypt if you add them.
