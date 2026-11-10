// Pure routing + Back-button rules (no DOM, no history API) so they can be unit-tested.
//
// Routes live in the URL hash so the browser's Back/Forward buttons (and the phone's Back gesture) move between screens:
//   #/            menu                    #/decks               gallery (list of decks)
//   #/deck/<id>   one deck                #/deck/<id>/card/<n>  one card of a deck
//   #/lobby/<code> game lobby             #/play                a solo game in progress
//   #/signin  #/username  #/account       sign-in flow and account

/** Hash or path -> { name, params, path }. Unknown paths fall back to the menu. */
export function parseRoute(hash) {
  let p = String(hash ?? '').replace(/^#/, '').split('?')[0];
  if (!p.startsWith('/')) p = `/${p}`;
  const seg = p.split('/').filter(Boolean).map((s) => { try { return decodeURIComponent(s); } catch { return s; } });
  const menu = { name: 'menu', params: {}, path: '/' };

  switch (seg[0]) {
    case undefined: return menu;
    case 'decks': return seg.length === 1 ? { name: 'decks', params: {}, path: '/decks' } : menu;
    case 'deck': {
      const id = seg[1];
      if (!id) return menu;
      if (seg.length === 2) return { name: 'deck', params: { id }, path: `/deck/${encodeURIComponent(id)}` };
      if (seg.length === 4 && seg[2] === 'card' && /^\d+$/.test(seg[3])) {
        const index = Number(seg[3]);
        return { name: 'card', params: { id, index }, path: `/deck/${encodeURIComponent(id)}/card/${index}` };
      }
      return menu;
    }
    case 'lobby': {
      if (!seg[1] || seg.length !== 2) return menu;
      const code = seg[1].toUpperCase();
      return { name: 'lobby', params: { code }, path: `/lobby/${encodeURIComponent(code)}` };
    }
    case 'play': case 'signin': case 'username': case 'account':
      return seg.length === 1 ? { name: seg[0], params: {}, path: `/${seg[0]}` } : menu;
    default: return menu;
  }
}

export const toHash = (path) => `#${path}`;

/** Paths for the routes (single place that knows the URL shapes). */
export const paths = {
  menu: () => '/',
  decks: () => '/decks',
  deck: (id) => `/deck/${encodeURIComponent(id)}`,
  card: (id, index) => `/deck/${encodeURIComponent(id)}/card/${index}`,
  lobby: (code) => `/lobby/${encodeURIComponent(String(code).toUpperCase())}`,
  play: () => '/play',
  signin: () => '/signin',
  username: () => '/username',
  account: () => '/account',
};

/**
 * The user pressed Back (or Forward / edited the hash). Is it fine to leave `from` for `to`?
 * A solo game in progress, or a lobby / live game where you hold a seat, must never be left without asking.
 */
export function backDecision({ from, to, gameActive = false, seated = false }) {
  const leavingSolo = from?.name === 'play' && to?.name !== 'play' && gameActive;
  const sameLobby = to?.name === 'lobby' && to.params?.code === from?.params?.code;
  const leavingTable = from?.name === 'lobby' && !sameLobby && seated;
  return leavingSolo || leavingTable ? 'confirm' : 'allow';
}

/** Warn on reload / tab close only while a game is in progress on screen. */
export const needsUnloadGuard = ({ route, gameActive }) => route?.name === 'play' && Boolean(gameActive);

/** A route that cannot be shown right now -> the path to show instead (or null). */
export function redirectFor(route, { hasGame }) {
  if (route?.name === 'play' && !hasGame) return '/';   // e.g. a reload, or Forward after leaving
  return null;
}

/**
 * Moving "up" to a parent screen: if the previous history entry already is that screen, just go Back (no history
 * growth); otherwise replace the current entry with it. `state` is history.state: { idx, from } set by the router.
 */
export function upAction(state, parentPath) {
  return state?.from === toHash(parentPath) ? 'back' : 'replace';
}
