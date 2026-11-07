// Generates PLACEHOLDER cards (fictional names, seeded stats) into data/cards.js.
// Replace data/cards.js with your real data (see README) when you have it.
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

let seed = 42;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296);
const int = (a, b) => Math.floor(a + rnd() * (b - a + 1));

const first = ['Iron', 'Thunder', 'Crimson', 'Savage', 'Atlas', 'Viper', 'Granite', 'Phantom', 'Titan', 'Blaze', 'Rogue', 'Mammoth', 'Cobra', 'Havoc', 'Brute'];
const last = ['Mike', 'Duke', 'Kane', 'Rex', 'Bishop', 'Stone', 'Wolfe', 'Cross', 'Fury', 'Hammer', 'Slade', 'Knox', 'Bruno', 'Drake', 'Vance'];
const brands = ['raw', 'sd']; // 'sd' cards are blue, anything else is red (as in the Android app)

const names = new Set();
while (names.size < 54) names.add(`${first[int(0, first.length - 1)]} ${last[int(0, last.length - 1)]}`);

const ranks = Array.from({ length: 54 }, (_, i) => i + 1).sort(() => rnd() - 0.5);
const cards = [...names].map((name, i) => {
  const weight = int(190, 330);
  return {
    id: i + 1,
    name,
    brand: brands[int(0, 1)],
    img: '', // empty = silhouette placeholder
    rank: ranks[i],
    fight: int(5, 60),
    chest: int(40, 62),
    biceps: int(14, 26),
    feet: int(5, 6) === 5 ? 5 : 6,
    inches: int(0, 11),
    weight,
  };
});

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'cards.js');
writeFileSync(out, `// PLACEHOLDER DATA - fictional wrestlers. Replace with real data.\nwindow.CARDS = ${JSON.stringify(cards, null, 1)};\n`);
console.log(`Wrote ${cards.length} cards to ${out}`);
