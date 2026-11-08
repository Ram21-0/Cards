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

async function request(path, { method = 'GET', body, timeoutMs = 5000 } = {}) {
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
      },
      body: body ? JSON.stringify(body) : undefined,
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
    img: c.img ?? null,
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
export const devLogin = (username) => request('/api/dev/login', { method: 'POST', body: { username } });

// ---- Lobby actions (see app/backend/LIVE.md). Each resolves the updated game body, like getGame. ----
const gamePath = (code) => `/api/games/${encodeURIComponent(code)}`;

/** Host: adds a CPU in the lowest free seat. difficulty: 'easy' | 'medium' | 'hard'. */
export const addCpu = (code, difficulty) => request(`${gamePath(code)}/cpu`, { method: 'POST', body: { difficulty } });

/** Host: removes a CPU seat (human seats cannot be removed). */
export const removeSeat = (code, seat) => request(`${gamePath(code)}/seats/${seat}`, { method: 'DELETE' });

/** Leaves a game that has not started. The host passes to the next human; the last one out closes the game. */
export const leaveLobby = (code) => request(`${gamePath(code)}/leave`, { method: 'POST' });

/** Host: starts the game (needs at least 2 seats); seats are renumbered 1..n. */
export const startLiveGame = (code) => request(`${gamePath(code)}/start`, { method: 'POST' });

// ---- Playing (see LIVE.md "REST: playing"). Both answer with your own `view` (same shape as the socket's view message). ----

/** Call a stat (a stat `key` of the deck): you are the caller, or you are racing in a clash. */
export const callStat = (code, stat) => request(`${gamePath(code)}/call`, { method: 'POST', body: { stat } });

/** CHOOSE: put a card from `view.ownHand` in play (`cardId` is its id). */
export const chooseCard = (code, cardId) => request(`${gamePath(code)}/choose`, { method: 'POST', body: { cardId } });
