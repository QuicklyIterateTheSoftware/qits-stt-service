package eu.wohlben.qits.stt.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * <b>The client-credentials token mint, as quarkus-oidc-client sends it</b> (epic qits-428): the
 * {@code qits} named client application.properties ships, switched on and pointed at a stand-in
 * for qits-idp's token endpoint. The client's one reader is qits-telemetry-core, whose exporters
 * attach the token to every OTLP export; the SDK is off under {@code %test}, so the test asks the
 * named client directly.
 *
 * <p>No pact: this repository writes no consumer pacts ({@code .config/qits/release.yml} declares
 * none). What is pinned is the request qits-idp records as {@code issueToken} in "a service client
 * calling its peers" — {@code POST /idp/token}, HTTP Basic with the client's id and secret, {@code
 * grant_type=client_credentials} and {@code audience=qits-platform} — the row qits-docs-service and
 * qits-system-service pact.
 *
 * <p><b>The port and the recording are system properties, not static fields.</b> Quarkus loads the
 * test profile in its own classloader, so a static field there and the one this test reads would
 * be two fields with two ports. The process has one property table: the stand-in starts once,
 * whichever loader asks first, parks its port there, and writes the request it saw there too.
 */
@QuarkusTest
@TestProfile(QitsClientMintTest.StandInIdp.class)
class QitsClientMintTest {

  private static final String PORT_PROPERTY = "qits.stt.test.idp-stand-in.port";
  private static final String SEEN_PROPERTY = "qits.stt.test.idp-stand-in.seen";

  static final String CLIENT_ID = "test-stt";
  static final String SECRET = "test-stt-secret";
  static final String TOKEN = "stand-in-token";

  private static HttpServer server;

  /** The `qits` client on, against the stand-in, with an id and secret of the test's own. */
  public static class StandInIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + port() + "/idp",
          "quarkus.oidc-client.qits.client-id", CLIENT_ID,
          "quarkus.oidc-client.qits.credentials.secret", SECRET);
    }
  }

  @Inject
  @NamedOidcClient("qits")
  OidcClient client;

  @Test
  void theQitsClientMintsByClientCredentialsForThePlatformAudience() {
    String minted = client.getTokens().await().atMost(Duration.ofSeconds(20)).getAccessToken();
    assertEquals(TOKEN, minted);

    String seen = System.getProperty(SEEN_PROPERTY, "");
    String[] parts = seen.split("\n", 3);
    assertEquals(3, parts.length, "the stand-in saw no token request: " + seen);
    assertEquals("POST /idp/token", parts[0]);
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
    assertEquals(basic, parts[1]);
    String form = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
    assertTrue(form.contains("grant_type=client_credentials"), form);
    assertTrue(form.contains("audience=qits-platform"), form);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** The stand-in's port: started once per JVM, whichever classloader asks first. */
  static synchronized int port() {
    String parked = System.getProperty(PORT_PROPERTY);
    if (parked != null) {
      return Integer.parseInt(parked);
    }
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/idp/token",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          System.setProperty(
              SEEN_PROPERTY,
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI().getPath()
                  + "\n"
                  + authorization
                  + "\n"
                  + body);
          byte[] answer =
              ("{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\","
                      + "\"expires_in\":3600}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    server.start();
    int port = server.getAddress().getPort();
    System.setProperty(PORT_PROPERTY, String.valueOf(port));
    return port;
  }
}
