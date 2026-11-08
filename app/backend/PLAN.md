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
| `players` | accounts | username unique; Google sign-in adds `google_sub`, `email`, `display_name`, `avatar_url` and a `sessions` table (see *Authentication*) |
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
3. `POST /api/players` `{"username": "ann"}` → `201 {"id": "<uuid>", "username": "ann"}` ✅ built
   - trimmed + lowercased; valid = 1–8 chars of `[a-z0-9_]`, else `400 {"error": …}`; taken → `409 {"error": "Username already taken"}`
   - no auth yet: the client keeps the returned `id` as its identity
4. **Lobby:**
   - `POST /api/games` `{"deckId": "<uuid>", "maxPlayers": 4, "hostId": "<uuid, optional>"}` → `201` ✅ built
     ```json
     { "id": "<uuid>", "joinCode": "K7Q2XM", "joinUrl": "<frontend base>/?join=K7Q2XM", "status": "WAITING", "maxPlayers": 4,
       "deck": { "id": "<uuid>", "name": "Wrestling" },
       "seats": [ { "seat": 1, "kind": "HUMAN", "username": "ann", "difficulty": null } ] }
     ```
     `deckId` required; `maxPlayers` 2–8; the deck must be public/unlisted (else 404) and have at least `maxPlayers` cards (else 400);
     `hostId` must exist (else 404) and takes seat 1 (no `hostId` = no seats). `joinUrl` uses `app.frontend-base-url`
     (local `http://localhost:8081`, prod `${FRONTEND_ORIGIN}`). Join codes: 6 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, `SecureRandom`,
     inserted with `ON CONFLICT DO NOTHING` and retried on collision.
   - `GET /api/games/{joinCode}` (case-insensitive) → same body; 404 if unknown. The deck is looked up without the private filter so joiners see its name. ✅ built
   - Errors are always `{"error": "<message>"}`: 400 (`BadRequestException`, malformed body), 404 (`NotFoundException`), 409 (`ConflictException`).
   - `POST /api/games/{joinCode}/join` — take the next free seat;
   `POST /api/games/{joinCode}/cpu` `{difficulty}` — host adds a CPU seat; `DELETE …/seats/{n}` — host removes one;
   `POST /api/games/{joinCode}/start` — host only, needs ≥ 2 seats
5. **Play:** `POST /api/games/{joinCode}/play` `{stat}` — only the current caller; the server resolves the round (CPUs and timeouts handled server-side)
6. **Live updates:** a WebSocket (or SSE) channel per game pushes lobby changes, "round resolved" events, and each player's own next card.
   REST for actions, push for state. `spring-boot-starter-websocket` when you get there.
7. `GET /api/leaderboard` — wins (`placement = 1`), games, win rate per player

## Authentication (Google sign-in)

**Decisions (agreed with the user)**
- **Who must sign in:** only people who want to **create or join a multiplayer game**. Solo play vs the CPU, the deck gallery and the public GET endpoints stay anonymous.
- **No guests.** Joining via a link requires signing in with Google (the link first shows a read-only preview of the game, then asks to sign in).
- **Hosting (decided): one origin in production.** Spring Boot serves the static frontend (`app/frontend`, copied into `static/` at build time) next to the API, so the browser sees a single site: no CORS in prod, first-party cookies, WebSockets on the same origin, one Google "authorized origin". Locally the frontend (:8081) and API (:8080) stay separate — `localhost` ports are the same *site*, so cookies still work with CORS-with-credentials. If you later split to `app.<domain>` / `api.<domain>` (same registrable domain) nothing in the cookie design changes; keep `apiBase` configurable.

**Flow**
1. Frontend loads Google Identity Services and shows the "Sign in with Google" button (`config.js` has `googleClientId`; one-time nonce from `POST /api/auth/nonce`).
2. Google returns an **ID token** to the browser → `POST /api/auth/google {credential}`.
3. Backend verifies it (signature via Google keys, `aud` = our client id, `iss`, `exp`, `nonce`, `email_verified`), finds or creates the player by the stable Google **`sub`** (never by email), creates a session and sets it as a cookie.
4. Every later request is authenticated by the cookie, never by the Google token. `GET /api/me` tells the UI who is signed in.

**Sessions:** random 256-bit token, only its SHA-256 hash is stored (`sessions` table: `token_hash`, `player_id`, `created_at`, `expires_at`, `last_seen`). Cookie: `HttpOnly; Secure` (prod); `SameSite=Lax`; `__Host-` prefix in prod. Revocable (logout / delete account). The same token is also accepted as `Authorization: Bearer` so a future Android client can reuse it. Libraries: `spring-boot-starter-security` (cookie-session filter, CSRF, CORS, method security) + `google-api-client` `GoogleIdTokenVerifier`, wrapped in our own `GoogleTokenVerifier` interface so tests use a fake and never call Google.

**Data model (new changeset `002-auth.yaml`)**
- `players`: add `google_sub` (unique, not null for real accounts), `email`, `display_name`, `avatar_url`; make `username` **nullable** (chosen once after the first sign-in: 1–8 chars `[a-z0-9_]`, unique).
- `sessions`: as above.

**Endpoints**
| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/auth/nonce` | – | one-time nonce for the Google sign-in |
| `POST /api/auth/google` `{credential}` | – | verify token, set cookie, return the player |
| `GET /api/me` | cookie | current player or 401 |
| `PUT /api/me` `{username}` | cookie | choose/change username (400 invalid, 409 taken) |
| `POST /api/auth/logout` | cookie | delete session, clear cookie |
| `DELETE /api/me` | cookie | delete the account (privacy) |
| `POST /api/dev/login` `{username}` | **local profile only** | dev/test sign-in without Google |

**Stage 1 — built (additive, nothing existing changed)**
- Changeset `002-auth.yaml`: `players.google_sub` (unique) / `email` / `display_name` / `avatar_url`, `username` nullable; `sessions` table.
- Responses (`MeResponse`): `{ "id", "username" (null until chosen), "displayName", "avatarUrl", "email", "needsUsername" }`.
  - `POST /api/auth/nonce` → `{ "nonce" }` (single use, 5 minutes, held in memory — move to the DB if you run several instances).
  - `POST /api/auth/google {credential}` → 200 `MeResponse` + `Set-Cookie`. `400` no credential · `401` forged/expired/wrong-audience token, unverified email, unknown/reused/expired nonce · `503` `GOOGLE_CLIENT_ID` not set or Google's keys unreachable.
  - `GET /api/me` → `MeResponse`, or `401 {"error":"Sign in required"}`.
  - `PUT /api/me {username}` → `MeResponse`; `400` invalid (1–8 of `[a-z0-9_]`, trimmed + lowercased), `409` taken; choosing your own current name again is fine.
  - `POST /api/auth/logout` → 204 and the cookie is cleared (idempotent, also fine when signed out).
  - `DELETE /api/me` → 204; all sessions end and the account is **anonymized** (username, Google id, email, name, avatar all wiped, username freed) — the row stays so finished games keep their history.
  - `POST /api/dev/login {username}` → `MeResponse` + cookie; exists **only** where `app.dev-login.enabled=true` (set in `resources/local/application.yml`); in any other config it is a 404.
- **Cookie:** `session=<token>; Path=/; HttpOnly; SameSite=Lax; Max-Age=30 days` (`Secure` and the `__Host-session` name in prod). The token is also accepted as `Authorization: Bearer <token>`.
- **CSRF rule for cookie sessions:** state-changing requests sent *with the cookie* to `/api/me` and `/api/auth/logout` must carry an `X-Requested-With` header (any value) or get `403 {"error":"Missing X-Requested-With header"}`. Bearer clients are exempt. Stage 2 adds the game endpoints to this list.
- **CORS:** exact origins from `app.cors.allowed-origins` with `Access-Control-Allow-Credentials: true`; allowed headers `Content-Type, Authorization, X-Requested-With`. The frontend must use `fetch(..., { credentials: 'include' })`.
- **Config:** `GOOGLE_CLIENT_ID` (env or `.env.<APP_ENV>`; empty by default), `app.session.ttl-days` (30), `app.session.cookie-name`, `app.session.cookie-secure`.
- **Not done yet:** rate-limiting `/api/auth/*`, a scheduled purge of expired sessions (they are purged whenever a session is created), the stage-2 changes below.

**Changes to existing endpoints** *(stage 2 — only after the Frontend session has switched)*
- `POST /api/games` requires sign-in; the host is the **session's player**. The `hostId` body field is removed (a client-supplied host id would allow impersonation).
- `POST /api/games/{code}/join` (next backend step) requires sign-in.
- `GET /api/games/{code}` stays readable without sign-in (join-link preview).
- The anonymous `POST /api/players` is removed once sign-in ships (`/api/dev/login` replaces it for local development). **The Frontend session must be told before this lands** — it currently stores the returned player id as the host identity.
- CORS: `allowCredentials(true)` with the exact origin list (never `*`); frontend `fetch` uses `credentials: 'include'`.

**Security checklist:** verify the token server-side on every login, including the audience; nonce against replay; `HttpOnly`/`Secure`/`SameSite` cookie; require a custom header (or CSRF token) on state-changing requests; rate-limit `/api/auth/*`; expire and purge old sessions; never log tokens or cookies; store only `sub`, name, avatar and email.

**You do (Google Cloud, ~10 min):** create a project → OAuth consent screen (External; app name, support email; scopes `openid email profile`; add yourself as a test user while in Testing) → Credentials → *OAuth client ID* → **Web application** → authorized JavaScript origins `http://localhost`, `http://localhost:8081` (and later the prod origin). No redirect URI and no client secret are needed for this flow. Send the **Client ID**: it goes in the frontend `config.js` and the backend `GOOGLE_CLIENT_ID` setting.

**Build order**
1. *You:* Google Cloud client id.
2. *Backend:* `002-auth.yaml`; `GoogleTokenVerifier` (+ fake for tests); session service + cookie; Spring Security; the endpoints above; protect `POST /api/games`; tests (expired/forged token, wrong audience, username rules, session expiry, unauthenticated create-game → 401).
3. *Backend:* serve the frontend from Spring Boot in the `prod` build; CORS-with-credentials for local.
4. *Frontend:* Google button, `GET /api/me` on load, username screen, logout, 401 handling, join-link preview → sign-in → join; drop `hostId`/`POST /api/players`.
5. *Later:* the WebSocket handshake reuses the cookie; Android uses the same token endpoint with a bearer token.

## Phases
- [x] **0 Connect** — local Postgres (5433) works; Neon dev next
- [ ] **1 Decks + cards** — seed cards into the Wrestling deck (export Firebase `Data`, or the placeholder 54), repositories/row mappers for the model classes, `GET /api/decks…`, CORS for the frontend origin
- [ ] **2 Frontend uses it** — frontend fetches decks/cards (keep `data/cards.js` as offline fallback) and reads stat definitions instead of the hardcoded `STATS`
- [x] **3a Players (anonymous, temporary)** — `POST /api/players`; replaced by Google sign-in below
- [ ] **3b Google sign-in** — see *Authentication* above
- [ ] **4 Rules engine (pure Java, N players)** — `Engine` with the multiplayer rules above, CPU seat logic, placements; unit-test it hard (3-way ties, caller eliminated, uneven deal, simultaneous elimination, conservation of cards)
- [ ] **5 Lobby + games** — create + read are built; join / add CPU / start still to do
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
