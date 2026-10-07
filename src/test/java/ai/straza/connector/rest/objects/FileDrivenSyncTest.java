package ai.straza.connector.rest.objects;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.UniversalRestConnector;
import ai.straza.connector.rest.support.EmbeddedRestServer;
import ai.straza.connector.rest.support.TestSchemas;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.spi.SyncTokenResultsHandler;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * LiveSync for the reserved ConnId classes, routed by each class's {@code sync}
 * block in the schema file. Re-fetches use the same read path as a search.
 */
@Test(groups = "unit")
public class FileDrivenSyncTest {

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

    public void theDeclaredRequestTypesNarrowTheFeedAndDriveARefetch() {
        server.stub("GET", "/v1/admin/changes", 200,
                "{\"changes\":[{\"cursor\":\"c1\",\"type\":\"role\",\"op\":\"update\",\"id\":\"r-1\"}],"
                        + "\"nextCursor\":\"c1\",\"more\":false,\"head\":\"c1\"}");
        server.stub("GET", "/scim/v2/Groups/r-1", 200,
                "{\"id\":\"r-1\",\"displayName\":\"dev\",\"members\":[{\"value\":\"u-1\"}]}");
        UniversalRestConnector connector = connector();

        CollectingSyncHandler handler = new CollectingSyncHandler();
        connector.sync(ObjectClass.GROUP, new SyncToken("c0"), handler, null);

        assertEquals(handler.deltas.size(), 1);
        assertEquals(handler.deltas.get(0).getDeltaType(), SyncDeltaType.CREATE_OR_UPDATE);
        assertEquals(handler.deltas.get(0).getObject().getUid().getUidValue(), "r-1");
        assertEquals(handler.deltas.get(0).getObject().getName().getNameValue(), "dev");
        assertEquals(handler.token.getValue(), "c1");

        String feedUri = URLDecoder.decode(server.requests().get(0).uri(), StandardCharsets.UTF_8);
        assertTrue(feedUri.contains("types=role,binding"), feedUri);
        assertEquals(server.requests().get(0).authorization(), "Bearer admin-token");
        assertEquals(server.requests().get(1).authorization(), "Bearer admin-token");
        connector.dispose();
    }

    public void aDeclaredDeleteMarkerTypeBecomesADeleteDelta() {
        server.stub("GET", "/v1/admin/changes", 200,
                "{\"changes\":[{\"cursor\":\"c2\",\"type\":\"identity\",\"op\":\"delete\",\"id\":\"r-gone\"}],"
                        + "\"nextCursor\":\"c2\",\"more\":false,\"head\":\"c2\"}");
        UniversalRestConnector connector = connector();

        CollectingSyncHandler handler = new CollectingSyncHandler();
        connector.sync(ObjectClass.GROUP, new SyncToken("c1"), handler, null);

        assertEquals(handler.deltas.size(), 1);
        assertEquals(handler.deltas.get(0).getDeltaType(), SyncDeltaType.DELETE);
        assertEquals(handler.deltas.get(0).getUid().getUidValue(), "r-gone");
        connector.dispose();
    }

    public void anObjectThatNoLongerReadsBackBecomesASafeDeleteDelta() {
        server.stub("GET", "/v1/admin/changes", 200,
                "{\"changes\":[{\"cursor\":\"c3\",\"type\":\"role\",\"op\":\"update\",\"id\":\"r-hidden\"}],"
                        + "\"nextCursor\":\"c3\",\"more\":false,\"head\":\"c3\"}");
        server.stub("GET", "/scim/v2/Groups/r-hidden", 404, "{\"status\":\"404\",\"detail\":\"no such group\"}");
        UniversalRestConnector connector = connector();

        CollectingSyncHandler handler = new CollectingSyncHandler();
        connector.sync(ObjectClass.GROUP, new SyncToken("c2"), handler, null);

        assertEquals(handler.deltas.size(), 1);
        assertEquals(handler.deltas.get(0).getDeltaType(), SyncDeltaType.DELETE);
        assertEquals(handler.deltas.get(0).getUid().getUidValue(), "r-hidden");
        connector.dispose();
    }

    public void aDeclaredReemitTypeRefreshesEveryObjectOfTheClass() {
        server.stub("GET", "/v1/admin/changes", 200,
                "{\"changes\":[{\"cursor\":\"c4\",\"type\":\"binding\",\"op\":\"update\",\"id\":\"app-1\"}],"
                        + "\"nextCursor\":\"c4\",\"more\":false,\"head\":\"c4\"}");
        server.stub("GET", "/scim/v2/Groups", 200, "{\"totalResults\":2,\"Resources\":["
                + "{\"id\":\"r-1\",\"displayName\":\"dev\"},{\"id\":\"r-2\",\"displayName\":\"ops\"}]}");
        UniversalRestConnector connector = connector();

        CollectingSyncHandler handler = new CollectingSyncHandler();
        connector.sync(ObjectClass.GROUP, new SyncToken("c3"), handler, null);

        assertEquals(handler.deltas.size(), 2);
        assertEquals(handler.deltas.get(0).getDeltaType(), SyncDeltaType.CREATE_OR_UPDATE);
        connector.dispose();
    }

    public void eachClassNarrowsTheFeedToItsOwnTypes() {
        server.stub("GET", "/v1/admin/changes", 200,
                "{\"changes\":[{\"cursor\":\"c5\",\"type\":\"user\",\"op\":\"update\",\"id\":\"u-1\"}],"
                        + "\"nextCursor\":\"c5\",\"more\":false,\"head\":\"c5\"}");
        server.stub("GET", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\",\"userName\":\"bob\",\"active\":true}");
        UniversalRestConnector connector = connector();

        CollectingSyncHandler handler = new CollectingSyncHandler();
        connector.sync(ObjectClass.ACCOUNT, new SyncToken("c4"), handler, null);

        assertEquals(handler.deltas.size(), 1);
        assertEquals(handler.deltas.get(0).getObject().getName().getNameValue(), "bob");
        String feedUri = URLDecoder.decode(server.requests().get(0).uri(), StandardCharsets.UTF_8);
        assertTrue(feedUri.contains("types=user"), feedUri);
        connector.dispose();
    }

    public void syncWithoutTheFeedTransportFailsLoudly() {
        UniversalRestConfiguration configuration = configuration();
        configuration.setSchemaFilePath(TestSchemas.copy("evidence-only"));
        UniversalRestConnector connector = new UniversalRestConnector();
        connector.init(configuration);
        assertThrows(ConfigurationException.class, () -> connector.sync(
                new ObjectClass("widget"), new SyncToken(""), new CollectingSyncHandler(), null));
        connector.dispose();
    }

    public void liveSyncRefusesTheAllObjectClass() {
        server.stub("GET", "/v1/admin/changes", 200,
                "{\"changes\":[],\"nextCursor\":\"\",\"more\":false,\"head\":\"h\"}");
        UniversalRestConnector connector = connector();
        assertThrows(UnsupportedOperationException.class,
                () -> connector.sync(ObjectClass.ALL, null, new CollectingSyncHandler(), null));
        connector.dispose();
    }

    private UniversalRestConfiguration configuration() {
        UniversalRestConfiguration configuration = new UniversalRestConfiguration();
        configuration.setBaseUrl(server.baseUrl());
        configuration.setApiToken(new GuardedString("admin-token".toCharArray()));
        configuration.setSchemaFilePath(TestSchemas.scimClasses());
        return configuration;
    }

    private UniversalRestConnector connector() {
        UniversalRestConnector connector = new UniversalRestConnector();
        connector.init(configuration());
        return connector;
    }

    private static final class CollectingSyncHandler implements SyncResultsHandler, SyncTokenResultsHandler {
        private final List<SyncDelta> deltas = new ArrayList<>();
        private SyncToken token;

        @Override
        public boolean handle(SyncDelta delta) {
            deltas.add(delta);
            return true;
        }

        @Override
        public void handleResult(SyncToken result) {
            this.token = result;
        }
    }
}
