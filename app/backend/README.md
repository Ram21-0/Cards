# Cards — backend (Java / Spring Boot + Neon Postgres)

What exists today: project skeleton + the **database connector**. Everything else is yours to write —
see [PLAN.md](PLAN.md) for what to build and in what order.

## 1. Connect Neon (one-time setup)

1. **Create an account** at <https://neon.tech> (GitHub/Google/email) and create a **project**.
   Pick the region closest to where the server will run; Postgres 16 or 17 is fine.
2. **Get the connection string:** project dashboard → **Connect**. Choose your branch (`main`), the
   default database (`neondb`) and role, and **untick "Connection pooling"** so you get the *direct*
   host (Flyway migrations need a direct connection; the pooled `-pooler` host can be used for the app later).
   Copy the string — it looks like  
   `postgresql://USER:PASSWORD@ep-xxxx.REGION.aws.neon.tech/neondb?sslmode=require`
3. **Save it locally:**
   ```bash
   cd app/backend
   # .env.local and .env.prod already exist (git-ignored — never commit them).
   # Edit .env.local and paste your Neon *dev* branch string after DATABASE_URL=   (no quotes)
   # Edit .env.prod only if you want to run against prod from your machine:  APP_ENV=prod mvn spring-boot:run
   ```
4. **Install tools** (JDK 21+ is enough; you have 25): `brew install maven`
5. **Run:**
   ```bash
   mvn spring-boot:run
   ```
   Expected log lines: Flyway applying `V1__init.sql`, then  
   `Database connected: db=neondb, PostgreSQL 16.x ...`
   The first start can take a few seconds if Neon's compute was asleep.
6. **Check in Neon:** console → **Tables** (or SQL Editor: `select * from flyway_schema_history;`) —
   you should see `cards`, `players`, `games`.

Troubleshooting
- `password authentication failed` → re-copy the string; special characters are fine (they're URL-decoded).
- `SSL` errors → keep `sslmode=require`.
- Slow first request → free-tier compute auto-suspends after ~5 min idle; the pool timeout is 30 s to allow for wake-up.
- Leaked the password? Neon console → Roles → reset password.
- In production set `DATABASE_URL` as an environment variable on the host instead of using `.env.prod`.
- In IntelliJ the app reads `.env.local` only if the run configuration's working directory is `app/backend`; otherwise add `DATABASE_URL` under Environment variables (use `APP_ENV=prod` to switch files).

## Lombok
Added (`@Getter`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`, …). Two things to know:
- The pom pins Lombok 1.18.42 and lists it under `annotationProcessorPaths` — needed on JDK 23+, which no longer
  runs annotation processors from the classpath automatically.
- In IntelliJ: **Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable annotation processing**.
  (IntelliJ 2023.1+ bundles the Lombok plugin.) Reload Maven after pulling pom changes.

## 2. Code overview

```
src/main/java/com/cards
├── CardsApplication.java          entry point
├── config/                        DatabaseConfig (Neon/local URL -> pool), ConnectionCheck, WebConfig (CORS)
├── common/                        domain model, no Spring: Deck, StatDefinition, Stat, Card, Game, GamePlayer, Player,
│                                  enums (DbEnum codes: Visibility, ComparisonStrategy, ValueType, SeatKind, GameStatus,
│                                  DifficultyLevel) + Outcome, NotFoundException, Util
└── service/
    ├── controller/                HTTP layer: HealthController, DeckController (+ Game/Player stubs),
    │   └── dto/                   response records (what the JSON looks like), ApiExceptionHandler (errors -> JSON)
    ├── service/                   business rules: DeckService (visibility), Game/Player stubs
    └── db/                        SQL via JdbcTemplate, rows -> model classes: DeckRepository, CardRepository (+ stubs)

src/main/resources
├── application.yml                common settings; imports local/ or prod/ + the .env file
├── local/ , prod/                 per-environment non-secret settings (log level, CORS origins)
└── db/migration/V1__init.sql      schema + built-in Wrestling deck

src/test/java/com/cards/common     ModelTest
```

Request flow: `controller` (HTTP, DTOs) → `service` (rules) → `db` (SQL) → `common` model classes.
Working today: `GET /api/health`, `GET /api/decks`, `GET /api/decks/{id}`, `GET /api/decks/{id}/cards`
(the cards table is empty until you seed it). Everything marked "stub" is a placeholder for the next phases in PLAN.md.

How the pieces fit: `DatabaseConfig` defines the one `DataSource` bean → Spring Boot's Flyway runs the
migrations with it → anything you write that takes a `DataSource` or `JdbcTemplate` gets the same pool.

You will add (suggested package layout, details in PLAN.md):

```
com.cards
├── card/      Card (record), CardRepository (JdbcTemplate), CardController   GET /api/cards
├── player/    Player, PlayerRepository, PlayerController                     POST /api/players
├── game/      Engine (port of frontend/js/engine.js), GameService, GameController
└── web/       CORS config, error handling
```
