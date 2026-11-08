# Live play contract (REST + WebSocket)

Everything the frontend needs to run a game lobby and play a game. The rules themselves are in [RULES.md](RULES.md).
**Server-authoritative:** the browser never decides anything; it sends intentions and renders what the server pushes.

Status: **complete**: lobby actions and the live lobby, calling a stat, CHOOSE, `events`, `gameOver`, timers, reconnect.

## Authentication

* REST: the `session` cookie (HttpOnly, set at sign-in). `Authorization: Bearer <token>` also works for non-browser clients.
* State-changing REST requests authenticated by the cookie must also send the header `X-Requested-With: <anything>`
  (e.g. `cards`), otherwise `403`. Bearer requests do not need it.
* Errors always look like `{"error": "message"}` with the status codes below. `401` = not signed in.
* Action endpoints (`join`, `cpu`, `seats`, `leave`, `start`, `call`, `choose`) are rate limited per player (default 30 requests per 10 s) → `429 {"error":"Too many requests, slow down"}`.

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

## REST: playing

Both need the sign-in cookie (+ `X-Requested-With`) or Bearer, and the caller must hold a seat in the running game.
They answer `200` with the caller's own `view` (same shape as the WebSocket `view` message); everyone, including the caller's
other tabs, also gets the pushes below. Prefer reacting to the pushes; the answer is a convenience.

| Request | Body | When |
|---|---|---|
| `POST /api/games/{code}/call` | `{"stat":"weight"}` (a stat `key` of the deck) | you are the caller of a normal round (`view.canCall`), or any player still in a clash race |
| `POST /api/games/{code}/choose` | `{"cardId":17}` (an id from `view.ownHand`) | `view.canChoose` (CHOOSE: the caller before calling, everyone else after the stat is known; also inside clashes) |

Errors (`{"error":"…"}`): `400` unknown stat, a card that is not in your hand, `cardId` missing · `401` not signed in ·
`403` not seated in this game, or it is not your turn · `404` unknown game · `409` the game is not running, or it is not waiting
for this kind of action (e.g. `choose` while a stat is awaited, anything after the game ended) · `429` rate limit.
A late race loser in a clash simply gets `409` (the stat was already set).

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

Actions (call / choose / add CPU / start …) are done with the REST endpoints above, not over the socket.

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
  "seat": 1, "phase": "AWAIT_CALL", "round": 1, "callerSeat": 1, "stat": null,
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
* `phase` is one of `AWAIT_CHOOSE_CALLER`, `AWAIT_CALL`, `AWAIT_CHOOSE_OTHERS`, `RESOLVE`, `CLASH_AWAIT_CALL`, `CLASH_AWAIT_CHOOSE`,
  `CLASH_RESOLVE`, `ROUND_DONE`, `GAME_OVER` (the RESOLVE/DONE ones are transient; you will normally see the other three kinds).
  `callerSeat` is the caller of this round. `stat` is the key of the stat in play (null until called). `clashLevel` is 0 in a normal round,
  1, 2 … in repeated clashes; `potSize` counts cards waiting in the pot; `contenders` are the seats still in the round or clash.
* `canCall` / `canChoose` say what *you* may do now. `deadline` (ISO-8601, or `null`) is when the server will act by itself
  (turn timeout, or the end of a clash race); compare it with `serverTime`, not with your own clock.
* `ownHand` is only your top card normally; when `canChoose` is true it is your whole hand (that is what you choose from).
  `committed` is the card you already put in play this round (only you see it until it is revealed).
* `placements` is `{seat: placement}` for seats already decided (eliminated players, or everyone at the end).

**`events`**: what just happened, for animations. Sent to every player, **identical for all**, followed by each player's `view`.
A game step can produce several events at once (e.g. a human's call, the CPUs' choices, the reveal, the winner, the next round).

```json
{"type":"events","serverTime":"…","events":[
  {"type":"statCalled","seat":1,"stat":"weight","auto":false},
  {"type":"cardChosen","seat":2,"auto":false},
  {"type":"revealed","cards":{"1":{"id":4,"name":"…","stats":{…}},"2":{"id":9,"name":"…","stats":{…}}}},
  {"type":"roundWon","seat":2,"cardsWon":5},
  {"type":"roundStarted","round":2,"caller":2,"seats":[1,2]}
]}
```

| event `type` | fields | meaning |
|---|---|---|
| `roundStarted` | `round`, `caller`, `seats` | a new round; `seats` still hold cards |
| `cardChosen` | `seat`, `auto` | someone committed a card (never says which). `auto`: the clock ran out and their top card was used |
| `statCalled` | `seat` (null if nobody called in a clash), `stat`, `auto` | the stat is set. `auto`: the clock ran out and the server picked at random |
| `revealed` | `cards` = `{seat: card}` | the cards in play, **now public**. Same card shape as in `ownHand` |
| `roundWon` | `seat`, `cardsWon` | the winner took everything; `cardsWon` = how many cards went to the bottom of their pile this round (their own included, plus the others' and any pot) |
| `clashStarted` | `tiedSeats`, `level`, `potSize` | a tie for best: a clash among `tiedSeats`; race to call the stat |
| `potCarried` | `potSize` | nobody in the clash had a next card; the pot waits for a later round's winner |
| `playerEliminated` | `seat`, `placement` | a player has no cards left |
| `gameOver` | `placements`, `endedByRoundCap` | the end (the separate `gameOver` message follows once the result is saved) |

Events are ordered; apply them in order. If you miss any (reconnect), just use the `view` you get on connect: it is the truth.

**`gameOver`** (a top-level message, sent after the result is saved):

```json
{"type":"gameOver","serverTime":"…","mySeat":1,"placements":{"1":2,"2":1,"3":3},"endedByRoundCap":false,"game":{ …final game body, status FINISHED, seats with placement… }}
```

`placements`: 1 = winner; players who tie at the end share a number. `endedByRoundCap` is true when the round cap (`options.maxRounds`) ended
the game. After this the game leaves server memory: reconnecting gets a `lobby` message (status `FINISHED`, with placements) and no `view`;
`call`/`choose` answer `409`.

## Timers and absent players (all server side)

* **Turn timeout** (`options.turnTimeoutSeconds`, `null` = none): when it runs out the server plays the move (random stat, or the top card)
  and tells everyone with `auto: true` on the event.
* **Clash race** (`options.clashCallSeconds`, default 5): the first `call` wins; if nobody calls in time the server picks a random stat
  (`statCalled` with `seat: null, auto: true`). CPUs call after a random 1.5–4 s delay so humans can win the race.
* **Disconnected human:** if a human has no open socket while it is their turn (or becomes their turn), the server waits a grace period
  (`app.live.absence-grace-seconds`, default 15) **from the later of "disconnected" and "their turn began"** and then plays that seat's
  move as a timeout (`auto: true`). Reconnecting within the grace cancels it. This happens even when the game has no turn timeout.
  A clash race is not shortened by absence (it has its own 5 s timer).
* CPU seats play instantly where the rules allow (the whole CPU-only part of a round is applied in one step, so you receive its events in one batch).

## Deployment notes

Running games live **in memory** (nothing about a game in progress is stored in the database except who sits where; the result is written when it ends: status `FINISHED`, `rounds`, `finished_at`, each seat's `placement`) on one server instance; run exactly one instance. A server restart marks games that were
IN_PROGRESS as ABANDONED. A free Render instance that sleeps drops every open WebSocket: the client must reconnect (it gets a `lobby`
and `view` again) and may need to retry for ~30 s while the instance wakes.
