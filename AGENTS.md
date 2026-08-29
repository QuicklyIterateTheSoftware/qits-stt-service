# qits-stt — working notes

Read `README.md` first: it says why a speech service is host-side and what the one route does. This
file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no python, no docker, no prior
`mvn install` elsewhere, no network beyond maven central. `mvn verify` is the gate. Anything that
would break that is not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why the poms duplicate versions instead of inheriting them, why the error types are copied
rather than imported, and — above all — why **no test may ever create a venv, run pip, or download
the model**. See "Tests" below, including the one test that does spawn a worker process and why
that does not break this rule.

**`service/` compiles to a GraalVM native image**, the same rule qits-workspace-daemon and
qits-gateway carry, and it extends the clone-alone rule rather than qualifying it: `.sdkmanrc` names
`25.0.2-graalce`, so `sdk env` gives you a `native-image` and `./mvnw package -Dnative` produces
`service/target/qits-stt` in about 40 seconds with no container involved.

Two consequences worth stating before you reach for a dependency:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
  Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
  either way, so the fallback is easy to be in without noticing — recognise it by the image pull.
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resource loading by computed name and JNI/JNA all need registering, and
  the failure lands at *runtime* in the binary while the JVM suite stays green. Prefer what is
  already in the image — `ProcessBuilder` over a process library, `java.lang.foreign` over JNA — and
  if a native build needs configuration to pass, that configuration is part of the change.

## Package and module conventions

`eu.wohlben.qits.stt.*`, split across two maven modules with disjoint sub-packages so there is no
split package:

- `domain/` — `control` (the venv bootstrap, the resident worker, `ProcessExecutor`) and `error`.
  Framework-free in the sense that matters: no JAX-RS.
- `service/` — `api` (the JAX-RS route and the exception mapper).

In the monorepo *both* halves lived under `eu.wohlben.qits.domain.speech` — the controller too. The
`domain.`/`.speech` segments are gone; `stt` is the context name everywhere, matching the repo and
the gateway segment.

## The host-side python is the design, not a bug

`SpeechWorker.ensureProcess()` shells a host `venv/bin/python`, and `TranscriptionService`
`pip install`s into a venv it creates. This looks like something that should be containerized or
moved into the workspace-daemon. It is not:

- the model must be loaded **once** and stay loaded, or per-utterance latency is dominated by
  startup rather than inference;
- transcription is not workspace-scoped — nothing here takes a workspace or repository id.

If that ever changes, it changes as a designed migration with a new wire protocol, not as a cleanup.

## Copied classes

Per migration-plan.md §5 (duplicate-now, library-later), these are **copies of monorepo code**, not
originals:

- `stt/error/*` — the five `DomainException` subclasses.
- `stt/control/ProcessExecutor` — from `domain.agent.control`; also copied into the daemon repo.

Treat them as vendored: fix a bug here and it needs fixing in `../qits` too, and vice versa. They
collapse into `libs/qits-commons` when that exists.

`SttExceptionMapper` is *not* one of these — the monorepo's `eu.wohlben.qits.api
.DomainExceptionMapper` is app-shell code that no target receives, so this is a fresh mapper typed
to this context's `DomainException`. It mirrors `qits-workspaces`' `WorkspacesExceptionMapper`.

## Schema changes

There are none. This context owns no tables (migration-plan.md §7), has no datasource and no Flyway
lineage. If something here ever needs to persist, that is a design decision to take deliberately —
adding a datasource is not a routine change in this repo.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `stt/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`; human-facing REST boundaries use Jakarta
`@RolesAllowed("qits:admin")`. Machine-facing boundaries require an authenticated identity and
retain their narrower `MachineAuth` audience/scope checks.

**`X-Qits-*` is the gateway's reserved namespace, stripped from every inbound request
unconditionally**, so a client cannot forge one. That strip rule is the entire reason the header can
be trusted here — and it is why `ForwardAuthTest` sets the real header rather than reaching for
`@TestSecurity`. The header *is* the contract under test; a test that mocked the identity instead
would pass just as happily against a mechanism that never reads it.

## Tests

The two `@QuarkusTest` suites **touch no python at all**:

- `TranscriptionServiceTest` installs a `FakeProcessExecutor` (records the bootstrap commands,
  returns canned results) and a `FakeSpeechWorker` (records the staged WAV, returns a canned
  transcript) via `QuarkusMock.installMockForType`, and points `qits.speech.home` at a temp dir
  through a `@TestProfile`.
- `SpeechControllerTest` is validation-level only: every request it sends fails before the
  transcription runner could spawn.

A test that actually ran the real worker would need a venv, a pip install and a 700 MB model
download. Keep the fakes.

### The userflow, and the one worker process a test may spawn

`TranscriptionBootstrapIT` is a `@QuarkusIntegrationTest`: it launches the **packaged** artifact and
tells two `@UserStory` stories against it — a clip transcribed end to end, and the three refusals
that never reach the engine. `mvn verify` emits them under `service/target/userstories/`, and
`.config/qits/ci-event-userflows.yml` publishes that directory per commit as the docs bundle
`@userflows/qits-stt`. `skipITs` is `false` in `service/pom.xml` so a plain `mvn verify` runs it;
`docker/Dockerfile` stops at `package`, so the image build is untouched.

**It is the only test that spawns a worker, and it still creates no venv, runs no pip and downloads
no model.** It pre-seeds `qits.speech.home` with a dozen-line `/bin/sh` script at
`venv/bin/python` — the exact path `SpeechWorker` runs — which speaks the worker protocol (one
greeting line, then one WAV path in and one JSON line out) and records what it was asked. That is a
posture `docker/Dockerfile` already describes: a deployment that cannot reach PyPI or the Hugging
Face hub pre-seeds the volume. `qits.speech.python` is pointed at a path that cannot exist, so a
broken fixture fails in milliseconds instead of reaching for PyPI.

Why it earns its place, given the fakes above: **the packaged posture is the only one where the door
and the process plumbing are real.** `@RolesAllowed("qits:admin")` is invisible to every
`@QuarkusTest` here, because qits-auth-core's `%test` dev-user hands each one a `qits:admin`
identity and `ForwardAuthMechanism` only ignores that fallback under `LaunchMode.NORMAL`; and
`TranscriptionServiceTest` replaces `ProcessExecutor`/`SpeechWorker` with CDI fakes, so the real
`ProcessBuilder`, the greeting handshake and the staged WAV's deletion never execute. Do not "fix"
the IT by mocking either half — that is what the surefire suite is already for.

App-level config lives in `service/src/main/resources/application.properties` and **the tests
inherit it** — Quarkus merges main's copy into the test run, it is not shadowed. That is why
`SpeechControllerTest` can assert `/stt/api/transcriptions` with no test-side `quarkus.rest.path`.
`service/src/test/resources/application.properties` exists again and holds exactly one line — the
userflows class orderer, which configures the JUnit run rather than the application and has nowhere
else to live. Nothing else may join it.

Never re-declare an app-level setting in test resources. A second copy does not make the suite
safer, it makes it lie: the run goes green because the *test* copy is right, so a wrong or missing
value in the shipped copy — the one that actually reaches a deployment — passes unnoticed. Test
resources are for genuine test-only overrides, and a real override belongs in a `@TestProfile`
next to the test that needs it (see `NoDevUserProfile`, `TranscriptionServiceTest.SpeechTestProfile`,
`TranscriptionBootstrapIT.PackagedWithAPreSeededEngine`) so its scope is visible.

`OpenApiSchemaExportTest` writes `docs/openapi.yml` as a side effect. Regenerate and commit it
whenever the route surface changes:

    ./mvnw -pl service test -Dtest=OpenApiSchemaExportTest

It runs as a `@QuarkusTest`, so **the test classpath is indexed too**: any `@Path` resource under
`src/test` lands in the committed document unless it is `@Operation(hidden = true)`. That is why
`IdentityEchoResource` carries the annotation.

A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
(`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run before
investigating.
