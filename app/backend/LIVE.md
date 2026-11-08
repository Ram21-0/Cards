# Live play contract (REST + WebSocket)

Everything the frontend needs to run a game lobby and play a game. The rules themselves are in [RULES.md](RULES.md).
**Server-authoritative:** the browser never decides anything; it sends intentions and renders what the server pushes.

Status: **lobby actions and the live lobby are built (stage 1).** Calling a stat, choosing a card, `events`,
`gameOver` and timers arrive in stage 2 and will be added to this file.

## Authentication

* REST: the `session` cookie (HttpOnly, set at sign-in). `Authorization: Bearer <token>` also works for non-browser clients.
* State-changing REST requests authenticated by the cookie must also send the header `X-Requested-With: <anything>`
  (e.g. `cards`), otherwise `403`. Bearer requests do not need it.
* Errors always look like `{"error": "message"}` with the status codes below. `401` = not signed in.
* Action endpoints are rate limited per player (default 30 requests per 10 s) → `429 {"error":"Too many requests, slow down"}`.

## REST: lobby actions

All return the game (`GameResponse`) on success unless noted. `{code}` is the join code, case-insensitive.

| Request | Who | Notes |
|---|---|---|
| `POST /api/games` | any signed-in user | existing: create (you take seat 1 and are host) |
| `POST /api/games/{code}/join` | signed in | existing; idempotent if you are already seated |
| `POST /api/games/{code}/cpu` body `{"difficulty":"easy\|medium\|hard"}` | host | adds a CPU in the lowest free seat |
| `DELETE /api/games/{code}/seats/{seat}` | host | removes a **CPU** seat only |
| `POST /api/games/{code}/leave` | seated human | leaves a WAITING game. Host leaving passes host to the lowest-seated human; if no human is left the game becomes `ABANDONED` |
| `POST /api/games/{code}/start` | host | needs ≥ 2 seats; WAITING → IN_PROGRESS; seats are renumbered 1..n (gaps closed) |

Errors: `400` bad input (unknown difficulty, removing a human seat); `403` not the host; `404` unknown game / empty seat;
`409` wrong state (game already started, full, fewer than 2 seats, not in this game).

### The game body

```json
{
  "id": "6f1c…", "joinCode": "K7Q2XM", "joinUrl": "http://localhost:8081/?join=K7Q2XM",
  "status": "WAITING", "maxPlayers": 4,
  "deck": {"id": "…", "name": "Wrestling"},
  "seats": [
    {"seat": 1, "kind": "HUMAN", "username": "ann", "difficulty": null, "placement": null},
    {"seat": 3, "kind": "CPU",   "username": null,  "difficulty": "HARD", "placement": null}
  ],
  "options": {"maxRounds": 200, "chooseThreshold": 8, "clashCallSeconds": 5, "turnTimeoutSeconds": null},
  "hostSeat": 1
}
```

`status` is `WAITING`, `IN_PROGRESS`, `FINISHED` or `ABANDONED`. While WAITING, seat numbers may have gaps (a removed CPU or a
player who left); `start` closes them. `placement` is `null` until the game is finished (1 = winner, ties share a number).
Player ids are never sent.

## WebSocket

Plain WebSocket, JSON text frames (not STOMP). One connection per player per game:

```
ws(s)://<backend>/ws/games/{code}
```

(`wss` on Render/HTTPS.) Browsers send the session cookie automatically; the page's `Origin` must be one of the configured
frontend origins. A non-browser client may send `Authorization: Bearer <token>` instead.

**Handshake refusals** (HTTP status instead of `101`): `401` not signed in · `403` not seated in this game, or bad `Origin` ·
`404` unknown game. Do not reconnect in a loop on 401/403/404.

**Limits:** client messages are limited to 512 characters (larger → close `1008`). The server pings; a connection that has been
silent for 90 s is closed. Clients should send `{"type":"ping"}` every ~25 s (answered with `pong`) to keep proxies from
dropping an idle socket, and reconnect with back-off when the socket closes.

### Client → server

| Message | Reply |
|---|---|
| `{"type":"ping"}` | `{"type":"pong","serverTime":"…"}` |
| anything unparsable | `{"type":"error","error":"…"}` |

Actions are done with the REST endpoints above, not over the socket.

### Server → client

Every message has `type` and, where useful, `serverTime` (ISO-8601 UTC; use it to correct clock skew when counting down a deadline).

**`lobby`**: sent on connect, and again after every lobby change (someone joins/leaves, a CPU is added/removed, host changes).
After the game has started a reconnecting client also gets a `lobby` first (status `IN_PROGRESS`), then its `view`.

```json
{"type":"lobby","serverTime":"2026-11-08T10:00:00Z","mySeat":1,"game":{ …the game body above… }}
```

`mySeat` is yours (or `null` if you were removed). If you leave the game the server closes your socket.

**`started`**: the host started the game; `game.seats` are now numbered 1..n, `status` is `IN_PROGRESS`. A `view` follows immediately.

```json
{"type":"started","serverTime":"…","mySeat":1,"game":{ … }}
```

**`view`**: *your* personalised view of the table. Sent on connect (so a reconnect or page reload just works) and after every change.

```json
{
  "type": "view", "serverTime": "2026-11-08T10:00:01Z",
  "seat": 1, "phase": "AWAITING_CALL", "round": 1, "callerSeat": 1, "stat": null,
  "clashLevel": 0, "potSize": 0, "deadline": null, "contenders": [1, 2],
  "handSizes": {"1": 6, "2": 6},
  "ownHand": [ {"id":"…","name":"…","stats":{…}} ],
  "committed": null, "canChoose": false, "canCall": true,
  "placements": {}
}
```

* **Hidden information:** `ownHand` (and `committed`) contain only *your* cards. Opponents appear only as counts in `handSizes`
  (which includes a card currently in play). A card, its id or its stat values are never sent to anyone but its holder until the
  engine reveals it (stage 2 `events`). This is covered by tests.
* `phase`, `deadline` (when a timer is running: the server will act at that instant) and `canCall`/`canChoose` say what you may do.
* `placements` is `{seat: placement}`, empty until the game ends.

## Deployment notes

Running games live **in memory** on one server instance; run exactly one instance. A server restart marks games that were
IN_PROGRESS as ABANDONED. A free Render instance that sleeps drops every open WebSocket: the client must reconnect (it gets a `lobby`
and `view` again) and may need to retry for ~30 s while the instance wakes.
