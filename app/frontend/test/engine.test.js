import test from 'node:test';
import assert from 'node:assert/strict';
import { Game, compare, cpuChoose, buildStrengths, RESULT, DIFFICULTY, normaliseCard } from '../js/engine.js';

const card = (id, o = {}) => ({ id, name: `C${id}`, rank: id, fight: 10, chest: 40, biceps: 15, feet: 6, inches: 0, weight: 200, ...o });
const deck = (n) => Array.from({ length: n }, (_, i) => card(i + 1));
const fixedRng = (v = 0.1) => () => v;

test('rank: lower wins', () => {
  assert.equal(compare(card(1), card(2), 'rank'), RESULT.WIN);
  assert.equal(compare(card(3), card(2), 'rank'), RESULT.LOSS);
});

test('height compares feet and inches together (6\'0 beats 5\'11)', () => {
  const a = normaliseCard(card(1, { feet: 6, inches: 0 }));
  const b = normaliseCard(card(2, { feet: 5, inches: 11 }));
  assert.equal(compare(a, b, 'height'), RESULT.WIN);
  const c = normaliseCard(card(3, { feet: 5, inches: 3 }));
  const d = normaliseCard(card(4, { feet: 5, inches: 9 }));
  assert.equal(compare(c, d, 'height'), RESULT.LOSS);
});

test('equal stat is a clash', () => {
  assert.equal(compare(card(1), card(2), 'fight'), RESULT.CLASH);
});

test('deal splits deck evenly with no duplicates', () => {
  const g = new Game(deck(54));
  assert.equal(g.playerCount, 27);
  assert.equal(g.cpuCount, 27);
  const ids = new Set([...g.player, ...g.cpu].map((c) => c.id));
  assert.equal(ids.size, 54);
});

test('winner takes both cards and keeps the turn', () => {
  const g = new Game([card(1, { fight: 50 }), card(2, { fight: 5 })], { firstTurn: 'player', rng: fixedRng() });
  // make sure player holds the strong card
  g.player = [card(1, { fight: 50 }), card(3)];
  g.cpu = [card(2, { fight: 5 }), card(4)];
  const r = g.play('fight');
  assert.equal(r.result, RESULT.WIN);
  assert.equal(g.playerCount, 3);
  assert.equal(g.cpuCount, 1);
  assert.equal(g.turn, 'player');
});

test('loser hands over the turn', () => {
  const g = new Game(deck(4), { firstTurn: 'player' });
  g.player = [card(1, { fight: 1 }), card(3)];
  g.cpu = [card(2, { fight: 9 }), card(4)];
  g.play('fight');
  assert.equal(g.turn, 'cpu');
  assert.equal(g.cpuCount, 3);
});

test('clash pot goes to the next winner; turn unchanged on clash', () => {
  const g = new Game(deck(6), { firstTurn: 'player' });
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
  const g = new Game(deck(2), { firstTurn: 'player' });
  g.player = [card(1, { fight: 50 })];
  g.cpu = [card(2, { fight: 1 })];
  const r = g.play('fight');
  assert.equal(r.over, true);
  assert.equal(g.winner, 'player');
  assert.throws(() => g.play('fight'));
});

test('clash that empties both hands is a draw and pot is not lost silently', () => {
  const g = new Game(deck(2), { firstTurn: 'player' });
  g.player = [card(1)];
  g.cpu = [card(2)];
  const r = g.play('fight');
  assert.equal(r.over, true);
  assert.equal(g.winner, 'draw');
});

test('conservation: total cards never change', () => {
  const g = new Game(deck(54), { rng: fixedRng(0.3) });
  for (let i = 0; i < 400 && !g.over; i++) {
    g.play(['rank', 'fight', 'chest', 'biceps', 'height', 'weight'][i % 6]);
    assert.equal(g.playerCount + g.cpuCount + g.potCount, 54);
  }
});

test('hard CPU calls the stat where its card is relatively strongest', () => {
  const cards = deck(10).map((c, i) => normaliseCard({ ...c, weight: 200 + i, chest: 40 + (9 - i) }));
  const s = buildStrengths(cards);
  const heaviest = cards[9]; // best weight, worst chest
  assert.equal(cpuChoose(heaviest, s, DIFFICULTY.HARD), 'weight');
});

test('CPU treats a low rank number as strong', () => {
  const cards = deck(10).map((c) => normaliseCard({ ...c, fight: 10, chest: 40, biceps: 15, weight: 200 }));
  const s = buildStrengths(cards);
  assert.equal(cpuChoose(cards[0], s, DIFFICULTY.HARD), 'rank'); // rank 1 beats everyone
});
