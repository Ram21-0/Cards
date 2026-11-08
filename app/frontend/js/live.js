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

export const initialLive = () => ({ game: null, mySeat: null, view: null, skewMs: 0, started: false, lastError: null });

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
      break;
    case 'view':
      next.view = msg;
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
      return { text: `CLASH!${view.clashLevel > 1 ? ` ×${view.clashLevel}` : ''} The first call decides the stat`, clash: true };
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
