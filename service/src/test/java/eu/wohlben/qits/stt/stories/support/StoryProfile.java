package eu.wohlben.qits.stt.stories.support;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.nio.file.Path;
import java.util.Map;

/**
 * <b>One profile for the whole story catalogue</b>, and that is the point of it: every {@code
 * @QuarkusIntegrationTest} in this repository carries this class, so failsafe launches the fast-jar
 * <b>once</b> — one boot, one resident worker, one recording of what was asked of a child process. A
 * second profile would be a second process with a second startup, whose spawn would land in
 * whichever diagram happened to be open.
 *
 * <p>It also means the catalogue's stories are not independent of one another, and they say so:
 * whether a story opens with a spawn edge depends on whether the story before it left a worker
 * alive. That is not a wart to be engineered away — it is the actual behaviour of a service whose
 * whole design is one resident process, and it is why the catalogue's order is pinned with {@code
 * @UserflowRunsAfter} rather than left to JUnit.
 *
 * <p>Hands the launched artifact its config the way a deployment does, and there is very little of
 * it, which is this service's shape rather than an omission: it owns no tables, so {@code
 * .config/qits/deployments.yml} declares no {@code resources:} and there are no generic triples to
 * supply. The whole of a qits-stt deployment is where its disk state lives.
 *
 * <p><b>Every key here is a RUNTIME key.</b> A packaged process takes its configuration as {@code
 * -D} arguments on an artifact that was already built, so a build-time key would be silently ignored
 * and the catalogue would prove something other than what it says.
 *
 * <p><b>The stand-ins are prepared before the application starts, and their coordinates travel in a
 * system property</b> — the one namespace every classloader in a JVM shares, which matters because a
 * test profile is instantiated in more than one of them and the launched artifact is a different
 * process again. {@link StoryEngine#install()} is what prepares them; it is also the guard that
 * keeps a second {@code getConfigOverrides()} call from wiping the speech home out from under a
 * running application.
 */
public class StoryProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    Path home = StoryEngine.install();
    return Map.of(
        // The one seam that matters: where the disk state is. The packaged artifact is otherwise
        // exactly what ships — the same route, the same door, the same worker protocol.
        // `docker/Dockerfile` moves this same key (QITS_SPEECH_HOME) for the same reason.
        "qits.speech.home",
        home.toString(),
        // THE INTERPRETER THAT CREATES THE VENV, and it is pointed at a RECORDING STAND-IN rather
        // than — as the first rollout did — at a path that cannot exist. Both arrangements keep
        // `python3 -m venv` and `pip install onnx-asr[cpu,hub]` away from PyPI; only this one makes
        // the absence provable. A binary that exists and records is what turns "no venv was built
        // and no model was downloaded" from a sentence in a description into
        // assertNoEdgesTo(the host python) on every story that transcribes anything.
        "qits.speech.python",
        StoryEngine.hostPython().toString(),
        // Left at the code default, said out loud: warmup bootstraps the venv and spawns the worker
        // on a virtual thread at startup. Off, the first spawn in the whole run is caused by the
        // first request — which is what lets the first story claim it, and why the recording is
        // registered at zero with no floor.
        "qits.speech.warmup-on-start",
        "false",
        // Dark outside a deployment, like %dev/%test. A packaged run is the `prod` profile, so the
        // shipped %test line does not apply here and this has to be said. It is also the catalogue's
        // one STATED GAP in the diagram: with the exporter off there is no tap that could see an
        // OTLP export, so no story is entitled to assert that none happened. Turning it on instead
        // would put a batched, timer-driven export into whichever story was open when it fired.
        "quarkus.otel.sdk.disabled",
        "true");
  }
}
