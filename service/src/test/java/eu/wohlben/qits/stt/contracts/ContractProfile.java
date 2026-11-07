package eu.wohlben.qits.stt.contracts;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * <b>The application the contract tests record and verify against</b>: the shipped configuration,
 * with the speech home moved to {@link ContractEngine}'s pre-seeded one.
 *
 * <p>Shared by {@link GoldenMasterRecordingTest} and {@link ConsumerPactVerificationTest}, so the
 * two boot one application between them: a restart away from the default-profile {@code
 * QuarkusTest}s, in the ordinary surefire run. The identity is each class's {@code @TestSecurity},
 * as everywhere else in the suite — the tenant stays off under {@code %test}.
 */
public class ContractProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        // Where the disk state is — the one seam, the key docker/Dockerfile moves too.
        "qits.speech.home",
        ContractEngine.install().toString(),
        // The venv builder, pointed at a stand-in that refuses, so nothing can reach PyPI.
        "qits.speech.python",
        ContractEngine.hostPython().toString(),
        // Said out loud: no worker at boot. The first spawn is the recording's own request.
        "qits.speech.warmup-on-start",
        "false");
  }
}
