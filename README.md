# qits-stt

Speech-to-text for qits: a browser-recorded WAV goes in, a transcript comes out. Transcription runs
server-side with NVIDIA Parakeet (ONNX, CPU) via [onnx-asr](https://pypi.org/project/onnx-asr/).

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no python

## Why this is its own service

Because it is genuinely **host-side and not workspace-scoped**, which is the one thing nothing else
in the qits split is:

- `SpeechWorker` keeps a **resident host python process** alive (`<home>/venv/bin/python
  transcribe_worker.py`) with the model loaded in memory. Loading Parakeet costs seconds, so a
  python run per request would make live-ish transcription impossible; instead one worker stays up
  and requests stream over its pipes — one WAV path in, one JSON line out.
- `TranscriptionService` **bootstraps that venv on the host**, lazily and once: `python3 -m venv`
  followed by `pip install --quiet onnx-asr[cpu,hub]`, then a ~700 MB model pull from the Hugging
  Face hub on first use.

None of that belongs inside a per-workspace container, and none of it belongs in a module that has
to start fast. It wants one long-lived process with one warm model, which is exactly a service.

## Layout

| Module | What |
|---|---|
| `domain/` | `eu.wohlben.qits.stt.{control,error}` — the venv bootstrap, the resident worker, the process plumbing, and `speech/transcribe_worker.py` as a classpath resource. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.stt.api` — `POST /stt/api/transcriptions` and the exception mapper over it. |

`domain/` is a library jar. **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080, route on /stt/api/transcriptions

    ./mvnw package -Dnative
    ./service/target/qits-stt                             # same routes, ~14ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain — the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
if it finds none it does not fail, it quietly falls back to pulling a 1.8 GB Mandrel image and
running the compile under docker. That fallback still works and is what CI without a GraalVM gets;
it is just not the intended path, and it is worth recognising by name when a build that normally
takes 40 seconds starts downloading a container image.

It was extracted as a library on the assumption that some consuming Quarkus application would pull
it in and gain the route. No such application was ever written, and under the proxy topology that
replaced it none will be — so the route had no way to be served and the proxy had nothing to route
to.

## What it owns

Nothing persistent. **No tables, no datasource, no Flyway lineage.** A transcription is a pure
request/response: the WAV is staged to `<home>/tmp/<uuid>.wav`, handed to the worker, and deleted in
a `finally` block. Everything durable about it — the venv, the pip install, the model cache — is
disk state under `qits.speech.home`, not database state.

The API is one route:

    POST /stt/api/transcriptions   { "audioBase64": "…" } -> { "text": "…" }

Everything this service serves lives under the `/stt` segment — `qits-platform-edge` routes
verbatim by prefix, so the prefix is part of the address here, not something a proxy adds. `/stt/api` is
`quarkus.rest.path`; the framework's own surface (`/stt/q/openapi`, `/stt/q/swagger-ui`) sits under
`quarkus.http.non-application-root-path`. There is no unprefixed form.

Base64 in JSON rather than multipart, deliberately: the clips are small and it keeps the generated
client trivial. Payloads are capped at 30 MB (≈16 minutes of 16 kHz mono 16-bit WAV).

## Configuration

| Property | Default | What |
|---|---|---|
| `qits.speech.home` | `data/speech` | Where the venv, the materialized worker script and the staging dir live. Relative to the process CWD. |
| `qits.speech.python` | `python3` | The interpreter used to *create* the venv. Must have the `venv` module. |
| `qits.speech.warmup-on-start` | `false` | Bootstrap the venv and spawn the worker (= download/load the model) on a virtual thread at startup, so the first real request doesn't pay for it. |

The worker script ships as a classpath resource at `/speech/transcribe_worker.py` and is
re-materialized to `<home>/transcribe_worker.py` on **every** bootstrap check, so script changes
deploy with the jar. The resource path kept its `speech/` prefix through the extraction — it is a
classpath location, not a package, and `WORKER_RESOURCE` in `TranscriptionService` names it
absolutely.

## Operating it

The first request (or the first warmup) is slow and network-bound: venv creation, `pip install`, and
a ~700 MB model download. `START_TIMEOUT` is 10 minutes for exactly that reason; steady-state
transcription is `TRANSCRIBE_TIMEOUT` = 2 minutes.

Requests are serialized — the worker is single-threaded. A dead or wedged worker is killed and
respawned once per request before the call gives up with a 500.

Clips up to 25 s go through `recognize()` directly; longer ones are segmented with a silero VAD
model (loaded alongside Parakeet at worker startup), because plain `recognize()` tops out around
30 s.

The host running this needs `python3` with `venv`, a C toolchain-free wheel path for `onnx-asr`, and
outbound access to PyPI and the Hugging Face hub. A deployment that cannot reach either must
pre-seed `qits.speech.home` with a built venv and a warm HF cache.

## What is deliberately *not* here

- **The recorder.** The browser side (WAV segmentation at pauses, the live transcript UI) is
  `service/src/main/webui/` in the monorepo and stays there until the frontend is redone as
  per-service Lit components.
- **Any workspace or repository awareness.** This context never sees a workspace id. It transcribes
  bytes.
- **A `main` class or an auth variant.** Quarkus supplies the entrypoint and `qits-platform-edge`
  owns authentication. The repo has been a deployable since image publishing shipped: every green build
  pushes `qits/qits-stt:<sha>` and qits-cd deploys it.

Integrated by the release flow (AC live proof, maven reactor, 2026-07-31T21:33:42Z).
Released by the /release door (AJ live proof, 2026-08-01). The event AJ saw was named
`SoftwareRelease` and is now `SCMRelease`: it says source control has the version, and nothing
about an image. `.config/qits/ci-event-release.yml` reacts to it, builds the tagged commit and
pushes `qits/qits-stt:<version>`; qits-ci turns that green run into `SoftwareRelease`, which is the
event that means the image exists.

AT: released through the mirror substrate on 2026-08-01.

Released through the new release-request flow on 2026-09-04, verifying the deploy path end to end.
