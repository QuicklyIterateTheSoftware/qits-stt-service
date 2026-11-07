package eu.wohlben.qits.stt.contracts;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>qits-stt's provider states</b> (ticket qits-1935): each stages what one consumer situation
 * needs and hands back its parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before it records each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p><b>The service keeps no state of its own</b> — no tables, nothing persisted — so a state is
 * the one thing a deployment supplies it: a speech home holding an engine. {@link ContractEngine}
 * stages it once per JVM and every state only checks that it is there. Applying a state twice is
 * therefore the same state.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_RESIDENT_SPEECH_ENGINE = "a resident speech engine";

  /**
   * The clip a recording sends: a minimal valid PCM WAV — the 44-byte RIFF header and eight samples
   * of silence, 16 kHz mono 16-bit — base64-encoded, so the index that records it as the request
   * body stays readable. {@code TranscriptionService} checks only that it is non-empty and under
   * the size cap, and the stand-in never parses it; it is a real WAV all the same, so a consumer
   * may send the same bytes to a real engine.
   */
  public static final String CLIP = Base64.getEncoder().encodeToString(wav(16_000, 8));

  /** What a state hands back: its parameters, keys sorted. */
  public record Setup(Map<String, String> params) {}

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_RESIDENT_SPEECH_ENGINE, this::aResidentSpeechEngine);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * A speech home whose engine is already installed — the pre-seeded volume — so a clip is
   * transcribed by the resident worker without a venv bootstrap. Its params are the clip a consumer
   * sends ({@code audioBase64}) and the transcript it gets back ({@code text}).
   */
  private Setup aResidentSpeechEngine() {
    Path python = ContractEngine.residentPython();
    if (!Files.isExecutable(python)) {
      throw new IllegalStateException(
          "State '" + A_RESIDENT_SPEECH_ENGINE + "' has no resident engine at " + python);
    }
    Map<String, String> params = new TreeMap<>();
    params.put("audioBase64", CLIP);
    params.put("text", ContractEngine.TRANSCRIPT);
    return new Setup(Collections.unmodifiableMap(params));
  }

  /** A canonical 44-byte-header PCM WAV: mono, 16-bit, {@code samples} samples of silence. */
  static byte[] wav(int sampleRate, int samples) {
    int channels = 1;
    int bitsPerSample = 16;
    int blockAlign = channels * bitsPerSample / 8;
    int dataBytes = samples * blockAlign;
    ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
    wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(36 + dataBytes);
    wav.put("WAVE".getBytes(StandardCharsets.US_ASCII));
    wav.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(16); // the fmt chunk's size, for PCM
    wav.putShort((short) 1); // PCM
    wav.putShort((short) channels);
    wav.putInt(sampleRate);
    wav.putInt(sampleRate * blockAlign); // byte rate
    wav.putShort((short) blockAlign);
    wav.putShort((short) bitsPerSample);
    wav.put("data".getBytes(StandardCharsets.US_ASCII));
    wav.putInt(dataBytes);
    // The samples: zeros, which the allocation already holds.
    return wav.array();
  }
}
