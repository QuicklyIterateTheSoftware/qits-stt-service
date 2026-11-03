package eu.wohlben.qits.stt.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Forwarded {@code X-Qits-User} / {@code X-Qits-Roles} headers are not a credential (epic qits-543;
 * forward-auth leaves qits-auth-core with qits-1338). The one test in this suite that sends them: a
 * request carrying them and nothing else is anonymous, and the guarded door refuses it.
 */
@QuarkusTest
class ForwardedHeadersIgnoredTest {

  private static final Map<String, String> FORWARDED =
      Map.of("X-Qits-User", "alice", "X-Qits-Roles", "qits:admin");

  @Test
  void forwardedIdentityHeadersAuthenticateNobody() {
    given()
        .headers(FORWARDED)
        .when()
        .get("/stt/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
    given()
        .headers(FORWARDED)
        .contentType(ContentType.JSON)
        .body(Map.of("audioBase64", "AAAA"))
        .when()
        .post("/stt/api/transcriptions")
        .then()
        .statusCode(401);
  }

  @Test
  void noCredentialIsAnonymous() {
    given()
        .when()
        .get("/stt/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }
}
