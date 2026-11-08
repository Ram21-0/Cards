-- Card-game schema: decks define their own stats; games are played with a deck.
-- Enum-like columns are smallint codes that mirror the Java DbEnum constants in com.cards.common:
--   visibility          0 PRIVATE, 1 UNLISTED, 2 PUBLIC                  (Visibility)
--   value_type          0 INT, 1 DECIMAL                                 (ValueType)
--   comparison_strategy 0 HIGHER_WINS, 1 LOWER_WINS                      (ComparisonStrategy)
--   mode                0 CPU, 1 PVP                                     (GameMode)
--   difficulty          0 easy, 1 medium, 2 hard                         (DifficultyLevel)
--   status              0 WAITING, 1 IN_PROGRESS, 2 FINISHED, 3 ABANDONED (GameStatus)
--   result              0 WIN, 1 LOSS, 2 DRAW                            (GameResult)
-- Single migration for now (nothing is deployed). Edit freely until it runs on a shared database;
-- after that add V2__..., never edit this file.

create table players (
    id         uuid primary key default gen_random_uuid(),
    username   text not null unique,
    created_at timestamptz not null default now()
);

-- ===================== decks =====================
-- A set of cards that can be played. Owns the definition of what its cards hold (deck_stats).
-- visibility: 2 public = listed for everyone · 1 unlisted = anyone with the link · 0 private = owner only
-- owner_id is null for platform-provided decks (e.g. the built-in Wrestling deck).
create table decks (
    id          uuid primary key default gen_random_uuid(),
    name        text not null,
    description text,
    visibility  smallint not null default 0 check (visibility in (0, 1, 2)),
    owner_id    uuid references players (id) on delete cascade,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create index decks_owner_idx      on decks (owner_id);
create index decks_visibility_idx on decks (visibility);

-- The stats every card in the deck holds, and how each is compared (direction lives here, in the DB).
create table deck_stats (
    id                  serial primary key,
    deck_id             uuid     not null references decks (id) on delete cascade,
    key                 text     not null,        -- 'height', 'runs', ... (key inside cards.stats)
    label               text     not null,        -- shown on the card
    unit                text,                     -- 'lbs', 'in', '%' ... (nullable)
    value_type          smallint not null default 0 check (value_type in (0, 1)),
    comparison_strategy smallint not null check (comparison_strategy in (0, 1)),
    display_order       smallint not null default 0,   -- order stats appear on the card
    unique (deck_id, key)
);

-- Cards. Anything comparable goes in stats (keys must match deck_stats.key — validated in code);
-- display-only extras (e.g. wrestling brand colour) go in meta.
create table cards (
    id      serial primary key,
    deck_id uuid  not null references decks (id) on delete cascade,
    name    text  not null,
    img_url text,
    stats   jsonb not null check (jsonb_typeof(stats) = 'object'),   -- {"rank": 12, "fight": 44, ...}
    meta    jsonb not null default '{}'::jsonb check (jsonb_typeof(meta) = 'object'),
    unique (deck_id, name)
);
create index cards_deck_idx on cards (deck_id);

-- ===================== games =====================
-- One play-through with a deck. join_code is the shareable part: the join link is built in code as
--   <frontend base url>/join/<join_code>
-- (the full URL is not stored because the domain differs per environment).
create table games (
    id          uuid primary key default gen_random_uuid(),
    join_code   text not null unique check (join_code ~ '^[A-Z0-9]{6,8}$'),
    deck_id     uuid not null references decks (id),
    host_id     uuid references players (id),                 -- null = anonymous host
    mode        smallint not null default 0 check (mode in (0, 1)),
    difficulty  smallint check (difficulty in (0, 1, 2)),          -- cpu mode only
    status      smallint not null default 0 check (status in (0, 1, 2, 3)),
    rounds      int,
    created_at  timestamptz not null default now(),
    started_at  timestamptz,
    finished_at timestamptz,
    check (mode = 1 or difficulty is not null)
);
create index games_deck_idx   on games (deck_id);
create index games_host_idx   on games (host_id, created_at desc);
create index games_status_idx on games (status);

-- Who is in the game (1 human for cpu mode, 2 for pvp). CPU opponent is implied by games.mode.
create table game_players (
    game_id   uuid     not null references games (id) on delete cascade,
    player_id uuid     not null references players (id),
    seat      smallint not null check (seat in (1, 2)),
    result    smallint check (result in (0, 1, 2)),
    joined_at timestamptz not null default now(),
    primary key (game_id, seat),
    unique (game_id, player_id)
);
create index game_players_player_idx on game_players (player_id, result);

-- ===================== reference data =====================
-- Built-in public Wrestling deck (same rules as the Android app / frontend engine).
-- Height is stored as TOTAL INCHES (feet*12 + inches) so it compares as a plain number.
insert into decks (name, description, visibility, owner_id)
values ('Wrestling', 'The original wrestling cards', 2, null);

insert into deck_stats (deck_id, key, label, unit, value_type, comparison_strategy, display_order)
select d.id, s.key, s.label, s.unit, s.value_type, s.comparison_strategy, s.display_order
from decks d,
     (values ('rank',   'Rank',   null,  0, 1, 1),   -- INT,     LOWER_WINS
             ('fight',  'Fight',  null,  0, 0, 2),   -- INT,     HIGHER_WINS
             ('chest',  'Chest',  'in',  1, 0, 3),   -- DECIMAL, HIGHER_WINS
             ('biceps', 'Biceps', 'in',  1, 0, 4),
             ('height', 'Height', 'in',  0, 0, 5),
             ('weight', 'Weight', 'lbs', 0, 0, 6))
         as s (key, label, unit, value_type, comparison_strategy, display_order)
where d.name = 'Wrestling' and d.owner_id is null;
