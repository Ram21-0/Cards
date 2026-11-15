// The live table screen (DOM). The states, copy and layout come from docs/gameplay-design/gameplay-design.md;
// everything that can be decided without a DOM lives in table.js (and is unit-tested there).
//
// buildTable(ctx, d) returns the nodes of the whole screen; it is called on every repaint, so nothing here keeps state:
// what the player did (picked a stat, opened a sheet ...) lives on `ctx`. tickTable(ctx, root) moves the countdowns
// between repaints. `d` carries what only app.js knows: cardView, mapCard, play, callStat, chooseCard, leave, openDeck, backToDecks.

import { el } from './dom.js';
import { seatName, callCard, ordinal } from './live.js';
import {
  tableKind, seatChips, resultHead, pileView, logGroups, callRule, bestIndex, statLabel, timerModel, timerTotal, urgentAtFor, overHead, holdSeconds,
} from './table.js';
import { bigCardNotices, bestStat } from './strength.js';
import { HOLD_MAX, clampHold, holdText } from './table.js';

const PLACEHOLDER = 'assets/placeholder.png';
const RING = 163.4;                           // circumference of the timer ring (r = 26)
const WIDE = '(min-width:1100px)';
const reducedMotion = (ctx) => Boolean(ctx.rm) || (typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches);
const bold = (parts) => (Array.isArray(parts) ? parts.map((p) => (typeof p === 'string' || p?.nodeType ? p : el('b', {}, p.b))) : parts);
const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

// ---------------- countdown ----------------

/** The countdown on show: the server's deadline, or (for the last round, which the server does not hold) our own timer. */
function countdown(ctx, kind) {
  const v = ctx.view;
  const skew = ctx.rt.skewMs;
  const steps = reducedMotion(ctx);
  if (kind === 'result' && ctx.game.status === 'FINISHED') {
    if (!(ctx.overHoldUntil > Date.now())) return null;
    return timerModel({ deadline: new Date(ctx.overHoldUntil).toISOString(), nowMs: Date.now(), skewMs: 0, total: ctx.overHoldTotal, urgentAt: 0, steps });
  }
  if (!v?.deadline) return null;
  if (ctx.tt?.deadline !== v.deadline) {                // a new deadline: remember how long it started
    const known = timerTotal({ view: v, options: ctx.game.options, lastRound: ctx.rt.lastRound });
    const first = timerModel({ deadline: v.deadline, nowMs: Date.now(), skewMs: skew, total: 0 })?.whole ?? 0;
    ctx.tt = { deadline: v.deadline, total: Math.max(known ?? 0, first) };
  }
  return timerModel({ deadline: v.deadline, nowMs: Date.now(), skewMs: skew, total: ctx.tt.total, urgentAt: urgentAtFor(v.phase), steps });
}

function ring(tm, { big = false, label = 'Time left' } = {}) {
  const node = el('div', { class: `timer${big ? ' big' : ''}${tm.urgent ? ' urgent' : ''}`, role: 'timer', 'aria-label': label },
    (() => {
      const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      s.setAttribute('viewBox', '0 0 60 60');
      s.setAttribute('aria-hidden', 'true');
      for (const cls of ['trk', 'arc']) {
        const c = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
        c.setAttribute('class', cls);
        c.setAttribute('cx', '30'); c.setAttribute('cy', '30'); c.setAttribute('r', '26');
        if (cls === 'arc') c.style.strokeDashoffset = (RING * (1 - tm.frac)).toFixed(1);
        s.append(c);
      }
      return s;
    })(),
    el('span', { class: 't-num' }, String(tm.whole)),
    el('span', { class: 't-lab' }, 'sec'));
  return node;
}

/** Moves every countdown on screen (called a few times a second; no repaint). */
export function tickTable(ctx, root) {
  if (!root) return;
  const kind = root.querySelector('.app')?.dataset.kind;
  const tm = countdown(ctx, kind);
  if (!tm) return;
  for (const t of root.querySelectorAll('.timer')) {
    t.querySelector('.arc').style.strokeDashoffset = (RING * (1 - tm.frac)).toFixed(1);
    t.querySelector('.t-num').textContent = String(tm.whole);
    t.classList.toggle('urgent', tm.urgent);
  }
  for (const b of root.querySelectorAll('.racebar')) {
    b.firstElementChild.style.transform = `scaleX(${tm.frac})`;
    b.classList.toggle('urgent', tm.urgent);
  }
  for (const f of root.querySelectorAll('.cta .fill')) {
    f.style.width = `${((1 - tm.frac) * 100).toFixed(1)}%`;
    f.parentElement.querySelector('.cnt').textContent = String(tm.whole);
  }
  for (const n of root.querySelectorAll('.tn')) n.textContent = String(tm.whole);
}

// ---------------- pieces ----------------

const head = (h1, tone, sub, extra = '') => el('div', { class: 'head' },
  el('div', { class: 'txt' }, el('h1', { class: `h1 ${tone ?? ''}`, role: 'status' }, bold(h1)), sub ? el('p', { class: 'sub' }, bold(sub)) : ''),
  extra);

const cardZone = (label, node, { clash = false } = {}) => el('div', { class: 'cardzone' },
  el('div', { class: `cardlab${clash ? ' clash' : ''}` }, label),
  el('div', { class: 'cw' }, node));

/** The time left on the head: a ring, or "No time limit" when the game has none (only where a player is being waited for). */
function headTimer(ctx, kind, opts = {}) {
  const tm = countdown(ctx, kind);
  if (tm) return ring(tm, opts);
  return opts.noLimit && ctx.game.options?.turnTimeoutSeconds == null ? el('div', { class: 'nolimit' }, 'No time limit') : '';
}

function statGrid(ctx, d, card, { clash = false, onPick = null } = {}) {
  const deck = ctx.deck;
  return el('div', { class: `stats${clash ? ' clashmode' : ''}${ctx.picked ? ' locked' : ''}` }, deck.stats.map((s, i) => {
    const shown = card.stats[s.key]?.display ?? '–';
    return el('button', {
      class: `stat${ctx.picked === s.key ? ' picked' : ''}`, disabled: ctx.pending && ctx.picked !== s.key, 'data-key': s.key,
      'aria-label': `Call ${s.label}${s.higherWins ? '' : ', lower wins'}, value ${shown}`,
      onclick: () => {
        if (ctx.pending) return;
        ctx.picked = s.key;
        if (onPick) onPick(s.key);
        else d.play(ctx, () => d.callStat(ctx.code, s.key));
      },
    }, el('span', { class: 'k', 'aria-hidden': 'true' }, String(i + 1)), el('span', { class: 'l' }, s.label, s.higherWins ? '' : ' ↓'), el('span', { class: 'v' }, shown));
  }));
}

const waitBox = (name, title, line, note) => el('div', { class: 'waitbox' },
  el('span', { class: 'av', 'aria-hidden': 'true' }, (name[0] ?? '?').toUpperCase()),
  el('div', { class: 't' }, el('b', {}, title), el('span', {}, line, ' ', el('span', { class: 'dots', 'aria-hidden': 'true' }, el('i'), el('i'), el('i')), note ? [el('br'), note] : '')));

const cta = (label, onclick, { ghost = false, fill = false, cnt = null, disabled = false } = {}) => el('button', {
  class: `cta${ghost ? ' ghost' : ''}`, onclick, disabled,
}, fill ? el('i', { class: 'fill' }) : '', el('span', {}, label, cnt != null ? [' ', el('span', { class: 'cnt' }, String(cnt))] : ''));

function revealRow(ctx, d, rows, stat, key) {
  if (!rows.length) return '';
  const fresh = ctx.animKey !== key;
  ctx.animKey = key;
  return el('div', { class: `t-reveals${rows.length > 4 ? ' scroll' : ''}${fresh ? ' fresh' : ''}`, style: `--per:${Math.min(rows.length, 4)}`, role: 'list', 'aria-label': 'Revealed cards' },
    rows.map((r, i) => el('figure', { class: `rv ${r.winner ? 'win' : 'lose'}${r.tie ? ' eq' : ''}`, role: 'listitem', style: `--i:${i}` },
      r.winner ? el('span', { class: 't-tag' }, 'WINNER') : '',
      d.cardView(d.mapCard(r.card, ctx.deck.stats), ctx.deck, { highlight: stat }),
      el('figcaption', {}, el('span', { class: 'who' }, r.who), el('span', { class: 'val' }, `${statLabel(ctx.deck.stats, stat)} ${r.display}`)))));
}

// ---------------- the state bodies ----------------

function stateCall(ctx, d, v) {
  const c = callCard(v);
  const card = d.mapCard(c, ctx.deck.stats);
  return el('main', { class: 'stage' },
    head('Your turn', 'gold', callRule(ctx.deck.stats), headTimer(ctx, 'call', { noLimit: true })),
    el('div', { class: 'play' },
      cardZone('Your card', d.cardView(card, ctx.deck, { highlight: ctx.picked })),
      el('div', { class: 'acts' },
        ctx.picked ? el('p', { class: 't-hint', style: 'color:var(--accent);font-weight:700' }, `You called ${statLabel(ctx.deck.stats, ctx.picked)}. Waiting for the others.`) : '',
        statGrid(ctx, d, card))));
}

function stateWait(ctx, d, v) {
  const g = ctx.game;
  const caller = v.callerSeat === ctx.mySeat ? 'You' : seatName(g, v.callerSeat);
  const stats = ctx.deck.stats;
  const s = stats.find((x) => x.key === v.stat);
  const clash = v.clashLevel > 0;
  const c = callCard(v);
  const card = c ? d.mapCard(c, stats) : null;
  let h1; let sub; let panel; let tone = clash ? 'clash' : '';
  if (v.phase === 'AWAIT_CALL' || v.phase === 'AWAITING_CALL') {
    h1 = `${caller} is calling`;
    sub = 'Your card is ready. The stat shows up here as soon as it is picked.';
    panel = waitBox(caller, `Waiting for ${caller}`, 'Picking a stat', ctx.game.options?.turnTimeoutSeconds != null ? 'If the time runs out the server picks one at random.' : '');
  } else if (v.phase === 'AWAIT_CHOOSE_CALLER') {
    h1 = `${caller} is picking a card`;
    sub = 'Your card is ready. The stat shows up here as soon as it is picked.';
    panel = waitBox(caller, `Waiting for ${caller}`, 'Picking a card', '');
  } else if (v.phase === 'AWAIT_CHOOSE_OTHERS' || v.phase === 'CLASH_AWAIT_CHOOSE') {
    h1 = clash ? `Clash${v.clashLevel > 1 ? ` ×${v.clashLevel}` : ''}` : 'Players are choosing their cards';
    sub = s ? [`${caller} called `, { b: s.label }, `. ${s.higherWins ? 'Highest' : 'Lowest'} ${s.label.toLowerCase()} wins.`] : 'Players are choosing their cards.';
    panel = waitBox('?', 'Waiting for the others', 'Choosing cards', '');
  } else {
    h1 = 'Revealing the cards…';
    sub = '';
    panel = waitBox('…', 'One moment', 'Revealing', '');
    tone = '';
  }
  return el('main', { class: 'stage' },
    head(h1, tone, sub, headTimer(ctx, 'wait', { noLimit: true })),
    el('div', { class: 'play' },
      card ? cardZone(v.committed ? 'Your card' : 'Your card', d.cardView(card, ctx.deck, { highlight: v.stat })) : el('div'),
      el('div', { class: 'acts' }, panel)));
}

function stateClash(ctx, d, v) {
  const g = ctx.game;
  const stats = ctx.deck.stats;
  const c = callCard(v);
  const card = c ? d.mapCard(c, stats) : null;
  const level = v.clashLevel;
  const others = (v.contenders ?? []).filter((s) => s !== ctx.mySeat).map((s) => seatName(g, s));
  const alive = g.seats.filter((s) => v.handSizes?.[s.seat] !== 0 && !(v.placements?.[s.seat] != null)).map((s) => s.seat);
  const waiting = alive.filter((s) => !(v.contenders ?? []).includes(s)).length;
  const list = others.length <= 1 ? others[0] ?? 'the others' : `${others.slice(0, -1).join(', ')} and ${others.at(-1)}`;
  const sub = `${others.length ? `You and ${list} tied.` : 'You tied.'} Call a stat first to win the race.${waiting ? ` ${plural(waiting, 'other player waits', 'other players wait')}.` : ''}`;
  const tm = countdown(ctx, 'clash');
  const lost = ctx.raceLost ? [...ctx.rt.feed].reverse().find((e) => e.type === 'statCalled' && e.seat != null && e.seat !== ctx.mySeat) : null;
  return el('main', { class: 'stage' },
    tm ? el('div', { class: `racebar${tm.urgent ? ' urgent' : ''}`, role: 'presentation' }, el('i', { style: `transform:scaleX(${tm.frac})` })) : '',
    head(`Clash${level > 1 ? ` ×${level}` : ''}`, 'clash', sub, tm ? ring(tm, { big: true, label: 'Race time left' }) : ''),
    el('div', { class: 'play' },
      card ? cardZone(`Your next card · Pool ${v.potSize ?? 0}`, d.cardView(card, ctx.deck, { highlight: ctx.picked }), { clash: true }) : el('div'),
      el('div', { class: 'acts' },
        ctx.picked ? el('p', { class: 't-hint', style: 'color:var(--clash);font-weight:700' }, `You called ${statLabel(stats, ctx.picked)} first.`) : '',
        lost ? el('p', { class: 't-hint', style: 'color:var(--clash);font-weight:700' }, `${seatName(g, lost.seat)} called ${statLabel(stats, lost.stat)} first.`) : '',
        card ? statGrid(ctx, d, card, { clash: true }) : '',
        el('p', { class: 't-hint' }, 'First tap wins the race.'))));
}

/** A player not in the clash: they just watch the race. */
function stateClashWatch(ctx, d, v) {
  const g = ctx.game;
  const names = (v.contenders ?? []).map((s) => seatName(g, s));
  const list = names.length <= 1 ? names[0] ?? '' : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`;
  const tm = countdown(ctx, 'clash');
  return el('main', { class: 'stage' },
    tm ? el('div', { class: `racebar${tm.urgent ? ' urgent' : ''}`, role: 'presentation' }, el('i', { style: `transform:scaleX(${tm.frac})` })) : '',
    head(`Clash${v.clashLevel > 1 ? ` ×${v.clashLevel}` : ''}`, 'clash', `${list} ${names.length === 1 ? 'is' : 'are'} racing to call.`, tm ? ring(tm, { big: true, label: 'Race time left' }) : ''),
    el('div', { class: 'play' }, el('div', { class: 'acts' }, waitBox(names[0] ?? '?', 'The race is on', 'Waiting for a stat', ''))));
}

/**
 * Choose, I am the caller (spec 5b): the card and the stat on ONE screen. The top card is preselected; tapping a stat plays the
 * selected card (`choose`) and then calls the stat (`call`, once the server's next view allows it).
 */
function stateCallerChoose(ctx, d, v, hand) {
  const stats = ctx.deck.stats;
  const sel = hand.find((c) => c.id === ctx.pick) ?? hand[0];
  const top = sel.id === hand[0].id;
  const send = (key) => d.play(ctx, async () => {
    const after = await d.chooseCard(ctx.code, sel.id);
    return after.canCall ? d.callStat(ctx.code, key) : after;               // refused or not yet: the server's view is the truth
  });
  return el('main', { class: 'stage st-choose st-callchoose' },
    head('Your turn', 'gold', 'Pick a card to play, then tap a stat. The top card is preselected.', headTimer(ctx, 'choose', { noLimit: true })),
    el('div', { class: 'play' },
      cardZone(top ? 'Playing · top card' : 'Playing', d.cardView(sel, ctx.deck, { highlight: ctx.picked })),
      el('div', { class: 'acts' },
        el('div', { class: 'tray', role: 'radiogroup', 'aria-label': 'Your cards' }, hand.map((c, i) => {
          const best = bestStat(c, ctx.deck.cards, stats);
          return el('button', { class: `tile${c.id === sel.id ? ' sel' : ''}`, role: 'radio', 'aria-checked': String(c.id === sel.id), disabled: ctx.pending || Boolean(ctx.picked), 'data-i': String(i),
            onclick: () => { ctx.pick = c.id; ctx.paint(); } },
            el('span', { class: 'tg' }, i === 0 ? el('b', {}, 'TOP') : ''),
            el('span', { class: 'tn2' }, el('span', { class: 'n' }, c.name), best ? el('span', { class: 'sl' }, `${best.label} ${best.display}`) : ''));
        })),
        ctx.picked ? el('p', { class: 't-hint', style: 'color:var(--accent);font-weight:700' }, `You called ${statLabel(stats, ctx.picked)} with ${sel.name}.`) : '',
        statGrid(ctx, d, sel, { onPick: send }),
        el('p', { class: 't-hint' }, 'Tap a stat to play this card and call it'))));
}

function stateChoose(ctx, d, v) {
  const g = ctx.game;
  const stats = ctx.deck.stats;
  const hand = v.ownHand.map((c) => d.mapCard(c, stats));
  if (!hand.length) return stateWait(ctx, d, v);
  if (!v.stat && v.callerSeat === ctx.mySeat) return stateCallerChoose(ctx, d, v, hand);
  const stat = v.stat ? stats.find((s) => s.key === v.stat) : null;
  const caller = v.callerSeat === ctx.mySeat ? 'You' : seatName(g, v.callerSeat);
  const sel = hand.find((c) => c.id === ctx.pick) ?? hand[0];
  const best = stat ? bestIndex(hand, stats, stat.key) : -1;
  const sub = stat
    ? [`${caller} called `, { b: stat.label }, `. ${stat.higherWins ? 'Highest' : 'Lowest'} ${stat.label.toLowerCase()} wins.`]
    : 'Pick the card to play. You choose the stat next.';
  const clash = v.clashLevel > 0;
  return el('main', { class: 'stage st-choose' },
    head('Pick your card', clash ? 'clash' : '', sub, headTimer(ctx, 'choose', { noLimit: true })),
    el('div', { class: 'play' },
      cardZone('Playing', d.cardView(sel, ctx.deck, { highlight: v.stat })),
      el('div', { class: 'acts' },
        el('div', { class: 'tray', role: 'radiogroup', 'aria-label': 'Your cards' }, hand.map((c, i) => {
          const top = !stat ? bestStat(c, ctx.deck.cards, stats) : null;
          return el('button', { class: `tile${c.id === sel.id ? ' sel' : ''}`, role: 'radio', 'aria-checked': String(c.id === sel.id), disabled: ctx.pending, 'data-i': String(i),
            onclick: () => { ctx.pick = c.id; ctx.paint(); } },
            el('span', { class: 'tg' }, i === 0 ? el('b', {}, 'TOP') : '', i === best ? el('b', { class: 'best' }, 'BEST') : ''),
            el('span', { class: 'tn2' }, el('span', { class: 'n' }, c.name), top ? el('span', { class: 'sl' }, top.label) : ''),
            el('span', { class: 'v' }, stat ? c.stats[stat.key]?.display ?? '–' : top?.display ?? '–'));
        })),
        el('p', { class: 't-hint' }, stat ? `${stat.label} shown on each card. Time out plays your top card.` : 'Best stat shown on each card. Time out plays your top card.'),
        cta(`Play ${sel.name}`, () => d.play(ctx, () => d.chooseCard(ctx.code, sel.id)), { disabled: ctx.pending }))));
}

/** The big-card notices for the round on show (the banner in app.js plays once; the chip stays while the result is up). */
const bigFor = (ctx, lr) => bigCardNotices({ winnerSeat: lr?.winner, mySeat: ctx.mySeat, reveals: lr?.reveals, strong: ctx.strong });

function resultBody(ctx, d, lr) {
  const stats = ctx.deck.stats;
  const h = resultHead({ lr, game: ctx.game, mySeat: ctx.mySeat, stats, big: bigFor(ctx, lr) });
  const key = `${lr.round}:${lr.stat}:${lr.winner}:${lr.clash}`;
  const chips = h.chips.map((c) => (c.big
    ? el('span', { class: `t-notice${c.tone === 'lost' ? ' lost' : ''}` }, c.text)
    : el('span', { class: `t-pill${c.tone ? ` ${c.tone}` : ''}` }, c.text)));
  return { h, key, chips };
}

function stateResult(ctx, d, v) {
  const lr = ctx.rt.lastRound;
  const final = ctx.game.status === 'FINISHED';
  const done = () => { if (final) { ctx.overHoldUntil = 0; ctx.paint(); } else { ctx.dismissedKey = v?.deadline ?? null; ctx.paint(); } };
  const tm = countdown(ctx, 'result');
  if (!lr) {
    return el('main', { class: 'stage' }, el('div', { class: 'resultwrap' }, head('Round over', '', 'The next round starts in a moment.'), el('div', { style: 'flex:1' }),
      cta('Continue', done, { fill: true, cnt: tm?.whole ?? '' })));
  }
  const { h, key, chips } = resultBody(ctx, d, lr);
  return el('main', { class: 'stage' }, el('div', { class: 'resultwrap' },
    head(h.h1, h.tone, h.sub),
    el('div', { class: 't-chips', style: 'margin-top:0' }, chips),
    revealRow(ctx, d, h.rows, lr.stat, key),
    el('div', { style: 'flex:1' }),
    cta('Continue', done, { fill: true, cnt: tm?.whole ?? '' })));
}

/** After Continue: a calm wait until the server starts the next round. */
function stateNext(ctx) {
  const tm = countdown(ctx, 'next');
  return el('main', { class: 'stage' }, el('div', { class: 'resultwrap' },
    head(['Next round in ', el('span', { class: 'tn' }, String(tm?.whole ?? ''))], '', 'Get your next card ready.', tm ? ring(tm, { label: 'Next round in' }) : ''),
    el('div', { style: 'flex:1' }),
    el('div', { class: 'acts' }, waitBox('↻', 'The next round starts soon', 'Dealing', 'The result stays in the event log.')),
    el('div', { style: 'flex:1' }),
    cta('Show the result again', () => { ctx.dismissedKey = null; ctx.paint(); }, { ghost: true })));
}

function stateOut(ctx, d, v) {
  const g = ctx.game;
  const place = v.placements?.[ctx.mySeat] ?? g.seats.find((s) => s.seat === ctx.mySeat)?.placement ?? null;
  const caller = seatName(g, v.callerSeat);
  const phase = v.phase;
  const live = phase === 'ROUND_RESULT' ? ['The round is over', 'The next round starts soon']
    : phase === 'AWAIT_CALL' ? [`${caller} is calling a stat`, `Round ${v.round} · you are watching`]
    : phase === 'AWAIT_CHOOSE_CALLER' ? [`${caller} is picking a card`, `Round ${v.round} · you are watching`]
    : phase === 'CLASH_AWAIT_CALL' ? ['The clash race is on', `Round ${v.round} · you are watching`]
    : ['Players are choosing their cards', `Round ${v.round} · you are watching`];
  const lr = ctx.rt.lastRound;
  const rows = lr ? resultHead({ lr, game: g, mySeat: ctx.mySeat, stats: ctx.deck.stats }).rows : [];
  const tm = countdown(ctx, 'out');
  return el('main', { class: 'stage st-out' },
    head("You're out", 'loss', place != null ? [`You finished `, { b: ordinal(place) }, '. Keep watching, or leave the game.'] : 'You have no cards left. Keep watching, or leave the game.'),
    el('div', { class: 'watchbox' }, el('span', { class: 'av', 'aria-hidden': 'true' }, (caller[0] ?? '?').toUpperCase()),
      el('div', { class: 'txt' }, live[0], el('small', {}, live[1])), tm ? ring(tm) : ''),
    rows.length ? [el('div', { class: 'sechead' }, `Last round · ${statLabel(ctx.deck.stats, lr.stat)}`), revealRow(ctx, d, rows, lr.stat, `out:${lr.round}:${lr.winner}`)] : '',
    el('div', { style: 'flex:1' }),
    el('div', { class: 'cta-row' }, cta('Event log', () => d.open(ctx, 'log'), { ghost: true }), cta('Leave game', d.leave)));
}

function stateOver(ctx, d) {
  const g = ctx.game;
  const rounds = ctx.rt.lastRound?.round ?? ctx.view?.round ?? null;
  const h = overHead({ over: ctx.rt.over ?? d.overFromGame(g), game: g, mySeat: ctx.mySeat, rounds });
  const lr = ctx.rt.lastRound;
  const last = lr && ctx.deck ? resultHead({ lr, game: g, mySeat: ctx.mySeat, stats: ctx.deck.stats }).rows : [];
  const rank = el('ol', { class: `rank${h.rows.length > 5 ? ' dense' : ''}`, 'aria-label': 'Final ranking' }, h.rows.map((r, i) =>
    el('li', { class: `${r.placement === 1 ? 'first' : ''}${r.isMe ? ' me' : ''}` },
      el('span', { class: 'no' }, r.placement != null ? String(r.placement) : '–'),
      el('span', { class: 'nm' }, r.isMe ? 'You' : r.name),
      r.placement === 1 ? el('span', { class: 't-pill' }, 'Winner') : '',
      el('span', { class: 'ord' }, r.placement != null ? ordinal(r.placement) : ''))));
  return el('main', { class: 'stage' }, el('div', { class: 'resultwrap', style: 'gap:14px' },
    head(h.h1, h.tone, h.sub),
    el('div', { class: 'overwrap' }, el('div', {}, rank),
      last.length ? el('div', { class: 'over-only-w' }, el('div', { class: 'sechead', style: 'margin-bottom:10px' }, `Last round · ${statLabel(ctx.deck.stats, lr.stat)}`), revealRow(ctx, d, last, lr.stat, `over:${lr.round}:${lr.winner}`)) : ''),
    el('div', { style: 'flex:1' }),
    el('div', { class: 'cta-row over-cta' },
      ctx.mySeat != null ? cta('New game with this deck', () => d.openDeck(g.deck.id)) : '',
      cta('Back to decks', d.backToDecks, { ghost: ctx.mySeat != null }))));
}

function stateWaiting(ctx, d, text) {
  return el('main', { class: 'stage' }, el('div', { class: 'resultwrap' },
    head(text.h1, '', text.sub),
    el('div', { class: 'acts', style: 'align-items:center' }, text.spin ? el('span', { class: 'spin', 'aria-hidden': 'true', style: 'border-color:var(--line);border-top-color:var(--accent)' }) : ''),
    text.back ? [el('div', { style: 'flex:1' }), cta('Back to decks', d.backToDecks, { ghost: true })] : ''));
}

// ---------------- frame ----------------

const CONN = { open: ['Live', false], connecting: ['Connecting', false], reconnecting: ['Offline', true], closed: ['Offline', true] };

function hud(ctx, d, v, kind) {
  const g = ctx.game;
  const [connText, bad] = CONN[ctx.conn] ?? [null, false];
  const round = v?.round ?? ctx.rt.lastRound?.round ?? null;
  const pool = v?.potSize ?? 0;
  const clash = v && (v.clashLevel > 0 || String(v.phase ?? '').startsWith('CLASH'));
  return el('header', { class: 't-hud' },
    el('button', { class: 'ib', 'aria-label': 'Leave game', onclick: d.leave }, '✕'),
    el('span', { class: 'deckname' }, g.deck.name),
    el('div', { class: 'hud-mid' },
      round != null ? el('span', { class: 'rnd' }, 'Round ', el('b', {}, String(round))) : '',
      v ? el('span', { class: `t-pill pool hide-w${clash ? ' clash' : ''}`, 'aria-label': `Pool ${pool} cards` }, 'Pool ', el('b', {}, String(pool))) : ''),
    el('div', { class: 'hud-r' },
      ctx.mySeat != null && connText ? el('span', { class: `t-conn${bad ? ' bad' : ''}`, role: 'status' }, el('i'), connText) : '',
      ctx.mySeat != null && !finished(ctx) ? el('button', { class: 'ib', 'aria-label': 'Game settings', onclick: () => d.openSettings(ctx) }, '⚙') : '',
      ctx.mySeat != null && v ? [el('button', { class: 'ib w-mid', onclick: () => d.open(ctx, 'log') }, 'Log'), finished(ctx) ? '' : el('button', { class: 'ib w-mid', onclick: () => d.open(ctx, 'pile') }, 'My pile')] : ''));
}

function rail(ctx, v, kind) {
  const chips = seatChips({ game: ctx.game, view: v, mySeat: ctx.mySeat, kind });
  const pool = v?.potSize ?? 0;
  const clash = v?.clashLevel > 0;
  return el('nav', { class: 'rail', 'aria-label': 'Players' },
    el('ul', {}, chips.map((c) => el('li', {
      class: `seat${c.isMe ? ' me' : ''}${c.caller ? ' caller' : ''}${c.contender ? ' contender' : ''}${c.dim ? ' dim' : ''}${c.out ? ' out' : ''}`, 'data-n': String(c.n), 'aria-label': c.aria,
    }, c.flag ? el('span', { class: 's-flag' }, c.flag) : '', el('span', { class: 's-av', 'aria-hidden': 'true' }, c.initial),
    el('span', { class: 'nm2' }, el('span', { class: 's-name' }, c.short), el('span', { class: 's-sub' }, c.sub)),
    el('span', { class: 's-count' }, el('b', {}, c.count), el('i', {}, c.unit))))),
    v ? el('div', { class: 'tablebox' }, el('small', {}, 'Pool'), el('b', {}, String(pool)), ' ', el('span', { style: 'color:var(--muted)' }, pool === 1 ? 'card' : 'cards', clash ? ` · clash${v.clashLevel > 1 ? ` ×${v.clashLevel}` : ''}` : ''),
      pool ? el('div', { class: 'stack', 'aria-hidden': 'true' }, Array.from({ length: Math.min(Math.max(pool, 1), 6) }, () => el('i'))) : '') : '');
}

function logPane(ctx, d) {
  const groups = logGroups(ctx.rt.feed, ctx.game, (k) => statLabel(ctx.deck?.stats, k));
  const list = groups.length
    ? el('ol', { class: 'log', 'aria-label': 'Event log, newest first' }, groups.flatMap((gr, gi) => [
      el('li', { class: 'rh' }, gr.round != null ? `Round ${gr.round}` : 'Earlier'),
      ...gr.lines.map((l, li) => el('li', { class: `e${l.kind ? ` ${l.kind}` : ''}${gi === 0 && li === 0 ? ' now' : ''}` },
        el('span', { class: 'ic', 'aria-hidden': 'true' }, l.kind === 'win' ? '★' : l.kind === 'clash' ? '⚔' : l.kind === 'out' ? '✕' : '●'), el('span', {}, l.text))),
    ]))
    : el('p', { class: 'pnote' }, 'Nothing has happened yet.');
  return [list, el('p', { class: 'pnote', style: 'margin-top:14px' },
    el('button', { class: 'ib', style: 'min-height:44px;font-size:13px;border:1px solid var(--line)', 'aria-pressed': String(Boolean(ctx.rm)), onclick: () => d.toggleMotion(ctx) }, `Reduce motion: ${ctx.rm ? 'on' : 'off'}`))];
}

function pilePane(ctx) {
  const p = pileView(ctx.view, ctx.mySeat, ctx.deck?.cards);
  if (!p) return el('p', { class: 'pnote' }, 'Your pile is not available from this server.');
  return [el('p', { class: 'pnote' }, p.note),
    p.rows.length ? el('ul', { class: 'piles', 'aria-label': 'My pile' }, p.rows.map((r, i) => el('li', { class: r.inPlay ? 'inplay' : r.next ? 'next' : '' },
      el('span', { class: 'ix' }, r.inPlay ? '▶' : String(i + (p.rows[0].inPlay ? 0 : 1))),
      el('span', { class: 'th' }, el('img', { src: r.img || PLACEHOLDER, alt: '', loading: 'lazy', onerror: (e) => { e.target.src = PLACEHOLDER; } })),
      el('span', { class: 'nm' }, r.name),
      r.inPlay || r.next ? el('span', { class: 't-pill' }, r.inPlay ? 'In play' : 'Next') : '',
      r.brand ? el('span', { class: `br${r.brand === 'sd' ? ' sd' : ''}` }, r.brand === 'sd' ? 'SD' : 'RAW') : '')))
      : el('p', { class: 'pnote' }, 'No other cards: only the one in play.')];
}

const pileCount = (ctx) => {
  const p = pileView(ctx.view, ctx.mySeat, null);
  return p ? p.count : ctx.view?.handSizes?.[ctx.mySeat] ?? 0;
};

const finished = (ctx) => ctx.game.status === 'FINISHED';

function dock(ctx, d) {
  const fresh = ctx.rt.feedTotal > (ctx.logSeen ?? 0);
  return el('footer', { class: 't-dock' },
    finished(ctx) ? '' : el('button', { onclick: () => d.open(ctx, 'pile') }, 'My pile ', el('b', {}, String(pileCount(ctx)))),
    el('button', { onclick: () => d.open(ctx, 'log') }, fresh ? el('span', { class: 'dot', 'aria-label': 'New events' }) : '', 'Event log'));
}

function side(ctx, d) {
  const tab = ctx.tab ?? 'log';
  const set = (t) => () => { ctx.tab = t; ctx.paint(); };
  return el('aside', { class: 't-side', 'aria-label': 'Log and pile' },
    el('div', { class: 't-tabs', role: 'tablist' },
      el('button', { role: 'tab', 'aria-selected': String(tab === 'log'), onclick: set('log') }, 'Event log'),
      finished(ctx) ? '' : el('button', { role: 'tab', 'aria-selected': String(tab === 'pile'), onclick: set('pile') }, `My pile · ${pileCount(ctx)}`)),
    el('div', { class: 'pane' }, tab === 'log' || finished(ctx) ? logPane(ctx, d) : pilePane(ctx)));
}

/** Result pause: the host gets a stepper (0 to 20 s, applies from now on), everyone else the current value. Used here and in the lobby. */
export function holdControl(ctx, d, { cls = 'hold' } = {}) {
  const g = ctx.game;
  const value = g.options?.resultHoldSeconds ?? 6;
  const host = ctx.mySeat != null && ctx.mySeat === g.hostSeat;
  const step = (delta) => d.setHold(ctx, clampHold(value + delta));
  if (!host) return el('div', { class: cls }, el('p', { class: 'hold-v' }, `Result pause: ${holdText(value)}`), el('p', { class: 'pnote' }, 'Only the host can change this.'));
  return el('div', { class: cls },
    el('div', { class: 'hold-row' },
      el('span', { class: 'hold-l' }, 'Result pause'),
      el('button', { class: 'ib', 'aria-label': 'Shorter pause', disabled: ctx.optBusy || value <= 0, onclick: () => step(-1) }, '−'),
      el('output', { class: 'hold-v', 'aria-live': 'polite' }, holdText(value)),
      el('button', { class: 'ib', 'aria-label': 'Longer pause', disabled: ctx.optBusy || value >= HOLD_MAX, onclick: () => step(1) }, '+')),
    el('p', { class: 'pnote' }, 'Applies from now on, also to a result on screen. 0 = no pause.'));
}

function settingsPane(ctx, d) {
  const o = ctx.game.options ?? {};
  const row = (k, v) => el('li', {}, el('span', {}, k), el('b', {}, v));
  return [holdControl(ctx, d),
    el('ul', { class: 'info' },
      row('Time per turn', o.turnTimeoutSeconds == null ? 'No limit' : `${o.turnTimeoutSeconds} s`),
      row('Clash race', `${o.clashCallSeconds ?? 5} s`),
      row('Choose a card with', o.chooseThreshold >= 2 ? `2 to ${o.chooseThreshold} cards` : 'never'),
      row('Round limit', String(o.maxRounds ?? '–')))];
}

function sheet(ctx, d) {
  if (!ctx.sheet) return '';
  const settings = ctx.sheet === 'settings';
  const log = ctx.sheet === 'log';
  const enter = ctx.sheetFresh;
  ctx.sheetFresh = false;
  const node = el('div', { class: `sheet${settings ? '' : ' docked'}${enter ? ' enter' : ''}`, onclick: (e) => { if (e.target === node) d.close(ctx); } },
    el('div', { class: 't-panel', role: 'dialog', 'aria-modal': 'true', 'aria-label': settings ? 'Game settings' : log ? 'Event log' : 'My pile' },
      el('div', { class: 'grab' }),
      el('div', { class: 'ph' }, el('h3', {}, settings ? 'Game settings' : log ? 'Event log' : `My pile · ${plural(pileCount(ctx), 'card')}`), el('button', { class: 'ib', 'aria-label': 'Close', onclick: () => d.close(ctx) }, '✕')),
      el('div', { class: 'pane' }, settings ? settingsPane(ctx, d) : log ? logPane(ctx, d) : pilePane(ctx))));
  return node;
}

function connBanner(ctx) {
  if (ctx.mySeat == null || ctx.game.status !== 'IN_PROGRESS') return '';
  if (ctx.conn === 'reconnecting') {
    const limit = ctx.game.options?.turnTimeoutSeconds;
    return el('div', { class: 'connbar', role: 'alert' }, el('span', { class: 'spin', 'aria-hidden': 'true' }),
      el('div', {}, 'Connection lost. Reconnecting…', el('small', {}, `Attempt ${Math.max(1, ctx.attempt ?? 1)}. Your cards are safe.${limit != null ? ` If you are away on your turn, the server plays for you after ${limit} s.` : ''}`)));
  }
  if (ctx.conn === 'closed') {
    return el('div', { class: 'connbar', role: 'alert' }, el('div', {}, 'Disconnected from the table.', el('small', {}, 'Leave and rejoin with the code to carry on.')));
  }
  return '';
}

/** What the table shows: the state, or a "waiting" text while the first view / the cards are still loading. */
function body(ctx, d, kind) {
  const v = ctx.view;
  if (kind === 'over') return stateOver(ctx, d);
  if (ctx.mySeat == null) return stateWaiting(ctx, d, { h1: 'This game is in progress', sub: 'You do not have a seat at this table.', back: true });
  if (kind === 'connecting') return stateWaiting(ctx, d, { h1: ctx.conn === 'reconnecting' ? 'Reconnecting…' : 'Joining the table…', sub: 'Waiting for the server.', spin: true });
  if (!ctx.deck) return stateWaiting(ctx, d, { h1: 'Loading your cards…', sub: 'One moment.', spin: true });
  switch (kind) {
    case 'call': return stateCall(ctx, d, v);
    case 'clash': return stateClash(ctx, d, v);
    case 'clashWatch': return stateClashWatch(ctx, d, v);
    case 'choose': return stateChoose(ctx, d, v);
    case 'result': return stateResult(ctx, d, v);
    case 'next': return stateNext(ctx);
    case 'out': return stateOut(ctx, d, v);
    default: return stateWait(ctx, d, v);
  }
}

export function buildTable(ctx, d) {
  const v = ctx.view;
  const kind = tableKind({ game: ctx.game, view: v, rt: ctx.rt, mySeat: ctx.mySeat, ui: { dismissedKey: ctx.dismissedKey, overHoldUntil: ctx.overHoldUntil }, nowMs: Date.now() });
  const seated = ctx.mySeat != null;
  const wide = typeof matchMedia === 'function' && matchMedia(WIDE).matches;
  if (ctx.sheet === 'log' || (wide && (ctx.tab ?? 'log') === 'log' && seated && v)) ctx.logSeen = ctx.rt.feedTotal;
  const offline = seated && ctx.game.status === 'IN_PROGRESS' && (ctx.conn === 'reconnecting' || ctx.conn === 'closed');
  return [el('div', { class: `tbl${ctx.rm ? ' rm' : ''}` },
    el('div', { class: `app${offline ? ' offline' : ''}`, 'data-kind': kind },
      hud(ctx, d, v, kind), rail(ctx, v, kind), body(ctx, d, kind),
      seated && v ? dock(ctx, d) : '', seated && v ? side(ctx, d) : ''),
    seated && v ? sheet(ctx, d) : '',
    connBanner(ctx))];
}

/** Keyboard: 1-6 call a stat or pick a card, Enter plays the picked card, Escape closes a sheet. Returns true when it used the key. */
export function handleKey(ctx, e, d) {
  if (e.ctrlKey || e.metaKey || e.altKey || /^(INPUT|TEXTAREA|SELECT)$/.test(e.target?.tagName ?? '')) return false;
  if (document.querySelector('.overlay')) return false;                         // the leave dialog is open
  if (e.key === 'Escape' && ctx.sheet) { d.close(ctx); return true; }
  if (ctx.sheet || !ctx.view || ctx.mySeat == null) return false;
  const kind = tableKind({ game: ctx.game, view: ctx.view, rt: ctx.rt, mySeat: ctx.mySeat, ui: { dismissedKey: ctx.dismissedKey, overHoldUntil: ctx.overHoldUntil } });
  const n = Number(e.key);
  const callerChoose = kind === 'choose' && !ctx.view.stat && ctx.view.callerSeat === ctx.mySeat;
  if ((kind === 'call' || kind === 'clash' || callerChoose) && n >= 1 && n <= (ctx.deck?.stats.length ?? 0) && !ctx.pending && !ctx.picked) {
    document.querySelectorAll('.tbl .stat')[n - 1]?.click();
    return true;
  }
  if (kind === 'choose' && ctx.view.ownHand?.length) {
    if (n >= 1 && n <= ctx.view.ownHand.length) { ctx.pick = ctx.view.ownHand[n - 1].id; ctx.paint(); return true; }
    if (e.key === 'Enter' && e.target?.tagName !== 'BUTTON') { document.querySelector('.tbl .cta')?.click(); return true; }
  }
  return false;
}

export { holdSeconds };
