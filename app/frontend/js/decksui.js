// DOM for deck requests, "My decks" and the admin page. The rules live in decks.js (tested); the calls in api.js.
// app.js hands in what only it owns (the screen switcher, navigation, the signed-in player): see `createDecksUi(deps)`.
import { el } from './dom.js';
import { paths } from './nav.js';
import { imageUrl } from './api.js';
import * as api from './api.js';
import {
  LIMITS, parseNames, requestProblems, requestQuota, statusView, requestKind, canWithdraw, deckActions, deckBadge, parseCsvHeader, defaultStatRows,
  buildMapping, importInputProblems, deckNameProblem, previewView, inputKey, adminActions, ADMIN_FILTERS, errorText, reviewTable,
} from './decks.js';

const PLACEHOLDER = 'assets/placeholder.png';
const fmtDate = (iso) => (iso ? new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '');
const badge = (v) => el('span', { class: `dk-badge ${v.tone}` }, v.text);

/** Runs `fn` while `btn` is disabled, so a double tap cannot send twice. */
async function busy(btn, fn) {
  btn.disabled = true;
  try { return await fn(); } finally { btn.disabled = false; }
}

function field(label, input, hint) {
  return el('label', { class: 'dk-field' }, el('span', { class: 'dk-label' }, label), input, hint ? el('span', { class: 'dk-hint' }, hint) : '');
}

function thumb(src) {
  const img = el('img', { class: 'dk-thumb', src: src || PLACEHOLDER, alt: '', loading: 'lazy' });
  img.addEventListener('error', () => { img.src = PLACEHOLDER; }, { once: true });
  return img;
}

function downloadText(name, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }));
  const a = el('a', { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function createDecksUi(deps) {
  const { show, screen, nav, goUp, toast, getMe, signIn, chooseUsername } = deps;
  const topbar = (title, parent, label) => el('div', { class: 'topbar' },
    el('button', { class: 'btn small', onclick: () => goUp(parent) }, label), el('h2', {}, title), el('span'));
  const wrap = (...kids) => screen('dk', ...kids);

  /** Signed in with a username? Otherwise send the player through sign-in first and come back. */
  function needAccount(path) {
    const me = getMe();
    if (!me) { signIn(path, { replace: true }); return false; }
    if (me.needsUsername) { chooseUsername(path, { replace: true }); return false; }
    return true;
  }

  /** A failed call: signed-out goes to sign-in, everything else is a toast. */
  function fail(e, path) {
    if (e?.status === 401) { signIn(path); return; }
    toast(errorText(e));
  }

  const message = (title, text, parent, label) => show(wrap(topbar(title, parent, label), el('p', { class: 'dk-empty' }, text)));

  // ---------------------------------------------------------------- requests
  async function requestsScreen() {
    if (!needAccount(paths.requests())) return;
    show(wrap(topbar('Request a deck', paths.menu(), '← Menu'), el('p', { class: 'dk-empty' }, 'Loading…')));
    let list;
    try { list = await api.fetchMyRequests(); } catch (e) { return fail(e, paths.requests()); }
    const quota = requestQuota(list);

    const category = el('input', { class: 'field', maxlength: '60', placeholder: 'e.g. Cricket legends', autocomplete: 'off' });
    const description = el('textarea', { class: 'field', rows: '2', maxlength: '500', placeholder: 'Optional: what is this deck about?' });
    const names = el('textarea', { class: 'field', rows: '7', placeholder: 'One name per line' });
    const count = el('span', { class: 'dk-hint' });
    const problems = el('ul', { class: 'dk-problems' });
    const send = el('button', { class: 'btn primary', type: 'submit' }, 'Send request');

    const read = () => ({ category: category.value, description: description.value, names: parseNames(names.value) });
    const refresh = () => {
      const r = read();
      const probs = requestProblems(r);
      count.textContent = `${r.names.length} different name${r.names.length === 1 ? '' : 's'} (3 to 60)`;
      problems.replaceChildren(...(category.value || names.value ? probs.map((p) => el('li', {}, p)) : []));
      send.disabled = probs.length > 0 || !quota.canSend;
    };
    for (const i of [category, description, names]) i.addEventListener('input', refresh);
    refresh();

    const form = el('form', { class: 'panel dk-form', novalidate: true, onsubmit: async (e) => {
      e.preventDefault();
      const r = read();
      if (requestProblems(r).length) return;
      await busy(send, async () => {
        try {
          await api.createDeckRequest({ ...r, description: r.description.trim() || undefined });
          toast('Request sent');
          requestsScreen();
        } catch (err) { fail(err, paths.requests()); }
      });
    } },
      el('h3', {}, 'Suggest a new deck'),
      el('p', { class: 'dk-hint' }, 'Tell us the category and the names that should be cards. An admin reviews it and builds the deck.'),
      field('Category', category),
      field('Description', description),
      field('Names', names), count, problems,
      quota.canSend ? '' : el('div', { class: 'notice' }, `You already have ${quota.maxPending} pending requests; wait for a decision (or withdraw one).`),
      el('div', { class: 'btn-row' }, send));

    show(wrap(
      topbar('Request a deck', paths.menu(), '← Menu'),
      form,
      el('h3', { class: 'section' }, `Your requests · ${list.length}`),
      list.length ? el('div', { class: 'dk-list' }, list.map(requestCard)) : el('p', { class: 'dk-empty' }, 'Nothing yet. Requests for a category, and for making one of your decks public, show up here.')));
  }

  function requestCard(r) {
    const s = statusView(r);
    return el('article', { class: 'panel dk-item' },
      el('div', { class: 'dk-head' }, el('h3', {}, r.category), badge(s)),
      el('p', { class: 'dk-hint' }, `${requestKind(r)} · sent ${fmtDate(r.createdAt)}${r.decidedAt ? ` · decided ${fmtDate(r.decidedAt)}` : ''}`),
      s.detail ? el('p', { class: `dk-note ${s.tone}` }, r.status === 'REJECTED' ? `Admin note: ${s.detail}` : s.detail) : '',
      r.description ? el('p', {}, r.description) : '',
      el('details', {}, el('summary', {}, `${r.names.length} names`), el('p', { class: 'dk-names' }, r.names.join(' · '))),
      el('div', { class: 'btn-row' },
        r.live && r.deckId ? el('button', { class: 'btn small', onclick: () => nav(paths.deck(r.deckId)) }, 'Open deck') : '',
        canWithdraw(r) ? el('button', { class: 'btn small danger', onclick: (e) => withdraw(r, e.currentTarget) }, 'Withdraw') : ''));
  }

  async function withdraw(r, btn, after = requestsScreen) {
    if (!confirm(`Withdraw "${r.category}"?`)) return;
    await busy(btn, async () => {
      try { await api.withdrawRequest(r.id); toast('Request withdrawn'); after(); } catch (e) { fail(e, paths.requests()); if (e.status === 409) after(); }
    });
  }

  // ---------------------------------------------------------------- my decks
  async function myDecksScreen() {
    if (!needAccount(paths.myDecks())) return;
    show(wrap(topbar('My decks', paths.menu(), '← Menu'), el('p', { class: 'dk-empty' }, 'Loading…')));
    let list;
    try { list = await api.fetchMyDecks(); } catch (e) { return fail(e, paths.myDecks()); }
    show(wrap(
      topbar('My decks', paths.menu(), '← Menu'),
      el('div', { class: 'btn-row dk-left' },
        el('button', { class: 'btn primary', onclick: () => nav(paths.newDeck()) }, 'New deck from CSV'),
        el('button', { class: 'btn', onclick: () => nav(paths.requests()) }, 'Requests')),
      list.length ? el('div', { class: 'dk-list' }, list.map(deckCard))
        : el('p', { class: 'dk-empty' }, 'You have no decks yet. Import a CSV (8 to 100 cards), or ask for a category.')));
  }

  function deckCard(d) {
    const a = deckActions(d);
    const promo = d.promotion;
    const run = (fn, ok) => async (e) => busy(e.currentTarget, async () => {
      try { await fn(); toast(ok); await deps.refreshDecks(); myDecksScreen(); } catch (err) { fail(err, paths.myDecks()); if (err.status === 409) myDecksScreen(); }
    });
    return el('article', { class: 'panel dk-item' },
      el('div', { class: 'dk-head' }, el('h3', {}, d.name), badge(deckBadge(d))),
      d.description ? el('p', {}, d.description) : '',
      el('div', { class: 'chips' }, el('span', { class: 'chip strong' }, `${d.cardCount} cards`), d.stats.map((s) => el('span', { class: 'chip' }, s.label))),
      promo?.status === 'REJECTED' && promo.adminNote ? el('p', { class: 'dk-note bad' }, `Admin note: ${promo.adminNote}`) : '',
      !a.edit && a.editBlock && d.live ? el('p', { class: 'dk-hint' }, a.editBlock) : '',
      el('div', { class: 'btn-row' },
        a.view ? el('button', { class: 'btn small', onclick: () => nav(paths.deck(d.id)) }, 'View cards') : '',
        a.edit ? el('button', { class: 'btn small', onclick: () => nav(paths.editDeck(d.id)) }, 'Edit') : '',
        a.copy ? el('button', { class: 'btn small', onclick: async (e) => {
          const name = prompt('Name for your private copy (3 to 40 characters)', `${d.name} copy`.slice(0, 40));
          if (name === null) return;
          await run(() => api.copyDeck(d.id, name.trim()), 'Copied to a private deck')(e);
        } }, 'Copy') : '',
        a.requestPublic ? el('button', { class: 'btn small', onclick: async (e) => {
          if (!confirm(`Ask the admins to make "${d.name}" public? You cannot edit it while the request is open.`)) return;
          await run(() => api.requestGlobal(d.id), 'Request sent')(e);
        } }, 'Request to make public') : '',
        a.withdrawPublic ? el('button', { class: 'btn small', onclick: (e) => run(() => api.withdrawRequest(promo.requestId), 'Request withdrawn')(e) }, 'Withdraw public request') : '',
        a.remove ? el('button', { class: 'btn small danger', onclick: async (e) => {
          if (!confirm(`Delete "${d.name}" and its images? This cannot be undone.`)) return;
          await run(() => api.deleteDeck(d.id), 'Deck deleted')(e);
        } }, 'Delete') : ''));
  }

  // ---------------------------------------------------------------- create / edit a deck (CSV import with a dry run)
  async function deckFormScreen(deckId = null) {
    const path = deckId ? paths.editDeck(deckId) : paths.newDeck();
    if (!needAccount(path)) return;
    let existing = null;
    if (deckId) {
      show(wrap(topbar('Edit deck', paths.myDecks(), '← My decks'), el('p', { class: 'dk-empty' }, 'Loading…')));
      try { existing = (await api.fetchMyDecks()).find((d) => d.id === deckId); } catch (e) { return fail(e, path); }
      if (!existing) return message('Edit deck', 'That deck was not found.', paths.myDecks(), '← My decks');
      if (!deckActions(existing).edit) return message('Edit deck', deckActions(existing).editBlock || 'This deck cannot be edited now.', paths.myDecks(), '← My decks');
    }

    const st = { csv: null, columns: [], rows: [], images: [], template: null, ok: false, key: null };
    const name = el('input', { class: 'field', maxlength: '40', value: existing?.name ?? '', placeholder: '3 to 40 characters', autocomplete: 'off' });
    const description = el('textarea', { class: 'field', rows: '2', maxlength: String(LIMITS.deckDescription), placeholder: 'Optional' });
    description.value = existing?.description ?? '';
    const csvInput = el('input', { type: 'file', accept: '.csv,text/csv', class: 'dk-file' });
    const imgInput = el('input', { type: 'file', multiple: true, accept: '.zip,application/zip,image/png,image/jpeg,image/gif', class: 'dk-file' });
    const mappingBox = el('div', { class: 'dk-mapping' });
    const problems = el('ul', { class: 'dk-problems' });
    const result = el('div', { class: 'dk-result' });
    const check = el('button', { class: 'btn', type: 'button' }, 'Check the file');
    const save = el('button', { class: 'btn primary', type: 'button' }, existing ? 'Save changes' : 'Save deck');

    const mappingJson = () => JSON.stringify(buildMapping(st.rows));
    const currentKey = () => inputKey({ csv: st.csv, images: st.images, mappingJson: mappingJson() });
    const inputProblems = () => importInputProblems({ csv: st.csv, rows: st.rows, name: name.value, nameRequired: !existing });
    const detailsOnly = () => Boolean(existing) && !st.csv;                 // edit without a new file: rename only

    const update = () => {
      const probs = st.csv ? inputProblems() : [deckNameProblem(name.value, Boolean(existing))].filter(Boolean);
      problems.replaceChildren(...(st.csv || name.value ? probs.map((p) => el('li', {}, p)) : []));
      check.disabled = !st.csv || probs.length > 0;
      if (st.ok && st.key !== currentKey()) st.ok = false;            // safety net: a stale "valid" can never be saved
      save.disabled = detailsOnly() ? probs.length > 0 : !(st.csv && st.ok && probs.length === 0);
    };
    const touch = () => {
      if (st.ok) result.replaceChildren(el('p', { class: 'dk-hint' }, 'You changed something. Check the file again before saving.'));
      st.ok = false;
      update();
    };

    function paintMapping() {
      if (!st.rows.length) { mappingBox.replaceChildren(st.csv ? el('p', { class: 'dk-note bad' }, 'No stat columns found. Besides name, image and brand the CSV needs at least one numeric column.') : ''); return; }
      mappingBox.replaceChildren(
        el('h3', { class: 'section' }, 'Stat columns'),
        el('p', { class: 'dk-hint' }, 'Every column except name, image and brand can be a stat. Untick the ones to ignore. "Higher wins" decides which card beats which when this stat is called.'),
        el('div', { class: 'dk-statrows' }, st.rows.map((r) => {
          const unit = el('input', { class: 'field', value: r.unit, placeholder: 'unit (lbs, in…)', maxlength: '12', 'aria-label': `Unit for ${r.column}`, oninput: (e) => { r.unit = e.target.value; touch(); } });
          const sync = () => { unit.hidden = r.displayFormat !== 'WITH_UNIT'; };
          const fmt = el('select', { class: 'field', 'aria-label': `Display for ${r.column}`, onchange: (e) => { r.displayFormat = e.target.value; sync(); touch(); } },
            [['PLAIN', 'Plain'], ['WITH_UNIT', 'With unit'], ['FEET_INCHES', 'Feet and inches (whole inches)']].map(([v, t]) => el('option', { value: v, selected: v === r.displayFormat }, t)));
          sync();
          return el('div', { class: 'dk-statrow' },
            el('label', { class: 'dk-check' }, el('input', { type: 'checkbox', checked: r.use, onchange: (e) => { r.use = e.target.checked; touch(); } }), el('code', {}, r.column)),
            el('input', { class: 'field', value: r.label, maxlength: '20', 'aria-label': `Label for ${r.column}`, oninput: (e) => { r.label = e.target.value; touch(); } }),
            el('label', { class: 'dk-check' }, el('input', { type: 'checkbox', checked: r.higherWins, onchange: (e) => { r.higherWins = e.target.checked; touch(); } }), 'Higher wins'),
            fmt, unit);
        })));
    }

    csvInput.addEventListener('change', async () => {
      st.csv = csvInput.files[0] ?? null;
      st.ok = false;
      st.columns = [];
      st.rows = [];
      result.replaceChildren();
      if (st.csv) {
        try {
          if (!st.template) st.template = await api.fetchImportTemplate().catch(() => null);
          st.columns = parseCsvHeader(await st.csv.slice(0, 4096).text());
        } catch { st.columns = []; }
        st.rows = defaultStatRows(st.columns, st.template?.mapping?.stats ?? []);
        if (!st.columns.includes('name')) { result.replaceChildren(el('p', { class: 'dk-note bad' }, 'The first line must be a header with a "name" column.')); }
      }
      paintMapping();
      touch();
    });
    imgInput.addEventListener('change', () => { st.images = [...imgInput.files]; touch(); });
    name.addEventListener('input', update);

    async function doCheck() {
      await busy(check, async () => {
        result.replaceChildren(el('p', { class: 'dk-hint' }, 'Checking…'));
        try {
          const res = await api.previewDeck({ csv: st.csv, mapping: buildMapping(st.rows), images: st.images, name: name.value.trim() || undefined });
          const v = previewView(res);
          st.ok = v.ok;
          st.key = currentKey();
          result.replaceChildren(previewBlock(v));
        } catch (e) {
          st.ok = false;
          result.replaceChildren(el('p', { class: 'dk-note bad' }, errorText(e)));
          if (e.status === 401) signIn(path);
        }
        update();
      });
    }

    async function doSave() {
      await busy(save, async () => {
        const input = { csv: st.csv, mapping: buildMapping(st.rows), images: st.images, name: name.value.trim(), description: description.value.trim() };
        try {
          if (detailsOnly()) await api.patchDeck(deckId, { name: input.name, description: input.description });
          else if (existing) await api.replaceDeck(deckId, input);
          else await api.importDeck(input);
          deps.invalidateDeck(deckId);
          await deps.refreshDecks();
          toast(existing ? 'Deck saved' : 'Deck created');
          goUp(paths.myDecks());
        } catch (e) {
          if (e.status === 401) return signIn(path);
          result.replaceChildren(el('p', { class: 'dk-note bad' }, errorText(e)));
          st.ok = false;
          update();
        }
      });
    }
    check.addEventListener('click', doCheck);
    save.addEventListener('click', doSave);

    const tplBtn = el('button', { class: 'btn small', type: 'button', onclick: async (e) => {
      await busy(e.currentTarget, async () => {
        try { st.template = st.template ?? await api.fetchImportTemplate(); downloadText('deck-template.csv', st.template.csv); } catch (err) { fail(err, path); }
      });
    } }, 'Download a template CSV');

    show(wrap(
      topbar(existing ? 'Edit deck' : 'New deck', paths.myDecks(), '← My decks'),
      el('section', { class: 'panel dk-form' },
        field('Name', name), field('Description', description),
        el('h3', { class: 'section' }, existing ? 'Replace the cards (optional)' : 'Cards'),
        el('p', { class: 'dk-hint' }, `A CSV with a header row and ${LIMITS.minCards} to 100 cards. It needs a "name" column; "image" (an uploaded file name or an https link) and "brand" are optional; every other column is a stat.${existing ? ' Choose a file only to replace all stats and cards.' : ''}`),
        el('div', { class: 'btn-row dk-left' }, tplBtn),
        field('CSV file', csvInput),
        mappingBox,
        field('Images (optional)', imgInput, 'One ZIP, and/or PNG / JPEG / GIF files (2 MB each). A CSV "image" cell names a file.'),
        problems,
        el('div', { class: 'btn-row dk-left' }, check, save)),
      result));
    paintMapping();
    update();
  }

  function previewBlock(v) {
    const parts = [el('p', { class: `dk-note ${v.ok ? 'good' : 'bad'}` }, v.headline)];
    if (v.errors.length) {
      parts.push(el('ul', { class: 'dk-errors' }, v.errors.map((m) => el('li', {}, m))));
      if (v.more) parts.push(el('p', { class: 'dk-hint' }, `…and ${v.more} more.`));
    }
    if (v.cards.length) {
      parts.push(el('div', { class: 'dk-table-wrap' }, el('table', { class: 'dk-table' },
        el('thead', {}, el('tr', {}, el('th', {}, 'Name'), el('th', {}, 'Brand'), el('th', {}, 'Image'), v.stats.map((s) => el('th', {}, s.label)))),
        el('tbody', {}, v.cards.map((c) => el('tr', {}, el('td', {}, c.name), el('td', {}, c.brand ?? ''), el('td', {}, c.image ?? ''), v.stats.map((s) => el('td', {}, String(c.stats?.[s.key] ?? '–')))))))));
    }
    return el('div', { class: 'panel' }, ...parts);
  }

  // ---------------------------------------------------------------- admin
  const adminAllowed = (path) => {
    if (!needAccount(path)) return false;
    if (!getMe().isAdmin) {
      show(wrap(topbar('Admin', paths.menu(), '← Menu'), el('p', { class: 'dk-empty' }, 'This page is for admins only.')));
      return false;
    }
    return true;
  };

  /** 401 -> sign in, 403 -> "admins only"; anything else a message on the screen. Returns true when handled. */
  function adminFail(e, path, title) {
    if (e?.status === 401) { signIn(path); return true; }
    show(wrap(topbar(title, paths.menu(), '← Menu'), el('p', { class: 'dk-empty' }, e?.status === 404 ? 'That request was not found.' : errorText(e, { admin: true }))));
    return true;
  }

  async function adminListScreen(status = 'PENDING') {
    if (!adminAllowed(paths.adminRequests())) return;
    let list;
    try { list = await api.adminListRequests(status); } catch (e) { return adminFail(e, paths.adminRequests(), 'Admin'); }
    const filter = el('select', { class: 'field', 'aria-label': 'Status', onchange: (e) => adminListScreen(e.target.value) },
      ADMIN_FILTERS.map(([v, t]) => el('option', { value: v, selected: v === status }, t)));
    show(wrap(
      topbar('Deck requests', paths.menu(), '← Menu'),
      el('div', { class: 'dk-filter' }, filter),
      list.length ? el('div', { class: 'dk-list' }, list.map((r) => el('button', { class: 'panel dk-item dk-link', onclick: () => nav(paths.adminRequest(r.id)) },
        el('div', { class: 'dk-head' }, el('h3', {}, r.category), badge(statusView(r))),
        el('p', { class: 'dk-hint' }, `${requestKind(r)} · @${r.requester?.username ?? '?'} · ${fmtDate(r.createdAt)} · ${r.names.length} names`))))
        : el('p', { class: 'dk-empty' }, 'No requests here.')));
  }

  async function adminRequestScreen(id) {
    const path = paths.adminRequest(id);
    if (!adminAllowed(path)) return;
    show(wrap(topbar('Deck request', paths.adminRequests(), '← Requests'), el('p', { class: 'dk-empty' }, 'Loading…')));
    let r;
    try { r = await api.adminGetRequest(id); } catch (e) { return adminFail(e, path, 'Deck request'); }
    const act = adminActions(r);
    const again = () => adminRequestScreen(id);
    const guard = (btn, fn, ok) => busy(btn, async () => {
      try { await fn(); if (ok) toast(ok); again(); } catch (e) { if (e.status === 401 || e.status === 403) adminFail(e, path, 'Deck request'); else { toast(errorText(e)); if (e.status === 409) again(); } }
    });

    const cardsBox = el('div', { class: 'dk-cards' });
    const sections = [
      el('section', { class: 'panel' },
        el('div', { class: 'dk-head' }, el('h3', {}, r.category), badge(statusView(r))),
        el('p', { class: 'dk-hint' }, `${requestKind(r)} · from @${r.requester?.username ?? '?'} · sent ${fmtDate(r.createdAt)}`),
        r.decidedAt ? el('p', { class: 'dk-hint' }, `Decided ${fmtDate(r.decidedAt)}${r.decidedBy?.username ? ` by @${r.decidedBy.username}` : ''}${r.notifiedAt ? '' : ' · no notification mail went out'}`) : '',
        r.adminNote ? el('p', { class: 'dk-note info' }, `Note: ${r.adminNote}`) : '',
        r.description && !act.edit ? el('p', {}, r.description) : '',
        r.live ? el('p', { class: 'dk-note good' }, `Live since ${fmtDate(r.publishedAt)}`) : '',
        !act.edit ? el('details', { open: true }, el('summary', {}, `${r.names.length} names`), el('p', { class: 'dk-names' }, r.names.join(' · '))) : ''),
    ];

    if (act.edit) sections.push(editSection(r, guard));
    if (act.decide) sections.push(decideSection(r, act, guard));
    if (act.importStats) sections.push(statsSection(r, guard));
    if (act.importStats) {
      sections.push(el('section', { class: 'panel' }, el('h3', {}, 'Publish'),
        act.publishBlock ? el('p', { class: 'dk-hint' }, act.publishBlock) : el('p', { class: 'dk-hint' }, 'Makes the deck live with the visibility chosen at approval.'),
        el('div', { class: 'btn-row dk-left' }, el('button', { class: 'btn primary', disabled: !act.publish, onclick: (e) => {
          if (confirm(`Publish "${r.category}" with ${r.cardCount} cards?`)) guard(e.currentTarget, () => api.adminPublish(id), 'Published');
        } }, 'Publish'))));
    }
    if (act.reviewCards) sections.push(el('section', { class: 'panel' }, el('h3', {}, `Cards · ${r.cardCount ?? 0}`), cardsBox));

    show(wrap(topbar('Deck request', paths.adminRequests(), '← Requests'), ...sections));
    if (act.reviewCards) loadCards(id, cardsBox, path);
  }

  async function loadCards(id, box, path) {
    box.replaceChildren(el('p', { class: 'dk-hint' }, 'Loading cards…'));
    try {
      const t = reviewTable(await api.adminCards(id));
      if (!t.rows.length) return box.replaceChildren(el('p', { class: 'dk-hint' }, 'No cards yet.'));
      box.replaceChildren(el('div', { class: 'dk-table-wrap' }, el('table', { class: 'dk-table' },
        el('thead', {}, el('tr', {}, el('th', {}, ''), el('th', {}, 'Name'), el('th', {}, 'Brand'), t.keys.map((k) => el('th', {}, k)))),
        el('tbody', {}, t.rows.map((c) => el('tr', {}, el('td', {}, thumb(imageUrl(c.img))), el('td', {}, c.name), el('td', {}, c.brand), c.cells.map((x) => el('td', {}, x))))))));
    } catch (e) {
      if (e.status === 401 || e.status === 403) return adminFail(e, path, 'Deck request');
      box.replaceChildren(el('p', { class: 'dk-note bad' }, errorText(e)));
    }
  }

  function editSection(r, guard) {
    const category = el('input', { class: 'field', value: r.category, maxlength: '60' });
    const description = el('textarea', { class: 'field', rows: '2', maxlength: '500' });
    description.value = r.description ?? '';
    const names = el('textarea', { class: 'field', rows: '8' });
    names.value = r.names.join('\n');
    const problems = el('ul', { class: 'dk-problems' });
    const saveBtn = el('button', { class: 'btn', type: 'button' }, 'Save changes');
    const read = () => ({ category: category.value, description: description.value.trim(), names: parseNames(names.value) });
    const refresh = () => { const p = requestProblems(read()); problems.replaceChildren(...p.map((m) => el('li', {}, m))); saveBtn.disabled = p.length > 0; };
    for (const i of [category, description, names]) i.addEventListener('input', refresh);
    saveBtn.addEventListener('click', () => guard(saveBtn, () => api.adminEditRequest(r.id, read()), 'Changes saved'));
    refresh();
    return el('section', { class: 'panel dk-form' }, el('h3', {}, 'Review and edit'),
      el('p', { class: 'dk-hint' }, 'Fix names or wording before deciding. Only a pending request can be edited.'),
      field('Category', category), field('Description', description), field('Names (one per line)', names), problems, el('div', { class: 'btn-row dk-left' }, saveBtn));
  }

  function decideSection(r, act, guard) {
    const note = el('textarea', { class: 'field', rows: '2', maxlength: '500', placeholder: 'Note for the requester (required to reject)' });
    const vis = el('select', { class: 'field', 'aria-label': 'Visibility' },
      el('option', { value: 'PUBLIC' }, 'Public: everybody can play it'), el('option', { value: 'PRIVATE' }, 'Private: only the requester'));
    const approve = el('button', { class: 'btn primary', type: 'button', onclick: () => guard(approve, () => api.adminApprove(r.id, { visibility: act.visibilityChoice ? vis.value : 'PUBLIC', note: note.value.trim() }), 'Approved') }, 'Approve');
    const reject = el('button', { class: 'btn danger', type: 'button', onclick: () => {
      if (!note.value.trim()) { toast('Write a note so the requester knows why'); note.focus(); return; }
      if (confirm('Reject this request?')) guard(reject, () => api.adminReject(r.id, note.value.trim()), 'Rejected');
    } }, 'Reject');
    return el('section', { class: 'panel dk-form' }, el('h3', {}, 'Decision'),
      r.type === 'PROMOTE' ? el('p', { class: 'dk-hint' }, 'Approving makes the deck public at once; the owner keeps it. Rejecting leaves it private and shows the note to the owner.')
        : el('p', { class: 'dk-hint' }, 'Approving creates a deck that is not live yet: import its stats, then publish.'),
      act.visibilityChoice ? field('Visibility', vis) : '',
      field('Note', note), el('div', { class: 'btn-row dk-left' }, approve, reject));
  }

  function statsSection(r, guard) {
    const file = el('input', { type: 'file', accept: '.csv,text/csv', class: 'dk-file' });
    const errors = el('pre', { class: 'dk-errors-pre', hidden: true });
    const upload = el('button', { class: 'btn', type: 'button', onclick: async () => {
      const f = file.files[0];
      if (!f) { toast('Choose a CSV file'); return; }
      errors.hidden = true;
      await busy(upload, async () => {
        try { await api.adminPutStats(r.id, await f.text()); toast('Cards imported'); adminRequestScreen(r.id); }
        catch (e) {
          if (e.status === 401 || e.status === 403) return adminFail(e, paths.adminRequest(r.id), 'Deck request');
          errors.textContent = errorText(e); errors.hidden = false;
        }
      });
    } }, r.cardCount ? 'Replace the cards' : 'Import the cards');
    return el('section', { class: 'panel dk-form' }, el('h3', {}, 'Stats'),
      el('p', { class: 'dk-hint' }, 'CSV header: name,rank,fight,chest,biceps,height,weight (optional: brand, image). At least 8 cards, up to 500. Height is total inches.'),
      field('CSV file', file), errors, el('div', { class: 'btn-row dk-left' }, upload));
  }

  return { requestsScreen, myDecksScreen, newDeckScreen: () => deckFormScreen(null), editDeckScreen: (id) => deckFormScreen(id), adminListScreen: () => adminListScreen('PENDING'), adminRequestScreen };
}
