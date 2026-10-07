package ai.straza.connector.rest.transport;

import java.io.IOException;
import java.net.http.HttpResponse;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.support.EmbeddedRestServer;

import org.identityconnectors.common.security.GuardedString;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test(groups = "unit")
public class HttpClientManagerTest {

    private EmbeddedRestServer server;

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws IOException {
        server = new EmbeddedRestServer();
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private UniversalRestConfiguration config(String baseUrl, String token) {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl(baseUrl);
        c.setApiToken(new GuardedString(token.toCharArray()));
        return c;
    }

    public void sendsBearerTokenAndReturnsBody() throws Exception {
        HttpClientManager manager = new HttpClientManager(config(server.baseUrl(), "s3cr3t"));
        HttpResponse<String> response = manager.get("/ok");
        assertEquals(response.statusCode(), 200);
        assertTrue(response.body().contains("\"ok\""));
        assertEquals(server.lastAuthorization(), "Bearer s3cr3t");
    }

    public void normalizesTrailingSlashInBaseUrl() throws Exception {
        HttpClientManager manager = new HttpClientManager(config(server.baseUrl() + "/", "t"));
        assertEquals(manager.getBaseUrl(), server.baseUrl());
        assertEquals(manager.get("/ok").statusCode(), 200);
    }

    public void resolvesRelativePathWithoutLeadingSlash() throws Exception {
        HttpClientManager manager = new HttpClientManager(config(server.baseUrl(), "t"));
        assertEquals(manager.get("ok").statusCode(), 200);
    }

    public void trustAllCertificatesStillWorksOverPlainHttp() throws Exception {
        UniversalRestConfiguration c = config(server.baseUrl(), "t");
        c.setTrustAllCertificates(true);
        HttpClientManager manager = new HttpClientManager(c);
        assertEquals(manager.get("/ok").statusCode(), 200);
    }
}
