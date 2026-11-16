# Deck requests and decks of your own: plan

Users ask for a new category (a deck); the admin gets an email, reviews the request, approves it as a **private** or **global** deck, and
the deck goes live once it has stats. Branch: `deck_requests`. Backend only here; the frontend follows `API.md`.

## A. User flow
A signed-in (Google) user with a chosen username submits **category name**, **short description** and a **suggested list of names**
(3-60 names; trimmed, whitespace collapsed, case-insensitively de-duplicated, max 60 characters each, control characters removed, one per line in the UI).
They can list their own requests and see the status. Limits: at most **3 pending** requests per user (409) and **5 new requests per rolling 24 h** (429).
Anonymous users get 401. Every error is the usual `{"error": "..."}`.

## B. Data (changeset 006, new file)
- `deck_requests`: `id`, `requester_id` -> players, `category`, `description`, `names` jsonb array, `status` smallint, `admin_note`,
  `resulting_deck_id` -> decks, `created_at`, `decided_at`, `decided_by` -> players, `notified_at`, `published_at` (set when the deck goes live, stage 2).
- Status codes (`DeckRequestStatus`): 0 PENDING, 1 REJECTED, 2 APPROVED_PRIVATE, 3 APPROVED_GLOBAL. "Live" is `published_at`, not a status.
- `decks.live` (boolean, default true): a deck created by an approval starts **not live**. A not-live deck, or a deck without stats, is never listed,
  never readable through the public deck endpoints and cannot be used for a game.

## C. Admin login
Reuse Google sign-in. A player is an **admin** when their stored, verified Google email is in the env allow-list `ADMIN_EMAILS` (comma separated, case-insensitive).
Computed on every request (nothing stored), so removing an address revokes access. `GET /api/me` returns `isAdmin`. Everything under `/api/admin/**`: 401 signed out,
403 not an admin, and the same `X-Requested-With` rule as other state-changing cookie calls. Local development only (`app.dev-login.enabled`):
`POST /api/dev/login?admin=true` signs in as an admin. No email address is hardcoded.

## D. Email to the admin
On creation, mail `ADMIN_NOTIFY_TO` (default `ADMIN_EMAILS`) with the category, requester username, number of names and a link
`<FRONTEND_ORIGIN>/#/admin/requests/<id>`. `EmailSender` interface; default **log-only** (local dev, tests); **Resend** HTTP API
(`POST https://api.resend.com/emails`, Bearer `EMAIL_API_KEY`, from `EMAIL_FROM`) when both are set. No SMTP (free Render blocks it). Sent asynchronously after the
request is committed; a mail failure never fails or slows the request; `notified_at` is recorded on success; failures are logged without secrets; everything
user-supplied is HTML-escaped. Env var names only in docs: `EMAIL_API_KEY`, `EMAIL_FROM`, `ADMIN_NOTIFY_TO`, `ADMIN_EMAILS`.

## E. Approval -> live deck
Admin endpoints: list (pending first, filter by status), detail, edit (names / category / description while pending), approve with `visibility` `PRIVATE` or `PUBLIC`,
reject with a note. Approval creates the **not-live** deck (owner = requester, no stats yet) and records `decided_by`.
- **Stage 2a:** admin import of the stats for the deck's cards (JSON/CSV: name, rank, fight, chest, biceps, height inches, weight, brand), validated against the six
  `deck_stats` of the built-in Wrestling deck (copied to the new deck: same keys, units and strategies), unique ranks; then **publish**: the deck gets the visibility the admin chose,
  `live = true`, `published_at` is set. Private decks become readable/playable by their owner only.
- **Stage 2b (optional, needs a paid API key, not built until confirmed):** automatic stat generation behind a `StatGenerator` interface (a fake for tests).

## F. Security / abuse
Per-player rate limit on the new endpoints, input sanitising and length limits, request size limit (64 KB, 2 MB for admin), audit fields (`decided_by`, `decided_at`),
no new PII (the admin sees the requester's username only), admin endpoints covered by tests.

## Stages
1. Plan, changeset 006, user endpoints, admin allow-list + `isAdmin`, admin list/detail/edit/reject/approve, `EmailSender` + notification. Contract in `API.md`.
2. 2a import + publish. 2b only after the user confirms.

---

## Two flows (addendum from the owner)
- **Flow 1, category request** (sections A-F above): a user suggests a category, optionally with names; the admin chooses PRIVATE or GLOBAL; the admin fills in the stats.
- **Flow 2, self-service upload:** a signed-in user uploads a whole CSV of cards (with images) and gets a **private** deck of their own at once, no admin involved. They can then
  raise a "make it global" request (type `PROMOTE`); the admin approves (the deck becomes PUBLIC, the owner keeps ownership) or rejects with a note (it stays private).

### One import pipeline (`DeckImportService`), used twice
CSV text + a **stat mapping** + optional images -> validated cards -> a deck. Flow 2 uses the user's own mapping; the admin import of Flow 1 uses the mapping of the built-in Wrestling deck
(its six stats, copied from `deck_stats`, with tighter ranges). The same code parses the CSV, checks every cell, resolves and re-encodes images and writes the deck.
- **Upload** `POST /api/decks/import` (multipart): `csv`, optional `images` (one ZIP, or several image files), `mapping` (JSON), `name`, `description`; `?dryRun=true` validates and
  returns every row error plus the parsed cards without saving. Template: `GET /api/decks/import/template` (the Wrestling schema as CSV + mapping).
- **Mapping:** per stat column: `label`, `unit`, `higherWins`, `displayFormat` (PLAIN / WITH_UNIT / FEET_INCHES). The CSV needs `name`; optional `image` (an uploaded file name or an
  https URL) and `brand`; every other mapped column is a stat. At most 8 stats. Whole-number columns are INT, the others DECIMAL (at most 2 decimals), found from the data.
- **Rules:** 8-100 cards per user deck, unique names, numbers parse and are within +-1,000,000, ranks unique when a stat `rank` exists. Limits per user: 5 decks, 10 uploads and
  30 previews per hour. Sizes: CSV 1 MB, ZIP 30 MB uncompressed, 2 MB per image, 100 images.
- **Images:** URLs are **never fetched** by the server (no SSRF); an https URL in the CSV is stored as given. Uploaded files are identified by their real content (magic bytes), decoded,
  **re-encoded** (EXIF and anything hidden is dropped) and scaled to at most 800 px, then kept by an `ImageStore` (interface; default Postgres `bytea`, served at the unguessable
  `GET /api/images/{uuid}` with long cache headers; swappable for S3/R2). ZIPs: entry-count and total-size limits, no path traversal (only the base name is used), non-images ignored.
- **Ownership:** the owner reads, replaces and deletes their private decks (`GET /api/decks/mine`, `PUT /api/decks/{id}/import`, `PATCH`, `DELETE`); an admin can read any deck; `GET /api/decks`
  lists public decks plus your own. A game can be created with a deck its creator owns even if it is private; everybody seated in that game reads the deck's cards (and images) for the length of
  the game. Nobody else can read a private deck. A PUBLIC deck cannot be edited (copy it with `POST /api/decks/{id}/copy`), so a promotion review is never bypassed; a deck with an open promotion
  request cannot be edited either.
- **Promotion:** `POST /api/decks/{id}/request-global` (owner, one open request per deck) creates a `deck_request` of type `PROMOTE` for that deck; the admin queue lists both types, the detail view shows the
  deck with cards and images, approve = PUBLIC, reject = stays private with the note. The same email notification goes out for both types. The owner can withdraw a pending request.

### Stages
1. Plan, requests, admin, email (`40d4176`). 2a. Import and publish for requested decks (`20a0888`, fixed Wrestling schema).
2. The generic pipeline, images, user upload + my decks + promotion, admin import through the same pipeline (done). 3. Whatever is left: stage 2b, generated stats, still only after the owner confirms (`StatGenerator` is the interface, there is no implementation).
