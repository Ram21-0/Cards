import { Game, RESULT, DIFFICULTY } from './engine.js';
import {
  API_BASE, ApiError, fetchDecks, fetchDeck, createGame, getGame,
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
function cardView(w, d = deck) {
  const img = el('img', { src: w.img || PLACEHOLDER, alt: w.name, draggable: 'false' });
  img.addEventListener('error', () => { img.src = PLACEHOLDER; }, { once: true });
  return el('article', { class: `card ${w.brand === 'sd' ? 'sd' : 'raw'}` },
    el('div', { class: 'image' }, img),
    el('h2', { class: 'name' }, w.name),
    el('div', { class: 'grid', style: `--cols:${d.stats.length}` },
      d.stats.map((s) => el('span', { class: 'label' }, s.label)),
      d.stats.map((s) => el('span', { class: 'value' }, w.stats[s.key].display))));
}

/* ---------- splash ---------- */
async function boot() {
  show(screen('center', el('h1', { class: 'logo' }, 'CARDS'), el('p', { class: 'tagline' }, 'Are you ready?')));
  await Promise.all([loadDecks().then(loadMe), new Promise((r) => setTimeout(r, SPLASH_MS))]);
  const code = new URLSearchParams(location.search).get('join');
  if (code) return openJoinLink(code);
  menu();
}

/** Someone opened a shared link (?join=CODE): show that game's lobby. */
async function openJoinLink(code) {
  try {
    lobby(await getGame(code.trim().toUpperCase()), { host: false });
  } catch (e) {
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');
    menu();
  }
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
    el('button', { class: 'btn wide', onclick: () => gallery() }, 'Gallery'),
    el('button', { class: 'btn wide', onclick: () => (me ? account() : signIn()) }, me ? 'Account' : 'Sign in'),
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
  menu();
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
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: menu }, '← Menu'), el('h2', {}, `Decks · ${tiles.length}`), el('span')),
    offline ? el('div', { class: 'notice' }, 'Offline — creating games needs the server.') : '',
    el('div', { class: 'deck-list' }, tiles.map((d) =>
      el('button', { class: 'deck-tile', onclick: () => openDeck(d.id) },
        el('h3', {}, d.name),
        d.description ? el('p', {}, d.description) : '',
        el('div', { class: 'chips' }, el('span', { class: 'chip strong' }, `${d.cardCount} cards`), d.stats.map((s) => el('span', { class: 'chip' }, s.label))))))));
}

async function openDeck(id) {
  let d = id === 'offline' ? deck : null;
  if (!d) {
    show(screen('center', el('p', { class: 'tagline' }, 'Loading deck…')));
    try {
      d = await loadFullDeck(id);
    } catch {
      toast('Could not load that deck');
      return gallery();
    }
  }
  deckScreen(d);
}

/** One deck: the "create a game" panel on top, its cards below. */
function deckScreen(d, filter = 'all') {
  const brands = [...new Set(d.cards.map((c) => c.brand).filter(Boolean))];
  const list = d.cards.filter((c) => filter === 'all' || c.brand === filter);
  const chip = (id, label) => el('button', { class: 'btn small ghost', 'aria-pressed': String(filter === id), onclick: () => deckScreen(d, id),
    style: filter === id ? 'border-color:var(--accent);color:var(--accent)' : '' }, label);
  show(screen('',
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: gallery }, '← Decks'), el('h2', {}, d.name), el('span')),
    createPanel(d),
    el('h3', { class: 'section' }, `Cards · ${list.length}`),
    brands.length > 1 ? el('div', { class: 'filters' }, chip('all', 'All'), brands.map((b) => chip(b, b.toUpperCase()))) : '',
    el('div', { class: 'gallery' }, list.map((w) =>
      el('button', { class: 'cell', 'aria-label': w.name, onclick: () => viewCard(d.cards.indexOf(w), d) }, cardView(w, d))))));
}

function viewCard(i, d) {
  const n = d.cards.length;
  show(screen('',
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: () => deckScreen(d) }, `← ${d.name}`), el('h2', {}, `${i + 1} / ${n}`), el('span')),
    el('div', { class: 'card-wrap' }, cardView(d.cards[i], d)),
    el('div', { class: 'viewer-nav' },
      el('button', { class: 'btn', onclick: () => viewCard((i + n - 1) % n, d) }, '← Prev'),
      el('button', { class: 'btn', onclick: () => viewCard((i + 1) % n, d) }, 'Next →'))));
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
    return requireIdentity(() => deckScreen(d));
  }
  button.disabled = true;
  button.textContent = 'Creating…';
  try {
    lobby(await createGame({ deckId: d.id, maxPlayers: players }), { host: true });
  } catch (e) {
    button.disabled = false;
    button.textContent = 'Create game';
    if (e instanceof ApiError && e.status === 401) {   // the session expired or was revoked
      me = null;
      toast('Your session expired — sign in again');
      return signIn(() => deckScreen(d));
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

const seatLabel = (s) => (s.kind === 'CPU' ? `CPU (${s.difficulty ?? 'normal'})` : s.username) + (s.seat === 1 ? ' · host' : '');

function lobby(g, { host = false } = {}) {
  const link = g.joinUrl ?? `${location.origin}${location.pathname}?join=${g.joinCode}`;
  const countEl = el('h3', { class: 'section' });
  const seatsEl = el('ol', { class: 'seats', 'aria-live': 'polite' });
  const fill = (game) => {
    countEl.textContent = `Players · ${game.seats.length} / ${game.maxPlayers}`;
    seatsEl.replaceChildren(...Array.from({ length: game.maxPlayers }, (_, i) => {
      const s = game.seats.find((x) => x.seat === i + 1);
      return el('li', { class: s ? 'taken' : 'open' }, el('span', { class: 'seat-no' }, String(i + 1)), s ? seatLabel(s) : 'Waiting for a player…');
    }));
  };
  fill(g);

  const leave = () => {
    const url = new URL(location.href);          // drop only ?join=, keep other query params (e.g. ?api=)
    url.searchParams.delete('join');
    history.replaceState(null, '', url);
    gallery();
  };
  show(screen('',
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: leave }, '← Decks'), el('h2', {}, 'Game lobby'), el('span')),
    el('section', { class: 'panel lobby' },
      el('p', { class: 'tagline' }, host ? 'Share this code so others can join' : `Join code for ${g.deck.name}`),
      el('div', { class: 'join-code', 'aria-label': `Join code ${g.joinCode.split('').join(' ')}` }, g.joinCode),
      el('p', { class: 'link' }, link),
      el('div', { class: 'btn-row' },
        el('button', { class: 'btn', onclick: () => copyText(g.joinCode, 'Code') }, 'Copy code'),
        el('button', { class: 'btn', onclick: () => copyText(link, 'Link') }, 'Copy link'),
        navigator.share ? el('button', { class: 'btn primary', onclick: () => navigator.share({ title: 'Join my Cards game', text: `Join my ${g.deck.name} game with code ${g.joinCode}`, url: link }).catch(() => {}) }, 'Share') : '')),
    countEl, seatsEl,
    !host && !me ? el('button', { class: 'btn primary wide', onclick: () => signIn(() => lobby(g, { host: false })) }, 'Sign in to join') : '',
    el('p', { class: 'hint' }, `${g.deck.name} · up to ${g.maxPlayers} players` + (host ? '' : ' · joining a game isn’t available yet')),
  ));

  // Keep the seat list fresh while this screen is open.
  const id = screenId;
  const poll = async () => {
    await new Promise((r) => setTimeout(r, 4000));
    if (screenId !== id) return;
    try {
      const fresh = await getGame(g.joinCode);
      if (screenId === id && JSON.stringify(fresh.seats) !== JSON.stringify(g.seats)) { g = fresh; fill(fresh); }
    } catch { /* keep showing what we have */ }
    poll();
  };
  poll();
}

/* ---------- sign-in (Google) ---------- */
/** Signed in with a username? Run `next`. Otherwise send the user through sign-in / username first. */
function requireIdentity(next) {
  if (!me) return signIn(next);
  if (me.needsUsername) return chooseUsername(next);
  return next();
}

const isLocalApi = () => ['localhost', '127.0.0.1', '[::1]'].includes(new URL(API_BASE).hostname);

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

function signIn(next) {
  const googleBox = el('div', { class: 'gsi' });
  show(screen('center',
    el('h2', {}, 'Sign in'),
    el('p', { class: 'tagline' }, 'Sign in to create or join a multiplayer game.'),
    googleBox,
    isLocalApi() ? devSignInForm(next) : '',
    el('p', { class: 'hint' }, 'Playing alone against the CPU never needs an account.'),
    el('button', { class: 'btn ghost', onclick: () => (next ? gallery() : menu()) }, 'Back')));
  setupGoogleButton(googleBox, next);
}

/** Fetches a one-time nonce, then shows Google's own button. Called again after a failed attempt (nonces are single use). */
async function setupGoogleButton(box, next) {
  const clientId = globalThis.CARDS_CONFIG?.googleClientId;
  if (!clientId) { box.replaceChildren(el('p', { class: 'hint' }, 'Google sign-in is not configured for this site.')); return; }
  const id = screenId;
  try {
    await loadGoogleScript();
    const { nonce } = await fetchNonce();
    if (screenId !== id) return;
    google.accounts.id.initialize({ client_id: clientId, nonce, callback: (r) => onGoogleCredential(r.credential, box, next) });
    box.replaceChildren();
    google.accounts.id.renderButton(box, { theme: 'filled_black', size: 'large', shape: 'pill', text: 'signin_with', width: 280 });
  } catch (e) {
    box.replaceChildren(el('p', { class: 'hint' }, e instanceof ApiError ? e.message : (e.message || 'Could not start Google sign-in')));
  }
}

async function onGoogleCredential(credential, box, next) {
  try {
    me = await googleSignIn(credential);
  } catch (e) {
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');
    return setupGoogleButton(box, next);              // new nonce for the next attempt
  }
  welcome(next);
}

/** Local development only: pick any username and sign in without Google (the server refuses outside the local profile). */
function devSignInForm(next) {
  const input = el('input', { class: 'field', type: 'text', placeholder: 'dev username', maxlength: '8', autocomplete: 'off', 'aria-label': 'Dev username' });
  const go = async () => {
    try {
      me = await devLogin(input.value.trim());
      welcome(next);
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

function welcome(next) {
  toast(`Welcome, ${me.displayName ?? me.username ?? 'player'}`);
  if (me.needsUsername) return chooseUsername(next);
  (next ?? menu)();
}

/** First sign-in (or "Change username"): pick the name other players see. */
function chooseUsername(next) {
  const input = el('input', { class: 'field', type: 'text', placeholder: 'Choose a username', maxlength: '8', autocomplete: 'off', 'aria-label': 'Username' });
  const save = el('button', { class: 'btn primary wide', onclick: () => submit() }, 'Save');
  const submit = async () => {
    const username = input.value.trim().toLowerCase();
    if (!/^[a-z0-9_]{1,8}$/.test(username)) return toast('Use 1–8 letters, digits or _');
    save.disabled = true;
    try {
      me = await updateMe(username);
      toast(`You are ${me.username}`);
      (next ?? menu)();
    } catch (e) {
      save.disabled = false;
      if (e instanceof ApiError && e.status === 401) { me = null; toast('Your session expired — sign in again'); return signIn(next); }
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
    el('button', { class: 'btn ghost', onclick: () => (me?.username && next ? next() : menu()) }, me?.username ? 'Cancel' : 'Not now')));
  input.value = me?.username ?? '';
  input.focus();
}

/* ---------- account ---------- */
function account() {
  if (!me) return signIn();
  const who = me.username ?? me.displayName ?? 'player';
  show(screen('center',
    el('section', { class: 'panel profile' },
      me.avatarUrl ? el('img', { class: 'avatar', src: me.avatarUrl, alt: '', referrerpolicy: 'no-referrer' }) : el('div', { class: 'avatar blank' }, who[0].toUpperCase()),
      el('h2', {}, me.username ? `@${me.username}` : (me.displayName ?? 'Player')),
      me.displayName && me.username ? el('p', { class: 'tagline' }, me.displayName) : '',
      me.email ? el('p', { class: 'link' }, me.email) : ''),
    el('div', { class: 'btn-col' },
      el('button', { class: 'btn', onclick: () => chooseUsername(() => account()) }, 'Change username'),
      el('button', { class: 'btn', onclick: doSignOut }, 'Sign out'),
      el('button', { class: 'btn danger', onclick: doDeleteAccount }, 'Delete account'),
      el('button', { class: 'btn ghost', onclick: menu }, 'Back'))));
}

async function doSignOut() {
  try { await signOut(); } catch { /* the cookie may already be gone; treat as signed out */ }
  me = null;
  toast('Signed out');
  menu();
}

async function doDeleteAccount() {
  if (!confirm('Delete your account? Your name and sign-in details are erased. This cannot be undone.')) return;
  try {
    await deleteAccount();
    me = null;
    toast('Account deleted');
    menu();
  } catch (e) {
    toast(e instanceof ApiError ? e.message : 'Could not reach the server');
  }
}

/* ---------- game ---------- */
function startGame() {
  if (deck.cards.length < 2) { toast('This deck has too few cards'); return; }
  game = new Game(deck.cards, { stats: deck.stats, difficulty });
  startedAt = Date.now();
  nextRound();
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
      el('button', { class: 'btn small ghost', onclick: () => confirm('Quit this game?') && menu() }, '✕ Quit'),
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
  setTimeout(() => {
    if (!alive()) return;
    status.replaceChildren(`CPU picks ${game.stat(key).label}`);
    setTimeout(() => alive() && resolve(key), 1000);
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
      el('button', { class: 'btn', onclick: menu }, 'Menu'))));
}

boot();
