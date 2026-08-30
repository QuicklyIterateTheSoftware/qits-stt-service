package eu.wohlben.qits.stt.stories.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The recording every story posts: a 16 kHz mono 16-bit PCM WAV of silence, built here rather than
 * committed because a binary fixture nobody can read is a worse fixture than eight lines that say
 * what it is.
 *
 * <p>Silence is honest. This service treats the payload as opaque bytes — decoding audio is the
 * engine's job and the engine here is a stand-in — while the 44-byte canonical RIFF header keeps the
 * fixture the shape a browser really records, which is what the size assertions are about: the
 * engine is handed a file of exactly {@link #BYTES} bytes, so "the clip was staged" is a measurement
 * and not an inference from the fact that a transcript came back.
 */
public final class StoryClip {

  /** Samples in the fixture. 4000 at 16 kHz is a quarter of a second, which is enough of a clip. */
  private static final int SAMPLES = 4000;

  /** The header plus two bytes per sample — the exact size the engine must see on disk. */
  public static final int BYTES = 44 + SAMPLES * 2;

  private StoryClip() {}

  /** The clip as the browser sends it: base64 in JSON, which is the whole of the request body. */
  public static String base64() {
    return Base64.getEncoder().encodeToString(wav());
  }

  private static byte[] wav() {
    int dataBytes = SAMPLES * 2;
    ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
    wav.put(ascii("RIFF")).putInt(36 + dataBytes).put(ascii("WAVE"));
    wav.put(ascii("fmt "))
        .putInt(16) // PCM header length
        .putShort((short) 1) // format: PCM
        .putShort((short) 1) // channels: mono
        .putInt(16_000) // sample rate
        .putInt(32_000) // byte rate = rate * channels * 2
        .putShort((short) 2) // block align
        .putShort((short) 16); // bits per sample
    wav.put(ascii("data")).putInt(dataBytes);
    return wav.array();
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }
}
