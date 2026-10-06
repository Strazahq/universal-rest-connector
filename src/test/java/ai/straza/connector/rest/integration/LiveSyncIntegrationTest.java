package ai.straza.connector.rest.integration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.UniversalRestConnector;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.spi.SyncTokenResultsHandler;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * LiveSync against a running, seeded strazad. Opt-in; without both variables the
 * class is skipped:
 * <pre>
 *   STRAZA_TEST_BASEURL=http://localhost:8420
 *   STRAZA_TEST_ADMIN_TOKEN=&lt;admin API token with scim:read, scim:write and the read scopes&gt;
 *   mvn test -DtestGroups=live
 * </pre>
 */
@Test(groups = "live")
public class LiveSyncIntegrationTest {

    private String baseUrl;
    private String token;

    @BeforeClass
    public void requireLiveStack() {
        baseUrl = System.getenv("STRAZA_TEST_BASEURL");
        token = System.getenv("STRAZA_TEST_ADMIN_TOKEN");
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            throw new SkipException("Set STRAZA_TEST_BASEURL and STRAZA_TEST_ADMIN_TOKEN to run LiveSync live tests.");
        }
    }

    public void getLatestSyncTokenReturnsHeadPerObjectClass() {
        // __GROUP__ is covered by syncWireGroupsFromGenesis.
        UniversalRestConnector conn = connector();
        try {
            for (String oc : List.of("user", "app", "tool")) {
                SyncToken head = conn.getLatestSyncToken(new ObjectClass(oc));
                assertNotNull(head, "null head for " + oc);
                assertTrue(head.getValue() instanceof String && !((String) head.getValue()).isBlank(),
                        "blank head token for " + oc);
                System.out.println("[live] getLatestSyncToken(" + oc + ") = " + head.getValue());
            }
        } finally {
            conn.dispose();
        }
    }

    public void syncUserFromGenesisEmitsTypedUserDeltas() {
        assertClassSyncsFromGenesis("user");
    }

    public void syncAppFromGenesisEmitsTypedAppDeltas() {
        assertClassSyncsFromGenesis("app");
    }

    /** {@code role} feed events drive {@code __GROUP__}, re-read over SCIM with the same token. */
    public void syncWireGroupsFromGenesis() {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl(baseUrl);
        c.setApiToken(new GuardedString(token.toCharArray()));
        c.setSchemaFilePath(Path.of("samples/straza/straza-schema.json").toAbsolutePath().toString());
        UniversalRestConnector conn = new UniversalRestConnector();
        conn.init(c);
        try {
            Collector handler = new Collector();
            conn.sync(ObjectClass.GROUP, new SyncToken(""), handler, null);
            assertTrue(!handler.deltas.isEmpty(), "no wire-group deltas from genesis (expected seeded roles)");
            assertNotNull(handler.watermark, "no final watermark delivered");
            System.out.println("[live] sync(__GROUP__, genesis) -> " + handler.deltas.size()
                    + " deltas, watermark=" + handler.watermark.getValue());
        } finally {
            conn.dispose();
        }
    }

    /** Syncs {@code objectClass} from the start of the feed and checks the deltas and the watermark. */
    private void assertClassSyncsFromGenesis(String objectClass) {
        UniversalRestConnector conn = connector();
        try {
            ObjectClass oc = new ObjectClass(objectClass);
            Collector handler = new Collector();
            conn.sync(oc, new SyncToken(""), handler, null);

            assertTrue(!handler.deltas.isEmpty(),
                    "no deltas for " + objectClass + " from genesis (expected seeded inventory)");
            long createOrUpdate = 0;
            for (SyncDelta delta : handler.deltas) {
                assertEquals(objectClassOf(delta), oc, "delta for wrong object class: " + delta);
                assertNotNull(delta.getToken(), "delta without a token");
                if (delta.getDeltaType() == SyncDeltaType.CREATE_OR_UPDATE) {
                    assertNotNull(delta.getObject(), "CREATE_OR_UPDATE without an object");
                    assertNotNull(delta.getObject().getUid(), "object without a uid");
                    createOrUpdate++;
                }
            }
            assertTrue(createOrUpdate >= 1, "expected >=1 CREATE_OR_UPDATE for " + objectClass);
            assertNotNull(handler.watermark, "no final watermark delivered");
            assertTrue(!((String) handler.watermark.getValue()).isBlank(), "blank watermark");
            System.out.println("[live] sync(" + objectClass + ", genesis) -> " + handler.deltas.size()
                    + " deltas (" + createOrUpdate + " create/update), watermark=" + handler.watermark.getValue());
        } finally {
            conn.dispose();
        }
    }

    private static ObjectClass objectClassOf(SyncDelta delta) {
        return delta.getObject() != null ? delta.getObject().getObjectClass() : delta.getObjectClass();
    }

    private UniversalRestConnector connector() {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl(baseUrl);
        c.setApiToken(new GuardedString(token.toCharArray()));
        c.setSchemaFilePath(Path.of("samples/straza/straza-schema.json").toAbsolutePath().toString());
        UniversalRestConnector conn = new UniversalRestConnector();
        conn.init(c);
        return conn;
    }

    private static final class Collector implements SyncResultsHandler, SyncTokenResultsHandler {
        private final List<SyncDelta> deltas = new ArrayList<>();
        private SyncToken watermark;

        @Override
        public boolean handle(SyncDelta delta) {
            deltas.add(delta);
            return true;
        }

        @Override
        public void handleResult(SyncToken result) {
            this.watermark = result;
        }
    }

    @AfterClass(alwaysRun = true)
    public void noop() {
        // nothing to tear down
    }
}
