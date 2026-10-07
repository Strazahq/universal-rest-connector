package ai.straza.connector.rest.sync;

import java.io.IOException;
import java.util.List;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.schema.SyncFeedConfig;
import ai.straza.connector.rest.support.EmbeddedRestServer;
import ai.straza.connector.rest.transport.HttpClientManager;
import ai.straza.connector.rest.schema.TargetInfo;
import ai.straza.connector.rest.transport.ResourceClient;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test(groups = "unit")
public class ChangeFeedReaderTest {

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

    /** The default feed config, used across the tests. */
    static SyncFeedConfig defaultFeed() {
        return new SyncFeedConfig("/v1/admin/changes", "since", "types", "limit", 500,
                "changes", "head", "nextCursor", "more", "cursor", "type", "op", "id");
    }

    private ResourceClient client() {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl(server.baseUrl());
        c.setApiToken(new GuardedString("t".toCharArray()));
        return new ResourceClient(new HttpClientManager(c), c, TargetInfo.fallback());
    }

    // ---- parsePage (pure) -----------------------------------------------------

    public void parsePageReadsRecordsAndPageFields() {
        String body = "{\"changes\":["
                + "{\"cursor\":\"c1\",\"type\":\"user\",\"op\":\"create\",\"id\":\"u1\"},"
                + "{\"cursor\":\"c2\",\"type\":\"identity\",\"op\":\"delete\",\"id\":\"dead\"}],"
                + "\"nextCursor\":\"c2\",\"more\":false,\"head\":\"c9\"}";

        ChangeFeedReader.FeedPage page = ChangeFeedReader.parsePage(body, defaultFeed());

        assertEquals(page.records().size(), 2);
        assertEquals(page.records().get(0), new ChangeRecord("c1", "user", "create", "u1"));
        assertTrue(page.records().get(1).isDeleteOp());
        assertEquals(page.nextCursor(), "c2");
        assertEquals(page.head(), "c9");
        assertTrue(!page.more());
    }

    // ---- head() ---------------------------------------------------------------

    public void headReturnsFeedHead() {
        server.stub("/v1/admin/changes", 200, "{\"changes\":[],\"nextCursor\":\"\",\"more\":false,\"head\":\"cHEAD\"}");
        assertEquals(new ChangeFeedReader(client(), defaultFeed()).head(), "cHEAD");
    }

    public void aFeedEndpointThatAnswersNotFoundIsNotAMissingObject() {
        // A 404 from the feed means a wrong endpoint, not a missing object; the
        // latter would turn into delete deltas.
        server.stub("/v1/admin/changes", 404, "{\"error\":\"no such route\"}");
        try {
            new ChangeFeedReader(client(), defaultFeed()).head();
            throw new AssertionError("a 404 from the change feed was not reported at all");
        } catch (UnknownUidException e) {
            throw new AssertionError("a wrong feed endpoint read as a missing object: " + e.getMessage());
        } catch (ConnectorException e) {
            assertTrue(e.getMessage().contains("/v1/admin/changes"),
                    "the message must name the endpoint: " + e.getMessage());
            assertTrue(e.getMessage().contains("404"), "the message must name the status: " + e.getMessage());
        }
    }

    // ---- changesSince(): paging + query --------------------------------------

    public void changesSinceFollowsNextCursorAcrossPages() {
        String page1 = "{\"changes\":[{\"cursor\":\"c1\",\"type\":\"app\",\"op\":\"create\",\"id\":\"github\"}],"
                + "\"nextCursor\":\"c1\",\"more\":true,\"head\":\"c3\"}";
        String page2 = "{\"changes\":[{\"cursor\":\"c2\",\"type\":\"app\",\"op\":\"delete\",\"id\":\"slack\"}],"
                + "\"nextCursor\":\"c2\",\"more\":false,\"head\":\"c3\"}";
        server.stubSequence("/v1/admin/changes", List.of(page1, page2));

        FeedBatch batch = new ChangeFeedReader(client(), defaultFeed()).changesSince(null, List.of("app"));

        assertEquals(batch.records().size(), 2);
        assertEquals(batch.records().get(0).id(), "github");
        assertEquals(batch.records().get(1).id(), "slack");
        assertEquals(batch.nextCursor(), "c2"); // advanced past both pages
        assertEquals(batch.head(), "c3");
    }

    public void changesSinceSendsSinceAndTypesAndLimit() {
        server.stub("/v1/admin/changes", 200, "{\"changes\":[],\"nextCursor\":\"X\",\"more\":false,\"head\":\"X\"}");

        new ChangeFeedReader(client(), defaultFeed()).changesSince("X", List.of("role", "binding"));

        String uri = server.requestUris().get(server.requestUris().size() - 1);
        assertTrue(uri.contains("since=X"), uri);
        assertTrue(uri.contains("types=role%2Cbinding"), uri); // comma URL-encoded
        assertTrue(uri.contains("limit=500"), uri);
    }
}
