// "Big cards": the strongest cards of a deck, judged on what the cards can do (their stats),
// not on the number printed as "rank". Pure functions, no DOM.
//
// Strength of a card = for every stat, the share of the other cards it beats (a tie counts half), averaged over the stats.
// A stat called "rank" is left out: it is a label for where a card sits, and we want the strength itself.
// Falls back to all stats if "rank" would be the only one.

export const BIG_CARDS = 10;

/** Map card id -> strength between 0 and 1. Cards are { id, stats: { key: { value } } }; stats are { key, higherWins }. */
export function cardStrengths(cards, stats) {
  const used = stats.filter((s) => s.key !== 'rank');
  const list = used.length ? used : stats;
  const n = cards.length;
  const out = new Map();
  for (const c of cards) {
    let sum = 0;
    for (const s of list) {
      let beaten = 0;
      for (const o of cards) {
        if (o === c) continue;
        const x = c.stats[s.key].value;
        const y = o.stats[s.key].value;
        if (x === y) beaten += 0.5;
        else if ((x > y) === s.higherWins) beaten += 1;
      }
      sum += n > 1 ? beaten / (n - 1) : 0;
    }
    out.set(c.id, list.length ? sum / list.length : 0);
  }
  return out;
}

/**
 * The strongest cards, strongest first: [{ id, position }] with position 1 = strongest.
 * At most `count` (10), and never more than half the deck, so "big" still means something in a small deck.
 */
export function topCards(cards, stats, count = BIG_CARDS) {
  const limit = Math.max(0, Math.min(count, Math.floor(cards.length / 2)));
  const scores = cardStrengths(cards, stats);
  return [...cards]
    .sort((a, b) => scores.get(b.id) - scores.get(a.id) || a.id - b.id)      // ties: lower id first, so it is stable
    .slice(0, limit)
    .map((c, i) => ({ id: c.id, position: i + 1 }));
}

/** Map card id -> position (1 = strongest) for the big cards of a deck. */
export const strongMap = (cards, stats, count = BIG_CARDS) => new Map(topCards(cards, stats, count).map((t) => [t.id, t.position]));

/**
 * What to celebrate or mourn after a round, for the player in `mySeat`.
 * `reveals` = every card that was in play this round: [{ seat, card: { id, name } }] (all clash levels).
 * Winning the round takes the other players' cards: big ones are "won". Losing it gives away my own cards: big ones are "lost".
 * A round nobody wins (the pool carries over) and a round I am not part of give nothing.
 */
export function bigCardNotices({ winnerSeat, mySeat, reveals, strong }) {
  if (winnerSeat == null || mySeat == null || !strong || !reveals?.length) return [];
  const iWon = winnerSeat === mySeat;
  const seen = new Set();
  const notices = [];
  for (const { seat, card } of reveals) {
    const position = strong.get(card.id);
    if (position == null || seen.has(card.id)) continue;
    if (iWon && seat !== mySeat) notices.push({ kind: 'won', id: card.id, name: card.name, position });
    else if (!iWon && seat === mySeat) notices.push({ kind: 'lost', id: card.id, name: card.name, position });
    else continue;
    seen.add(card.id);
  }
  return notices.sort((a, b) => a.position - b.position);
}

/** "You won Atlas Wolfe" / "You lost Iron Duke". */
export const bigCardText = (n) => `${n.kind === 'won' ? 'You won' : 'You lost'} ${n.name}`;
