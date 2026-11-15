// The table screen, as data (no DOM): which of the seven states we are in, what the heading says, the result row,
// the event log, the countdown. Design: docs/gameplay-design/gameplay-design.md. Everything here is pure and unit-tested.

import { seatName, callCard, clashInfo, ordinal, overSummary, overFromGame } from './live.js';

export const DEFAULT_HOLD_SECONDS = 6;     // how long a round result stays up (the server's `resultHoldSeconds` overrides it)
export const HOLD_EXTRA_SECONDS = 3;       // ... plus this after a clash or an elimination

/** How long the result of `round` (a folded round, see foldRounds) stays up. 0 = no hold. */
export function holdSeconds(options, round) {
  const base = options?.resultHoldSeconds ?? DEFAULT_HOLD_SECONDS;
  if (!(base > 0)) return 0;
  return base + (round && (round.clash > 0 || round.eliminated?.length > 0) ? HOLD_EXTRA_SECONDS : 0);
}

/** Has this player run out of cards (they keep receiving views and may keep watching)? */
export function isOut(view, mySeat) {
  if (!view || mySeat == null) return false;
  return view.placements?.[mySeat] != null || view.handSizes?.[mySeat] === 0;
}

/**
 * Which of the states the table is in.
 *   connecting · call · wait · clash (I may race) · clashWatch · choose · result · next · out · over
 * `ui` = { dismissedKey: the deadline of the result the player closed with Continue, overHoldUntil: ms epoch while the last round's result is still up }.
 */
export function tableKind({ game, view, rt, mySeat, ui = {}, nowMs = Date.now() }) {
  if (game?.status === 'FINISHED') return ui.overHoldUntil > nowMs && rt?.lastRound ? 'result' : 'over';
  if (!view) return 'connecting';
  if (isOut(view, mySeat)) return 'out';
  if (view.phase === 'ROUND_RESULT') return ui.dismissedKey != null && ui.dismissedKey === view.deadline ? 'next' : 'result';
  if (view.canChoose) return 'choose';
  const clash = clashInfo({ view, game, mySeat });
  if (view.canCall) return clash.active ? 'clash' : 'call';
  if (view.phase === 'CLASH_AWAIT_CALL') return clash.iAmContender ? 'clash' : 'clashWatch';
  return 'wait';
}

const statOf = (stats, key) => stats?.find((s) => s.key === key) ?? null;
export const statLabel = (stats, key) => statOf(stats, key)?.label ?? key ?? '';
const lowerWins = (stats) => (stats ?? []).filter((s) => !s.higherWins);
const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** "Call a stat. Highest wins, except Rank where the lowest wins." */
export function callRule(stats) {
  const low = lowerWins(stats).map((s) => s.label);
  return low.length ? `Call a stat. Highest wins, except ${low.join(' and ')} where the lowest wins.` : 'Call a stat. Highest wins.';
}

/** The best card of a hand for a stat: the index of the highest (or lowest) value, first one on ties. -1 when nothing can be compared. */
export function bestIndex(hand, stats, key) {
  const s = statOf(stats, key);
  if (!s) return -1;
  let best = -1;
  hand.forEach((c, i) => {
    const v = c.stats?.[key]?.value;
    if (v == null) return;
    if (best < 0) { best = i; return; }
    const b = hand[best].stats[key].value;
    if (s.higherWins ? v > b : v < b) best = i;
  });
  return best;
}

/** A name with a possessive, from the point of view of the player in `mySeat`: "your" / "ann's". */
const possessive = (game, seat, mySeat) => (seat === mySeat ? 'your' : `${seatName(game, seat)}'s`);
const who = (game, seat, mySeat) => (seat === mySeat ? 'you' : seatName(game, seat));
const display = (card, key) => card?.stats?.[key]?.displayValue ?? card?.stats?.[key]?.display ?? card?.stats?.[key]?.value ?? '–';

/**
 * The cards of a finished round for the result row: best first, the winner first of all.
 * `lr` is a folded round (cards: { seat: card }). Cards are in the API shape ({ name, stats: { key: { value, displayValue } } }).
 */
export function resultRows(lr, game, mySeat, stats) {
  const s = statOf(stats, lr?.stat);
  const entries = Object.entries(lr?.cards ?? {}).map(([seat, card]) => ({ seat: Number(seat), card, value: card?.stats?.[lr.stat]?.value ?? null }));
  const dir = s && !s.higherWins ? 1 : -1;
  entries.sort((a, b) => (a.seat === lr?.winner ? -1 : b.seat === lr?.winner ? 1 : 0) || (a.value == null ? 1 : b.value == null ? -1 : dir * (a.value - b.value)));
  const top = entries.find((e) => e.seat === lr?.winner)?.value ?? null;
  return entries.map((e) => ({
    seat: e.seat,
    who: who(game, e.seat, mySeat),
    name: e.card?.name ?? '',
    card: e.card,
    display: display(e.card, lr.stat),
    winner: e.seat === lr?.winner,
    tie: e.seat !== lr?.winner && top != null && e.value === top,
  }));
}

/** Heading, sub line and chips of the round result. `big` = bigCardNotices(...) for this round. */
export function resultHead({ lr, game, mySeat, stats, big = [] }) {
  const clash = lr.clash > 0;
  const rows = resultRows(lr, game, mySeat, stats);
  const noun = clash ? 'clash' : 'round';
  const label = statLabel(stats, lr.stat);
  const chips = [];
  let h1;
  let tone;
  let sub;
  if (lr.winner == null) {
    h1 = 'No winner';
    tone = 'clash';
    sub = rows.length ? `${label ? `${label.toUpperCase()}: ` : ''}nobody could settle the tie.` : 'Nobody could settle the tie.';
    if (lr.potCarried != null) chips.push({ tone: 'clash', text: `The pool (${lr.potCarried}) carries over` });
  } else {
    const mine = lr.winner === mySeat;
    h1 = mine ? `You win the ${noun}` : `${seatName(game, lr.winner)} wins the ${noun}`;
    tone = mine ? 'win' : 'loss';
    const [first, second] = rows;
    const bold = label.toUpperCase();
    sub = second
      ? [{ b: bold }, ` ${first.name} ${first.display} beats ${possessive(game, second.seat, mySeat)} ${second.name} ${second.display}.`]
      : [{ b: bold }, ` ${first?.name ?? ''} ${first?.display ?? ''}.`];
    const takes = mine ? 'You take' : `${seatName(game, lr.winner)} takes`;
    chips.push({ tone: mine ? 'win' : '', text: `${takes} ${clash ? `the pool: ${plural(lr.cardsWon, 'card')}` : plural(lr.cardsWon, 'card')}` });
  }
  for (const e of lr.eliminated ?? []) chips.push({ tone: 'loss', text: `${e.seat === mySeat ? 'You are' : `${seatName(game, e.seat)} is`} out · ${ordinal(e.placement)}` });
  for (const n of big) chips.push({ tone: n.kind === 'lost' ? 'lost' : 'won', big: true, text: `Big card: you ${n.kind} ${n.name}` });
  return { h1, tone, sub, chips, rows };
}

/** One entry per seat for the strip / rail. */
export function seatChips({ game, view, mySeat, kind }) {
  const seats = game?.seats ?? [];
  const n = seats.length;
  const resting = kind === 'result' || kind === 'next' || kind === 'over' || view?.phase === 'ROUND_RESULT';     // a round is decided: nobody is calling or racing
  const clashOn = Boolean(view) && !resting && (view.clashLevel > 0 || String(view.phase ?? '').startsWith('CLASH'));
  return seats.map((s) => {
    const placements = kind === 'over' ? { ...(view?.placements ?? {}), ...Object.fromEntries(seats.filter((x) => x.placement != null).map((x) => [x.seat, x.placement])) } : view?.placements ?? {};
    const handSize = view?.handSizes?.[s.seat] ?? null;
    const placement = placements[s.seat] ?? s.placement ?? null;
    const out = kind === 'over' ? false : placement != null || handSize === 0;
    const contender = clashOn && Boolean(view?.contenders?.includes(s.seat)) && !out;
    const caller = !resting && !clashOn && view?.callerSeat === s.seat && !out;
    const dim = clashOn && !contender && !out;
    const name = seatName(game, s.seat);
    const isMe = s.seat === mySeat;
    const count = kind === 'over' ? (placement != null ? ordinal(placement) : '–') : out ? 'OUT' : handSize == null ? '–' : String(handSize);
    const flag = contender ? 'VS' : caller ? (n > 5 ? 'CALL' : 'CALLS') : '';
    const sub = contender ? 'In the clash' : out ? 'Out' : caller ? 'Calls' : isMe ? 'You' : '';
    const aria = [name, isMe ? '(you)' : null, kind === 'over' ? (placement != null ? `finished ${ordinal(placement)}` : null) : out ? 'out' : handSize == null ? null : plural(handSize, 'card'),
      caller ? 'calls' : null, contender ? 'in the clash' : null].filter(Boolean).join(', ');
    return {
      seat: s.seat, name, short: isMe ? 'You' : name, initial: (name[0] ?? '?').toUpperCase(), isMe, out, caller, contender, dim, count, flag, sub,
      unit: kind === 'over' || out || handSize == null ? '' : handSize === 1 ? 'card' : 'cards', aria, n,
    };
  });
}

/** The cards I hold, as one pile list: the card in play first (if the server leaves it out), then the pile in order. */
export function pileView(view, mySeat, deckCards) {
  const pile = Array.isArray(view?.ownPile) ? view.ownPile : null;
  if (!pile) return null;
  const total = view.handSizes?.[mySeat];
  const inPlayCard = view.committed ?? (!view.canChoose ? view.ownHand?.[0] : null) ?? null;
  const showsInPlay = Boolean(inPlayCard) && (total == null || pile.length < total);
  const byId = new Map((deckCards ?? []).map((c) => [c.id, c]));
  const row = (c, extra) => ({ id: c.id ?? null, name: c.name ?? '', img: byId.get(c.id)?.img ?? null, brand: byId.get(c.id)?.brand ?? null, ...extra });
  const rows = [];
  if (showsInPlay) rows.push(row(inPlayCard, { inPlay: true }));
  pile.forEach((c, i) => rows.push(row(c, { next: i === 0 })));
  return { rows, count: rows.length, note: 'Your cards in the order they come up. Cards you win go to the bottom.' };
}

/**
 * The event log, newest first, grouped by round: [{ round, lines: [{ text, kind }] }] (kind: win | clash | out | '' for the icon).
 * `events` are the raw server events, oldest first.
 */
export function logGroups(events, game, label = (k) => k) {
  const groups = [];
  let clash = false;
  let cur = null;
  for (const ev of events ?? []) {
    if (ev.type === 'roundStarted') { clash = false; cur = { round: ev.round, lines: [] }; groups.push(cur); }
    if (ev.type === 'clashStarted') clash = true;
    const text = ev.type === 'revealed' ? 'Cards revealed' : textOf(ev, game, label, clash);
    if (!text) continue;
    if (!cur) { cur = { round: null, lines: [] }; groups.push(cur); }
    cur.lines.push({ text, kind: ev.type === 'roundWon' ? 'win' : ev.type === 'clashStarted' || ev.type === 'potCarried' ? 'clash' : ev.type === 'playerEliminated' ? 'out' : '' });
  }
  return groups.reverse().map((g) => ({ ...g, lines: g.lines.reverse() }));
}

function textOf(ev, game, label, clash) {
  const name = (seat) => seatName(game, seat);
  const auto = ev.auto ? ' (time ran out)' : '';
  switch (ev.type) {
    case 'roundStarted': return `Round ${ev.round}: ${name(ev.caller)} calls`;
    case 'cardChosen': return `${name(ev.seat)} chose a card${auto}`;
    case 'statCalled': return ev.seat == null ? `Nobody called in time: ${label(ev.stat)} was picked at random` : `${name(ev.seat)} called ${label(ev.stat)}${auto}`;
    case 'roundWon': return `${name(ev.seat)} won the ${clash ? 'clash' : 'round'} and takes ${clash ? 'the pool: ' : ''}${plural(ev.cardsWon, 'card')}`;
    case 'clashStarted': return `CLASH${ev.level > 1 ? ` ×${ev.level}` : ''}: ${(ev.tiedSeats ?? []).map(name).join(' and ')} tied (pool ${ev.potSize})`;
    case 'potCarried': return `Nobody could settle the tie: the pool (${ev.potSize}) carries over`;
    case 'playerEliminated': return `${name(ev.seat)} is out (#${ev.placement})`;
    case 'gameOver': return ev.endedByRoundCap ? 'Round cap reached: game over' : 'Game over';
    default: return '';
  }
}

// ---------------- countdown ----------------

/** The length (seconds) the countdown ring counts from, for the phase on screen: from the game's options, or null when unknown. */
export function timerTotal({ view, options, lastRound }) {
  if (!view) return null;
  if (view.phase === 'ROUND_RESULT') return holdSeconds(options, lastRound) || null;
  if (view.phase === 'CLASH_AWAIT_CALL') return options?.clashCallSeconds ?? null;
  return options?.turnTimeoutSeconds ?? null;
}

/**
 * Where a countdown stands. `total` may be null/too small (an old game body, a changed setting): it grows to what is left.
 * `urgentAt`: the last this-many seconds are urgent. `steps`: reduced motion, whole seconds only.
 */
export function timerModel({ deadline, nowMs, skewMs = 0, total, urgentAt = 0, steps = false }) {
  if (!deadline) return null;
  const end = Date.parse(deadline);
  if (Number.isNaN(end)) return null;
  const left = Math.max(0, (end - (nowMs + skewMs)) / 1000);
  const whole = Math.ceil(left);
  const span = Math.max(total || 0, whole, 1);
  const shown = steps ? whole : left;
  return { left, whole, span, frac: Math.min(1, shown / span), urgent: left > 0 && left <= urgentAt };
}

/** Seconds at which a countdown turns urgent for a phase: the last 5 s of a turn, the last 2 s of a clash race, never for a result hold. */
export const urgentAtFor = (phase) => (phase === 'ROUND_RESULT' ? 0 : phase === 'CLASH_AWAIT_CALL' ? 2 : 5);

// ---------------- game over ----------------

/** Headline, sub line and ranking of the game-over state. */
export function overHead({ over, game, mySeat, rounds }) {
  const sum = overSummary({ over, game, mySeat });
  const winners = sum.rows.filter((r) => r.placement === 1);
  const mine = sum.rows.find((r) => r.isMe)?.placement ?? null;
  const total = sum.rows.length;
  const h1 = mine === 1 && winners.length === 1 ? 'You win' : mine === 1 ? 'You tied for 1st' : winners.length === 1 ? `${winners[0].name} wins` : winners.length > 1 ? `${winners.map((w) => w.name).join(' and ')} tie` : 'Game over';
  const parts = [];
  if (sum.endedByRoundCap) parts.push('The round limit ended the game: the player with the most cards wins.');
  else {
    if (mine != null) parts.push(`You finished ${ordinal(mine)} of ${total}.`);
    if (rounds) parts.push(`Game lasted ${rounds} rounds.`);
  }
  if (!parts.length && total) parts.push(`${total} players.`);
  return { h1, tone: mine === 1 ? 'gold' : winners.length ? 'gold' : '', sub: parts.join(' '), rows: sum.rows, won: mine === 1 };
}

/** The card the player is looking at: their committed card or top card (null when nothing is to be shown). */
export const focusCard = (view) => callCard(view);

export { overFromGame };
