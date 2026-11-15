# Deck requests: API contract (stage 1)

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
{"id": "6f1c…", "category": "Cricket legends", "description": "Test and ODI greats", "names": ["Sachin Tendulkar", "Kapil Dev", "Shane Warne"],
 "status": "PENDING", "adminNote": null, "createdAt": "2026-11-20T10:00:00Z", "decidedAt": null, "live": false, "deckId": null}
```
`status`: `PENDING`, `REJECTED`, `APPROVED_PRIVATE`, `APPROVED_GLOBAL`. `adminNote` is the admin's note (always present on a rejection). `live` becomes true when the deck has been
published (stage 2); `deckId` is only set then. An approved request that is not live yet is "approved, being prepared".

### `GET /api/deck-requests` -> `200`
Your requests, newest first (max 200). ### `GET /api/deck-requests/{id}` -> `200` one of yours (`404` for anybody else's).

## Admin endpoints (`/api/admin/**`): `401` signed out, `403` not an admin

`AdminDeckRequest` (what the admin sees):
```json
{"id": "6f1c…", "requester": {"id": "…", "username": "ann"}, "category": "Cricket legends", "description": "…", "names": ["…"],
 "status": "PENDING", "adminNote": null, "deckId": null, "createdAt": "…", "decidedAt": null, "decidedBy": null, "notifiedAt": "…", "live": false, "publishedAt": null}
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
