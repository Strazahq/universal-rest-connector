package ai.straza.connector.rest;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import ai.straza.connector.rest.support.EmbeddedRestServer;
import ai.straza.connector.rest.support.TestSchemas;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.operations.APIOperation;
import org.identityconnectors.framework.api.operations.CreateApiOp;
import org.identityconnectors.framework.api.operations.DeleteApiOp;
import org.identityconnectors.framework.api.operations.SyncApiOp;
import org.identityconnectors.framework.api.operations.UpdateApiOp;
import org.identityconnectors.framework.api.operations.UpdateDeltaApiOp;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.Uid;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * The connector advertises only what the schema file declares: no reserved
 * ConnId class unless the file has it, and exactly the operations each class lists.
 */
@Test(groups = "unit")
public class FileDrivenSchemaTest {

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

    public void theSampleFileDeclaresAllFiveClasses() {
        UniversalRestConnector connector = connector(TestSchemas.sample());
        assertEquals(types(connector.schema()),
                Set.of("app", "tool", "user", ObjectClass.ACCOUNT_NAME, ObjectClass.GROUP_NAME));
        connector.dispose();
    }

    public void aFileWithoutTheReservedClassesYieldsAConnectorWithoutThem() {
        UniversalRestConnector connector = connector(TestSchemas.copy("evidence-only"));
        Set<String> types = types(connector.schema());
        assertEquals(types, Set.of("widget"));
        assertFalse(types.contains(ObjectClass.ACCOUNT_NAME), "__ACCOUNT__ must not be added by Java");
        assertFalse(types.contains(ObjectClass.GROUP_NAME), "__GROUP__ must not be added by Java");
        connector.dispose();
    }

    public void eachClassAdvertisesExactlyTheOperationsItLists() {
        UniversalRestConnector connector = connector(TestSchemas.sample());
        Schema schema = connector.schema();

        assertTrue(supportedFor(schema, CreateApiOp.class).contains(ObjectClass.ACCOUNT_NAME));
        assertTrue(supportedFor(schema, DeleteApiOp.class).contains(ObjectClass.ACCOUNT_NAME));
        assertFalse(supportedFor(schema, CreateApiOp.class).contains(ObjectClass.GROUP_NAME),
                "__GROUP__ lists no create, so midPoint must see the capability as absent");
        assertFalse(supportedFor(schema, DeleteApiOp.class).contains(ObjectClass.GROUP_NAME),
                "__GROUP__ lists no delete");
        assertTrue(supportedFor(schema, UpdateApiOp.class).contains(ObjectClass.GROUP_NAME));
        assertTrue(supportedFor(schema, UpdateDeltaApiOp.class).contains(ObjectClass.GROUP_NAME));

        for (String readOnly : Set.of("app", "tool", "user")) {
            assertFalse(supportedFor(schema, CreateApiOp.class).contains(readOnly), readOnly + " is read-only");
            assertFalse(supportedFor(schema, UpdateApiOp.class).contains(readOnly), readOnly + " is read-only");
            assertFalse(supportedFor(schema, UpdateDeltaApiOp.class).contains(readOnly), readOnly + " is read-only");
            assertFalse(supportedFor(schema, DeleteApiOp.class).contains(readOnly), readOnly + " is read-only");
        }
        connector.dispose();
    }

    public void aClassAdvertisesSyncOnlyWithBothItsBlockAndTheFeedTransport() {
        UniversalRestConnector withFeed = connector(TestSchemas.sample());
        Set<String> syncable = supportedFor(withFeed.schema(), SyncApiOp.class);
        assertTrue(syncable.containsAll(Set.of("app", "tool", "user",
                ObjectClass.ACCOUNT_NAME, ObjectClass.GROUP_NAME)), "got: " + syncable);
        withFeed.dispose();

        // A class sync block without a top-level feed does not advertise SyncOp.
        UniversalRestConnector withoutFeed = connector(TestSchemas.copy("evidence-only"));
        assertTrue(supportedFor(withoutFeed.schema(), SyncApiOp.class).isEmpty());
        withoutFeed.dispose();
    }

    public void aWriteAgainstAReadOnlyClassIsRefused() {
        UniversalRestConnector connector = connector(TestSchemas.sample());
        assertThrows(UnsupportedOperationException.class, () -> connector.create(new ObjectClass("app"),
                Set.of(AttributeBuilder.build(Name.NAME, "x")), null));
        assertThrows(UnsupportedOperationException.class,
                () -> connector.delete(new ObjectClass("user"), new Uid("u"), null));
        assertThrows(UnsupportedOperationException.class, () -> connector.updateDelta(new ObjectClass("tool"),
                new Uid("t"), Set.of(), null));
        assertTrue(server.requests().isEmpty(), "a refused write reached the wire");
        connector.dispose();
    }

    public void anObjectClassTheFileDoesNotDeclareIsRefusedByName() {
        UniversalRestConnector connector = connector(TestSchemas.copy("evidence-only"));
        try {
            connector.executeQuery(new ObjectClass("gadget"), null, object -> true, null);
            throw new AssertionError("an undeclared object class was not refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unsupported object class: gadget"), e.getMessage());
            assertTrue(e.getMessage().contains("widget"), "the message must say what the file does declare");
        }
        connector.dispose();
    }

    public void aCreateOnlySchemaNamingAnUnknownGateFailsAtInit() {
        // Checked at init, so a wrong gate fails the test connection, not the first create.
        UniversalRestConfiguration configuration = new UniversalRestConfiguration();
        configuration.setBaseUrl(server.baseUrl());
        configuration.setApiToken(new GuardedString("t".toCharArray()));
        configuration.setSchemaFilePath(TestSchemas.copy("unknown-gate"));
        UniversalRestConnector connector = new UniversalRestConnector();
        try {
            connector.init(configuration);
            throw new AssertionError("an unknown configuration gate survived initialisation");
        } catch (ConfigurationException e) {
            assertTrue(e.getMessage().contains("emitAgentikUrn"),
                    "the message must name the gate the file asked for: " + e.getMessage());
            assertTrue(e.getMessage().contains("emitAgenticUrn"),
                    "the message must name the gates that exist: " + e.getMessage());
        } finally {
            connector.dispose();
        }
    }

    private UniversalRestConnector connector(String schemaFilePath) {
        UniversalRestConfiguration configuration = new UniversalRestConfiguration();
        configuration.setBaseUrl(server.baseUrl());
        configuration.setApiToken(new GuardedString("t".toCharArray()));
        configuration.setSchemaFilePath(schemaFilePath);
        UniversalRestConnector connector = new UniversalRestConnector();
        connector.init(configuration);
        return connector;
    }

    private static Set<String> types(Schema schema) {
        return schema.getObjectClassInfo().stream().map(ObjectClassInfo::getType).collect(Collectors.toSet());
    }

    /** Object class names the schema advertises for one API operation. */
    private static Set<String> supportedFor(Schema schema, Class<? extends APIOperation> operation) {
        Set<ObjectClassInfo> infos = schema.getSupportedObjectClassesByOperation().get(operation);
        return infos == null ? Set.of()
                : infos.stream().map(ObjectClassInfo::getType).collect(Collectors.toSet());
    }
}
