package eu.wohlben.qits.stt.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.stt.stories.support.StoryClip;
import eu.wohlben.qits.stt.stories.support.StoryEngine;
import eu.wohlben.qits.stt.stories.support.StoryProfile;
import eu.wohlben.qits.stt.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service as it is <b>packaged</b>, with a stand-in engine behind it — the one posture the
 * surefire suite never has. Three things are true only here:
 *
 * <ul>
 *   <li><b>The door in front of the route is real.</b> {@code SpeechController} is
 *       {@code @RolesAllowed("qits:admin")}, but every {@code @QuarkusTest} in this repo arrives as
 *       {@code dev} with that role: qits-auth-core ships {@code %test.qits.auth.forward.dev-user},
 *       and {@code ForwardAuthMechanism} additionally ignores it under {@code LaunchMode.NORMAL} —
 *       which is the mode a packaged process runs in. So the refusals a deployment gives are
 *       reachable in this catalogue and nowhere else in the suite.
 *   <li><b>The engine seam is real.</b> {@code TranscriptionServiceTest} replaces {@code
 *       ProcessExecutor} and {@code SpeechWorker} with CDI fakes, so the actual {@code
 *       ProcessBuilder} plumbing — spawn, the {@code {"ready":true}} greeting, one WAV path in, one
 *       JSON line out, and the staged file's lifetime — has never executed. Here the fakes are gone:
 *       the shipped {@code SpeechWorker} talks to a genuine child process over genuine pipes, and
 *       only the far end of those pipes is a stand-in.
 *   <li><b>The shipped configuration is what answers.</b> {@code quarkus.rest.path=/stt/api} and
 *       {@code quarkus.http.non-application-root-path=/stt/q} are read out of the built artifact by
 *       a process launched from it, not merged into a test run.
 * </ul>
 *
 * <p><b>The far side is a process, not a service</b>, which is why this catalogue boots no {@code
 * MockService}: qits-stt calls no HTTP upstream at all (its only outbound is the OTLP exporter, dark
 * here as it is under {@code %dev}/{@code %test}). What a deployment supplies instead is <em>disk
 * state</em> — a venv under {@code qits.speech.home} whose {@code venv/bin/python} holds the Parakeet
 * model — so the stand-in is a shell script placed at exactly that path, speaking exactly that
 * protocol. No python, no pip, no 700 MB model pull, no GPU and no network: the pre-seeded speech
 * home is a posture {@code docker/Dockerfile} already documents, so the arrangement is a
 * deployment's and not an invention of this test.
 *
 * <p><b>And because the far side is a program rather than a port, the diagram is evidence.</b> The
 * first rollout of these two stories <em>declared</em> the process edges, reasoning that no tap can
 * stand in front of a pipe. It can: the stand-in records what it was asked and how it answered, and
 * {@link StoryEngine} turns that recording into observed {@code process} edges. Nothing in this
 * class is declared any more. The two dependencies genuinely out of reach — the venv bootstrap's
 * reach for PyPI and the model pull from the Hugging Face hub — are prevented by the pre-seeded
 * home, and a claim drawn for traffic a run deliberately prevented is exactly the dishonesty the
 * {@code declared} flag exists to avoid. One of them is even provable as an <b>absence</b>, because
 * the interpreter that would do it is a recording stand-in too.
 *
 * <p><b>This class is the head of the catalogue and its order is load-bearing.</b> The recording is
 * cumulative and attributed by a cursor, so the first spawn — which this service makes on its first
 * request rather than at boot, warmup being off — lands in whichever story drains first. Every other
 * story class carries {@code @UserflowRunsAfter(TranscriptionBootstrapIT.class)} so that is the
 * story that claims it. Run a later class on its own and its first story inherits the spawn and
 * fails its edge count, loudly, which is the right way for that assumption to break.
 *
 * <p>The ITs are opted back in from the pom ({@code skipITs} false in {@code service/pom.xml}, the
 * qits-githost shape) rather than named on a command line: there is no heavyweight sibling for that
 * flip to drag into somebody's plain {@code mvn verify} — no docker, no database, no network, and
 * every child process is {@code /bin/sh}. {@code docker/Dockerfile} stops at {@code package}, before
 * the integration-test phase, so the image build is untouched by it.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TranscriptionBootstrapIT {

  static final String CATEGORY = "transcription";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String TRANSCRIBED = "A recorded clip comes back as text";

  static final String TRANSCRIBED_SLUG = Slugs.slug(TRANSCRIBED);

  static final String REFUSED = "A refused request never wakes the engine";

  static final String REFUSED_SLUG = Slugs.slug(REFUSED);

  /**
   * The worker script {@code TranscriptionService} re-materializes out of the domain jar on every
   * bootstrap check ("so script changes deploy with the jar", README.md). Named absolutely because
   * that is how {@code WORKER_RESOURCE} names it.
   */
  static final String WORKER_RESOURCE = "/speech/transcribe_worker.py";

  /** How the refusal story names each of its six callers. Six arrows in, and none out. */
  private static final String NOBODY = "a caller the edge never named";

  private static final String A_BEARER = "a machine holding a bearer token";

  private static final String WITHOUT_ADMIN = "a signed-in user without qits:admin";

  private static final String NOT_BASE64 = "an admin sending something that is not audio";

  private static final String NO_AUDIO = "an admin sending a request with no clip in it";

  private static final String NOT_JSON = "an admin sending a body that is not JSON";

  /**
   * A bearer that is not a credential here, and could not be: this service has no OIDC tenant at all
   * — no {@code quarkus-oidc} extension, no {@code qits.auth.machine.*} — so identity arrives only
   * as the edge's forwarded header. It is a literal rather than a minted token, which is the honest
   * shape of the claim: nothing on this side ever looks at it. Asserted not to have leaked into the
   * published bundle all the same, because a token-shaped string in a document reads like a secret
   * whatever it actually is.
   */
  private static final String BEARER = "not-a-real-token-this-service-has-no-machine-door";

  /** The generated staging name this run used, kept so the report can be proved free of it. */
  private static String stagedId;

  @BeforeAll
  static void tapBothSidesOfThisService() {
    // The framework SHIPS the incoming tap now — the hand-copied StoryNetworkFilter that used to sit
    // beside this class was deleted with this rollout. The default skip is any path with a `/q/`
    // segment, and this service's probe root is `/stt/q`, so the default is right without an
    // override. Idempotent per service name, so every story class installs from its own @BeforeAll.
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    // …and the outgoing half, which is this service's whole fan-out: two child processes.
    StoryEngine.installSource();
  }

  @UserStory(value = TRANSCRIBED, category = CATEGORY)
  @UserStoryDescription(
      """
      Someone dictates into the qits UI; the browser posts the recorded WAV, base64 in JSON, and
      the words come back. Between those two moments qits-stt does exactly one thing, and it is the
      reason this is a service of its own: it hands the clip to a resident engine that already
      holds the model in memory, and passes the engine's answer back verbatim.

      Both halves of that are drawn from evidence. The request into this service is tapped and
      labelled with the status it answered; the conversation with the engine is read out of the
      engine's own recording — the spawn, with the script it was handed, and the one transcription,
      with the answer it gave. What is NOT here is a reach for PyPI or for the Hugging Face hub:
      the speech home is pre-seeded, and the interpreter that would have built a venv is a
      recording stand-in of its own, so "no venv was built and no model was downloaded" is an
      absence with a witness rather than a sentence.

      The clip itself is never kept. It is staged under the speech home for the length of the call
      and deleted in a `finally` block, and both halves of that are measured: the engine reports
      the size of the file it was handed, and the story looks for the file afterwards and does not
      find it. This context owns no tables and, it turns out, no files either, which is what lets a
      recording of somebody's voice pass through it.
      """)
  @Order(1)
  void aClipIsHandedToTheResidentEngineAndTheAnswerComesBack(Interactions story) {
    Path home = StoryEngine.home();
    int before = StoryEngine.mark();

    story.note(
        "qits-stt starts with a pre-seeded speech home: the engine is already there, so no venv is"
            + " built and no model is downloaded");
    given().get(StoryTarget.READY).then().statusCode(200);

    // End (a), the caller's: the edge's forwarded identity opens the route and the transcript comes
    // back as the JSON the generated client expects.
    //
    // The actor is set BEFORE the call, because the tap sees a request and never a narrative role:
    // X-Qits-User says "alice", and what the diagram needs to say is WHO put that header there.
    // Nothing here draws the arrow — the tap does, with the status this service answered — so the
    // note is left with the one thing a method, a path and a status cannot carry.
    NetworkCapture.actor(StoryTarget.EDGE);
    given()
        .header("X-Qits-User", StoryTarget.USER)
        .header("X-Qits-Roles", StoryTarget.ADMIN_ROLE)
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", StoryClip.base64()))
        .when()
        .post(StoryTarget.ROUTE)
        .then()
        .statusCode(200)
        .body("text", equalTo(StoryTarget.TRANSCRIPT));
    story
        .note("the edge forwards an operator's recording: a WAV as base64, under X-Qits-* headers")
        .as("clip-submitted");

    // End (b), the engine's, read out of its own recording rather than believed: exactly two calls
    // happened, in this order, and this run is what caused both of them.
    assertEquals(
        List.of(StoryEngine.spawned(), StoryEngine.transcribed(StoryEngine.TEXT)),
        StoryEngine.callsSince(before),
        "one request must cause exactly one spawn and exactly one transcription");

    // The spawn's own argument, asked of the same recording: the engine was handed the script this
    // service materialized out of its own jar, absolutely and not merely by name — and that script
    // is the one the jar carries, byte for byte. "Script changes deploy with the jar" (README.md)
    // is a claim about a file written at runtime, so nothing short of running the service checks it.
    assertEquals(
        List.of("spawn", home.resolve("transcribe_worker.py").toString()),
        StoryEngine.argvOf(StoryEngine.SPAWN),
        "the engine must be spawned with the worker script, materialized under the speech home");
    assertArrayEquals(
        classpathWorkerScript(),
        readAllBytes(home.resolve("transcribe_worker.py")),
        "the materialized worker script must be the one shipping in the jar");
    story
        .note("the engine was spawned once, with the worker script this service materialized from"
            + " its jar — and it stayed up, which is the whole point of a resident worker")
        .as("engine-spawned");

    // …and it was asked to transcribe exactly one clip, by a path under the speech home's staging
    // directory, holding exactly the bytes the browser posted. The path IS the request protocol.
    List<StoryEngine.Staged> staged = StoryEngine.stagedSince(before);
    assertEquals(1, staged.size(), "the engine should have been asked exactly once");
    Path clip = staged.getFirst().path();
    stagedId = staged.getFirst().generatedId();
    assertTrue(
        clip.startsWith(home.resolve("tmp")) && clip.toString().endsWith(".wav"),
        "the clip must be staged as a WAV under <home>/tmp, not handed over some other way: " + clip);
    assertEquals(
        StoryClip.BYTES,
        staged.getFirst().bytes(),
        "the engine must have been handed the whole recording, not an empty file or a truncated one");
    story
        .note("one staged WAV path was written to the worker's pipe, and exactly one — with the"
            + " whole recording behind it, as the engine measured it on the far side")
        .as("engine-asked");
    story
        .note("the engine answered on the same pipe, in lockstep, one JSON line: the transcript")
        .as("engine-answered");

    // And the recording is gone. The 200 was already served, so this is not a race: the delete
    // happens in the `finally` around the transcription, before the response is built. This is an
    // assertion about what is NOT on disk, not traffic — the 200 is already the observed edge's
    // label — so it is a step and never an arrow.
    assertTrue(
        Files.notExists(clip),
        "the staged recording must be deleted once transcribed, not left under the speech home");
    story
        .note("the transcript was served and the staged WAV is gone: this context keeps no clip")
        .as("transcript-served");
  }

  @UserStory(value = REFUSED, category = CATEGORY)
  @UserStoryDescription(
      """
      The engine behind this service is the most expensive thing in it — a resident process holding
      a ~700 MB model, serving one request at a time. So the interesting half of the contract is
      what does NOT reach it.

      Six refusals, in the order a deployed process applies them. Three are about who is asking: a
      caller with no identity is challenged, because in a deployment there is no dev-user to fall
      back on whatever a test suite enjoys; a caller holding a bearer token is challenged too, and
      that is not an oversight but this service's shape — it has no OIDC tenant at all, so identity
      arrives as the edge's forwarded header or it does not arrive; and a signed-in user without
      `qits:admin` is forbidden rather than challenged, because reaching the service is not the
      same as being allowed to dictate into it.

      Three are about what is being asked. A body that is not JSON never reaches the method. Base64
      that is not base64 is decoded HERE, and a decode failure is this service's 400 with a
      sentence in it rather than a spawned process and a timeout. A request with no clip in it at
      all fails validation on the way in.

      In all six the engine stays exactly as it was: never asked, never woken — and neither is the
      host interpreter that would build a venv. That is the paying half, and the diagram is where
      it is checkable: six arrows in, and not one leaving this process.
      """)
  @Order(2)
  void neitherAStrangerNorNonsenseReachesTheEngine(Interactions story) {
    int before = StoryEngine.mark();
    String clip = StoryClip.base64();

    // Six callers, six arrows, and the actor is named before EACH of them: the tap reads the sticky
    // initiator at the moment of the call, so a name set late would draw the previous caller's
    // arrow. It is also what keeps the refusals from collapsing — the framework dedupes on the whole
    // (kind, from, to, label) quadruple, and two of these six answer the same 401.

    // (a) no forwarded identity at all. The %test dev-user that hands every @QuarkusTest in this
    // repo a qits:admin identity is scoped to the test profile AND ignored under LaunchMode.NORMAL,
    // so this — a challenge, not a silent anonymous pass — is the deployed answer.
    NetworkCapture.actor(NOBODY);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", clip))
        .when()
        .post(StoryTarget.ROUTE)
        .then()
        .statusCode(401);

    // (b) a bearer token, which is nobody here. Worth its own arrow because the answer is the same
    // 401 for a completely different reason: five other qits services would have validated this
    // against qits-platform-idp, and this one has no tenant to validate it with. A machine that
    // wants a transcription goes through the edge like everybody else.
    NetworkCapture.actor(A_BEARER);
    given()
        .header("Authorization", "Bearer " + BEARER)
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", clip))
        .when()
        .post(StoryTarget.ROUTE)
        .then()
        .statusCode(401);
    story
        .note("no identity and a bearer token get the same answer for opposite reasons: this"
            + " service has no machine door at all, so a name arrives from the edge or not at all")
        .as("anonymous-refused");

    // (c) a real user, forwarded by the edge, holding a real platform role that is not the one this
    // route names. Authenticated, so this is authorization (403) rather than a missing-authentication
    // challenge (401) — the same pair qits-artifacts' admin guard draws.
    NetworkCapture.actor(WITHOUT_ADMIN);
    given()
        .header("X-Qits-User", StoryTarget.USER)
        .header("X-Qits-Roles", StoryTarget.READER_ROLE)
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", clip))
        .when()
        .post(StoryTarget.ROUTE)
        .then()
        .statusCode(403);
    story
        .note("a signed-in user holding a real platform role that is not qits:admin gets the OTHER"
            + " answer: they are somebody, and they cover nothing here")
        .as("non-admin-refused");

    // (d) past the door, and not JSON. The route is @Consumes(APPLICATION_JSON), so this is refused
    // by content negotiation before the resource method exists to be entered.
    NetworkCapture.actor(NOT_JSON);
    given()
        .header("X-Qits-User", StoryTarget.USER)
        .header("X-Qits-Roles", StoryTarget.ADMIN_ROLE)
        .contentType(ContentType.TEXT)
        .body("this is not a request body")
        .when()
        .post(StoryTarget.ROUTE)
        .then()
        .statusCode(415);

    // (e) JSON, and no clip in it. @NotBlank on the record, so the violation is refused on the way
    // in rather than becoming an empty byte array somebody has to check for later.
    NetworkCapture.actor(NO_AUDIO);
    given()
        .header("X-Qits-User", StoryTarget.USER)
        .header("X-Qits-Roles", StoryTarget.ADMIN_ROLE)
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", ""))
        .when()
        .post(StoryTarget.ROUTE)
        .then()
        .statusCode(400);

    // (f) a clip that is not base64. SttExceptionMapper turns this context's BadRequestException
    // into the JSON envelope every qits client reads — a message, not a stack trace and not the
    // framework's HTML error page.
    NetworkCapture.actor(NOT_BASE64);
    given()
        .header("X-Qits-User", StoryTarget.USER)
        .header("X-Qits-Roles", StoryTarget.ADMIN_ROLE)
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", "!!! not base64 !!!"))
        .when()
        .post(StoryTarget.ROUTE)
        .then()
        .statusCode(400)
        .body("message", equalTo("audioBase64 is not valid base64"));
    story
        .note("and past the door the request still has to be a request: a body that is not JSON, a"
            + " body with no clip in it, and base64 that is not base64 are three different refusals"
            + " and none of them is a spawned process and a timeout")
        .as("not-audio-refused");

    // The other end of all six: neither child process was touched. This is the assertion the repo's
    // validation-level @QuarkusTest cannot make — it has no engine to look at, only fakes.
    //
    // It is a step and NOT an edge, deliberately: "nothing happened" is an absence, and an absence
    // drawn as an arrow would be an arrow that means its own opposite. The diagram makes the same
    // claim by being empty of it, which assertNoEdgesTo pins in @AfterAll.
    List<String> calls = StoryEngine.callsSince(before);
    assertTrue(calls.isEmpty(), "a refused request must reach no child process at all: " + calls);
    story
        .note("the speech engine was never asked and the host interpreter was never run — the door,"
            + " the content type and the decoder all come first")
        .as("engine-never-woken");
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

  private static byte[] readAllBytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + file, e);
    }
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY_SLUG, TRANSCRIBED_SLUG, UserflowReport.PASSED);
    in(TRANSCRIBED_SLUG, StoryTarget.EDGE, "POST " + StoryTarget.ROUTE + " -> 200");
    // The two edges that used to be DECLARATIONS and are now observations. assertEdge (rather than
    // assertDeclaredEdge) is the whole change: it fails if either ever goes back to being a claim.
    engine(TRANSCRIBED_SLUG, StoryEngine.spawned());
    engine(TRANSCRIBED_SLUG, StoryEngine.transcribed(StoryEngine.TEXT));
    // One clip in, one conversation with the engine, and nothing else — this service's own promise
    // ("the engine should have been asked exactly once") said again from the diagram's side, where
    // a stray edge shows and a presence check could not.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, TRANSCRIBED_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, TRANSCRIBED_SLUG, List.of(StoryTarget.EDGE, StoryTarget.SERVICE));
    // THE ABSENCE WITH A WITNESS. The interpreter that builds the venv is a recording stand-in at a
    // path this process really would have run, so "no venv was built and nothing reached PyPI" is
    // an assertion here and not a sentence in a description.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, TRANSCRIBED_SLUG, StoryEngine.HOST_PYTHON);
    // The staging name is generated per request. It is templated inside the label rather than after
    // a '/', which the framework's own scrubber could not have done — so this is the check that the
    // templating worked and that no note interpolated it either.
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, TRANSCRIBED_SLUG, stagedId);
    ReportAssertions.assertStepId(CATEGORY_SLUG, TRANSCRIBED_SLUG, "clip-submitted");
    ReportAssertions.assertStepId(CATEGORY_SLUG, TRANSCRIBED_SLUG, "engine-spawned");
    ReportAssertions.assertStepId(CATEGORY_SLUG, TRANSCRIBED_SLUG, "engine-asked");
    ReportAssertions.assertStepId(CATEGORY_SLUG, TRANSCRIBED_SLUG, "engine-answered");
    ReportAssertions.assertStepId(CATEGORY_SLUG, TRANSCRIBED_SLUG, "transcript-served");

    ReportAssertions.assertComplete(CATEGORY_SLUG, REFUSED_SLUG, UserflowReport.PASSED);
    in(REFUSED_SLUG, NOBODY, "POST " + StoryTarget.ROUTE + " -> 401");
    in(REFUSED_SLUG, A_BEARER, "POST " + StoryTarget.ROUTE + " -> 401");
    in(REFUSED_SLUG, WITHOUT_ADMIN, "POST " + StoryTarget.ROUTE + " -> 403");
    in(REFUSED_SLUG, NOT_JSON, "POST " + StoryTarget.ROUTE + " -> 415");
    in(REFUSED_SLUG, NO_AUDIO, "POST " + StoryTarget.ROUTE + " -> 400");
    in(REFUSED_SLUG, NOT_BASE64, "POST " + StoryTarget.ROUTE + " -> 400");
    // THE STORY'S TITLE, ASSERTED AS A SHAPE, and it pays here in a way it could not before this
    // rollout: the engine is a node other stories DO reach, so "no edges to it" is a claim about a
    // reachable thing rather than about something no tap could have seen either way.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, REFUSED_SLUG, StoryEngine.ENGINE);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, REFUSED_SLUG, StoryEngine.HOST_PYTHON);
    ReportAssertions.assertNoEdgesFrom(CATEGORY_SLUG, REFUSED_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, REFUSED_SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        REFUSED_SLUG,
        List.of(NOBODY, A_BEARER, WITHOUT_ADMIN, NOT_JSON, NO_AUDIO, NOT_BASE64));
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, REFUSED_SLUG, BEARER);
    ReportAssertions.assertStepId(CATEGORY_SLUG, REFUSED_SLUG, "anonymous-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, REFUSED_SLUG, "non-admin-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, REFUSED_SLUG, "not-audio-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, REFUSED_SLUG, "engine-never-woken");
  }

  private static void in(String slug, String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }

  private static void engine(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, StoryEngine.KIND, StoryTarget.SERVICE, StoryEngine.ENGINE, label);
  }
}
