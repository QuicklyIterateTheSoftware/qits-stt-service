package eu.wohlben.qits.stt.stories.support;

import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>The recording engine the story catalogue runs against</b>, and the tap that draws what the
 * launched service asked its child processes for.
 *
 * <h2>Why the pipe conversation is observed here and no longer declared</h2>
 *
 * <p>The first rollout of these stories <em>declared</em> the two process edges — the spawn, and the
 * one request/reply over the worker's pipes — on the reasoning that "no tap can stand in front of a
 * pipe". That reasoning was wrong by one step, and qits-platform-system is what proved it: this
 * service never opens a socket to a speech engine, it <b>spawns a program</b> and reads its pipes,
 * and which program that is arrives as one runtime key, {@code qits.speech.home}. So the honest
 * stand-in for its one outbound dependency is not a claim — it is an executable, and this repository
 * has staged one since the IT was written. Making it <em>record</em> is the whole difference between
 * a diagram that says "we believe this happened" and one that says "here is what was asked and what
 * came back".
 *
 * <p>So both edges are {@link NetworkEdge#PROCESS} edges, and both are <b>observed</b>. What
 * survives as an out-of-reach dependency is stated in the story descriptions rather than drawn: the
 * real engine's first-ever start pulls ~700 MB from the Hugging Face hub, and the venv bootstrap
 * behind it reaches PyPI. Neither happens in a pre-seeded speech home, so neither is a claim this
 * catalogue is entitled to draw — a declared edge for traffic the run deliberately prevented would
 * be exactly the dishonesty the {@code declared} flag exists to avoid.
 *
 * <h2>Two stand-ins, because the service has two child processes</h2>
 *
 * <ul>
 *   <li>{@code <home>/venv/bin/python} — the resident worker. {@code SpeechWorker.ensureProcess}
 *       runs it with {@code <home>/transcribe_worker.py} as its one argument, reads a greeting line,
 *       then writes one staged WAV path per request and reads one JSON line back. A dozen lines of
 *       {@code /bin/sh} at that path is a complete engine as far as this service is concerned.
 *   <li>{@code <home>/host/python3} — the interpreter {@code qits.speech.python} names, used only to
 *       <b>create</b> the venv when the resident one is absent. It exists so that the reach for PyPI
 *       is <b>observable as an absence</b>: every ordinary story can assert {@code
 *       assertNoEdgesTo(HOST_PYTHON)} and mean it, because the binary is right there and would have
 *       recorded. Pointing that key at a path that cannot exist — which is what the first rollout
 *       did — makes the same absence unprovable.
 * </ul>
 *
 * <h2>The catalogue's directory, and why the recording has no floor</h2>
 *
 * <p>{@link #install()} wipes and prepares {@code target/userflow-speech-home/} once per JVM and
 * parks its path in a system property; the recording lands in {@code <home>/engine/results.log},
 * which nothing but the launched process's children ever writes. Everything shared is a file or a
 * system property, because nothing here shares a heap: {@link #install()} is called from a {@code
 * QuarkusTestProfile}, which Quarkus instantiates in more than one classloader, and the stand-ins
 * are children of a <i>second</i> JVM.
 *
 * <p>The source is registered at <b>zero, with no floor</b>. This service makes no call at boot at
 * all — {@code qits.speech.warmup-on-start} is left at its shipped {@code false}, so the very first
 * spawn is caused by the very first request — which is why the first story in the catalogue can
 * claim the spawn as its own. Run a later class on its own and its first story inherits that spawn
 * and fails its edge count: loudly, which is the right way for that assumption to break.
 *
 * <h2>What a line becomes</h2>
 *
 * <p>Each stand-in appends {@code <who><TAB><answer><TAB><verb><TAB>arg…}, and {@link #summarize}
 * reduces it to the sentence a reader of a dependency map needs — {@code spawn venv/bin/python
 * transcribe_worker.py}, {@code transcribe tmp/{id}.wav}, {@code python3 -m venv venv}. Never the
 * absolute paths: the speech home is under {@code target/} and therefore under whatever directory
 * this repository was cloned into, and one absolute path in a label moves the story's {@code
 * networkHash} on every machine. The answer follows as {@code -> text}, {@code -> error}, {@code ->
 * not json}, {@code -> exit}, {@code -> running}, {@code -> 1}, in the shape an HTTP label's status
 * has, because it is the same half of the evidence: that the call was <i>answered</i>, and how.
 *
 * <p><b>The vocabulary is closed and the answers are shapes, never content.</b> A transcript is a
 * recording of somebody's voice turned into words, and the one thing this service promises is that
 * it keeps none of it — so the label says {@code -> text} and not what the text was. The finer
 * questions are asked of the same recording rather than of the diagram: {@link #stagedSince} reads
 * the staged path and its size back out, which is how "the clip really was written to disk, all
 * {@link StoryClip#BYTES} bytes of it" and "and now it is gone" become two measurements.
 */
public final class StoryEngine {

  /**
   * How a diagram names the far end of the pipes. Not a host and not a port — a resident child
   * process holding the model, which is the whole reason this context is a service of its own.
   */
  public static final String ENGINE = "the speech engine";

  /**
   * How a diagram names the other child: the host interpreter that would build the venv. A separate
   * node from the engine on purpose — they are two different programs, reached for two different
   * reasons, and only one of them is on the path of a request that works.
   */
  public static final String HOST_PYTHON = "the host python";

  /** The kind both edges carry: a spawned process talked to over its pipes. */
  public static final String KIND = NetworkEdge.PROCESS;

  /** The marker a still-running child carries where a completed call carries its exit code. */
  public static final String RUNNING = "running";

  /** The engine answered a transcript. */
  public static final String TEXT = "text";

  /** The engine answered its own refusal — {@code {"error": …}}, which is a 500 and no respawn. */
  public static final String ERROR = "error";

  /** The engine answered something that is not the protocol, which is a respawn. */
  public static final String NOT_JSON = "not json";

  /** The engine was asked and then simply went away, which is also a respawn. */
  public static final String EXIT = "exit";

  /** Arm the next request only, and disarm on the way through — the shape of a RECOVERY story. */
  public static final String ONCE = "once:";

  /** Arm every request until a story clears it — the shape of a GIVE-UP story. */
  public static final String ALWAYS = "always:";

  /** Where the prepared speech home is parked for the story methods and the second classloader. */
  private static final String HOME_PROPERTY = "qits.stt.it.speech-home";

  private static final String SOURCE_ID = "story-engine";

  private static final Pattern UUID =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final Object LOCK = new Object();

  private static boolean registered;

  /** How many recorded lines are already edges. The framework's own cursor slices what it returns. */
  private static int harvested;

  /** Everything ever emitted, in order. Only grows, so the framework's cursor cannot re-slice it. */
  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryEngine() {}

  // --- the binaries -----------------------------------------------------------------------------

  /**
   * Prepare, once per JVM, a speech home that already holds an engine — the pre-seeded volume a
   * deployment without PyPI or Hugging Face access supplies, which is a posture {@code
   * docker/Dockerfile} already documents ("a deployment that cannot reach either registry pre-seeds
   * the VOLUME instead"). Answers the home to hand {@code qits.speech.home}.
   *
   * <p>Under {@code target/} so {@code mvn clean} owns its lifetime, and wiped on the way in so a
   * previous run's recording can never stand in for this one's. Idempotent per JVM through the
   * parked property: the profile is instantiated more than once and only the first copy has any
   * business wiping anything — by the time the second asks, the launched process may already be
   * booting against it.
   *
   * <p>The mode has to be set here: a {@code ProcessBuilder} on a non-executable file fails with
   * "Permission denied", which reads like a sandbox problem and is not one.
   */
  public static synchronized Path install() {
    String parked = System.getProperty(HOME_PROPERTY);
    if (parked != null) {
      return Path.of(parked);
    }
    Path home = Path.of("target", "userflow-speech-home").toAbsolutePath().normalize();
    try {
      deleteRecursively(home);
      write(home.resolve("venv").resolve("bin").resolve("python"), engineScript());
      write(home.resolve("host").resolve("python3"), hostPythonScript());
    } catch (IOException e) {
      throw new UncheckedIOException("could not prepare the pre-seeded speech home " + home, e);
    }
    System.setProperty(HOME_PROPERTY, home.toString());
    return home;
  }

  /**
   * The speech home the profile prepared, read from the property it parked it in rather than
   * prepared a second time — a story must never be able to wipe the engine out from under the
   * running application.
   */
  public static Path home() {
    String parked = System.getProperty(HOME_PROPERTY);
    if (parked == null) {
      throw new IllegalStateException("the profile prepares the speech home before the launch");
    }
    return Path.of(parked);
  }

  /** The interpreter {@code qits.speech.python} is pointed at — the venv builder that refuses. */
  public static Path hostPython() {
    return home().resolve("host").resolve("python3");
  }

  /** The resident worker's executable, at exactly the path {@code SpeechWorker} runs. */
  public static Path residentPython() {
    return home().resolve("venv").resolve("bin").resolve("python");
  }

  /** Where the answered calls are recorded — the stand-ins' own directory, nobody else's. */
  public static Path resultLog() {
    return home().resolve("engine").resolve("results.log");
  }

  // --- arming -----------------------------------------------------------------------------------

  /**
   * Arm the engine's next answer (or every answer, with {@link #ALWAYS}) — {@code arm(ONCE, EXIT)}
   * is "the resident process is gone when the next clip arrives".
   *
   * <p>The file is read by the engine at the moment it is handed a WAV path, so arming is a write
   * before the request and never a restart. A story that arms must disarm in an {@code @AfterEach}
   * and not merely at the end of its happy path: a story that failed mid-way would otherwise leave
   * the next one's engine broken, and the failure would be reported against the wrong story.
   */
  public static void arm(String duration, String fault) {
    try {
      Path mode = home().resolve("engine").resolve("mode");
      Files.createDirectories(mode.getParent());
      Files.writeString(mode, duration + fault);
    } catch (IOException e) {
      throw new UncheckedIOException("could not arm the engine", e);
    }
  }

  /** Clear any armed fault. Safe to call when nothing is armed. */
  public static void disarm() {
    try {
      Files.deleteIfExists(home().resolve("engine").resolve("mode"));
    } catch (IOException e) {
      throw new UncheckedIOException("could not disarm the engine", e);
    }
  }

  // --- what a story class calls -----------------------------------------------------------------

  /**
   * Register the recording as a cumulative {@link NetworkCapture} source, once per JVM.
   *
   * <p>Called from every story class's {@code @BeforeAll} so each class is self-contained; whichever
   * runs first does the work, and the {@code registered} gate is what stops a second install drawing
   * every call twice.
   */
  public static void installSource() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      harvested = 0;
      EDGES.clear();
      NetworkCapture.source(SOURCE_ID, StoryEngine::edges);
      registered = true;
    }
  }

  /**
   * How many calls the stand-ins have answered so far — a story's own <b>starting line</b>.
   *
   * <p>The recording is cumulative and one launched process serves the whole catalogue, so a bare
   * "the log holds no transcribe" would be a claim about the RUN rather than about the story, and it
   * is wrong in exactly the case worth asserting: the refusal story runs beside stories that
   * legitimately transcribed something. Take a mark before acting and read {@link #callsSince}
   * afterwards, and the question becomes "what did THIS story ask of a child process".
   */
  public static int mark() {
    return recorded().size();
  }

  /** Every call answered since {@code mark}, as the labels the diagram carries. */
  public static List<String> callsSince(int mark) {
    List<String> summarized = new ArrayList<>();
    List<List<String>> lines = recorded();
    for (List<String> fields : lines.subList(Math.min(mark, lines.size()), lines.size())) {
      summarized.add(label(fields));
    }
    return List.copyOf(summarized);
  }

  /**
   * The staged clips the engine was handed since {@code mark}: the path it read off its pipe, and
   * the size that path had <b>at the moment the engine looked at it</b>.
   *
   * <p>The diagram carries summaries, so a claim about the FILE — that the recording really was
   * written to disk before the engine was told about it, and that it is not there afterwards — reads
   * the recording instead. Same evidence, finer question.
   */
  public static List<Staged> stagedSince(int mark) {
    List<Staged> staged = new ArrayList<>();
    List<List<String>> lines = recorded();
    for (List<String> fields : lines.subList(Math.min(mark, lines.size()), lines.size())) {
      if (fields.size() >= 5 && "engine".equals(fields.get(0)) && "transcribe".equals(fields.get(2))) {
        staged.add(new Staged(Path.of(fields.get(3)), Long.parseLong(fields.get(4).trim())));
      }
    }
    return List.copyOf(staged);
  }

  /**
   * The whole argv of the last recorded call whose summary matches, or an empty list.
   *
   * <p>The diagram carries summaries, so a claim about an <b>argument</b> — that the script the
   * engine was handed is the one this service materialized under the speech home, absolutely and
   * not merely by name — reads the argv instead. Same recording, finer question.
   */
  public static List<String> argvOf(String summary) {
    List<String> found = List.of();
    for (List<String> fields : recorded()) {
      if (summarize(fields).equals(summary)) {
        found = fields.subList(2, fields.size());
      }
    }
    return found;
  }

  /** One clip as the engine saw it: where it was staged, and how many bytes were in it. */
  public record Staged(Path path, long bytes) {

    /** The generated half of the staged name, which must never reach a label or a note. */
    public String generatedId() {
      String name = path.getFileName().toString();
      return name.endsWith(".wav") ? name.substring(0, name.length() - 4) : name;
    }
  }

  // --- the labels a story asserts ---------------------------------------------------------------

  /** The summary a spawn renders as — the fork and the exec, without the answer beside it. */
  public static final String SPAWN = "spawn venv/bin/python transcribe_worker.py";

  /** The summary one transcription renders as. The staged name is generated, so it is templated. */
  public static final String TRANSCRIBE = "transcribe tmp/" + StoryTarget.ID + ".wav";

  /** The label the one spawn edge carries. The engine is spawned with the script, and stays up. */
  public static String spawned() {
    return SPAWN + " -> " + RUNNING;
  }

  /** The label a transcription edge carries, with {@code answer} one of the four shapes above. */
  public static String transcribed(String answer) {
    return TRANSCRIBE + " -> " + answer;
  }

  /** The label the venv bootstrap's one call carries. The exit code is the refusal. */
  public static String venvAttempted() {
    return "python3 -m venv venv -> 1";
  }

  // --- the source -------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      List<List<String>> lines = recorded();
      if (harvested > lines.size()) {
        // The file was truncated under us. Start over rather than mis-slice a prefix.
        harvested = 0;
        EDGES.clear();
      }
      for (List<String> fields : lines.subList(harvested, lines.size())) {
        EDGES.add(new NetworkEdge(KIND, StoryTarget.SERVICE, node(fields), label(fields)));
      }
      harvested = lines.size();
      return List.copyOf(EDGES);
    }
  }

  private static String node(List<String> fields) {
    return "host-python".equals(fields.get(0)) ? HOST_PYTHON : ENGINE;
  }

  /** The label one answered call renders as — the summary, then how it was answered. */
  static String label(List<String> fields) {
    return summarize(fields) + " -> " + fields.get(1);
  }

  /**
   * One recorded line as the label a reader wants.
   *
   * <p>The vocabulary is CLOSED — {@code SpeechWorker} and {@code TranscriptionService} between them
   * spawn exactly three command lines, and two of them are this — so this is a table rather than a
   * heuristic, with a fallback that still shows a call nobody expected rather than swallowing it.
   */
  static String summarize(List<String> fields) {
    List<String> argv = fields.subList(2, fields.size());
    if ("host-python".equals(fields.get(0))) {
      // `python3 -m venv <home>/venv`, and then — only if that had succeeded — a pip install. The
      // program name is not in the argv (it is $0), so it is written back in: a diagram that said
      // only "-m venv" would not name the thing being reached for.
      StringBuilder command = new StringBuilder("python3");
      for (String argument : argv) {
        command.append(' ').append(relativize(argument));
      }
      return command.toString();
    }
    if (argv.isEmpty()) {
      return ENGINE;
    }
    return switch (argv.getFirst()) {
      // The fork and the exec, once for the life of the process. The argv is the materialized
      // worker script; the interpreter is $0 and is written back in for the same reason as above.
      case "spawn" -> "spawn venv/bin/python " + relativize(argv.get(1));
      // The whole request protocol: one staged WAV path in. The size the engine measured travels in
      // the recording beside it and deliberately NOT in the label — it is an assertion, not a
      // dependency.
      case "transcribe" -> "transcribe " + relativize(argv.get(1));
      default -> argv.getFirst();
    };
  }

  /**
   * A recorded path as a label may carry it: relative to the speech home, with the generated half of
   * a staged name templated.
   *
   * <p>Both halves are necessary and neither is the framework's job. The home is an absolute path
   * under {@code target/}, so it differs on every machine and every checkout — {@code Labels} would
   * not touch it, because there is nothing about it that says "generated", and one absolute path in
   * a label is a {@code networkHash} that never settles. The staging name is {@code <uuid>.wav},
   * where the uuid is not a whole path segment either, so the framework's scrubber cannot see it:
   * templating inside the token is the caller's job, exactly as it is in qits-platform-system's
   * docker summaries.
   */
  private static String relativize(String token) {
    String home = home().toString();
    String relative = token.startsWith(home + "/") ? token.substring(home.length() + 1) : token;
    return UUID.matcher(relative).replaceAll(StoryTarget.ID);
  }

  // --- reading the recording --------------------------------------------------------------------

  /**
   * The recording's complete lines, each split into {@code [who, answer, verb, arg…]}.
   *
   * <p>A missing file is an empty recording rather than a failure, and an <b>unterminated tail is
   * dropped</b>: a stand-in appends while this reads, and half a line would shape half an edge. The
   * next read sees it whole.
   */
  private static List<List<String>> recorded() {
    Path log = resultLog();
    if (!Files.isRegularFile(log)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(log, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    List<List<String>> lines = new ArrayList<>();
    for (String line : text.substring(0, lastComplete).split("\n")) {
      List<String> fields = List.of(line.split("\t", -1));
      if (fields.size() >= 2) {
        lines.add(fields);
      }
    }
    return List.copyOf(lines);
  }

  // --- the stand-ins themselves -----------------------------------------------------------------

  private static void write(Path executable, String script) throws IOException {
    Files.createDirectories(executable.getParent());
    Files.writeString(executable, script);
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  /**
   * The resident worker's stand-in. It derives the speech home from {@code $0} rather than being
   * templated with it — a path baked into a fixture is a path that has to be right twice — and the
   * two substitutions are the transcript and the two failure lines, so each is spelled exactly once,
   * in {@link StoryTarget}, where the assertions read them.
   */
  private static String engineScript() {
    return """
        #!/bin/sh
        # A stand-in for the resident Parakeet worker (see speech/transcribe_worker.py), staged by
        # qits-stt's userflow catalogue. It loads no model and needs no python: the protocol is one
        # greeting line, then one WAV path in and one JSON line out, forever.
        #
        # AND IT RECORDS, which is what makes the pipe conversation evidence rather than a claim:
        # `<who><TAB><answer><TAB><verb><TAB>arg…` into <home>/engine/results.log, read back by
        # StoryEngine. Never onto stdout — that carries protocol lines and nothing else, which is
        # the rule transcribe_worker.py's own docstring states and the rule one armed fault below
        # deliberately breaks.
        home=${0%/venv/bin/python}
        dir=$home/engine
        mkdir -p "$dir"
        tab=$(printf '\\t')

        record() {
          answer=$1
          shift
          line="engine$tab$answer"
          for field in "$@"; do line="$line$tab$field"; done
          printf '%s\\n' "$line" >> "$dir/results.log"
        }

        # A LONG-LIVED CHILD RECORDS ONCE, AT SPAWN, with the word `running` where an exit code would
        # go: it is still there when the story looks at it, and the service ends it with
        # Process.destroy(), which no EXIT trap is guaranteed to survive. A code recorded there would
        # be a code that sometimes exists, which is worse than no code at all.
        record running spawn "$1"
        echo '{"ready": true}'

        # The armed fault, read fresh on every request so arming is a write and never a restart.
        # `once:` disarms itself on the way through, which is what lets a story prove a RECOVERY;
        # `always:` stays until the story clears it, which is how the give-up path is reached.
        mode() {
          armed=""
          if [ -f "$dir/mode" ]; then
            armed=$(cat "$dir/mode" </dev/null)
            case "$armed" in
              once:*) rm -f "$dir/mode"; armed=${armed#once:} ;;
              always:*) armed=${armed#always:} ;;
              *) armed="" ;;
            esac
          fi
          echo "$armed"
        }

        while IFS= read -r wav; do
          [ -n "$wav" ] || continue
          # The size the clip HAS, measured by the far side at the moment it was told about it. It
          # is the evidence for "the recording really was staged", and its counterpart is the
          # story's own check that the path is gone once the answer has been served.
          bytes=$(wc -c < "$wav" 2>/dev/null || echo 0)
          bytes=$(echo "$bytes" | tr -d ' ')
          case "$(mode)" in
            # A library wrote to stdout. This is the realistic corruption of a line protocol, and
            # the service's answer to it is to kill the worker and respawn it once.
            garbage) record "not json" transcribe "$wav" "$bytes"; echo '@NOISE@' ;;
            # The worker's OWN refusal: it survived, and the clip is what it could not read. No
            # respawn — there is nothing wrong with the worker.
            error) record error transcribe "$wav" "$bytes"; echo '{"error": "@ERROR@"}' ;;
            # The resident process is simply gone, and the service finds out when stdout closes.
            die) record exit transcribe "$wav" "$bytes"; exit 0 ;;
            *) record text transcribe "$wav" "$bytes"; echo '{"text": "@TRANSCRIPT@"}' ;;
          esac
        done
        """
        .replace("@TRANSCRIPT@", StoryTarget.TRANSCRIPT)
        .replace("@ERROR@", StoryTarget.ENGINE_ERROR)
        .replace("@NOISE@", StoryTarget.ENGINE_NOISE);
  }

  /**
   * The host interpreter's stand-in: it records the attempt and refuses, in one sentence the 500 is
   * then required to carry.
   *
   * <p>It refuses on purpose rather than building anything. The two commands behind this key are
   * {@code python3 -m venv} and {@code pip install onnx-asr[cpu,hub]} — a reach for PyPI that must
   * never leave a step container — and a stand-in that succeeded would only move the question one
   * command further along. What it buys is the absence: because this binary exists and records,
   * {@code assertNoEdgesTo(HOST_PYTHON)} on an ordinary story is a claim with a witness.
   */
  private static String hostPythonScript() {
    return """
        #!/bin/sh
        # A stand-in for the HOST python — the interpreter `qits.speech.python` names, reached for
        # only when <home>/venv/bin/python is absent. It records the attempt and refuses: the two
        # commands behind this key are `python3 -m venv` and `pip install onnx-asr[cpu,hub]`, and
        # neither may ever leave a step container.
        home=${0%/host/python3}
        dir=$home/engine
        mkdir -p "$dir"
        tab=$(printf '\\t')
        line="host-python${tab}1"
        for field in "$@"; do line="$line$tab$field"; done
        printf '%s\\n' "$line" >> "$dir/results.log"
        echo '@NO_PYTHON@' >&2
        exit 1
        """
        .replace("@NO_PYTHON@", StoryTarget.NO_PYTHON);
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

  /**
   * Move the resident engine out of the way, and back — the one seam the speech-home story needs.
   *
   * <p>{@code TranscriptionService.ensureReady} decides whether to bootstrap by asking whether
   * {@code <home>/venv/bin/python} exists, so this is the whole of "a deployment whose volume was
   * never pre-seeded". A rename rather than a delete, because the running worker is a shell reading
   * its own script through an open descriptor: the inode is what it holds, and a rename does not
   * disturb it — which is itself part of what the story proves, since the resident process survives
   * the failed bootstrap in front of it.
   */
  public static void unseedTheEngine() {
    move(residentPython(), parkedPython());
  }

  /** Put it back. Idempotent, so an {@code @AfterEach} may call it after the story already has. */
  public static void reseedTheEngine() {
    if (Files.exists(parkedPython())) {
      move(parkedPython(), residentPython());
    }
  }

  private static Path parkedPython() {
    return home().resolve("venv").resolve("bin").resolve("python.parked");
  }

  private static void move(Path from, Path to) {
    try {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("could not move " + from + " to " + to, e);
    }
  }
}
