# Game rules — engine specification

The authoritative spec for the rules engine (pure Java, no Spring/DB code). Agreed with the user; change it here first, then the code.

## 1. Setup
- 2–8 seats (humans and/or CPUs). The deck is shuffled and dealt round-robin; uneven decks are fine (some seats start with one more card).
- The first caller is chosen at random.
- **Settings per game** (defaults come from `application.yml`, the host may override when creating the game; all are validated):

| Setting | Default | Range | Meaning |
|---|---|---|---|
| `maxRounds` | 500 | 20–5000 | Safety cap, because cards cycle (see §6) |
| `chooseThreshold` | 3 | 0–10 | A player with at most this many cards may CHOOSE (0 disables CHOOSE) |
| `clashCallSeconds` | 5 | 2–30 | Time to call a stat in a clash |
| `turnTimeoutSeconds` | none | 5–120 | Time for a normal call / choose; none = no limit |

## 2. A normal round
1. **Eligible players choose** (see §4): the caller first.
2. The **caller picks a stat** from their own card. On timeout the server picks a **random** stat.
3. Other CHOOSE-eligible players pick their card, now that the stat is known (timeout = their top card).
4. Everyone still holding cards reveals their (chosen or top) card. The best value under the stat's `comparison_strategy` wins.
5. **Unique best → that player wins the round:** every revealed card plus any carried pot goes to the **bottom of the winner's pile** (their own card first, then the others in seat order starting after the winner). **The winner calls next.**
6. **Tie for best → clash (§3).**

Cards are never discarded: they cycle back to the winner's pile.

## 3. Clash
Triggered when `x ≥ 2` players tie for the **best** value. Let `T` be the tied players.
1. Every card revealed so far (all players, including those not in `T`) goes into the clash pot `P`. The UI shows **CLASH** (with the tied players and a "×N" counter on repeats).
2. Each player in `T` takes the **next card** of their pile. A tied player with **no card left** drops out of the tie-break (their cards stay in `P`; with no cards they are eliminated).
3. **Race to call:** every player in `T` can call a stat for this tie-break. The **first valid call within `clashCallSeconds`** sets the stat for everyone (server arrival order; simultaneous → lowest seat). Nobody calls in time → the server picks a **random** stat. CPU seats call after a random 1.5–4 s so humans can win the race.
4. CHOOSE-eligible players in `T` then pick their card (§4); everyone in `T` reveals.
5. Compare among `T` only:
   - unique best → that player takes **all of `P` plus the cards just revealed** (cards from every clash level and the original round) to the bottom of their pile, and **calls next**;
   - tie again among `T' ⊆ T` → only `T'` repeat from step 1 (pot keeps growing) until one wins;
   - only one player in `T` still has cards → they win by default;
   - no player in `T` has a next card → the pot **carries** to the next round's winner, and the caller passes to the next active seat clockwise.

## 4. CHOOSE
- A player with **at most `chooseThreshold` cards** (default 3) and **at least 2** may play **any card from their hand** instead of the top card. It is optional.
- It happens **after the stat is known** (the caller chooses first, then picks the stat; everyone else chooses after the stat is called), also inside clashes. Timeout = the top card.
- The chosen card leaves the hand for the round; the other cards keep their order.

## 5. Elimination and ending
- A player left with **0 cards** after a round is eliminated. Their `placement` = (players still in the game) + 1 at that moment; players eliminated by the same round share the placement.
- The game ends when **one player holds all cards** → placement 1.
- If the caller is eliminated (lost their last card), the round winner calls next anyway.

## 6. Round cap
When `maxRounds` is reached, the player with the **most cards** wins (cards in a carried pot count for nobody). Equal top counts share placement 1; others are placed by card count.

## 7. CPU seats
Each difficulty (`easy`, `medium`, `hard`) is a **profile**: a *strategy* plus *noise*, configured in `application.yml`
(`app.game.cpu.<difficulty>.strategy` and `.noise`; invalid values stop the application at startup).
Defaults: **easy = random**, **medium = opponent-percentile with noise 0.25**, **hard = smart**.

| Strategy | What it does |
|---|---|
| `random` | A random stat, a random card. |
| `deck-percentile` | Judges a card on a stat by the share of the **whole deck** it beats. |
| `opponent-percentile` | The share of **only the cards the CPU does not hold** that it beats. The CPU's own cards cannot be its opponents' cards, so counting them (as `deck-percentile` does) distorts the estimate. |
| `smart` | Expected value: `p × prize − (1 − p) × cost`. `p` = the chance to beat **every** opponent in the round (the opponent-percentile, a tie counting half, raised to the number of opponents). `prize` = cards won (one per opponent plus the pot). `cost` = the card lost: 1 plus how much a keeper it is (its best stat's opponent-percentile). So a hopeless round is "lost anyway": the weakest card is given away and the strong ones are kept; a winnable round is played with the card that wins it. |

All strategies are fair: they use only the CPU's own hand, the deck (every card and its stats are public), how many opponents are in the round and the pot size — never another player's hand.
**Noise** (0–1) is added to every judgement, as a share of the strategy's value range (0 = always the best option).

- **Choosing a stat:** the stat the strategy rates best for the card in play (`random`: any).
- **CHOOSE:** the caller chooses before the stat is known, so cards are judged by their strongest single stat; everyone else knows the stat and picks the card that rates best for it. If **no** card in the hand can beat or tie any card the CPU does not hold on that stat, the percentile strategies (`deck-percentile`, `opponent-percentile`) pick a **random** card (they have no sacrifice logic); `smart` instead gives away the card it least wants to keep.
- **Delay in a clash race:** random 1.5–4 s, so humans can win the race. In a normal round a CPU decides immediately.

## 8. Hidden information
The server never sends a card to anyone but its holder until it is revealed. A player's view contains: their own hand (count; top card, or the whole hand when CHOOSE applies), every seat's **card count**, the pot size, whose turn it is, the current phase and its deadline. Revealed cards are sent to everyone at the reveal.

## 9. Engine shape
- A pure state machine with injectable **random source** and **clock**.
- **Commands:** `choose(seat, cardId)`, `call(seat, statKey)`, `tick(now)` (applies timeouts).
- **Events returned:** `RoundStarted`, `CardChosen`, `StatCalled{seat, stat, auto}`, `Revealed{cards}`, `RoundWon{seat, cardsWon}`, `ClashStarted{tiedSeats, level, potSize}`, `PlayerEliminated{seat, placement}`, `GameOver{placements}`.
- Phases: `AWAIT_CHOOSE_CALLER → AWAIT_CALL → AWAIT_CHOOSE_OTHERS → RESOLVE → (CLASH_AWAIT_CALL → CLASH_AWAIT_CHOOSE → CLASH_RESOLVE)* → ROUND_DONE → … → GAME_OVER`.

## 10. Test checklist
Conservation of cards at every step (hands + pot + in-flight = deck size) · no duplicates · normal round with 2…8 players · winner calls next · won-card order · 3-way tie and repeated clash · only tied players take part in a repeated clash · tied player with no next card · nobody can continue → pot carries · clash timeout picks a random stat (seeded) · first caller wins the race · CHOOSE with 3, 2, 1 and 4 cards (not offered above the threshold, not offered with 1) · caller eliminated · simultaneous elimination shares placement · uneven deal · round cap with equal counts · determinism for a fixed seed · thousands of seeded random games (2–8 seats, mixed CPU difficulties) that all terminate.

## 11. Implementation notes (how the engine settles what this spec leaves open)
`com.cards.game.GameEngine` follows this document; where it was silent the engine does the following. Change it here first if you disagree.
- **Phases:** `RESOLVE`, `CLASH_RESOLVE` and `ROUND_DONE` happen inside a single command and are never observable; callers only see the `AWAIT_*` phases and `GAME_OVER`.
- **Won-card order:** the winner's own card, then the others in seat order starting after the winner, then the pot in the order it was built (clash cards by level, each level in seat order).
- **Deadlines are exclusive:** a call arriving exactly at the deadline is too late; the timeout fires first. At the same instant the lowest seat wins a race (also CPU against human).
- **Timeouts catch up:** a late `tick` applies every missed timeout at its own logical time, so a game does not depend on how often `tick` is called.
- **Placements:** competition ranking. Eliminated in a round = (players still holding cards after that round) + 1, shared. At the round cap, survivors get 1 + (survivors with strictly more cards). If everyone runs out together (all cards in a carried pot) they share placement 1: a draw.
- **Clash with one player left:** wins by default and takes the pot; the winner does not reveal a card.
- **Carried pot:** the next caller is the next seat clockwise holding cards; the pot goes to the next round's winner (after that round's revealed cards).
- **Events:** `CardChosen` is emitted only for players who were offered CHOOSE (a timeout is `auto = true`); `PotCarried` is added for the carry case; `RoundWon.cardsWon` counts every card now on the winner's pile; `GameOver` says whether the round cap ended the game.
- **Card counts** (`handSize`, the view's `handSizes`) include a card already put in play this round: it is still the player's until the round is decided.
- **Turn timeout** (when set) also limits CHOOSE inside a clash; with no limit a human may take as long as they like.
- **A CPU delay longer than `clashCallSeconds`** (e.g. a 2 s window against a 1.5–4 s delay) simply loses to the timeout: the server draws the stat.
- **Absent humans (live play):** a human who has no open connection while it is their turn is timed out after a grace period (default 15 s, counted from the later of the disconnect and the start of their turn), exactly as a turn timeout would: random stat, or the top card. It applies even when the game has no turn timeout, affects only that seat, and a clash race is left to its own timer (`GameEngine.timeoutSeat`).
- **`smart` and a card that can only tie:** if a card cannot beat any unheld card on the stat but can tie, the tie is not counted as half a win. It is worth the chance of winning the resulting clash, estimated from the **overall strength of the CPU's other cards** (the average, over them, of their best stat against the cards the CPU does not hold), not from the stat in question: `P(tie) × (clashWin × clashPrize − (1 − clashWin) × clashCost) − P(lose) × cost`. Strong cards behind it mean a strong card is kept in play instead of being given away.
