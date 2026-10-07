package ai.straza.connector.rest;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import ai.straza.connector.rest.filter.RestFilter;
import ai.straza.connector.rest.filter.RestFilterTranslator;
import ai.straza.connector.rest.support.EmbeddedRestServer;
import ai.straza.connector.rest.support.TestSchemas;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SearchResult;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.spi.SearchResultsHandler;
import org.identityconnectors.framework.spi.SyncTokenResultsHandler;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

@Test(groups = "unit")
public class UniversalRestConnectorTest {

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

    private static String sampleSchemaPath() {
        return Path.of("samples/straza/straza-schema.json").toAbsolutePath().toString();
    }

    private UniversalRestConnector connector(String baseUrl, String token, String testEndpoint) {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl(baseUrl);
        c.setApiToken(new GuardedString(token.toCharArray()));
        c.setTestEndpoint(testEndpoint);
        UniversalRestConnector conn = new UniversalRestConnector();
        conn.init(c);
        return conn;
    }

    private UniversalRestConnector connectorWithSchema(String baseUrl, String token) {
        return connectorWithSchema(baseUrl, token, sampleSchemaPath());
    }

    private UniversalRestConnector connectorWithSchema(String baseUrl, String token, String schemaFilePath) {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl(baseUrl);
        c.setApiToken(new GuardedString(token.toCharArray()));
        c.setSchemaFilePath(schemaFilePath);
        UniversalRestConnector conn = new UniversalRestConnector();
        conn.init(c);
        return conn;
    }

    // ---- test() ---------------------------------------------------------------

    public void testPassesOnTwoHundred() {
        UniversalRestConnector conn = connector(server.baseUrl(), "t", "/ok");
        conn.test();
        conn.dispose();
    }

    public void testConnectivityWithoutEndpoint() {
        UniversalRestConnector conn = connector(server.baseUrl(), "t", null);
        conn.test();
        conn.dispose();
    }

    public void testThrowsInvalidCredentialOn401() {
        UniversalRestConnector conn = connector(server.baseUrl(), "t", "/unauthorized");
        assertThrows(InvalidCredentialException.class, conn::test);
        conn.dispose();
    }

    public void testThrowsConnectionFailedWhenUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        UniversalRestConnector conn = connector("http://127.0.0.1:" + closedPort, "t", "/ok");
        assertThrows(ConnectionFailedException.class, conn::test);
        conn.dispose();
    }

    public void filterTranslatorIsNotNull() {
        UniversalRestConnector conn = connector(server.baseUrl(), "t", "/ok");
        assertNotNull(conn.createFilterTranslator(ObjectClass.ACCOUNT, null));
        conn.dispose();
    }

    // ---- schema() -------------------------------------------------------------

    public void schemaBuildsObjectClassesFromFile() {
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");
        Schema schema = conn.schema();
        Set<String> types = schema.getObjectClassInfo().stream()
                .map(ObjectClassInfo::getType).collect(Collectors.toSet());
        assertTrue(types.containsAll(Set.of("app", "tool", "user",
                ObjectClass.ACCOUNT_NAME, ObjectClass.GROUP_NAME)), "got: " + types);

        ObjectClassInfo tool = schema.getObjectClassInfo().stream()
                .filter(oc -> oc.getType().equals("tool")).findFirst().orElseThrow();
        assertTrue(tool.getAttributeInfo().stream().anyMatch(a -> a.getName().equals("app")));
        conn.dispose();
    }

    public void schemaWithoutAFileFailsLoudly() {
        UniversalRestConnector conn = connector(server.baseUrl(), "t", "/ok");
        try {
            conn.schema();
            throw new AssertionError("schema() without a schema file was not refused");
        } catch (ConfigurationException e) {
            assertTrue(e.getMessage().contains("schemaFilePath must be configured"), e.getMessage());
        }
        conn.dispose();
    }

    // ---- executeQuery(): objects ---------------------------------------------

    public void searchReturnsTypedObjects() {
        server.stub("/v1/admin/apps", 200, "[{\"id\":\"a1\",\"name\":\"github\",\"version\":\"1.0\","
                + "\"runtime\":\"remote\",\"status\":\"running\",\"tools\":[\"create_issue\",\"list_repos\"]}]");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");

        List<ConnectorObject> results = new ArrayList<>();
        conn.executeQuery(new ObjectClass("app"), null, co -> {
            results.add(co);
            return true;
        }, null);

        assertEquals(results.size(), 1);
        ConnectorObject app = results.get(0);
        assertEquals(app.getObjectClass(), new ObjectClass("app"));
        assertEquals(app.getUid().getUidValue(), "a1");
        assertEquals(app.getName().getNameValue(), "github");
        assertEquals(app.getAttributeByName("version").getValue().get(0), "1.0");
        assertEquals(app.getAttributeByName("tools").getValue().size(), 2);
        conn.dispose();
    }

    public void searchByNameFiltersClientSide() {
        server.stub("/v1/admin/apps", 200,
                "[{\"id\":\"a1\",\"name\":\"github\"},{\"id\":\"a2\",\"name\":\"slack\"}]");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");

        RestFilter byName = new RestFilterTranslator()
                .translate(new EqualsFilter(AttributeBuilder.build(Name.NAME, "slack"))).get(0);

        List<ConnectorObject> results = new ArrayList<>();
        conn.executeQuery(new ObjectClass("app"), byName, co -> {
            results.add(co);
            return true;
        }, null);

        assertEquals(results.size(), 1);
        assertEquals(results.get(0).getName().getNameValue(), "slack");
        conn.dispose();
    }

    public void searchUnauthorizedSurfacesAuthFailure() {
        server.stub("/v1/admin/apps", 401, "{\"error\":\"token rejected\"}");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");
        assertThrows(InvalidCredentialException.class,
                () -> conn.executeQuery(new ObjectClass("app"), null, co -> true, null));
        conn.dispose();
    }

    public void searchWithoutSchemaThrows() {
        UniversalRestConnector conn = connector(server.baseUrl(), "t", "/ok");
        assertThrows(ConfigurationException.class,
                () -> conn.executeQuery(new ObjectClass("app"), null, co -> true, null));
        conn.dispose();
    }

    // ---- executeQuery(): derived object class (tool) -------------------------

    public void searchToolDerivesFromApps() {
        server.stub("/v1/admin/apps", 200,
                "[{\"id\":\"a1\",\"name\":\"github\",\"tools\":[\"github__create_issue\",\"github__list_repos\"]},"
                        + "{\"id\":\"a2\",\"name\":\"slack\",\"tools\":[\"slack__post_message\"]}]");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t", TestSchemas.copy("derived-tool"));

        Map<String, ConnectorObject> byUid = new HashMap<>();
        conn.executeQuery(new ObjectClass("tool"), null, co -> {
            byUid.put(co.getUid().getUidValue(), co);
            return true;
        }, null);

        assertEquals(byUid.size(), 3);
        ConnectorObject createIssue = byUid.get("github__create_issue");
        assertNotNull(createIssue);
        assertEquals(createIssue.getObjectClass(), new ObjectClass("tool"));
        assertEquals(createIssue.getName().getNameValue(), "github__create_issue"); // verbatim, never parsed
        assertEquals(createIssue.getAttributeByName("app").getValue().get(0), "github"); // parent ref from app
        assertEquals(byUid.get("slack__post_message").getAttributeByName("app").getValue().get(0), "slack");
        conn.dispose();
    }

    // ---- executeQuery(): associations ----------------------------------------

    public void searchUserPopulatesRoleAssociations() {
        server.stub("/v1/admin/users", 200,
                "[{\"id\":\"u1\",\"username\":\"alice\"},{\"id\":\"u2\",\"username\":\"bob\"}]");
        server.stub("/v1/admin/assignments", 200,
                "[{\"id\":\"as1\",\"subject_kind\":\"user\",\"subject_id\":\"u1\",\"role_id\":\"r-dev\"},"
                        + "{\"id\":\"as2\",\"subject_kind\":\"user\",\"subject_id\":\"u1\",\"role_id\":\"r-ops\"},"
                        + "{\"id\":\"as3\",\"subject_kind\":\"group\",\"subject_id\":\"u1\",\"role_id\":\"r-group\"},"
                        + "{\"id\":\"as4\",\"subject_kind\":\"user\",\"subject_id\":\"u2\",\"role_id\":\"r-dev\"}]");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");

        Map<String, ConnectorObject> byName = new HashMap<>();
        conn.executeQuery(new ObjectClass("user"), null, co -> {
            byName.put(co.getName().getNameValue(), co);
            return true;
        }, null);

        Attribute aliceRoles = byName.get("alice").getAttributeByName("roles");
        assertEquals(aliceRoles.getValue().size(), 2);
        assertTrue(aliceRoles.getValue().contains("r-dev"));
        assertTrue(aliceRoles.getValue().contains("r-ops"));
        assertTrue(!aliceRoles.getValue().contains("r-group")); // wrong subject_kind excluded by 'where'
        assertEquals(byName.get("bob").getAttributeByName("roles").getValue().size(), 1);
        conn.dispose();
    }

    public void searchEvidenceAttributeFilterNarrowsLocally() {
        // The json dialect cannot push this filter, so the connector narrows locally.
        server.stub("/v1/admin/users", 200,
                "[{\"id\":\"u1\",\"username\":\"alice\"},{\"id\":\"u2\",\"username\":\"bob\"}]");
        server.stub("/v1/admin/assignments", 200,
                "[{\"id\":\"as1\",\"subject_kind\":\"user\",\"subject_id\":\"u1\",\"role_id\":\"r-dev\"},"
                        + "{\"id\":\"as2\",\"subject_kind\":\"user\",\"subject_id\":\"u1\",\"role_id\":\"r-ops\"},"
                        + "{\"id\":\"as3\",\"subject_kind\":\"user\",\"subject_id\":\"u2\",\"role_id\":\"r-dev\"}]");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");

        RestFilter byRole = new RestFilterTranslator()
                .translate(new EqualsFilter(AttributeBuilder.build("roles", "r-ops"))).get(0);
        List<ConnectorObject> results = new ArrayList<>();
        conn.executeQuery(new ObjectClass("user"), byRole, co -> {
            results.add(co);
            return true;
        }, null);

        assertEquals(results.size(), 1, "only the holder of r-ops may match");
        assertEquals(results.get(0).getName().getNameValue(), "alice");
        conn.dispose();
    }

    // ---- executeQuery(): paging ----------------------------------------------

    public void searchPagingReturnsFirstPageAndRemaining() {
        stubFiveApps();
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");
        OperationOptions options = new OperationOptionsBuilder().setPageSize(2).setPagedResultsOffset(1).build();

        CollectingHandler handler = new CollectingHandler();
        conn.executeQuery(new ObjectClass("app"), null, handler, options);

        assertEquals(handler.objects.size(), 2);
        assertEquals(handler.objects.get(0).getUid().getUidValue(), "a1");
        assertEquals(handler.objects.get(1).getUid().getUidValue(), "a2");
        assertEquals(handler.result.getRemainingPagedResults(), 3);
        conn.dispose();
    }

    public void searchPagingSecondPage() {
        stubFiveApps();
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");
        OperationOptions options = new OperationOptionsBuilder().setPageSize(2).setPagedResultsOffset(3).build();

        CollectingHandler handler = new CollectingHandler();
        conn.executeQuery(new ObjectClass("app"), null, handler, options);

        assertEquals(handler.objects.size(), 2);
        assertEquals(handler.objects.get(0).getUid().getUidValue(), "a3");
        assertEquals(handler.result.getRemainingPagedResults(), 1);
        conn.dispose();
    }

    private void stubFiveApps() {
        server.stub("/v1/admin/apps", 200, "[{\"id\":\"a1\",\"name\":\"one\"},{\"id\":\"a2\",\"name\":\"two\"},"
                + "{\"id\":\"a3\",\"name\":\"three\"},{\"id\":\"a4\",\"name\":\"four\"},{\"id\":\"a5\",\"name\":\"five\"}]");
    }

    // ---- LiveSync (SyncOp) ----------------------------------------------------

    public void getLatestSyncTokenReturnsFeedHead() {
        server.stub("/v1/admin/changes", 200,
                "{\"changes\":[],\"nextCursor\":\"\",\"more\":false,\"head\":\"cHEAD\"}");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");

        SyncToken token = conn.getLatestSyncToken(new ObjectClass("user"));

        assertEquals(token.getValue(), "cHEAD");
        conn.dispose();
    }

    public void syncUserEmitsCreateFromFeedAndAdvancesToken() {
        server.stub("/v1/admin/changes", 200,
                "{\"changes\":[{\"cursor\":\"c1\",\"type\":\"user\",\"op\":\"create\",\"id\":\"u1\"}],"
                        + "\"nextCursor\":\"c1\",\"more\":false,\"head\":\"c1\"}");
        server.stub("/v1/admin/users", 200, "[{\"id\":\"u1\",\"username\":\"alice\"}]");
        server.stub("/v1/admin/assignments", 200, "[]");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");

        CollectingSyncHandler handler = new CollectingSyncHandler();
        conn.sync(new ObjectClass("user"), new SyncToken("c0"), handler, null);

        assertEquals(handler.deltas.size(), 1);
        SyncDelta delta = handler.deltas.get(0);
        assertEquals(delta.getDeltaType(), SyncDeltaType.CREATE_OR_UPDATE);
        assertEquals(delta.getObject().getUid().getUidValue(), "u1");
        assertEquals(delta.getObject().getName().getNameValue(), "alice");
        assertEquals(handler.token.getValue(), "c1"); // watermark advanced to nextCursor
        conn.dispose();
    }

    public void syncAppReFetchesByUidFromFeed() {
        // App events carry the app uid, so the object is re-read by uid.
        server.stub("/v1/admin/changes", 200,
                "{\"changes\":[{\"cursor\":\"c1\",\"type\":\"app\",\"op\":\"create\",\"id\":\"a1-uuid\"}],"
                        + "\"nextCursor\":\"c1\",\"more\":false,\"head\":\"c1\"}");
        server.stub("/v1/admin/apps", 200, "[{\"id\":\"a1-uuid\",\"name\":\"github\",\"version\":\"1.0\"}]");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");

        CollectingSyncHandler handler = new CollectingSyncHandler();
        conn.sync(new ObjectClass("app"), new SyncToken("c0"), handler, null);

        assertEquals(handler.deltas.size(), 1);
        assertEquals(handler.deltas.get(0).getObject().getUid().getUidValue(), "a1-uuid"); // re-fetched by uid
        assertEquals(handler.deltas.get(0).getObject().getName().getNameValue(), "github");
        conn.dispose();
    }

    public void syncAppDeleteEmitsDeleteByUid() {
        server.stub("/v1/admin/changes", 200,
                "{\"changes\":[{\"cursor\":\"c1\",\"type\":\"app\",\"op\":\"delete\",\"id\":\"a1-uuid\"}],"
                        + "\"nextCursor\":\"c1\",\"more\":false,\"head\":\"c1\"}");
        server.stub("/v1/admin/apps", 200, "[]");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");

        CollectingSyncHandler handler = new CollectingSyncHandler();
        conn.sync(new ObjectClass("app"), new SyncToken("c0"), handler, null);

        assertEquals(handler.deltas.size(), 1);
        assertEquals(handler.deltas.get(0).getDeltaType(), SyncDeltaType.DELETE);
        assertEquals(handler.deltas.get(0).getUid().getUidValue(), "a1-uuid");
        conn.dispose();
    }

    public void syncAllObjectClassIsRejected() {
        server.stub("/v1/admin/changes", 200,
                "{\"changes\":[],\"nextCursor\":\"\",\"more\":false,\"head\":\"h\"}");
        UniversalRestConnector conn = connectorWithSchema(server.baseUrl(), "t");
        assertThrows(UnsupportedOperationException.class,
                () -> conn.sync(ObjectClass.ALL, null, new CollectingSyncHandler(), null));
        conn.dispose();
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

    private static final class CollectingHandler implements SearchResultsHandler {
        private final List<ConnectorObject> objects = new ArrayList<>();
        private SearchResult result;

        @Override
        public boolean handle(ConnectorObject connectorObject) {
            objects.add(connectorObject);
            return true;
        }

        @Override
        public void handleResult(SearchResult searchResult) {
            this.result = searchResult;
        }
    }
}
