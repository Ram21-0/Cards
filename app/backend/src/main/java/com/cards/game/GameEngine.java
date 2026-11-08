package com.cards.game;

import com.cards.common.Card;
import com.cards.common.Outcome;
import com.cards.common.StatDefinition;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.random.RandomGenerator;

/**
 * The multiplayer rules as a pure state machine (RULES.md): no Spring, no database, no threads, no wall clock.
 * Randomness comes from an injected {@link RandomGenerator} and time from an injected {@link Clock}, so a game replays
 * exactly from its seed and tests control time.
 *
 * <p>Commands are {@link #choose}, {@link #call} and {@link #tick}; each returns the {@link GameEvent}s it caused.
 * CPU seats are driven by the engine itself. {@link #nextWakeup()} says when the engine next needs {@code tick}
 * (a timeout or a CPU's delayed call in a clash).
 *
 * <p>The engine is not thread-safe: the live layer must serialize commands per game.
 * Cards never disappear: hands + pot + cards in play always add up to the deck (see {@link #checkInvariants()}).
 */
public final class GameEngine {

    public static final int MIN_SEATS = 2;
    public static final int MAX_SEATS = 8;

    /** One seat's cards. {@code hand.get(0)} is the top of the pile; the winner's cards go to the bottom (end). */
    private static final class Seat {
        final int number;
        final SeatSpec spec;
        final List<Card> hand = new ArrayList<>();
        boolean eliminated;

        Seat(int number, SeatSpec spec) {
            this.number = number;
            this.spec = spec;
        }
    }

    /** A CPU call in a clash race that is due at {@code dueAt}. */
    private record CpuCall(int seat, Instant dueAt) {
    }

    // ---- configuration (immutable)
    private final List<StatDefinition> stats;
    private final GameSettings settings;
    private final RandomGenerator rnd;
    private final Clock clock;
    private final CpuPlayer cpu;
    private final int totalCards;

    // ---- state
    private final List<Seat> seats = new ArrayList<>();                       // index = seat number - 1
    private final List<Card> pot = new ArrayList<>();                         // cards waiting for a clash/carry winner
    private final Map<Integer, Card> inFlight = new TreeMap<>();              // cards committed this round, by seat
    private final TreeSet<Integer> contenders = new TreeSet<>();              // seats playing the round / the current clash
    private final TreeSet<Integer> pendingChoosers = new TreeSet<>();         // seats that still may CHOOSE
    private final List<CpuCall> cpuCalls = new ArrayList<>();                 // clash race, sorted by (dueAt, seat)
    private final Map<Integer, Integer> placements = new TreeMap<>();
    private final List<GameEvent> out = new ArrayList<>();
    private Phase phase;
    private int callerSeat;
    private int round;
    private int clashLevel;
    private StatDefinition stat;
    private Instant deadline;
    private Integer roundWinner;                                              // set when a round ends; null = the pot carried

    // ------------------------------------------------------------------------------------------ creating a game

    /**
     * Deals {@code deck} round-robin over the seats (uneven decks are fine), picks the first caller at random and starts
     * round 1. The events of the start (RoundStarted, …) are available from {@link #pollEvents()}.
     */
    public static GameEngine start(List<Card> deck, List<StatDefinition> stats, List<SeatSpec> seatSpecs,
                                   GameSettings settings, RandomGenerator rnd, Clock clock) {
        return start(deck, stats, seatSpecs, settings, CpuProfiles.defaults(), rnd, clock);
    }

    /** Same, with the CPU strategies to use for each difficulty (see {@link CpuProfiles}). */
    public static GameEngine start(List<Card> deck, List<StatDefinition> stats, List<SeatSpec> seatSpecs,
                                   GameSettings settings, CpuProfiles cpuProfiles, RandomGenerator rnd, Clock clock) {
        if (seatSpecs.size() < MIN_SEATS || seatSpecs.size() > MAX_SEATS) {
            throw new IllegalArgumentException("A game has " + MIN_SEATS + " to " + MAX_SEATS + " seats");
        }
        if (deck.size() < seatSpecs.size()) {
            throw new IllegalArgumentException("The deck needs at least one card per seat");
        }
        List<Card> shuffled = new ArrayList<>(deck);
        Collections.shuffle(shuffled, rnd);
        List<List<Card>> hands = new ArrayList<>();
        for (int i = 0; i < seatSpecs.size(); i++) hands.add(new ArrayList<>());
        for (int i = 0; i < shuffled.size(); i++) hands.get(i % seatSpecs.size()).add(shuffled.get(i));
        int firstCaller = rnd.nextInt(seatSpecs.size()) + 1;
        return new GameEngine(stats, seatSpecs, settings, cpuProfiles, hands, firstCaller, 0, rnd, clock);
    }

    /** A game from known hands (top card first) — for tests and for resuming a stored game. {@code roundsPlayed} rounds count towards the cap. */
    public static GameEngine withHands(List<StatDefinition> stats, List<SeatSpec> seatSpecs, GameSettings settings,
                                List<List<Card>> hands, int firstCaller, int roundsPlayed,
                                RandomGenerator rnd, Clock clock) {
        return withHands(stats, seatSpecs, settings, CpuProfiles.defaults(), hands, firstCaller, roundsPlayed, rnd, clock);
    }

    public static GameEngine withHands(List<StatDefinition> stats, List<SeatSpec> seatSpecs, GameSettings settings,
                                CpuProfiles cpuProfiles, List<List<Card>> hands, int firstCaller, int roundsPlayed,
                                RandomGenerator rnd, Clock clock) {
        return new GameEngine(stats, seatSpecs, settings, cpuProfiles, hands, firstCaller, roundsPlayed, rnd, clock);
    }

    private GameEngine(List<StatDefinition> stats, List<SeatSpec> seatSpecs, GameSettings settings, CpuProfiles cpuProfiles,
                       List<List<Card>> hands, int firstCaller, int roundsPlayed, RandomGenerator rnd, Clock clock) {
        if (stats.isEmpty()) throw new IllegalArgumentException("The deck has no stats");
        if (seatSpecs.size() != hands.size()) throw new IllegalArgumentException("One hand per seat");
        if (firstCaller < 1 || firstCaller > seatSpecs.size()) throw new IllegalArgumentException("No such seat: " + firstCaller);
        this.stats = List.copyOf(stats);
        this.settings = settings;
        this.rnd = rnd;
        this.clock = clock;
        this.round = roundsPlayed;

        List<Card> deck = new ArrayList<>();
        for (int i = 0; i < seatSpecs.size(); i++) {
            Seat seat = new Seat(i + 1, seatSpecs.get(i));
            seat.hand.addAll(hands.get(i));
            deck.addAll(seat.hand);
            seats.add(seat);
        }
        this.totalCards = deck.size();
        if (deck.stream().map(Card::id).distinct().count() != totalCards) throw new IllegalArgumentException("Duplicate cards in the deal");
        this.cpu = new CpuPlayer(this.stats, deck, cpuProfiles, rnd);

        this.callerSeat = firstCaller;
        beginRound(clock.instant());
        drive(clock.instant());
    }

    // ------------------------------------------------------------------------------------------ commands

    /**
     * A CHOOSE-eligible seat commits one card from its hand for this round (choosing the top card is "no choice").
     * The caller chooses before calling a stat; everyone else after the stat is known; also inside clashes.
     */
    public List<GameEvent> choose(int seatNumber, int cardId) {
        Instant now = clock.instant();
        requireRunning();
        settle(now, Integer.MAX_VALUE);
        requireRunning();
        if (phase != Phase.AWAIT_CHOOSE_CALLER && phase != Phase.AWAIT_CHOOSE_OTHERS && phase != Phase.CLASH_AWAIT_CHOOSE) {
            throw new GameRuleException(GameRuleException.Kind.WRONG_PHASE, "Not waiting for a card choice");
        }
        Seat seat = seatOrThrow(seatNumber);
        if (!pendingChoosers.contains(seatNumber)) throw new GameRuleException(GameRuleException.Kind.NOT_YOUR_TURN, "Seat " + seatNumber + " cannot choose a card now");
        int index = indexOfCard(seat.hand, cardId);
        if (index < 0) throw new GameRuleException(GameRuleException.Kind.BAD_INPUT, "Card " + cardId + " is not in the hand of seat " + seatNumber);

        commit(seat, index);
        pendingChoosers.remove(seatNumber);
        out.add(new GameEvent.CardChosen(seatNumber, false));
        afterChoosing(now);
        drive(now);
        return drain();
    }

    /**
     * Sets the stat. In a normal round only the caller may; in a clash every player still in it may, and the first call
     * within {@code clashCallSeconds} wins (the server's arrival order; at the same instant the lowest seat).
     */
    public List<GameEvent> call(int seatNumber, String statKey) {
        Instant now = clock.instant();
        requireRunning();
        settle(now, seatNumber);
        requireRunning();
        StatDefinition called = statOrThrow(statKey);
        seatOrThrow(seatNumber);
        if (phase == Phase.AWAIT_CALL) {
            if (seatNumber != callerSeat) throw new GameRuleException(GameRuleException.Kind.NOT_YOUR_TURN, "Seat " + seatNumber + " is not the caller");
            applyCall(seatNumber, called, false, now);
        } else if (phase == Phase.CLASH_AWAIT_CALL) {
            if (!contenders.contains(seatNumber)) throw new GameRuleException(GameRuleException.Kind.NOT_YOUR_TURN, "Seat " + seatNumber + " is not in the clash");
            applyClashStat(seatNumber, called, false, now);
        } else {
            throw new GameRuleException(GameRuleException.Kind.WRONG_PHASE, "Not waiting for a stat");
        }
        drive(now);
        return drain();
    }

    /** Applies everything that is due at {@code now}: timeouts (random stat / top card) and CPU calls in a clash race. */
    public List<GameEvent> tick(Instant now) {
        if (!over()) settle(now, Integer.MAX_VALUE);
        return drain();
    }

    /**
     * The seat is not there (it is disconnected) and its turn is not worth waiting for: does what a timeout would have
     * done for THIS seat only, now. Does nothing unless the seat may act in a phase that has a timeout (a clash race is
     * left to its own 5 s timer, and the other seats are untouched).
     */
    public List<GameEvent> timeoutSeat(int seatNumber) {
        Instant now = clock.instant();
        if (over()) return drain();
        settle(now, Integer.MAX_VALUE);
        if (over() || !actingSeats().contains(seatNumber)) return drain();
        Seat seat = seatOrThrow(seatNumber);
        switch (phase) {
            case AWAIT_CHOOSE_CALLER, AWAIT_CHOOSE_OTHERS, CLASH_AWAIT_CHOOSE -> {
                commit(seat, 0);
                pendingChoosers.remove(seatNumber);
                out.add(new GameEvent.CardChosen(seatNumber, true));
                afterChoosing(now);
            }
            case AWAIT_CALL -> {
                StatDefinition random = stats.get(rnd.nextInt(stats.size()));
                stat = random;
                out.add(new GameEvent.StatCalled(callerSeat, random.key(), true));
                enterChoose(Phase.AWAIT_CHOOSE_OTHERS, now);
            }
            default -> {
                return drain();
            }
        }
        drive(now);
        return drain();
    }

    /** The events produced since the last call (use after {@link #start} to get the opening events). */
    public List<GameEvent> pollEvents() {
        return drain();
    }

    // ------------------------------------------------------------------------------------------ queries

    public boolean isOver() {
        return over();
    }

    public Phase phase() {
        return phase;
    }

    public int round() {
        return round;
    }

    public int callerSeat() {
        return callerSeat;
    }

    public int seatCount() {
        return seats.size();
    }

    public int potSize() {
        return pot.size();
    }

    /** Cards the seat holds, including the one it has put in play this round (it is still theirs until the round is decided). */
    public int handSize(int seatNumber) {
        return seatOrThrow(seatNumber).hand.size() + (inFlight.containsKey(seatNumber) ? 1 : 0);
    }

    /** The stat in play (null until it is called). */
    public Optional<StatDefinition> currentStat() {
        return Optional.ofNullable(stat);
    }

    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    /** Placement by seat (1 = winner, ties share a number). Complete once the game is over; before that only eliminated seats. */
    public Map<Integer, Integer> placements() {
        return Collections.unmodifiableMap(new TreeMap<>(placements));
    }

    /** When the engine next needs {@link #tick}: the earliest timeout or CPU call. Empty = it is waiting for a player. */
    public Optional<Instant> nextWakeup() {
        Instant next = deadline;
        if (!cpuCalls.isEmpty()) {
            Instant cpuDue = cpuCalls.get(0).dueAt();
            if (next == null || cpuDue.isBefore(next)) next = cpuDue;
        }
        return over() ? Optional.empty() : Optional.ofNullable(next);
    }

    /** The seats that may act right now: the caller, the players who may still CHOOSE, or everyone in a clash race. CPU seats included. */
    public Set<Integer> actingSeats() {
        if (over()) return Set.of();
        return switch (phase) {
            case AWAIT_CALL -> Set.of(callerSeat);
            case CLASH_AWAIT_CALL -> new TreeSet<>(contenders);
            case AWAIT_CHOOSE_CALLER, AWAIT_CHOOSE_OTHERS, CLASH_AWAIT_CHOOSE -> new TreeSet<>(pendingChoosers);
            default -> Set.of();
        };
    }

    /** What one seat may know (RULES.md §8): never another seat's cards. */
    public PlayerView view(int seatNumber) {
        Seat seat = seatOrThrow(seatNumber);
        boolean canChoose = !over() && pendingChoosers.contains(seatNumber);
        boolean canCall = !over() && ((phase == Phase.AWAIT_CALL && seatNumber == callerSeat)
                || (phase == Phase.CLASH_AWAIT_CALL && contenders.contains(seatNumber)));
        List<Card> own = canChoose ? List.copyOf(seat.hand) : seat.hand.isEmpty() ? List.of() : List.of(seat.hand.get(0));
        Map<Integer, Integer> sizes = new LinkedHashMap<>();
        for (Seat s : seats) sizes.put(s.number, handSize(s.number));
        // The cards that follow this round's: the whole hand while the seat may still choose (any card could be the one played),
        // or when nothing of theirs is in play; otherwise the top card is the one being played and is left out.
        boolean topIsInPlay = !over() && !inFlight.containsKey(seatNumber) && contenders.contains(seatNumber)
                && !canChoose && !settings.mayChoose(seat.hand.size());
        List<Card> pile = topIsInPlay && !seat.hand.isEmpty() ? List.copyOf(seat.hand.subList(1, seat.hand.size())) : List.copyOf(seat.hand);
        return new PlayerView(seatNumber, phase, round, callerSeat, stat == null ? null : stat.key(), clashLevel, pot.size(),
                deadline, List.copyOf(contenders), sizes, own, inFlight.get(seatNumber), canChoose, canCall, pile);
    }

    // ------------------------------------------------------------------------------------------ test hooks

    /** Throws if cards were lost or duplicated: hands + pot + cards in play must be exactly the deck. */
    void checkInvariants() {
        Set<Integer> ids = new HashSet<>();
        int count = 0;
        for (Seat s : seats) {
            for (Card c : s.hand) { ids.add(c.id()); count++; }
        }
        for (Card c : pot) { ids.add(c.id()); count++; }
        for (Card c : inFlight.values()) { ids.add(c.id()); count++; }
        if (count != totalCards) throw new IllegalStateException("Card count " + count + " != deck " + totalCards);
        if (ids.size() != count) throw new IllegalStateException("A card exists twice");
    }

    List<Card> handOf(int seatNumber) {
        return List.copyOf(seatOrThrow(seatNumber).hand);
    }

    List<Card> potCards() {
        return List.copyOf(pot);
    }

    int cardsInPlay() {
        return inFlight.size();
    }

    // ------------------------------------------------------------------------------------------ the loop

    /**
     * Does everything that needs no player: CPU decisions, resolving rounds, ending rounds, starting the next one.
     * A loop (not recursion), so an all-CPU game of thousands of rounds uses constant stack.
     */
    private void drive(Instant at) {
        while (!over()) {
            switch (phase) {
                case AWAIT_CHOOSE_CALLER -> {
                    Seat caller = seats.get(callerSeat - 1);
                    if (!caller.spec.isCpu()) return;
                    commit(caller, cpu.pickCardForCaller(caller.hand, caller.spec.difficulty(), situation(caller)));
                    pendingChoosers.clear();
                    out.add(new GameEvent.CardChosen(caller.number, false));
                    enterAwaitCall(at);
                }
                case AWAIT_CALL -> {
                    Seat caller = seats.get(callerSeat - 1);
                    if (!caller.spec.isCpu()) return;
                    Card card = inFlight.get(callerSeat);
                    applyCall(callerSeat, stats.get(cpu.pickStat(card, caller.spec.difficulty(), situation(caller))), false, at);
                }
                case AWAIT_CHOOSE_OTHERS, CLASH_AWAIT_CHOOSE -> {
                    cpuChoose();
                    if (!pendingChoosers.isEmpty()) return;
                    phase = phase == Phase.AWAIT_CHOOSE_OTHERS ? Phase.RESOLVE : Phase.CLASH_RESOLVE;
                    deadline = null;
                }
                case RESOLVE, CLASH_RESOLVE -> resolve(at);
                case ROUND_DONE -> endRound(at);
                case CLASH_AWAIT_CALL -> {
                    return;                                               // humans race; CPUs call from cpuCalls at their due time
                }
                case GAME_OVER -> {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ rounds

    private void beginRound(Instant at) {
        round++;
        contenders.clear();
        for (Seat s : seats) if (!s.eliminated && !s.hand.isEmpty()) contenders.add(s.number);
        inFlight.clear();
        pendingChoosers.clear();
        cpuCalls.clear();
        stat = null;
        clashLevel = 0;
        roundWinner = null;
        out.add(new GameEvent.RoundStarted(round, callerSeat, List.copyOf(contenders)));

        Seat caller = seats.get(callerSeat - 1);
        if (settings.mayChoose(caller.hand.size())) {
            pendingChoosers.add(callerSeat);
            phase = Phase.AWAIT_CHOOSE_CALLER;
            deadline = turnDeadline(at);
        } else {
            commit(caller, 0);
            enterAwaitCall(at);
        }
    }

    private void enterAwaitCall(Instant at) {
        phase = Phase.AWAIT_CALL;
        deadline = turnDeadline(at);
    }

    private void applyCall(int seatNumber, StatDefinition called, boolean auto, Instant at) {
        stat = called;
        out.add(new GameEvent.StatCalled(seatNumber, called.key(), auto));
        enterChoose(Phase.AWAIT_CHOOSE_OTHERS, at);
    }

    /** After the stat is known, every contender who may CHOOSE and has not committed a card yet gets to. */
    private void enterChoose(Phase choosePhase, Instant at) {
        pendingChoosers.clear();
        for (int s : contenders) {
            if (!inFlight.containsKey(s) && settings.mayChoose(seats.get(s - 1).hand.size())) pendingChoosers.add(s);
        }
        phase = choosePhase;
        deadline = turnDeadline(at);
    }

    /** CPU seats that may choose do so at once (the stat is known by now). */
    private void cpuChoose() {
        for (int s : List.copyOf(pendingChoosers)) {
            Seat seat = seats.get(s - 1);
            if (!seat.spec.isCpu()) continue;
            commit(seat, cpu.pickCardForStat(seat.hand, stats.indexOf(stat), seat.spec.difficulty(), situation(seat)));
            pendingChoosers.remove(s);
            out.add(new GameEvent.CardChosen(s, false));
        }
    }

    /** What follows a human's choice. */
    private void afterChoosing(Instant at) {
        if (phase == Phase.AWAIT_CHOOSE_CALLER) {
            pendingChoosers.clear();
            enterAwaitCall(at);
        } else if (pendingChoosers.isEmpty()) {
            phase = phase == Phase.AWAIT_CHOOSE_OTHERS ? Phase.RESOLVE : Phase.CLASH_RESOLVE;
            deadline = null;
        }
    }

    /**
     * Everyone in play commits their card (top card unless they chose), the cards are revealed and compared among the
     * contenders. A unique best wins; a tie for best is a clash among the tied players.
     */
    private void resolve(Instant at) {
        for (int s : contenders) {
            if (!inFlight.containsKey(s)) commit(seats.get(s - 1), 0);
        }
        out.add(new GameEvent.Revealed(Collections.unmodifiableMap(new LinkedHashMap<>(inFlight))));

        List<Integer> best = bestSeats();
        if (best.size() == 1) {
            int winner = best.get(0);
            out.add(new GameEvent.RoundWon(winner, award(winner)));
            roundWinner = winner;
            phase = Phase.ROUND_DONE;
            return;
        }
        // tie for best: every card played so far goes into the pot; only the tied players go on
        for (Card c : inFlight.values()) pot.add(c);
        inFlight.clear();
        contenders.clear();
        contenders.addAll(best);
        clashLevel++;
        out.add(new GameEvent.ClashStarted(List.copyOf(best), clashLevel, pot.size()));
        enterClashStep(at);
    }

    /** Seats holding the best value for the stat in play (by the stat's comparison strategy), in seat order. */
    private List<Integer> bestSeats() {
        List<Integer> best = new ArrayList<>();
        BigDecimal bestValue = null;
        for (var entry : inFlight.entrySet()) {
            BigDecimal value = entry.getValue().stat(stat.key()).value();
            if (bestValue == null) {
                best.add(entry.getKey());
                bestValue = value;
                continue;
            }
            Outcome outcome = stat.comparisonStrategy().compare(value, bestValue);
            if (outcome == Outcome.WIN) {
                best.clear();
                best.add(entry.getKey());
                bestValue = value;
            } else if (outcome == Outcome.TIE) {
                best.add(entry.getKey());
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------ clash

    /** One step of the tie-break: the tied players "take their next card"; those with none drop out. */
    private void enterClashStep(Instant at) {
        contenders.removeIf(s -> seats.get(s - 1).hand.isEmpty());
        if (contenders.isEmpty()) {                                       // nobody can continue: the pot carries
            out.add(new GameEvent.PotCarried(pot.size()));
            roundWinner = null;
            phase = Phase.ROUND_DONE;
            return;
        }
        if (contenders.size() == 1) {                                     // the last player standing wins by default
            int winner = contenders.first();
            out.add(new GameEvent.RoundWon(winner, award(winner)));
            roundWinner = winner;
            phase = Phase.ROUND_DONE;
            return;
        }
        stat = null;
        phase = Phase.CLASH_AWAIT_CALL;
        deadline = at.plusSeconds(settings.clashCallSeconds());
        cpuCalls.clear();
        for (int s : contenders) {
            if (seats.get(s - 1).spec.isCpu()) cpuCalls.add(new CpuCall(s, at.plusMillis(cpu.clashDelayMillis())));
        }
        cpuCalls.sort((a, b) -> a.dueAt().equals(b.dueAt()) ? Integer.compare(a.seat(), b.seat()) : a.dueAt().compareTo(b.dueAt()));
    }

    /** The race is decided: {@code seat} (null = nobody, the server drew a random stat) set the stat for everyone. */
    private void applyClashStat(Integer seat, StatDefinition called, boolean auto, Instant at) {
        stat = called;
        cpuCalls.clear();
        out.add(new GameEvent.StatCalled(seat, called.key(), auto));
        enterChoose(Phase.CLASH_AWAIT_CHOOSE, at);
    }

    // ------------------------------------------------------------------------------------------ the end of a round

    /** Eliminates players with no cards, then ends the game, applies the round cap, or starts the next round. */
    private void endRound(Instant at) {
        List<Seat> alive = new ArrayList<>();
        List<Seat> knockedOut = new ArrayList<>();
        for (Seat s : seats) {
            if (s.eliminated) continue;
            (s.hand.isEmpty() ? knockedOut : alive).add(s);
        }
        for (Seat s : knockedOut) {                                       // same round = same placement
            s.eliminated = true;
            placements.put(s.number, alive.size() + 1);
            out.add(new GameEvent.PlayerEliminated(s.number, alive.size() + 1));
        }
        if (alive.size() <= 1) {                                          // one player holds the cards (or everyone ran out together)
            if (alive.size() == 1) placements.put(alive.get(0).number, 1);
            finish(false);
            return;
        }
        if (round >= settings.maxRounds()) {                              // cards cycle, so the game is capped
            for (Seat s : alive) {
                int moreCards = 0;
                for (Seat other : alive) if (other.hand.size() > s.hand.size()) moreCards++;
                placements.put(s.number, moreCards + 1);
            }
            finish(true);
            return;
        }
        callerSeat = roundWinner != null ? roundWinner : nextActiveAfter(callerSeat);
        beginRound(at);
    }

    private void finish(boolean cap) {
        phase = Phase.GAME_OVER;
        deadline = null;
        cpuCalls.clear();
        pendingChoosers.clear();
        out.add(new GameEvent.GameOver(Collections.unmodifiableMap(new TreeMap<>(placements)), cap));
    }

    /** The next seat clockwise (higher number, wrapping) that still holds cards. */
    private int nextActiveAfter(int from) {
        for (int i = 1; i <= seats.size(); i++) {
            Seat s = seats.get((from - 1 + i) % seats.size());
            if (!s.eliminated && !s.hand.isEmpty()) return s.number;
        }
        throw new IllegalStateException("No player holds cards");
    }

    // ------------------------------------------------------------------------------------------ cards

    /** Takes a card out of the hand and puts it in play for this round. */
    private void commit(Seat seat, int handIndex) {
        inFlight.put(seat.number, seat.hand.remove(handIndex));
    }

    /**
     * Gives the winner every card in play plus the pot, to the bottom of their pile: their own card first, then the
     * others in seat order starting after the winner, then the pot in the order it was built. Returns how many.
     */
    private int award(int winnerSeat) {
        Seat winner = seats.get(winnerSeat - 1);
        List<Card> won = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            Card played = inFlight.get(((winnerSeat - 1 + i) % seats.size()) + 1);
            if (played != null) won.add(played);
        }
        won.addAll(pot);
        winner.hand.addAll(won);
        inFlight.clear();
        pot.clear();
        return won.size();
    }

    // ------------------------------------------------------------------------------------------ time

    /**
     * Applies due CPU calls and timeouts in the order they fall due, each at its own logical time (so a late tick still
     * gives the same game). {@code humanSeat} is the seat of a human call that is arriving right now: a CPU call due at
     * exactly this instant goes first only if its seat is lower.
     */
    private void settle(Instant now, int humanSeat) {
        while (!over()) {
            boolean timeoutDue = deadline != null && !now.isBefore(deadline);
            CpuCall cpuCall = cpuCalls.isEmpty() ? null : cpuCalls.get(0);
            boolean cpuDue = cpuCall != null && (cpuCall.dueAt().isBefore(now)
                    || (cpuCall.dueAt().equals(now) && cpuCall.seat() < humanSeat));
            if (cpuDue && (!timeoutDue || cpuCall.dueAt().isBefore(deadline))) {
                cpuCalls.remove(0);
                Seat s = seats.get(cpuCall.seat() - 1);
                applyClashStat(cpuCall.seat(), stats.get(cpu.pickStat(s.hand.get(0), s.spec.difficulty(), situation(s))), false, cpuCall.dueAt());
                drive(cpuCall.dueAt());
            } else if (timeoutDue) {
                timeout();
            } else {
                return;
            }
        }
    }

    /** The current phase ran out of time: a random stat, or the top card. Continues at the deadline's logical time. */
    private void timeout() {
        Instant at = deadline;
        switch (phase) {
            case AWAIT_CHOOSE_CALLER -> {
                commit(seats.get(callerSeat - 1), 0);
                pendingChoosers.clear();
                out.add(new GameEvent.CardChosen(callerSeat, true));
                enterAwaitCall(at);
            }
            case AWAIT_CALL -> {
                StatDefinition random = stats.get(rnd.nextInt(stats.size()));
                stat = random;
                out.add(new GameEvent.StatCalled(callerSeat, random.key(), true));
                enterChoose(Phase.AWAIT_CHOOSE_OTHERS, at);
            }
            case AWAIT_CHOOSE_OTHERS, CLASH_AWAIT_CHOOSE -> {
                for (int s : List.copyOf(pendingChoosers)) {
                    commit(seats.get(s - 1), 0);
                    out.add(new GameEvent.CardChosen(s, true));
                }
                pendingChoosers.clear();
                phase = phase == Phase.AWAIT_CHOOSE_OTHERS ? Phase.RESOLVE : Phase.CLASH_RESOLVE;
                deadline = null;
            }
            case CLASH_AWAIT_CALL -> applyClashStat(null, stats.get(rnd.nextInt(stats.size())), true, at);
            default -> throw new IllegalStateException("A timeout in phase " + phase);
        }
        drive(at);
    }

    /** What a CPU may know: its own cards (also the one already in play), how many opponents are in the round, the pot. */
    private CpuPlayer.Situation situation(Seat seat) {
        List<Card> own = new ArrayList<>(seat.hand);
        if (inFlight.containsKey(seat.number)) own.add(inFlight.get(seat.number));
        return new CpuPlayer.Situation(own, Math.max(0, contenders.size() - 1), pot.size());
    }

    private Instant turnDeadline(Instant at) {
        return settings.turnTimeoutSeconds() == null ? null : at.plusSeconds(settings.turnTimeoutSeconds());
    }

    // ------------------------------------------------------------------------------------------ small helpers

    private boolean over() {
        return phase == Phase.GAME_OVER;
    }

    private void requireRunning() {
        if (over()) throw new GameRuleException(GameRuleException.Kind.GAME_OVER, "The game is over");
    }

    private Seat seatOrThrow(int number) {
        if (number < 1 || number > seats.size()) throw new GameRuleException(GameRuleException.Kind.BAD_INPUT, "No such seat: " + number);
        return seats.get(number - 1);
    }

    private StatDefinition statOrThrow(String key) {
        for (StatDefinition s : stats) if (s.key().equals(key)) return s;
        throw new GameRuleException(GameRuleException.Kind.BAD_INPUT, "Unknown stat: " + key);
    }

    private static int indexOfCard(Collection<Card> hand, int cardId) {
        int i = 0;
        for (Card c : hand) {
            if (c.id() == cardId) return i;
            i++;
        }
        return -1;
    }

    private List<GameEvent> drain() {
        List<GameEvent> events = List.copyOf(out);
        out.clear();
        return events;
    }
}
