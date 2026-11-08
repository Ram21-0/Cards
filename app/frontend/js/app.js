import { Game, RESULT, DIFFICULTY } from './engine.js';
import { fetchDecks, fetchDeck } from './api.js';
import { offlineDeck } from './offline.js';

const app = document.getElementById('app');
const PLACEHOLDER = 'assets/placeholder.png';
const SPLASH_MS = 1400;

let game = null;
let deck = null;    // the deck being played: { id, name, source, stats, cards } (see api.js)
let decks = [];     // public decks the backend offers (empty when offline)
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
function cardView(w) {
  const img = el('img', { src: w.img || PLACEHOLDER, alt: w.name, draggable: 'false' });
  img.addEventListener('error', () => { img.src = PLACEHOLDER; }, { once: true });
  return el('article', { class: `card ${w.brand === 'sd' ? 'sd' : 'raw'}` },
    el('div', { class: 'image' }, img),
    el('h2', { class: 'name' }, w.name),
    el('div', { class: 'grid', style: `--cols:${deck.stats.length}` },
      deck.stats.map((s) => el('span', { class: 'label' }, s.label)),
      deck.stats.map((s) => el('span', { class: 'value' }, w.stats[s.key].display))));
}

/* ---------- splash ---------- */
async function boot() {
  show(screen('center', el('h1', { class: 'logo' }, 'CARDS'), el('p', { class: 'tagline' }, 'Are you ready?')));
  await Promise.all([loadDecks(), new Promise((r) => setTimeout(r, SPLASH_MS))]);
  menu();
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
  const user = store('username');
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
    el('button', { class: 'btn wide', onclick: () => gallery() }, 'Card gallery'),
    el('button', { class: 'btn wide', onclick: login }, user ? 'Change player' : 'Login'),
    deckPicker,
    el('div', { class: 'row' }, el('span', {}, 'CPU difficulty'), seg),
    user ? el('p', { class: 'whoami' }, `Playing as ${user}`) : ''));
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

/* ---------- gallery ---------- */
function gallery(filter = 'all') {
  const list = deck.cards.filter((c) => filter === 'all' || (filter === 'sd') === (c.brand === 'sd'));
  const chip = (id, label) => el('button', { class: 'btn small ghost', 'aria-pressed': String(filter === id), onclick: () => gallery(id),
    style: filter === id ? 'border-color:var(--accent);color:var(--accent)' : '' }, label);
  show(screen('',
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: menu }, '← Menu'), el('h2', {}, `Gallery · ${list.length}`), el('span')),
    el('div', { class: 'filters' }, chip('all', 'All'), chip('raw', 'Raw'), chip('sd', 'SD')),
    el('div', { class: 'gallery' }, list.map((w) =>
      el('button', { class: 'cell', 'aria-label': w.name, onclick: () => viewCard(deck.cards.indexOf(w)) }, cardView(w))))));
}

function viewCard(i) {
  const n = deck.cards.length;
  show(screen('',
    el('div', { class: 'topbar' }, el('button', { class: 'btn small', onclick: () => gallery() }, '← Gallery'), el('h2', {}, `${i + 1} / ${n}`), el('span')),
    el('div', { class: 'card-wrap' }, cardView(deck.cards[i])),
    el('div', { class: 'viewer-nav' },
      el('button', { class: 'btn', onclick: () => viewCard((i + n - 1) % n) }, '← Prev'),
      el('button', { class: 'btn', onclick: () => viewCard((i + 1) % n) }, 'Next →'))));
}

/* ---------- login (local only until the backend exists) ---------- */
function login() {
  const input = el('input', { class: 'field', type: 'text', placeholder: 'Choose a username', maxlength: '8', autocomplete: 'off', 'aria-label': 'Username' });
  const enter = () => {
    const username = input.value.trim().toLowerCase();
    if (!username) return toast('Enter a username');
    store('username', username);
    toast(`Welcome, ${username}`);
    menu();
  };
  input.addEventListener('keydown', (e) => e.key === 'Enter' && enter());
  show(screen('center',
    el('h2', {}, 'Who’s playing?'),
    input,
    el('button', { class: 'btn primary wide', onclick: enter }, 'Enter'),
    el('p', { class: 'hint' }, 'Saved on this device for now. Online accounts and a leaderboard come with the backend.'),
    el('button', { class: 'btn ghost', onclick: menu }, 'Back')));
  input.value = store('username') || '';
  input.focus();
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
