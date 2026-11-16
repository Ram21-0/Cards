import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseNames, requestProblems, requestQuota, statusView, canWithdraw, deckActions, deckBadge, parseCsvHeader, defaultStatRows, mappingProblems,
  buildMapping, importInputProblems, previewView, inputKey, adminActions, errorText, reviewTable,
} from '../js/decks.js';
import { imageUrl } from '../js/api.js';

test('parseNames: one per line, trimmed, collapsed, de-duplicated ignoring case', () => {
  assert.deepEqual(parseNames('  Sachin   Tendulkar \r\n\nKapil Dev\nsachin tendulkar\nShane\tWarne'), ['Sachin Tendulkar', 'Kapil Dev', 'Shane Warne']);
  assert.deepEqual(parseNames(''), []);
  assert.deepEqual(parseNames(null), []);
});

test('requestProblems mirrors the server rules', () => {
  const ok = { category: 'Cricket legends', description: '', names: ['A', 'B', 'C'] };
  assert.deepEqual(requestProblems(ok), []);
  assert.deepEqual(requestProblems({ ...ok, category: ' ab ' }), ['Category must be 3 to 60 characters']);
  assert.deepEqual(requestProblems({ ...ok, names: ['A', 'B'] }), ['Suggest 3 to 60 different names']);
  assert.deepEqual(requestProblems({ ...ok, names: Array.from({ length: 61 }, (_, i) => `n${i}`) }), ['Suggest 3 to 60 different names']);
  assert.deepEqual(requestProblems({ ...ok, names: ['A', 'B', 'x'.repeat(61)] }), ['Each name must be at most 60 characters']);
  assert.deepEqual(requestProblems({ ...ok, description: 'x'.repeat(501) }), ['Description must be at most 500 characters']);
  assert.deepEqual(requestProblems({ ...ok, names: 'A\nB\nC' }), []);              // text is accepted too
});

test('requestQuota counts pending requests only', () => {
  const r = (status) => ({ status });
  assert.deepEqual(requestQuota([r('PENDING'), r('REJECTED')]), { pending: 1, maxPending: 3, canSend: true });
  assert.equal(requestQuota([r('PENDING'), r('PENDING'), r('PENDING')]).canSend, false);
});

test('statusView words each state, rejection shows the note, approved-not-live is "being prepared"', () => {
  assert.equal(statusView({ status: 'PENDING', type: 'CATEGORY' }).tone, 'wait');
  assert.deepEqual(statusView({ status: 'REJECTED', adminNote: 'Too niche' }), { text: 'Rejected', tone: 'bad', detail: 'Too niche' });
  assert.equal(statusView({ status: 'APPROVED_PRIVATE', live: false, type: 'CATEGORY' }).text, 'Approved, being prepared');
  assert.equal(statusView({ status: 'APPROVED_GLOBAL', live: true, type: 'CATEGORY' }).text, 'Live — public deck');
  assert.equal(statusView({ status: 'APPROVED_GLOBAL', live: true, type: 'PROMOTE' }).tone, 'good');
  assert.equal(canWithdraw({ status: 'PENDING' }), true);
  assert.equal(canWithdraw({ status: 'REJECTED' }), false);
});

const deck = (o = {}) => ({ id: 'd', visibility: 'PRIVATE', live: true, cardCount: 10, promotion: null, ...o });

test('deckActions: private deck can do everything, public only copy, open promotion blocks editing', () => {
  const a = deckActions(deck());
  assert.deepEqual([a.edit, a.remove, a.copy, a.requestPublic, a.withdrawPublic], [true, true, true, true, false]);
  const pub = deckActions(deck({ visibility: 'PUBLIC' }));
  assert.deepEqual([pub.edit, pub.remove, pub.copy, pub.requestPublic], [false, false, true, false]);
  assert.match(pub.editBlock, /copy it/);
  const open = deckActions(deck({ promotion: { status: 'PENDING' } }));
  assert.deepEqual([open.edit, open.remove, open.requestPublic, open.withdrawPublic], [false, false, false, true]);
  assert.equal(deckActions(deck({ promotion: { status: 'REJECTED' } })).requestPublic, true);   // may ask again
  assert.equal(deckActions(deck({ cardCount: 7 })).requestPublic, false);
  const prep = deckActions(deck({ live: false }));
  assert.deepEqual([prep.view, prep.edit, prep.copy, prep.requestPublic], [false, false, false, false]);
});

test('deckBadge', () => {
  assert.equal(deckBadge(deck({ live: false })).text, 'Being prepared');
  assert.equal(deckBadge(deck({ visibility: 'PUBLIC' })).tone, 'good');
  assert.equal(deckBadge(deck({ promotion: { status: 'PENDING' } })).tone, 'wait');
  assert.equal(deckBadge(deck({ promotion: { status: 'REJECTED' } })).tone, 'bad');
  assert.equal(deckBadge(deck()).text, 'Private');
});

test('parseCsvHeader handles BOM, quotes, case and CRLF', () => {
  assert.deepEqual(parseCsvHeader('﻿Name, Rank ,"Fight"\r\nA,1,2'), ['name', 'rank', 'fight']);
  assert.deepEqual(parseCsvHeader('name\n'), ['name']);
  assert.deepEqual(parseCsvHeader(''), []);
  assert.deepEqual(parseCsvHeader('"a,b",c'), ['a,b', 'c']);
});

test('defaultStatRows skips reserved columns, uses the template when it knows a column, rank is lower-wins', () => {
  const tpl = [{ column: 'height', label: 'Height', unit: 'in', higherWins: true, displayFormat: 'FEET_INCHES' }];
  const rows = defaultStatRows(['name', 'image', 'brand', 'rank', 'height', 'top_speed'], tpl);
  assert.deepEqual(rows.map((r) => r.column), ['rank', 'height', 'top_speed']);
  assert.equal(rows[0].higherWins, false);
  assert.deepEqual([rows[1].label, rows[1].displayFormat, rows[1].unit], ['Height', 'FEET_INCHES', 'in']);
  assert.deepEqual([rows[2].label, rows[2].higherWins, rows[2].displayFormat], ['Top speed', true, 'PLAIN']);
});

test('mappingProblems and buildMapping', () => {
  const rows = defaultStatRows(['name', 'rank', 'power']);
  assert.deepEqual(mappingProblems(rows), []);
  rows[1].use = false;
  assert.deepEqual(buildMapping(rows), { stats: [{ column: 'rank', label: 'Rank', unit: null, higherWins: false, displayFormat: 'PLAIN' }] });
  rows[0].use = false;
  assert.deepEqual(mappingProblems(rows), ['Choose 1 to 8 stat columns']);
  rows[0].use = true; rows[0].displayFormat = 'WITH_UNIT';
  assert.deepEqual(mappingProblems(rows), ['Column "rank" shows a unit, so it needs one']);
  rows[0].unit = ' lbs ';
  assert.equal(buildMapping(rows).stats[0].unit, 'lbs');
  const nine = defaultStatRows(Array.from({ length: 9 }, (_, i) => `s${i}`));
  assert.deepEqual(mappingProblems(nine), ['Choose 1 to 8 stat columns']);
});

test('importInputProblems', () => {
  const rows = defaultStatRows(['name', 'rank']);
  assert.deepEqual(importInputProblems({ csv: null, rows: [], name: 'Heroes', nameRequired: true }), ['Choose a CSV file']);
  assert.deepEqual(importInputProblems({ csv: {}, rows, name: 'ab', nameRequired: true }), ['Deck name must be 3 to 40 characters']);
  assert.deepEqual(importInputProblems({ csv: {}, rows, name: '', nameRequired: false }), []);        // edit: name optional
  assert.deepEqual(importInputProblems({ csv: {}, rows: [], name: 'Heroes', nameRequired: true }), ['The CSV needs a stat column besides name, image and brand', 'Choose 1 to 8 stat columns']);
});

test('previewView counts the problems the server did not list', () => {
  const bad = previewView({ valid: false, errorCount: 53, errors: Array(50).fill('x'), cards: [], stats: [] });
  assert.deepEqual([bad.ok, bad.headline, bad.more], [false, '53 problems to fix', 3]);
  assert.equal(previewView({ valid: false, errorCount: 1, errors: ['x'], cards: [] }).headline, '1 problem to fix');
  const good = previewView({ valid: true, errorCount: 0, errors: [], cards: [{}, {}], stats: [] });
  assert.deepEqual([good.ok, good.headline, good.more], [true, 'Looks good: 2 cards', 0]);
});

test('inputKey changes with the files and the mapping', () => {
  const f = (name, size) => ({ name, size, lastModified: 1 });
  const base = inputKey({ csv: f('a.csv', 10), images: [f('i.zip', 5)], mappingJson: '{}' });
  assert.equal(base, inputKey({ csv: f('a.csv', 10), images: [f('i.zip', 5)], mappingJson: '{}' }));
  assert.notEqual(base, inputKey({ csv: f('a.csv', 11), images: [f('i.zip', 5)], mappingJson: '{}' }));
  assert.notEqual(base, inputKey({ csv: f('a.csv', 10), images: [], mappingJson: '{}' }));
  assert.notEqual(base, inputKey({ csv: f('a.csv', 10), images: [f('i.zip', 5)], mappingJson: '{"x":1}' }));
});

test('adminActions by state', () => {
  const pending = adminActions({ type: 'CATEGORY', status: 'PENDING', live: false, cardCount: 0 });
  assert.deepEqual([pending.edit, pending.decide, pending.visibilityChoice, pending.importStats, pending.publish], [true, true, true, false, false]);
  const promote = adminActions({ type: 'PROMOTE', status: 'PENDING', live: false, cardCount: 9 });
  assert.deepEqual([promote.edit, promote.decide, promote.visibilityChoice, promote.reviewCards], [false, true, false, true]);
  const prep = adminActions({ type: 'CATEGORY', status: 'APPROVED_PRIVATE', live: false, cardCount: 3 });
  assert.deepEqual([prep.importStats, prep.publish], [true, false]);
  assert.match(prep.publishBlock, /at least 8/);
  assert.equal(adminActions({ type: 'CATEGORY', status: 'APPROVED_GLOBAL', live: false, cardCount: 8 }).publish, true);
  const live = adminActions({ type: 'CATEGORY', status: 'APPROVED_GLOBAL', live: true, cardCount: 8 });
  assert.deepEqual([live.importStats, live.publish, live.decide], [false, false, false]);
  assert.equal(adminActions({ type: 'PROMOTE', status: 'APPROVED_GLOBAL', live: true, cardCount: 9 }).importStats, false);
  assert.equal(adminActions({ type: 'CATEGORY', status: 'REJECTED', live: false, cardCount: 0 }).decide, false);
});

test('errorText: admin 401/403, upload size, offline, server message', () => {
  const e = (status, message) => Object.assign(new Error(message), { status });
  assert.equal(errorText(e(403, 'Forbidden'), { admin: true }), 'This page is for admins only.');
  assert.equal(errorText(e(401, 'x'), { admin: true }), 'Sign in to continue.');
  assert.equal(errorText(e(409, 'You already have 3 pending requests; wait for a decision')), 'You already have 3 pending requests; wait for a decision');
  assert.equal(errorText(e(413, 'Request body too large')), 'That upload is too large.');
  assert.equal(errorText(new Error('Failed to fetch')), 'Could not reach the server.');
});

test('reviewTable keeps stat order and fills gaps', () => {
  const t = reviewTable([
    { name: 'A', meta: { brand: 'raw' }, img: '/api/images/1', stats: { rank: { displayValue: '1' }, power: { displayValue: '9' } } },
    { name: 'B', stats: { power: { displayValue: '7' } } },
  ]);
  assert.deepEqual(t.keys, ['rank', 'power']);
  assert.deepEqual(t.rows[1].cells, ['–', '7']);
  assert.equal(t.rows[0].brand, 'raw');
});

test('imageUrl resolves uploaded images against the API origin only', () => {
  assert.equal(imageUrl('/api/images/abc', 'http://api'), 'http://api/api/images/abc');
  assert.equal(imageUrl('https://x/y.png', 'http://api'), 'https://x/y.png');
  assert.equal(imageUrl(null, 'http://api'), null);
  assert.equal(imageUrl('/api/images/abc', ''), '/api/images/abc');                // bundled build: same origin
});
