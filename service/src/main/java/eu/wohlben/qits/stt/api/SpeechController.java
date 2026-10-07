package eu.wohlben.qits.stt.api;

import eu.wohlben.qits.stt.control.TranscriptionService;
import eu.wohlben.qits.stt.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Base64;

/**
 * Speech-to-text: accepts a browser-recorded WAV (base64 in JSON — small clips, and it keeps the
 * generated client trivial) and returns the transcript. Transcription runs server-side with
 * Parakeet (see {@link TranscriptionService}); a typical utterance takes a few seconds.
 */
@Path("/transcriptions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
public class SpeechController {

  @Inject TranscriptionService transcriptionService;

  public static record TranscribeRequest(@NotBlank String audioBase64) {
    public record Response(String text) {}
  }

  @POST
  public TranscribeRequest.Response transcribe(@Valid TranscribeRequest request) {
    byte[] wav;
    try {
      wav = Base64.getDecoder().decode(request.audioBase64());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("audioBase64 is not valid base64");
    }
    return new TranscribeRequest.Response(transcriptionService.transcribe(wav));
  }
}
