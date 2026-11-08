import test from 'node:test';
import assert from 'node:assert/strict';
import { wsUrl, backoffDelay, skewMs, initialLive, applyMessage, seatName, seatRows, phaseInfo, secondsLeft, createLiveClient, PING_MS } from '../js/live.js';

const game = (over = {}) => ({
  id: 'g1', joinCode: 'K7Q2XM', status: 'WAITING', maxPlayers: 4, deck: { id: 'd', name: 'Wrestling' }, hostSeat: 1,
  seats: [{ seat: 1, kind: 'HUMAN', username: 'ann', difficulty: null, placement: null }, { seat: 2, kind: 'CPU', username: null, difficulty: 'HARD', placement: null }],
  ...over,
});

test('wsUrl: http -> ws, https -> wss, empty apiBase = this page\'s origin', () => {
  assert.equal(wsUrl({ apiBase: 'http://localhost:8080', location: {}, code: 'k7q2xm' }), 'ws://localhost:8080/ws/games/K7Q2XM');
  assert.equal(wsUrl({ apiBase: 'https://api.example.com', location: {}, code: 'ABC123' }), 'wss://api.example.com/ws/games/ABC123');
  assert.equal(wsUrl({ apiBase: '', location: { protocol: 'https:', host: 'cards.onrender.com' }, code: 'ABC123' }), 'wss://cards.onrender.com/ws/games/ABC123');
  assert.equal(wsUrl({ apiBase: 'http://localhost:8080/some/path?x=1', location: {}, code: 'ABC123' }), 'ws://localhost:8080/ws/games/ABC123');
});

test('backoffDelay grows, is capped at 30 s, and keeps 50-100 % jitter', () => {
  assert.equal(backoffDelay(0, () => 1), 500);
  assert.equal(backoffDelay(0, () => 0), 250);
  assert.equal(backoffDelay(3, () => 1), 4000);
  assert.equal(backoffDelay(20, () => 1), 30000);
  assert.equal(backoffDelay(20, () => 0), 15000);
  for (let a = 0; a < 12; a++) assert.ok(backoffDelay(a, () => 0.5) <= 30000);
});

test('skewMs is server time minus our clock; junk gives 0', () => {
  assert.equal(skewMs('2026-11-08T10:00:05Z', Date.parse('2026-11-08T10:00:00Z')), 5000);
  assert.equal(skewMs('not a date', 123), 0);
});

test('applyMessage: lobby / started / view / error / pong', () => {
  let s = initialLive();
  s = applyMessage(s, { type: 'lobby', serverTime: '2026-11-08T10:00:00Z', mySeat: 1, game: game() }, Date.parse('2026-11-08T10:00:02Z'));
  assert.equal(s.mySeat, 1);
  assert.equal(s.game.joinCode, 'K7Q2XM');
  assert.equal(s.skewMs, -2000);
  assert.equal(s.view, null);

  s = applyMessage(s, { type: 'started', mySeat: 1, game: game({ status: 'IN_PROGRESS' }) });
  assert.equal(s.started, true);
  assert.equal(s.game.status, 'IN_PROGRESS');

  s = applyMessage(s, { type: 'view', seat: 1, phase: 'AWAIT_CALL', handSizes: { 1: 6, 2: 6 } });
  assert.equal(s.view.phase, 'AWAIT_CALL');

  const before = s;
  s = applyMessage(s, { type: 'pong', serverTime: '2026-11-08T10:00:09Z' }, Date.parse('2026-11-08T10:00:09Z'));
  assert.equal(s.view, before.view);                                       // pong changes only the clock

  s = applyMessage(s, { type: 'error', error: 'bad message' });
  assert.equal(s.lastError, 'bad message');
  assert.notEqual(before, s);                                              // never mutates the old state
});

test('a lobby message for a WAITING game clears any old table view; a reconnect to a started game keeps it', () => {
  let s = applyMessage(initialLive(), { type: 'view', phase: 'AWAIT_CALL' });
  s = applyMessage(s, { type: 'lobby', mySeat: 1, game: game({ status: 'IN_PROGRESS' }) });
  assert.equal(s.view.phase, 'AWAIT_CALL');
  s = applyMessage(s, { type: 'lobby', mySeat: 1, game: game({ status: 'WAITING' }) });
  assert.equal(s.view, null);
});

test('seatName and seatRows', () => {
  const g = game();
  assert.equal(seatName(g, 1), 'ann');
  assert.equal(seatName(g, 2), 'CPU (hard)');
  assert.equal(seatName(g, 5), 'Seat 5');
  const rows = seatRows(g, { callerSeat: 1, clashLevel: 1, contenders: [1, 2], handSizes: { 1: 3, 2: 0 }, placements: {} }, 1);
  assert.equal(rows[0].isMe, true);
  assert.equal(rows[0].isCaller, true);
  assert.equal(rows[0].isContender, true);
  assert.equal(rows[1].out, true);
  assert.equal(rows[1].handSize, 0);
  assert.equal(seatRows(g, null, 1)[0].handSize, null);                    // no view yet
  assert.equal(seatRows(g, { contenders: [1, 2], clashLevel: 0, handSizes: {} }, 1)[0].isContender, false); // contenders only matter in a clash
});

test('phaseInfo reads well for the caller, the others, clashes and the end', () => {
  const g = game();
  const v = (o) => ({ phase: 'AWAIT_CALL', callerSeat: 1, clashLevel: 0, canChoose: false, ...o });
  assert.equal(phaseInfo({ view: null, game: g, mySeat: 1 }).text, 'Connecting to the table…');
  assert.equal(phaseInfo({ view: v(), game: g, mySeat: 1 }).text, 'Your turn: call a stat');
  assert.equal(phaseInfo({ view: v(), game: g, mySeat: 2 }).text, 'ann is calling a stat');
  assert.equal(phaseInfo({ view: v({ phase: 'AWAITING_CALL' }), game: g, mySeat: 1 }).text, 'Your turn: call a stat');
  assert.equal(phaseInfo({ view: v({ phase: 'AWAIT_CHOOSE_OTHERS', canChoose: true }), game: g, mySeat: 2, statLabel: 'Height' }).text, 'Stat called — Height: choose your card');
  const clash = phaseInfo({ view: v({ phase: 'CLASH_AWAIT_CALL', clashLevel: 2 }), game: g, mySeat: 1 });
  assert.equal(clash.clash, true);
  assert.match(clash.text, /CLASH! ×2/);
  assert.equal(phaseInfo({ view: v({ phase: 'GAME_OVER' }), game: g, mySeat: 1 }).text, 'Game over');
  assert.equal(phaseInfo({ view: v({ phase: 'SOMETHING_NEW' }), game: g, mySeat: 1 }).text, 'something new');
});

// ---------------- the client, with a fake socket and a hand-cranked clock ----------------
function harness(opts = {}) {
  const sockets = [];
  class FakeSocket {
    constructor(url) { this.url = url; this.sent = []; this.closed = false; sockets.push(this); }
    send(d) { this.sent.push(JSON.parse(d)); }
    close() { this.closed = true; }
    open() { this.onopen?.(); }
    message(obj) { this.onmessage?.({ data: typeof obj === 'string' ? obj : JSON.stringify(obj) }); }
    drop() { this.onclose?.({ code: 1006 }); }
  }
  const timeouts = []; const intervals = []; const statuses = []; const messages = []; const fatals = [];
  const client = createLiveClient({
    url: 'ws://x/ws/games/ABC123',
    WebSocketImpl: FakeSocket,
    setTimeoutImpl: (fn, ms) => { timeouts.push({ fn, ms, cleared: false }); return timeouts.length - 1; },
    clearTimeoutImpl: (i) => { if (timeouts[i]) timeouts[i].cleared = true; },
    setIntervalImpl: (fn, ms) => { intervals.push({ fn, ms, cleared: false }); return intervals.length - 1; },
    clearIntervalImpl: (i) => { if (intervals[i]) intervals[i].cleared = true; },
    rng: () => 1,
    onStatus: (s) => statuses.push(s), onMessage: (m) => messages.push(m), onFatal: (r) => fatals.push(r),
    ...opts,
  });
  return { client, sockets, timeouts, intervals, statuses, messages, fatals };
}
const flush = () => new Promise((r) => setImmediate(r));

test('client: connects, reports open, delivers messages, ignores junk frames', () => {
  const h = harness();
  h.client.start();
  assert.deepEqual(h.statuses, ['connecting']);
  h.sockets[0].open();
  assert.equal(h.client.isOpen(), true);
  assert.deepEqual(h.statuses, ['connecting', 'open']);
  h.sockets[0].message({ type: 'lobby', mySeat: 1 });
  h.sockets[0].message('this is not json');
  assert.deepEqual(h.messages, [{ type: 'lobby', mySeat: 1 }]);
});

test('client: pings every 25 s while open and stops when the socket drops', async () => {
  const h = harness();
  h.client.start();
  h.sockets[0].open();
  assert.equal(h.intervals[0].ms, PING_MS);
  h.intervals[0].fn();
  assert.deepEqual(h.sockets[0].sent, [{ type: 'ping' }]);
  h.sockets[0].drop();
  await flush();
  assert.equal(h.intervals[0].cleared, true);
});

test('client: reconnects with growing back-off after a drop, and resets the back-off once connected', async () => {
  const h = harness();
  h.client.start();
  h.sockets[0].open();
  h.sockets[0].drop();                       // was open: just retry
  await flush();
  assert.equal(h.statuses.at(-1), 'reconnecting');
  assert.equal(h.timeouts[0].ms, 500);
  h.timeouts[0].fn();                        // reconnect attempt 1 ...
  h.sockets[1].drop(); await flush();        // ... fails before opening (probe says: transient)
  assert.equal(h.timeouts[1].ms, 1000);
  h.timeouts[1].fn();
  h.sockets[2].open();                       // connected again: back-off resets
  h.sockets[2].drop(); await flush();
  assert.equal(h.timeouts[2].ms, 500);
  assert.equal(h.sockets.length, 3);
});

test('client: a refused handshake with a fatal reason stops for good (no reconnect loop on 401/403/404)', async () => {
  for (const reason of ['signed-out', 'not-seated', 'not-found']) {
    const h = harness({ probe: async () => reason });
    h.client.start();
    h.sockets[0].drop();                     // never opened
    await flush();
    assert.deepEqual(h.fatals, [reason]);
    assert.equal(h.statuses.at(-1), 'closed');
    assert.equal(h.timeouts.length, 0);      // nothing scheduled
  }
});

test('client: probe failing or returning null means a transient error: keep retrying', async () => {
  const h = harness({ probe: async () => { throw new Error('network'); } });
  h.client.start();
  h.sockets[0].drop(); await flush();
  assert.equal(h.fatals.length, 0);
  assert.equal(h.timeouts.length, 1);
});

test('client: close() stops everything, closes the socket and never reconnects', async () => {
  const h = harness();
  h.client.start();
  h.sockets[0].open();
  h.client.close();
  assert.equal(h.sockets[0].closed, true);
  assert.equal(h.statuses.at(-1), 'closed');
  h.sockets[0].drop(); await flush();        // the close event arriving afterwards must not retry
  assert.equal(h.timeouts.length, 0);
  assert.equal(h.client.isOpen(), false);
});

test('client: close() while a retry is pending cancels it', async () => {
  const h = harness();
  h.client.start();
  h.sockets[0].open(); h.sockets[0].drop(); await flush();
  h.client.close();
  assert.equal(h.timeouts[0].cleared, true);
});

test('secondsLeft counts down to a server deadline, corrected for clock skew', () => {
  const now = Date.parse('2026-11-08T10:00:00Z');
  assert.equal(secondsLeft('2026-11-08T10:00:05Z', now), 5);
  assert.equal(secondsLeft('2026-11-08T10:00:04.200Z', now), 5);              // rounds up: never shows 0 while time remains
  assert.equal(secondsLeft('2026-11-08T09:59:00Z', now), 0);                  // past: 0, never negative
  assert.equal(secondsLeft('2026-11-08T10:00:05Z', now, 2000), 3);            // our clock is 2 s behind the server
  assert.equal(secondsLeft(null, now), null);
  assert.equal(secondsLeft('junk', now), null);
});
