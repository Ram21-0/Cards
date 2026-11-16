// Pure logic for deck requests, "My decks" and the admin page (no DOM, no fetch) so it can be unit-tested.
// Rules mirror the backend (docs/deck-requests/API.md); the server stays the judge, this only saves round trips.

export const LIMITS = { category: [3, 60], description: 500, names: [3, 60], nameLength: 60, deckName: [3, 40], deckDescription: 300, minCards: 8, maxStats: 8 };
export const RESERVED_COLUMNS = ['name', 'image', 'brand'];

const clean = (s) => String(s ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();

/** Textarea (one name per line) -> names: trimmed, whitespace collapsed, de-duplicated ignoring case (first spelling wins). */
export function parseNames(text) {
  const seen = new Set();
  const out = [];
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const n = clean(line);
    if (!n || seen.has(n.toLowerCase())) continue;
    seen.add(n.toLowerCase());
    out.push(n);
  }
  return out;
}

/** Problems with a category request, worded like the server's; [] when it should be accepted. */
export function requestProblems({ category, description, names }) {
  const out = [];
  const c = clean(category);
  if (c.length < LIMITS.category[0] || c.length > LIMITS.category[1]) out.push('Category must be 3 to 60 characters');
  if (String(description ?? '').trim().length > LIMITS.description) out.push('Description must be at most 500 characters');
  const list = Array.isArray(names) ? names : parseNames(names);
  if (list.length < LIMITS.names[0] || list.length > LIMITS.names[1]) out.push('Suggest 3 to 60 different names');
  if (list.some((n) => n.length > LIMITS.nameLength)) out.push('Each name must be at most 60 characters');
  return out;
}

/** Counts for the form hint: { names, pending, canSend }. A signed-in player may hold 3 pending requests. */
export function requestQuota(requests, maxPending = 3) {
  const pending = requests.filter((r) => r.status === 'PENDING').length;
  return { pending, maxPending, canSend: pending < maxPending };
}

/** How a request reads to its owner: { text, tone: wait|good|bad|info, detail }. */
export function statusView(r) {
  const promote = r.type === 'PROMOTE';
  switch (r.status) {
    case 'PENDING': return { text: 'Waiting for review', tone: 'wait', detail: promote ? 'An admin will look at your deck.' : 'An admin will look at your suggestion.' };
    case 'REJECTED': return { text: 'Rejected', tone: 'bad', detail: r.adminNote ?? '' };
    case 'APPROVED_PRIVATE':
    case 'APPROVED_GLOBAL': {
      const scope = r.status === 'APPROVED_GLOBAL' ? 'public' : 'private';
      if (promote) return { text: 'Approved — the deck is public', tone: 'good', detail: r.adminNote ?? '' };
      return r.live
        ? { text: `Live — ${scope} deck`, tone: 'good', detail: r.adminNote ?? '' }
        : { text: 'Approved, being prepared', tone: 'info', detail: r.adminNote ?? '' };
    }
    default: return { text: r.status ?? '', tone: 'info', detail: '' };
  }
}

export const requestKind = (r) => (r.type === 'PROMOTE' ? 'Make deck public' : 'New category');
export const canWithdraw = (r) => r.status === 'PENDING';

// ---- Decks of your own ----

/** What can be done with a deck from `GET /api/decks/mine`. */
export function deckActions(d) {
  const promo = d.promotion?.status ?? null;
  const priv = d.visibility === 'PRIVATE';
  const open = promo === 'PENDING';
  return {
    view: d.live,
    edit: priv && !open && d.live,                         // a public deck, or one under review, cannot be edited
    editBlock: !priv ? 'A public deck cannot be edited; copy it to a private deck.' : open ? 'Withdraw the public request first to edit.' : '',
    remove: priv && !open,
    copy: d.live,
    requestPublic: priv && d.live && d.cardCount >= LIMITS.minCards && promo !== 'PENDING',
    withdrawPublic: open,
  };
}

/** One-line state of a deck: { text, tone }. */
export function deckBadge(d) {
  if (!d.live) return { text: 'Being prepared', tone: 'info' };
  if (d.visibility === 'PUBLIC') return { text: 'Public', tone: 'good' };
  switch (d.promotion?.status) {
    case 'PENDING': return { text: 'Private · public request pending', tone: 'wait' };
    case 'REJECTED': return { text: 'Private · public request rejected', tone: 'bad' };
    default: return { text: 'Private', tone: 'info' };
  }
}

// ---- CSV import ----

/** The header cells of a CSV text (BOM, quotes and CRLF handled); [] when empty. Names are trimmed and lower-cased like the server. */
export function parseCsvHeader(text) {
  const src = String(text ?? '').replace(/^﻿/, '');
  const cells = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"' && src[i + 1] === '"') { cell += '"'; i++; } else if (ch === '"') quoted = false; else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { cells.push(cell); cell = ''; } else if (ch === '\n' || ch === '\r') break;
    else cell += ch;
  }
  cells.push(cell);
  const out = cells.map((c) => c.trim().toLowerCase());
  return out.length === 1 && out[0] === '' ? [] : out;
}

const titleCase = (c) => c.replace(/[_-]+/g, ' ').replace(/^./, (m) => m.toUpperCase());

/**
 * Editable stat rows for a CSV's columns: one per non-reserved column. A column the server's template knows keeps the template's
 * settings; others get a sensible default (higher wins, except `rank`).
 * -> [{ column, use, label, unit, higherWins, displayFormat }]
 */
export function defaultStatRows(columns, templateStats = []) {
  const known = new Map(templateStats.map((s) => [s.column, s]));
  return columns.filter((c) => c && !RESERVED_COLUMNS.includes(c)).map((column) => {
    const t = known.get(column);
    return {
      column,
      use: true,
      label: t?.label ?? titleCase(column),
      unit: t?.unit ?? '',
      higherWins: t ? Boolean(t.higherWins) : column !== 'rank',
      displayFormat: t?.displayFormat ?? 'PLAIN',
    };
  });
}

/** Problems with the stat rows (what the server would refuse); []. */
export function mappingProblems(rows) {
  const used = rows.filter((r) => r.use);
  const out = [];
  if (used.length < 1 || used.length > LIMITS.maxStats) out.push('Choose 1 to 8 stat columns');
  for (const r of used) {
    if (!clean(r.label)) out.push(`Column "${r.column}" needs a label`);
    if (r.displayFormat === 'WITH_UNIT' && !clean(r.unit)) out.push(`Column "${r.column}" shows a unit, so it needs one`);
  }
  return out;
}

/** The `mapping` JSON for the server from the stat rows. */
export function buildMapping(rows) {
  return {
    stats: rows.filter((r) => r.use).map((r) => ({
      column: r.column,
      label: clean(r.label),
      unit: r.displayFormat === 'WITH_UNIT' ? clean(r.unit) : (clean(r.unit) || null),
      higherWins: Boolean(r.higherWins),
      displayFormat: r.displayFormat,
    })),
  };
}

/** Everything needed before the dry run: [] when the form can be checked. */
export function importInputProblems({ csv, rows, name, nameRequired }) {
  const out = [];
  if (!csv) out.push('Choose a CSV file');
  else if (!rows.length) out.push('The CSV needs a stat column besides name, image and brand');
  out.push(...(csv ? mappingProblems(rows) : []));
  const nameProblem = deckNameProblem(name, nameRequired);
  if (nameProblem) out.push(nameProblem);
  return out;
}

/** The deck name must be 3 to 40 characters when it is required, or when something was typed. */
export function deckNameProblem(name, required) {
  const n = clean(name);
  return (required || n) && (n.length < LIMITS.deckName[0] || n.length > LIMITS.deckName[1]) ? 'Deck name must be 3 to 40 characters' : '';
}

/** A dry-run answer -> what to show: { ok, headline, errors, more, cards, stats }. */
export function previewView(res) {
  const errors = res.errors ?? [];
  const more = Math.max(0, (res.errorCount ?? errors.length) - errors.length);
  const ok = Boolean(res.valid);
  return {
    ok,
    headline: ok ? `Looks good: ${res.cards.length} cards` : `${res.errorCount ?? errors.length} problem${(res.errorCount ?? errors.length) === 1 ? '' : 's'} to fix`,
    errors,
    more,
    cards: res.cards ?? [],
    stats: res.stats ?? [],
  };
}

/** A key that changes whenever the inputs of a dry run change, so a stale "valid" cannot be saved. */
export function inputKey({ csv, images, mappingJson }) {
  const f = (x) => `${x.name}:${x.size}:${x.lastModified ?? 0}`;
  return JSON.stringify([csv ? f(csv) : null, (images ?? []).map(f), mappingJson]);
}

/** Template CSV text -> a download file { name, text }. */
export const templateFile = (tpl) => ({ name: 'deck-template.csv', text: tpl.csv });

// ---- Admin ----

/** What an admin can do with a request right now. */
export function adminActions(r) {
  const promote = r.type === 'PROMOTE';
  const pending = r.status === 'PENDING';
  const approved = r.status === 'APPROVED_PRIVATE' || r.status === 'APPROVED_GLOBAL';
  const preparing = approved && !r.live && !promote;
  return {
    edit: pending && !promote,
    decide: pending,
    visibilityChoice: pending && !promote,                  // a PROMOTE request can only become PUBLIC
    importStats: preparing,
    publish: preparing && (r.cardCount ?? 0) >= LIMITS.minCards,
    publishBlock: preparing && (r.cardCount ?? 0) < LIMITS.minCards ? `Import at least ${LIMITS.minCards} cards first (now ${r.cardCount ?? 0}).` : '',
    reviewCards: (r.cardCount ?? 0) > 0 || promote,
  };
}

/** Admin list filters, in the order shown. */
export const ADMIN_FILTERS = [
  ['PENDING', 'Pending'], ['APPROVED_PRIVATE', 'Approved (private)'], ['APPROVED_GLOBAL', 'Approved (public)'], ['REJECTED', 'Rejected'], ['ALL', 'All'],
];

/** An error from the API (or a network failure) -> the text to show. Admin screens treat 401/403 specially. */
export function errorText(e, { admin = false } = {}) {
  const status = e?.status;
  if (status === 401) return admin ? 'Sign in to continue.' : 'Your session ended. Sign in again.';
  if (status === 403) return admin ? 'This page is for admins only.' : (e.message || 'Not allowed');
  if (status === 413) return 'That upload is too large.';
  if (status == null) return 'Could not reach the server.';
  return e.message || `Request failed (${status})`;
}

/** Cards of an admin review (API Card[]) -> table: { keys, rows: [{ name, brand, img, cells }] } with the stat order of the first card. */
export function reviewTable(cards) {
  const keys = [];
  for (const c of cards) for (const k of Object.keys(c.stats ?? {})) if (!keys.includes(k)) keys.push(k);
  return {
    keys,
    rows: cards.map((c) => ({ name: c.name, brand: c.meta?.brand ?? '', img: c.img ?? null, cells: keys.map((k) => c.stats?.[k]?.displayValue ?? '–') })),
  };
}
