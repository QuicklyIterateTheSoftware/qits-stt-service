package eu.wohlben.qits.stt.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b>, with a stand-in engine behind it — the one posture
 * this repository's suite has never had. Three things are true only here:
 *
 * <ul>
 *   <li><b>The door in front of the route is real.</b> {@code SpeechController} is
 *       {@code @RolesAllowed("qits:admin")}, but every {@code @QuarkusTest} in this repo arrives as
 *       {@code dev} with that role: qits-auth-core ships {@code %test.qits.auth.forward.dev-user},
 *       and {@code ForwardAuthMechanism} additionally ignores it under {@code LaunchMode.NORMAL} —
 *       which is the mode a packaged process runs in. So the refusals a deployment gives are
 *       reachable in this class and nowhere else in the suite.
 *   <li><b>The engine seam is real.</b> {@code TranscriptionServiceTest} replaces {@code
 *       ProcessExecutor} and {@code SpeechWorker} with CDI fakes, so the actual
 *       {@code ProcessBuilder} plumbing — spawn, the {@code {"ready":true}} greeting, one WAV path
 *       in, one JSON line out, and the staged file's lifetime — has never executed. Here the fakes
 *       are gone: the shipped {@code SpeechWorker} talks to a genuine child process over genuine
 *       pipes, and only the far end of those pipes is a stand-in.
 *   <li><b>The shipped configuration is what answers.</b> {@code quarkus.rest.path=/stt/api} and
 *       {@code quarkus.http.non-application-root-path=/stt/q} are read out of the built artifact by
 *       a process launched from it, not merged into a test run.
 * </ul>
 *
 * <p><b>The far side is a process, not a service</b>, which is why this IT boots no {@code
 * MockService}: qits-stt calls no HTTP upstream at all (its only outbound is the OTLP exporter,
 * dark here as it is under {@code %dev}/{@code %test}). What a deployment supplies instead is
 * <em>disk state</em> — a venv under {@code qits.speech.home} whose {@code venv/bin/python} holds
 * the Parakeet model — so the stand-in is a dozen-line {@code /bin/sh} script placed at exactly that
 * path, speaking exactly that protocol. No python, no pip, no 700 MB model pull, no GPU and no
 * network: the pre-seeded speech home is a posture {@code docker/Dockerfile} already documents ("a
 * deployment that cannot reach either registry pre-seeds the VOLUME instead"), so the arrangement
 * is a deployment's, not an invention of this test.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. Both stories
 * are browserless (an {@code Interactions} parameter and no {@code Flow}), so the framework's
 * transitive Playwright never launches anything — which is what lets this run in a step container
 * with no browser in it.
 *
 * <p><b>This IT is the only one in the repository, so the module opts back in</b> from the pom
 * ({@code skipITs} false in {@code service/pom.xml}, the qits-githost shape) rather than being
 * named on a command line: there is no heavyweight sibling for that flip to drag into somebody's
 * plain {@code mvn verify} — no docker, no database, no network, and the child process is
 * {@code /bin/sh}. {@code docker/Dockerfile} stops at {@code package}, before the integration-test
 * phase, so the image build is untouched by it.
 */
@QuarkusIntegrationTest
@TestProfile(TranscriptionBootstrapIT.PackagedWithAPreSeededEngine.class)
public class TranscriptionBootstrapIT {

  static final String CATEGORY = "transcription";
  static final String TRANSCRIBED_SLUG = "a-recorded-clip-comes-back-as-text";
  static final String REFUSED_SLUG = "a-refused-request-never-wakes-the-engine";

  static final String ROUTE = "/stt/api/transcriptions";

  /** The transcript the stand-in engine answers with — the payload that has to survive the trip. */
  static final String TRANSCRIPT = "the release train leaves at nine";

  /**
   * The worker script {@code TranscriptionService} re-materializes out of the domain jar on every
   * bootstrap check ("so script changes deploy with the jar", README.md). Named absolutely because
   * that is how {@code WORKER_RESOURCE} names it.
   */
  static final String WORKER_RESOURCE = "/speech/transcribe_worker.py";

  /**
   * The stand-in for the resident Parakeet worker: it speaks the worker protocol and does nothing
   * else. {@code SpeechWorker} runs {@code <home>/venv/bin/python <home>/transcribe_worker.py} with
   * the home as its working directory, reads one greeting line, then writes one staged WAV path per
   * request and reads one JSON line back — so a shell script at that path is a complete engine as
   * far as this service is concerned.
   *
   * <p>The two files it leaves behind are what make each interaction assertable on <b>both ends</b>:
   * {@code spawned} names the script it was handed (proving the classpath resource really was
   * materialized), and {@code asked} accumulates one line per transcription (proving what the engine
   * was — and was not — asked to do). It derives the speech home from {@code $0} rather than being
   * templated with it; the one substitution is {@code @TRANSCRIPT@}, so the transcript this story
   * asserts is spelled exactly once.
   */
  private static final String ENGINE =
      """
      #!/bin/sh
      # A stand-in for the resident Parakeet worker (see speech/transcribe_worker.py), written by
      # TranscriptionBootstrapIT. It loads no model and needs no python: the protocol is one
      # greeting line, then one WAV path in and one JSON line out, forever.
      home=${0%/venv/bin/python}
      echo "$1" > "$home/spawned"
      echo '{"ready": true}'
      while IFS= read -r wav; do
        echo "$wav" >> "$home/asked"
        echo '{"text": "@TRANSCRIPT@"}'
      done
      """;

  /**
   * Where the prepared speech home is parked for the story methods. A test profile is instantiated
   * in more than one classloader and a static field written by one copy is not the field another
   * reads, while the JVM has exactly one property table — the same reason the sibling repos' mocks
   * park their coordinates this way. It is also the guard that keeps a second {@code
   * getConfigOverrides()} call from wiping the home out from under a running application.
   */
  private static final String HOME_PROPERTY = "qits.stt.it.speech-home";

  /**
   * Hands the launched artifact its config the way a deployment does — and there is very little of
   * it, which is this service's shape rather than an omission: it owns no tables, so {@code
   * .config/qits/deployments.yml} declares no {@code resources:} and there are no generic triples to
   * supply. The whole of a qits-stt deployment is where its disk state lives.
   *
   * <p>Every key here is a <b>runtime</b> key. A packaged process takes its configuration as {@code
   * -D} arguments on an artifact that was already built, so a build-time key would be silently
   * ignored and the test would prove something other than what it says.
   */
  public static class PackagedWithAPreSeededEngine implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      Path home = speechHomeWithAnEngine();
      return Map.of(
          // The one seam this test moves: where the disk state is. The packaged artifact is
          // otherwise exactly what ships — the same routes, the same door, the same worker
          // protocol. `docker/Dockerfile` moves this same key (QITS_SPEECH_HOME) for the same
          // reason.
          "qits.speech.home",
          home.toString(),
          // THE SAFETY PIN, and the reason it is spelled at all: this is the interpreter that
          // CREATES the venv, used only when `<home>/venv/bin/python` is absent — and the two
          // commands behind it are `python3 -m venv` and `pip install onnx-asr[cpu,hub]`, i.e. a
          // reach for PyPI. Naming a path that cannot exist means a broken fixture fails in
          // milliseconds with "Failed to start process" instead of dialing out of a step container.
          "qits.speech.python",
          home.resolve("there-is-no-python-here").toString(),
          // Left at the code default, said out loud: warmup bootstraps the venv and spawns the
          // worker on a virtual thread at startup. Off, the spawn this story observes is caused by
          // the request it makes — which is the whole of what the first story claims.
          "qits.speech.warmup-on-start",
          "false",
          // Dark outside a deployment, like %dev/%test — a runtime key, and the only dial-out this
          // process has apart from the engine itself. A packaged run is the `prod` profile, so the
          // shipped %test line does not apply here.
          "quarkus.otel.sdk.disabled",
          "true");
    }
  }

  /**
   * Prepare, once per JVM, a speech home that already holds an engine — the pre-seeded volume a
   * deployment without PyPI or Hugging Face access supplies.
   *
   * <p>Under {@code target/} so {@code mvn clean} owns its lifetime, and wiped on the way in so a
   * previous run's {@code spawned}/{@code asked} can never stand in for this one's.
   */
  static synchronized Path speechHomeWithAnEngine() {
    String parked = System.getProperty(HOME_PROPERTY);
    if (parked != null) {
      return Path.of(parked);
    }
    Path home = Path.of("target", "userflow-speech-home").toAbsolutePath().normalize();
    try {
      deleteRecursively(home);
      Path engine = home.resolve("venv").resolve("bin").resolve("python");
      Files.createDirectories(engine.getParent());
      Files.writeString(engine, ENGINE.replace("@TRANSCRIPT@", TRANSCRIPT));
      Files.setPosixFilePermissions(engine, PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to prepare the pre-seeded speech home " + home, e);
    }
    System.setProperty(HOME_PROPERTY, home.toString());
    return home;
  }

  @UserStory(value = "A recorded clip comes back as text", category = "transcription")
  @UserStoryDescription(
      """
      Someone dictates into the qits UI; the browser posts the recorded WAV, base64 in JSON, and
      the words come back. Between those two moments qits-stt does exactly one thing, and it is
      the reason this is a service of its own: it hands the clip to a resident engine that already
      holds the model in memory, and passes the engine's answer back verbatim.

      The clip itself is never kept. It is staged under the speech home for the length of the
      call and deleted in a `finally` block — this context owns no tables and, it turns out, no
      files either, which is what lets a recording of somebody's voice pass through it.
      """)
  void aClipIsHandedToTheResidentEngineAndTheAnswerComesBack(Interactions story) {
    Path home = speechHome();

    story.note(
        "qits-stt starts with a pre-seeded speech home: the engine is already there, so no venv is"
            + " built and no model is downloaded");
    given().get("/stt/q/health/ready").then().statusCode(200);

    // The clip: a real 16 kHz mono 16-bit WAV of silence. The service treats it as opaque bytes —
    // decoding audio is the engine's job — so silence is honest, while the RIFF header keeps the
    // fixture the shape a browser really records.
    String clip = Base64.getEncoder().encodeToString(silentWav(4000));

    // End (a), the caller's: the gateway's identity opens the route and the transcript comes back
    // as the JSON the generated client expects.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", clip))
        .when()
        .post(ROUTE)
        .then()
        .statusCode(200)
        .body("text", equalTo(TRANSCRIPT));
    story
        .happened(
            "qits-gateway", "qits-stt", "POST /stt/api/transcriptions (a WAV as base64, X-Qits-*)")
        .as("clip-submitted");

    // End (b), the engine's: it was spawned with the script this service materialized out of its
    // own jar, and that script is the one the jar carries — byte for byte. "Script changes deploy
    // with the jar" (README.md) is a claim about a file written at runtime, so nothing short of
    // running the service can check it.
    assertEquals(
        home.resolve("transcribe_worker.py").toString(),
        readString(home.resolve("spawned")).strip(),
        "the engine must be spawned with the worker script, materialized under the speech home");
    assertArrayEquals(
        classpathWorkerScript(),
        readAllBytes(home.resolve("transcribe_worker.py")),
        "the materialized worker script must be the one shipping in the jar");
    story
        .happened("qits-stt", "the speech engine", "spawn <home>/venv/bin/python <script>")
        .as("engine-spawned");

    // …and it was asked to transcribe exactly one clip, by a path under the speech home's staging
    // directory. The path is the whole request protocol: one WAV path in, one JSON line out.
    List<String> asked = linesOf(home.resolve("asked"));
    assertEquals(1, asked.size(), "the engine should have been asked exactly once");
    Path staged = Path.of(asked.getFirst());
    assertTrue(
        staged.startsWith(home.resolve("tmp")) && staged.toString().endsWith(".wav"),
        "the clip must be staged as a WAV under <home>/tmp, not handed over some other way: "
            + staged);
    story
        .happened("qits-stt", "the speech engine", "one staged WAV path over the worker's pipes")
        .as("engine-asked");
    story
        .happened("the speech engine", "qits-stt", "one JSON line: the transcript")
        .as("engine-answered");

    // And the recording is gone. The 200 was already served, so this is not a race: the delete
    // happens in the `finally` around the transcription, before the response is built.
    assertTrue(
        Files.notExists(staged),
        "the staged recording must be deleted once transcribed, not left under the speech home");
    story
        .happened("qits-stt", "qits-gateway", "200 with the transcript, the staged WAV deleted")
        .as("transcript-served");
  }

  @UserStory(value = "A refused request never wakes the engine", category = "transcription")
  @UserStoryDescription(
      """
      The engine behind this service is the most expensive thing in it — a resident process
      holding a ~700 MB model, serving one request at a time. So the interesting half of the
      contract is what does NOT reach it.

      Three refusals, in the order a deployed process applies them. A caller with no identity is
      challenged: in a deployment there is no dev-user to fall back on, whatever a test suite
      enjoys. A signed-in user without `qits:admin` is forbidden: reaching the service is not the
      same as being allowed to dictate into it. And an admin sending something that is not audio
      is told so by this service, not by the engine — the base64 is decoded here, and a decode
      failure is a 400 with a message rather than a spawned process and a timeout.

      In all three the engine stays exactly as it was: never asked, never woken.
      """)
  void neitherAStrangerNorNonsenseReachesTheEngine(Interactions story) {
    Path home = speechHome();
    int askedBefore = linesOf(home.resolve("asked")).size();
    String clip = Base64.getEncoder().encodeToString(silentWav(4000));

    // (a) no gateway identity at all. The %test dev-user that hands every @QuarkusTest in this repo
    // a qits:admin identity is scoped to the test profile AND ignored under LaunchMode.NORMAL, so
    // this — a challenge, not a silent anonymous pass — is the deployed answer.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", clip))
        .when()
        .post(ROUTE)
        .then()
        .statusCode(401);
    story
        .happened("a caller with no gateway identity", "qits-stt", "POST " + ROUTE + " -> 401")
        .as("anonymous-refused");

    // (b) a real user, forwarded by the gateway, without the role. Authenticated, so this is
    // authorization (403) rather than a missing-authentication challenge (401) — the same pair
    // qits-artifacts' admin guard draws.
    given()
        .header("X-Qits-User", "alice")
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", clip))
        .when()
        .post(ROUTE)
        .then()
        .statusCode(403);
    story
        .happened("a signed-in user without qits:admin", "qits-stt", "POST " + ROUTE + " -> 403")
        .as("non-admin-refused");

    // (c) past the door, but not audio. SttExceptionMapper turns this context's BadRequestException
    // into the JSON envelope every qits client reads — a message, not a stack trace and not the
    // framework's HTML error page.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", "!!! not base64 !!!"))
        .when()
        .post(ROUTE)
        .then()
        .statusCode(400)
        .body("message", equalTo("audioBase64 is not valid base64"));
    story
        .happened(
            "an admin sending something that is not audio", "qits-stt", "POST " + ROUTE + " -> 400")
        .as("not-audio-refused");

    // The other end of all three: the engine's own log is untouched. This is the assertion the
    // repo's validation-level @QuarkusTest cannot make — it has no engine to look at, only fakes.
    assertEquals(
        askedBefore,
        linesOf(home.resolve("asked")).size(),
        "a refused request must never reach the transcription engine");
    story.note("the speech engine was never asked — the door and the decoder both come first")
        .as("engine-never-woken");
  }

  /**
   * The speech home the profile prepared, read from the property it parked it in rather than
   * prepared a second time — a story must never be able to wipe the engine out from under the
   * running application.
   */
  private static Path speechHome() {
    String parked = System.getProperty(HOME_PROPERTY);
    assertTrue(parked != null, "the profile prepares the speech home before the artifact launches");
    return Path.of(parked);
  }

  /**
   * A 16 kHz mono 16-bit PCM WAV of silence: a 44-byte canonical RIFF header and {@code samples}
   * zero samples. Built here rather than committed because a binary fixture nobody can read is a
   * worse fixture than eight lines that say what it is.
   */
  private static byte[] silentWav(int samples) {
    int dataBytes = samples * 2;
    ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
    wav.put(ascii("RIFF")).putInt(36 + dataBytes).put(ascii("WAVE"));
    wav.put(ascii("fmt "))
        .putInt(16) // PCM header length
        .putShort((short) 1) // format: PCM
        .putShort((short) 1) // channels: mono
        .putInt(16_000) // sample rate
        .putInt(32_000) // byte rate = rate * channels * 2
        .putShort((short) 2) // block align
        .putShort((short) 16); // bits per sample
    wav.put(ascii("data")).putInt(dataBytes);
    return wav.array();
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  /** The worker script as the domain jar carries it, for comparison with the materialized copy. */
  private static byte[] classpathWorkerScript() {
    try (InputStream in = TranscriptionBootstrapIT.class.getResourceAsStream(WORKER_RESOURCE)) {
      assertTrue(in != null, "the worker script is missing from the test classpath");
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + WORKER_RESOURCE, e);
    }
  }

  /** The engine's log lines, or none at all when it has never been asked anything. */
  private static List<String> linesOf(Path file) {
    try {
      return Files.exists(file) ? Files.readAllLines(file) : List.of();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
  }

  private static byte[] readAllBytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
  }

  /** A file the engine was supposed to have written, read without the checked exception. */
  private static String readString(Path file) {
    assertTrue(Files.exists(file), "the engine never wrote " + file);
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, TRANSCRIBED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        TRANSCRIBED_SLUG,
        "qits-stt",
        "the speech engine",
        "one staged WAV path over the worker's pipes");
    ReportAssertions.assertStepId(CATEGORY, TRANSCRIBED_SLUG, "clip-submitted");
    ReportAssertions.assertStepId(CATEGORY, TRANSCRIBED_SLUG, "engine-spawned");
    ReportAssertions.assertStepId(CATEGORY, TRANSCRIBED_SLUG, "engine-asked");
    ReportAssertions.assertStepId(CATEGORY, TRANSCRIBED_SLUG, "engine-answered");
    ReportAssertions.assertStepId(CATEGORY, TRANSCRIBED_SLUG, "transcript-served");

    ReportAssertions.assertComplete(CATEGORY, REFUSED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "anonymous-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "non-admin-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "not-audio-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "engine-never-woken");
  }
}
