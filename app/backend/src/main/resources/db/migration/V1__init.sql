-- Starting schema. Edit freely BEFORE the first successful run; after that add V2__..., never edit V1.

create table cards (
    id      serial primary key,
    name    text         not null,
    brand   text         not null default 'raw' check (brand in ('raw', 'sd')),
    img_url text,
    rank    int          not null unique,   -- lower rank wins
    fight   int          not null,
    chest   numeric(5,1) not null,
    biceps  numeric(5,1) not null,
    feet    int          not null,
    inches  int          not null check (inches between 0 and 11),
    weight  int          not null
);

create table players (
    id         uuid primary key default gen_random_uuid(),
    username   text not null unique,
    created_at timestamptz not null default now()
);

create table games (
    id          uuid primary key default gen_random_uuid(),
    player_id   uuid references players (id),
    difficulty  text not null check (difficulty in ('easy', 'normal', 'hard')),
    result      text check (result in ('win', 'loss', 'draw')),
    rounds      int,
    started_at  timestamptz not null default now(),
    finished_at timestamptz
);
create index games_player_idx on games (player_id, finished_at desc);
