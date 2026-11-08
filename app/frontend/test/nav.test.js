import test from 'node:test';
import assert from 'node:assert/strict';
import { parseRoute, toHash, paths, backDecision, needsUnloadGuard, redirectFor, upAction } from '../js/nav.js';

test('parseRoute understands every screen', () => {
  assert.deepEqual(parseRoute(''), { name: 'menu', params: {}, path: '/' });
  assert.equal(parseRoute('#/').name, 'menu');
  assert.equal(parseRoute('#/decks').name, 'decks');
  assert.deepEqual(parseRoute('#/deck/abc-123').params, { id: 'abc-123' });
  assert.deepEqual(parseRoute('#/deck/abc-123/card/7').params, { id: 'abc-123', index: 7 });
  assert.deepEqual(parseRoute('#/lobby/k7q2xm').params, { code: 'K7Q2XM' });     // codes are case-insensitive
  for (const n of ['play', 'signin', 'username', 'account']) assert.equal(parseRoute(`#/${n}`).name, n);
});

test('parseRoute is forgiving: trailing slash, no #, query part, junk -> menu', () => {
  assert.equal(parseRoute('#/decks/').name, 'decks');
  assert.equal(parseRoute('/decks').name, 'decks');
  assert.equal(parseRoute('#/decks?x=1').name, 'decks');
  assert.equal(parseRoute(undefined).name, 'menu');
  for (const junk of ['#/nope', '#/deck', '#/deck/a/card', '#/deck/a/card/x', '#/lobby', '#/play/extra', '#/decks/1', '#/%E0%A4%A']) {
    assert.equal(parseRoute(junk).name, 'menu', junk);
  }
});

test('paths and parseRoute round-trip', () => {
  const cases = [paths.menu(), paths.decks(), paths.deck('d1'), paths.card('d1', 3), paths.lobby('ab12cd'), paths.play(), paths.signin(), paths.username(), paths.account()];
  for (const p of cases) assert.equal(parseRoute(toHash(p)).path, p);
  assert.equal(paths.lobby('ab12cd'), '/lobby/AB12CD');
  assert.equal(parseRoute(toHash(paths.deck('a b/c'))).params.id, 'a b/c');   // ids are URL-encoded
});

test('Back during a game asks first; other screens move freely', () => {
  const play = parseRoute('#/play');
  assert.equal(backDecision({ from: play, to: parseRoute('#/'), gameActive: true }), 'confirm');
  assert.equal(backDecision({ from: play, to: parseRoute('#/decks'), gameActive: true }), 'confirm');
  assert.equal(backDecision({ from: play, to: parseRoute('#/play'), gameActive: true }), 'allow');       // same screen
  assert.equal(backDecision({ from: play, to: parseRoute('#/'), gameActive: false }), 'allow');          // game already over / left
  assert.equal(backDecision({ from: parseRoute('#/decks'), to: parseRoute('#/'), gameActive: true }), 'allow');
  assert.equal(backDecision({ from: parseRoute('#/lobby/ABC123'), to: parseRoute('#/deck/d1'), gameActive: false }), 'allow');
  assert.equal(backDecision({ from: null, to: parseRoute('#/'), gameActive: true }), 'allow');
});

test('the reload / tab-close warning only applies to a game in progress', () => {
  assert.equal(needsUnloadGuard({ route: parseRoute('#/play'), gameActive: true }), true);
  assert.equal(needsUnloadGuard({ route: parseRoute('#/play'), gameActive: false }), false);
  assert.equal(needsUnloadGuard({ route: parseRoute('#/decks'), gameActive: true }), false);
  assert.equal(needsUnloadGuard({ route: null, gameActive: true }), false);
});

test('#/play without a game redirects to the menu', () => {
  assert.equal(redirectFor(parseRoute('#/play'), { hasGame: false }), '/');
  assert.equal(redirectFor(parseRoute('#/play'), { hasGame: true }), null);
  assert.equal(redirectFor(parseRoute('#/decks'), { hasGame: false }), null);
});

test('going up: Back if the previous entry is the parent, otherwise replace', () => {
  assert.equal(upAction({ idx: 2, from: '#/decks' }, '/decks'), 'back');
  assert.equal(upAction({ idx: 1, from: '#/' }, '/'), 'back');
  assert.equal(upAction({ idx: 2, from: '#/signin' }, '/decks'), 'replace');
  assert.equal(upAction({ idx: 0, from: null }, '/'), 'replace');
  assert.equal(upAction(null, '/'), 'replace');
});

test('Back out of a lobby / live game asks only when you hold a seat', () => {
  const lobby = parseRoute('#/lobby/ABC123');
  assert.equal(backDecision({ from: lobby, to: parseRoute('#/deck/d1'), seated: true }), 'confirm');
  assert.equal(backDecision({ from: lobby, to: parseRoute('#/decks'), seated: true }), 'confirm');
  assert.equal(backDecision({ from: lobby, to: parseRoute('#/decks'), seated: false }), 'allow');       // just looking at a link
  assert.equal(backDecision({ from: lobby, to: parseRoute('#/lobby/ABC123'), seated: true }), 'allow');  // same lobby
  assert.equal(backDecision({ from: lobby, to: parseRoute('#/lobby/XYZ999'), seated: true }), 'confirm'); // a different game
  assert.equal(backDecision({ from: parseRoute('#/decks'), to: lobby, seated: true }), 'allow');          // going in is fine
});

test('the solo-game rule is unchanged by the lobby rule', () => {
  assert.equal(backDecision({ from: parseRoute('#/play'), to: parseRoute('#/'), gameActive: true, seated: false }), 'confirm');
  assert.equal(backDecision({ from: parseRoute('#/play'), to: parseRoute('#/'), gameActive: false, seated: true }), 'allow');
});
