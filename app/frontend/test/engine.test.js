import test from 'node:test';
import assert from 'node:assert/strict';
import { Game, compare, cpuChoose, buildStrengths, RESULT, DIFFICULTY } from '../js/engine.js';

// Stat definitions as the API would supply them (rank: lower wins).
const STATS = [
  { key: 'rank', label: 'Rank', higherWins: false },
  { key: 'fight', label: 'Fight', higherWins: true },
  { key: 'chest', label: 'Chest', higherWins: true },
  { key: 'biceps', label: 'Biceps', higherWins: true },
  { key: 'height', label: 'Height', higherWins: true },   // total inches
  { key: 'weight', label: 'Weight', higherWins: true },
];
const stat = (key) => STATS.find((s) => s.key === key);

const card = (id, o = {}) => {
  const values = { rank: id, fight: 10, chest: 40, biceps: 15, height: 72, weight: 200, ...o };
  return {
    id, name: `C${id}`,
    stats: Object.fromEntries(Object.entries(values).map(([k, value]) => [k, { value, display: String(value) }])),
  };
};
const deck = (n) => Array.from({ length: n }, (_, i) => card(i + 1));
const newGame = (cards, opts = {}) => new Game(cards, { stats: STATS, ...opts });
const fixedRng = (v = 0.1) => () => v;

test('rank: lower wins', () => {
  assert.equal(compare(card(1), card(2), stat('rank')), RESULT.WIN);
  assert.equal(compare(card(3), card(2), stat('rank')), RESULT.LOSS);
});

test('height is compared as a plain number of inches (6\'0" beats 5\'11")', () => {
  assert.equal(compare(card(1, { height: 72 }), card(2, { height: 71 }), stat('height')), RESULT.WIN);
  assert.equal(compare(card(3, { height: 63 }), card(4, { height: 69 }), stat('height')), RESULT.LOSS);
});

test('equal stat is a clash', () => {
  assert.equal(compare(card(1), card(2), stat('fight')), RESULT.CLASH);
});

test('a game needs the deck stat definitions', () => {
  assert.throws(() => new Game(deck(4)), /stat definitions/);
});

test('deal splits deck evenly with no duplicates', () => {
  const g = newGame(deck(54));
  assert.equal(g.playerCount, 27);
  assert.equal(g.cpuCount, 27);
  const ids = new Set([...g.player, ...g.cpu].map((c) => c.id));
  assert.equal(ids.size, 54);
});

test('winner takes both cards and keeps the turn', () => {
  const g = newGame(deck(4), { firstTurn: 'player', rng: fixedRng() });
  g.player = [card(1, { fight: 50 }), card(3)];
  g.cpu = [card(2, { fight: 5 }), card(4)];
  const r = g.play('fight');
  assert.equal(r.result, RESULT.WIN);
  assert.equal(g.playerCount, 3);
  assert.equal(g.cpuCount, 1);
  assert.equal(g.turn, 'player');
});

test('loser hands over the turn', () => {
  const g = newGame(deck(4), { firstTurn: 'player' });
  g.player = [card(1, { fight: 1 }), card(3)];
  g.cpu = [card(2, { fight: 9 }), card(4)];
  g.play('fight');
  assert.equal(g.turn, 'cpu');
  assert.equal(g.cpuCount, 3);
});

test('clash pot goes to the next winner; turn unchanged on clash', () => {
  const g = newGame(deck(6), { firstTurn: 'player' });
  g.player = [card(1), card(3, { fight: 99 }), card(5)];
  g.cpu = [card(2), card(4, { fight: 1 }), card(6)];
  const r1 = g.play('fight'); // equal -> clash
  assert.equal(r1.result, RESULT.CLASH);
  assert.equal(g.potCount, 2);
  assert.equal(g.turn, 'player');
  const r2 = g.play('fight');
  assert.equal(r2.result, RESULT.WIN);
  assert.equal(g.potCount, 0);
  assert.equal(g.playerCount, 1 + 2 + 2); // remaining + two cards + pot
});

test('game ends when a side runs out of cards', () => {
  const g = newGame(deck(2), { firstTurn: 'player' });
  g.player = [card(1, { fight: 50 })];
  g.cpu = [card(2, { fight: 1 })];
  const r = g.play('fight');
  assert.equal(r.over, true);
  assert.equal(g.winner, 'player');
  assert.throws(() => g.play('fight'));
});

test('clash that empties both hands is a draw', () => {
  const g = newGame(deck(2), { firstTurn: 'player' });
  g.player = [card(1)];
  g.cpu = [card(2)];
  const r = g.play('fight');
  assert.equal(r.over, true);
  assert.equal(g.winner, 'draw');
});

test('unknown stat is rejected', () => {
  const g = newGame(deck(4));
  assert.throws(() => g.play('runs'), /Unknown stat/);
});

test('conservation: total cards never change', () => {
  const g = newGame(deck(54), { rng: fixedRng(0.3) });
  for (let i = 0; i < 400 && !g.over; i++) {
    g.play(STATS[i % 6].key);
    assert.equal(g.playerCount + g.cpuCount + g.potCount, 54);
  }
});

test('works for any deck, not just wrestling (a cricket-like deck)', () => {
  const cricketStats = [{ key: 'runs', label: 'Runs', higherWins: true }, { key: 'economy', label: 'Economy', higherWins: false }];
  const c = (id, runs, economy) => ({ id, name: `P${id}`, stats: { runs: { value: runs }, economy: { value: economy } } });
  const g = new Game([c(1, 100, 5), c(2, 50, 7), c(3, 80, 6), c(4, 20, 8)], { stats: cricketStats, firstTurn: 'player' });
  g.player = [c(1, 100, 5)];
  g.cpu = [c(2, 50, 7)];
  assert.equal(g.play('economy').result, RESULT.WIN);   // lower economy wins
});

test('hard CPU calls the stat where its card is relatively strongest', () => {
  const cards = deck(10).map((c, i) => card(c.id, { weight: 200 + i, chest: 40 + (9 - i) }));
  const s = buildStrengths(cards, STATS);
  const heaviest = cards[9]; // best weight, worst chest
  assert.equal(cpuChoose(heaviest, s, STATS, DIFFICULTY.HARD), 'weight');
});

test('CPU treats a low rank number as strong', () => {
  const cards = deck(10);
  const s = buildStrengths(cards, STATS);
  assert.equal(cpuChoose(cards[0], s, STATS, DIFFICULTY.HARD), 'rank'); // rank 1 beats everyone
});
