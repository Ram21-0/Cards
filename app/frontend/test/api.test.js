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
