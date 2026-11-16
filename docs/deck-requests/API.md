# Deck requests and decks of your own: API contract

All JSON. Errors are always `{"error": "message"}`. Sign-in is the existing session: the `session` cookie (or `Authorization: Bearer <token>`).
State-changing calls authenticated by the **cookie** must also send `X-Requested-With: <anything>` (e.g. `cards`), else `403 {"error":"Missing X-Requested-With header"}`.
Bodies over 64 KB (2 MB under `/api/admin/`) are refused with `413 {"error":"Request body too large"}`.
Dates are ISO-8601 UTC.

## `GET /api/me` (changed)
Adds `"isAdmin": true|false`. A player is an admin when their verified Google email is in the server's `ADMIN_EMAILS` list. Nothing is stored, so access disappears
as soon as the address is removed. Show admin UI only when `isAdmin` is true (the server enforces it anyway).

Local development only (when `/api/dev/login` exists): `POST /api/dev/login?admin=true` with `{"username":"boss"}` signs in as an admin.

## User endpoints (sign-in required, a chosen username required)

### `POST /api/deck-requests` -> `201`
```json
{"category": "Cricket legends", "description": "Test and ODI greats", "names": ["Sachin Tendulkar", "Kapil Dev", "Shane Warne"]}
```
The UI sends one name per line as an array element. The server trims, collapses whitespace, strips control characters, de-duplicates ignoring case (first spelling wins).
Rules: `category` 3-60 characters; `description` optional, at most 500; `names` 3-60 **different** names after cleaning, each at most 60 characters.
Limits: at most **3 pending** requests per user, and **5 new requests per rolling 24 hours**.

Errors: `400` validation (`category must be 3 to 60 characters`, `Suggest 3 to 60 different names`, `each name must be at most 60 characters`,
`description must be at most 500 characters`, `names is required`, `Choose a username first`) · `401` signed out · `409` `You already have 3 pending requests; wait for a decision`
· `413` too large · `429` `You can send 5 requests per day; try again tomorrow` or `Too many requests, slow down`.

Response (`DeckRequest`, the same shape for the two GETs below):
```json
{"id": "6f1c…", "type": "CATEGORY", "category": "Cricket legends", "description": "Test and ODI greats", "names": ["Sachin Tendulkar", "Kapil Dev", "Shane Warne"],
 "status": "PENDING", "adminNote": null, "createdAt": "2026-11-20T10:00:00Z", "decidedAt": null, "live": false, "deckId": null}
```
`status`: `PENDING`, `REJECTED`, `APPROVED_PRIVATE`, `APPROVED_GLOBAL`. `adminNote` is the admin's note (always present on a rejection). `live` becomes true when the deck has been
published (stage 2); `deckId` is only set then. An approved request that is not live yet is "approved, being prepared".

### `GET /api/deck-requests` -> `200`
Your requests, newest first (max 200). ### `GET /api/deck-requests/{id}` -> `200` one of yours (`404` for anybody else's).

## Admin endpoints (`/api/admin/**`): `401` signed out, `403` not an admin

`AdminDeckRequest` (what the admin sees):
```json
{"id": "6f1c…", "type": "CATEGORY", "requester": {"id": "…", "username": "ann"}, "category": "Cricket legends", "description": "…", "names": ["…"],
 "status": "PENDING", "adminNote": null, "deckId": null, "createdAt": "…", "decidedAt": null, "decidedBy": null, "notifiedAt": "…", "live": false, "publishedAt": null, "cardCount": 0}
```
`deckId` = the deck record created by the approval. `notifiedAt` = when the notification mail went out (null if it failed or no mail is configured).

| Request | Body | Result |
|---|---|---|
| `GET /api/admin/deck-requests?status=PENDING\|REJECTED\|APPROVED_PRIVATE\|APPROVED_GLOBAL\|ALL` | | list, **pending first**, then newest first (max 200); no `status` = ALL; `400` for an unknown status |
| `GET /api/admin/deck-requests/{id}` | | one request (`404` unknown) |
| `PATCH /api/admin/deck-requests/{id}` | `{"names": [...], "category": "…", "description": "…"}` every field optional | edits a **pending** request with the same cleaning and limits (`409` once decided) |
| `POST /api/admin/deck-requests/{id}/approve` | `{"visibility": "PRIVATE" \| "PUBLIC", "note": "optional"}` | `PRIVATE` = only the requester will see the deck, `PUBLIC` = global. Status becomes `APPROVED_PRIVATE` / `APPROVED_GLOBAL`, `decidedBy` / `decidedAt` are set and a **not-live** deck record is created (owner = requester). `400` bad visibility, `409` already decided |
| `POST /api/admin/deck-requests/{id}/reject` | `{"note": "why"}` required | status `REJECTED`; `400` without a note; `409` already decided |

A deck created by an approval is **not live**: it is not in `GET /api/decks`, `GET /api/decks/{id}` answers `404` and a game cannot be created with it, until it has stats and is
published (stage 2: import stats + publish). The same rule hides any deck that has no stats.

The admin page link in the notification email is `<FRONTEND_ORIGIN>/#/admin/requests/<id>`.

## Environment (names only)
`ADMIN_EMAILS` (comma-separated admin Google emails), `ADMIN_NOTIFY_TO` (defaults to `ADMIN_EMAILS`), `EMAIL_API_KEY` + `EMAIL_FROM` (Resend; without both, mails are only logged).

---

# Stage 2: stats, publishing, and decks of your own

## `type` of a request
`CATEGORY` (a suggested category; the admin makes the deck) or `PROMOTE` (the owner of a private deck asks to make it global). For a `PROMOTE` request `category` is the deck's name,
`names` is a snapshot of its card names and `deckId` is always the deck being promoted. `GET /api/deck-requests` and the admin list contain both types.
`DELETE /api/deck-requests/{id}` withdraws one of **your own** requests while it is still `PENDING` (`204`; `404` not yours; `409` already decided).

## Admin: filling and publishing a category request (all under `/api/admin/**`)
| Request | Body | Result |
|---|---|---|
| `PUT /api/admin/deck-requests/{id}/stats` | `Content-Type: text/csv` (header `name,rank,fight,chest,biceps,height,weight` plus optional `brand`, `image`) | replaces the deck's cards; the deck gets the six stats of the built-in Wrestling deck (same keys, units, directions; `height` = total inches). `image` may be an https URL (stored as given). Up to 500 cards. Answers with the request (`cardCount`). `400` lists the row problems, `409` not approved / already live / a PROMOTE request |
| the same | `application/json` `{"cards": [{"name": "A", "rank": 1, "fight": 44, "chest": 38.5, "biceps": 15, "height": 73, "weight": 240, "brand": "raw", "image": "https://…"}, …]}` | same as CSV |
| `GET /api/admin/deck-requests/{id}/cards` | | the cards of the request's deck as `Card`s (also for a PROMOTE request: the deck under review; images included) |
| `POST /api/admin/deck-requests/{id}/publish` | | goes live: the deck gets the visibility chosen at approval (`PRIVATE` or `PUBLIC`), `live: true`, `publishedAt` set. `409` if fewer than 8 cards were imported, already live, not approved, or a PROMOTE request |
| `POST /api/admin/deck-requests/{id}/approve` of a **PROMOTE** request | `{"note": "optional"}` (`visibility` may be left out; only `PUBLIC` is accepted) | the deck becomes `PUBLIC` at once, the owner keeps it; the request is `APPROVED_GLOBAL`, `live: true` |
| `POST …/reject` of a PROMOTE request | `{"note": "why"}` | the deck stays private; the owner sees the note in `GET /api/decks/mine` |

## Deck visibility (changed)
* `GET /api/decks` = the public decks, plus **your own** decks when signed in. A deck that is not live, or has no stats, is never listed.
* `GET /api/decks/{id}` and `/cards`: public decks for everybody; a private deck for **its owner**, for **an admin**, and for **anybody seated in a game (waiting or playing) that uses it**
  (the other players need its cards). Anybody else gets `404`.
* A game can be created with a private deck by its owner only (`404` for anybody else). Everybody seated reads the deck's cards through the game; the live `view` messages already carry them,
  with `img` set to the image address.
* Card `img` is either `/api/images/{uuid}` (an uploaded image, use it as a relative URL on the API origin) or an https URL given in the CSV, or `null`.

## `GET /api/images/{id}` (public)
The re-encoded image (`image/jpeg`, or `image/png` with transparency), at most 800 px, `Cache-Control: public, max-age=31536000, immutable`, `ETag`, `X-Content-Type-Options: nosniff`; `404` if unknown.

## Your own decks (sign-in required; cookie state changes need `X-Requested-With`)

### `GET /api/decks/import/template` -> `200`
`{"csv": "name,image,brand,rank,fight,chest,biceps,height,weight\n…8 example rows…", "mapping": {"stats": [...6 stats...]}, "notes": [...]}`: a CSV that validates and the mapping that goes with it
(the Wrestling schema). Offer it as a download.

### `POST /api/decks/import` (`multipart/form-data`)
| Part | |
|---|---|
| `csv` | the CSV file (UTF-8, header row, at most 1 MB / 600 rows). Needs a `name` column; optional `image` (an uploaded file name, or an https URL) and `brand` (any text up to 30 characters, e.g. raw / sd); **every other column named in the mapping is a stat** |
| `mapping` | JSON text: `{"stats": [{"column": "rank", "label": "Rank", "unit": null, "higherWins": false, "displayFormat": "PLAIN"}, …]}`. `key` is optional (default: the column name, lower-case letters/digits/_ , up to 20). `higherWins` is required. `displayFormat`: `PLAIN`, `WITH_UNIT` (needs `unit`), `FEET_INCHES` (whole inches). 1 to 8 stats; `name`, `image`, `brand` are reserved. Whole-number columns become INT, the rest DECIMAL (at most 2 decimals) |
| `images` | optional, repeatable: **one ZIP** and/or image files (PNG, JPEG or GIF, at most 2 MB each, 200 files; a ZIP may unpack to at most 30 MB and 300 entries). A CSV `image` cell names a file by its base name, case-insensitive; folders inside the ZIP do not matter |
| `name` | deck name, 3-40 characters (required to save) |
| `description` | optional, at most 300 characters |

Rules: **8 to 100 cards**, unique names (case-insensitive, at most 60 characters), every stat cell a plain number within ±1,000,000, ranks unique when a stat with key `rank` exists.
Limits per player: **5 decks**, **10 saved uploads per hour**, **30 previews per hour**. The whole request is limited to 40 MB.

* `?dryRun=true` -> `200` always (when the mapping and CSV can be read at all) and **saves nothing**:
```json
{"valid": false, "errorCount": 2, "errors": ["Row 7 (Hero 7): image 'hero7.jpg' was not uploaded", "…"],
 "stats": [{"key": "rank", "column": "rank", "label": "Rank", "unit": null, "higherWins": false, "valueType": "INT", "displayFormat": "PLAIN"}],
 "cards": [{"name": "Hero 1", "image": "(uploaded image 120x90)", "brand": "raw", "stats": {"rank": 1, "power": 11}}]}
```
  `errors` has up to 50 entries, `errorCount` the true number; `cards` is empty when invalid.
* without `dryRun` -> `201` + the `Deck` (visibility `PRIVATE`, live, you are the owner), or `400 {"error": "first problems…"}`. `409` at 5 decks, `429` over the limits, `413` too large.
* Images are never fetched from URLs. An https address is stored as given (no whitespace/quotes, at most 500 characters, no user info); `http:`, `data:`, `javascript:` etc. are refused.
  Uploaded files are identified by their real content, decoded and **re-encoded** (metadata and hidden data are dropped).

### `GET /api/decks/mine` -> `200`
```json
[{"id": "…", "name": "Heroes", "description": null, "visibility": "PRIVATE", "live": true, "cardCount": 8,
  "stats": [ {…StatDefinition…} ],
  "promotion": {"requestId": "…", "status": "PENDING" | "REJECTED" | "APPROVED_GLOBAL", "adminNote": null, "createdAt": "…", "decidedAt": null}}]
```
`promotion` is `null` when you never asked. A deck created from an approved category request appears here too (`live: false` until the admin publishes it).

### Changing a deck (owner only; `404` when it is not yours and private, `403` when it is public and not yours)
| Request | |
|---|---|
| `PATCH /api/decks/{id}` `{"name": "…", "description": "…"}` | rename; a field left out stays |
| `PUT /api/decks/{id}/import` | the same multipart as the upload (`name`/`description` optional): replaces the stats and cards; unused old images are deleted |
| `DELETE /api/decks/{id}` | `204`; its images and any open promotion request go with it. `409` when the deck was ever used in a game |
| `POST /api/decks/{id}/copy` `{"name": "optional"}` | `201` a new private deck with the same stats, cards and images |

Edit rules: a **PUBLIC deck cannot be edited** (`409`; copy it to a private deck instead), and a deck with an **open promotion request cannot be edited** (`409`: withdraw the request first).
So a promotion review can never be bypassed.

### `POST /api/decks/{id}/request-global` -> `201` a request with `type: "PROMOTE"`
Owner only, the deck must be private and have at least 8 cards. One open request per deck (`409`). It counts toward the same limits as category requests (3 pending, 5 per day). The admin gets the same
email (subject `Make deck global: <name>`, with the link `<FRONTEND_ORIGIN>/#/admin/requests/<id>`). Withdraw it with `DELETE /api/deck-requests/{id}` while pending.
