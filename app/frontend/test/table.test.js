import test from 'node:test';
import assert from 'node:assert/strict';
import {
  holdSeconds, isOut, tableKind, callRule, bestIndex, resultRows, resultHead, seatChips, pileView, logGroups,
  timerTotal, timerModel, urgentAtFor, overHead, clampHold, holdText, resultGroups, carriedCards,
} from '../js/table.js';

const stats = [
  { key: 'rank', label: 'Rank', higherWins: false },
  { key: 'fight', label: 'Fight', higherWins: true },
  { key: 'weight', label: 'Weight', higherWins: true },
];
const card = (id, name, fight, weight = 100, rank = 5) => ({
  id, name, stats: { rank: { value: rank, displayValue: String(rank) }, fight: { value: fight, displayValue: String(fight) }, weight: { value: weight, displayValue: `${weight} lb` } },
});
const game = (extra = {}) => ({
  status: 'IN_PROGRESS', joinCode: 'K7Q2XM', options: { resultHoldSeconds: 6, turnTimeoutSeconds: 20, clashCallSeconds: 5 },
  seats: [
    { seat: 1, kind: 'HUMAN', username: 'me' }, { seat: 2, kind: 'HUMAN', username: 'ann' },
    { seat: 3, kind: 'CPU', difficulty: 'HARD' }, { seat: 4, kind: 'HUMAN', username: 'bob' },
  ],
  ...extra,
});
const view = (extra = {}) => ({
  seat: 1, phase: 'AWAIT_CALL', round: 7, callerSeat: 1, stat: null, clashLevel: 0, potSize: 0, deadline: null, contenders: [1, 2, 3, 4],
  handSizes: { 1: 6, 2: 7, 3: 5, 4: 6 }, ownHand: [card(1, 'JOHN CENA', 90)], committed: null, canChoose: false, canCall: true, ownPile: [], placements: {}, ...extra,
});
const rt = (extra = {}) => ({ lastRound: null, feed: [], ...extra });
const kind = (v, over = {}) => tableKind({ game: game(), view: v, rt: rt(), mySeat: 1, ...over });

test('holdSeconds: the base, a few seconds more after a clash or an elimination, none when switched off', () => {
  assert.equal(holdSeconds({ resultHoldSeconds: 6 }, { clash: 0, eliminated: [] }), 6);
  assert.equal(holdSeconds({ resultHoldSeconds: 6 }, { clash: 1, eliminated: [] }), 9);
  assert.equal(holdSeconds({ resultHoldSeconds: 6 }, { clash: 0, eliminated: [{ seat: 2, placement: 4 }] }), 9);
  assert.equal(holdSeconds({}, null), 6);                            // a server that does not send the option
  assert.equal(holdSeconds({ resultHoldSeconds: 0 }, { clash: 2 }), 0);
  assert.equal(holdSeconds({ resultHoldSeconds: 10 }, { clash: 1 }), 13);
});

test('isOut: placed, or no cards left; never without a view or a seat', () => {
  assert.equal(isOut(view(), 1), false);
  assert.equal(isOut(view({ placements: { 1: 3 } }), 1), true);
  assert.equal(isOut(view({ handSizes: { 1: 0, 2: 4 } }), 1), true);
  assert.equal(isOut(null, 1), false);
  assert.equal(isOut(view({ placements: { 1: 3 } }), null), false);
});

test('tableKind: the seven states', () => {
  assert.equal(kind(null), 'connecting');
  assert.equal(kind(view()), 'call');
  assert.equal(kind(view({ canCall: false, callerSeat: 2 })), 'wait');
  assert.equal(kind(view({ canCall: false, canChoose: true, phase: 'AWAIT_CHOOSE_OTHERS', stat: 'fight', callerSeat: 2 })), 'choose');
  assert.equal(kind(view({ canCall: false, canChoose: true, phase: 'AWAIT_CHOOSE_CALLER' })), 'choose');         // the caller picks a card first
  assert.equal(kind(view({ canChoose: true, canCall: true, phase: 'AWAIT_CHOOSE_CALLER' })), 'choose');          // even if the server also says canCall
  assert.equal(kind(view({ phase: 'CLASH_AWAIT_CALL', clashLevel: 1, contenders: [1, 2] })), 'clash');
  assert.equal(kind(view({ phase: 'CLASH_AWAIT_CALL', clashLevel: 1, contenders: [2, 3], canCall: false })), 'clashWatch');
  assert.equal(kind(view({ phase: 'CLASH_AWAIT_CALL', clashLevel: 1, contenders: [1, 2], canCall: false })), 'clash');   // I already called
  assert.equal(kind(view({ placements: { 1: 3 }, canCall: false })), 'out');
  assert.equal(tableKind({ game: game({ status: 'FINISHED' }), view: null, rt: rt(), mySeat: 1 }), 'over');
});

test('tableKind: the round result stays until Continue is tapped for that very result', () => {
  const r = view({ phase: 'ROUND_RESULT', canCall: false, deadline: '2026-11-08T10:00:06Z' });
  assert.equal(kind(r), 'result');
  assert.equal(kind(r, { ui: { dismissedKey: '2026-11-08T10:00:06Z' } }), 'next');
  assert.equal(kind(r, { ui: { dismissedKey: '2026-11-08T09:59:00Z' } }), 'result');        // an older result was dismissed, this one is new
  assert.equal(kind(view({ phase: 'ROUND_RESULT', canCall: false, deadline: '2026-11-08T10:00:06Z', placements: { 1: 4 } })), 'out');
});

test('tableKind: the last round has no server hold: its result is shown for a while, then the game-over screen', () => {
  const g = game({ status: 'FINISHED' });
  const r = rt({ lastRound: { round: 38, winner: 2, cards: {} } });
  assert.equal(tableKind({ game: g, view: null, rt: r, mySeat: 1, ui: { overHoldUntil: 10_000 }, nowMs: 9_000 }), 'result');
  assert.equal(tableKind({ game: g, view: null, rt: r, mySeat: 1, ui: { overHoldUntil: 10_000 }, nowMs: 10_001 }), 'over');
  assert.equal(tableKind({ game: g, view: null, rt: rt(), mySeat: 1, ui: { overHoldUntil: 10_000 }, nowMs: 9_000 }), 'over');   // nothing to show: straight to the end
});

test('callRule names the stats where the lowest wins', () => {
  assert.equal(callRule(stats), 'Call a stat. Highest wins, except Rank where the lowest wins.');
  assert.equal(callRule([{ key: 'a', label: 'A', higherWins: true }]), 'Call a stat. Highest wins.');
});

test('bestIndex: the best card for a stat, first on ties, the lowest for a lower-wins stat', () => {
  const hand = [card(1, 'A', 70, 200, 9), card(2, 'B', 90, 150, 2), card(3, 'C', 90, 300, 5)];
  assert.equal(bestIndex(hand, stats, 'fight'), 1);        // 90 twice: the first one
  assert.equal(bestIndex(hand, stats, 'weight'), 2);
  assert.equal(bestIndex(hand, stats, 'rank'), 1);         // lowest wins
  assert.equal(bestIndex(hand, stats, 'unknown'), -1);
  assert.equal(bestIndex([], stats, 'fight'), -1);
});

const round = (extra = {}) => ({
  round: 7, stat: 'fight', clash: 0, winner: 2, cardsWon: 4, potCarried: null, eliminated: [],
  cards: { 1: card(1, 'JOHN CENA', 89), 2: card(9, 'RANDY ORTON', 90), 3: card(5, 'KANE', 70), 4: card(6, 'BIG SHOW', 89) },
  reveals: [], ...extra,
});

test('resultRows: the winner first, then best to worst, ties with the winner marked', () => {
  const rows = resultRows(round(), game(), 1, stats);
  assert.deepEqual(rows.map((r) => r.name), ['RANDY ORTON', 'JOHN CENA', 'BIG SHOW', 'KANE']);
  assert.deepEqual(rows.map((r) => r.who), ['ann', 'you', 'bob', 'Blaze (CPU)']);
  assert.deepEqual(rows.map((r) => r.winner), [true, false, false, false]);
  const tie = resultRows(round({ cards: { 1: card(1, 'A', 90), 2: card(2, 'B', 90), 3: card(3, 'C', 80) } }), game(), 1, stats);
  assert.deepEqual(tie.map((r) => r.tie), [false, true, false]);
  // lower wins for rank: the smallest number is best
  const low = resultRows(round({ stat: 'rank', winner: 3, cards: { 1: card(1, 'A', 1, 1, 9), 3: card(3, 'C', 1, 1, 2), 4: card(4, 'D', 1, 1, 5) } }), game(), 1, stats);
  assert.deepEqual(low.map((r) => r.name), ['C', 'D', 'A']);
});

test('resultHead: I win / someone wins / a clash / nobody wins, with chips for the pool, eliminations and big cards', () => {
  const mine = resultHead({ lr: round({ winner: 1, cardsWon: 4, cards: { 1: card(1, 'JOHN CENA', 90), 2: card(2, 'RANDY ORTON', 89) } }), game: game(), mySeat: 1, stats,
    big: [{ kind: 'won', name: 'RANDY ORTON' }] });
  assert.equal(mine.h1, 'You win the round');
  assert.equal(mine.tone, 'win');
  assert.deepEqual(mine.sub, [{ b: 'FIGHT' }, " JOHN CENA 90 beats ann's RANDY ORTON 89."]);
  assert.deepEqual(mine.chips.map((c) => c.text), ['You take 4 cards', 'Big card: you won RANDY ORTON']);

  const other = resultHead({ lr: round({ eliminated: [{ seat: 3, placement: 4 }] }), game: game(), mySeat: 1, stats, big: [{ kind: 'lost', name: 'JOHN CENA' }] });
  assert.equal(other.h1, 'ann wins the round');
  assert.equal(other.tone, 'loss');
  assert.deepEqual(other.sub, [{ b: 'FIGHT' }, " RANDY ORTON 90 beats your JOHN CENA 89."]);
  assert.deepEqual(other.chips.map((c) => c.text), ['ann takes 4 cards', 'Blaze (CPU) is out · 4th', 'Big card: you lost JOHN CENA']);
  assert.equal(other.chips[2].tone, 'lost');

  const clash = resultHead({ lr: round({ winner: 1, clash: 2, cardsWon: 9 }), game: game(), mySeat: 1, stats });
  assert.equal(clash.h1, 'You win the clash');
  assert.equal(clash.chips[0].text, 'You take the pool: 9 cards');

  const none = resultHead({ lr: round({ winner: null, potCarried: 6, cards: {} }), game: game(), mySeat: 1, stats });
  assert.equal(none.h1, 'No winner');
  assert.deepEqual(none.chips.map((c) => c.text), ['The pool (6) carries over']);

  assert.equal(resultHead({ lr: round({ winner: 1, cardsWon: 1 }), game: game(), mySeat: 1, stats }).chips[0].text, 'You take 1 card');
});

test('seatChips: who calls, who is in the clash, who is out, and the count', () => {
  const v = view({ callerSeat: 2, handSizes: { 1: 6, 2: 7, 3: 0, 4: 1 }, placements: { 3: 4 } });
  const chips = seatChips({ game: game(), view: v, mySeat: 1, kind: 'call' });
  assert.deepEqual(chips.map((c) => c.count), ['6', '7', 'OUT', '1']);
  assert.deepEqual(chips.map((c) => c.flag), ['', 'CALLS', '', '']);
  assert.equal(chips[0].short, 'You');
  assert.equal(chips[3].unit, 'card');
  assert.equal(chips[2].out, true);
  assert.match(chips[1].aria, /ann, 7 cards, calls/);

  const c = view({ phase: 'CLASH_AWAIT_CALL', clashLevel: 2, contenders: [1, 2], callerSeat: 1 });
  const clash = seatChips({ game: game(), view: c, mySeat: 1, kind: 'clash' });
  assert.deepEqual(clash.map((x) => x.flag), ['VS', 'VS', '', '']);
  assert.deepEqual(clash.map((x) => x.dim), [false, false, true, true]);          // those not in the clash fade, nobody is "calling"

  const big = { ...game(), seats: Array.from({ length: 6 }, (_, i) => ({ seat: i + 1, kind: 'CPU', difficulty: 'EASY' })) };
  assert.equal(seatChips({ game: big, view: view(), mySeat: 1, kind: 'call' })[1].short, 'Nova');                   // every computer has a name
  assert.equal(seatChips({ game: big, view: view(), mySeat: 1, kind: 'call' })[1].name, 'Nova (CPU)');              // the full name stays for labels
  assert.equal(seatChips({ game: big, view: view(), mySeat: 1, kind: 'call' })[1].sub, 'CPU easy');                 // and the level shows under it
  assert.equal(chips[2].short, 'Blaze');
  assert.equal(seatChips({ game: big, view: view({ callerSeat: 2 }), mySeat: 1, kind: 'call' })[1].flag, 'CALL');   // short label above 5 seats

  const result = seatChips({ game: game(), view: view({ phase: 'ROUND_RESULT', callerSeat: 2, clashLevel: 1, contenders: [1, 2] }), mySeat: 1, kind: 'result' });
  assert.deepEqual(result.map((x) => x.flag), ['', '', '', '']);                  // nobody is calling or racing during a result
  assert.deepEqual(result.map((x) => x.dim), [false, false, false, false]);
  const watching = seatChips({ game: game(), view: view({ phase: 'ROUND_RESULT', callerSeat: 2, clashLevel: 1, contenders: [1, 2] }), mySeat: 1, kind: 'out' });
  assert.deepEqual(watching.map((x) => x.flag), ['', '', '', '']);               // the same for someone watching from the sidelines
});

test('seatChips: at the end the count is the final place', () => {
  const g = game({ status: 'FINISHED', seats: game().seats.map((s, i) => ({ ...s, placement: [2, 1, 3, 4][i] })) });
  const chips = seatChips({ game: g, view: null, mySeat: 1, kind: 'over' });
  assert.deepEqual(chips.map((c) => c.count), ['2nd', '1st', '3rd', '4th']);
  assert.equal(chips[0].out, false);
});

test('pileView: the card in play first when the server leaves it out, then the pile with "next"', () => {
  const deckCards = [{ id: 2, img: 'two.png', brand: 'sd' }];
  const v = view({ ownPile: [{ id: 2, name: 'KANE' }, { id: 3, name: 'BIG SHOW' }], handSizes: { 1: 3 } });
  const p = pileView(v, 1, deckCards);
  assert.deepEqual(p.rows.map((r) => [r.name, r.inPlay ?? false, r.next ?? false]), [['JOHN CENA', true, false], ['KANE', false, true], ['BIG SHOW', false, false]]);
  assert.equal(p.rows[1].img, 'two.png');
  assert.equal(p.rows[1].brand, 'sd');
  assert.equal(p.count, 3);
  // CHOOSE open: the whole pile is listed, nothing is in play
  const all = pileView(view({ canChoose: true, ownHand: [card(1, 'A', 1), card(2, 'B', 2)], ownPile: [{ id: 1, name: 'A' }, { id: 2, name: 'B' }], handSizes: { 1: 2 } }), 1, []);
  assert.deepEqual(all.rows.map((r) => r.name), ['A', 'B']);
  assert.equal(all.rows[0].inPlay, undefined);
  // a committed card is in play
  const com = pileView(view({ committed: card(7, 'C', 1), ownHand: [], ownPile: [{ id: 2, name: 'B' }], handSizes: { 1: 2 } }), 1, []);
  assert.deepEqual(com.rows.map((r) => r.name), ['C', 'B']);
  assert.equal(pileView(view({ ownPile: undefined }), 1, []), null);                // an older server
});

test('logGroups: newest first, grouped by round, clash wording only where there was a clash', () => {
  const events = [
    { type: 'roundStarted', round: 1, caller: 1, seats: [1, 2] },
    { type: 'statCalled', seat: 1, stat: 'fight', auto: false },
    { type: 'revealed', cards: {} },
    { type: 'roundWon', seat: 2, cardsWon: 2 },
    { type: 'roundStarted', round: 2, caller: 2, seats: [1, 2] },
    { type: 'clashStarted', tiedSeats: [1, 2], level: 2, potSize: 4 },
    { type: 'roundWon', seat: 1, cardsWon: 6 },
    { type: 'playerEliminated', seat: 2, placement: 2 },
  ];
  const groups = logGroups(events, game(), (k) => k.toUpperCase());
  assert.deepEqual(groups.map((g) => g.round), [2, 1]);
  assert.deepEqual(groups[0].lines.map((l) => l.text), ['ann is out (#2)', 'me won the clash and takes the pool: 6 cards', 'CLASH ×2: me and ann tied (pool 4)', 'Round 2: ann calls']);
  assert.deepEqual(groups[0].lines.map((l) => l.kind), ['out', 'win', 'clash', '']);
  assert.deepEqual(groups[1].lines.map((l) => l.text), ['ann won the round and takes 2 cards', 'Cards revealed', 'me called FIGHT', 'Round 1: me calls']);
  // events that arrive before any roundStarted (a reconnect) still show
  assert.deepEqual(logGroups([{ type: 'roundWon', seat: 1, cardsWon: 3 }], game()).map((g) => [g.round, g.lines.length]), [[null, 1]]);
  assert.deepEqual(logGroups([], game()), []);
});

test('timerTotal: the length to count from, by phase', () => {
  const o = { turnTimeoutSeconds: 20, clashCallSeconds: 5, resultHoldSeconds: 6 };
  assert.equal(timerTotal({ view: view(), options: o }), 20);
  assert.equal(timerTotal({ view: view({ phase: 'CLASH_AWAIT_CALL' }), options: o }), 5);
  assert.equal(timerTotal({ view: view({ phase: 'ROUND_RESULT' }), options: o, lastRound: { clash: 1, eliminated: [] } }), 9);
  assert.equal(timerTotal({ view: view({ phase: 'ROUND_RESULT' }), options: o, lastRound: { clash: 0, eliminated: [] } }), 6);
  assert.equal(timerTotal({ view: view(), options: { turnTimeoutSeconds: null } }), null);
  assert.equal(timerTotal({ view: null, options: o }), null);
});

test('timerModel: whole seconds, the fraction left, urgency, no deadline, and steps for reduced motion', () => {
  const dl = '2026-11-08T10:00:20Z';
  const at = (s) => Date.parse('2026-11-08T10:00:00Z') + s * 1000;
  assert.equal(timerModel({ deadline: null, nowMs: 0, total: 20 }), null);
  assert.equal(timerModel({ deadline: 'nope', nowMs: 0, total: 20 }), null);
  const a = timerModel({ deadline: dl, nowMs: at(5), total: 20, urgentAt: 5 });
  assert.equal(a.whole, 15);
  assert.equal(a.frac, 0.75);
  assert.equal(a.urgent, false);
  assert.equal(timerModel({ deadline: dl, nowMs: at(15.5), total: 20, urgentAt: 5 }).urgent, true);
  assert.equal(timerModel({ deadline: dl, nowMs: at(15.5), total: 20, urgentAt: 5 }).whole, 5);
  assert.equal(timerModel({ deadline: dl, nowMs: at(25), total: 20, urgentAt: 5 }).whole, 0);            // past the deadline: 0, not urgent
  assert.equal(timerModel({ deadline: dl, nowMs: at(25), total: 20, urgentAt: 5 }).urgent, false);
  assert.equal(timerModel({ deadline: dl, nowMs: at(0), skewMs: 4000, total: 20 }).whole, 16);            // the server clock is 4 s ahead
  assert.equal(timerModel({ deadline: dl, nowMs: at(0), total: 10 }).span, 20);                           // an unknown or too small total grows to what is left
  assert.equal(timerModel({ deadline: dl, nowMs: at(0.5), total: 20 }).frac < 1, true);
  assert.equal(timerModel({ deadline: dl, nowMs: at(0.5), total: 20, steps: true }).frac, 1);              // steps: no smooth drain
  assert.equal(urgentAtFor('AWAIT_CALL'), 5);
  assert.equal(urgentAtFor('CLASH_AWAIT_CALL'), 2);
  assert.equal(urgentAtFor('ROUND_RESULT'), 0);
});

test('overHead: who won, where I finished, how long it lasted, the round-cap wording', () => {
  const g = game({ status: 'FINISHED' });
  const over = { placements: { 1: 2, 2: 1, 3: 3, 4: 4 }, endedByRoundCap: false };
  const h = overHead({ over, game: g, mySeat: 1, rounds: 38 });
  assert.equal(h.h1, 'ann wins');
  assert.equal(h.sub, 'You finished 2nd of 4. Game lasted 38 rounds.');
  assert.deepEqual(h.rows.map((r) => r.name), ['ann', 'me', 'Blaze (CPU)', 'bob']);
  assert.equal(h.won, false);
  assert.equal(overHead({ over: { placements: { 1: 1, 2: 2 }, endedByRoundCap: false }, game: g, mySeat: 1 }).h1, 'You win');
  assert.equal(overHead({ over: { placements: { 1: 1, 2: 1 }, endedByRoundCap: false }, game: g, mySeat: 1 }).h1, 'You tied for 1st');
  assert.equal(overHead({ over: { ...over, endedByRoundCap: true }, game: g, mySeat: 1 }).sub, 'The round limit ended the game: the player with the most cards wins.');
  assert.equal(overHead({ over, game: g, mySeat: 1 }).sub, 'You finished 2nd of 4.');                    // rounds unknown: leave that out
  assert.equal(overHead({ over, game: g, mySeat: null }).sub, '4 players.');                              // a visitor
});

test('clampHold / holdText: the result pause is 0 to 20 whole seconds, 0 reads "No pause"', () => {
  assert.equal(clampHold(7), 7);
  assert.equal(clampHold(-1), 0);
  assert.equal(clampHold(21), 20);
  assert.equal(clampHold('12'), 12);
  assert.equal(clampHold(undefined), 0);
  assert.equal(holdText(0), 'No pause');
  assert.equal(holdText(6), '6 s');
});

test('resultGroups: a clash shows every level, only the last has a winner; a plain round is one group', () => {
  const lr = round({
    clash: 1, winner: 2, cardsWon: 7, stat: 'weight',
    reveals: [
      { seat: 1, card: card(1, 'A', 50, 100), level: 0, stat: 'fight' }, { seat: 2, card: card(2, 'B', 50, 100), level: 0, stat: 'fight' },
      { seat: 1, card: card(3, 'C', 10, 120), level: 1, stat: 'weight' }, { seat: 2, card: card(4, 'D', 10, 150), level: 1, stat: 'weight' },
    ],
  });
  const groups = resultGroups(lr, game(), 1, stats);
  assert.deepEqual(groups.map((g) => g.title), ['The tied cards · Fight', 'Clash · Weight']);
  assert.deepEqual(groups[0].rows.map((r) => [r.name, r.winner, r.tie]), [['A', false, true], ['B', false, true]]);   // tied, nobody won this level
  assert.deepEqual(groups[1].rows.map((r) => [r.name, r.winner]), [['D', true], ['C', false]]);
  assert.equal(carriedCards(lr), 3);                                                                                  // 7 cards won, 4 shown: 3 came from earlier rounds
  assert.equal(carriedCards({ ...lr, reveals: [...lr.reveals, ...lr.reveals], cardsWon: 8 }), 0);
  assert.equal(resultGroups(round(), game(), 1, stats).length, 1);
  assert.equal(carriedCards(round()), 0);
});

test('resultRows: the winner is mine or theirs (the outline is green for me, red for the others)', () => {
  assert.deepEqual(resultRows(round({ winner: 2 }), game(), 1, stats).map((r) => r.mine), [false, false, false, false]);
  assert.equal(resultRows(round({ winner: 1 }), game(), 1, stats)[0].mine, true);
});
