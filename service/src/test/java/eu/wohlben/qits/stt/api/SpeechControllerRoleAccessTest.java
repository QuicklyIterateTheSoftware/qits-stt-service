package eu.wohlben.qits.stt.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins qits-628's rule at this service's one door: {@code qits:admin-agent} must be admitted
 * everywhere {@code qits:admin} is, stated explicitly beside it rather than inherited from any
 * augmentor or prefix match. Each test names exactly one role with {@code @TestSecurity}, so the
 * identity holds that role and nothing else, and the door can be seen refusing somebody.
 *
 * <p>qits-1596 extends the same rule to the delivery desk ({@code qits:desk}): a transcription
 * request carries nothing to scope — {@code TranscribeRequest} has no workspace or repository id
 * — so the desk is admitted outright, the same way {@code qits:admin-agent} is, rather than bound
 * to a project claim.
 *
 * <p>Epic qits-1621 extends it again to {@code qits:desk-admin}: a platform operator role,
 * admitted everywhere {@code qits:admin-agent} is, this door included.
 *
 * <p>Neither case needs a real transcription: a request the door admits still reaches the base64
 * decode and fails there with this context's own 400, which is enough to prove the role gate let it
 * through without waking the engine; a request the door refuses never gets that far.
 */
@QuarkusTest
class SpeechControllerRoleAccessTest {

  private static final String NOT_BASE64 = "!!! not base64 !!!";

  @Test
  @TestSecurity(user = "alice", roles = "qits:admin-agent")
  void adminAgentAloneIsAdmittedToTheWriteDoor() {
    given()
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
  @TestSecurity(user = "the-desk", roles = "qits:desk")
  void deskAloneIsAdmittedToTheWriteDoor() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", NOT_BASE64))
        .when()
        .post("/stt/api/transcriptions")
        .then()
        // Same proof as the admin-agent case above: past the door, into the method, and failing
        // on the decode rather than on the role gate.
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", equalTo("audioBase64 is not valid base64"));
  }

  @Test
  @TestSecurity(user = "the-desk-admin", roles = "qits:desk-admin")
  void deskAdminAloneIsAdmittedToTheWriteDoor() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", NOT_BASE64))
        .when()
        .post("/stt/api/transcriptions")
        .then()
        // Same proof as the admin-agent case above: past the door, into the method, and failing
        // on the decode rather than on the role gate.
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", equalTo("audioBase64 is not valid base64"));
  }

  @Test
  @TestSecurity(user = "alice", roles = "qits:agent")
  void plainAgentWithoutAdminOrAdminAgentStillGets403() {
    // qits:agent is the ordinary agent credential — never admitted by this admin-only door, and
    // worth its own case precisely because it is the role an admin-agent identity is NOT: this
    // proves admitting qits:admin-agent did not widen the door to agents in general.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", NOT_BASE64))
        .when()
        .post("/stt/api/transcriptions")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
  }
}
