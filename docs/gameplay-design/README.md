# Gameplay screen design

- `gameplay-design.md` — the spec (layout regions, the seven states with their exact copy, overlays, countdown, motion, breakpoints, what changed versus the old screen).
- `gameplay-design.html` — the interactive mockup (open it in a browser: every state, overlay, device size, 2 to 8 seats, reduced motion). `src.html` is its unbuilt source.

Built in `app/frontend`: `js/table.js` (pure logic, tested), `js/tableui.js` (DOM), `table.css` (styles). The mockup's CSS was ported with one change of mechanism and a few additions:

- **Media queries instead of container queries** on the live root (`< 700`, `700–1099`, `>= 1100`, `max-height: 700px`), because the table is the whole viewport. The heading font stack falls back to Arial Narrow; the optional web font is not loaded.
- **Round result.** It is a server phase (`ROUND_RESULT`, with a `deadline`), so **Continue** only collapses the result locally (to "Next round in N"); it cannot shorten the server's hold, and the countdown cannot be paused while a drawer is open. The last round has no server hold: the client keeps its result up for the same time before the ranking.
- **Wide screens** collapse the docked log while a result is shown, so the revealed cards get the room (the spec's suggestion). The result row is a grid of up to 4 cards and scrolls sideways for more; on phones it scrolls if a long result does not fit.
- **CHOOSE for the caller:** a caller with 2 to `chooseThreshold` cards picks the card first, then calls the stat.
- A crowded strip (more than 4 seats) shows computers as "CPU E / M / H".
