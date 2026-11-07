// Pure game engine: no DOM, no I/O. Fully unit-testable.

export const STATS = [
  { key: 'rank',   label: 'Rank',   higherWins: false, fmt: (c) => `${c.rank}` },
  { key: 'fight',  label: 'Fight', higherWins: true,  fmt: (c) => `${c.fight}` },
  { key: 'chest',  label: 'Chest',  higherWins: true,  fmt: (c) => `${c.chest}` },
  { key: 'biceps', label: 'Biceps', higherWins: true,  fmt: (c) => `${c.biceps}` },
  { key: 'height', label: 'Height', higherWins: true,  fmt: (c) => `${Math.floor(c.height / 12)}'${c.height % 12}"` },
  { key: 'weight', label: 'Weight', higherWins: true,  fmt: (c) => `${c.weight}lbs` },
];

export const RESULT = { WIN: 'win', LOSS: 'loss', CLASH: 'clash' };

/** Normalise a raw card: derive total-inches height. */
export function normaliseCard(c) {
  const height = c.height ?? c.feet * 12 + c.inches;
  return { ...c, height };
}

export function statByKey(key) {
  const s = STATS.find((x) => x.key === key);
  if (!s) throw new Error(`Unknown stat: ${key}`);
  return s;
}

/** Compare two cards on a stat from the first card's point of view. */
export function compare(a, b, key) {
  const s = statByKey(key);
  if (a[key] === b[key]) return RESULT.CLASH;
  const aBigger = a[key] > b[key];
  return aBigger === s.higherWins ? RESULT.WIN : RESULT.LOSS;
}

export function shuffle(arr, rng = Math.random) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/**
 * For every card and stat: the fraction of the deck that card beats (0..1).
 * Used by the CPU to judge how strong a stat really is.
 */
export function buildStrengths(cards) {
  const map = new Map();
  for (const c of cards) {
    const strengths = {};
    for (const s of STATS) {
      let beaten = 0;
      for (const o of cards) if (o !== c && compare(c, o, s.key) === RESULT.WIN) beaten++;
      strengths[s.key] = beaten / Math.max(1, cards.length - 1);
    }
    map.set(c.id, strengths);
  }
  return map;
}

export const DIFFICULTY = { EASY: 'easy', NORMAL: 'normal', HARD: 'hard' };

/** CPU picks a stat for its top card. */
export function cpuChoose(card, strengths, difficulty = DIFFICULTY.NORMAL, rng = Math.random) {
  const keys = STATS.map((s) => s.key);
  if (difficulty === DIFFICULTY.EASY) return keys[Math.floor(rng() * keys.length)];
  const noise = difficulty === DIFFICULTY.NORMAL ? 0.25 : 0;
  let best = keys[0];
  let bestScore = -Infinity;
  for (const k of keys) {
    const score = strengths.get(card.id)[k] + rng() * noise;
    if (score > bestScore) { bestScore = score; best = k; }
  }
  return best;
}

export class Game {
  /**
   * @param {object[]} cards full deck (even count recommended)
   * @param {{rng?: ()=>number, difficulty?: string, firstTurn?: 'player'|'cpu'}} opts
   */
  constructor(cards, opts = {}) {
    this.rng = opts.rng ?? Math.random;
    this.difficulty = opts.difficulty ?? DIFFICULTY.NORMAL;
    this.deck = cards.map(normaliseCard);
    this.strengths = buildStrengths(this.deck);
    const shuffled = shuffle(this.deck, this.rng);
    this.player = shuffled.filter((_, i) => i % 2 === 0);
    this.cpu = shuffled.filter((_, i) => i % 2 === 1);
    this.pot = [];
    this.turn = opts.firstTurn ?? (this.rng() < 0.5 ? 'player' : 'cpu');
    this.over = false;
    this.winner = null; // 'player' | 'cpu' | 'draw'
    this.round = 0;
  }

  get playerCount() { return this.player.length; }
  get cpuCount() { return this.cpu.length; }
  get potCount() { return this.pot.length; }

  get playerCard() { return this.player[0] ?? null; }
  get cpuCard() { return this.cpu[0] ?? null; }

  /** The stat the CPU will call this round (only meaningful on the CPU's turn). */
  cpuPick() {
    return cpuChoose(this.cpuCard, this.strengths, this.difficulty, this.rng);
  }

  /**
   * Resolve a round on `statKey`. Returns details for the UI.
   * Winner gets both cards plus any pot and keeps/gets the turn. A clash
   * puts both cards in the pot and the turn stays with whoever had it.
   */
  play(statKey) {
    if (this.over) throw new Error('Game is over');
    const p = this.player.shift();
    const c = this.cpu.shift();
    const result = compare(p, c, statKey);
    const caller = this.turn;
    this.round++;

    if (result === RESULT.CLASH) {
      this.pot.push(p, c);
    } else {
      const winnerPile = result === RESULT.WIN ? this.player : this.cpu;
      winnerPile.push(p, c, ...this.pot);
      this.pot = [];
      this.turn = result === RESULT.WIN ? 'player' : 'cpu';
    }

    if (this.player.length === 0 && this.cpu.length === 0) {
      this.over = true;
      this.winner = 'draw';
    } else if (this.player.length === 0) {
      this.over = true;
      this.winner = 'cpu';
    } else if (this.cpu.length === 0) {
      this.over = true;
      this.winner = 'player';
    }

    return { result, statKey, caller, playerCard: p, cpuCard: c, over: this.over, winner: this.winner };
  }
}
