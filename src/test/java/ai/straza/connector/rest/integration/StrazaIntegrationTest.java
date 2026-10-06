package ai.straza.connector.rest.integration;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.UniversalRestConnector;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * Runs against a standalone strazad started from {@code STRAZA_BIN}. Opt-in;
 * without {@code STRAZA_BIN} the class is skipped:
 * <pre>
 *   STRAZA_BIN=/path/to/strazad mvn verify -DtestGroups=unit,integration
 * </pre>
 * <p>Set {@code STRAZA_TEST_ADMIN_TOKEN} to an admin API token
 * ({@code strazactl api-token create}) to also run the authenticated reads.</p>
 */
@Test(groups = "integration")
public class StrazaIntegrationTest {

    private Process process;
    private Path dataDir;
    private String baseUrl;

    @BeforeClass
    public void startStraza() throws Exception {
        String bin = System.getenv("STRAZA_BIN");
        if (bin == null || bin.isBlank() || !Files.isRegularFile(Path.of(bin))) {
            throw new SkipException("Set STRAZA_BIN to a strazad executable to run the strazad integration tests.");
        }

        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        baseUrl = "http://127.0.0.1:" + port;
        dataDir = Files.createTempDirectory("strazad-it");

        process = new ProcessBuilder(bin, "serve", "--profile", "standalone",
                "--data-dir", dataDir.toString(), "--listen", "127.0.0.1:" + port)
                .redirectErrorStream(true)
                .redirectOutput(dataDir.resolve("strazad.log").toFile())
                .start();

        waitForReady(baseUrl + "/healthz", Duration.ofSeconds(40));
    }

    @AfterClass(alwaysRun = true)
    public void stopStraza() throws IOException {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        if (dataDir != null && Files.exists(dataDir)) {
            try (Stream<Path> walk = Files.walk(dataDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort temp cleanup
                    }
                });
            }
        }
    }

    public void reachesLiveStrazaHealth() {
        UniversalRestConnector conn = connector("unused-for-healthz", "/healthz");
        conn.test(); // /healthz answers 200 without auth
        conn.dispose();
    }

    public void classifiesUnauthenticatedAdminAsAuthFailure() {
        UniversalRestConnector conn = connector("definitely-not-a-valid-token", "/v1/admin/overview");
        assertThrows(InvalidCredentialException.class, conn::test);
        conn.dispose();
    }

    public void authenticatedAdminReadPassesWhenTokenProvided() {
        String token = System.getenv("STRAZA_TEST_ADMIN_TOKEN");
        if (token == null || token.isBlank()) {
            throw new SkipException("Set STRAZA_TEST_ADMIN_TOKEN (a fresh admin token) to exercise the authenticated admin read.");
        }
        UniversalRestConnector conn = connector(token, "/v1/admin/overview");
        conn.test();
        conn.dispose();
    }

    public void searchUsersReturnsBootstrapAdminWhenTokenProvided() {
        String token = System.getenv("STRAZA_TEST_ADMIN_TOKEN");
        if (token == null || token.isBlank()) {
            throw new SkipException("Set STRAZA_TEST_ADMIN_TOKEN (a fresh admin token) to exercise Search against live strazad.");
        }
        UniversalRestConnector conn = connectorWithSchema(token);
        List<ConnectorObject> users = new ArrayList<>();
        conn.executeQuery(new ObjectClass("user"), null, co -> {
            users.add(co);
            return true;
        }, null);

        assertTrue(users.size() >= 1, "expected at least the bootstrap admin user");
        assertTrue(users.stream().allMatch(u -> u.getObjectClass().equals(new ObjectClass("user"))));
        assertTrue(users.stream().anyMatch(u -> "admin".equals(u.getName().getNameValue())),
                "bootstrap admin should be present");
        conn.dispose();
    }

    private UniversalRestConnector connectorWithSchema(String token) {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl(baseUrl);
        c.setApiToken(new GuardedString(token.toCharArray()));
        c.setSchemaFilePath(Path.of("samples/straza/straza-schema.json").toAbsolutePath().toString());
        UniversalRestConnector conn = new UniversalRestConnector();
        conn.init(c);
        return conn;
    }

    private UniversalRestConnector connector(String token, String testEndpoint) {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl(baseUrl);
        c.setApiToken(new GuardedString(token.toCharArray()));
        c.setTestEndpoint(testEndpoint);
        UniversalRestConnector conn = new UniversalRestConnector();
        conn.init(c);
        return conn;
    }

    private static void waitForReady(String healthUrl, Duration timeout) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(3)).GET().build();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadlineNanos) {
            try {
                HttpResponse<Void> r = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("strazad did not become ready at " + healthUrl, last);
    }
}
