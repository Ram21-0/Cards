# Multiplayer table: design spec

Mockup: `gameplay-design.html` (same folder). Switch states, overlays, device, seat count (2 to 8), result variant, reduced motion and "Show regions" at the top. The mockup renders one markup with container queries, so the DOM below is what the frontend can build.

## Principles
1. One focus per moment. The biggest thing on screen is what I do now (call, race, choose, continue). Everything else is smaller or one tap away.
2. Three tiers. Primary: the action and its countdown. Secondary: what just happened (result row) and who is where (players rail). Tertiary: event log and my pile (drawers, docked on wide screens).
3. No scrolling during a decision on phones. The card shrinks to fit; the action never leaves the screen.
4. The action area keeps its height between states, so nothing jumps when a stat arrives.
5. Never colour alone: caller = ring + `CALLS` label, clash = ring + `VS` label, out = dashed + strikethrough + `OUT`, winner = ring + `WINNER` tag, urgent timer = thicker ring + `!` mark.

## Layout regions
Regions: **A** HUD, **B** players, **C** focus (status head, card, action), **D** drawers.

### Mobile (< 700 px wide), portrait
| Region | Contents |
|---|---|
| A HUD, 52 px (46 on short phones) | Leave (X, 44 px), `ROUND n`, `POOL n` pill (amber in a clash), connection chip (`Live` / `Offline`) |
| B Players strip | One chip per seat, equal width, fits 8 at 360 px: name (ellipsis), card count, caller flag (`CALLS`, `CALL` above 5 seats), `VS` in a clash, `OUT` when out. Me is cream-tinted. Non-contenders dim in a clash |
| C Focus | Head: heading + one-line sub + countdown ring (right). Below: my card (width computed from free height, 124 to 270 px) and the action block (3 x 2 stat buttons, or waiting panel, or card tray + confirm, or Continue) |
| D Dock, 56 px | `My pile n`, `Event log` buttons; each opens a bottom sheet (max 78% height) |

### Web (>= 700 px)
| Region | Contents |
|---|---|
| A HUD, 56 px | Leave, deck name, `ROUND n`, connection. 700 to 1099 px also shows `Log` and `My pile` buttons |
| B Left rail, 232 to 248 px | Seat rows (avatar, name, state line, big count), then a Pool box with a card stack |
| C Centre | Head with big timer. Play area is two columns: my card left, action right (stat buttons as 6 full rows with value and keys 1 to 6) |
| D Right panel, 340 px, from 1100 px | Tabs `Event log` / `My pile`, always docked, scrolls on its own. Below 1100 px it becomes a right drawer opened from the HUD |

Result row on web: wrapped grid, 4 cards per row, horizontal scroll above 4 players. The mockup keeps the card at 150 px minimum; if you want the cards larger on web, collapse the right panel while the result shows.

## States (copy is exact)
Heading is an `h1` with `role="status"`. `<p>` below is the sub line.

1. **My turn** (`canCall`, phase AWAIT_CALL, no clash). Heading `Your turn`. Sub `Call a stat. Highest wins, except Rank where the lowest wins.` Card label `Your card`. Buttons: label + value (`Rank ↓` marks lower wins). After tap: tapped button gets `CALLED`, others dim; line `You called Fight. Waiting for the others.` Ring shows `turnTimeoutSeconds`; if null show `No time limit` instead of the ring.
2. **Another player calling** (AWAIT_CALL, not me). Heading `{name} is calling`. Sub `Your card is ready. The stat shows up here as soon as it is picked.` Panel: avatar, `Waiting for {name}`, `Picking a stat...`, `If the time runs out the server picks one at random.` Same ring. Also used for AWAIT_CHOOSE_CALLER/OTHERS when I cannot choose: heading `{name} is picking a card` / `Players are choosing their cards`, card shows the stat highlighted once known.
3. **Round result** (events revealed + roundWon). Heading `You win the round` (green) or `{name} wins the round` (red-tinted). Sub `FIGHT JOHN CENA 90 beats ann's RANDY ORTON 89.` Chips: `You take 4 cards` / `{name} takes 4 cards`; `{name} is out · 4th`; big-card notice `Big card: you won RANDY ORTON` / `Big card: you lost JOHN CENA`. Row of all revealed cards, sorted best first, stat cell highlighted (existing `.hl`), `WINNER` tag on the winner, caption `{player}  {Stat} {value}`. Clash result: heading `You win the clash`, chip `You take the pool: 9 cards`. Pot carried: heading `No winner`, chip `The pool (6) carries over`. Button `Continue 5` fills over 5 s and auto-advances; tap skips. Pause the countdown while a sheet is open.
4. **Clash race** (CLASH_AWAIT_CALL, I am in `contenders`). Heading `Clash ×2` (clash colour; no ×1). Sub `You and ann tied. Call a stat first to win the race.` plus `2 other players wait.` when others are out of the clash. Card label `Your next card · Pool 6`. Amber bar across the top of the focus area drains over `clashCallSeconds`; ring is larger with a 44 px number. Buttons have clash-colour borders; hint `First tap wins the race.` After my tap: `You called Fight first.` If I lost the race (409): sheet-less inline line `ann called Weight first.` If I am not a contender: heading `Clash ×2`, sub `ann and bob are racing to call.`, my card hidden, bar still shows.
5. **Choose** (`canChoose`). Heading `Pick your card`. Sub `{name} called **Weight**. Highest weight wins.` (this is the case where someone else called; for the caller choosing before the stat is known see 5b). Card shows the selected card with the called stat highlighted. Tray: one tile per card with name and called-stat value, tags `TOP` (default if time runs out) and `BEST` (highest/lowest of the hand for this stat, computed client side from `ownHand`). Top is preselected. Button `Play {NAME}` confirms. Hint `Weight shown on each card. Time out plays your top card.`
5b. **Choose, I am the caller** (mockup: tab `5b Choose (I call)`, then `I call with` 3 / 2 / 1 card). Rule: a caller with 2 or 3 cards (at most `chooseThreshold`, at least 2) picks the card BEFORE the stat is known, then calls the stat.
   - One screen, two quick taps. Order of steps: (1) the top card is preselected and shown in the card slot; (2) optionally tap another tile to swap the card; (3) tap a stat button, which both plays the selected card and calls that stat. A caller who does not care just taps a stat.
   - Heading `Your turn`. Sub `Pick a card to play, then tap a stat. The top card is preselected.` Card label `Playing · top card` (or `Playing` for another card; hidden on short phones). Same ring as My turn.
   - Tray (compact tiles, 3 across on phone, rows on web): card name plus a hint with its strongest stat, e.g. `HEIGHT 6′4″`, computed from the deck (share of cards it beats; Rank counts lowest). Tag `TOP` on the first card. No `BEST` tag, because no stat is called yet. Selected tile has a cream ring and a check.
   - Stat buttons below the tray show the values of the selected card and change when the tile changes. Hint on tall screens: `Tap a stat to play this card and call it`.
   - After the tap: the button shows `CALLED`, others dim, line `You called Height with SHEAMUS.` Tapping a tile after that is ignored until the next phase.
   - Hand sizes: 3 and 2 cards show the tray (2 tiles). 1 card means no choose step (`canChoose` false), so the screen is exactly state 1, `Your turn`, with no tray. The rail shows my real count (3, 2 or 1).
   - Timeout (`turnTimeoutSeconds`): the server plays the top card, then picks a random stat. The client sees `cardChosen auto:true` then `statCalled auto:true`; the log reads `you chose a card (time ran out)` and `you called Weight (time ran out)`. The ring is not reset between the two steps because the deadline is per turn; if the server sets a new `deadline` after the card is chosen, restart the ring.
   - Server sequence and `view` fields: phase `AWAIT_CHOOSE_CALLER` with `canChoose: true`, `canCall: false` and `ownHand` = the whole hand; this screen is shown for it. After `POST /choose` (the card id) the phase becomes `AWAIT_CALL` with `canCall: true` and `committed` = the chosen card. Because the design merges both steps on one screen, send them back to back on the stat tap: first `choose` with the selected `cardId` (skip it when the top card is selected and you prefer one request, since top card is the default only on timeout, so send `choose` anyway to be explicit), then `call` with the stat when the next `view` has `canCall`. Disable the buttons between the two responses (a few hundred ms) and show the `CALLED` state at once. If `choose` fails (409/403) fall back to the server view.
   - Fields: `phase`, `canChoose`, `canCall`, `ownHand` (all cards, stats for the hint and button values), `committed` (after choosing), `deadline`/`serverTime`, `handSizes[me]`, `callerSeat` (me, drives the `CALLS` flag), `ownPile` (pile drawer: while `canChoose` it is the whole pile, so the in-play row is the selected card).
6. **Out / spectating** (my seat in `placements`, I still receive views). Heading `You're out`. Sub `You finished 3rd. Keep watching, or leave the game.` Live line `{name} is calling a stat` + ring. Section `Last round · Fight` with the revealed cards. Buttons `Event log`, `Leave game`. Rail shows me as `OUT`.
7. **Game over** (`gameOver` message or FINISHED lobby). Heading `{name} wins` (or `You win`, with gold treatment). Sub `You finished 2nd of 4. Game lasted 38 rounds.` (round cap: sub `The round limit ended the game: the player with the most cards wins.`). Ranking list, winner first, my row outlined, placement `1st`... Shared placements show the same number. Buttons `New game with this deck` (primary), `Back to decks`. Web shows the last round next to the ranking.

### Overlays
- **Event log**: sheet (phone) / drawer (mid web) / docked tab (wide). Newest first, grouped under `Round n` rules; each line has a small icon square (star win, swords clash, X out). Texts: existing `eventText` strings. The dock `Event log` button shows a dot when new lines arrived while closed.
- **My pile**: title `My pile · 5 cards`, note `Your cards in the order they come up. Cards you win go to the bottom.` First row is the in-play card (dashed, `In play`), then the pile in order, first with `Next`. Each row: thumb, name, `RAW`/`SD` badge (text, not just colour).
- **Leave dialog**: centred. Title `Leave this game?`, text `The game keeps going without you. You stay seated and can come back with the code K7Q2XM while it is running.` Buttons `Stay` (primary, left), `Leave`. Esc / scrim = Stay.
- **Connection lost**: HUD chip turns amber `Offline` (diamond icon, not just colour). Amber banner under the HUD: `Connection lost. Reconnecting...` + `Attempt 3. Your cards are safe. If you are away on your turn, the server plays for you after 15 s.` Card and action area dim and ignore taps. Banner disappears on reconnect; the next `view` is the truth.

## Countdown and urgency
- Source: `deadline` minus `serverTime` skew. Show whole seconds (ceil). Ring drains smoothly; the number is tabular.
- Normal timer: 58 px ring, 26 px number, label `SEC`. Clash: 76 px (96 on web) ring, 44 px number, plus the top bar.
- Urgent: last 5 s of a turn, last 2 s of a clash. Ring turns red and thicker, number gets a `!` and pulses. Reduced motion: no pulse.
- No deadline (`turnTimeoutSeconds` null): text `No time limit`, no ring.
- Expired: do nothing visually special; the server's `auto: true` event arrives and the log says `(time ran out)`.

## Motion
Reveal cards pop in with a 90 ms stagger (0.4 s, translate 12 px + fade). Sheets rise (0.25 s), drawers slide, dialogs pop. Stat buttons scale to .97 on press. Dots in the waiting panel pulse. Urgent number throbs.
**Reduced motion** (`prefers-reduced-motion` and an in-app toggle): all animation and transitions off, the timer ring and race bar update once per second in steps, no pulse; the `!` mark and the thicker red ring still show urgency.

## Responsive breakpoints (use `@container` or media queries on the live root)
| Width | Layout |
|---|---|
| < 700 | Phone: strip + focus + dock, sheets from bottom |
| 700 to 1099 | Rail left + focus; log/pile as right drawer from HUD buttons |
| >= 1100 | Rail + focus + docked right panel |
| Height <= 700 | Short phone: HUD 46, dock 50, buttons 50, sub clamped to 2 lines, hint hidden, card min 124 px |
Card width on phone = clamp(124px, (viewport height - reserved chrome - 46px) / 1.225, 270px), where 1.225 is the card height to width ratio plus fixed parts. `--res` (chrome height) is 492 px for call/clash/wait, 510 for choose, 514 for 5b (caller choose), 392 / 414 / 440 on short phones.

## What changes versus today
| Today | New place |
|---|---|
| Top bar (Leave, deck name, conn chip) | HUD (A) |
| Status panel: round | HUD |
| Status panel: pool pill | HUD pill; web: Pool box in rail |
| Status panel: CLASH banner | Head heading `Clash ×2`, amber race bar, amber pool pill, `VS` flags on seats |
| Status panel: countdown | Ring in the head (right), bar for clashes |
| Status panel: status sentence | Head heading + sub line |
| Call panel | Action block in C (stat buttons with values; on web a vertical list with key hints) |
| Choose panel | Card tray + `Play {NAME}` in C |
| Seats list | Players strip / rail (B) |
| Last round panel | State 3 result row in C (full), and in states 6 and 7; log keeps the text lines |
| Your hand / committed card | The card in C; committed card = same slot labelled `Your card` |
| Own pile (collapsible) | My pile sheet / tab (D) |
| Event feed | Event log sheet / tab (D) |
| Leave dialog | Same text, centred dialog |
| Big-card notices | Chip in the result head |

## Components
`Hud`, `SeatChip` (strip) and `SeatRow` (rail) as one element with two layouts, `PoolBox`, `StatusHead` (heading, sub, `TimerRing`), `RaceBar`, `CardSlot` (label + existing card), `StatButton`, `StatGrid`, `WaitPanel`, `CardTray` + `Tile`, `RevealRow` + `RevealItem`, `ContinueButton` (with fill), `RankList`, `Sheet` (bottom/right), `SidePanel` (tabs), `LeaveDialog`, `ConnBanner`, `Dock`.

## CSS tokens
Existing: `--bg --bg-2 --panel --line --text --muted --accent --accent-ink --win --loss --clash --raw --sd --cream --black --radius`.
New: `--panel-2:#2b3042` (raised/selected), `--clash-ink:#2b1d00`, `--win-ink:#04210f`, `--focus:#8ab4ff` (focus ring), `--scrim:rgba(8,9,13,.66)`, `--f-display:'Barlow Condensed','Arial Narrow',system-ui` (headings, numerals; optional, falls back well), `--btn-h:56px`, `--hud-h:52px`, `--dock-h:56px`. Contrast: `--muted` on `--panel` is about 5.8:1; ink tokens on win/clash/accent fills pass 7:1.
Accessibility: all buttons >= 44 px (stat buttons 56), visible `:focus-visible` ring, `role="status"` on heading and connection chip, `role="timer"` on the ring, `aria-label` on seats ("ann, 7 cards, calls"), radio group semantics for the card tray.

## Implementation note: `view` and event fields per element
| Element | Fields |
|---|---|
| Round, Pool pill / box | `round`, `potSize`, `clashLevel` |
| Seat chips / rows | `handSizes`, `callerSeat`, `contenders`, `placements` (out + final place), game `seats[].username/kind/difficulty`, `seat` (me) |
| Status head | `phase`, `callerSeat`, `stat`, `clashLevel`, `canCall`, `canChoose`, `contenders` |
| Timer ring / race bar | `deadline`, `serverTime` (skew), `options.turnTimeoutSeconds`, `options.clashCallSeconds` |
| My card | `ownHand[0]` or `committed` (callCard) |
| Stat buttons | card `stats[key].display`, deck stats `label` and `comparison_strategy` (for `↓`), `canCall` |
| Choose tray | `ownHand` (whole hand when `canChoose`), `stat` (for values and BEST), `canChoose` |
| Result head and row | events `revealed.cards`, `roundWon.seat/cardsWon`, `clashStarted`, `potCarried`, `playerEliminated`, `statCalled.stat/auto`; keep `lastRound` as today |
| Big-card chip | existing `bigCardNotices` |
| Pile sheet | `ownPile` (`id`, `name`), `committed` (in-play row) |
| Event log | all `events` via `feedLines` |
| Out state | my seat in `placements` while `status` is IN_PROGRESS |
| Game over | `gameOver.placements`, `endedByRoundCap`, `game.seats[].placement`, game `joinCode` |
| Connection | socket status from `createLiveClient` |
| Leave dialog | `game.status`, `game.joinCode` |

Derive client-side only from public or own data: BEST tag (own hand), `Rank ↓` hint, sort order of the result row.
