package eu.wohlben.qits.stt.stories.engine;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.stt.stories.home.SpeechHomeBootstrapIT;
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
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>Three ways a resident engine goes wrong, and the three different things this service does about
 * them.</b>
 *
 * <p>{@code SpeechWorker} draws a distinction that is easy to miss reading it and impossible to miss
 * seeing it: an <i>engine</i> that has stopped working is killed and respawned once, while a
 * <i>clip</i> the engine could not read is passed straight back to the caller. Those are different
 * failures with different costs — one of them throws away a warm model — and the code tells them
 * apart by which exception came out of the pipe read. There is no test in this repository that has
 * ever executed either path, because {@code TranscriptionServiceTest} replaces the worker with a
 * fake that always answers.
 *
 * <p>The stand-in engine here is <b>armable</b>: a story writes one word into the engine's own
 * directory and the next answer over the pipe becomes an exit, a refusal, or a line that is not the
 * protocol at all. The arming is a file rather than a restart, so the process under test is the
 * process that was already running, and {@code once:} disarms itself on the way through, which is
 * what lets a story prove a recovery rather than only a failure.
 *
 * <p><b>The respawn is the thing worth drawing, and it is drawn from evidence.</b> Each story's
 * diagram shows exactly what the pipe carried and exactly how many engines it took: an exit followed
 * by a spawn and a successful retry; a refusal with no spawn at all; two broken answers and one
 * spawn between them. The presence or absence of that one arrow is the whole of the policy.
 *
 * <p><b>Order is load-bearing within this class</b>, for the same reason it is across the catalogue:
 * one launched process serves all three, so the worker each story finds is the worker the story
 * before it left. The give-up story runs last because it is the one that ends with no worker at all.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EngineFailureIT {

  static final String CATEGORY = "the engine";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String GONE = "An engine that goes away is replaced before the caller notices";

  static final String GONE_SLUG = Slugs.slug(GONE);

  static final String REFUSES = "An engine that refuses a clip is not a broken engine";

  static final String REFUSES_SLUG = Slugs.slug(REFUSES);

  static final String BROKEN = "An engine that keeps breaking the protocol costs one retry, then a 500";

  static final String BROKEN_SLUG = Slugs.slug(BROKEN);

  /** How the diagram names the caller in every story here: the same operator, three outcomes. */
  private static final String OPERATOR = "an operator dictating through the edge";

  private static String goneStagedId;

  private static String refusedStagedId;

  private static String brokenStagedId;

  @BeforeAll
  static void tapBothSidesOfThisService() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryEngine.installSource();
  }

  /**
   * Disarm whatever the story armed, pass or fail. Not in a {@code finally} inside the story: a
   * story that failed on its first assertion would leave an {@code always:} fault armed for every
   * later one, and the failure would be reported against the wrong story.
   */
  @AfterEach
  void disarmTheEngine() {
    StoryEngine.disarm();
  }

  @UserStory(value = GONE, category = CATEGORY)
  @UserStoryDescription(
      """
      The engine is a child process on a host. It can be OOM-killed, it can be reaped by a restart,
      it can crash on a clip that walked into an onnxruntime bug — and none of that is visible to
      qits-stt until it writes a path onto a pipe nobody is reading any more.

      What the service does then is the one piece of resilience it has: it kills what is left of
      the worker, spawns a new one, and asks the same question again. If the second attempt
      answers, the caller gets a transcript and never learns that anything happened. That is the
      right trade for this service — the expensive thing is loading the model, not spawning the
      process, and a warm model is worth one retry.

      The diagram is the proof and it is unusually literal here: the clip goes to an engine that
      exits instead of answering, a spawn follows, and the same clip is transcribed by the second
      engine. Three arrows out of this process for one arrow in, and the caller's arrow says 200.
      """)
  @UserflowRunsAfter(SpeechHomeBootstrapIT.class)
  @Order(1)
  void anEngineThatGoesAwayIsReplaced(Interactions story) {
    int before = StoryEngine.mark();

    // One word into the engine's own directory, read by the engine at the moment it is handed a WAV
    // path — so the process under test is the process that was already running, and no restart of
    // anything is involved in setting the failure up.
    StoryEngine.arm(StoryEngine.ONCE, "die");
    story
        .note("the resident engine is set to go away on the next clip rather than answer it — the"
            + " shape of a worker the host reaped while nobody was looking")
        .as("engine-armed-to-die");

    NetworkCapture.actor(OPERATOR);
    transcribe().statusCode(200).body("text", equalTo(StoryTarget.TRANSCRIPT));
    story
        .note("the caller got a transcript, and nothing in the answer says a worker died to"
            + " produce it")
        .as("caller-never-noticed");

    // THE STORY, READ OUT OF THE RECORDING: asked, gone, respawned, asked again, answered. The
    // second engine is handed the SAME staged path, which is what makes this a retry rather than a
    // second request.
    assertEquals(
        List.of(
            StoryEngine.transcribed(StoryEngine.EXIT),
            StoryEngine.spawned(),
            StoryEngine.transcribed(StoryEngine.TEXT)),
        StoryEngine.callsSince(before),
        "a dead worker must cost exactly one respawn and one retry");
    List<StoryEngine.Staged> staged = StoryEngine.stagedSince(before);
    assertEquals(2, staged.size(), "both attempts are recorded by the engine that received them");
    assertEquals(
        staged.getFirst().path(),
        staged.getLast().path(),
        "the retry must re-ask the SAME staged clip, not stage a second copy of it");
    goneStagedId = staged.getFirst().generatedId();
    assertTrue(
        Files.notExists(staged.getFirst().path()),
        "the staged recording is still deleted on the way out, retry or not");
    story
        .note("one respawn, one retry, and the second engine was handed the same staged clip: the"
            + " recording was written once and read twice")
        .as("respawned-and-retried");
  }

  @UserStory(value = REFUSES, category = CATEGORY)
  @UserStoryDescription(
      """
      The other failure looks the same from the caller's side and is the opposite thing underneath.
      A clip that is corrupt, truncated, or not really a WAV makes the engine answer
      `{"error": "…"}` — which is the worker doing its job: it caught the exception, it survived,
      and it said what went wrong. Nothing about it is broken.

      So qits-stt does NOT respawn. It turns the engine's sentence into a 500 with that sentence in
      it and leaves the worker exactly where it is, because throwing away a warm model over a bad
      quarter-second of audio would be the expensive mistake this whole service exists to avoid.

      The diagram is where the two failures are told apart, and it is told apart by an absence:
      compare this story's two arrows with the previous story's four. One transcription, one
      refusal, and no spawn.
      """)
  @UserflowRunsAfter(SpeechHomeBootstrapIT.class)
  @Order(2)
  void anEngineThatRefusesAClipIsLeftAlone(Interactions story) {
    int before = StoryEngine.mark();

    StoryEngine.arm(StoryEngine.ONCE, "error");
    story
        .note("the engine is set to answer its own refusal on the next clip: it read the audio and"
            + " could not make sense of it, which is a fact about the audio")
        .as("engine-armed-to-refuse");

    NetworkCapture.actor(OPERATOR);
    transcribe()
        .statusCode(500)
        .contentType(ContentType.JSON)
        // The engine's own words, in the mapper's one-key envelope. Passing the sentence through is
        // worth more than anything this service could invent about somebody's recording.
        .body("message", containsString(StoryTarget.ENGINE_ERROR));
    story
        .note("the caller is told what the engine said, in the same one-key envelope every other"
            + " refusal in this service uses")
        .as("engine-refusal-passed-through");

    // THE STORY, AND IT IS AN ABSENCE: one transcription, no spawn. The worker that said no is the
    // worker that is still running.
    assertEquals(
        List.of(StoryEngine.transcribed(StoryEngine.ERROR)),
        StoryEngine.callsSince(before),
        "an engine's own refusal must not cost a respawn");
    List<StoryEngine.Staged> staged = StoryEngine.stagedSince(before);
    assertEquals(1, staged.size(), "the clip is offered exactly once, and refused exactly once");
    refusedStagedId = staged.getFirst().generatedId();
    assertTrue(
        Files.notExists(staged.getFirst().path()),
        "the staged recording is deleted in the finally block, on the failure path too");
    story
        .note("no respawn: the warm model stayed loaded, because a clip this engine could not read"
            + " says nothing at all about the engine")
        .as("no-respawn");
  }

  @UserStory(value = BROKEN, category = CATEGORY)
  @UserStoryDescription(
      """
      The realistic way a python worker breaks its own protocol is not that it stops — it is that
      something else writes to stdout. A library warning, a deprecation notice, a progress bar: any
      one of them lands in the middle of a line protocol whose whole contract, stated in
      `transcribe_worker.py`'s docstring, is that stdout carries protocol lines and nothing else.

      qits-stt cannot tell that from a worker that has come apart, and it should not try. It reads
      a line, fails to parse it, and takes the same action it takes for a dead worker: kill,
      respawn, ask once more. And when the second answer is broken too, it stops — one retry, then
      a 500 that says the worker failed rather than that the audio did.

      The bound is the point. A service that kept retrying a permanently broken worker would spawn
      a process per request forever, on a host chosen for having enough memory to hold a model.
      Three arrows out for one arrow in — and the middle one is the only spawn.
      """)
  @UserflowRunsAfter(SpeechHomeBootstrapIT.class)
  @Order(3)
  void anEngineThatKeepsBreakingTheProtocolIsGivenUpOn(Interactions story) {
    int before = StoryEngine.mark();

    // `always:` rather than `once:` — this is the give-up path, so both attempts have to break.
    // Cleared in @AfterEach, which is why the story does not need a finally of its own.
    StoryEngine.arm(StoryEngine.ALWAYS, "garbage");
    story
        .note("the engine is set to write a library warning onto the protocol pipe, every time: the"
            + " one line that must never appear there, and the one that most often does")
        .as("engine-armed-to-babble");

    NetworkCapture.actor(OPERATOR);
    transcribe()
        .statusCode(500)
        .contentType(ContentType.JSON)
        // "the worker failed", not "the audio failed" — the two 500s in this catalogue say
        // different things, which is the whole reason both are worth telling.
        .body("message", containsString("Transcription worker failed"));
    story
        .note("after one retry the caller is told the WORKER failed — a different sentence from the"
            + " one an unreadable clip earns, and the difference is the diagnosis")
        .as("given-up-on");

    // THE BOUND, READ OUT OF THE RECORDING: broken, one spawn, broken again, and then it stops.
    assertEquals(
        List.of(
            StoryEngine.transcribed(StoryEngine.NOT_JSON),
            StoryEngine.spawned(),
            StoryEngine.transcribed(StoryEngine.NOT_JSON)),
        StoryEngine.callsSince(before),
        "a permanently broken worker must cost exactly one respawn, and then no more");
    List<StoryEngine.Staged> staged = StoryEngine.stagedSince(before);
    assertEquals(2, staged.size(), "one clip, offered to two engines");
    brokenStagedId = staged.getFirst().generatedId();
    assertTrue(
        Files.notExists(staged.getFirst().path()),
        "the staged recording is deleted even when nothing could be made of it");
    story
        .note("exactly one respawn and no more: a service that retried forever would spawn a"
            + " process per request on a host chosen for holding one model in memory")
        .as("one-retry-and-no-more");
  }

  /** One clip, from an operator who is allowed to send it. Every story here differs after this. */
  private static ValidatableResponse transcribe() {
    RequestSpecification request =
        given()
            .header("X-Qits-User", StoryTarget.USER)
            .header("X-Qits-Roles", StoryTarget.ADMIN_ROLE)
            .contentType(ContentType.JSON)
            .body(Map.of("audioBase64", StoryClip.base64()));
    return request.when().post(StoryTarget.ROUTE).then();
  }

  @AfterAll
  static void allThreeEngineFailureStoriesAreComplete() {
    // --- the engine that went away ---------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, GONE_SLUG, UserflowReport.PASSED);
    in(GONE_SLUG, "POST " + StoryTarget.ROUTE + " -> 200");
    engine(GONE_SLUG, StoryEngine.transcribed(StoryEngine.EXIT));
    engine(GONE_SLUG, StoryEngine.spawned());
    engine(GONE_SLUG, StoryEngine.transcribed(StoryEngine.TEXT));
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, GONE_SLUG, 4);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, GONE_SLUG, StoryEngine.HOST_PYTHON);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, GONE_SLUG, List.of(OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, GONE_SLUG, goneStagedId);
    ReportAssertions.assertStepId(CATEGORY_SLUG, GONE_SLUG, "engine-armed-to-die");
    ReportAssertions.assertStepId(CATEGORY_SLUG, GONE_SLUG, "caller-never-noticed");
    ReportAssertions.assertStepId(CATEGORY_SLUG, GONE_SLUG, "respawned-and-retried");

    // --- the engine that said no -----------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, REFUSES_SLUG, UserflowReport.PASSED);
    in(REFUSES_SLUG, "POST " + StoryTarget.ROUTE + " -> 500");
    engine(REFUSES_SLUG, StoryEngine.transcribed(StoryEngine.ERROR));
    // THE STORY'S TITLE, ASSERTED AS A SHAPE: two edges, and the spawn the story above drew from
    // this same recording is not one of them. "Not a broken engine" means "no engine was replaced".
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, REFUSES_SLUG, 2);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, REFUSES_SLUG, StoryEngine.HOST_PYTHON);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, REFUSES_SLUG, List.of(OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, REFUSES_SLUG, refusedStagedId);
    ReportAssertions.assertStepId(CATEGORY_SLUG, REFUSES_SLUG, "engine-armed-to-refuse");
    ReportAssertions.assertStepId(CATEGORY_SLUG, REFUSES_SLUG, "engine-refusal-passed-through");
    ReportAssertions.assertStepId(CATEGORY_SLUG, REFUSES_SLUG, "no-respawn");

    // --- the engine that kept babbling -----------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, BROKEN_SLUG, UserflowReport.PASSED);
    in(BROKEN_SLUG, "POST " + StoryTarget.ROUTE + " -> 500");
    engine(BROKEN_SLUG, StoryEngine.transcribed(StoryEngine.NOT_JSON));
    engine(BROKEN_SLUG, StoryEngine.spawned());
    // THE BOUND, ASSERTED AS A SHAPE. Three edges and not four: the two broken answers are one
    // arrow, because a dependency map says what is depended on and not how many times — and the
    // count is what stops a service that retried forever from passing this story.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, BROKEN_SLUG, 3);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, BROKEN_SLUG, StoryEngine.HOST_PYTHON);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, BROKEN_SLUG, List.of(OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, BROKEN_SLUG, brokenStagedId);
    ReportAssertions.assertStepId(CATEGORY_SLUG, BROKEN_SLUG, "engine-armed-to-babble");
    ReportAssertions.assertStepId(CATEGORY_SLUG, BROKEN_SLUG, "given-up-on");
    ReportAssertions.assertStepId(CATEGORY_SLUG, BROKEN_SLUG, "one-retry-and-no-more");
  }

  private static void in(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, OPERATOR, StoryTarget.SERVICE, label);
  }

  private static void engine(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, StoryEngine.KIND, StoryTarget.SERVICE, StoryEngine.ENGINE, label);
  }
}
