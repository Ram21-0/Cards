import test from 'node:test';
import assert from 'node:assert/strict';
import { wsUrl, backoffDelay, skewMs, initialLive, applyMessage, seatName, seatRows, phaseInfo, secondsLeft, createLiveClient, PING_MS,
  foldRounds, eventText, callCard, clashInfo, ordinal, overFromGame, overSummary, pileList, pileTitle } from '../js/live.js';

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

// ---------------- playing: events, rounds, clash, game over ----------------
const card = (id, name) => ({ id, name, stats: {} });

test('foldRounds: a normal round from start to win', () => {
  const evs = [
    { type: 'roundStarted', round: 3, caller: 1, seats: [1, 2] },
    { type: 'cardChosen', seat: 2, auto: false },
    { type: 'statCalled', seat: 1, stat: 'weight', auto: false },
    { type: 'revealed', cards: { 1: card(4, 'A'), 2: card(9, 'B') } },
    { type: 'roundWon', seat: 2, cardsWon: 5 },
  ];
  const { current, lastRound } = foldRounds({ current: null, lastRound: null }, evs);
  assert.equal(lastRound.round, 3);
  assert.equal(lastRound.stat, 'weight');
  assert.equal(lastRound.winner, 2);
  assert.equal(lastRound.cardsWon, 5);
  assert.equal(lastRound.cards[1].name, 'A');
  assert.equal(current, lastRound);
});

test('foldRounds: a clash keeps the latest reveal and the clash level, and a CPU-only batch ends on the latest round', () => {
  const evs = [
    { type: 'roundStarted', round: 1, caller: 1, seats: [1, 2, 3] },
    { type: 'statCalled', seat: 1, stat: 'fight', auto: false },
    { type: 'revealed', cards: { 1: card(1, 'a'), 2: card(2, 'b'), 3: card(3, 'c') } },
    { type: 'clashStarted', tiedSeats: [1, 2], level: 1, potSize: 3 },
    { type: 'statCalled', seat: null, stat: 'rank', auto: true },
    { type: 'revealed', cards: { 1: card(5, 'd'), 2: card(6, 'e') } },
    { type: 'roundWon', seat: 2, cardsWon: 5 },
    { type: 'roundStarted', round: 2, caller: 2, seats: [1, 2, 3] },
  ];
  const { current, lastRound } = foldRounds({ current: null, lastRound: null }, evs);
  assert.equal(lastRound.round, 1);
  assert.equal(lastRound.clash, 1);
  assert.deepEqual(lastRound.tied, [1, 2]);
  assert.equal(lastRound.stat, 'rank');            // the tie-break stat
  assert.equal(lastRound.statAuto, true);
  assert.deepEqual(Object.keys(lastRound.cards), ['1', '2']);
  assert.equal(current.round, 2);                  // the next round has started but is not "last" yet
  assert.equal(current.winner, null);
});

test('foldRounds: pot carried and eliminations attach to the round they belong to', () => {
  let st = foldRounds({ current: null, lastRound: null }, [
    { type: 'roundStarted', round: 7, caller: 1, seats: [1, 2] },
    { type: 'revealed', cards: { 1: card(1, 'a'), 2: card(2, 'b') } },
    { type: 'roundWon', seat: 1, cardsWon: 2 },
    { type: 'playerEliminated', seat: 2, placement: 2 },
  ]);
  assert.deepEqual(st.lastRound.eliminated, [{ seat: 2, placement: 2 }]);
  st = foldRounds(st, [{ type: 'roundStarted', round: 8, caller: 1, seats: [1] }, { type: 'potCarried', potSize: 4 }]);
  assert.equal(st.lastRound.round, 8);
  assert.equal(st.lastRound.potCarried, 4);
  assert.equal(foldRounds({ current: null, lastRound: null }, []).lastRound, null);
});

test('foldRounds tolerates events that arrive without a roundStarted (e.g. after a reconnect)', () => {
  const { lastRound } = foldRounds({ current: null, lastRound: null }, [{ type: 'roundWon', seat: 2, cardsWon: 3 }]);
  assert.equal(lastRound.winner, 2);
  assert.equal(lastRound.round, null);
});

test('eventText wording for every event, including auto moves and clashes', () => {
  const g = game();
  const label = (k) => ({ weight: 'Weight', rank: 'Rank' })[k] ?? k;
  assert.equal(eventText({ type: 'roundStarted', round: 2, caller: 1 }, g, label), 'Round 2: ann calls');
  assert.equal(eventText({ type: 'cardChosen', seat: 2, auto: false }, g), 'CPU (hard) chose a card');
  assert.equal(eventText({ type: 'cardChosen', seat: 1, auto: true }, g), 'ann chose a card (time ran out)');
  assert.equal(eventText({ type: 'statCalled', seat: 1, stat: 'weight', auto: false }, g, label), 'ann called Weight');
  assert.equal(eventText({ type: 'statCalled', seat: 1, stat: 'weight', auto: true }, g, label), 'ann called Weight (time ran out)');
  assert.equal(eventText({ type: 'statCalled', seat: null, stat: 'rank', auto: true }, g, label), 'Nobody called in time: Rank was picked at random');
  assert.equal(eventText({ type: 'roundWon', seat: 2, cardsWon: 1 }, g), 'CPU (hard) won the round and takes 1 card');
  assert.equal(eventText({ type: 'roundWon', seat: 1, cardsWon: 5 }, g), 'ann won the round and takes 5 cards');
  assert.equal(eventText({ type: 'clashStarted', tiedSeats: [1, 2], level: 2, potSize: 6 }, g), 'CLASH ×2: ann and CPU (hard) tied (pot 6)');
  assert.equal(eventText({ type: 'potCarried', potSize: 4 }, g), 'Nobody could settle the tie: the pot (4) carries over');
  assert.equal(eventText({ type: 'playerEliminated', seat: 2, placement: 2 }, g), 'CPU (hard) is out (#2)');
  assert.equal(eventText({ type: 'gameOver', endedByRoundCap: true }, g), 'Round cap reached: game over');
  assert.equal(eventText({ type: 'nonsense' }, g), '');
});

test('applyMessage: events build the feed (capped) and the last round; gameOver stores the result', () => {
  let s = initialLive();
  s = applyMessage(s, { type: 'lobby', mySeat: 1, game: game({ status: 'IN_PROGRESS' }) });
  s = applyMessage(s, { type: 'events', events: [{ type: 'roundStarted', round: 1, caller: 1, seats: [1, 2] }, { type: 'statCalled', seat: 1, stat: 'rank', auto: false }, { type: 'roundWon', seat: 1, cardsWon: 2 }] });
  assert.equal(s.feed.length, 3);
  assert.equal(s.lastRound.winner, 1);
  for (let i = 0; i < 20; i++) s = applyMessage(s, { type: 'events', events: [{ type: 'cardChosen', seat: 2, auto: false }] });
  assert.equal(s.feed.length, 12);                     // capped, newest kept
  s = applyMessage(s, { type: 'gameOver', mySeat: 1, placements: { 1: 1, 2: 2 }, endedByRoundCap: true, game: game({ status: 'FINISHED' }) });
  assert.deepEqual(s.over, { placements: { 1: 1, 2: 2 }, endedByRoundCap: true });
  assert.equal(s.game.status, 'FINISHED');
});

test('applyMessage: an older view (a slow REST answer) never replaces a newer one', () => {
  let s = applyMessage(initialLive(), { type: 'view', serverTime: '2026-11-08T10:00:05Z', round: 2 });
  s = applyMessage(s, { type: 'view', serverTime: '2026-11-08T10:00:01Z', round: 1 });
  assert.equal(s.view.round, 2);
  s = applyMessage(s, { type: 'view', serverTime: '2026-11-08T10:00:05Z', round: 2, phase: 'X' });   // same time: accepted
  assert.equal(s.view.phase, 'X');
  s = applyMessage(s, { type: 'view', serverTime: '2026-11-08T10:00:09Z', round: 3 });
  assert.equal(s.view.round, 3);
});

test('applyMessage: reconnecting to a finished game gets the result from the seat placements', () => {
  const finished = game({ status: 'FINISHED', seats: [{ seat: 1, kind: 'HUMAN', username: 'ann', placement: 2 }, { seat: 2, kind: 'HUMAN', username: 'bob', placement: 1 }] });
  const s = applyMessage(initialLive(), { type: 'lobby', mySeat: 1, game: finished });
  assert.deepEqual(s.over.placements, { 1: 2, 2: 1 });
});

test('callCard prefers the committed card, then the top card', () => {
  assert.equal(callCard({ committed: card(5, 'c'), ownHand: [card(1, 'a')] }).id, 5);
  assert.equal(callCard({ committed: null, ownHand: [card(1, 'a'), card(2, 'b')] }).id, 1);
  assert.equal(callCard({ committed: null, ownHand: [] }), null);
  assert.equal(callCard(null), null);
});

test('clashInfo: who is in the clash and whether I can race', () => {
  const g = game();
  const none = clashInfo({ view: { phase: 'AWAIT_CALL', clashLevel: 0, contenders: [1, 2] }, game: g, mySeat: 1 });
  assert.equal(none.active, false);
  assert.deepEqual(none.seats, []);
  const c = clashInfo({ view: { phase: 'CLASH_AWAIT_CALL', clashLevel: 2, contenders: [1, 2], canCall: true }, game: g, mySeat: 1 });
  assert.equal(c.active, true);
  assert.equal(c.level, 2);
  assert.deepEqual(c.names, ['ann', 'CPU (hard)']);
  assert.equal(c.iAmContender, true);
  assert.equal(c.canRace, true);
  assert.equal(clashInfo({ view: { phase: 'CLASH_AWAIT_CHOOSE', clashLevel: 1, contenders: [2, 3] }, game: g, mySeat: 1 }).iAmContender, false);
  assert.equal(clashInfo({ view: null, game: g, mySeat: 1 }).active, false);
});

test('phaseInfo: clash wording depends on whether I can race', () => {
  const g = game();
  const base = { phase: 'CLASH_AWAIT_CALL', callerSeat: 1, clashLevel: 1 };
  assert.match(phaseInfo({ view: { ...base, canCall: true }, game: g, mySeat: 1 }).text, /Call a stat now/);
  assert.match(phaseInfo({ view: { ...base, canCall: false }, game: g, mySeat: 1 }).text, /Waiting for a stat/);
});

test('ordinal', () => {
  assert.deepEqual([1, 2, 3, 4, 11, 12, 13, 21, 22, 101, 111].map(ordinal), ['1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd', '101st', '111th']);
});

test('overSummary: winner, runner-up, shared first place, and sorted rows', () => {
  const g = game({ seats: [{ seat: 1, kind: 'HUMAN', username: 'ann' }, { seat: 2, kind: 'CPU', difficulty: 'EASY' }, { seat: 3, kind: 'HUMAN', username: 'cy' }] });
  const won = overSummary({ over: { placements: { 1: 1, 2: 2, 3: 3 }, endedByRoundCap: false }, game: g, mySeat: 1 });
  assert.equal(won.title, 'You won!');
  assert.equal(won.won, true);
  assert.deepEqual(won.rows.map((r) => r.name), ['ann', 'CPU (easy)', 'cy']);
  const third = overSummary({ over: { placements: { 1: 1, 2: 2, 3: 3 } }, game: g, mySeat: 3 });
  assert.equal(third.title, 'You finished 3rd');
  assert.equal(third.rows.at(-1).isMe, true);
  const tie = overSummary({ over: { placements: { 1: 1, 2: 2, 3: 1 }, endedByRoundCap: true }, game: g, mySeat: 3 });
  assert.equal(tie.title, 'You tied for 1st!');
  assert.equal(tie.endedByRoundCap, true);
  assert.deepEqual(tie.rows.map((r) => r.seat), [1, 3, 2]);                // shared 1st by seat, then 2nd
  assert.equal(overSummary({ over: { placements: {} }, game: g, mySeat: 1 }).title, 'Game over');   // placements unknown
  assert.equal(overFromGame(g).placements[1], undefined);
});

test('pileList: your own cards in turn order, names only; null when the server does not send it', () => {
  assert.equal(pileList(null), null);
  assert.equal(pileList({}), null);                                           // older server: no section
  assert.equal(pileList({ ownPile: 'nope' }), null);
  assert.deepEqual(pileList({ ownPile: [] }), []);                            // sent but empty: only the card in play is left
  const rows = pileList({ ownPile: [{ id: 17, name: 'Savage Kane' }, { id: 4, name: 'Iron Duke' }] });
  assert.deepEqual(rows, [{ position: 1, id: 17, name: 'Savage Kane' }, { position: 2, id: 4, name: 'Iron Duke' }]);
  assert.deepEqual(pileList({ ownPile: ['A', 'B'] }).map((r) => r.name), ['A', 'B']);   // plain names are fine too
  assert.deepEqual(pileList({ ownPile: [{ id: 1 }] })[0], { position: 1, id: 1, name: '' });
});

test('pileTitle: "after this turn" once a card is in play, "your pile" while you can still choose', () => {
  assert.equal(pileTitle({ canChoose: false }), 'Your cards after this turn');
  assert.equal(pileTitle({ canChoose: true, committed: null }), 'Your pile, in order');
  assert.equal(pileTitle({ canChoose: true, committed: { id: 1 } }), 'Your cards after this turn');
  assert.equal(pileTitle(null), 'Your cards after this turn');
});
