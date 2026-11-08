import test from 'node:test';
import assert from 'node:assert/strict';
import { cardStrengths, topCards, strongMap, bigCardNotices, bigCardText, BIG_CARDS } from '../js/strength.js';

const STATS = [
  { key: 'rank', higherWins: false },     // a label, ignored for strength
  { key: 'fight', higherWins: true },
  { key: 'weight', higherWins: true },
  { key: 'speed', higherWins: false },    // lower is better
];
const card = (id, o = {}) => ({ id, name: `C${id}`, stats: Object.fromEntries(Object.entries({ rank: 50, fight: 10, weight: 100, speed: 5, ...o }).map(([k, value]) => [k, { value }])) });

test('strength is the average share of cards beaten, respecting lower-wins stats', () => {
  const cards = [card(1, { fight: 30, weight: 300, speed: 1 }), card(2, { fight: 20, weight: 200, speed: 2 }), card(3, { fight: 10, weight: 100, speed: 3 })];
  const s = cardStrengths(cards, STATS);
  assert.equal(s.get(1), 1);          // beats everyone on every stat (speed: lowest wins)
  assert.equal(s.get(2), 0.5);
  assert.equal(s.get(3), 0);
});

test('the number called "rank" does not decide strength', () => {
  // card 1 has the worst rank number (99) but the best everything else; card 2 has rank 1 but weak stats
  const cards = [card(1, { rank: 99, fight: 30, weight: 300, speed: 1 }), card(2, { rank: 1, fight: 10, weight: 100, speed: 3 }), card(3, { rank: 50, fight: 20, weight: 200, speed: 2 })];
  const top = topCards(cards, STATS, 3);       // 3 cards: only floor(3/2)=1 allowed
  assert.deepEqual(top, [{ id: 1, position: 1 }]);
  assert.ok(cardStrengths(cards, STATS).get(1) > cardStrengths(cards, STATS).get(2));
});

test('ties count half; a stat set of only "rank" falls back to using it', () => {
  const same = [card(1), card(2)];
  assert.equal(cardStrengths(same, STATS).get(1), 0.5);
  const onlyRank = [{ key: 'rank', higherWins: false }];
  const cards = [card(1, { rank: 1 }), card(2, { rank: 9 })];
  const s = cardStrengths(cards, onlyRank);
  assert.equal(s.get(1), 1);           // lower rank number wins, and rank is all there is
  assert.equal(s.get(2), 0);
});

test('topCards: top 10, strongest first, ties broken by id, never more than half the deck', () => {
  const big = Array.from({ length: 54 }, (_, i) => card(i + 1, { fight: i, weight: i * 10, speed: 100 - i }));
  const top = topCards(big, STATS);
  assert.equal(top.length, BIG_CARDS);
  assert.deepEqual(top.slice(0, 3), [{ id: 54, position: 1 }, { id: 53, position: 2 }, { id: 52, position: 3 }]);
  const small = Array.from({ length: 6 }, (_, i) => card(i + 1, { fight: i }));
  assert.equal(topCards(small, STATS).length, 3);                          // half of 6, not 10
  assert.equal(topCards([card(1)], STATS).length, 0);                      // one card: nothing is "big"
  const clones = Array.from({ length: 8 }, (_, i) => card(i + 1));
  assert.deepEqual(topCards(clones, STATS, 3).map((t) => t.id), [1, 2, 3]); // all equal: stable by id
  assert.equal(strongMap(big, STATS).get(54), 1);
  assert.equal(strongMap(big, STATS).get(1), undefined);
});

const strong = new Map([[7, 1], [3, 4]]);                                   // card 7 is the strongest, card 3 the 4th
const reveals = [{ seat: 1, card: { id: 3, name: 'Three' } }, { seat: 2, card: { id: 7, name: 'Seven' } }, { seat: 2, card: { id: 9, name: 'Nine' } }];

test('winning takes the other players\' big cards: "won"', () => {
  assert.deepEqual(bigCardNotices({ winnerSeat: 1, mySeat: 1, reveals, strong }), [{ kind: 'won', id: 7, name: 'Seven', position: 1 }]);
});

test('losing gives away my own big cards: "lost"', () => {
  assert.deepEqual(bigCardNotices({ winnerSeat: 2, mySeat: 1, reveals, strong }), [{ kind: 'lost', id: 3, name: 'Three', position: 4 }]);
});

test('no notice for weak cards, nobody winning, not taking part, or a missing deck', () => {
  assert.deepEqual(bigCardNotices({ winnerSeat: 1, mySeat: 1, reveals: [{ seat: 2, card: { id: 9, name: 'Nine' } }], strong }), []);
  assert.deepEqual(bigCardNotices({ winnerSeat: null, mySeat: 1, reveals, strong }), []);               // pool carried over
  assert.deepEqual(bigCardNotices({ winnerSeat: 2, mySeat: 3, reveals, strong }), []);                  // I am not in this round
  assert.deepEqual(bigCardNotices({ winnerSeat: 1, mySeat: 1, reveals, strong: null }), []);
  assert.deepEqual(bigCardNotices({ winnerSeat: 1, mySeat: 1, reveals: [], strong }), []);
  assert.deepEqual(bigCardNotices({ winnerSeat: 2, mySeat: 1, reveals: [{ seat: 1, card: { id: 9, name: 'Nine' } }], strong }), []);
});

test('a clash winner is told about big cards from every level of the clash, strongest first, each once', () => {
  // level 0: I (seat 1) played card 5, seat 2 played the strongest card 7; level 1: I played card 3 (big, mine), seat 2 played 9
  const clash = [
    { seat: 1, card: { id: 5, name: 'Five' } }, { seat: 2, card: { id: 7, name: 'Seven' } },
    { seat: 1, card: { id: 3, name: 'Three' } }, { seat: 2, card: { id: 9, name: 'Nine' } },
    { seat: 2, card: { id: 7, name: 'Seven' } },                                  // reported twice: still mentioned once
  ];
  const won = bigCardNotices({ winnerSeat: 1, mySeat: 1, reveals: clash, strong });
  assert.deepEqual(won.map((n) => [n.kind, n.id]), [['won', 7]]);                // my own big card 3 comes back to me: not "won"
  const lost = bigCardNotices({ winnerSeat: 2, mySeat: 1, reveals: clash, strong });
  assert.deepEqual(lost.map((n) => [n.kind, n.id]), [['lost', 3]]);              // I lose card 3, which is big; 7 was never mine
  const bothMine = bigCardNotices({ winnerSeat: 2, mySeat: 1, reveals: [{ seat: 1, card: { id: 7, name: 'Seven' } }, { seat: 1, card: { id: 3, name: 'Three' } }], strong });
  assert.deepEqual(bothMine.map((n) => n.id), [7, 3]);                           // strongest first
});

test('bigCardText', () => {
  assert.equal(bigCardText({ kind: 'won', name: 'Atlas Wolfe', position: 1 }), 'You won Atlas Wolfe, the strongest card in the deck');
  assert.equal(bigCardText({ kind: 'lost', name: 'Iron Duke', position: 3 }), 'You lost Iron Duke, the #3 strongest card in the deck');
});
