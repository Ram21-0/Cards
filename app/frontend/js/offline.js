// Offline fallback: turns the bundled data/cards.js (legacy shape with feet/inches) into the same deck model
// the API produces, so the game still works when the backend is unreachable.

const STATS = [
  { key: 'rank',   label: 'Rank',   unit: null,  higherWins: false },
  { key: 'fight',  label: 'Fight',  unit: null,  higherWins: true },
  { key: 'chest',  label: 'Chest',  unit: 'in',  higherWins: true },
  { key: 'biceps', label: 'Biceps', unit: 'in',  higherWins: true },
  { key: 'height', label: 'Height', unit: 'in',  higherWins: true },   // total inches
  { key: 'weight', label: 'Weight', unit: 'lbs', higherWins: true },
];

export function offlineDeck(legacyCards = []) {
  const cards = legacyCards.map((c) => {
    const height = c.feet * 12 + c.inches;
    const v = (value, display) => ({ value, display });
    return {
      id: c.id,
      name: c.name,
      img: c.img || null,
      brand: c.brand ?? null,
      stats: {
        rank: v(c.rank, `${c.rank}`),
        fight: v(c.fight, `${c.fight}`),
        chest: v(c.chest, `${c.chest}`),
        biceps: v(c.biceps, `${c.biceps}`),
        height: v(height, `${c.feet}'${c.inches}"`),
        weight: v(c.weight, `${c.weight}lbs`),
      },
    };
  });
  return { id: 'offline', name: 'Wrestling (offline)', description: '', source: 'offline', stats: STATS, cards };
}
