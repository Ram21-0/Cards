// Live game plumbing (see app/backend/LIVE.md): pure helpers + a small reconnecting WebSocket client.
// No DOM here, so everything can be unit-tested with a fake socket.

export const PING_MS = 25_000;      // the server closes sockets that are silent for 90 s

/** ws(s) URL of a game's socket. apiBase '' (bundled build) means the page's own origin. */
export function wsUrl({ apiBase, location, code }) {
  const u = new URL(apiBase || `${location.protocol}//${location.host}`);
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  u.pathname = `/ws/games/${encodeURIComponent(String(code).toUpperCase())}`;
  u.search = '';
  u.hash = '';
  return u.toString();
}

/** Reconnect delay: 0.5 s, 1 s, 2 s … capped at 30 s, with jitter (50–100 %) so clients don't reconnect in lockstep. */
export function backoffDelay(attempt, rng = Math.random) {
  const base = Math.min(30_000, 500 * 2 ** attempt);
  return Math.round(base / 2 + (rng() * base) / 2);
}

/** What the server reported minus what our clock says: add it to Date.now() to get server time. */
export const skewMs = (serverTime, nowMs) => {
  const t = Date.parse(serverTime);
  return Number.isNaN(t) ? 0 : t - nowMs;
};

export const initialLive = () => ({
  game: null, mySeat: null, view: null, skewMs: 0, started: false, lastError: null,
  feed: [],            // the most recent game events (raw), newest last
  current: null,       // the round being played, folded from events
  lastRound: null,     // the last finished round (what to show on the table)
  over: null,          // { placements, endedByRoundCap } once the game has ended
});

const FEED_MAX = 12;

/** Folds one server message into the client state. Pure: returns a new state. */
export function applyMessage(state, msg, nowMs = Date.now()) {
  const next = { ...state };
  if (msg?.serverTime) next.skewMs = skewMs(msg.serverTime, nowMs);
  switch (msg?.type) {
    case 'lobby':
    case 'started':
      next.game = msg.game;
      next.mySeat = msg.mySeat ?? null;
      if (msg.type === 'started') next.started = true;
      if (msg.game?.status === 'WAITING') next.view = null;     // no table before the start
      if (msg.game?.status === 'FINISHED' && !next.over) next.over = overFromGame(msg.game);
      break;
    case 'view': {
      // A REST answer can arrive after a newer push: never let an older view replace a newer one.
      const older = state.view?.serverTime && msg.serverTime && Date.parse(msg.serverTime) < Date.parse(state.view.serverTime);
      if (!older) next.view = msg;
      break;
    }
    case 'events': {
      const events = msg.events ?? [];
      next.feed = [...state.feed, ...events].slice(-FEED_MAX);
      const folded = foldRounds({ current: state.current, lastRound: state.lastRound }, events);
      next.current = folded.current;
      next.lastRound = folded.lastRound;
      break;
    }
    case 'gameOver':
      next.over = { placements: msg.placements ?? {}, endedByRoundCap: Boolean(msg.endedByRoundCap) };
      next.game = msg.game ?? state.game;
      next.mySeat = msg.mySeat ?? state.mySeat;
      break;
    case 'error':
      next.lastError = msg.error ?? 'Unknown error';
      break;
    default:                                                       // 'pong' and anything newer: only the clock matters
  }
  return next;
}

/** A seat's display name: the player, or "CPU (hard)". */
export function seatName(game, seat) {
  const s = game?.seats?.find((x) => x.seat === seat);
  if (!s) return `Seat ${seat}`;
  return s.kind === 'CPU' ? `CPU (${String(s.difficulty ?? 'medium').toLowerCase()})` : s.username;
}

/** One row per seat for the table: hand size, whose call it is, who is in a clash, who is out. */
export function seatRows(game, view, mySeat) {
  return (game?.seats ?? []).map((s) => {
    const handSize = view?.handSizes?.[s.seat] ?? null;
    const placement = view?.placements?.[s.seat] ?? s.placement ?? null;
    return {
      seat: s.seat,
      name: seatName(game, s.seat),
      kind: s.kind,
      isMe: s.seat === mySeat,
      isCaller: view?.callerSeat === s.seat,
      isContender: Boolean(view?.contenders?.includes(s.seat)) && view?.clashLevel > 0,
      handSize,
      out: handSize === 0,
      placement,
    };
  });
}

/** What to tell the player about the current phase. `statLabel` is the called stat's label, if known. */
export function phaseInfo({ view, game, mySeat, statLabel }) {
  if (!view) return { text: 'Connecting to the table…', clash: false };
  const caller = seatName(game, view.callerSeat);
  const mine = view.callerSeat === mySeat;
  const called = statLabel ? ` — ${statLabel}` : '';
  switch (view.phase) {
    case 'AWAIT_CHOOSE_CALLER':
      return { text: mine ? 'You may pick which card to play' : `${caller} is picking a card`, clash: false };
    case 'AWAIT_CALL':
    case 'AWAITING_CALL':                                          // the example name in LIVE.md
      return { text: mine ? 'Your turn: call a stat' : `${caller} is calling a stat`, clash: false };
    case 'AWAIT_CHOOSE_OTHERS':
      return { text: view.canChoose ? `Stat called${called}: choose your card` : `Players are choosing their cards${called}`, clash: false };
    case 'CLASH_AWAIT_CALL':
      return { text: `CLASH!${view.clashLevel > 1 ? ` ×${view.clashLevel}` : ''} ${view.canCall ? 'Call a stat now: the first call wins' : 'Waiting for a stat to be called'}`, clash: true };
    case 'CLASH_AWAIT_CHOOSE':
      return { text: `CLASH!${called} Choose your card`, clash: true };
    case 'RESOLVE':
    case 'CLASH_RESOLVE':
    case 'ROUND_DONE':
      return { text: 'Revealing the cards…', clash: view.clashLevel > 0 };
    case 'GAME_OVER':
      return { text: 'Game over', clash: false };
    default:
      return { text: String(view.phase ?? '').replaceAll('_', ' ').toLowerCase(), clash: false };
  }
}

/**
 * A WebSocket that reconnects with back-off, pings to stay alive, and gives up (onFatal) when the server refuses it for good.
 *
 * - `probe()` runs after a connection attempt failed before opening. It asks the REST API why (browsers hide the handshake
 *   status) and resolves null (probably transient: retry) or a reason string ('signed-out' | 'not-found' | 'not-seated').
 * - Everything time-related and the socket class are injectable, so tests need no real network or clock.
 */
export function createLiveClient({
  url, probe = async () => null, onMessage = () => {}, onStatus = () => {}, onFatal = () => {},
  WebSocketImpl = globalThis.WebSocket, setTimeoutImpl = setTimeout, clearTimeoutImpl = clearTimeout,
  setIntervalImpl = setInterval, clearIntervalImpl = clearInterval, rng = Math.random,
}) {
  let ws = null;
  let attempt = 0;
  let open = false;
  let stopped = false;
  let everOpened = false;
  let retryTimer = null;
  let pingTimer = null;

  const status = (s) => onStatus(s);

  function connect() {
    if (stopped) return;
    status(everOpened || attempt > 0 ? 'reconnecting' : 'connecting');
    let opened = false;
    ws = new WebSocketImpl(url);
    const mine = ws;
    mine.onopen = () => {
      if (stopped || ws !== mine) return;
      opened = true; open = true; everOpened = true; attempt = 0;
      status('open');
      pingTimer = setIntervalImpl(() => { try { mine.send(JSON.stringify({ type: 'ping' })); } catch { /* closing */ } }, PING_MS);
    };
    mine.onmessage = (e) => {
      let msg;
      try { msg = JSON.parse(e.data); } catch { return; }          // ignore frames we cannot read
      onMessage(msg);
    };
    mine.onclose = async () => {
      if (ws !== mine) return;
      open = false;
      clearIntervalImpl(pingTimer); pingTimer = null;
      if (stopped) return;
      if (!opened) {                                                // refused or unreachable: is it worth retrying?
        const reason = await probe().catch(() => null);
        if (stopped) return;
        if (reason) { stopped = true; status('closed'); onFatal(reason); return; }
      }
      status('reconnecting');
      retryTimer = setTimeoutImpl(connect, backoffDelay(attempt++, rng));
    };
    mine.onerror = () => { /* onclose follows and does the work */ };
  }

  return {
    start() { stopped = false; connect(); },
    close() {
      stopped = true;
      clearTimeoutImpl(retryTimer); clearIntervalImpl(pingTimer);
      open = false;
      try { ws?.close(); } catch { /* already closed */ }
      status('closed');
    },
    isOpen: () => open,
  };
}

/** Whole seconds until a server `deadline` (ISO string), using the server clock offset; never below 0. null = no timer. */
export function secondsLeft(deadline, nowMs, offsetMs = 0) {
  if (!deadline) return null;
  const t = Date.parse(deadline);
  return Number.isNaN(t) ? null : Math.max(0, Math.ceil((t - (nowMs + offsetMs)) / 1000));
}

// ---------------- events -> text and "what happened last round" ----------------

/** Folds game events (in order) into the round being played and the last finished round. Pure. */
export function foldRounds({ current, lastRound }, events) {
  let cur = current;
  let last = lastRound;
  const fresh = (round, caller) => ({ round, caller, stat: null, statSeat: null, statAuto: false, cards: null, clash: 0, tied: null,
    winner: null, cardsWon: 0, potCarried: null, eliminated: [] });
  for (const ev of events ?? []) {
    switch (ev.type) {
      case 'roundStarted':
        cur = fresh(ev.round, ev.caller);
        break;
      case 'statCalled':
        cur ??= fresh(null, null);
        cur = { ...cur, stat: ev.stat, statSeat: ev.seat, statAuto: Boolean(ev.auto) };
        break;
      case 'clashStarted':
        cur ??= fresh(null, null);
        cur = { ...cur, clash: ev.level, tied: ev.tiedSeats };
        break;
      case 'revealed':
        cur ??= fresh(null, null);
        cur = { ...cur, cards: ev.cards };                         // in a clash this is the latest reveal
        break;
      case 'roundWon':
        cur ??= fresh(null, null);
        cur = { ...cur, winner: ev.seat, cardsWon: ev.cardsWon };
        last = cur;
        break;
      case 'potCarried':
        cur ??= fresh(null, null);
        cur = { ...cur, potCarried: ev.potSize };
        last = cur;
        break;
      case 'playerEliminated':
        cur ??= fresh(null, null);
        cur = { ...cur, eliminated: [...cur.eliminated, { seat: ev.seat, placement: ev.placement }] };
        if (last && last.round === cur.round) last = cur;          // elimination is reported right after the win
        break;
      default:                                                     // cardChosen, gameOver: nothing to fold
    }
  }
  return { current: cur, lastRound: last };
}

/** One line per event for the feed. `statLabel(key)` turns a stat key into its label. */
export function eventText(ev, game, statLabel = (k) => k) {
  const name = (seat) => seatName(game, seat);
  const auto = ev.auto ? ' (time ran out)' : '';
  switch (ev.type) {
    case 'roundStarted': return `Round ${ev.round}: ${name(ev.caller)} calls`;
    case 'cardChosen': return `${name(ev.seat)} chose a card${auto}`;
    case 'statCalled': return ev.seat == null
      ? `Nobody called in time: ${statLabel(ev.stat)} was picked at random`
      : `${name(ev.seat)} called ${statLabel(ev.stat)}${auto}`;
    case 'revealed': return 'Cards revealed';
    case 'roundWon': return `${name(ev.seat)} won the round and takes ${ev.cardsWon} card${ev.cardsWon === 1 ? '' : 's'}`;
    case 'clashStarted': return `CLASH${ev.level > 1 ? ` ×${ev.level}` : ''}: ${(ev.tiedSeats ?? []).map(name).join(' and ')} tied (pot ${ev.potSize})`;
    case 'potCarried': return `Nobody could settle the tie: the pot (${ev.potSize}) carries over`;
    case 'playerEliminated': return `${name(ev.seat)} is out (#${ev.placement})`;
    case 'gameOver': return ev.endedByRoundCap ? 'Round cap reached: game over' : 'Game over';
    default: return '';
  }
}

/** The card a player is calling a stat from: the one they committed, else their top card. */
export const callCard = (view) => view?.committed ?? view?.ownHand?.[0] ?? null;

/** Is there a clash on, who is in it, and may I race to call? */
export function clashInfo({ view, game, mySeat }) {
  const active = Boolean(view) && (view.clashLevel > 0 || String(view.phase ?? '').startsWith('CLASH'));
  const seats = active ? (view.contenders ?? []) : [];
  return { active, level: view?.clashLevel ?? 0, seats, names: seats.map((s) => seatName(game, s)), iAmContender: seats.includes(mySeat), canRace: Boolean(view?.canCall) };
}

export function ordinal(n) {
  const v = n % 100;
  if (v >= 11 && v <= 13) return `${n}th`;
  return `${n}${({ 1: 'st', 2: 'nd', 3: 'rd' })[n % 10] ?? 'th'}`;
}

/** Placements from a finished game body (seat.placement), for a reconnect after the game ended. */
export function overFromGame(game) {
  const placements = {};
  for (const s of game?.seats ?? []) if (s.placement != null) placements[s.seat] = s.placement;
  return { placements, endedByRoundCap: false };
}

/** Everything the game-over screen shows. */
export function overSummary({ over, game, mySeat }) {
  const placements = over?.placements ?? {};
  const rows = (game?.seats ?? [])
    .map((s) => ({ seat: s.seat, name: seatName(game, s.seat), isMe: s.seat === mySeat, placement: placements[s.seat] ?? placements[String(s.seat)] ?? null }))
    .sort((a, b) => (a.placement ?? 99) - (b.placement ?? 99) || a.seat - b.seat);
  const mine = rows.find((r) => r.isMe)?.placement ?? null;
  const winners = rows.filter((r) => r.placement === 1).length;
  const title = mine == null ? 'Game over'
    : mine === 1 ? (winners > 1 ? 'You tied for 1st!' : 'You won!')
    : `You finished ${ordinal(mine)}`;
  return { title, rows, won: mine === 1, endedByRoundCap: Boolean(over?.endedByRoundCap) };
}
