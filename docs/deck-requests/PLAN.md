# Deck requests: plan

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
