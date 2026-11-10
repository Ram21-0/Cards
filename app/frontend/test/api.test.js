import test from 'node:test';
import assert from 'node:assert/strict';
import { mapDeck } from '../js/api.js';
import { offlineDeck } from '../js/offline.js';

// Shapes copied from the Java backend: GET /api/decks/{id} and GET /api/decks/{id}/cards
const deckJson = {
  id: '3cf07a6d-34fd-4122-960b-aa09e8e93b47', name: 'Wrestling', description: 'The original wrestling cards', visibility: 'PUBLIC',
  stats: [
    { key: 'weight', label: 'Weight', unit: 'lbs', valueType: 'INT', comparisonStrategy: 'HIGHER_WINS', displayOrder: 6, displayFormat: 'WITH_UNIT' },
    { key: 'rank', label: 'Rank', unit: null, valueType: 'INT', comparisonStrategy: 'LOWER_WINS', displayOrder: 1, displayFormat: 'PLAIN' },
    { key: 'height', label: 'Height', unit: 'in', valueType: 'INT', comparisonStrategy: 'HIGHER_WINS', displayOrder: 5, displayFormat: 'FEET_INCHES' },
  ],
};
const cardJson = (id, extra = {}) => ({
  id, name: `W${id}`, img: null, meta: { brand: 'sd' },
  stats: {
    rank: { value: id, displayValue: `${id}` },
    height: { value: 73, displayValue: '6\'1"' },
    weight: { value: 240, displayValue: '240lbs' },
  },
  ...extra,
});

test('mapDeck orders stats by displayOrder and maps comparison strategy', () => {
  const d = mapDeck(deckJson, [cardJson(1)]);
  assert.deepEqual(d.stats.map((s) => s.key), ['rank', 'height', 'weight']);
  assert.equal(d.stats[0].higherWins, false);   // LOWER_WINS
  assert.equal(d.stats[1].higherWins, true);
  assert.equal(d.source, 'api');
});

test('mapDeck keeps the server-side displayValue and the numeric value', () => {
  const [c] = mapDeck(deckJson, [cardJson(1)]).cards;
  assert.equal(c.stats.height.display, '6\'1"');
  assert.equal(c.stats.height.value, 73);
  assert.equal(c.stats.weight.display, '240lbs');
  assert.equal(c.brand, 'sd');
});

test('mapDeck drops cards that miss one of the deck stats', () => {
  const broken = cardJson(2, { stats: { rank: { value: 2, displayValue: '2' } } });
  const d = mapDeck(deckJson, [cardJson(1), broken]);
  assert.deepEqual(d.cards.map((c) => c.id), [1]);
});

test('offlineDeck builds the same model from the bundled legacy cards', () => {
  const d = offlineDeck([{ id: 1, name: 'A', brand: 'raw', img: '', rank: 3, fight: 20, chest: 40.5, biceps: 15, feet: 6, inches: 1, weight: 240 }]);
  assert.equal(d.source, 'offline');
  assert.equal(d.cards[0].stats.height.value, 73);
  assert.equal(d.cards[0].stats.height.display, '6\'1"');
  assert.equal(d.cards[0].stats.weight.display, '240lbs');
  assert.equal(d.stats.find((s) => s.key === 'rank').higherWins, false);
});

// ---- create game, sign-in, current player (fetch is mocked) ----
import { createGame, getGame, fetchMe, updateMe, signOut, deleteAccount, googleSignIn, fetchNonce, devLogin, ApiError } from '../js/api.js';

function mockFetch(status, body) {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  };
  return calls;
}

test('every request sends the session cookie and the CSRF header', async () => {
  const calls = mockFetch(200, {});
  await getGame('ABC234');
  await createGame({ deckId: 'd1', maxPlayers: 2 });
  for (const c of calls) {
    assert.equal(c.init.credentials, 'include');
    assert.equal(c.init.headers['X-Requested-With'], 'cards');
  }
});

test('createGame POSTs the deck and player count (the host is the signed-in player, so no hostId)', async () => {
  const game = { id: 'g1', joinCode: 'K7Q2XM', joinUrl: 'http://localhost:8081/?join=K7Q2XM', status: 'WAITING', maxPlayers: 4,
    deck: { id: 'd1', name: 'Wrestling' }, seats: [{ seat: 1, kind: 'HUMAN', username: 'ann', difficulty: null }] };
  const calls = mockFetch(201, game);
  const got = await createGame({ deckId: 'd1', maxPlayers: 4, hostId: 'ignored' });
  assert.deepEqual(got, game);
  assert.match(calls[0].url, /\/api\/games$/);
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(calls[0].init.body), { deckId: 'd1', maxPlayers: 4 });
});

test('createGame without a session is an ApiError 401', async () => {
  mockFetch(401, { error: 'Sign in required' });
  await assert.rejects(() => createGame({ deckId: 'd1', maxPlayers: 2 }), (e) => e instanceof ApiError && e.status === 401);
});

test('error responses become ApiError with the server message and status', async () => {
  mockFetch(409, { error: 'Username already taken' });
  await assert.rejects(() => updateMe('ann'), (e) => e instanceof ApiError && e.status === 409 && e.message === 'Username already taken');
  mockFetch(404, { error: 'Game not found' });
  await assert.rejects(() => getGame('NOPE12'), (e) => e instanceof ApiError && e.status === 404);
});

test('getGame encodes the join code and uses GET', async () => {
  const calls = mockFetch(200, { joinCode: 'ABC234' });
  await getGame('ABC234');
  assert.match(calls[0].url, /\/api\/games\/ABC234$/);
  assert.equal(calls[0].init.method, 'GET');
});

test('a non-JSON error body still gives a readable message', async () => {
  globalThis.fetch = async () => ({ ok: false, status: 500, json: async () => { throw new Error('not json'); } });
  await assert.rejects(() => getGame('ABC234'), /HTTP 500/);
});

test('fetchMe returns the player, and null when signed out (401)', async () => {
  const me = { id: 'p1', username: 'ann', displayName: 'Ann A', avatarUrl: null, email: 'a@x.com', needsUsername: false };
  const calls = mockFetch(200, me);
  assert.deepEqual(await fetchMe(), me);
  assert.match(calls[0].url, /\/api\/me$/);
  mockFetch(401, { error: 'Sign in required' });
  assert.equal(await fetchMe(), null);
});

test('fetchMe lets other failures through (server down, 500)', async () => {
  mockFetch(500, { error: 'boom' });
  await assert.rejects(() => fetchMe(), (e) => e instanceof ApiError && e.status === 500);
  globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); };
  await assert.rejects(() => fetchMe(), TypeError);
});

test('nonce, Google sign-in and dev login use the documented endpoints and bodies', async () => {
  let calls = mockFetch(200, { nonce: 'abc' });
  assert.deepEqual(await fetchNonce(), { nonce: 'abc' });
  assert.equal(calls[0].init.method, 'POST');
  assert.match(calls[0].url, /\/api\/auth\/nonce$/);

  calls = mockFetch(200, { id: 'p1' });
  await googleSignIn('the.id.token');
  assert.match(calls[0].url, /\/api\/auth\/google$/);
  assert.deepEqual(JSON.parse(calls[0].init.body), { credential: 'the.id.token' });

  calls = mockFetch(200, { id: 'p1' });
  await devLogin('ann');
  assert.match(calls[0].url, /\/api\/dev\/login$/);
  assert.deepEqual(JSON.parse(calls[0].init.body), { username: 'ann' });
});

test('Google sign-in not configured is a 503 ApiError with the server message', async () => {
  mockFetch(503, { error: 'Google sign-in is not configured' });
  await assert.rejects(() => googleSignIn('x'), (e) => e.status === 503 && /not configured/.test(e.message));
});

test('updateMe PUTs the username; sign out and delete handle 204 (no body)', async () => {
  let calls = mockFetch(200, { username: 'ann' });
  await updateMe('ann');
  assert.equal(calls[0].init.method, 'PUT');
  assert.deepEqual(JSON.parse(calls[0].init.body), { username: 'ann' });

  globalThis.fetch = async (url, init) => ({ ok: true, status: 204, json: async () => { throw new Error('no body'); } });
  assert.equal(await signOut(), null);
  assert.equal(await deleteAccount(), null);
});

test('joinGame POSTs to the join endpoint with no body and returns the game', async () => {
  const game = { id: 'g1', joinCode: 'K7Q2XM', maxPlayers: 3, deck: { id: 'd', name: 'W' }, seats: [{ seat: 1, kind: 'HUMAN', username: 'ann' }, { seat: 2, kind: 'HUMAN', username: 'bob' }] };
  const calls = mockFetch(200, game);
  const { joinGame } = await import('../js/api.js');
  assert.deepEqual(await joinGame('k7q2xm'), game);
  assert.match(calls[0].url, /\/api\/games\/k7q2xm\/join$/);
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.body, undefined);
  assert.equal(calls[0].init.credentials, 'include');
  assert.equal(calls[0].init.headers['X-Requested-With'], 'cards');
});

test('join errors keep the server message and status (full / started / username / signed out)', async () => {
  const { joinGame } = await import('../js/api.js');
  for (const [status, error] of [[409, 'Game is full'], [409, 'Game has already started'], [400, 'Choose a username first'], [401, 'Sign in required'], [404, 'Game ZZZZZZ not found']]) {
    mockFetch(status, { error });
    await assert.rejects(() => joinGame('ZZZZZZ'), (e) => e instanceof ApiError && e.status === status && e.message === error);
  }
});

test('an empty apiBase (bundled build) means same origin: paths stay relative', async () => {
  const saved = globalThis.CARDS_CONFIG;
  globalThis.CARDS_CONFIG = { apiBase: '' };
  const fresh = await import('../js/api.js?bundle');         // re-evaluates the module with the new config
  assert.equal(fresh.API_BASE, '');
  const calls = mockFetch(200, {});
  await fresh.getGame('ABC234');
  assert.equal(calls[0].url, '/api/games/ABC234');
  globalThis.CARDS_CONFIG = saved;
});

test('callStat and chooseCard POST to /call and /choose with the right bodies and return the view', async () => {
  const { callStat, chooseCard } = await import('../js/api.js');
  let calls = mockFetch(200, { type: 'view', phase: 'AWAIT_CHOOSE_OTHERS' });
  assert.equal((await callStat('k7q2xm', 'weight')).phase, 'AWAIT_CHOOSE_OTHERS');
  assert.match(calls[0].url, /\/api\/games\/k7q2xm\/call$/);
  assert.equal(calls[0].init.method, 'POST');
  assert.deepEqual(JSON.parse(calls[0].init.body), { stat: 'weight' });
  assert.equal(calls[0].init.credentials, 'include');
  assert.equal(calls[0].init.headers['X-Requested-With'], 'cards');

  calls = mockFetch(200, { type: 'view' });
  await chooseCard('K7Q2XM', 17);
  assert.match(calls[0].url, /\/api\/games\/K7Q2XM\/choose$/);
  assert.deepEqual(JSON.parse(calls[0].init.body), { cardId: 17 });
});

test('play errors keep the server message and status (not your turn / wrong phase / bad card)', async () => {
  const { callStat, chooseCard } = await import('../js/api.js');
  for (const [status, error] of [[403, 'It is not your turn'], [409, 'The stat was already set'], [400, 'Unknown stat']]) {
    mockFetch(status, { error });
    await assert.rejects(() => callStat('ABC123', 'x'), (e) => e instanceof ApiError && e.status === status && e.message === error);
  }
  mockFetch(400, { error: 'That card is not in your hand' });
  await assert.rejects(() => chooseCard('ABC123', 99), /not in your hand/);
});
