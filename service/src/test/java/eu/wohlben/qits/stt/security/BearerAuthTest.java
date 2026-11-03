package eu.wohlben.qits.stt.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bearer door (qits-1370): the shipped tenant, checking tokens against the suite's local key.
 *
 * <p>A request the door admits still reaches the base64 decode and fails there with this context's
 * own 400, which proves the role gate let it through without waking the engine.
 */
@QuarkusTest
@TestProfile(BearerAuthProfile.class)
class BearerAuthTest {

  private static final Map<String, String> NOT_BASE64 = Map.of("audioBase64", "!!! not base64 !!!");

  private static RequestSpecification withBearer(String token) {
    return given().header("Authorization", BearerTokens.bearer(token));
  }

  @Test
  void aPersonsBearerIsTheIdentity() {
    withBearer(BearerTokens.personToken("qits:admin"))
        .when()
        .get("/stt/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo(BearerTokens.SUBJECT))
        .body("roles", hasItem("qits:admin"));
  }

  @Test
  void anAdminsBearerPassesTheDoor() {
    withBearer(BearerTokens.personToken("qits:admin"))
        .contentType(ContentType.JSON)
        .body(NOT_BASE64)
        .when()
        .post("/stt/api/transcriptions")
        .then()
        .statusCode(400)
        .body("message", equalTo("audioBase64 is not valid base64"));
  }

  @Test
  void aBearerWithoutTheRoleIsForbidden() {
    withBearer(BearerTokens.personToken("qits:reader"))
        .contentType(ContentType.JSON)
        .body(NOT_BASE64)
        .when()
        .post("/stt/api/transcriptions")
        .then()
        .statusCode(403);
  }

  @Test
  void aBearerForAnotherAudienceIsRefused() {
    withBearer(BearerTokens.tokenFor("some-other-audience", "qits:admin"))
        .contentType(ContentType.JSON)
        .body(NOT_BASE64)
        .when()
        .post("/stt/api/transcriptions")
        .then()
        .statusCode(401);
  }

  @Test
  void aBearerSignedByAnUntrustedKeyIsRefused() {
    withBearer(BearerTokens.foreignToken("qits:admin"))
        .contentType(ContentType.JSON)
        .body(NOT_BASE64)
        .when()
        .post("/stt/api/transcriptions")
        .then()
        .statusCode(401);
  }
}
