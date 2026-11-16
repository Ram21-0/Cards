// Talks to the Java backend. Pure mapping (mapDeck) is separated from fetching so it can be unit-tested.
//
// Frontend deck model (what the rest of the app uses):
//   { id, name, description, source, stats: [{ key, label, unit, higherWins }],
//     cards: [{ id, name, img, brand, stats: { key: { value: Number, display: String } } }] }

const config = globalThis.CARDS_CONFIG ?? {};

// Dev convenience: open the app with ?api=http://localhost:9090 to point it at another backend.
const apiOverride = globalThis.location ? new URLSearchParams(globalThis.location.search).get('api') : null;
export const API_BASE = (apiOverride ?? config.apiBase ?? 'http://localhost:8080').replace(/\/$/, '');

/** An error response from the backend ({"error": "..."}); `status` is the HTTP status. */
export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request(path, { method = 'GET', body, form, text, timeoutMs = 5000 } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(API_BASE + path, {
      method,
      signal: ctl.signal,
      credentials: 'include',                       // the session cookie is cross-origin in local dev
      headers: {
        Accept: 'application/json',
        'X-Requested-With': 'cards',                // required by the backend on state-changing requests
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(text != null ? { 'Content-Type': 'text/csv' } : {}),   // a FormData body sets its own multipart boundary
      },
      body: form ?? text ?? (body ? JSON.stringify(body) : undefined),
    });
    if (!res.ok) {
      const detail = await res.json().catch(() => null);
      throw new ApiError(detail?.error ?? `${path}: HTTP ${res.status}`, res.status);
    }
    return res.status === 204 ? null : await res.json();
  } finally {
    clearTimeout(timer);
  }
}

const getJson = (path) => request(path);

/** Card images: `/api/images/<uuid>` (an upload) is relative to the API origin; https URLs and null pass through. */
export const imageUrl = (img, base = API_BASE) => (img ? (img.startsWith('/') ? base + img : img) : null);

/** Public decks: [{ id, name, description, visibility, cardCount, stats }]. */
export const fetchDecks = () => getJson('/api/decks');

/** One deck with its stat definitions and cards, mapped to the frontend model. */
export async function fetchDeck(id) {
  const [deck, cards] = await Promise.all([getJson(`/api/decks/${id}`), getJson(`/api/decks/${id}/cards`)]);
  return mapDeck(deck, cards);
}

/** Deck JSON -> the stat definitions the UI uses, ordered for display. */
export function mapStats(deckJson) {
  return [...deckJson.stats]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((s) => ({ key: s.key, label: s.label, unit: s.unit ?? null, higherWins: s.comparisonStrategy === 'HIGHER_WINS' }));
}

/** One card from the API (REST cards, or a hand in a live `view`) -> frontend card. Stats missing from the card are skipped. */
export function mapCard(c, stats) {
  return {
    id: c.id,
    name: c.name,
    img: imageUrl(c.img),
    brand: c.meta?.brand ?? null,
    stats: Object.fromEntries(stats
      .filter((s) => c.stats?.[s.key])
      .map((s) => [s.key, { value: Number(c.stats[s.key].value), display: c.stats[s.key].displayValue }])),
  };
}

/** API JSON -> frontend deck model. Cards that lack one of the deck's stats are dropped (the engine needs them all). */
export function mapDeck(deckJson, cardsJson) {
  const stats = mapStats(deckJson);
  const cards = cardsJson.map((c) => mapCard(c, stats)).filter((c) => stats.every((s) => s.key in c.stats));

  if (cards.length !== cardsJson.length) {
    console.warn(`${cardsJson.length - cards.length} card(s) skipped: missing one of the deck's stats`);
  }
  return { id: deckJson.id, name: deckJson.name, description: deckJson.description ?? '', source: 'api', stats, cards };
}

/** Just the stat definitions of a deck (for labelling cards in a live game): { id, name, stats }. */
export async function fetchDeckStats(id) {
  const d = await getJson(`/api/decks/${encodeURIComponent(id)}`);
  return { id: d.id, name: d.name, stats: mapStats(d) };
}

/**
 * Creates a game lobby for a deck; the host is the signed-in player (session cookie). Rejects with ApiError 401
 * when not signed in. Resolves
 * { id, joinCode, joinUrl, status, maxPlayers, deck: {id, name}, seats: [{ seat, kind, username, difficulty }] }.
 */
export const createGame = ({ deckId, maxPlayers }) =>
  request('/api/games', { method: 'POST', body: { deckId, maxPlayers } });

/**
 * Takes the lowest free seat in a game (needs sign-in + a username). Idempotent: already seated -> same game again.
 * ApiError 401 (signed out) / 400 (no username yet) / 404 (unknown code) / 409 ("Game is full" / "Game has already started").
 * Resolves the same body as getGame.
 */
export const joinGame = (joinCode) => request(`/api/games/${encodeURIComponent(joinCode)}/join`, { method: 'POST' });

/** Looks a game up by its join code (same shape as createGame). */
export const getGame = (joinCode) => request(`/api/games/${encodeURIComponent(joinCode)}`);

// ---- Sign-in (Google) and the current player. Auth is a session cookie; JS never sees the token. ----
// Me = { id, username (null until chosen), displayName, avatarUrl, email, needsUsername }

/** One-time nonce to hand to Google Identity Services (valid 5 minutes, single use). */
export const fetchNonce = () => request('/api/auth/nonce', { method: 'POST' });

/** Exchanges a Google ID token for a session. Rejects with ApiError 401 (bad token) / 503 (not configured). Resolves Me. */
export const googleSignIn = (credential) => request('/api/auth/google', { method: 'POST', body: { credential } });

/** The signed-in player, or null when signed out (or when the server can't be reached for the check). */
export async function fetchMe() {
  try {
    return await request('/api/me');
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return null;
    throw e;
  }
}

/** Chooses/changes the username (1–8 of a–z 0–9 _). ApiError 400 (invalid) / 409 (taken). Resolves Me. */
export const updateMe = (username) => request('/api/me', { method: 'PUT', body: { username } });

export const signOut = () => request('/api/auth/logout', { method: 'POST' });

/** Deletes the account (the server anonymizes it) and ends the session. */
export const deleteAccount = () => request('/api/me', { method: 'DELETE' });

/** Local development only: signs in without Google. The server answers 404 anywhere else. Resolves Me. */
export const devLogin = (username, { admin = false } = {}) =>
  request(`/api/dev/login${admin ? '?admin=true' : ''}`, { method: 'POST', body: { username } });

// ---- Lobby actions (see app/backend/LIVE.md). Each resolves the updated game body, like getGame. ----
const gamePath = (code) => `/api/games/${encodeURIComponent(code)}`;

/** Host: adds a CPU in the lowest free seat. difficulty: 'easy' | 'medium' | 'hard'. */
export const addCpu = (code, difficulty) => request(`${gamePath(code)}/cpu`, { method: 'POST', body: { difficulty } });

/** Host: removes a CPU seat (human seats cannot be removed). */
export const removeSeat = (code, seat) => request(`${gamePath(code)}/seats/${seat}`, { method: 'DELETE' });

/** Leaves a game that has not started. The host passes to the next human; the last one out closes the game. */
export const leaveLobby = (code) => request(`${gamePath(code)}/leave`, { method: 'POST' });

/** Host: changes a game option while it is waiting or running (only `resultHoldSeconds`, 0 to 20, for now). Resolves the game body. */
export const updateGameOptions = (code, options) => request(`${gamePath(code)}/options`, { method: 'PATCH', body: options });

/** Host: starts the game (needs at least 2 seats); seats are renumbered 1..n. */
export const startLiveGame = (code) => request(`${gamePath(code)}/start`, { method: 'POST' });

// ---- Playing (see LIVE.md "REST: playing"). Both answer with your own `view` (same shape as the socket's view message). ----

/** Call a stat (a stat `key` of the deck): you are the caller, or you are racing in a clash. */
export const callStat = (code, stat) => request(`${gamePath(code)}/call`, { method: 'POST', body: { stat } });

/** CHOOSE: put a card from `view.ownHand` in play (`cardId` is its id). */
export const chooseCard = (code, cardId) => request(`${gamePath(code)}/choose`, { method: 'POST', body: { cardId } });

// ---- Deck requests and decks of your own (docs/deck-requests/API.md). Me also carries `isAdmin`. ----
const enc = encodeURIComponent;

/** Your requests, newest first. Each: { id, type: CATEGORY|PROMOTE, category, description, names, status, adminNote, createdAt, decidedAt, live, deckId }. */
export const fetchMyRequests = () => request('/api/deck-requests');

/** Asks for a new deck category. ApiError 400 (validation) / 409 (3 pending) / 429 (5 a day). */
export const createDeckRequest = ({ category, description, names }) =>
  request('/api/deck-requests', { method: 'POST', body: { category, description, names } });

/** Withdraws one of your own requests while it is PENDING (resolves null). */
export const withdrawRequest = (id) => request(`/api/deck-requests/${enc(id)}`, { method: 'DELETE' });

/** Your decks: [{ id, name, description, visibility, live, cardCount, stats, promotion }]. */
export const fetchMyDecks = () => request('/api/decks/mine');

/** { csv, mapping: { stats }, notes } for a CSV that validates. */
export const fetchImportTemplate = () => request('/api/decks/import/template');

/** The multipart body of an upload. images: File[] (one ZIP and/or image files). */
export function importForm({ csv, mapping, images = [], name, description }) {
  const f = new FormData();
  f.append('csv', csv);
  f.append('mapping', JSON.stringify(mapping));
  for (const img of images) f.append('images', img);
  if (name != null && name !== '') f.append('name', name);
  if (description != null && description !== '') f.append('description', description);
  return f;
}
const UPLOAD_MS = 120000;

/** Checks an upload and saves nothing: { valid, errorCount, errors, stats, cards }. */
export const previewDeck = (input) =>
  request('/api/decks/import?dryRun=true', { method: 'POST', form: importForm(input), timeoutMs: UPLOAD_MS });

/** Saves a new private deck (201, the Deck). ApiError 400 (first problems) / 409 (5 decks) / 429 / 413. */
export const importDeck = (input) =>
  request('/api/decks/import', { method: 'POST', form: importForm(input), timeoutMs: UPLOAD_MS });

/** Replaces the stats and cards of one of your decks. */
export const replaceDeck = (id, input) =>
  request(`/api/decks/${enc(id)}/import`, { method: 'PUT', form: importForm(input), timeoutMs: UPLOAD_MS });

/** Renames / re-describes a deck; a field left out stays. */
export const patchDeck = (id, fields) => request(`/api/decks/${enc(id)}`, { method: 'PATCH', body: fields });

/** Deletes a deck (409 when it was ever used in a game). */
export const deleteDeck = (id) => request(`/api/decks/${enc(id)}`, { method: 'DELETE' });

/** A private copy of any deck you can see. */
export const copyDeck = (id, name) => request(`/api/decks/${enc(id)}/copy`, { method: 'POST', body: name ? { name } : {} });

/** Asks for a private deck to become public: a PROMOTE request. */
export const requestGlobal = (id) => request(`/api/decks/${enc(id)}/request-global`, { method: 'POST' });

// ---- Admin (401 signed out, 403 not an admin) ----

/** status: PENDING | REJECTED | APPROVED_PRIVATE | APPROVED_GLOBAL | ALL. Pending first, then newest. */
export const adminListRequests = (status = 'ALL') => request(`/api/admin/deck-requests?status=${enc(status)}`);
export const adminGetRequest = (id) => request(`/api/admin/deck-requests/${enc(id)}`);
/** Edits a pending request: any of { category, description, names }. */
export const adminEditRequest = (id, fields) => request(`/api/admin/deck-requests/${enc(id)}`, { method: 'PATCH', body: fields });
/** visibility: PRIVATE | PUBLIC (a PROMOTE request accepts only PUBLIC). */
export const adminApprove = (id, { visibility, note }) =>
  request(`/api/admin/deck-requests/${enc(id)}/approve`, { method: 'POST', body: { visibility, note: note || undefined } });
export const adminReject = (id, note) => request(`/api/admin/deck-requests/${enc(id)}/reject`, { method: 'POST', body: { note } });
/** Replaces the cards of the approved request's deck from CSV text. 400 lists the row problems. */
export const adminPutStats = (id, csvText) =>
  request(`/api/admin/deck-requests/${enc(id)}/stats`, { method: 'PUT', text: csvText, timeoutMs: UPLOAD_MS });
export const adminCards = (id) => request(`/api/admin/deck-requests/${enc(id)}/cards`);
export const adminPublish = (id) => request(`/api/admin/deck-requests/${enc(id)}/publish`, { method: 'POST' });
