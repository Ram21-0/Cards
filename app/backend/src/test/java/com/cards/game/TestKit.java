package com.cards.game;

import com.cards.TestClock;
import com.cards.common.Card;
import com.cards.common.ComparisonStrategy;
import com.cards.common.DisplayFormat;
import com.cards.common.Stat;
import com.cards.common.StatDefinition;
import com.cards.common.ValueType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/** Small builders so a test can say exactly which cards sit where. */
final class TestKit {

    static final UUID DECK = UUID.randomUUID();
    /** "power": higher wins. "speed": LOWER wins (like rank). */
    static final StatDefinition POWER = stat("power", ComparisonStrategy.HIGHER_WINS, 1);
    static final StatDefinition SPEED = stat("speed", ComparisonStrategy.LOWER_WINS, 2);
    static final List<StatDefinition> STATS = List.of(POWER, SPEED);

    /** CHOOSE off, so a hand of any size just plays its top card. */
    static final GameSettings NO_CHOOSE = new GameSettings(500, 0, 5, null);
    static final GameSettings DEFAULT = GameSettings.defaults();

    private TestKit() {
    }

    static StatDefinition stat(String key, ComparisonStrategy strategy, int order) {
        return StatDefinition.builder().deckId(DECK).key(key).label(key).valueType(ValueType.INT)
                .comparisonStrategy(strategy).displayFormat(DisplayFormat.PLAIN).displayOrder(order).build();
    }

    static Card card(int id, int power, int speed) {
        return Card.builder().id(id).deckId(DECK).name("C" + id)
                .stats(List.of(new Stat("power", BigDecimal.valueOf(power)), new Stat("speed", BigDecimal.valueOf(speed))))
                .metadata(Map.of()).build();
    }

    /** A card whose speed is a neutral 5. */
    static Card card(int id, int power) {
        return card(id, power, 5);
    }

    static List<SeatSpec> humans(int n) {
        List<SeatSpec> specs = new ArrayList<>();
        for (int i = 0; i < n; i++) specs.add(SeatSpec.human());
        return specs;
    }

    @SafeVarargs
    static List<List<Card>> hands(List<Card>... hands) {
        return List.of(hands);
    }

    static List<Card> hand(Card... cards) {
        return new ArrayList<>(List.of(cards));
    }

    static GameEngine engine(GameSettings settings, List<SeatSpec> specs, int caller, TestClock clock, RandomGenerator rnd,
                             List<List<Card>> hands) {
        GameEngine e = GameEngine.withHands(STATS, specs, settings, hands, caller, 0, rnd, clock);
        e.pollEvents();                                    // the opening events are not what the next command caused
        return e;
    }

    /** Humans only, a seeded generator, a fresh clock. */
    static GameEngine humansEngine(GameSettings settings, int caller, List<List<Card>> hands) {
        return engine(settings, humans(hands.size()), caller, TestClock.atNow(), new java.util.SplittableRandom(1), hands);
    }

    static List<Integer> ids(List<Card> cards) {
        return cards.stream().map(Card::id).collect(Collectors.toList());
    }

    static List<Integer> ids(Card... cards) {
        return ids(List.of(cards));
    }

    /** A card id list with its first {@code n} entries (the top of a pile). */
    static List<Integer> top(GameEngine engine, int seat, int n) {
        return ids(engine.handOf(seat)).subList(0, n);
    }

    /** The last {@code n} card ids of a pile (what a winner just received at the bottom). */
    static List<Integer> bottom(GameEngine engine, int seat, int n) {
        List<Integer> all = ids(engine.handOf(seat));
        return all.subList(all.size() - n, all.size());
    }

    @SuppressWarnings("unchecked")
    static <E extends GameEvent> List<E> only(List<GameEvent> events, Class<E> type) {
        return events.stream().filter(type::isInstance).map(e -> (E) e).collect(Collectors.toList());
    }

    /** An event as a stable string (cards by id) for comparing whole games. */
    static String describe(GameEvent e) {
        if (e instanceof GameEvent.Revealed r) {
            return "Revealed" + r.cards().entrySet().stream().map(en -> en.getKey() + "=" + en.getValue().id()).collect(Collectors.toList());
        }
        return e.toString();
    }

    /** A generator that always answers 0: every random choice takes its first/lowest/shortest option. */
    static RandomGenerator zeros() {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }
        };
    }
}
