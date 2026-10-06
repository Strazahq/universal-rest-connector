package ai.straza.connector.rest.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

@Test(groups = "unit")
public class UniversalSchemaHandlerTest {

    private static String shippedSample() {
        return Path.of("samples/straza/straza-schema.json").toAbsolutePath().toString();
    }

    public void parsesShippedSample() {
        UniversalSchemaHandler handler = new UniversalSchemaHandler(shippedSample());
        Map<String, SchemaType> types = handler.getSchemaTypes();
        assertTrue(types.keySet().containsAll(java.util.Set.of("app", "tool", "user")));
        // Roles are modelled by __GROUP__ only.
        assertTrue(!types.containsKey("role"));
        assertTrue(!types.containsKey("group"));

        SchemaType app = types.get("app");
        assertEquals(app.getListEndpoint(), "/v1/admin/apps");
        assertEquals(app.getIcfsUid(), "id");
        assertEquals(app.getIcfsName(), "name");
        assertTrue(attribute(app, "tools").isMultivalued());
        assertTrue(attribute(app, "version").isReturnedByDefault());
        assertTrue(!attribute(app, "detail").isReturnedByDefault());

        SchemaType user = types.get("user");
        assertEquals(user.getIcfsName(), "username");
        assertTrue(!user.isUidAndNameSame());
    }

    public void parsesTempSchemaWithDefaults() throws IOException {
        Path file = writeSchema("{ \"objects\": [ { \"objectClass\": \"widget\", "
                + "\"listEndpoint\": \"/v1/widgets\", \"icfsUid\": \"id\", \"icfsName\": \"id\", "
                + "\"attributes\": { \"label\": {}, \"tags\": { \"multivalued\": true } } } ] }");
        try {
            UniversalSchemaHandler handler = new UniversalSchemaHandler(file.toString());
            SchemaType widget = handler.getSchemaTypes().get("widget");
            assertTrue(widget.isUidAndNameSame());
            assertTrue(!attribute(widget, "label").isMultivalued());
            assertEquals(attribute(widget, "label").getDataType(), String.class);
            assertTrue(attribute(widget, "tags").isMultivalued());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public void missingRequiredFieldThrows() throws IOException {
        Path file = writeSchema("{ \"objects\": [ { \"objectClass\": \"x\", \"icfsUid\": \"id\", "
                + "\"icfsName\": \"id\" } ] }"); // no listEndpoint
        try {
            assertThrows(ConfigurationException.class, () -> new UniversalSchemaHandler(file.toString()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public void noObjectsArrayThrows() throws IOException {
        Path file = writeSchema("{ \"nope\": true }");
        try {
            assertThrows(ConfigurationException.class, () -> new UniversalSchemaHandler(file.toString()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public void parsesAssociationsFromSample() {
        UniversalSchemaHandler handler = new UniversalSchemaHandler(shippedSample());

        SchemaType user = handler.getSchemaTypes().get("user");
        assertEquals(user.getAssociations().size(), 1);
        AssociationType roles = user.getAssociations().get(0);
        assertEquals(roles.getAttributeName(), "roles");
        assertEquals(roles.getEndpoint(), "/v1/admin/assignments");
        assertEquals(roles.getLocalField(), "id");
        assertEquals(roles.getMatchField(), "subject_id");
        assertEquals(roles.getValueField(), "role_id");
        assertEquals(roles.getWhere().get("subject_kind"), "user");
    }

    public void parsesDerivedToolObjectClass() {
        UniversalSchemaHandler handler = new UniversalSchemaHandler(shippedSample());
        SchemaType tool = handler.getSchemaTypes().get("tool");
        assertNotNull(tool);
        assertTrue(tool.isDerived());
        assertEquals(tool.getDerived().getParentObjectClass(), "app");
        assertEquals(tool.getDerived().getArrayField(), "tools");
        assertEquals(tool.getDerived().getParentRefAttribute(), "app");
        assertEquals(tool.getDerived().getParentRefValueField(), "name");
    }

    public void parsesSyncFeedConfigWithDefaults() {
        UniversalSchemaHandler handler = new UniversalSchemaHandler(shippedSample());
        SyncFeedConfig feed = handler.getSyncFeedConfig();
        assertNotNull(feed);
        assertEquals(feed.getFeedEndpoint(), "/v1/admin/changes");
        // the rest are defaults
        assertEquals(feed.getSinceParam(), "since");
        assertEquals(feed.getRecordsField(), "changes");
        assertEquals(feed.getCursorField(), "cursor");
        assertEquals(feed.getHeadField(), "head");
        assertEquals(feed.getPageLimit(), 500);
    }

    public void parsesPerObjectClassSyncRouting() {
        UniversalSchemaHandler handler = new UniversalSchemaHandler(shippedSample());

        SyncType user = handler.getSchemaTypes().get("user").getSyncType();
        assertNotNull(user);
        // Membership changes emit user ids, so `user` alone drives this class.
        assertEquals(user.getRequestTypes(), java.util.List.of("user"));
        assertTrue(user.isSelf("user"));
        assertTrue(user.isDelete("identity"));
        assertTrue(!user.isReemitAll("group"));
        assertTrue(!user.matchesByName());

        SyncType app = handler.getSchemaTypes().get("app").getSyncType();
        assertTrue(app.isSelf("app"));
        assertTrue(!app.matchesByName()); // app events carry the app uid

        SyncType tool = handler.getSchemaTypes().get("tool").getSyncType();
        assertTrue(tool.isReemitAll("tool")); // drift re-emits every tool
    }

    private static SchemaTypeAttribute attribute(SchemaType type, String name) {
        return type.getAttributes().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no attribute " + name));
    }

    private static Path writeSchema(String json) throws IOException {
        Path file = Files.createTempFile("uni-schema", ".json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
