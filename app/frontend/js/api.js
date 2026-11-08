// Talks to the Java backend. Pure mapping (mapDeck) is separated from fetching so it can be unit-tested.
//
// Frontend deck model (what the rest of the app uses):
//   { id, name, description, source, stats: [{ key, label, unit, higherWins }],
//     cards: [{ id, name, img, brand, stats: { key: { value: Number, display: String } } }] }

const config = globalThis.CARDS_CONFIG ?? {};
export const API_BASE = (config.apiBase ?? 'http://localhost:8080').replace(/\/$/, '');

async function getJson(path, timeoutMs = 5000) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await fetch(API_BASE + path, { signal: ctl.signal, headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

/** Public decks: [{ id, name, description, visibility, stats }]. */
export const fetchDecks = () => getJson('/api/decks');

/** One deck with its stat definitions and cards, mapped to the frontend model. */
export async function fetchDeck(id) {
  const [deck, cards] = await Promise.all([getJson(`/api/decks/${id}`), getJson(`/api/decks/${id}/cards`)]);
  return mapDeck(deck, cards);
}

/** API JSON -> frontend deck model. Cards that lack one of the deck's stats are dropped (the engine needs them all). */
export function mapDeck(deckJson, cardsJson) {
  const stats = [...deckJson.stats]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((s) => ({ key: s.key, label: s.label, unit: s.unit ?? null, higherWins: s.comparisonStrategy === 'HIGHER_WINS' }));

  const cards = cardsJson
    .map((c) => ({
      id: c.id,
      name: c.name,
      img: c.img ?? null,
      brand: c.meta?.brand ?? null,
      stats: Object.fromEntries(stats
        .filter((s) => c.stats?.[s.key])
        .map((s) => [s.key, { value: Number(c.stats[s.key].value), display: c.stats[s.key].displayValue }])),
    }))
    .filter((c) => stats.every((s) => s.key in c.stats));

  if (cards.length !== cardsJson.length) {
    console.warn(`${cardsJson.length - cards.length} card(s) skipped: missing one of the deck's stats`);
  }
  return { id: deckJson.id, name: deckJson.name, description: deckJson.description ?? '', source: 'api', stats, cards };
}
