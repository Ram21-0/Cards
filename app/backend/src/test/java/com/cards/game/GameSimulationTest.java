package com.cards.game;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.common.ComparisonStrategy;
import com.cards.common.DifficultyLevel;
import com.cards.common.Stat;
import com.cards.common.StatDefinition;
import com.cards.game.GameEvent.CardChosen;
import com.cards.game.GameEvent.ClashStarted;
import com.cards.game.GameEvent.GameOver;
import com.cards.game.GameEvent.PotCarried;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Seeded random games: 2-8 seats, humans and CPUs of every difficulty and strategy, small value ranges so ties and
 * clashes are common, caps small enough to be hit. At every step no card may be lost or duplicated, no seat may see
 * another seat's cards, and every game must end with a complete, sensible set of placements.
 */
class GameSimulationTest {

    private static final UUID DECK = UUID.randomUUID();
    private static final List<StatDefinition> STATS = List.of(
            TestKit.stat("power", ComparisonStrategy.HIGHER_WINS, 1),
            TestKit.stat("speed", ComparisonStrategy.LOWER_WINS, 2),
            TestKit.stat("luck", ComparisonStrategy.HIGHER_WINS, 3));

    /** What happened in one simulated game. */
    private record Result(List<String> log, Map<Integer, Integer> placements, boolean cap, int rounds, int seats,
                          int clashes, int deepestClash, int carries, int chooses, int steps) {
    }

    private static Card simCard(int id, int maxValue, SplittableRandom r) {
        return Card.builder().id(id).deckId(DECK).name("C" + id).metadata(Map.of())
                .stats(List.of(new Stat("power", BigDecimal.valueOf(r.nextInt(maxValue + 1))),
                        new Stat("speed", BigDecimal.valueOf(r.nextInt(maxValue + 1))),
                        new Stat("luck", BigDecimal.valueOf(r.nextInt(maxValue + 1)))))
                .build();
    }

    private static CpuProfile randomProfile(SplittableRandom r) {
        CpuStrategy[] all = CpuStrategy.values();
        return CpuProfile.of(all[r.nextInt(all.length)], r.nextInt(3) == 0 ? 0 : r.nextDouble());
    }

    static Result play(long seed) {
        SplittableRandom setup = new SplittableRandom(seed);
        int n = 2 + setup.nextInt(7);
        List<SeatSpec> specs = new ArrayList<>();
        DifficultyLevel[] levels = DifficultyLevel.values();
        for (int i = 0; i < n; i++) {
            specs.add(setup.nextInt(100) < 55 ? SeatSpec.cpu(levels[setup.nextInt(levels.length)]) : SeatSpec.human());
        }
        int maxValue = new int[]{2, 4, 9}[setup.nextInt(3)];
        List<Card> deck = new ArrayList<>();
        int deckSize = n + setup.nextInt(41);
        for (int i = 1; i <= deckSize; i++) deck.add(simCard(i, maxValue, setup));
        GameSettings settings = new GameSettings(20 + setup.nextInt(181), setup.nextInt(6), 2 + setup.nextInt(9),
                setup.nextBoolean() ? null : 5 + setup.nextInt(26));
        CpuProfiles profiles = CpuProfiles.of(randomProfile(setup), randomProfile(setup), randomProfile(setup), randomProfile(setup));

        TestClock clock = TestClock.atNow();
        GameEngine e = GameEngine.start(deck, STATS, specs, settings, profiles, new SplittableRandom(seed * 31 + 7), clock);
        List<String> log = new ArrayList<>();
        List<GameEvent> all = new ArrayList<>();
        record(e.pollEvents(), log, all);

        SplittableRandom bot = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
        int steps = 0;
        while (!e.isOver()) {
            if (++steps > 200_000) fail("seed " + seed + ": the game did not finish in 200000 steps (round " + e.round() + ", phase " + e.phase() + ")");
            e.checkInvariants();
            assertNobodySeesAnothersCards(e, n, seed);

            List<Integer> humansActing = e.actingSeats().stream().filter(s -> !specs.get(s - 1).isCpu()).toList();
            boolean act = !humansActing.isEmpty() && (bot.nextInt(100) < 80 || e.nextWakeup().isEmpty());
            List<GameEvent> events;
            try {
                if (act) {
                    int seat = humansActing.get(bot.nextInt(humansActing.size()));
                    PlayerView view = e.view(seat);
                    events = view.canChoose()
                            ? e.choose(seat, view.ownHand().get(bot.nextInt(view.ownHand().size())).id())
                            : e.call(seat, STATS.get(bot.nextInt(STATS.size())).key());
                } else {
                    Instant wake = e.nextWakeup().orElseThrow(() ->
                            new AssertionError("seed " + seed + ": stuck - nothing can act and nothing to wait for (phase " + e.phase() + ")"));
                    if (wake.isAfter(clock.instant())) clock.set(wake);
                    events = e.tick(clock.instant());
                }
            } catch (GameRuleException ex) {
                throw new AssertionError("seed " + seed + ": a legal-looking move was refused in phase " + e.phase() + ": " + ex.getMessage(), ex);
            }
            record(events, log, all);
        }
        e.checkInvariants();

        // ---- the ending
        long gameOvers = all.stream().filter(ev -> ev instanceof GameOver).count();
        assertEquals(1, gameOvers, "seed " + seed + ": exactly one GameOver");
        assertTrue(all.get(all.size() - 1) instanceof GameOver, "seed " + seed + ": GameOver is the last event");
        GameOver over = (GameOver) all.get(all.size() - 1);
        Map<Integer, Integer> placements = e.placements();
        assertEquals(over.placements(), placements, "seed " + seed);
        assertEquals(n, placements.size(), "seed " + seed + ": every seat is placed: " + placements);
        for (Entry<Integer, Integer> p : placements.entrySet()) {
            assertTrue(p.getValue() >= 1 && p.getValue() <= n, "seed " + seed + ": placement in 1..n: " + placements);
        }
        assertTrue(placements.containsValue(1), "seed " + seed + ": somebody is first: " + placements);
        assertTrue(e.round() <= settings.maxRounds(), "seed " + seed + ": round " + e.round() + " beyond the cap " + settings.maxRounds());
        if (over.endedByRoundCap()) assertEquals(settings.maxRounds(), e.round(), "seed " + seed + ": the cap ends the game at the cap");
        else assertTrue(e.round() < settings.maxRounds() || placements.values().stream().filter(v -> v == 1).count() >= 1);

        int clashes = (int) all.stream().filter(ev -> ev instanceof ClashStarted).count();
        int deepest = all.stream().filter(ev -> ev instanceof ClashStarted).mapToInt(ev -> ((ClashStarted) ev).level()).max().orElse(0);
        int carries = (int) all.stream().filter(ev -> ev instanceof PotCarried).count();
        int chooses = (int) all.stream().filter(ev -> ev instanceof CardChosen c && !c.auto()).count();
        return new Result(log, placements, over.endedByRoundCap(), e.round(), n, clashes, deepest, carries, chooses, steps);
    }

    private static void record(List<GameEvent> events, List<String> log, List<GameEvent> all) {
        for (GameEvent ev : events) {
            log.add(TestKit.describe(ev));
            all.add(ev);
        }
    }

    /** §8: a view holds only cards of its own seat (the hand and the card it put in play). */
    private static void assertNobodySeesAnothersCards(GameEngine e, int seats, long seed) {
        for (int seat = 1; seat <= seats; seat++) {
            PlayerView view = e.view(seat);
            List<Integer> mine = new ArrayList<>(TestKit.ids(e.handOf(seat)));
            if (view.committed() != null) mine.add(view.committed().id());
            for (Card c : view.ownHand()) {
                assertTrue(mine.contains(c.id()), "seed " + seed + ": seat " + seat + " is shown card " + c.id() + " that is not theirs");
            }
            assertEquals(seats, view.handSizes().size());
        }
    }

    // ---------------------------------------------------------------- the tests

    @Test
    void thousandsOfRandomGamesAllTerminateWithCardsConservedAndNothingLeaked() {
        int games = 3000;
        int capped = 0, natural = 0, withClash = 0, deep = 0, carried = 0, withChoose = 0;
        int[] bySeats = new int[9];
        for (long seed = 0; seed < games; seed++) {
            Result r = play(seed);
            if (r.cap()) capped++; else natural++;
            if (r.clashes() > 0) withClash++;
            if (r.deepestClash() >= 3) deep++;
            if (r.carries() > 0) carried++;
            if (r.chooses() > 0) withChoose++;
            bySeats[r.seats()]++;
        }
        // the simulation really exercised the interesting rules
        assertTrue(capped > 50, "round cap hit in " + capped + " games");
        assertTrue(natural > 500, "decided naturally in " + natural + " games");
        assertTrue(withClash > 500, "clashes in " + withClash + " games");
        assertTrue(deep > 20, "repeated clashes (level 3+) in " + deep + " games");
        assertTrue(carried > 10, "carried pots in " + carried + " games");
        assertTrue(withChoose > 300, "CHOOSE used in " + withChoose + " games");
        for (int seats = 2; seats <= 8; seats++) assertTrue(bySeats[seats] > 100, seats + " seats in " + bySeats[seats] + " games");
    }

    @Test
    void aGameReplaysExactlyFromItsSeed() {
        for (long seed : new long[]{0, 1, 2, 17, 99, 1234, 2999}) {
            Result a = play(seed), b = play(seed);
            assertEquals(a.log(), b.log(), "seed " + seed + ": same events");
            assertEquals(a.placements(), b.placements(), "seed " + seed);
            assertEquals(a.steps(), b.steps(), "seed " + seed);
        }
        assertTrue(!play(1).log().equals(play(2).log()), "different seeds give different games");
    }

    @Test
    void aLongAllCpuGameRunsInsideOneCommandWithoutGrowingTheStack() {
        // distinct values everywhere: no ties, so CPUs play the whole game without ever waiting for time to pass
        List<Card> deck = new ArrayList<>();
        for (int i = 1; i <= 54; i++) {
            deck.add(Card.builder().id(i).deckId(DECK).name("C" + i).metadata(Map.of())
                    .stats(List.of(new Stat("power", BigDecimal.valueOf(i)), new Stat("speed", BigDecimal.valueOf(i * 3)),
                            new Stat("luck", BigDecimal.valueOf((i * 7) % 55 + 100 * i))))
                    .build());
        }
        GameSettings longGame = new GameSettings(5000, 3, 5, null);
        GameEngine e = GameEngine.start(deck, STATS, List.of(SeatSpec.cpu(DifficultyLevel.hard), SeatSpec.cpu(DifficultyLevel.medium),
                SeatSpec.cpu(DifficultyLevel.easy)), longGame, new SplittableRandom(3), TestClock.atNow());
        assertTrue(e.isOver(), "everything happened inside the constructor");
        assertTrue(e.round() > 20, "a real game was played: " + e.round() + " rounds");
        e.checkInvariants();
        assertEquals(3, e.placements().size());
    }
}
