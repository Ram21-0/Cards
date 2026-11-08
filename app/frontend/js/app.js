import { Game, RESULT, DIFFICULTY } from './engine.js';
import {
  wsUrl, createLiveClient, initialLive, applyMessage, seatName, seatRows, phaseInfo, secondsLeft,
  eventText, callCard, clashInfo, overSummary, overFromGame, ordinal,
} from './live.js';
import { parseRoute, toHash, paths, backDecision, needsUnloadGuard, redirectFor, upAction } from './nav.js';
import {
  API_BASE, ApiError, fetchDecks, fetchDeck, createGame, getGame, joinGame, addCpu, removeSeat, leaveLobby, startLiveGame, fetchDeckStats, mapCard, callStat, chooseCard,
  fetchNonce, googleSignIn, fetchMe, updateMe, signOut, deleteAccount, devLogin,
} from './api.js';
import { offlineDeck } from './offline.js';

const app = document.getElementById('app');
const PLACEHOLDER = 'assets/placeholder.png';
const SPLASH_MS = 1400;

let game = null;
let deck = null;    // the deck being played: { id, name, source, stats, cards } (see api.js)
let decks = [];     // public decks the backend offers (empty when offline)
let me = null;      // the signed-in player ({ id, username, displayName, avatarUrl, email, needsUsername }) or null
let screenId = 0; // bumps on every screen change so stale timers can bail out
let difficulty = store('difficulty') || DIFFICULTY.NORMAL;

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    localStorage.setItem(key, value);
  } catch { return null; }
}

/* ---------- tiny DOM helper ---------- */
const el = (tag, props = {}, ...kids) => {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2), v);
    else n.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) n.append(kid?.nodeType ? kid : document.createTextNode(kid ?? ''));
  return n;
};
const show = (...nodes) => { screenId++; app.replaceChildren(...nodes); window.scrollTo(0, 0); };
const screen = (cls, ...kids) => el('section', { class: `screen ${cls}` }, ...kids);

function toast(msg) {
  const t = el('div', { class: 'toast', role: 'status' }, msg);
  document.body.append(t);
  setTimeout(() => t.remove(), 2000);
}

/* ---------- card (layout/card.xml) ---------- */
/** `opts.highlight`: a stat key to mark (the stat in play). */
function cardView(w, d = deck, opts = {}) {
  const img = el('img', { src: w.img || PLACEHOLDER, alt: w.name, draggable: 'false' });
  img.addEventListener('error', () => { img.src = PLACEHOLDER; }, { once: true });
  return el('article', { class: `card ${w.brand === 'sd' ? 'sd' : 'raw'}` },
    el('div', { class: 'image' }, img),
    el('h2', { class: 'name' }, w.name),
    el('div', { class: 'grid', style: `--cols:${d.stats.length}` },
      d.stats.map((s) => el('span', { class: `label${opts.highlight === s.key ? ' hl' : ''}` }, s.label)),
      d.stats.map((s) => el('span', { class: `value${opts.highlight === s.key ? ' hl' : ''}` }, w.stats[s.key]?.display ?? '–'))));
}

/* ---------- boot ---------- */
async function boot() {
  show(screen('center', el('h1', { class: 'logo' }, 'CARDS'), el('p', { class: 'tagline' }, 'Are you ready?')));
  await Promise.all([loadDecks().then(loadMe), new Promise((r) => setTimeout(r, SPLASH_MS))]);

  // A shared link (?join=CODE) opens that game's lobby. Turn it into a normal route and keep other params (e.g. ?api=).
  const url = new URL(location.href);
  const code = url.searchParams.get('join');
  if (code) {
    url.searchParams.delete('join');
    history.replaceState({ idx: 0, from: null }, '', `${url.pathname}${url.search}${toHash(paths.lobby(code.trim()))}`);
  } else if (history.state?.idx === undefined) {
    history.replaceState({ idx: 0, from: null }, '');
  }
  render();
}

/** Asks the server who is signed in (the session cookie decides). Anonymous or unreachable -> null. */
async function loadMe() {
  me = deck?.source === 'api' ? await fetchMe().catch(() => null) : null;
}

/** Loads the deck list + the chosen deck from the backend; falls back to the bundled cards if it is unreachable. */
async function loadDecks() {
  try {
    decks = await fetchDecks();
    if (!decks.length) throw new Error('backend has no public decks');
    const saved = store('deckId');
    await selectDeck((decks.find((d) => d.id === saved) ?? decks[0]).id);
  } catch (e) {
    console.warn('Backend unavailable, using bundled cards:', e.message);
    decks = [];
    deck = offlineDeck(window.CARDS);
  }
}

/** Switches the active deck (keeps the current one if loading fails). */
async function selectDeck(id) {
  deck = await fetchDeck(id);
  store('deckId', id);
}

/* ---------- router: hash routes, Back button, leave-game guard ---------- */
let current = parseRoute(location.hash);   // the route on screen
let afterAuth = null;                      // path to return to once sign-in / username is done
const gameCache = new Map();               // code -> game body we already have (avoids a refetch right after create/join)

const histState = () => history.state ?? {};
const isGameActive = () => Boolean(game) && !game.over;

/** Go to a screen: a new history entry (Back returns here), or `replace` to swap the current entry. */
function nav(path, { replace = false } = {}) {
  const idx = histState().idx ?? 0;
  if (replace) history.replaceState({ idx, from: histState().from ?? null }, '', toHash(path));
  else history.pushState({ idx: idx + 1, from: toHash(current.path) }, '', toHash(path));
  render();
}

/**
 * A lobby opened from a shared link is the first history entry of the visit, so Back would leave the page (no confirm,
 * and a seat you hold stays taken). Put the gallery underneath it so Back stays inside the app and meets the guard.
 */
function ensureParentEntry() {
  if ((histState().idx ?? 0) > 0) return;
  history.replaceState({ idx: 0, from: null }, '', toHash(paths.decks()));
  history.pushState({ idx: 1, from: toHash(paths.decks()) }, '', toHash(current.path));
}

/** Back out of a screen to its parent without growing the history. */
function goUp(parent) {
  if (upAction(history.state, parent) === 'back') history.back();      // the popstate handler renders
  else nav(parent, { replace: true });
}

/** A plain "Back" button: the previous screen if there is one inside the app, otherwise the menu. */
function goBack() {
  if ((histState().idx ?? 0) > 0) history.back();
  else nav(paths.menu(), { replace: true });
}

function render() {
  const route = parseRoute(location.hash);
  const redirect = redirectFor(route, { hasGame: Boolean(game) });
  if (redirect) { history.replaceState({ idx: histState().idx ?? 0, from: histState().from ?? null }, '', toHash(redirect)); return render(); }
  closeLive();                                                          // a lobby/live screen re-opens its own connection
  current = route;
  if (route.name !== 'play' && game?.over) game = null;                 // a finished game is not kept around
  switch (route.name) {
    case 'decks': return gallery();
    case 'deck': return openDeck(route.params.id);
    case 'card': return openCard(route.params.id, route.params.index);
    case 'lobby': return lobbyRoute(route.params.code);
    case 'play': return nextRound();
    case 'signin': return signInScreen();
    case 'username': return usernameScreen();
    case 'account': return account();
    default: return menu();
  }
}

// Browser Back / Forward / phone Back gesture. Leaving a running game asks first.
window.addEventListener('popstate', () => {
  const to = parseRoute(location.hash);
  if (backDecision({ from: current, to, gameActive: isGameActive(), seated: isSeated() }) === 'confirm') {
    // Undo the Back: put the game's/lobby's entry back on top and ask. The screen was never re-rendered, so nothing is lost.
    history.pushState({ idx: (histState().idx ?? 0) + 1, from: location.hash || toHash(paths.menu()) }, '', toHash(current.path));
    askLeave();
    return;
  }
  render();
});

// Accidental reload / tab close while a game is on screen.
window.addEventListener('beforeunload', (e) => {
  if (needsUnloadGuard({ route: current, gameActive: isGameActive() })) { e.preventDefault(); e.returnValue = ''; }
});

let leaving = null;   // the open "Leave this game?" dialog, if any
/** The one path for leaving a game (Back button, gesture and the Quit button): confirm first. */
function askLeave() {
  leaving ??= confirmDialog(leaveDialog())
    .then((leave) => { leaving = null; if (leave) leaveGame(); });
  return leaving;
}

/** Wording of the "are you sure?" dialog for whatever is on screen. */
function leaveDialog() {
  const g = live?.game;
  if (live && g?.status === 'IN_PROGRESS') {
    return { title: 'Leave this game?', text: `The game keeps going without you. You stay seated and can come back with the code ${g.joinCode} while it is running.`, stay: 'Stay', leave: 'Leave' };
  }
  if (live) {
    const host = live.mySeat === g.hostSeat;
    return { title: 'Leave this lobby?', text: host ? 'You are the host: hosting passes to the next player, and the game closes if nobody else is here.' : 'You give up your seat.', stay: 'Stay', leave: 'Leave' };
  }
  return { title: 'Leave this game?', text: 'The game ends and your cards are lost.', stay: 'Stay', leave: 'Leave' };
}

/**
 * The single place a game session ends. A live multiplayer game will also tell the server here
 * (POST /api/games/{code}/leave — see app/backend/LIVE.md); until that exists it only ends the local game.
 */
async function leaveGame() {
  const ctx = live;
  if (ctx) {                                          // a lobby or a live table
    const code = ctx.code;
    const waiting = ctx.game.status === 'WAITING' && ctx.mySeat != null;
    closeLive();                                      // stop the socket first so it does not reconnect
    if (waiting) {                                    // a running game cannot be left on the server yet: we just disconnect
      try { await leaveLobby(code); } catch (e) { if (!(e instanceof ApiError && e.status === 409)) toast(e instanceof ApiError ? e.message : 'Could not reach the server'); }
    }
    return goUp(paths.decks());
  }
  game = null;                                        // a solo game
  goUp(paths.menu());
}

/** Modal "are you sure?" — resolves true for the leave/confirm button, false for Stay, Escape or a click outside. */
function confirmDialog({ title, text, stay, leave }) {
  return new Promise((resolve) => {
    const before = document.activeElement;
    const close = (answer) => { document.removeEventListener('keydown', onKey, true); overlay.remove(); app.inert = false; before?.focus?.(); resolve(answer); };
    const onKey = (e) => { if (e.key === 'Escape') { e.preventDefault(); close(false); } };
    const stayBtn = el('button', { class: 'btn primary', onclick: () => close(false) }, stay);
    const dialog = el('div', { class: 'dialog', role: 'alertdialog', 'aria-modal': 'true', 'aria-labelledby': 'dlg-title', 'aria-describedby': 'dlg-text' },
      el('h2', { id: 'dlg-title' }, title),
      el('p', { id: 'dlg-text' }, text),
      el('div', { class: 'btn-row' }, stayBtn, el('button', { class: 'btn danger', onclick: () => close(true) }, leave)));
    const overlay = el('div', { class: 'overlay', onclick: (e) => { if (e.target === overlay) close(false); } }, dialog);
    document.addEventListener('keydown', onKey, true);
    document.body.append(overlay);
    app.inert = true;                                  // nothing behind the dialog can be clicked or tabbed to
    stayBtn.focus();
  });
}

/* ---------- menu ---------- */
function menu() {
  const seg = el('div', { class: 'segmented', role: 'group', 'aria-label': 'CPU difficulty' },
    Object.values(DIFFICULTY).map((d) => el('button', {
      'aria-pressed': String(d === difficulty),
      onclick: (e) => {
        difficulty = d; store('difficulty', d);
        seg.querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(b === e.currentTarget)));
      },
    }, d[0].toUpperCase() + d.slice(1))));
  const deckPicker = decks.length > 1
    ? el('label', { class: 'row' }, el('span', {}, 'Deck'),
      el('select', { class: 'field', 'aria-label': 'Deck', onchange: changeDeck },
        decks.map((d) => el('option', { value: d.id, selected: d.id === deck.id }, d.name))))
    : el('p', { class: 'whoami' }, `Deck: ${deck.name}`);
  const offlineNotice = deck.source === 'offline'
    ? el('div', { class: 'notice' }, 'Backend unreachable — using the bundled cards. ',
      el('button', { class: 'btn small', onclick: retryBackend }, 'Retry'))
    : '';
  show(screen('center menu',
    el('h1', { class: 'logo' }, 'CARDS'),
    el('p', { class: 'tagline' }, 'Pick a stat, beat the CPU’s card, win every card.'),
    offlineNotice,
    el('button', { class: 'btn primary wide', onclick: startGame }, 'Play'),
    el('button', { class: 'btn wide', onclick: () => nav(paths.decks()) }, 'Gallery'),
    el('button', { class: 'btn wide', onclick: () => (me ? nav(paths.account()) : signIn()) }, me ? 'Account' : 'Sign in'),
    deckPicker,
    el('div', { class: 'row' }, el('span', {}, 'CPU difficulty'), seg),
    me ? el('p', { class: 'whoami' }, `Signed in as ${me.username ?? me.displayName ?? 'player'}`) : ''));
}

async function changeDeck(e) {
  try {
    await selectDeck(e.target.value);
    toast(`Deck: ${deck.name}`);
  } catch {
    toast('Could not load that deck');
    e.target.value = deck.id;
  }
}

async function retryBackend() {
  toast('Connecting…');
  await loadDecks();
  await loadMe();
  render();
}

/* ---------- gallery: the list of public decks ---------- */
const deckCache = new Map();   // id -> full deck model (stats + cards)

async function loadFullDeck(id) {
  if (!deckCache.has(id)) deckCache.set(id, await fetchDeck(id));
  return deckCache.get(id);
}

function gallery() {
  const offline = decks.length === 0;
  const tiles = offline
    ? [{ id: 'offline', name: deck.name, description: 'Bundled cards (backend unreachable)', cardCount: deck.cards.length, stats: deck.stats }]
    : decks;
  show(screen('',
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: () => goUp(paths.menu()) }, '← Menu'), el('h2', {}, `Decks · ${tiles.length}`), el('span')),
    offline ? el('div', { class: 'notice' }, 'Offline — creating games needs the server.') : '',
    el('div', { class: 'deck-list' }, tiles.map((d) =>
      el('button', { class: 'deck-tile', onclick: () => nav(paths.deck(d.id)) },
        el('h3', {}, d.name),
        d.description ? el('p', {}, d.description) : '',
        el('div', { class: 'chips' }, el('span', { class: 'chip strong' }, `${d.cardCount} cards`), d.stats.map((s) => el('span', { class: 'chip' }, s.label))))))));
}

/** A full deck (stats + cards) by id; the offline deck has the id 'offline'. */
async function fullDeck(id) {
  if (id === 'offline' && deck?.source === 'offline') return deck;
  return loadFullDeck(id);
}

async function openDeck(id) {
  show(screen('center', el('p', { class: 'tagline' }, 'Loading deck…')));
  try {
    deckScreen(await fullDeck(id));
  } catch {
    toast('Could not load that deck');
    nav(paths.decks(), { replace: true });
  }
}

async function openCard(id, index) {
  try {
    const d = await fullDeck(id);
    if (index >= d.cards.length) return nav(paths.deck(id), { replace: true });
    viewCard(index, d);
  } catch {
    toast('Could not load that deck');
    nav(paths.decks(), { replace: true });
  }
}

/** One deck: the "create a game" panel on top, its cards below. */
function deckScreen(d, filter = 'all') {
  const brands = [...new Set(d.cards.map((c) => c.brand).filter(Boolean))];
  const list = d.cards.filter((c) => filter === 'all' || c.brand === filter);
  const chip = (id, label) => el('button', { class: 'btn small ghost', 'aria-pressed': String(filter === id), onclick: () => deckScreen(d, id),
    style: filter === id ? 'border-color:var(--accent);color:var(--accent)' : '' }, label);
  show(screen('',
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: () => goUp(paths.decks()) }, '← Decks'), el('h2', {}, d.name), el('span')),
    createPanel(d),
    el('h3', { class: 'section' }, `Cards · ${list.length}`),
    brands.length > 1 ? el('div', { class: 'filters' }, chip('all', 'All'), brands.map((b) => chip(b, b.toUpperCase()))) : '',
    el('div', { class: 'gallery' }, list.map((w) =>
      el('button', { class: 'cell', 'aria-label': w.name, onclick: () => nav(paths.card(d.id, d.cards.indexOf(w))) }, cardView(w, d))))));
}

function viewCard(i, d) {
  const n = d.cards.length;
  show(screen('',
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: () => goUp(paths.deck(d.id)) }, `← ${d.name}`), el('h2', {}, `${i + 1} / ${n}`), el('span')),
    el('div', { class: 'card-wrap' }, cardView(d.cards[i], d)),
    el('div', { class: 'viewer-nav' },
      el('button', { class: 'btn', onclick: () => nav(paths.card(d.id, (i + n - 1) % n), { replace: true }) }, '← Prev'),
      el('button', { class: 'btn', onclick: () => nav(paths.card(d.id, (i + 1) % n), { replace: true }) }, 'Next →'))));
}

/* ---------- create a game ---------- */
const MAX_PLAYERS = 8;

function createPanel(d) {
  const online = d.source !== 'offline';
  const most = Math.min(MAX_PLAYERS, d.cards.length);          // every player needs at least one card
  let players = Math.min(Math.max(Number(store('players')) || 2, 2), Math.max(most, 2));

  const seg = el('div', { class: 'segmented compact', role: 'group', 'aria-label': 'Number of players' },
    Array.from({ length: MAX_PLAYERS - 1 }, (_, i) => i + 2).map((n) => el('button', {
      'aria-pressed': String(n === players),
      disabled: n > most,
      onclick: (e) => {
        players = n; store('players', String(n));
        seg.querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(b === e.currentTarget)));
      },
    }, String(n))));
  const create = el('button', { class: 'btn primary wide', disabled: !online || most < 2, onclick: () => createGameFlow(d, players, create) }, 'Create game');

  const hint = !online ? 'Creating a game needs the server.'
    : most < 2 ? 'This deck needs at least 2 cards to play.'
    : `Up to ${most} players with this deck (${d.cards.length} cards). You get a code to share.`;
  return el('section', { class: 'panel' },
    el('h3', {}, 'Create a game'),
    el('div', { class: 'row' }, el('span', {}, 'Players'), seg),
    el('p', { class: 'hint' }, hint),
    create);
}

async function createGameFlow(d, players, button) {
  if (!me || me.needsUsername) {                   // hosting needs an account with a username
    toast(me ? 'Choose a username first' : 'Sign in to create a game');
    return requireIdentity(paths.deck(d.id));
  }
  button.disabled = true;
  button.textContent = 'Creating…';
  try {
    const g = await createGame({ deckId: d.id, maxPlayers: players });
    gameCache.set(g.joinCode, g);
    nav(paths.lobby(g.joinCode));
  } catch (e) {
    button.disabled = false;
    button.textContent = 'Create game';
    if (e instanceof ApiError && e.status === 401) {   // the session expired or was revoked
      me = null;
      toast('Your session expired — sign in again');
      return signIn(paths.deck(d.id));
    }
    if (e instanceof ApiError && e.status === 400 && /username/i.test(e.message)) {
      toast(e.message);
      return chooseUsername(paths.deck(d.id));
    }
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');
  }
}

/* ---------- lobby: the join code to share ---------- */
async function copyText(text, what) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const ta = el('textarea', { style: 'position:fixed;opacity:0' }, text);   // fallback for non-secure origins
    document.body.append(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
  }
  toast(`${what} copied`);
}

/* ---------- lobby + live game ---------- */
// One screen (#/lobby/<code>) shows the lobby while the game is WAITING and the live table once it has started.
// Seated players get updates over a WebSocket (js/live.js); everyone else, and anyone whose socket is down, polls GET /api/games/{code}.

let live = null;   // the open lobby / live screen: { code, game, mySeat, view, rt, conn, deck, busy, level, client, ticker, paint }

function closeLive() {
  if (!live) return;
  live.client?.close();
  clearInterval(live.ticker);
  live = null;
}

/** Do I hold a seat in the lobby / game on screen? (What the Back guard asks.) */
const isSeated = () => Boolean(live) && live.mySeat != null && ['WAITING', 'IN_PROGRESS'].includes(live.game.status);
const inTable = (g) => g.status === 'IN_PROGRESS' || g.status === 'FINISHED';

/** My seat number according to the REST body (matched by username), or null. */
const seatOfMe = (g) => (me?.username ? g.seats.find((s) => s.kind === 'HUMAN' && s.username === me.username)?.seat ?? null : null);

const seatLabel = (g, s, mine) => `${seatName(g, s.seat)}${s.seat === g.hostSeat ? ' · host' : ''}${mine ? ' · you' : ''}`;
const FATAL_TEXT = {
  'signed-out': 'You are signed out — sign in again to rejoin the table',
  'not-seated': 'You do not have a seat in this game',
  'not-found': 'This game no longer exists',
};

/** Route handler: open a game by join code (shared link, or right after create). */
async function lobbyRoute(code) {
  ensureParentEntry();
  const cached = gameCache.get(code);
  gameCache.delete(code);
  if (cached) return lobby(cached);
  show(screen('center', el('p', { class: 'tagline' }, 'Opening lobby…')));
  try {
    lobby(await getGame(code));
  } catch (e) {
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');
    nav(paths.menu(), { replace: true });
  }
}

function lobby(g) {
  closeLive();
  const ctx = {
    code: g.joinCode, game: g, mySeat: seatOfMe(g), view: null, rt: initialLive(), conn: 'idle', deck: null,
    level: store('cpuLevel') || 'medium', busy: false, pending: false, pick: null, client: null, ticker: null,
  };
  live = ctx;
  const root = screen('');
  show(root);
  const id = screenId;
  ctx.paint = () => {
    if (live === ctx) root.replaceChildren(...(inTable(ctx.game) ? liveScreen(ctx) : lobbyScreen(ctx)));
  };
  ctx.tick = () => {
    const left = secondsLeft(ctx.view?.deadline, Date.now(), ctx.rt.skewMs);
    const node = root.querySelector('.countdown');
    if (node) node.textContent = left == null ? '' : `${left}s`;
  };
  ctx.ticker = setInterval(ctx.tick, 500);

  setGame(ctx, g);
  pollLoop(ctx, id);
}

/** Takes a game body (REST or socket): updates what is shown, opens the socket once we hold a seat, loads the deck for the table. */
function setGame(ctx, g) {
  if (live !== ctx) return;
  ctx.game = g;
  ctx.mySeat = seatOfMe(g) ?? (ctx.client ? ctx.rt.mySeat : null);
  if (g.status === 'ABANDONED') {
    toast('This game was closed');
    closeLive();
    return goUp(paths.decks());
  }
  if (g.status === 'FINISHED' && ctx.client) {                  // nothing more will be pushed
    ctx.client.close();
    ctx.client = null;
    ctx.conn = 'idle';
  }
  if (ctx.mySeat != null && !ctx.client && ['WAITING', 'IN_PROGRESS'].includes(g.status)) startSocket(ctx);
  if (inTable(g) && !ctx.deck) {
    fetchDeckStats(g.deck.id).then((d) => { ctx.deck = d; ctx.paint(); }).catch(() => {});
  }
  ctx.paint();
}

function startSocket(ctx) {
  ctx.client = createLiveClient({
    url: wsUrl({ apiBase: API_BASE, location, code: ctx.code }),
    probe: () => probeSocket(ctx.code),
    onStatus: (s) => { if (live === ctx) { ctx.conn = s; ctx.paint(); } },
    onMessage: (msg) => onLiveMessage(ctx, msg),
    onFatal: (reason) => {
      if (live !== ctx) return;
      toast(FATAL_TEXT[reason] ?? 'Lost the connection');
      if (reason === 'signed-out') {                          // the session is gone: sign in again, then come back to this table
        me = null;
        closeLive();
        return signIn(paths.lobby(ctx.code));
      }
      ctx.conn = 'closed';
      ctx.paint();
    },
  });
  ctx.client.start();
}

/** After a refused connection: why? (Browsers hide the handshake status.) null = probably transient, keep retrying. */
async function probeSocket(code) {
  try {
    const m = await fetchMe();
    if (!m) return 'signed-out';
    const g = await getGame(code);
    return g.seats.some((s) => s.kind === 'HUMAN' && s.username === m.username) ? null : 'not-seated';
  } catch (e) {
    return e instanceof ApiError && e.status === 404 ? 'not-found' : null;
  }
}

function onLiveMessage(ctx, msg) {
  if (live !== ctx) return;
  const had = ctx.mySeat != null;
  ctx.rt = applyMessage(ctx.rt, msg, Date.now());
  if (msg.type === 'view') {
    ctx.view = ctx.rt.view;
    if (ctx.pick != null && !(ctx.view.canChoose && ctx.view.ownHand.some((c) => c.id === ctx.pick))) ctx.pick = null;   // the choice is over
    return ctx.paint();
  }
  if (msg.type === 'events') return ctx.paint();
  if (msg.type === 'gameOver') {
    ctx.mySeat = ctx.rt.mySeat;
    return setGame(ctx, ctx.rt.game);
  }
  if (msg.type !== 'lobby' && msg.type !== 'started') return;
  if (ctx.rt.mySeat == null && had) {                          // we were taken off the seat list
    toast('You are no longer in this game');
    closeLive();
    return goUp(paths.decks());
  }
  if (msg.type === 'started') toast('The game has started!');
  ctx.mySeat = ctx.rt.mySeat;
  setGame(ctx, ctx.rt.game);
}

/** Fallback while there is no live socket (visitors, reconnecting, old server): refresh over REST every 4 s. */
async function pollLoop(ctx, id) {
  await new Promise((r) => setTimeout(r, 4000));
  if (screenId !== id || live !== ctx) return;
  if (!ctx.client?.isOpen()) {
    try {
      const fresh = await getGame(ctx.code);
      if (live === ctx && JSON.stringify(fresh) !== JSON.stringify(ctx.game)) setGame(ctx, fresh);
    } catch { /* keep showing what we have */ }
  }
  pollLoop(ctx, id);
}

/** Runs a lobby action (join, add CPU, start …): one at a time, shows the server's answer, explains refusals. */
async function act(ctx, fn) {
  if (ctx.busy || live !== ctx) return;
  ctx.busy = true;
  ctx.paint();
  try {
    const g = await fn();
    if (live === ctx) setGame(ctx, g);
  } catch (e) {
    if (live !== ctx) return;
    if (e instanceof ApiError && e.status === 401) {
      me = null;
      toast('Your session expired — sign in again');
      closeLive();
      return signIn(paths.lobby(ctx.code));
    }
    if (e instanceof ApiError && e.status === 400 && /username/i.test(e.message)) {
      toast(e.message);
      closeLive();
      return chooseUsername(paths.lobby(ctx.code));
    }
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');
    if (e instanceof ApiError && e.status >= 400 && e.status < 500) {          // refused: show what the game really looks like
      try { setGame(ctx, await getGame(ctx.code)); } catch { /* keep this screen */ }
    }
  } finally {
    if (live === ctx) { ctx.busy = false; ctx.paint(); }
  }
}

const CONN_TEXT = { open: '● Live', connecting: 'Connecting…', reconnecting: 'Reconnecting…', closed: 'Offline' };
function connChip(ctx) {
  if (ctx.mySeat == null || ctx.conn === 'idle') return el('span');
  return el('span', { class: `conn ${ctx.conn}`, role: 'status' }, CONN_TEXT[ctx.conn] ?? '');
}

/** The ← / ✕ button: leaving asks first if you hold a seat. */
const leaveButton = (ctx, label) => el('button', { class: 'btn small', onclick: () => (isSeated() ? askLeave() : goUp(paths.decks())) }, label);

function lobbyScreen(ctx) {
  const g = ctx.game;
  const host = ctx.mySeat != null && ctx.mySeat === g.hostSeat;
  const seated = ctx.mySeat != null;
  const full = g.seats.length >= g.maxPlayers;
  const link = g.joinUrl ?? `${location.origin}${location.pathname}?join=${g.joinCode}`;

  const seats = el('ol', { class: 'seats', 'aria-live': 'polite' },
    Array.from({ length: g.maxPlayers }, (_, i) => {
      const s = g.seats.find((x) => x.seat === i + 1);
      const mine = s && s.seat === ctx.mySeat;
      return el('li', { class: `${s ? 'taken' : 'open'}${mine ? ' mine' : ''}` },
        el('span', { class: 'seat-no' }, String(i + 1)),
        el('span', { class: 'seat-name' }, s ? seatLabel(g, s, mine) : 'Waiting for a player…'),
        host && s?.kind === 'CPU'
          ? el('button', { class: 'seat-x', 'aria-label': `Remove the CPU in seat ${s.seat}`, disabled: ctx.busy, onclick: () => act(ctx, () => removeSeat(ctx.code, s.seat)) }, '✕')
          : '');
    }));

  const level = el('div', { class: 'segmented compact', role: 'group', 'aria-label': 'CPU difficulty' },
    ['easy', 'medium', 'hard'].map((d) => el('button', {
      'aria-pressed': String(ctx.level === d),
      onclick: () => { ctx.level = d; store('cpuLevel', d); ctx.paint(); },
    }, d[0].toUpperCase() + d.slice(1))));

  const controls = host
    ? el('section', { class: 'panel' },
      el('h3', {}, 'Add a computer player'),
      el('div', { class: 'row' }, el('span', {}, 'Difficulty'), level),
      el('button', { class: 'btn', disabled: full || ctx.busy, onclick: () => act(ctx, () => addCpu(ctx.code, ctx.level)) }, full ? 'Table is full' : 'Add CPU'),
      el('button', { class: 'btn primary wide', disabled: g.seats.length < 2 || ctx.busy, onclick: () => act(ctx, () => startLiveGame(ctx.code)) }, 'Start game'),
      el('p', { class: 'hint' }, g.seats.length < 2 ? 'You need at least 2 players to start — add a CPU to play right now.' : 'Ready when you are. Nobody can join after the start.'))
    : '';

  const join = el('button', { class: 'btn primary wide', disabled: ctx.busy, onclick: () => act(ctx, () => joinGame(ctx.code)) }, 'Join game');
  const action = seated
    ? el('div', { class: 'lobby-action' },
      host ? '' : el('p', { class: 'hint' }, `You are in seat ${ctx.mySeat}. Waiting for the host to start the game.`),
      el('button', { class: 'btn ghost', onclick: askLeave }, 'Leave game'))
    : g.status !== 'WAITING' ? el('p', { class: 'hint' }, g.status === 'IN_PROGRESS' ? 'This game has already started.' : 'This game is over.')
    : !me ? el('button', { class: 'btn primary wide', onclick: () => signIn(paths.lobby(g.joinCode)) }, 'Sign in to join')
    : full ? el('p', { class: 'hint' }, 'This game is full.')
    : join;

  return [
    el('div', { class: 'topbar' }, leaveButton(ctx, '← Decks'), el('h2', {}, 'Game lobby'), connChip(ctx)),
    el('section', { class: 'panel lobby' },
      el('p', { class: 'tagline' }, host ? 'Share this code so others can join' : `Join code for ${g.deck.name}`),
      el('div', { class: 'join-code', 'aria-label': `Join code ${g.joinCode.split('').join(' ')}` }, g.joinCode),
      el('p', { class: 'link' }, link),
      el('div', { class: 'btn-row' },
        el('button', { class: 'btn', onclick: () => copyText(g.joinCode, 'Code') }, 'Copy code'),
        el('button', { class: 'btn', onclick: () => copyText(link, 'Link') }, 'Copy link'),
        navigator.share ? el('button', { class: 'btn primary', onclick: () => navigator.share({ title: 'Join my Cards game', text: `Join my ${g.deck.name} game with code ${g.joinCode}`, url: link }).catch(() => {}) }, 'Share') : '')),
    el('h3', { class: 'section' }, `Players · ${g.seats.length} / ${g.maxPlayers}`),
    seats,
    controls,
    action,
    el('p', { class: 'hint' }, `${g.deck.name} · up to ${g.maxPlayers} players`),
  ];
}

/** You see your whole hand only when CHOOSE lets you play any card; otherwise just the card on top of your pile. */
function handTitle(v, mySeat) {
  const total = v.handSizes?.[mySeat] ?? v.ownHand.length;
  const visible = v.ownHand.length + (v.committed ? 1 : 0);
  return visible >= total ? `Your hand · ${v.ownHand.length}` : `Your top card · ${total} in your pile`;
}

/** Runs a move (call a stat / choose a card): one at a time; the server's answer is our new view, refusals are explained. */
async function play(ctx, fn) {
  if (ctx.pending || live !== ctx) return;
  ctx.pending = true;
  ctx.paint();
  try {
    const v = await fn();
    if (live === ctx) {
      ctx.rt = applyMessage(ctx.rt, { ...v, type: 'view' }, Date.now());   // an older push never wins over a newer view
      ctx.view = ctx.rt.view;
      ctx.pick = null;
    }
  } catch (e) {
    if (live !== ctx) return;
    if (e instanceof ApiError && e.status === 401) {
      me = null;
      toast('Your session expired — sign in again');
      closeLive();
      return signIn(paths.lobby(ctx.code));
    }
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');   // e.g. "too late: the stat was already set"
  } finally {
    if (live === ctx) { ctx.pending = false; ctx.paint(); }
  }
}

const statLabelOf = (ctx) => (key) => ctx.deck?.stats.find((s) => s.key === key)?.label ?? key;

/** Your turn to call: your card, and one button per stat showing its value. In a clash the first call wins. */
function callPanel(ctx, clash) {
  const v = ctx.view;
  const c = callCard(v);
  if (!c || !ctx.deck) return '';
  const card = mapCard(c, ctx.deck.stats);
  return el('section', { class: 'panel callpanel' },
    el('h3', {}, clash ? 'Race: call a stat first!' : 'Your turn: call a stat'),
    el('div', { class: 'card-wrap' }, cardView(card, ctx.deck)),
    el('div', { class: 'stat-grid' }, ctx.deck.stats.map((s) =>
      el('button', { class: 'stat-btn', disabled: ctx.pending, onclick: () => play(ctx, () => callStat(ctx.code, s.key)) },
        el('span', {}, s.label), el('b', {}, card.stats[s.key]?.display ?? '–')))));
}

/** CHOOSE: you may put any card from your hand in play. Tap a card, then confirm; or just play your top card. */
function choosePanel(ctx) {
  const v = ctx.view;
  if (!ctx.deck) return '';
  const hand = v.ownHand.map((c) => mapCard(c, ctx.deck.stats));
  const stat = v.stat ? statLabelOf(ctx)(v.stat) : null;
  const picked = hand.find((c) => c.id === ctx.pick);
  return el('section', { class: 'panel choosepanel' },
    el('h3', {}, stat ? `Choose a card to play · ${stat}` : 'Choose a card to play'),
    el('p', { class: 'hint' }, stat ? `The stat is ${stat}: pick the card that does best.` : 'The stat is not known yet. Pick a card, then you call the stat.'),
    el('div', { class: 'choose-hand' }, hand.map((c) =>
      el('button', { class: 'choose-card', 'aria-pressed': String(c.id === ctx.pick), 'aria-label': `Play ${c.name}`, disabled: ctx.pending,
        onclick: () => { ctx.pick = c.id; ctx.paint(); } }, cardView(c, ctx.deck, { highlight: v.stat })))),
    el('div', { class: 'btn-row' },
      el('button', { class: 'btn primary', disabled: !picked || ctx.pending, onclick: () => play(ctx, () => chooseCard(ctx.code, picked.id)) }, picked ? `Play ${picked.name}` : 'Pick a card'),
      el('button', { class: 'btn', disabled: ctx.pending || !hand.length, onclick: () => play(ctx, () => chooseCard(ctx.code, hand[0].id)) }, 'Play top card')));
}

/** The cards that were just revealed and who won them. Built once per finished round, so its animation is not replayed by later repaints. */
function lastRoundPanel(ctx) {
  const lr = ctx.rt.lastRound;
  if (!lr || !ctx.deck) return '';
  if (ctx.lrSrc !== lr || ctx.lrDeck !== ctx.deck) {
    ctx.lrSrc = lr;
    ctx.lrDeck = ctx.deck;
    ctx.lrNode = buildLastRound(ctx, lr);
  }
  return ctx.lrNode;
}

function buildLastRound(ctx, lr) {
  const g = ctx.game;
  const label = statLabelOf(ctx);
  const cards = Object.entries(lr.cards ?? {});
  return el('section', { class: 'panel last-round fresh' },
    el('h3', {}, `Last round${lr.round ? ` · ${lr.round}` : ''}`,
      lr.stat ? el('span', { class: 'pill' }, `${label(lr.stat)}${lr.statAuto ? ' (auto)' : ''}`) : '',
      lr.clash ? el('span', { class: 'pill clash' }, `CLASH${lr.clash > 1 ? ` ×${lr.clash}` : ''}`) : ''),
    cards.length ? el('div', { class: 'reveals' }, cards.map(([seat, c], i) => {
      const win = Number(seat) === lr.winner;
      return el('figure', { class: `reveal${win ? ' winner' : ''}`, style: `--i:${i}` },
        el('div', { class: 'card-wrap' }, cardView(mapCard(c, ctx.deck.stats), ctx.deck, { highlight: lr.stat })),
        el('figcaption', {}, seatName(g, Number(seat)), win ? ' 🏆' : ''));
    })) : '',
    el('p', { class: 'result' },
      lr.winner != null ? `${seatName(g, lr.winner)} won the round and takes ${lr.cardsWon} card${lr.cardsWon === 1 ? '' : 's'}`
        : lr.potCarried != null ? `No winner: the pot (${lr.potCarried}) carries over` : ''),
    ...lr.eliminated.map((e) => el('p', { class: 'result out' }, `${seatName(g, e.seat)} is out (#${e.placement})`)));
}

/** The last few things that happened, newest first. */
function feedList(ctx) {
  const g = ctx.game;
  const lines = ctx.rt.feed.filter((e) => e.type !== 'revealed' && e.type !== 'gameOver')
    .map((e) => eventText(e, g, statLabelOf(ctx))).filter(Boolean).slice(-6).reverse();
  return lines.length ? el('ol', { class: 'feed', 'aria-label': 'Recent events' }, lines.map((t) => el('li', {}, t))) : '';
}

/** The result, once the game has ended. */
function overScreen(ctx) {
  const g = ctx.game;
  const sum = overSummary({ over: ctx.rt.over ?? overFromGame(g), game: g, mySeat: ctx.mySeat });
  return [
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: () => goUp(paths.decks()) }, '← Decks'), el('h2', {}, g.deck.name), el('span')),
    el('section', { class: 'panel over' },
      el('h1', { class: `result-big ${sum.won ? 'won' : ''}` }, sum.title),
      sum.endedByRoundCap ? el('p', { class: 'hint' }, 'The round limit ended the game: the player with the most cards wins.') : ''),
    el('ol', { class: 'table' }, sum.rows.map((r) =>
      el('li', { class: r.isMe ? 'mine' : '' },
        el('span', { class: 'seat-no' }, r.placement != null ? String(r.placement) : '–'),
        el('span', { class: 'seat-name' }, r.name, r.isMe ? el('em', {}, ' · you') : ''),
        el('span', { class: 'pill' }, r.placement != null ? ordinal(r.placement) : '')))),
    lastRoundPanel(ctx),
    el('div', { class: 'btn-col' },
      el('button', { class: 'btn primary', onclick: () => nav(paths.deck(g.deck.id)) }, 'New game with this deck'),
      el('button', { class: 'btn', onclick: () => goUp(paths.decks()) }, 'Back to decks')),
  ];
}

/** The table while the game runs: status, your move, who is where, what just happened. */
function liveScreen(ctx) {
  const g = ctx.game;
  if (g.status === 'FINISHED') return overScreen(ctx);
  const v = ctx.view;
  const label = statLabelOf(ctx);
  const info = phaseInfo({ view: v, game: g, mySeat: ctx.mySeat, statLabel: v?.stat ? label(v.stat) : undefined });
  const clash = clashInfo({ view: v, game: g, mySeat: ctx.mySeat });
  const rows = seatRows(g, v, ctx.mySeat);
  const left = secondsLeft(v?.deadline, Date.now(), ctx.rt.skewMs);
  const mine = v && ctx.deck && !v.canChoose && !v.canCall;                // nothing to do: just show your card(s)
  const hand = mine ? v.ownHand.map((c) => mapCard(c, ctx.deck.stats)) : [];
  const committed = mine && v.committed ? mapCard(v.committed, ctx.deck.stats) : null;

  return [
    el('div', { class: 'topbar' }, el('button', { class: 'btn small ghost', onclick: () => (isSeated() ? askLeave() : goUp(paths.decks())) }, '✕ Leave'), el('h2', {}, g.deck.name), connChip(ctx)),
    el('section', { class: `panel table-status${info.clash ? ' clash' : ''}`, role: 'status' },
      clash.active ? el('div', { class: 'clash-banner' }, el('b', {}, `⚔ CLASH${clash.level > 1 ? ` ×${clash.level}` : ''}`), el('span', {}, clash.names.length ? `${clash.names.join(' vs ')}${v.potSize ? ` · pot ${v.potSize}` : ''}` : '')) : '',
      v ? el('div', { class: 'status-meta' }, el('b', {}, `Round ${v.round}`), !clash.active && v.potSize > 0 ? el('span', { class: 'pill' }, `Pot · ${v.potSize}`) : '', el('span', { class: `countdown${clash.active ? ' big' : ''}` }, left == null ? '' : `${left}s`)) : '',
      el('p', { class: 'status' }, info.text)),
    v && v.canChoose ? choosePanel(ctx) : '',
    v && v.canCall ? callPanel(ctx, clash.active) : '',
    el('ol', { class: 'table' }, rows.map((r) =>
      el('li', { class: `${r.isMe ? 'mine' : ''}${r.out ? ' out' : ''}${r.isCaller ? ' caller' : ''}${r.isContender ? ' contender' : ''}` },
        el('span', { class: 'seat-no' }, String(r.seat)),
        el('span', { class: 'seat-name' }, r.name, r.isMe ? el('em', {}, ' · you') : '', r.seat === g.hostSeat ? el('em', {}, ' · host') : ''),
        r.isCaller && !r.out ? el('span', { class: 'tag' }, 'calls') : '',
        r.isContender ? el('span', { class: 'tag clash' }, 'clash') : '',
        r.placement != null ? el('span', { class: 'tag' }, `#${r.placement}`) : '',
        el('span', { class: 'pill' }, r.handSize == null ? '–' : r.out ? 'out' : `${r.handSize} card${r.handSize === 1 ? '' : 's'}`)))),
    lastRoundPanel(ctx),
    mine ? el('h3', { class: 'section' }, handTitle(v, ctx.mySeat)) : '',
    committed ? el('div', { class: 'card-wrap committed' }, el('p', { class: 'hint' }, 'Played this round'), cardView(committed, ctx.deck, { highlight: v.stat })) : '',
    hand.length ? el('div', { class: 'hand' }, hand.map((c) => el('div', { class: 'card-wrap' }, cardView(c, ctx.deck, { highlight: v.stat })))) : '',
    v && !ctx.deck ? el('p', { class: 'hint' }, 'Loading your cards…') : '',
    feedList(ctx),
  ];
}

/* ---------- sign-in (Google) ---------- */
/** Signed in with a username? Go to `nextPath`. Otherwise send the user through sign-in / username first. */
function requireIdentity(nextPath) {
  if (!me) return signIn(nextPath);
  if (me.needsUsername) return chooseUsername(nextPath);
  return nav(nextPath);
}

/** Both screens return to `afterAuth` when finished; the sign-in / username entry is replaced, not kept in history. */
function finishAuth() {
  const path = afterAuth ?? paths.menu();
  afterAuth = null;
  goUp(path);
}

const isLocalApi = () => ['localhost', '127.0.0.1', '[::1]'].includes(new URL(API_BASE || location.origin).hostname);   // apiBase '' = same origin (bundled build)

let gsiScript = null;
function loadGoogleScript() {
  if (globalThis.google?.accounts?.id) return Promise.resolve();
  gsiScript ??= new Promise((resolve, reject) => {
    const tag = el('script', { src: 'https://accounts.google.com/gsi/client', async: true, defer: true });
    tag.onload = resolve;
    tag.onerror = () => { gsiScript = null; reject(new Error('Could not load Google sign-in')); };
    document.head.append(tag);
  });
  return gsiScript;
}

/** Open the sign-in screen; after signing in the user returns to `nextPath` (default: the menu). */
function signIn(nextPath = null, { replace = false } = {}) {
  afterAuth = nextPath;
  nav(paths.signin(), { replace });
}

function signInScreen() {
  const googleBox = el('div', { class: 'gsi' });
  show(screen('center',
    el('h2', {}, 'Sign in'),
    el('p', { class: 'tagline' }, 'Sign in to create or join a multiplayer game.'),
    googleBox,
    isLocalApi() ? devSignInForm() : '',
    el('p', { class: 'hint' }, 'Playing alone against the CPU never needs an account.'),
    el('button', { class: 'btn ghost', onclick: goBack }, 'Back')));
  setupGoogleButton(googleBox);
}

/** Fetches a one-time nonce, then shows Google's own button. Called again after a failed attempt (nonces are single use). */
async function setupGoogleButton(box) {
  const clientId = globalThis.CARDS_CONFIG?.googleClientId;
  if (!clientId) { box.replaceChildren(el('p', { class: 'hint' }, 'Google sign-in is not configured for this site.')); return; }
  const id = screenId;
  try {
    await loadGoogleScript();
    const { nonce } = await fetchNonce();
    if (screenId !== id) return;
    google.accounts.id.initialize({ client_id: clientId, nonce, callback: (r) => onGoogleCredential(r.credential, box) });
    box.replaceChildren();
    google.accounts.id.renderButton(box, { theme: 'filled_black', size: 'large', shape: 'pill', text: 'signin_with', width: 280 });
  } catch (e) {
    box.replaceChildren(el('p', { class: 'hint' }, e instanceof ApiError ? e.message : (e.message || 'Could not start Google sign-in')));
  }
}

async function onGoogleCredential(credential, box) {
  try {
    me = await googleSignIn(credential);
  } catch (e) {
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');
    return setupGoogleButton(box);                    // new nonce for the next attempt
  }
  welcome();
}

/** Local development only: pick any username and sign in without Google (the server refuses outside the local profile). */
function devSignInForm() {
  const input = el('input', { class: 'field', type: 'text', placeholder: 'dev username', maxlength: '8', autocomplete: 'off', 'aria-label': 'Dev username' });
  const go = async () => {
    try {
      me = await devLogin(input.value.trim());
      welcome();
    } catch (e) {
      toast(e instanceof ApiError ? (e.status === 404 ? 'Dev sign-in is not enabled on this server' : e.message) : 'Could not reach the server');
    }
  };
  input.addEventListener('keydown', (e) => e.key === 'Enter' && go());
  return el('div', { class: 'dev-login' },
    el('p', { class: 'hint' }, 'Local development only'),
    input,
    el('button', { class: 'btn small', onclick: go }, 'Dev sign-in'));
}

function welcome() {
  toast(`Welcome, ${me.displayName ?? me.username ?? 'player'}`);
  if (me.needsUsername) return nav(paths.username(), { replace: true });   // keeps afterAuth
  finishAuth();
}

/** First sign-in (or "Change username"): pick the name other players see. */
/** Open the username screen; afterwards return to `nextPath` (default: where we were / the menu). */
function chooseUsername(nextPath = null, { replace = false } = {}) {
  if (nextPath) afterAuth = nextPath;
  nav(paths.username(), { replace });
}

function usernameScreen() {
  if (!me) return signIn(afterAuth, { replace: true });
  const input = el('input', { class: 'field', type: 'text', placeholder: 'Choose a username', maxlength: '8', autocomplete: 'off', 'aria-label': 'Username' });
  const save = el('button', { class: 'btn primary wide', onclick: () => submit() }, 'Save');
  const submit = async () => {
    const username = input.value.trim().toLowerCase();
    if (!/^[a-z0-9_]{1,8}$/.test(username)) return toast('Use 1–8 letters, digits or _');
    save.disabled = true;
    try {
      me = await updateMe(username);
      toast(`You are ${me.username}`);
      finishAuth();
    } catch (e) {
      save.disabled = false;
      if (e instanceof ApiError && e.status === 401) { me = null; toast('Your session expired — sign in again'); return signIn(afterAuth, { replace: true }); }
      toast(e instanceof ApiError ? e.message : 'Could not reach the server');
    }
  };
  input.addEventListener('keydown', (e) => e.key === 'Enter' && submit());
  show(screen('center',
    el('h2', {}, me?.username ? 'Change username' : 'Choose a username'),
    el('p', { class: 'tagline' }, 'This is the name other players see.'),
    input,
    save,
    el('p', { class: 'hint' }, '1–8 characters: letters, digits or underscore.'),
    el('button', { class: 'btn ghost', onclick: () => { afterAuth = null; goBack(); } }, me?.username ? 'Cancel' : 'Not now')));
  input.value = me?.username ?? '';
  input.focus();
}

/* ---------- account ---------- */
function account() {
  if (!me) return signIn(paths.account(), { replace: true });
  const who = me.username ?? me.displayName ?? 'player';
  show(screen('center',
    el('section', { class: 'panel profile' },
      me.avatarUrl ? el('img', { class: 'avatar', src: me.avatarUrl, alt: '', referrerpolicy: 'no-referrer' }) : el('div', { class: 'avatar blank' }, who[0].toUpperCase()),
      el('h2', {}, me.username ? `@${me.username}` : (me.displayName ?? 'Player')),
      me.displayName && me.username ? el('p', { class: 'tagline' }, me.displayName) : '',
      me.email ? el('p', { class: 'link' }, me.email) : ''),
    el('div', { class: 'btn-col' },
      el('button', { class: 'btn', onclick: () => chooseUsername(paths.account()) }, 'Change username'),
      el('button', { class: 'btn', onclick: doSignOut }, 'Sign out'),
      el('button', { class: 'btn danger', onclick: doDeleteAccount }, 'Delete account'),
      el('button', { class: 'btn ghost', onclick: () => goUp(paths.menu()) }, 'Back'))));
}

async function doSignOut() {
  try { await signOut(); } catch { /* the cookie may already be gone; treat as signed out */ }
  me = null;
  toast('Signed out');
  goUp(paths.menu());
}

async function doDeleteAccount() {
  if (!confirm('Delete your account? Your name and sign-in details are erased. This cannot be undone.')) return;
  try {
    await deleteAccount();
    me = null;
    toast('Account deleted');
    goUp(paths.menu());
  } catch (e) {
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');
  }
}

/* ---------- game ---------- */
function startGame() {
  if (deck.cards.length < 2) { toast('This deck has too few cards'); return; }
  game = new Game(deck.cards, { stats: deck.stats, difficulty });
  startedAt = Date.now();
  if (current.name === 'play') nextRound();           // Rematch: same screen, new game
  else nav(paths.play());
}
let startedAt = 0;

function nextRound() {
  if (game.over) return gameOver();
  game.turn === 'player' ? playerTurn() : cpuTurn();
}

function hud() {
  const total = game.playerCount + game.cpuCount + game.potCount || 1;
  return el('div', { class: 'hud' },
    el('div', { class: 'score' }, el('b', {}, game.playerCount), el('small', {}, 'You')),
    el('div', { class: 'share-wrap', 'aria-hidden': 'true' },
      el('div', { class: 'share' }, el('i', { style: `width:${(game.playerCount / total) * 100}%` })),
      game.potCount ? el('span', { class: 'pot' }, `Pot · ${game.potCount}`) : el('span', {}, `Round ${game.round + 1}`)),
    el('div', { class: 'score cpu' }, el('b', {}, game.cpuCount), el('small', {}, 'CPU')));
}

function gameScreen(...kids) {
  show(screen('',
    el('div', { class: 'topbar' },
      el('button', { class: 'btn small ghost', onclick: askLeave }, '✕ Quit'),
      el('span', { class: 'whoami' }, `${difficulty[0].toUpperCase()}${difficulty.slice(1)}`)),
    hud(), ...kids));
}

function playerTurn() {
  const w = game.playerCard;
  gameScreen(
    el('p', { class: 'status' }, 'Your turn — pick a stat'),
    el('div', { class: 'card-wrap' }, cardView(w)),
    el('div', { class: 'stat-grid' }, game.stats.map((s) =>
      el('button', { class: 'stat-btn', onclick: () => resolve(s.key) }, el('span', {}, s.label), el('b', {}, w.stats[s.key].display)))));
}

function cpuTurn() {
  const id = screenId + 1; // the id this screen will get
  const w = game.playerCard;
  const key = game.cpuPick();
  const status = el('p', { class: 'status' }, 'CPU is choosing', el('span', { class: 'dots' }));
  gameScreen(status, el('div', { class: 'card-wrap' }, cardView(w)));
  const alive = () => screenId === id;
  // Like setTimeout, but holds still while the "Leave this game?" dialog is open.
  const later = (fn, ms) => setTimeout(function run() {
    if (!alive()) return;
    if (leaving) return void setTimeout(run, 250);
    fn();
  }, ms);
  later(() => {
    status.replaceChildren(`CPU picks ${game.stat(key).label}`);
    later(() => resolve(key), 1000);
  }, 900);
}

const BANNER = {
  [RESULT.WIN]: { cls: 'win', title: 'You win the round!' },
  [RESULT.LOSS]: { cls: 'loss', title: 'You lose the round' },
  [RESULT.CLASH]: { cls: 'clash', title: 'Clash!' },
};

function resolve(key) {
  const w1 = game.playerCard;
  const w2 = game.cpuCard;
  const r = game.play(key);
  const s = game.stat(key);
  const b = BANNER[r.result];
  const note = r.result === RESULT.CLASH ? `Tied on ${s.label}. Both cards go to the pot (${game.potCount}).`
    : `${r.caller === 'player' ? 'You' : 'CPU'} called ${s.label}${s.higherWins ? '' : ' (lower wins)'}`;

  const versus = el('div', { class: 'versus', 'data-view': 'cpu' },
    el('div', { class: 'side me' }, el('span', { class: 'tag' }, 'Your card'), el('div', { class: 'card-wrap' }, cardView(w1))),
    el('div', { class: 'side cpu' }, el('span', { class: 'tag' }, 'CPU card'), el('div', { class: 'card-wrap' }, cardView(w2))));
  const tab = (view, label) => el('button', { 'aria-pressed': String(view === 'cpu'),
    onclick: (e) => { versus.dataset.view = view; e.currentTarget.parentNode.querySelectorAll('button').forEach((x) => x.setAttribute('aria-pressed', String(x === e.currentTarget))); } }, label);

  const go = el('button', { class: 'btn primary wide', onclick: nextRound }, r.over ? 'See result' : 'Continue');
  gameScreen(
    el('div', { class: `banner ${b.cls}`, role: 'status' },
      el('h2', {}, b.title),
      el('p', {}, note),
      el('div', { class: 'vs' }, el('span', {}, `You ${w1.stats[key].display}`), el('span', {}, '·'), el('span', {}, `CPU ${w2.stats[key].display}`))),
    el('div', { class: 'tabs' }, el('div', { class: 'segmented' }, tab('me', 'Your card'), tab('cpu', 'CPU card'))),
    versus,
    el('div', { class: 'dock' }, go));
  go.focus({ preventScroll: true });
}

/* ---------- game over ---------- */
function gameOver() {
  const w = game.winner;
  const mins = Math.max(1, Math.round((Date.now() - startedAt) / 60000));
  show(screen('center',
    el('h1', { class: `result-big ${w === 'player' ? 'won' : w === 'cpu' ? 'lost' : 'draw'}` },
      w === 'player' ? 'You won!' : w === 'cpu' ? 'You lost' : 'Draw'),
    el('p', { class: 'tagline' }, w === 'player' ? 'Every card is yours.' : w === 'cpu' ? 'The CPU took your last card.' : 'Both hands ran out together.'),
    el('div', { class: 'stats-row' },
      el('span', {}, el('b', {}, game.round), 'rounds'),
      el('span', {}, el('b', {}, `${mins}m`), 'time'),
      el('span', {}, el('b', {}, difficulty[0].toUpperCase() + difficulty.slice(1)), 'CPU')),
    el('div', { class: 'btn-col' },
      el('button', { class: 'btn primary', onclick: startGame }, 'Rematch'),
      el('button', { class: 'btn', onclick: leaveGame }, 'Menu'))));
}

boot();
