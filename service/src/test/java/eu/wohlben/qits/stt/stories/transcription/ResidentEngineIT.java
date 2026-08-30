package eu.wohlben.qits.stt.stories.transcription;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.stt.api.TranscriptionBootstrapIT;
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
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>Why this is a service and not a function call.</b>
 *
 * <p>Everything about qits-stt's shape — a host-side python, a process that outlives a request, a
 * README paragraph arguing against containerising it — rests on one claim: the model is loaded
 * <i>once</i> and stays loaded, so a second utterance costs inference and nothing else. That claim
 * has never been checkable. {@code TranscriptionServiceTest} replaces {@code SpeechWorker} with a
 * fake, and the fake is a method call; the packaged IT before this one spawned a worker but only
 * ever asked it for one thing.
 *
 * <p>It is checkable now, and the diagram is where it reads: this story sends two more clips through
 * the same launched process and the engine's recording gains <b>two transcriptions and no spawn</b>.
 * The absence is the whole story, and it is an absence of a specific, reachable thing — the story
 * before this one drew that exact spawn edge from the same recording. So the pinned edge count of
 * two, with the spawn edge among the arrows that are <i>not</i> there, is the resident-worker design
 * asserted rather than described.
 *
 * <p><b>Two POSTs and two transcriptions collapse into two edges</b>, because the framework dedupes
 * on the whole {@code (kind, from, to, label)} quadruple and both requests are the same request. That
 * is the right rendering: a dependency map says <i>what this depends on</i>, not how many times. The
 * count that matters here is the one taken from the recording, in the story, where it is a number
 * and not a picture.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class ResidentEngineIT {

  static final String CATEGORY = "transcription";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String WARM = "The engine stays warm, and a second clip is not a second engine";

  static final String WARM_SLUG = Slugs.slug(WARM);

  /** Both generated staging names, kept so the report can be proved free of either. */
  private static String firstStagedId;

  private static String secondStagedId;

  @BeforeAll
  static void tapBothSidesOfThisService() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryEngine.installSource();
  }

  @UserStory(value = WARM, category = CATEGORY)
  @UserStoryDescription(
      """
      Loading Parakeet costs seconds. That single fact is why qits-stt is a service with a resident
      child process rather than a library call that shells python per request — README.md argues it
      at length, and until now nothing checked it.

      Two more clips go through the same launched service. Both come back with a transcript, both
      are staged and deleted under their own generated names, and the engine's recording gains two
      transcriptions and NOT ONE SPAWN: the process that answered the first story is the process
      that answers these. The diagram says the same thing by what it is missing — two arrows, and
      the spawn arrow the previous story drew from this very recording is not among them.

      It is also the only place the serialization is visible from outside. `SpeechWorker.transcribe`
      is synchronized and the worker is single-threaded, so "requests are serialized" is not a rate
      limit somebody configured — it is the shape of one process holding one pipe, and two clips
      answered in order by one child is what that looks like.
      """)
  @UserflowRunsAfter(TranscriptionBootstrapIT.class)
  void aSecondClipReusesTheProcessThatIsAlreadyWarm(Interactions story) {
    int before = StoryEngine.mark();

    NetworkCapture.actor(StoryTarget.EDGE);
    transcribe();
    story
        .note("a second recording arrives from the edge, on the same route and with the same"
            + " forwarded identity as the first")
        .as("second-clip-submitted");

    transcribe();
    story
        .note("and a third, so that what follows is a claim about a run of requests rather than"
            + " about one lucky one")
        .as("third-clip-submitted");

    // THE STORY, READ OUT OF THE RECORDING. Two transcriptions, no spawn: the resident process is
    // the design, and this is the only posture in the repository where it executes at all.
    List<String> calls = StoryEngine.callsSince(before);
    assertEquals(
        List.of(
            StoryEngine.transcribed(StoryEngine.TEXT), StoryEngine.transcribed(StoryEngine.TEXT)),
        calls,
        "two clips must cost two transcriptions and no second engine");
    story
        .note("the engine's own recording gained two transcriptions and no spawn: the model stayed"
            + " loaded, which is the whole argument for this being a service")
        .as("no-second-engine");

    // Two clips, two staging names, and neither of them still on disk. The names differ because
    // they are generated per request, which is also why neither may ever reach a label.
    List<StoryEngine.Staged> staged = StoryEngine.stagedSince(before);
    assertEquals(2, staged.size(), "each clip is staged in its own file");
    firstStagedId = staged.getFirst().generatedId();
    secondStagedId = staged.getLast().generatedId();
    assertNotEquals(
        firstStagedId, secondStagedId, "two requests must not share one staging path");
    for (StoryEngine.Staged clip : staged) {
      assertEquals(
          StoryClip.BYTES, clip.bytes(), "the engine must be handed the whole recording every time");
      assertTrue(
          Files.notExists(clip.path()),
          "every staged recording must be deleted once transcribed: " + clip.path());
    }
    story
        .note("both clips were staged under their own generated names and both are gone: the"
            + " resident half of this service is the process, never the audio")
        .as("nothing-kept");
  }

  private static void transcribe() {
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
  }

  @AfterAll
  static void theResidentEngineStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, WARM_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.EDGE,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.ROUTE + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        WARM_SLUG,
        StoryEngine.KIND,
        StoryTarget.SERVICE,
        StoryEngine.ENGINE,
        StoryEngine.transcribed(StoryEngine.TEXT));
    // THE TITLE, ASSERTED AS A SHAPE. Exactly two edges — so the spawn edge the previous story drew
    // from this same recording is provably not among them. A count is the right instrument here
    // rather than assertNoEdgesTo: the engine WAS reached, twice; what did not happen is that it was
    // reached in a new way.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, WARM_SLUG, 2);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, WARM_SLUG, List.of(StoryTarget.EDGE, StoryTarget.SERVICE));
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, WARM_SLUG, StoryEngine.HOST_PYTHON);
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, WARM_SLUG, firstStagedId);
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, WARM_SLUG, secondStagedId);
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "second-clip-submitted");
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "third-clip-submitted");
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "no-second-engine");
    ReportAssertions.assertStepId(CATEGORY_SLUG, WARM_SLUG, "nothing-kept");
  }
}
