# Deploying Cards (one jar, one origin) with Neon Postgres

This guide takes you from "works on my laptop" to a public URL. It assumes nothing; follow the sections in order.

**The plan in one paragraph.** `mvn -Pbundle-frontend package` produces a single Spring Boot jar that serves the website at `/` and the API at `/api`.
We wrap that jar in a Docker image (`Dockerfile`), run it on **Render** (recommended), and store data in **Neon** Postgres.
Because everything lives on one origin there is no CORS and the session cookie is first-party. Sign-in is Google Identity Services.
Liquibase creates/updates the schema automatically when the app starts. Secrets live only in the host's environment-variable store.

> **Facts about prices and limits in this guide come from my knowledge and can be out of date.** Each section says what to double-check
> on the vendor's page before you rely on it. Nothing in this repo has been deployed and no real Neon database was touched while writing this.

Contents: [1 Host](#1-choose-the-host) · [2 Prerequisites](#2-prerequisites) · [3 Neon](#3-neon-database) · [4 Build](#4-build) · [5 Env vars](#5-environment-variables) · [6 Deploy on Render](#6-deploy-on-render-recommended) · [7 Google](#7-google-cloud-authorize-the-production-origin) · [8 Checklist & smoke tests](#8-first-deploy-checklist-and-smoke-tests) · [9 Rollback](#9-rollback) · [10 Load card data (later)](#10-load-the-card-data-into-neon-later) · [11 Cost](#11-cost-summary) · [12 Security](#12-security-checklist) · [13 Second choice: Cloud Run](#13-second-choice-google-cloud-run) · [14 Troubleshooting](#14-troubleshooting)

---

## 1. Choose the host

What the app needs from a host: run a Docker image (or build a Maven project), ≥512 MB RAM for the JVM, HTTPS, environment variables with secrets, a health check.
It uses **plain HTTP polling** (no WebSockets/SSE) and keeps all state in Postgres (sessions included), so a host that stops and restarts the app, or runs it
scale-to-zero, loses nothing; the only cost is a slow first request.

| Host | Free / cheap tier (verify!) | Cold start with a JVM | RAM | Custom domain + HTTPS | Env vars / secrets | Verdict |
|---|---|---|---|---|---|---|
| **Render** (Docker web service) | Free web service: 512 MB, shared 0.1 CPU, sleeps after ~15 min idle, ~750 free instance-hours/month. Paid "Starter" ≈ $7/mo, always on. No card needed for free. | Free: ~1 min (container wake-up + JVM + Liquibase). Starter: none. | 512 MB (free and Starter) | Free `*.onrender.com` HTTPS; custom domains + auto TLS on free | Dashboard, `render.yaml` Blueprint with `sync: false` secrets | **Recommended**: simplest for a first-timer, GitHub-connected, health checks built in |
| **Google Cloud Run** | Scale-to-zero with a monthly free grant (vCPU-seconds, GiB-seconds, 2M requests); needs a billing account. | ~5-15 s (JVM) | You choose (set 512 MiB–1 GiB) | `*.run.app` HTTPS; custom domains via domain mapping/load balancer | Env vars + Secret Manager | **Second choice** (best free-tier economics; more console/CLI steps). You already use Google Cloud for sign-in. See [section 13](#13-second-choice-google-cloud-run) |
| **Fly.io** | No true free plan for new accounts (pay as you go); a `shared-cpu-1x` 512 MB machine is roughly $3-4/mo; machines can auto-stop. | A few seconds if auto-stop; none if always on | 256 MB machines are too small for this JVM; use 512 MB+ | Free `*.fly.dev` + custom domains with certs | `fly secrets set` (good) | Good, but CLI-first (`flyctl`) and card required |
| **Railway** | Trial credit, then usage-based with a ~$5/mo minimum plan. | None (always on, billed by usage) | Configurable | `*.up.railway.app` + custom domains | Dashboard variables, good UX | Fine, but not free long-term |
| **Koyeb** | Has had a free tier (small instance) and low-cost paid instances; free-tier terms have changed before. | Free instance sleeps when idle | Free instance has been small (check if it fits 512 MB) | `*.koyeb.app` + custom domains | Dashboard secrets | Check current terms; free RAM may be too small for a JVM |

**Pick: Render.** Reasons: it builds straight from the GitHub repo with the `Dockerfile`, a `render.yaml` can hold the variable *names*,
HTTPS is automatic, and 512 MB is enough with the memory flags below. Start on the **free** plan to prove everything works; move to **Starter (~$7/mo)**
when you want friends to join without the 1-minute first load. Because Neon also sleeps (see section 3), the first request after a quiet period on free+free
can take a minute or more. That is expected, not a bug.

**Double-check before committing:** render.com/pricing (free instance RAM/CPU, spin-down time, Starter price), neon.com/pricing (storage, compute hours, branch count),
cloud.google.com/run/pricing (free grant), fly.io/docs/about/pricing, railway.com/pricing, koyeb.com/pricing.

## 2. Prerequisites

You do these by hand (nothing here is automated):

1. A **GitHub** account with this repo pushed (Render pulls from GitHub). The `v2` branch holds the app; deploy that branch (or merge to `master` first).
2. A **Neon** account (neon.com) - section 3.
3. A **Render** account (render.com), connected to GitHub - section 6.
4. Your **Google OAuth Web client id** (already created) and access to its Google Cloud project - section 7.
5. Locally (optional but recommended): JDK 21+, Maven, and Docker Desktop if you want to test the image before pushing.

## 3. Neon database

### 3.1 Create the project and keep dev and prod apart
Neon separates environments with **branches** (copy-on-write copies of a database). Use one for each:

| Neon branch | Used by | Where its URL lives |
|---|---|---|
| default branch (`main`, or `production` in newer consoles; renaming it to `prod` is optional and cosmetic) | the deployed app | Render environment variable `DATABASE_URL` only |
| `dev` | your laptop | git-ignored `app/backend/.env.local` |

Steps:
1. Neon console → **Create project**. Name `cards`. Pick the region **closest to your Render region** (e.g. both in Singapore / AWS `ap-southeast-1`; or both US-East/Frankfurt). Latency between app and DB hits every request. Postgres 16 or 17.
2. Keep the default database (`neondb`) and role, or create a dedicated database `cards` under the prod branch (Branches → Databases). Either works; examples below use `neondb`.
3. Create the dev branch: **Branches → Create branch**, name `dev`, parent = default branch. **Do this before loading the real card data** if you want dev to start empty/small; later you can reset dev from prod (Branches → dev → Reset from parent).
4. Different roles/passwords per branch are a good habit: the roles are project-wide in Neon, but you can create a second role (e.g. `app_prod`) and use it only on Render, so rotating one does not break the other.

### 3.2 Connection strings: direct vs pooled
Console → select the branch → **Connect**. There is a toggle **Connection pooling**:

* **Direct** (toggle OFF): host looks like `ep-xxxx.REGION.aws.neon.tech`.
* **Pooled** (toggle ON): host contains `-pooler`: `ep-xxxx-pooler.REGION.aws.neon.tech` (PgBouncer in front).

**Use the DIRECT string for the app on Render.** The app runs Liquibase at startup on the same `DATABASE_URL`, and Liquibase takes a lock (`DATABASECHANGELOGLOCK`) that is only reliable on a direct, session-based connection.
The app's own pool is tiny (max 5 connections), so the pooler gives you nothing here. Use the pooled string only if you later move to a host that opens many short connections (e.g. dozens of replicas); then run migrations separately with the direct string.
For `psql` imports (section 10) always use the direct string too.

### 3.3 How the app parses `DATABASE_URL`
`app/backend/src/main/java/com/cards/config/DatabaseConfig.java`:

* Accepts Neon's format `postgresql://USER:PASSWORD@HOST/DB?sslmode=require` and converts it to `jdbc:postgresql://HOST:5432/DB?sslmode=require` with user and password split out (percent-encoded characters in the password are decoded; port defaults to 5432).
* Drops `channel_binding=...` from the query (the JDBC driver rejects it). Neon's copy-paste string includes it; that is fine.
* Adds `sslmode=require` for any non-local host if you omit it; an explicit `sslmode=` always wins. **Never set `sslmode=disable` in prod.**
* A string starting with `jdbc:` is also accepted, then user and password come from `DB_USER` / `DB_PASSWORD`. Not needed with Neon.
* Pool (`cards-pool`): max 5 connections, **min idle 0**, idle timeout 60 s, max lifetime 10 min, **connection timeout 30 s**. Idle connections are dropped so Neon can suspend; the 30 s timeout covers waking a suspended compute.

### 3.4 Free-tier behaviour to know
* The compute **suspends after ~5 minutes without connections** and wakes on the next connection (typically a second or two, sometimes more). The first request after idle is slow; later ones are normal. Nothing is lost.
* If the Render app also slept, the app boots first, then the database wakes during Liquibase/startup: the total can reach a minute. The 30 s Hikari timeout and the Render health-check window cover this.
* Free-tier limits (verify on neon.com/pricing): storage around 0.5 GB per project, a monthly compute-hours quota, a handful of branches, short point-in-time-restore history. A card game with image *URLs* (not image bytes) fits easily. **Do not store images in the database.**

## 4. Build

### 4.1 The jar
From the repo root:
```bash
cd app/backend
mvn -Pbundle-frontend package            # add -DskipTests to skip the unit tests
# -> target/cards-backend-0.0.1-SNAPSHOT.jar  (~40 MB, includes the frontend under /static)
```
Without `-Pbundle-frontend` the jar contains only the API (the local dev setup runs the frontend separately on :8081). `/config.js` is generated by the backend from `GOOGLE_CLIENT_ID`; the static `config.js` is deliberately excluded.
Needs JDK 21+ (Maven builds with `release 21`); IntelliJ's bundled Maven works if `mvn` isn't installed.

### 4.2 How the host builds it
Three options; this repo ships the first:

| Option | How | Notes |
|---|---|---|
| **Dockerfile (used)** | Render/Fly/Cloud Run build `./Dockerfile` with the **repo root as context** | Reproducible, JVM flags and non-root user baked in, works on every host |
| Buildpack | Cloud Run `--source`, Railway Nixpacks, Heroku-style | Would need the monorepo layout (`app/backend` + `../frontend`) configured, and no control of JVM flags except via env |
| Plain build command | Render "native" Java is not offered; some hosts let you run `mvn ... package` then `java -jar` | Not recommended |

The `Dockerfile` is multi-stage: stage 1 (`maven:3.9-eclipse-temurin-21`) runs `mvn -Pbundle-frontend package -DskipTests` after copying `app/backend` and `app/frontend`; stage 2 (`eclipse-temurin:21-jre-alpine`) copies only the jar, runs as the non-root user `cards`, and has a `HEALTHCHECK`.
`.dockerignore` keeps the Android project, Gradle files, `node_modules`, tests and any `.env*` files out of the build context (so secrets cannot leak into an image).

Test it on your machine if you have Docker (not verified in the sandbox where this guide was written; see the note at the end of section 8):
```bash
docker build -t cards .
# Use your DEV Neon branch below, never prod:
docker run --rm -p 8080:8080 \
  -e DATABASE_URL='postgresql://USER:PASS@HOST/DB?sslmode=require' \
  -e GOOGLE_CLIENT_ID='xxxx.apps.googleusercontent.com' \
  -e FRONTEND_ORIGIN='http://localhost:8080' cards
```
(Locally over plain `http` the `__Host-` Secure cookie will not stick, so sign-in will not work in this test. Everything else, including `/api/decks`, will.)

### 4.3 JVM memory for a 512 MB instance
Set in the image as `JAVA_TOOL_OPTIONS` (the JVM reads it automatically; override it as an env var on the host if needed):

```
-XX:MaxRAMPercentage=60   # heap ≈ 300 MB of 512; the rest is metaspace, thread stacks, code cache, native memory
-XX:+UseSerialGC          # lowest overhead GC, fine for 1-2 CPUs
-XX:TieredStopAtLevel=1   # C1 compiler only: less memory and faster warm-up
-XX:MaxMetaspaceSize=128m
-Xss512k                  # smaller thread stacks
-XX:+ExitOnOutOfMemoryError   # let the host restart a wedged JVM instead of limping
```
Memory is measured at the container level (`MaxRAMPercentage` uses the container limit). In a local test with no limits and default sizing the app used ~365 MB RSS; with the flags above it should sit comfortably under 512 MB, but **watch Render's Metrics tab after the first day**; if you see OOM restarts, lower `MaxRAMPercentage` to 50 or move to the 1 GB plan.

### 4.4 Health check
`GET /api/health` returns `{"db":true,"status":"ok"}` (HTTP 200) and checks the database connection. Used by `render.yaml` (`healthCheckPath`) and the Docker `HEALTHCHECK`.
Caveat: because it touches the database, a health check also keeps a free Neon compute awake if the host polls it every ~30 s. Render only health-checks during deploys and while running; if Neon compute-hours become a concern, point the host health check at `/` instead (static page, no DB).

## 5. Environment variables

These are the real names from `application.yml` and `prod/application.yml`. Spring also lets you override any property with an upper-case env var (`app.session.ttl-days` → `APP_SESSION_TTL_DAYS`).

| Name | Example | Secret? | Required | Where it comes from / notes |
|---|---|---|---|---|
| `APP_ENV` | `prod` | no | **yes** | Chooses `classpath:/prod/application.yml` (Secure `__Host-session` cookie, dev login OFF, errors hide details, INFO logs). Default is `local`, which is wrong for a server. The Dockerfile already sets it; `render.yaml` repeats it. |
| `DATABASE_URL` | `postgresql://app_prod:…@ep-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require` | **YES** | **yes** | Neon → prod branch → Connect → pooling OFF. App fails to start without it. |
| `GOOGLE_CLIENT_ID` | `1234567890-abc….apps.googleusercontent.com` | no (public id; keep per-env) | yes for sign-in | Google Cloud → Credentials → your OAuth Web client. Empty = sign-in answers 503 and `/config.js` has an empty id. Verifier rejects tokens for any other audience. |
| `FRONTEND_ORIGIN` | `https://cards.onrender.com` | no | **yes** | The exact public origin: scheme + host, no path, no trailing slash. Used for CORS allow-list (`app.cors.allowed-origins`) and join links (`app.frontend-base-url`). The app fails to start if unset in prod. Update it when you add a custom domain. |
| `PORT` | (Render sets `10000`) | no | no | Tomcat listens on `${PORT:8080}`. Do not set it on Render; the platform injects it. |
| `JAVA_TOOL_OPTIONS` | see section 4.3 | no | no | Baked into the image; override only to tune memory. |
| `APP_SESSION_TTL_DAYS` | `30` | no | no | `app.session.ttl-days`, default 30. Lower for stricter security. |
| `APP_SESSION_COOKIE_NAME` | `__Host-session` | no | no | Prod yml already sets it. Do not change: the `__Host-` prefix forces Secure + Path=/ + no Domain. |
| `APP_SESSION_COOKIE_SECURE` | `true` | no | no | Prod yml already sets `true`. Never set `false` in prod. |
| `DB_USER`, `DB_PASSWORD` | - | **yes** | no | Only used if `DATABASE_URL` starts with `jdbc:`. Leave unset with Neon. |
| `APP_DEV_LOGIN_ENABLED` | leave unset | - | no | Defaults to `false`; `POST /api/dev/login` must return 404 in prod. Never enable. |

Deliberately **not** used in prod: `.env.prod` files. The `DotEnvPostProcessor` only reads them if present; the image excludes them, so only real environment variables apply.

## 6. Deploy on Render (recommended)

1. Push your branch to GitHub (the repo must contain `Dockerfile`, `.dockerignore`, `render.yaml` at the root).
2. render.com → **New → Blueprint** → select the repo and branch. Render reads `render.yaml` and shows a service `cards` (Docker, plan free).
   (Alternative without Blueprint: **New → Web Service** → connect repo → Runtime **Docker**, Health Check Path `/api/health`, and add the variables from section 5 by hand.)
3. Render asks for the `sync: false` values: paste **`DATABASE_URL`** (direct Neon string) and **`GOOGLE_CLIENT_ID`**. For `FRONTEND_ORIGIN` enter a guess such as `https://cards.onrender.com` (the final URL is shown on the service page once created; names are unique, so you may get a suffix).
4. Choose the **region** nearest your Neon region (the `region:` line in `render.yaml`). Create. The first build takes several minutes (Maven downloads dependencies).
5. When it is live, note the exact URL, e.g. `https://cards-abcd.onrender.com`. If it differs from your `FRONTEND_ORIGIN` guess: service → **Environment** → fix `FRONTEND_ORIGIN` → save (triggers a redeploy).
6. Do section 7 (Google), then section 8 (smoke tests).
7. Custom domain (optional): service → Settings → Custom Domains → add `cards.yourdomain.com`, create the DNS CNAME Render shows, wait for the certificate, then set `FRONTEND_ORIGIN=https://cards.yourdomain.com` and add the same origin in Google (section 7). The `__Host-` cookie is host-bound, so everyone must use the one chosen host; players on the old `onrender.com` URL will have to sign in again.

`autoDeploy: false` means pushing to GitHub does not deploy; use **Manual Deploy → Deploy latest commit**. Switch it to `true` when you are comfortable.

## 7. Google Cloud: authorize the production origin

Google Cloud Console → **APIs & Services → Credentials** → click your **OAuth 2.0 Client ID (Web application)**:

1. **Authorized JavaScript origins** → **Add URI**: your exact production origin, e.g. `https://cards-abcd.onrender.com` (scheme + host, **no trailing slash, no path**). Add the custom domain too if you use one. Keep `http://localhost` and `http://localhost:8081` for development.
   *Authorized redirect URIs* are not needed: the app uses Google Identity Services with an ID token posted to `/api/auth/google`, not the redirect flow.
2. Save. Changes can take **a few minutes (up to hours, rarely)** to propagate.
3. **OAuth consent screen → Audience / Publishing status.** In **Testing** mode only the Test users you list (up to 100) can sign in, and Google may show "unverified" screens. For friends and family: either add them as test users, or click **Publish app** (status "In production"). The app only requests basic profile scopes (`openid email profile`), which do not need Google's verification review, but users may see an "unverified app" notice until you complete brand verification. Re-check what Google currently requires for your consent-screen type.
4. **Test:** open the production URL in a private window → click Google sign-in → choose an account → you should land signed in. If the button shows an error `origin_mismatch` or "Not a valid origin", the origin in step 1 differs from the address bar (http vs https, trailing slash, wrong subdomain) or has not propagated.
5. The client id in Google must equal `GOOGLE_CLIENT_ID` on Render (the `/config.js` the page loads is generated from it).

## 8. First-deploy checklist and smoke tests

**Before deploying**
- [ ] Neon prod branch exists; direct URL copied; `dev` branch exists and is what `.env.local` uses.
- [ ] Render env vars set: `APP_ENV=prod`, `DATABASE_URL`, `GOOGLE_CLIENT_ID`, `FRONTEND_ORIGIN`.
- [ ] Google origin added (section 7).
- [ ] Nothing secret committed: `git ls-files | grep -i '\.env'` shows only `*.example` files.

**What happens on first start:** the app connects to Neon, **Liquibase runs `001-init.yaml` and `002-auth.yaml`** (schema + the built-in Wrestling deck with **zero cards**), then Tomcat starts. In the Render logs look for `liquibase.command : Command execution complete`, `Started CardsApplication`, `Database connected: db=neondb`. Verify in Neon → SQL Editor: `select id, filename from databasechangelog;` shows both files. Later deploys apply only new changeSets.
If the first start times out, redeploy once; Liquibase is idempotent (a stale lock after a killed start: `delete from databasechangeloglock;` or `update databasechangeloglock set locked=false;` in the Neon SQL editor).

**Smoke tests** (replace `$URL`; the first call may take up to a minute while things wake up):
```bash
URL=https://cards-abcd.onrender.com
curl -i $URL/api/health          # 200 {"db":true,"status":"ok"}
curl -i $URL/                    # 200 text/html - the game's index page
curl    $URL/config.js           # window.CARDS_CONFIG = {"apiBase":"","googleClientId":"<your id>"};
curl    $URL/api/decks           # JSON list: the Wrestling deck with "cardCount":0 until you load data
curl -i $URL/api/me              # 401 (not signed in) - proves the auth filter works
curl -i -X POST $URL/api/dev/login -H 'Content-Type: application/json' -d '{"username":"x"}'   # must be 404 in prod
```
In the browser (private window):
1. Open `$URL`, sign in with Google. DevTools → Application → Cookies: a cookie named `__Host-session` with HttpOnly, Secure, SameSite=Lax.
2. Reload: still signed in (`/api/me` returns your profile).
3. Create a game (`POST /api/games` happens from the UI) and copy the join link: it must start with your `FRONTEND_ORIGIN`.
4. Join from a second browser/account. Until cards are loaded (section 10) the deck is empty, so gameplay itself cannot be tested yet; creation, join, and the lobby can.
5. Render → Logs: no stack traces; Metrics: memory comfortably below 512 MB.

**Note on verification:** while preparing this guide the jar was built with `mvn -Pbundle-frontend package` and started with `APP_ENV=prod`-style variables against a scratch local Postgres; `/`, `/config.js`, `/api/health` and `/api/decks` all answered from the single jar, Liquibase applied cleanly, `/api/me` gave 401 and `/api/dev/login` gave 404. **The Dockerfile itself was not built** (Docker daemon unavailable there), so the first Render build is its first real test; if it fails, the log shows the failing step.

## 9. Rollback

* **App version:** Render → service → **Events/Deploys** → pick a previous successful deploy → **Rollback** (or redeploy an older commit via Manual Deploy). Takes a few minutes.
* **Database schema:** Liquibase only moves forward at startup. Rolling the *app* back is safe as long as the newer migrations were additive (new tables/columns). **Rules: never edit a changeSet that has run in prod (checksum failure stops startup); add a new `00N-*.yaml` instead.** Each changeSet has a `rollback:` block for manual use against the direct URL if truly needed.
* **Safety net before risky migrations or data loads:** in Neon create a branch from prod (`prod-before-003`): instant, free copy-on-write snapshot. To go back, point `DATABASE_URL` to that branch (or Neon **Restore** to a past point in time; the free window is short, so do not rely on it).
* Once real player data exists, schedule an occasional `pg_dump` from the direct URL (see section 10 for the command style) kept outside Neon.

## 10. Load the card data into Neon (later)

> **PLACEHOLDER. Do not run anything here until the image URLs are confirmed.** This section lists options only; no real data is included, and the exact schema/columns must be read from `app/backend/src/main/resources/db/changelog/changes/001-init.yaml` at that time. The deck "Wrestling" already exists (seeded by Liquibase) with 0 cards.

**Where to run it:** against the **dev** branch first, check it in the app, then repeat on **prod**. Always use the **direct** connection string, with a `psql` client (`brew install libpq` or the postgresql package): `psql "postgresql://USER:PASS@ep-xxxx.REGION.aws.neon.tech/neondb?sslmode=require"` (never paste real strings into files in the repo).

Options:
1. **SQL file** (simplest, repeatable): a hand-written/generated `cards-seed.sql` with `INSERT ... ON CONFLICT DO NOTHING` statements; run `psql "$URL" -v ON_ERROR_STOP=1 -1 -f cards-seed.sql` (`-1` = one transaction, so a mistake loads nothing). Keep the file *outside* git if it contains copyrighted material you do not want public.
2. **CSV import:** `\copy cards(deck_id, name, image_url, …) FROM 'cards.csv' CSV HEADER` in `psql` (client-side copy, works with Neon). Stats may live in a separate table; load cards first, then stats.
3. **JSON import:** convert the existing `app/frontend/data` JSON to SQL/CSV with a small script (Node is already in the frontend tooling), or load with `jsonb_to_recordset` from a staging table.
4. **A Liquibase changeSet** (e.g. `003-seed-cards.yaml` with `loadData`/`sql`): versioned and applied automatically on deploy, good for a *fixed* card set; but it bakes data into the jar and a changed card then needs a new changeSet.
5. **Admin endpoint** later: not built; mention only if editing cards becomes frequent.

**Images: where to host them** (store only the **URL** in the database):
- Simplest/free: keep them in the repo under `app/frontend/assets/…` so they ship in the jar (served at `/assets/...`, same origin); fine for tens of small images (each deploy redeploys them, and the jar grows).
- Better as the set grows: a bucket + CDN: Cloudflare R2, Google Cloud Storage (public bucket), Backblaze B2, or Supabase/S3. Use **https** URLs; set long cache headers; keep original files in a backup.
- Hot-linking other sites' images is fragile (they can disappear or block you); confirm the licences for the images you use.
- Confirm the final URL pattern *before* loading, since the URL is what gets stored.

**Verification after loading:** `select count(*) from cards;`, then `curl $URL/api/decks` (the `cardCount`) and `curl $URL/api/decks/<deck id>/cards`. Make a Neon branch snapshot first (section 9).

## 11. Cost summary

| Item | Free setup | Comfortable setup |
|---|---|---|
| Render web service | $0 (sleeps after idle) | ~$7/mo (Starter, always on) |
| Neon | $0 (scale-to-zero, small storage) | $0 (free is enough) or paid plan if you outgrow it |
| Google sign-in | $0 | $0 |
| Domain (optional) | - | ~$10-15/year |
| Image hosting | $0 (in the jar) | $0-1/mo (R2/GCS at this scale) |
| **Total** | **$0/mo** | **~$7/mo** |

Re-check every figure on the vendors' pricing pages before you rely on them.

## 12. Security checklist

- [ ] Secrets (`DATABASE_URL`) exist only in Render's environment settings (and Neon). Not in git, not in `render.yaml`, not in the image (`.dockerignore` excludes `.env*`), not in chat/screenshots. `git ls-files | grep -i '\.env'` lists only `*.example`.
- [ ] HTTPS only: Render redirects HTTP→HTTPS and provides the certificate. Keep `FRONTEND_ORIGIN` as an `https://` origin.
- [ ] Cookies: in prod the session cookie is `__Host-session`, HttpOnly, Secure, SameSite=Lax (check in DevTools). Cross-site writes additionally need the `X-Requested-With` header (CSRF defence in `SessionAuthFilter`). Do not set `APP_SESSION_COOKIE_SECURE=false`.
- [ ] `APP_ENV=prod` so `/api/dev/login` returns 404 and error responses carry no stack traces/messages (verify with the smoke test).
- [ ] Neon: connections require SSL (`sslmode=require`, already the default in the app). **IP Allow lists are a paid Neon feature and Render free has no fixed outbound IPs, so the protection is SSL + a long random password**; use a dedicated `app_prod` role for Render (not your owner role) and never reuse the `dev` role/password.
- [ ] Rotate the Neon password: Neon → Roles → Reset password (or create a new role, switch Render's `DATABASE_URL`, redeploy, drop the old role). Do it immediately if the string ever appears in a log, chat, screenshot or commit, and every few months otherwise.
- [ ] Google: only your real origins in Authorized JavaScript origins; remove the unused ones. Rotate/regenerate the OAuth client if a *client secret* leaks (this app uses only the public client id, no secret).
- [ ] Container runs as non-root user `cards` (see `Dockerfile`).
- [ ] Turn on 2FA for GitHub, Render, Neon, Google Cloud.
- [ ] If the repo is public, double-check there is nothing sensitive in git history (`google-services.json` was already deleted from this repo, but old commits may contain it; rotate that Firebase/Google key if it was real).

## 13. Second choice: Google Cloud Run

Why: scale-to-zero with a real free grant, ~5-15 s cold start, same Google account as sign-in. Why not first: more moving parts (billing account, `gcloud`, Artifact Registry). Reuses the same `Dockerfile` unchanged (Cloud Run injects `PORT`, which the app reads).

```bash
gcloud auth login && gcloud config set project YOUR_PROJECT_ID
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com

# secret (paste the Neon DIRECT url when prompted / from stdin; do not put it in shell history)
printf '%s' "$DATABASE_URL" | gcloud secrets create cards-database-url --data-file=-

# from the repo root (builds with the Dockerfile via Cloud Build)
gcloud run deploy cards --source . --region asia-southeast1 --allow-unauthenticated \
  --memory 512Mi --cpu 1 --min-instances 0 --max-instances 2 --cpu-boost \
  --set-env-vars APP_ENV=prod,GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com,FRONTEND_ORIGIN=https://PLACEHOLDER \
  --set-secrets DATABASE_URL=cards-database-url:latest
# then read the printed URL (https://cards-xxxx-as.a.run.app), set the real origin and add it in Google:
gcloud run services update cards --region asia-southeast1 --update-env-vars FRONTEND_ORIGIN=https://cards-xxxx-as.a.run.app
```
Grant the Cloud Run service account `Secret Manager Secret Accessor` on the secret. Memory 512 MiB works with the flags in 4.3; use 1 GiB if you see restarts. `--cpu-boost` shortens JVM startup. Health: Cloud Run uses a TCP startup probe by default; add `--startup-probe`/HTTP probe to `/api/health` if desired. Custom domains: domain mapping (limited regions) or a load balancer. Fly.io alternative: `fly launch --no-deploy`, `fly secrets set DATABASE_URL=...`, set `internal_port = 8080`, `[[http_service.checks]] path = "/api/health"`, 512 MB VM; `fly deploy`.

## 14. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Render deploy fails at Maven step | Read the log; run `docker build` locally; ensure `Dockerfile` and `app/frontend` are committed on the deployed branch. |
| `DATABASE_URL is not set` / app exits at start | Variable missing or empty on the host. |
| `Could not resolve placeholder 'FRONTEND_ORIGIN'` | Set `FRONTEND_ORIGIN` (required in prod). |
| `password authentication failed` | Re-copy the string; the role/password is for the right branch? Reset password in Neon. |
| `Waiting for changelog lock` forever | You used the pooled string, or a previous start was killed: switch to the direct string; clear the lock row (section 8). |
| First request takes ~1 min | Render free wake-up + JVM + Neon wake-up. Upgrade to Starter to remove the first part. |
| Google button: `origin_mismatch` | Section 7, step 4. |
| Signed in but `/api/me` is 401 | Cookie not stored: open the site over `https://` on the exact `FRONTEND_ORIGIN` host; `__Host-` cookies are rejected on http or with another domain. |
| Out-of-memory restarts | Lower `MaxRAMPercentage` to 50 or go to a 1 GB plan. |
