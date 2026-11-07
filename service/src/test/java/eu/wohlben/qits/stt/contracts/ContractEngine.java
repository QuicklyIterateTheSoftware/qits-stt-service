package eu.wohlben.qits.stt.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

/**
 * <b>The speech engine the contract tests transcribe against</b>: a pre-seeded speech home under
 * {@code target/}, both of whose children are {@code /bin/sh}.
 *
 * <p>This service's one outbound dependency is not a socket but a program — {@code
 * <qits.speech.home>/venv/bin/python <home>/transcribe_worker.py} — so the stand-in is an
 * executable at exactly that path, speaking the worker protocol {@code SpeechWorker} expects: one
 * {@code {"ready": true}} greeting, then one {@code {"text": …}} line per staged WAV path, forever.
 * Its answer is always {@link #TRANSCRIPT}, which is what makes the golden master stable.
 *
 * <p><b>Why not {@code stories/support/StoryEngine}.</b> That one records every call into a
 * cumulative log the userflow framework reads, arms faults, and takes its transcript from the story
 * catalogue's {@code StoryTarget}: all of it story machinery a recording has no use for, and a home
 * under {@code target/userflow-speech-home} that the failsafe catalogue wipes on its way in. This
 * is the protocol and nothing else, in a home of its own.
 *
 * <p><b>No real python, no PyPI, no model hub.</b> {@code TranscriptionService.ensureReady} reaches
 * for {@code qits.speech.python} only when {@code venv/bin/python} is absent; this home always
 * holds one, and the interpreter the profile names ({@link #hostPython}) is a stand-in that refuses
 * — so a home that somehow lost its engine fails the recording with {@link #NO_PYTHON} rather than
 * running {@code python3 -m venv} and {@code pip install onnx-asr[cpu,hub]}.
 */
public final class ContractEngine {

  /** What the stand-in answers for every clip: the transcript the golden master records. */
  public static final String TRANSCRIPT = "Open a ticket for the failing build.";

  /** The host interpreter stand-in's refusal, on its stderr; the 500 would carry it. */
  public static final String NO_PYTHON =
      "the contract tests' host python refuses: no venv is built and no pip runs here";

  /** Where the first {@link #install} parked the home, so a second one never wipes it. */
  private static final String HOME_PROPERTY = "qits.stt.contract.speech-home";

  private ContractEngine() {}

  /**
   * Prepare, once per JVM, a speech home that already holds an engine, and answer it. Wiped on the
   * way in so nothing from a previous run is in it.
   *
   * <p>Idempotent per JVM through the parked property: the profile is instantiated more than once,
   * and the resident worker is a shell reading its own script through an open descriptor, so
   * rewriting the file under a running worker could corrupt what it reads next.
   *
   * <p>The mode has to be set here: a {@code ProcessBuilder} on a non-executable file fails with
   * "Permission denied".
   */
  public static synchronized Path install() {
    String parked = System.getProperty(HOME_PROPERTY);
    if (parked != null) {
      return Path.of(parked);
    }
    Path home = Path.of("target", "contract-speech-home").toAbsolutePath().normalize();
    try {
      deleteRecursively(home);
      write(home.resolve("venv").resolve("bin").resolve("python"), engineScript());
      write(home.resolve("host").resolve("python3"), hostPythonScript());
    } catch (IOException e) {
      throw new UncheckedIOException("could not prepare the contract speech home " + home, e);
    }
    System.setProperty(HOME_PROPERTY, home.toString());
    return home;
  }

  /** The interpreter {@code qits.speech.python} is pointed at — the venv builder that refuses. */
  public static Path hostPython() {
    return install().resolve("host").resolve("python3");
  }

  /** The resident worker's executable, at exactly the path {@code SpeechWorker} runs. */
  public static Path residentPython() {
    return install().resolve("venv").resolve("bin").resolve("python");
  }

  private static String engineScript() {
    return """
    #!/bin/sh
    # A stand-in for the resident Parakeet worker (see speech/transcribe_worker.py), staged by
    # qits-stt's contract tests. It loads no model and needs no python: one greeting line, then
    # one WAV path in and one JSON line out, with the same transcript every time.
    echo '{"ready": true}'
    while IFS= read -r wav; do
      [ -n "$wav" ] || continue
      echo '{"text": "@TRANSCRIPT@"}'
    done
    """
        .replace("@TRANSCRIPT@", TRANSCRIPT);
  }

  private static String hostPythonScript() {
    return """
    #!/bin/sh
    # A stand-in for the HOST python, reached for only when <home>/venv/bin/python is absent.
    # It refuses: `python3 -m venv` and `pip install onnx-asr[cpu,hub]` never run in a test.
    echo '@NO_PYTHON@' >&2
    exit 1
    """
        .replace("@NO_PYTHON@", NO_PYTHON);
  }

  private static void write(Path executable, String script) throws IOException {
    Files.createDirectories(executable.getParent());
    Files.writeString(executable, script);
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"));
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
}
