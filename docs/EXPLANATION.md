# ClaimFlow: the deep-dive explanation

This document explains **what** each part of ClaimFlow does, **why** it was built that way,
and **what else could have been done**. It also explains every Java/Spring idea the code uses,
in plain words, and ends with interview questions and short answers.

Read it top to bottom once. Then use the question bank at the end to practise out loud.

---

## Table of contents

1. [The business story](#1-the-business-story)
2. [Insurance words you must know](#2-insurance-words-you-must-know)
3. [How the code is organised](#3-how-the-code-is-organised)
4. [Follow one request through the code](#4-follow-one-request-through-the-code)
5. [Java and Spring concepts used (explained simply)](#5-java-and-spring-concepts-used-explained-simply)
6. [Milestone by milestone](#6-milestone-by-milestone)
7. [Tricky details worth knowing](#7-tricky-details-worth-knowing)
8. [Known limitations (say these before they ask)](#8-known-limitations-say-these-before-they-ask)
9. [Interview question bank](#9-interview-question-bank)
10. [Your 2-minute project pitch](#10-your-2-minute-project-pitch)

---

## 1. The business story

1. **Asha** buys a car insurance **policy**. It covers up to ₹10,00,000 (the *coverage limit*),
   and she pays the first ₹5,000 of any claim herself (the *deductible*).
2. Someone hits her car. She calls the insurer and reports it. This first report is called
   **FNOL: First Notice Of Loss**. A **claim** is created in status `FNOL`.
3. An **adjuster** (the person who checks claims) picks it up: `UNDER_REVIEW`.
4. At that moment the **fraud engine** runs. If anything looks odd (the claim is bigger than
   the policy limit, the policy only started 3 days ago, she filed 3 claims this month, or she
   waited 2 months to report), the claim jumps to `FLAGGED_FOR_INVESTIGATION` by itself.
5. An investigator either clears it (back to `UNDER_REVIEW`) or rejects it (`REJECTED`).
6. The adjuster approves an amount (`APPROVED`). The amount can't be more than
   *claimed minus deductible*, and never more than the coverage limit.
7. Finance pays it (`PAID`). `PAID` and `REJECTED` are final. Nothing can change after them.
8. Every status change is written to an **audit trail** (`claim_event`): who, when, from what, to what, why.
   Insurers are regulated, so "who approved this and why?" must always have an answer.

That is the whole product. Everything in the code serves this story.

---

## 2. Insurance words you must know

| Word | Meaning |
|---|---|
| **P&C** | Property & Casualty insurance: cars, homes, buildings. (Not life or health.) |
| **Policy** | The contract. Has a number, a type, dates, a coverage limit, and a deductible. |
| **Coverage limit** | The most the insurer will ever pay on one claim. |
| **Deductible** | The part the customer pays first. Claim ₹12,000, deductible ₹5,000 → insurer pays at most ₹7,000. |
| **FNOL** | First Notice Of Loss: the first report of an incident. |
| **Adjuster** | Employee who checks a claim and decides the payout. |
| **Lapsed / Cancelled** | Policy no longer active (not paid, or ended early). No new claims allowed. |
| **Guidewire** | The company whose products (PolicyCenter, ClaimCenter, BillingCenter) run many insurers. ClaimFlow is a tiny ClaimCenter. |

---

## 3. How the code is organised

### Layers

```
Controller  ->  Service  ->  Repository  ->  Database
 (HTTP)        (rules)        (SQL)
```

- **Controller**: turns HTTP into Java calls and back. Validates input shape. No business logic.
- **Service**: business rules and transactions. This is where the "brain" is.
- **Repository**: talks to the database. Spring Data writes most of the SQL for us.

Why layers? Each layer has one job, so each can be tested alone. Controllers are tested with a
mocked service (`@WebMvcTest`). Services are tested with mocked repositories (Mockito).

### Package by feature

```
com.claimflow
├── customer/       everything about customers
├── policy/         everything about policies
├── claim/          everything about claims (the biggest part)
├── fraud/          fraud rules engine
├── observability/  metrics
├── common/         shared errors + error handler
└── config/         small Spring config (Clock, OpenAPI)
```

The alternative is "package by layer" (`controllers/`, `services/`, `repositories/`).
Package by feature was chosen because when you work on claims, all the claim files sit together.
It also lets classes be *package-private*: `Claim.changeStatus(...)` has no `public`, so only
code in the `claim` package (the service) can change a claim's status. A controller can't.

### The database (Flyway `V1__init.sql`)

```
customer 1 ──< policy 1 ──< claim 1 ──< claim_event
                                  1 ──< claim_fraud_flag
```

- `claim.version` is used for optimistic locking.
- Indexes: `claim.status`, `claim.policy_id`, `policy.customer_id` (the columns we filter and join on).
- Sequences `policy_number_seq` and `claim_number_seq` create readable numbers like `CLM-2026-000045`.
- `CHECK` constraints repeat key rules in the DB (amounts > 0, `end_date > start_date`).
  It's defence in depth: even a bug or a manual SQL script can't store nonsense.

---

## 4. Follow one request through the code

**Request:** `POST /api/v1/claims/1/transitions` with
`{"toStatus": "UNDER_REVIEW", "reason": "Assigned"}` and header `X-Actor: adjuster.kim`.

1. **Spring MVC** matches the URL to `ClaimController.transition(...)`.
2. **Jackson** turns the JSON into a `TransitionRequest` record.
3. **`@Valid`** runs Bean Validation: `toStatus` must not be null, and `reason` must not be blank.
   If it fails → `MethodArgumentNotValidException` → `GlobalExceptionHandler` → **400** ProblemDetail
   with an `errors` list. The service is never called.
4. The controller calls `claimService.transition(1, request, "adjuster.kim")`.
5. `ClaimService` is really a **Spring proxy**. Because of `@Transactional`, the proxy **opens a
   DB transaction** before the method runs.
6. `findClaim(1)` loads the `Claim` entity. Hibernate now tracks it (it is *managed*).
   Its `version` is, say, 3.
7. `MDC.putCloseable("claimNumber", ...)` adds the claim number to every log line from here on.
8. `stateMachine.validate(FNOL, UNDER_REVIEW)` passes. An illegal move would throw
   `InvalidClaimTransitionException` → **409**.
9. Not an approval, so no amount check (and sending `approvedAmount` here would be a **422**).
10. `recordTransition(...)` sets the status, saves a `ClaimEvent` row, and bumps the
    `claims_transitions_total{from="FNOL",to="UNDER_REVIEW"}` counter.
11. Because this is FNOL → UNDER_REVIEW, `runFraudAssessment(claim)` runs:
    - `FraudAssessmentService` counts this customer's claims in the last 30 days (one SQL query),
      builds a `ClaimContext`, and asks each of the 4 `FraudRule` beans for an `Optional<FraudFlag>`.
    - If any flag comes back, the flags are added to the claim, the state machine allows
      UNDER_REVIEW → FLAGGED_FOR_INVESTIGATION, and a second event is written with actor
      `system:fraud-engine` and the reasons.
12. `claimRepository.saveAndFlush(claim)` sends the SQL now:
    `UPDATE claim SET status=?, version=4 WHERE id=1 AND version=3`.
    If another request changed the claim meanwhile, 0 rows match → Hibernate throws →
    Spring turns it into `ObjectOptimisticLockingFailureException` → **409 "Concurrent modification"**.
13. `ClaimResponse.from(claim)` builds the response DTO **inside** the transaction (needed because
    `open-in-view` is off and the policy is lazy-loaded).
14. The method returns. The proxy **commits**. If anything threw, the proxy **rolls back**,
    so neither the status change nor the event rows are saved. All or nothing.
15. Jackson writes the DTO as JSON with **200 OK**.

If you can tell this story in an interview, you understand the project.

---

## 5. Java and Spring concepts used (explained simply)

### Dependency Injection (DI) and beans
A **bean** is an object that Spring creates and manages. Classes marked `@Component`, `@Service`,
`@RestController`, or `@Configuration` become beans. **Dependency injection** means a class doesn't
build its own dependencies (`new ClaimRepository()`). It asks for them in its constructor,
and Spring passes them in. So in tests you can pass mocks instead. ClaimFlow uses
**constructor injection** everywhere. With one constructor, no `@Autowired` is needed, and fields can be `final`.

### `List<FraudRule>` injection
If a constructor asks for `List<FraudRule>`, Spring finds **every** bean that implements
`FraudRule` and passes them all. This is how the fraud engine is "pluggable": add a class with
`@Component implements FraudRule`, and it is picked up with no other change.

### `@Transactional`
Put it on a service method and Spring wraps the method in a **database transaction**: begin
before, commit after, roll back if a `RuntimeException` escapes. It works through a **proxy**:
Spring hands out a wrapper object that starts the transaction and then calls your real method.
Two traps to know:
- **Self-invocation**: if a method calls another `@Transactional` method *on the same object*
  (`this.other()`), the proxy is skipped, so the annotation on `other()` does nothing.
- **Checked exceptions don't roll back** by default (only unchecked ones do). All ClaimFlow
  exceptions extend `RuntimeException`.

`@Transactional(readOnly = true)` on the class makes reads cheaper (Hibernate skips dirty
checking). Write methods override it with a plain `@Transactional`.

### JPA, Hibernate and the entity lifecycle
**JPA** is the Java standard for mapping classes to tables. **Hibernate** is the implementation
Spring Boot uses. An **entity** (`@Entity`) is a class mapped to a table. An entity is always in one
of four states:
- **Transient**: just created with `new`, and Hibernate doesn't know it.
- **Managed**: loaded or saved inside a transaction. Hibernate **watches it for changes**
  (*dirty checking*): change a field, and an `UPDATE` is sent at flush/commit. You don't call "update".
- **Detached**: the transaction ended. Changes are no longer tracked.
- **Removed**: marked for delete.

That's why `ClaimService.transition` just calls `claim.changeStatus(...)`. The update happens
automatically. We call `saveAndFlush` only to force the SQL to run *now*, so a version conflict
is caught inside our method.

JPA also needs a **no-arg constructor**. We make it `protected` so our own code can't create
half-built entities by accident.

### Lazy loading and `open-in-view: false`
`@ManyToOne(fetch = LAZY)` means "don't load the policy until someone calls `claim.getPolicy()`".
It saves queries, but it only works **while the transaction is open**. Spring Boot's default
"open session in view" keeps the DB session open until the HTTP response is written. That hides
bugs and holds connections longer. We turned it **off**, so all DTO mapping happens inside services.

### N+1 queries
Loading 20 claims and then each claim's policy one by one = 1 + 20 queries. We set
`hibernate.default_batch_fetch_size: 50`, so Hibernate loads lazy relations in batches
(`WHERE id IN (...)`) instead of one at a time.

### Optimistic locking (`@Version`)
Each claim row has a `version` number. Every update does
`UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?`.
If someone else updated first, the version no longer matches and 0 rows change. Hibernate notices
and throws. Nobody waits on a lock, and nobody silently overwrites someone else's change.
**Pessimistic locking** (`SELECT ... FOR UPDATE`) is the alternative. It blocks the second user
until the first finishes, which is better when conflicts are frequent. Claim edits rarely clash,
so optimistic locking fits.

### Bean Validation
Annotations like `@NotNull`, `@NotBlank`, `@Email`, `@Positive`, `@Digits(integer=13, fraction=2)`
on request records. `@Valid` on the controller parameter triggers the check before your code runs.
We use it for the **shape** of input (400). **Business rules** that need the database or today's
date (policy is active, incident date in range) live in the service and return 422.

### Java records
`public record CustomerResponse(Long id, String fullName, ...) {}` is a short immutable class.
Java writes the constructor, getters (`id()`, not `getId()`), `equals`, `hashCode`, and `toString` for you.
Perfect for DTOs. Entities are **not** records, because JPA needs mutable classes with a no-arg constructor.

### `Optional`
A box that holds a value or is empty. It replaces returning `null`. `FraudRule.evaluate`
returns `Optional<FraudFlag>`: empty means "no problem found". `flatMap(Optional::stream)` then
keeps only the flags that exist.

### BigDecimal
Exact decimal numbers. `0.1 + 0.2` with `double` gives `0.30000000000000004`, but with `BigDecimal` it gives `0.3`.
Compare with `compareTo`, **not** `equals`: `new BigDecimal("1.0").equals(new BigDecimal("1.00"))`
is `false` because the scales differ, but `compareTo` returns 0.

### ProblemDetail (RFC 7807)
A standard JSON error format: `type`, `title`, `status`, `detail`, `instance`, plus custom fields.
Spring 6 has a `ProblemDetail` class. `@RestControllerAdvice` is a class whose `@ExceptionHandler`
methods catch exceptions from **all** controllers, so every error has the same shape.
Extending `ResponseEntityExceptionHandler` means Spring's own errors (bad JSON, missing parameter,
wrong type) also come back as ProblemDetail.

### `@ConfigurationProperties`
Binds a block of `application.yml` (`claimflow.fraud.*`) to a typed Java record, `FraudProperties`.
Kebab-case keys (`early-claim-days`) map to camelCase fields (`earlyClaimDays`). The env var
`CLAIMFLOW_FRAUD_EARLYCLAIMDAYS` also works, which is how Helm sets it. `@Validated` + `@Min(1)`
makes the app **refuse to start** with a bad value, which is safer than failing at 3 a.m.

### Profiles
`application-dev.yml` only loads when the `dev` profile is active. It adds the seed-data folder
to Flyway, so sample data exists in dev and never in prod.

### Flyway
Versioned SQL files (`V1__init.sql`, `V2__...`) run in order, once, and are recorded in a
`flyway_schema_history` table. Every environment gets the exact same schema, and changes are
code-reviewed like any other code.

### Actuator, Micrometer, MDC
- **Actuator** adds ready-made endpoints: `/actuator/health`, `/info`, `/prometheus`.
- **Micrometer** is like SLF4J, but for metrics. You write `Counter.increment()`, and it can export
  to Prometheus, Datadog, and others.
- **MDC** (Mapped Diagnostic Context) is a per-thread map whose values are added to every log line.
  We put `claimNumber` there, so you can search logs for one claim.

### Testing tools
- **JUnit 5**: the test framework (`@Test`, `@ParameterizedTest`).
- **Mockito**: fake objects. `when(repo.findById(1L)).thenReturn(...)`, then `verify(...)`.
- **AssertJ**: readable assertions: `assertThat(x).isEqualTo(y)`.
- **`@WebMvcTest`**: starts only the web layer (controllers, JSON, validation, error handler),
  with the service mocked. Fast.
- **`@SpringBootTest` + Testcontainers**: starts the whole app against a **real Postgres** running
  in Docker. `@ServiceConnection` wires the container's URL into Spring automatically.

---

## 6. Milestone by milestone

Each milestone lists what was built, why, what was rejected, and 3 likely interview questions.

### Milestone 1: Skeleton, entities, Flyway

**What:** Maven project on the Spring Boot 3.5 parent, Postgres via Docker Compose, the `V1__init.sql`
schema, four entities (`Customer`, `Policy`, `Claim`, `ClaimEvent`) plus the `FraudFlag` embeddable,
and Spring Data repositories. A Testcontainers test checks that the app starts and Flyway created the tables.

**Why:**
- The Boot **parent POM** pins compatible versions of ~hundreds of libraries, so we don't pick versions by hand.
- `ddl-auto: none`, because Flyway owns the schema.
- `IDENTITY` ids (Postgres `BIGSERIAL`) are simple and fine at this size.
- `FraudFlag` is an `@Embeddable` in an `@ElementCollection`: a *value* that belongs to one claim,
  with no identity of its own.
- `@Enumerated(EnumType.STRING)` stores `"APPROVED"`, not `3`. Reordering the enum won't corrupt data.

**Rejected:** `ddl-auto=update` (no history, can't safely rename or drop), H2 in-memory DB for tests
(it behaves differently from Postgres), Lombok (you asked for plain Java).

**Interview questions:**
1. *Why Flyway instead of `ddl-auto=update`?* Versioned, reviewed, repeatable SQL, and the same schema
   everywhere. `update` guesses, never drops or renames, and leaves no record of what changed.
2. *Why `EnumType.STRING`?* `ORDINAL` stores the position. Insert a new enum value in the middle
   and every stored row now means something else.
3. *Why a protected no-arg constructor on entities?* JPA needs one to create objects when loading
   rows. Keeping it protected stops our code from building half-empty entities.

### Milestone 2: Customers & Policies API, validation, ProblemDetail

**What:** CRUD-style endpoints, request/response **records**, Bean Validation, and the
`GlobalExceptionHandler` mapping exceptions to 400/404/409/422.

**Why:**
- **DTOs, not entities**, in the API: clients can't set `id` or `status`, and the DB can change
  without breaking the API.
- `201 Created` + `Location` header is REST-correct for POST.
- Emails are lower-cased and trimmed, and checked for duplicates (409). The DB `UNIQUE`
  constraint also catches the race where two requests arrive at once (the `DataIntegrityViolationException` handler).
- Policy numbers come from a **DB sequence**: unique even with many app replicas.

**Rejected:** returning entities (leaks internals, lazy-loading errors), a hand-rolled error JSON
(RFC 7807 is standard), and generating numbers with `max(id)+1` (breaks under concurrency).

**Interview questions:**
1. *Difference between 400 and 422 here?* 400 means the request is malformed (missing field, bad JSON).
   422 means it is well-formed but breaks a business rule (end date before start date).
2. *How does `@RestControllerAdvice` work?* It's a bean whose `@ExceptionHandler` methods apply to all
   controllers. Spring picks the handler with the most specific matching exception type.
3. *Why check for duplicate email in code if the DB has a unique constraint?* The code check gives a clear
   message in the normal case. The constraint is the real guarantee under concurrency.

### Milestone 3: FNOL + business rules

**What:** `POST /claims` with three rules (policy must be ACTIVE, incident date inside the policy
period, not in the future), claim numbers from a sequence, a first audit event (`null → FNOL`),
paging with a stable JSON `PageResponse`, and dev-only seed data.

**Why:**
- The future-date check is **in the service**, not `@PastOrPresent`, because the spec says 422
  and because "today" comes from the injectable `Clock` (tests can freeze time).
- Paging is capped at `size ≤ 100` so nobody can ask for a million rows.
- `PageResponse` instead of Spring's `Page`: Spring's JSON shape for `Page` isn't a stable contract.
- Seed data lives in a separate Flyway folder that only the `dev` profile loads.

**Rejected:** a dynamic query with `:param IS NULL OR ...` (known type problems with Postgres
and nulls). Simple derived queries were chosen instead. Specifications were also rejected as overkill for 2 filters.

**Interview questions:**
1. *Why inject a `Clock`?* So tests can say "today is 2026-06-15" and date rules are testable
   and deterministic.
2. *Is the incident date on the policy's start date allowed?* Yes. `Policy.covers()` includes both ends.
   There's a unit test for it.
3. *How would you add filtering by date range?* Add query parameters, then either more derived methods or
   switch to JPA Specifications / Querydsl once filters multiply.

### Milestone 4: State machine, transitions, audit trail

**What:** `ClaimStateMachine` (one `Map<ClaimStatus, Set<ClaimStatus>>`), `POST /claims/{id}/transitions`,
the approval amount rule in `ApprovalLimits`, and `GET /claims/{id}/events`.

**Why:**
- One table of allowed moves is easy to read, easy to change, and easy to test **exhaustively**:
  the test builds all 36 (from, to) pairs and checks 7 pass and 29 fail.
- `Claim.changeStatus` is package-private, so only the service (after the state machine) can call it.
- The approval check runs **after** the state check, so approving a PAID claim is a 409 (wrong state),
  not a 422 (wrong amount).
- Audit rows are written in the **same transaction** as the change: you can never have a change
  without its audit row, or an audit row for a change that rolled back.

**Rejected:** Spring Statemachine library (heavy for 6 states), status logic in `if` chains,
and the "State pattern" with one class per state (more files, same result here).

**Interview questions:**
1. *How do you add a new status, e.g. `REOPENED`?* Add the enum value, add its moves to the map,
   update the test's `VALID` set, and add a Flyway migration if a DB `CHECK` lists statuses.
2. *Why is the audit trail in the same transaction?* Atomicity: both are saved or neither is.
3. *Walk me through `maxApprovable`.* `min(coverageLimit, max(0, claimed − deductible))`.
   Claimed 1,200, deductible 500, limit 10,000 → 700.

### Milestone 5: Fraud rules engine

**What:** `FraudRule` interface, four `@Component` rules, `ClaimContext`, `FraudAssessmentService`,
and `FraudProperties` from YAML. It runs on FNOL → UNDER_REVIEW, and any flag auto-moves the claim to investigation.

**Why:**
- **Open/closed principle**: add a rule without editing the others or the engine.
- `ClaimContext` holds facts that need the DB (claim count in the window). It's loaded **once**
  and shared, so the rules stay pure functions that are trivial to unit test.
- Rules run **once**. When an investigator sends a claim back to review, re-running the rules would
  flag it again forever.
- Boundaries were made explicit and tested: *within 7 days* includes day 7, *≥ 3 claims* includes 3
  (and counts the current claim), *more than 30 days* excludes day 30. All dates are UTC.

**Rejected:** a rules engine like Drools (big learning curve, overkill for 4 rules), each rule querying
the DB itself (slower, harder to test), and hard-coded thresholds.

**Interview questions:**
1. *How does Spring find all the rules?* Injecting `List<FraudRule>` collects every bean of that type.
   Use `@Order` if order matters.
2. *What if a rule is slow or calls an external service?* Add a timeout, run it async (claim stays in
   review with a "pending" check), or move scoring to a separate service. The timer metric shows you when it gets slow.
3. *Why a record for `FraudProperties`?* Immutable, no setters, constructor binding, and validation at startup.

### Milestone 6: Integration tests (Testcontainers)

**What:** `ClaimLifecycleIT` runs the full story over real HTTP against real Postgres.
`ClaimConcurrencyIT` proves optimistic locking returns 409.

**Why:**
- Only a real DB proves the SQL, constraints, sequences, JPQL paths, and Flyway scripts actually work.
- **The concurrency test is deterministic**, not "hope two threads collide". The real
  `ClaimStateMachine` is wrapped with `@MockitoSpyBean`, and `validate(UNDER_REVIEW, REJECTED)` waits
  on a `CyclicBarrier(2)`. So both requests have **loaded the same version** before either writes.
  Then one commits and the other's `UPDATE ... WHERE version = old` matches 0 rows → 409. The test also
  checks only one REJECTED audit row exists (the loser rolled back).
- Each test creates its own customer with a random email, so tests don't interfere, even though they share one DB.

**Rejected:** H2 (different SQL dialect, no real concurrency behaviour) and `Thread.sleep`-based
concurrency tests (flaky).

**Interview questions:**
1. *Why Testcontainers over H2?* Same database engine as production, so no "passes on H2, fails on Postgres".
2. *How did you make the concurrency test reliable?* A barrier forces both requests to read before either
   writes. The outcome is always one 200 and one 409.
3. *What does the second request see in the DB?* Postgres blocks its UPDATE on the row lock until the first
   commits, re-checks the WHERE clause, finds the version changed, and updates 0 rows.

### Milestone 7: Observability

**What:** Liveness/readiness health groups, the Prometheus endpoint, 4 custom metrics,
JSON (ECS) logs with `claimNumber` in the MDC, and build info on `/actuator/info`.

**Why:**
- **Readiness includes the DB, and liveness doesn't.** If the DB goes down, pods stop receiving traffic
  but aren't restarted in a loop. Restarting wouldn't fix the DB.
- Metric tags have **low cardinality** (6 statuses, 4 rules). A tag like `claimNumber` would create a
  new time series per claim and blow up Prometheus.
- Timer with histogram buckets, so you can graph p95/p99 fraud-check latency.
- Spring Boot 3.4+ has built-in structured logging (`logging.structured.format.console: ecs`),
  so no extra library is needed.

**Rejected:** Logstash encoder dependency (not needed anymore), and tagging metrics by customer or claim (cardinality).

**Interview questions:**
1. *Liveness vs readiness?* Liveness: "is the process stuck? Restart me." Readiness: "can I serve
   traffic right now? If not, take me out of the Service."
2. *What is MDC and how is it cleaned up?* A per-thread log context. `MDC.putCloseable` in
   try-with-resources removes it, so the value doesn't leak to the next request on the same thread.
3. *Counter vs gauge vs timer?* Counter only goes up (claims created). A gauge goes up and down (queue size).
   A timer records durations and counts.

### Milestone 8: Docker + Helm

**What:** A multi-stage Dockerfile (Maven build stage → Alpine JRE 21 runtime, non-root UID 10001)
and a Helm chart with a Deployment, Service, ConfigMap, optional ServiceMonitor, and helm-unittest tests.

**Why:**
- **Multi-stage**: the final image has only the JRE and the jar, with no Maven or source. Smaller and safer.
  Copying `pom.xml` first caches the dependency layer.
- **Non-root, read-only root filesystem, drop all capabilities**, with an `emptyDir` for `/tmp`
  (Tomcat needs it).
- `-XX:MaxRAMPercentage=75`: the JVM sizes its heap from the container limit, not the host's RAM.
- **Secret name and keys are configurable**, and the chart **doesn't create** the Secret. Real clusters
  use External Secrets, Vault, or Sealed Secrets, and credentials don't belong in `values.yaml`.
- **Digest wins over tag**: a digest is immutable, so what you tested is exactly what runs.
- `checksum/config` annotation: changing the ConfigMap changes the pod template, which triggers a rolling restart.
- A startup probe gives the JVM time to boot before liveness can kill it.

**Rejected:** a single-stage image (huge, contains build tools), putting the DB password in the ConfigMap,
and a CPU limit that is too low (JVM startup is CPU-heavy, so a 1 CPU limit is set with a 250m request).

**Interview questions:**
1. *Why does a ConfigMap change restart pods here?* The pod template contains a hash of the ConfigMap.
   A new hash means a new template, so Kubernetes does a rolling update.
2. *Tag vs digest?* A tag like `1.2` can be moved to a new image. A digest (`sha256:...`) always means
   the same bytes.
3. *Why run as non-root?* If the app is compromised, the attacker isn't root in the container.
   Many clusters enforce it with Pod Security Standards.

### Milestone 9: CI + README

**What:** A GitHub Actions workflow: JDK 21 with Maven cache, `mvn verify` (Testcontainers works on
`ubuntu-latest` because Docker is there), test reports uploaded, Docker build, `helm lint`,
`helm template`, and `helm unittest`.

**Why:** Every push proves the build, tests, image, and chart still work. The Helm job is separate so
it runs in parallel with the Java build.

**Interview questions:**
1. *How does Maven caching work in CI?* `setup-java` with `cache: maven` saves `~/.m2` keyed by a hash
   of `pom.xml`.
2. *What would you add next to CI?* Push the image to GHCR with the commit SHA, a vulnerability scan (Trivy),
   and a deploy to a kind cluster for a smoke test.
3. *Why upload test reports?* So you can read failures in the Actions UI without re-running.

---

## 7. Tricky details worth knowing

- **Why `saveAndFlush` in `transition`?** Dirty checking would send the UPDATE at commit, which
  happens *after* the method returns, inside the proxy. Flushing inside the method makes the version
  conflict happen at a known place. (Spring would still translate it at commit. Flushing just makes it obvious.)
- **Auto-flush before queries.** The fraud check's count query makes Hibernate flush the pending
  status change first (so the query sees current data). That's one extra UPDATE, which is harmless.
- **The frequency count includes the current claim.** "3 claims in 30 days" means this claim plus 2 earlier ones.
- **Sequence gaps are fine.** If a transaction rolls back, that claim number is skipped.
  Sequences never go back, which is what makes them safe with many app instances.
- **Seed data uses version 1000** (`V1000__dev_seed_data.sql`) with `out-of-order: true` in dev, so a
  future `V2` still applies on a dev DB that already has seeds.
- **UTC everywhere**: the `Clock` bean is UTC, Hibernate's JDBC time zone is UTC, and `reportedAt` is converted
  to a date in UTC for the late-reporting rule.
- **Two kinds of validation exception (a bug CI caught).** `ClaimController` has `@Size(max = 100)`
  on the `X-Actor` header. In Spring 6.1+, any constraint directly on a controller parameter turns on
  *method validation* for that whole method. Then even `@Valid` body errors arrive as
  `HandlerMethodValidationException`, not `MethodArgumentNotValidException`. The first version only
  formatted the second one, so the 400 responses lost their `errors` list. The fix: `GlobalExceptionHandler`
  now formats both the same way. That's a good "tell me about a bug you fixed" story.
- **Metrics are off in tests by default (the second bug CI caught).** `@SpringBootTest` disables metrics
  exporters, so `/actuator/prometheus` returned 404 in `ObservabilityIT`. Adding
  `@AutoConfigureObservability` to that test class turns them back on. The app itself was fine.
- **`claims_created_total` became `claims_filed_total` (the third CI catch).** Spring Boot 3.5's Prometheus
  client (1.x) follows OpenMetrics, where `_created` is a reserved suffix (it stores a counter's
  creation time). So it stripped it: a meter named `claims.created` came out as `claims_total`.
  The counter was renamed to `claims.filed`.
- **The actor comes from `X-Actor`**, defaulting to `api-user`. With real auth you'd take it from the
  logged-in user (for example, a JWT subject via Spring Security).

---

## 8. Known limitations (say these before they ask)

Being honest about limits is a strength in interviews.

1. **No authentication/authorisation.** Anyone can approve a claim. Next step: Spring Security + OAuth2
   resource server, with roles like ADJUSTER, INVESTIGATOR, and FINANCE per transition.
2. **No idempotency on POST.** A retried FNOL could create two claims. Fix: an `Idempotency-Key` header
   stored with a unique constraint.
3. **Metrics are counted before commit.** A request that rolls back still increments the counter.
   Fix: increment in a `TransactionSynchronization.afterCommit` callback.
4. **Package cycle**: `claim` uses `fraud`, and `fraud` uses `claim`'s entities and repository. It's acceptable
   here. In a bigger system you'd pass a plain snapshot object to the fraud module.
5. **Fraud rules are synchronous.** Fine for in-memory checks. An external scoring API would need
   async processing or a timeout.
6. **This code was written without being compiled or run** in the authoring environment (no JDK, Maven,
   Docker, or Helm installed). CI runs everything on each push. Check the Actions tab, and fix anything
   it finds before you present the project.

---

## 9. Interview question bank

**Java & Spring basics**

1. **What is dependency injection and why use it?** Objects get their dependencies from outside
   (the constructor) instead of creating them. It makes code loosely coupled and testable with mocks.
2. **Constructor vs field injection?** Constructor injection allows `final` fields, makes
   dependencies obvious, and works without Spring in unit tests. Field injection hides them.
3. **What does `@SpringBootApplication` do?** It combines `@Configuration`, `@EnableAutoConfiguration`
   (configures beans based on the classpath), and `@ComponentScan` (finds your classes in this package and below).
4. **What is auto-configuration?** Spring Boot sees, for example, Postgres + JPA on the classpath and creates a
   `DataSource`, `EntityManagerFactory`, and transaction manager for you, unless you define your own.
5. **`@Component` vs `@Service` vs `@Repository`?** All make beans. `@Service` is naming intent.
   `@Repository` also translates DB exceptions into Spring's `DataAccessException`.
6. **Why records for DTOs?** Immutable, concise, with value-based equals. Good for data that just moves around.
7. **Checked vs unchecked exceptions?** Checked must be declared or caught. Unchecked (`RuntimeException`)
   don't have to be. `@Transactional` rolls back on unchecked ones by default.

**JPA / database**

8. **What is the N+1 problem and how did you handle it?** Loading N rows and then one query per row
   for a relation. Batch fetching (`default_batch_fetch_size`) here. Alternatives are `JOIN FETCH` or entity graphs.
9. **LAZY vs EAGER?** LAZY loads on first access (needs an open session). EAGER always loads.
   Default to LAZY for `@ManyToOne` to avoid loading half the database.
10. **What happens when you change a managed entity's field?** Dirty checking. Hibernate sends an
    UPDATE at flush/commit. No explicit save needed.
11. **Optimistic vs pessimistic locking?** Optimistic checks a version at write time, with no locks held,
    and the loser gets an error. Pessimistic locks the row at read time, so others wait. Pick based on how often conflicts happen.
12. **Why is `claim_event` a separate table and not a JSON column?** It's queryable, indexed, append-only,
    and each row has a clear schema. That's good for auditors and reports.
13. **Why indexes on `status`, `policy_id`, and `customer_id`?** They're the columns we filter
    (`?status=`) and join/filter on (`?policyId=`, `?customerId=`, and the frequency count).
14. **What does `@Transactional(readOnly = true)` give you?** Hibernate skips dirty checking and
    flushing, and some drivers/DBs can optimise read-only transactions.

**API design**

15. **Why POST `/transitions` instead of PATCH `status`?** A transition is an *action* with a reason,
    an actor, and side effects (fraud checks, audit), not a field edit. It also keeps the state machine as the only way in.
16. **How do you return errors?** RFC 7807 ProblemDetail from one `@RestControllerAdvice`:
    400 validation, 404 not found, 409 conflict/invalid transition/concurrent edit, 422 business rule.
17. **Why cap page size?** To protect the DB and memory from `size=1000000`.
18. **How would you version the API?** It's already under `/api/v1`. Breaking changes go to `/api/v2`
    while v1 keeps working for a while.

**Domain logic**

19. **Why BigDecimal?** Money must be exact. `double` is binary floating point and can't represent 0.1.
20. **Explain the approval limit rule with an example.** Claim 12,000, deductible 500, limit 10,000:
    12,000 − 500 = 11,500, capped at 10,000, so the max is 10,000. Claim 400 with deductible 500 gives 0.
21. **Why don't the fraud rules re-run after investigation?** A human cleared the claim. Re-running would
    flag it again forever.
22. **How would you add a rule "claim amount is a round number like 5000.00"?** Create a new
    `@Component` class implementing `FraudRule`, add a threshold to `FraudProperties` if needed, and write a boundary test.
    No other file changes.

**Testing**

23. **Unit vs slice vs integration tests here?** Unit tests check one class with mocks (fast, many).
    `@WebMvcTest` checks the web layer only. `@SpringBootTest` + Testcontainers checks the whole app with a real DB (slow, few).
24. **What is a parameterized test?** One test method run with many inputs (`@CsvSource`,
    `@MethodSource`), great for boundaries and the full state table.
25. **How do you test time-based rules?** Inject a `Clock`, and use `Clock.fixed(...)` in tests.
26. **Why is `mvn verify` needed for integration tests?** Surefire runs `*Test` in the `test` phase.
    Failsafe runs `*IT` in `integration-test` and fails the build in `verify`.

**DevOps**

27. **Walk me through your Dockerfile.** Build stage with Maven (pom first for caching), then a runtime stage
    with a slim JRE, a non-root user, only the jar, and a heap sized from the container limit.
28. **How does the app get its DB password in Kubernetes?** From an existing Secret via `secretKeyRef`.
    The Secret name and keys are Helm values, and the chart never stores the password.
29. **How does Prometheus find the app?** With the Prometheus Operator, the optional ServiceMonitor tells it
    to scrape the Service's `http` port at `/actuator/prometheus`.
30. **What happens during a rolling update?** New pods start. Once readiness passes they get traffic,
    then old pods are removed. Graceful shutdown (`server.shutdown: graceful`) lets in-flight requests finish.

**Scaling & "what if"**

31. **What if two app replicas create claims at the same time?** DB sequences give unique numbers
    and optimistic locking protects updates, so there is no in-memory state to share.
32. **What if the fraud check becomes slow?** Watch `claims_fraud_assessment_seconds` p95. Move it async with
    an outbox/event and a "fraud check pending" state, or cache customer history.
33. **How would you split this into microservices?** Policy and Claims services, with fraud as a separate scorer
    consuming claim events. Only do it when teams or scale demand it, because a modular monolith is simpler to run.

---

## 10. Your 2-minute project pitch

> "ClaimFlow is a Spring Boot 3 service for P&C insurance claims, the same space Guidewire's
> ClaimCenter covers. Customers have policies, and they file claims through a first-notice-of-loss endpoint,
> which checks the policy is active and the incident date is valid. Claims move through a strict
> lifecycle that I modelled as a small state machine, so illegal moves return 409, and every change
> writes an audit row in the same transaction.
>
> When a claim enters review, a pluggable fraud engine runs. Each rule is a Spring bean, so adding
> a rule is just adding a class, and the thresholds come from validated configuration. Any flag automatically
> sends the claim to investigation. Money is all BigDecimal, and approvals are capped at the claimed amount
> minus the deductible and the coverage limit.
>
> Concurrency is handled with optimistic locking. I wrote a deterministic integration test with
> Testcontainers and a barrier to prove two simultaneous updates give one success and one 409.
> It exposes Prometheus metrics and Kubernetes health probes, logs JSON with the claim number,
> ships as a non-root multi-stage Docker image with a Helm chart, and CI runs unit, web-slice,
> integration, and Helm unit tests on every push."
