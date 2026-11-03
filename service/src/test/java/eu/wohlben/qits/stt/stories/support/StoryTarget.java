package eu.wohlben.qits.stt.stories.support;

import eu.wohlben.qits.stt.security.BearerTokens;

/**
 * The names and addresses every story in this catalogue shares — spelled once, so a diagram and the
 * assertion that pins it cannot disagree about what a thing is called.
 *
 * <p><b>A name here is a stable literal, never a run stamp.</b> {@code
 * eu.wohlben.qits.userflows.Labels} rewrites only what it can tell was generated — a UUID, a long
 * hex run, a bare numeric path segment — so anything else in a label survives into the story's
 * {@code networkHash}. A fixture named after a temp directory or a timestamp would move that hash on
 * every run, and the only symptom is a hash that never settles.
 */
public final class StoryTarget {

  /**
   * How every diagram in this catalogue names the launched process, on both sides of an edge: it is
   * the {@code to} of everything a story sends here and the {@code from} of everything it spawns.
   */
  public static final String SERVICE = "qits-stt";

  /** The one route this service has. {@code quarkus.rest.path} is {@code /stt/api}. */
  public static final String ROUTE = "/stt/api/transcriptions";

  /**
   * The readiness probe, under {@code quarkus.http.non-application-root-path}. It carries a {@code
   * /q/} segment, so the shipped RestAssured tap's default skip covers it and a story may say "the
   * service is up" without putting a node in the diagram that hangs off a health check.
   */
  public static final String READY = "/stt/q/health/ready";

  /**
   * Who a real transcription arrives through. It is <b>qits-platform-edge</b> and it read {@code
   * qits-gateway} until this migration: that service no longer exists — the edge replaced the whole
   * gateway tier — and a diagram naming a node nobody can deploy is worse than no diagram. The edge
   * asserts no identity any more (epic qits-543, qits-1373): the operator's browser sends its own
   * qits-idp bearer through it, and this service checks that bearer itself (qits-1370).
   */
  public static final String EDGE = "qits-platform-edge";

  /** The role the one route names. */
  public static final String ADMIN_ROLE = "qits:admin";

  /**
   * A real platform role that is <b>not</b> the one this route names. Used to draw the difference
   * between "we do not know who you are" (401) and "we know exactly who you are, and it is not
   * enough" (403).
   */
  public static final String READER_ROLE = "qits:reader";

  /** The operator every story that gets past the door arrives as. */
  public static final String USER = "alice";

  /**
   * The {@code Authorization} value of a signed-in operator holding {@link #ADMIN_ROLE}: a person's
   * qits-idp bearer, signed with the suite's key, which {@link StoryProfile} hands the tenant.
   */
  public static String adminBearer() {
    return BearerTokens.bearer(BearerTokens.personToken(ADMIN_ROLE));
  }

  /** The same operator's bearer holding only {@link #READER_ROLE}. */
  public static String readerBearer() {
    return BearerTokens.bearer(BearerTokens.personToken(READER_ROLE));
  }

  /** The transcript the stand-in engine answers with — the payload that has to survive the trip. */
  public static final String TRANSCRIPT = "the release train leaves at nine";

  /**
   * What the stand-in engine says when a story arms its own refusal: the shape of a worker that
   * survived a clip it could not read. {@code SpeechWorker} turns it into a 500 with this sentence
   * in the message, without respawning anything, which is the distinction the failure stories draw.
   */
  public static final String ENGINE_ERROR = "the audio could not be decoded";

  /**
   * What the stand-in engine prints when a story arms the OTHER failure: a line that is not the
   * protocol at all. It is written as a library warning rather than as the word "garbage" because
   * that is what the failure really is in a python worker — anything at all reaching stdout beside
   * the protocol corrupts the line the service is waiting for, which is why {@code
   * transcribe_worker.py}'s own docstring says stdout carries protocol lines and nothing else.
   */
  public static final String ENGINE_NOISE = "UserWarning: onnxruntime is falling back to CPU";

  /** What the stand-in host python prints on stderr, and therefore what the 500 has to carry. */
  public static final String NO_PYTHON = "there is no python on this host";

  /**
   * The scrubbed marker a generated id becomes in a label. Authored here rather than interpolated,
   * because a story that put a real staging uuid in a label would move its own {@code networkHash}
   * on every run — the name is generated per request by definition.
   */
  public static final String ID = "{id}";

  private StoryTarget() {}
}
