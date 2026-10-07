package eu.wohlben.qits.stt.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import eu.wohlben.qits.stt.security.NoDevUserProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins qits-628's rule at this service's one door: {@code qits:admin-agent} must be admitted
 * everywhere {@code qits:admin} is, stated explicitly beside it rather than inherited from any
 * augmentor or prefix match. The dev-user fallback is blanked ({@link NoDevUserProfile}, the same
 * profile {@code ForwardAuthTest} uses) so the forwarded header is the only identity — without it
 * every {@code @QuarkusTest} here arrives with an automatic {@code qits:admin} (see
 * {@code TranscriptionBootstrapIT}'s javadoc) and the door could never be seen refusing anybody.
 *
 * <p>Neither case needs a real transcription: a request the door admits still reaches the base64
 * decode and fails there with this context's own 400, which is enough to prove the role gate let it
 * through without waking the engine; a request the door refuses never gets that far.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class SpeechControllerRoleAccessTest {

  private static final String NOT_BASE64 = "!!! not base64 !!!";

  @Test
  void adminAgentAloneIsAdmittedToTheWriteDoor() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin-agent")
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", NOT_BASE64))
        .when()
        .post("/stt/api/transcriptions")
        .then()
        // Past the door and into the method: the decode failure is the proof, not a guess at the
        // status code security alone would have produced.
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", equalTo("audioBase64 is not valid base64"));
  }

  @Test
  void plainAgentWithoutAdminOrAdminAgentStillGets403() {
    // qits:agent is the ordinary agent credential — never admitted by this admin-only door, and
    // worth its own case precisely because it is the role an admin-agent identity is NOT: this
    // proves admitting qits:admin-agent did not widen the door to agents in general.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:agent")
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", NOT_BASE64))
        .when()
        .post("/stt/api/transcriptions")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
  }
}
