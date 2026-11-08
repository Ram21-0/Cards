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
The authoritative specification is **[RULES.md](RULES.md)** — change it there first, then the code. In short:
- **Setup:** 2–8 seats (humans and/or CPUs), deck dealt round-robin (uneven is fine), random first caller. Per-game settings (host may override at creation):
  `maxRounds` (500), `chooseThreshold` (3), `clashCallSeconds` (5), `turnTimeoutSeconds` (none).
- **A round:** the caller picks a stat, everyone reveals a card, the unique best wins every revealed card (plus any carried pot) at the bottom of their pile and calls next.
- **Clash** (tie for best): all revealed cards go into a pot; only the tied players continue, racing to call a stat (first call within `clashCallSeconds`, else a random stat); repeats until one wins the whole pot.
- **CHOOSE:** a player with 2…`chooseThreshold` cards may play any card from their hand once the stat is known.
- **Elimination / placements** by cards left; **round cap** → most cards wins; **CPU seats** judge stats by deck-share strength (Easy random, Normal noisy, Hard best).
- **Hidden information:** nobody receives another player's card before it is revealed.
- **Code:** the pure engine lives in `com.cards.game` (`GameEngine`: commands `choose` / `call` / `tick`, events returned, injectable random source and clock); no Spring or database code in it.

## API (in build order)
1. `GET /api/health` — `{"status":"ok"}` (+ db ping)
2. `GET /api/decks` — public decks (+ the caller's own); `GET /api/decks/{id}` — deck with stat definitions;
   `GET /api/decks/{id}/cards` — `{ id, name, img, stats: { height: { value: 73, displayValue: "6'1\"" }, … }, meta: {…} }`
   (stats are in the deck's `display_order`; `displayValue` is formatted server-side from the stat's `display_format`, so clients just render it)
3. ~~`POST /api/players`~~ — **removed in stage 2** (it let anyone claim any username). Identity is now Google sign-in; the username is chosen with `PUT /api/me`
   (see *Authentication* below).
4. **Lobby:**
   - `POST /api/games` `{"deckId": "<uuid>", "maxPlayers": 4, "options": { "maxRounds": 500, "chooseThreshold": 3, "clashCallSeconds": 5, "turnTimeoutSeconds": 30 }}` → `201` ✅ built — **requires sign-in**; the host is the session's player.
     `options` and every field in it are optional (missing = the default from `app.game.*` in application.yml); ranges: maxRounds 20–5000, chooseThreshold 0–10, clashCallSeconds 2–30, turnTimeoutSeconds 5–120 (omit = no limit), else `400 {"error":"maxRounds must be between 20 and 5000"}` etc.
     ```json
     { "id": "<uuid>", "joinCode": "K7Q2XM", "joinUrl": "<frontend base>/?join=K7Q2XM", "status": "WAITING", "maxPlayers": 4,
       "deck": { "id": "<uuid>", "name": "Wrestling" },
       "seats": [ { "seat": 1, "kind": "HUMAN", "username": "ann", "difficulty": null } ],
       "options": { "maxRounds": 500, "chooseThreshold": 3, "clashCallSeconds": 5, "turnTimeoutSeconds": null } }
     ```
     `deckId` required; `maxPlayers` 2–8; the deck must be public/unlisted (else 404) and have at least `maxPlayers` cards (else 400);
     the signed-in host takes seat 1; a `hostId` in the body (old clients) is ignored. `401 {"error":"Sign in required"}` when signed out;
     `400 {"error":"Choose a username first"}` when the player has not picked a username (seats show usernames). `joinUrl` uses `app.frontend-base-url`
     (local `http://localhost:8081`, prod `${FRONTEND_ORIGIN}`). Join codes: 6 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, `SecureRandom`,
     inserted with `ON CONFLICT DO NOTHING` and retried on collision.
   - `GET /api/games/{joinCode}` (case-insensitive) → same body; 404 if unknown. The deck is looked up without the private filter so joiners see its name. ✅ built
   - Errors are always `{"error": "<message>"}`: 400 (`BadRequestException`, malformed body), 404 (`NotFoundException`), 409 (`ConflictException`).
   - `POST /api/games/{joinCode}/join` (no body) → `200`, the **same body as `GET /api/games/{joinCode}`** ✅ built — **requires sign-in**.
     Takes the lowest free seat (the game row is locked while seating, so two people cannot get the last seat). Idempotent: someone already
     seated just gets the game back (also after it started). `401` signed out · `400 "Choose a username first"` · `404` unknown code ·
     `409 "Game is full"` · `409 "Game has already started"`.
   - `POST /api/games/{joinCode}/cpu` `{difficulty}` — host adds a CPU seat; `DELETE …/seats/{n}` — host removes one;
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
- **Not done yet:** rate-limiting `/api/auth/*`, a scheduled purge of expired sessions (they are purged whenever a session is created).

**Stage 2 — built (the Frontend session switched first)**
- `POST /api/games` requires sign-in; the host is the **session's player** and takes seat 1. The `hostId` body field is gone (a client-supplied host id would allow impersonation; if an old client still sends one it is ignored).
- `POST /api/games/{code}/join` requires sign-in (contract above). `GET /api/games/{code}` stays public (join-link preview).
- Creating or joining needs a chosen username (`400 "Choose a username first"`), because seats show usernames.
- The anonymous `POST /api/players` is removed (404). `POST /api/dev/login` replaces it for local development.
- The `X-Requested-With` rule now also covers cookie-authenticated `POST /api/games` and `POST /api/games/{code}/join`.
- CORS: `allowCredentials(true)` with the exact origin list (never `*`); frontend `fetch` uses `credentials: 'include'`.

**Serving the frontend from Spring Boot (one origin in production)**
- `mvn -Pbundle-frontend package` copies `app/frontend` into the jar's `static/` (leaving out `test/`, `scripts/`, `package*.json`, `README.md` and `config.js`),
  so one jar serves the site at `/` and the API at `/api/**`: no CORS, first-party cookies.
- `config.js` is generated by the backend (`RuntimeConfigController`): `window.CARDS_CONFIG = {"apiBase":"","googleClientId":"<GOOGLE_CLIENT_ID>"}` — empty `apiBase` = same origin,
  and the client id comes from the same setting the token verifier checks, so there is one source of truth.
- The default build (local development) does not include the frontend: it keeps running on :8081 with its own `config.js` and talks to :8080 with CORS-with-credentials.
- Production settings: `APP_ENV=prod`, `DATABASE_URL`, `GOOGLE_CLIENT_ID`, `FRONTEND_ORIGIN` (the site's own origin, also used for join links); the cookie becomes `__Host-session` + `Secure`.

**Security checklist:** verify the token server-side on every login, including the audience; nonce against replay; `HttpOnly`/`Secure`/`SameSite` cookie; require a custom header (or CSRF token) on state-changing requests; rate-limit `/api/auth/*`; expire and purge old sessions; never log tokens or cookies; store only `sub`, name, avatar and email.

**You do (Google Cloud, ~10 min):** create a project → OAuth consent screen (External; app name, support email; scopes `openid email profile`; add yourself as a test user while in Testing) → Credentials → *OAuth client ID* → **Web application** → authorized JavaScript origins `http://localhost`, `http://localhost:8081` (and later the prod origin). No redirect URI and no client secret are needed for this flow. Send the **Client ID**: it goes in the frontend `config.js` and the backend `GOOGLE_CLIENT_ID` setting.

**Build order**
1. *You:* Google Cloud client id.
2. *Backend (done):* `002-auth.yaml`; `GoogleTokenVerifier` (+ fake for tests); session service + cookie; Spring Security; the endpoints above; protect `POST /api/games`; tests (expired/forged token, wrong audience, username rules, session expiry, unauthenticated create-game → 401).
3. *Backend (done):* serve the frontend from Spring Boot in the bundle build (`-Pbundle-frontend`); CORS-with-credentials for local.
4. *Frontend (done by the Frontend session):* Google button, `GET /api/me` on load, username screen, logout, 401 handling, join-link preview → sign-in → join; drop `hostId`/`POST /api/players`.
5. *Later:* the WebSocket handshake reuses the cookie; Android uses the same token endpoint with a bearer token.

## Phases
- [x] **0 Connect** — local Postgres (5433) works; Neon dev next
- [x] **1 Decks + cards** — seed cards into the Wrestling deck (export Firebase `Data`, or the placeholder 54), repositories/row mappers for the model classes, `GET /api/decks…`, CORS for the frontend origin
- [x] **2 Frontend uses it** — frontend fetches decks/cards (keep `data/cards.js` as offline fallback) and reads stat definitions instead of the hardcoded `STATS`
- [x] **3 Players** — Google sign-in, sessions, username (the temporary anonymous `POST /api/players` is gone) — see *Authentication* above
- [x] **4 Rules engine (pure Java, N players)** — `com.cards.game.GameEngine` per [RULES.md](RULES.md): rounds, recursive clash with the 5 s race, CHOOSE, elimination/placements, round cap, configurable CPU strategies; tested with the RULES.md §10 checklist and 3000 seeded random games (conservation checked at every step)
- [x] **5 Lobby + games** — create, read, join, add/remove CPU, leave, start (builds the `GameEngine` from the seats) and the live lobby over WebSocket; contract in [LIVE.md](LIVE.md)
- [x] **6 Live play** (contract: [LIVE.md](LIVE.md)) — `call`/`choose`, WebSocket `lobby`/`started`/`view`/`events`/`gameOver`, turn and clash timers, absent-player grace, reconnect (the view is rebuilt from server state), hidden-information tests, result saved on game over. **Games run in memory** (`GameRegistry`): a deployment must be a **single instance**, and games that were IN_PROGRESS at a restart are marked ABANDONED at startup (no changeset 004 was needed)
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
