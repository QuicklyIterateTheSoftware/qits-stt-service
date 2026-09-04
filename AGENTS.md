# qits-stt — working notes

Read `README.md` first: it says why a speech service is host-side and what the one route does. This
file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no python, no docker, no prior
`mvn install` elsewhere, no network beyond maven central. `mvn verify` is the gate. Anything that
would break that is not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why the poms duplicate versions instead of inheriting them, why the error types are copied
rather than imported, and — above all — why **no test may ever create a venv, run pip, or download
the model**. See "Tests" below, including the story catalogue, which does spawn worker processes,
and why that does not break this rule.

**`service/` compiles to a GraalVM native image**, the same rule qits-workspace-daemon and the
platform's other native deployables carry, and it extends the clone-alone rule rather than
qualifying it: `.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a `native-image` and
`./mvnw package -Dnative` produces `service/target/qits-stt` in about 40 seconds with no container
involved.

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
the `/stt` path segment the platform edge routes on.

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
collapse into a shared `qits-commons` lib when that exists.

`SttExceptionMapper` is *not* one of these — the monorepo's `eu.wohlben.qits.api
.DomainExceptionMapper` is app-shell code that no target receives, so this is a fresh mapper typed
to this context's `DomainException`. It mirrors `qits-workspaces`' `WorkspacesExceptionMapper`.

## Schema changes

There are none. This context owns no tables (migration-plan.md §7), has no datasource and no Flyway
lineage. If something here ever needs to persist, that is a design decision to take deliberately —
adding a datasource is not a routine change in this repo.

## Authentication

Authentication happens at `qits-platform-edge`. This service resolves a principal from a trusted
header (`X-Qits-User`, read by qits-auth-core's `ForwardAuthMechanism`) and authenticates nothing.

**The name matters and it changed.** This section read `qits-gateway` until the 2026.829 userflows
rollout. That service no longer exists — the edge replaced the whole gateway tier and is now the
only proxy in front of anything — so a document naming it sends a reader looking for a deployment
nobody can make. The *contract* is unchanged in every particular: same headers, same strip rule,
same reason to trust them.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`, and the one REST boundary there is uses Jakarta
`@RolesAllowed("qits:admin")`.

**And there is no machine door at all.** This repository has no `quarkus-oidc` extension, no
`quarkus.oidc.*` block and no `qits.auth.machine.*` key of its own, so a bearer token is not a
credential here — it is a header nothing reads, and a request carrying one and nothing else is a
401 like any other stranger. That is a deliberate shape rather than an omission (a transcription is
something an operator asks for, through the edge, like any other person), and it is asserted rather
than assumed: the refusal story sends a bearer and pins the 401. Adding a machine door is a design
decision, and it starts with the `quarkus.oidc.*` block every validator carries.

**`X-Qits-*` is the edge's reserved namespace, stripped from every inbound request
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

### The story catalogue, and the worker processes a test may spawn

**Seven `@UserStory` methods over four `@QuarkusIntegrationTest` classes**, emitting
`service/target/userstories/` and published by the userflow half of
`.config/qits/ci-event-release-request.yml`, once per release-request fold, as the docs bundle
`@userflows/qits-stt`:

    api/TranscriptionBootstrapIT            transcription    a clip transcribed end to end, and the
                                                             six refusals that never wake the engine
    stories/transcription/ResidentEngineIT  transcription    why this is a service: a second clip is
                                                             not a second engine
    stories/home/SpeechHomeBootstrapIT      the speech home  a volume that was never pre-seeded, and
                                                             the warm engine behind the failure
    stories/engine/EngineFailureIT          the engine       gone, refusing, and babbling — the three
                                                             failures, and the one respawn each costs

`skipITs` is `false` in `service/pom.xml`, so a plain `mvn verify` runs them all and that half names
no class: a new story class is run the day it is written rather than silently never. It declares
`gating: false`, so a red story shows the run red without holding the fold at the release gate. `docker/Dockerfile` stops at `package`, so the image build is untouched.

**One `StoryProfile` for the whole catalogue, and that is the point of it.** Every class carries
`@TestProfile(StoryProfile.class)`, so failsafe launches the fast-jar **once**: one boot, one
resident worker, one recording. A second profile would be a second process whose spawn landed in
whichever diagram happened to be open. It also means the stories are not independent of each other,
and they say so — whether a story opens with a spawn edge depends on what the story before it left
running, which is the actual behaviour of a service whose whole design is one resident process.

**Order is load-bearing, not tidiness.** The recording is cumulative and attributed by a cursor, so
the first spawn — made on the first REQUEST, warmup being off, not at boot — lands in whichever
story drains first. `@UserflowRunsAfter` on every other class is what keeps that story
`TranscriptionBootstrapIT`'s first one.
`UserflowClassOrderer` is registered as junit's *secondary* orderer in the test
`application.properties`; a local `junit-platform.properties` hard-fails surefire. Run a later class
on its own and its first story inherits the spawn and fails its edge count — loudly, which is the
right way for that assumption to break.

**These classes still create no venv, run no pip and download no model.** `StoryEngine` (under
`stories/support/`) stages the whole speech home into `target/`, and both children in it are
`/bin/sh`:
a resident worker at `venv/bin/python` speaking the worker protocol, and a host interpreter at
`host/python3` that records and refuses. That is a posture `docker/Dockerfile` already describes —
a deployment that cannot reach PyPI or the Hugging Face hub pre-seeds the volume.

Why they earn their place, given the fakes above: **the packaged posture is the only one where the
door and the process plumbing are real.** `@RolesAllowed("qits:admin")` is invisible to every
`@QuarkusTest` here, because qits-auth-core's `%test` dev-user hands each one a `qits:admin`
identity and `ForwardAuthMechanism` only ignores that fallback under `LaunchMode.NORMAL`; and
`TranscriptionServiceTest` replaces `ProcessExecutor`/`SpeechWorker` with CDI fakes, so the real
`ProcessBuilder`, the greeting handshake, the respawn-once policy and the staged WAV's deletion
never execute. Do not "fix" a story by mocking either half — that is what the surefire suite is for.

**Every edge in every diagram is OBSERVED. Nothing here is declared any more.** That is the change
this catalogue is really about, and it reversed a decision the first rollout got wrong:

- What arrives here is tapped by `NetworkTaps.restAssured("qits-stt")` — **the framework ships the
  tap now**, so the hand-copied `StoryNetworkFilter` that used to sit beside the IT was deleted. Its
  default skip is any path with a `/q/` segment and this service's probe root is `/stt/q`, so the
  default is right without an override. It is idempotent per service name, which is why every story
  class installs it from its own `@BeforeAll`. A story sets `NetworkCapture.actor(...)` **before**
  each call, because a tap sees a request and never a narrative role.
- What qits-stt talks to was `Network.declare`d in the first rollout, on the reasoning that no tap
  can stand in front of a pipe. **That reasoning was wrong by one step**, and qits-platform-system
  proved it: this service does not open a socket to an engine, it **spawns a program** and reads its
  pipes, and which program that is arrives as one runtime key. So the honest stand-in is not a claim
  — it is an executable, and making it *record* is the whole difference between "we believe this
  happened" and "here is what was asked and what came back". `StoryEngine` reads
  `<home>/engine/results.log` as a cumulative `NetworkCapture.source`, and both edges are ordinary
  observed `process` edges.

**A label is a summary, not a command line, and an answer is a shape, not content.**
`StoryEngine.summarize` reduces a recorded line to `spawn venv/bin/python transcribe_worker.py`,
`transcribe tmp/{id}.wav` or `python3 -m venv venv`, and appends how it was answered — `-> running`,
`-> text`, `-> error`, `-> not json`, `-> exit`, `-> 1` — in the shape an HTTP label's status has,
because it is the same half of the evidence. Two rules behind that:

- **The vocabulary is closed and the answer is never the transcript.** The one thing this service
  promises is that it keeps no recording, so the label says `-> text`, never what the text was.
- **Both halves of a path are templated here, not by the framework.** The speech home is an absolute
  path under `target/`, so it differs per checkout, and the staged name is `<uuid>.wav`, where the
  uuid is not a whole path segment. `Labels` rewrites neither, so `StoryEngine.relativize` does. One
  absolute path in a label is a `networkHash` that differs on every machine, and the only symptom of
  that is a hash that never settles.

Finer questions are asked of the same recording rather than of the diagram: `StoryEngine.argvOf`
reads the spawn's whole argv (the script really is the one materialized under the home), and
`stagedSince` reads the staged path and **the size the engine measured on the far side**, which is
what makes "the recording was really written to disk" a measurement and its absence afterwards a
second one.

**The stand-in engine is ARMABLE, and that is what made the failure stories possible.** A story
writes one word into `<home>/engine/mode` and the next answer over the pipe becomes an exit, a
refusal, or a library warning on the protocol pipe. `once:` disarms itself on the way through, so a
story can prove a *recovery*; `always:` stays, so a story can reach the *give-up*. Arming is a file
and never a restart, so the process under test is the process that was already running — and a story
that arms **disarms in `@AfterEach`**, not in a `finally`, or a story that failed early leaves the
next one's engine broken and the failure lands on the wrong story.

**The absences are the paying assertions**, and this rollout is what made them pay. `assertEdgeCount`
and `assertOnlyEdgesFrom` on every story; `assertNoEdgesTo("the speech engine")` on the refusal
story, which means something now that the engine is a node other stories genuinely reach;
`assertNoEdgesTo("the host python")` on every story but the one about the bootstrap itself — and
that one is the sharpest, because `qits.speech.python` points at a **recording stand-in that exists** rather than
(as the first rollout had it) a path that cannot. "No venv was built and nothing reached PyPI" is a
claim with a witness only because the binary that would have done it is right there and records.
`assertNotLeaked` covers every generated staging id and the bearer the refusal story sends. An
absence is a step and never an edge: an arrow that meant its own opposite would be a worse document
than none.

**What is deliberately NOT drawn, and why the diagram is honest to leave it out.** Two real
dependencies of a deployed qits-stt appear in no story: the model pull from the Hugging Face hub on
the engine's first-ever start, and the OTLP export to qits-observability. The first is prevented by
the pre-seeded home; the second is off (`quarkus.otel.sdk.disabled` in the profile), because a
batched, timer-driven export would land in whichever story happened to be open when it fired.
Neither is declared. **A declared edge for traffic a run deliberately prevented is exactly the
dishonesty the `declared` flag exists to avoid** — a claim would render beside evidence and read
like it. The venv bootstrap is the one out-of-reach dependency that was brought *in* reach, by
pointing its interpreter at a stand-in, and it is drawn because it genuinely happens.

App-level config lives in `service/src/main/resources/application.properties` and **the tests
inherit it** — Quarkus merges main's copy into the test run, it is not shadowed. That is why
`SpeechControllerTest` can assert `/stt/api/transcriptions` with no test-side `quarkus.rest.path`.
`service/src/test/resources/application.properties` exists again and holds exactly one line — the
userflows class orderer, which configures the JUnit run rather than the application and has nowhere
else to live. Nothing else may join it, and it is load-bearing rather than decorative now: without
it the `@UserflowRunsAfter` graph is ignored, JUnit picks its own class order, and the symptom is an
edge count failing in a story that did nothing wrong.

Never re-declare an app-level setting in test resources. A second copy does not make the suite
safer, it makes it lie: the run goes green because the *test* copy is right, so a wrong or missing
value in the shipped copy — the one that actually reaches a deployment — passes unnoticed. Test
resources are for genuine test-only overrides, and a real override belongs in a `@TestProfile`
next to the test that needs it (see `NoDevUserProfile`, `TranscriptionServiceTest.SpeechTestProfile`,
`stories/support/StoryProfile`) so its scope is visible.

`OpenApiSchemaExportTest` writes `docs/openapi.yml` as a side effect. Regenerate and commit it
whenever the route surface changes:

    ./mvnw -pl service test -Dtest=OpenApiSchemaExportTest

It runs as a `@QuarkusTest`, so **the test classpath is indexed too**: any `@Path` resource under
`src/test` lands in the committed document unless it is `@Operation(hidden = true)`. That is why
`IdentityEchoResource` carries the annotation.

A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
(`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run before
investigating.
