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
   cp .env.example .env      # .env is git-ignored — never commit it
   # edit .env and paste your string after DATABASE_URL=   (no quotes)
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
- In production set `DATABASE_URL` as an environment variable on the host instead of a `.env` file.

## 2. Code overview

```
app/backend
├── pom.xml                         Spring Boot 3.5, Java 21, web + jdbc + postgres + flyway
├── .env.example  /  .env           DATABASE_URL (the .env copy is git-ignored)
└── src/main
    ├── java/com/cards
    │   ├── CardsApplication.java   entry point (@SpringBootApplication)            [provided]
    │   └── config
    │       ├── DatabaseConfig.java converts Neon's postgresql:// URL -> JDBC +     [provided]
    │       │                       HikariCP pool tuned for Neon's free tier
    │       └── ConnectionCheck.java logs "Database connected" at startup            [provided, deletable]
    └── resources
        ├── application.yml         loads .env, server port                          [provided]
        └── db/migration
            └── V1__init.sql        cards / players / games tables                   [provided, editable until first run]
```

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
