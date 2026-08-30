package eu.wohlben.qits.stt.stories.home;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.stt.stories.support.StoryClip;
import eu.wohlben.qits.stt.stories.support.StoryEngine;
import eu.wohlben.qits.stt.stories.support.StoryProfile;
import eu.wohlben.qits.stt.stories.support.StoryTarget;
import eu.wohlben.qits.stt.stories.transcription.ResidentEngineIT;
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
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The one thing a qits-stt deployment actually has to be given</b>, and what happens when it is
 * not.
 *
 * <p>This service owns no tables, no datasource and no Flyway lineage. The entire content of
 * "deploying qits-stt" is a directory: {@code qits.speech.home}, holding a venv whose {@code
 * venv/bin/python} has onnx-asr in it and a Hugging Face cache with Parakeet in it. Everything else
 * — the route, the door, the worker protocol — is in the artifact. So the failure mode worth
 * documenting is not a bad request; it is a <b>volume that was never pre-seeded</b>, and the
 * question is what this service does about it.
 *
 * <p>What it does is bootstrap, lazily and once: {@code python3 -m venv} followed by {@code pip
 * install --quiet onnx-asr[cpu,hub]}, and then a ~700 MB model pull on first use. Which is exactly
 * why the story below is one this repository has to be careful about telling. It runs against a
 * stand-in interpreter that <b>records the attempt and refuses</b>, so the reach is observed, the
 * reason travels back to the caller, and nothing ever leaves for PyPI. That stand-in is also what
 * makes the same absence assertable in every other story in the catalogue: {@code
 * assertNoEdgesTo(the host python)} is a claim with a witness, because the binary is right there and
 * would have recorded.
 *
 * <p><b>The paying half is the second request.</b> A story that only showed the failure would show
 * an outage; what it has to show is that the failure was <i>in front of</i> the resident engine and
 * cost it nothing. So the speech home is put back and one more clip goes through — and it is
 * answered by the same warm process, with no spawn anywhere in the diagram. The bootstrap check is
 * a gate on the way in, not a restart.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class SpeechHomeBootstrapIT {

  static final String CATEGORY = "the speech home";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String UNSEEDED = "A speech home with no engine in it refuses out loud, not quietly";

  static final String UNSEEDED_SLUG = Slugs.slug(UNSEEDED);

  private static String stagedId;

  @BeforeAll
  static void tapBothSidesOfThisService() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryEngine.installSource();
  }

  /**
   * Put the engine back whatever happened. Not in a {@code finally} inside the story: a story that
   * failed on its first assertion would otherwise leave every later one running against a speech
   * home with no engine in it, and the failure would be reported against the wrong story.
   */
  @AfterEach
  void putTheEngineBack() {
    StoryEngine.reseedTheEngine();
  }

  @UserStory(value = UNSEEDED, category = CATEGORY)
  @UserStoryDescription(
      """
      qits-stt is deployed as an artifact plus a directory. The artifact carries the route, the
      door and the worker protocol; the directory carries a python venv with onnx-asr in it and a
      cache with a ~700 MB model in it. A deployment that forgets the directory — a fresh volume, a
      wrong mount, a host rebuilt without its cache — is the failure this story is about.

      The service handles it by bootstrapping: it looks for `<home>/venv/bin/python`, and when that
      is not there it runs `python3 -m venv` and then `pip install onnx-asr[cpu,hub]`. Both are a
      reach off the machine, which is why they are worth drawing, and why this catalogue points
      `qits.speech.python` at a recording stand-in that refuses instead of at a real interpreter.

      Two things then have to be true, and both are. The refusal is LOUD: the operator gets a 500
      carrying the interpreter's own last words rather than a hang, a timeout, or a silent
      fallback to no transcription at all — and it stops at the first command, so the pip install
      that would reach PyPI is never even attempted. And the refusal is IN FRONT of the engine: the
      resident worker that was already up is untouched by it, so the very next request, once the
      home is back, is answered by the same warm process with no spawn anywhere in the diagram.
      """)
  @UserflowRunsAfter(ResidentEngineIT.class)
  void aSpeechHomeWithNoEngineInItRefusesOutLoud(Interactions story) {
    int before = StoryEngine.mark();

    // The whole of "this volume was never pre-seeded", in one move: TranscriptionService.ensureReady
    // decides whether to bootstrap by asking whether <home>/venv/bin/python exists. A rename rather
    // than a delete, because the resident worker is a shell reading its own script through an open
    // descriptor — it holds the inode, and a rename does not disturb it, which is itself half of
    // what this story goes on to prove.
    StoryEngine.unseedTheEngine();
    story
        .note("the speech home loses its engine, which is the state a deployment given a fresh"
            + " volume starts in")
        .as("home-unseeded");

    NetworkCapture.actor(StoryTarget.EDGE);
    given()
        .header("X-Qits-User", StoryTarget.USER)
        .header("X-Qits-Roles", StoryTarget.ADMIN_ROLE)
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", StoryClip.base64()))
        .when()
        .post(StoryTarget.ROUTE)
        .then()
        .statusCode(500)
        .contentType(ContentType.JSON)
        // The mapper's one-key envelope, carrying the interpreter's own stderr. Passing that
        // sentence through is worth more than anything this service could invent about it.
        .body("message", containsString("create the speech venv"))
        .body("message", containsString(StoryTarget.NO_PYTHON));
    story
        .note("the next transcription is refused with the interpreter's own last words in the"
            + " envelope: an operator learns what is missing, from the answer, on the first try")
        .as("bootstrap-refused");

    // ONE CALL, AND IT IS THE FIRST ONE. `pip install onnx-asr[cpu,hub]` — the reach for PyPI — is
    // never attempted, because the venv step failed and TranscriptionService.run throws rather than
    // carrying on. And the engine was not asked either: the bootstrap check runs before the worker
    // is ever consulted.
    assertEquals(
        List.of(StoryEngine.venvAttempted()),
        StoryEngine.callsSince(before),
        "the venv step must fail loudly and stop, without a pip install and without an engine");
    story
        .note("and it stopped there: no pip install, so nothing reached PyPI, and no clip reached"
            + " the engine either — the bootstrap check is a gate on the way in")
        .as("nothing-reached-pypi");

    // The pre-seeded volume comes back, which is the operator's fix.
    StoryEngine.reseedTheEngine();

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

    // THE PAYING HALF. One more call in the recording — a transcription — and NO SPAWN: the worker
    // that was resident before the failure is the worker that answers after it. A bootstrap failure
    // is a refused request, not a restart.
    assertEquals(
        List.of(StoryEngine.venvAttempted(), StoryEngine.transcribed(StoryEngine.TEXT)),
        StoryEngine.callsSince(before),
        "the resident engine must survive a failed bootstrap in front of it");
    List<StoryEngine.Staged> staged = StoryEngine.stagedSince(before);
    assertEquals(1, staged.size(), "only the second request ever staged anything");
    stagedId = staged.getFirst().generatedId();
    assertTrue(
        Files.notExists(staged.getFirst().path()),
        "the staged recording must be deleted once transcribed");
    story
        .note("with the home restored the very next clip comes back transcribed, answered by the"
            + " process that was already warm: the failure never reached the engine to break it")
        .as("warm-engine-survived");
  }

  @AfterAll
  static void theSpeechHomeStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, UNSEEDED_SLUG, UserflowReport.PASSED);
    in(StoryTarget.EDGE, "POST " + StoryTarget.ROUTE + " -> 500");
    in(StoryTarget.EDGE, "POST " + StoryTarget.ROUTE + " -> 200");
    // The reach for the host interpreter, observed — the one story in the catalogue where this node
    // appears at all, which is exactly what makes assertNoEdgesTo(HOST_PYTHON) mean something in the
    // other five.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        UNSEEDED_SLUG,
        StoryEngine.KIND,
        StoryTarget.SERVICE,
        StoryEngine.HOST_PYTHON,
        StoryEngine.venvAttempted());
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        UNSEEDED_SLUG,
        StoryEngine.KIND,
        StoryTarget.SERVICE,
        StoryEngine.ENGINE,
        StoryEngine.transcribed(StoryEngine.TEXT));
    // FOUR EDGES, AND THE FIFTH IS THE ONE THAT MATTERS BY BEING ABSENT: no spawn. The count is the
    // instrument rather than assertNoEdgesTo, because the engine WAS reached — what did not happen
    // is that it was reached in a new way.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, UNSEEDED_SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, UNSEEDED_SLUG, List.of(StoryTarget.EDGE, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, UNSEEDED_SLUG, stagedId);
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNSEEDED_SLUG, "home-unseeded");
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNSEEDED_SLUG, "bootstrap-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNSEEDED_SLUG, "nothing-reached-pypi");
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNSEEDED_SLUG, "warm-engine-survived");
  }

  private static void in(String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, UNSEEDED_SLUG, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }
}
