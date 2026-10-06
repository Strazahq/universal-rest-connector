package ai.straza.connector.rest.objects;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.UniversalRestConnector;
import ai.straza.connector.rest.support.EmbeddedRestServer;
import ai.straza.connector.rest.support.EmbeddedRestServer.RecordedRequest;
import ai.straza.connector.rest.support.TestSchemas;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.AttributeDeltaBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.Uid;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * Writes to the reserved ConnId classes as a schema file declares them: create,
 * full replace and delta documents, the enable mapping and the gated create-only
 * schema URN.
 */
@Test(groups = "unit")
public class FileDrivenWriteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_URN = "urn:straza:params:scim:schemas:extension:2.0:User";
    private static final String AGENT_URN = "urn:ietf:params:scim:schemas:extension:agent:2.0:Agent";

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

    // ---- create -------------------------------------------------------------------

    public void createSendsTheDeclaredPathsAndAdoptsTheReturnedUid() {
        server.stub("POST", "/scim/v2/Users", 201, "{\"id\":\"u-100\",\"userName\":\"grace\"}");
        UniversalRestConnector connector = connector();

        Uid uid = connector.create(ObjectClass.ACCOUNT, Set.of(
                AttributeBuilder.build(Name.NAME, "grace"),
                AttributeBuilder.build("externalId", "idm-4711"),
                AttributeBuilder.build("displayName", "Grace Hopper"),
                AttributeBuilder.build("email", "grace@example.com"),
                AttributeBuilder.buildEnabled(true),
                AttributeBuilder.build("userType", "human"),
                AttributeBuilder.build("agencyMode", "interactive"),
                AttributeBuilder.build("sponsor", "kim")), null);

        assertEquals(uid.getUidValue(), "u-100");
        RecordedRequest request = server.lastRequest();
        assertEquals(request.method(), "POST");
        assertEquals(request.uri(), "/scim/v2/Users");
        assertEquals(request.authorization(), "Bearer admin-token");
        assertTrue(request.contentType().startsWith("application/scim+json"));

        JsonNode body = json(request.body());
        assertEquals(body.path("userName").asText(), "grace");
        assertEquals(body.path("externalId").asText(), "idm-4711");
        assertEquals(body.path("displayName").asText(), "Grace Hopper");
        assertEquals(body.path("emails").get(0).path("value").asText(), "grace@example.com");
        assertTrue(body.path("emails").get(0).path("primary").asBoolean());
        assertTrue(body.path("active").asBoolean());
        assertEquals(body.path("userType").asText(), "human");
        assertEquals(body.path(USER_URN).path("agencyMode").asText(), "interactive");
        assertEquals(body.path(USER_URN).path("sponsor").asText(), "kim");
        assertTrue(schemas(body).contains(USER_URN), "a namespace that carries values joins the envelope");
        assertTrue(schemas(body).contains("urn:ietf:params:scim:schemas:core:2.0:User"));
        assertFalse(request.body().contains(OperationalAttributes.ENABLE_NAME),
                "__ENABLE__ must never appear in a body");
        connector.dispose();
    }

    public void createAdoptsTheReturnedUidWhenTheServerRevives() {
        // A revived object keeps its old id; the uid is whatever the create response says.
        server.stub("POST", "/scim/v2/Users", 201, "{\"id\":\"u-EXISTING\",\"userName\":\"rehire\"}");
        UniversalRestConnector connector = connector();
        Uid uid = connector.create(ObjectClass.ACCOUNT,
                Set.of(AttributeBuilder.build(Name.NAME, "rehire")), null);
        assertEquals(uid.getUidValue(), "u-EXISTING");
        connector.dispose();
    }

    public void anEnableWithNoValueOnCreateTakesTheServerDefault() {
        // midPoint sends an empty __ENABLE__ when activation is undefined; create omits it.
        server.stub("POST", "/scim/v2/Users", 201, "{\"id\":\"u-d\",\"userName\":\"undecided\"}");
        UniversalRestConnector connector = connector();

        Uid uid = connector.create(ObjectClass.ACCOUNT, Set.of(
                AttributeBuilder.build(Name.NAME, "undecided"),
                AttributeBuilder.build(OperationalAttributes.ENABLE_NAME)), null);

        assertEquals(uid.getUidValue(), "u-d");
        assertFalse(json(server.lastRequest().body()).has("active"),
                "an __ENABLE__ with no value must leave the field out: " + server.lastRequest().body());
        connector.dispose();
    }

    public void createRequiresTheDeclaredRequiredName() {
        UniversalRestConnector connector = connector();
        assertThrows(InvalidAttributeValueException.class, () -> connector.create(ObjectClass.ACCOUNT,
                Set.of(AttributeBuilder.build("displayName", "No Name")), null));
        assertTrue(server.requests().isEmpty(), "a refused create reached the wire");
        connector.dispose();
    }

    public void createRefusesACallerSuppliedUid() {
        UniversalRestConnector connector = connector();
        assertThrows(InvalidAttributeValueException.class, () -> connector.create(ObjectClass.ACCOUNT,
                Set.of(AttributeBuilder.build(Name.NAME, "x"), AttributeBuilder.build(Uid.NAME, "mine")), null));
        connector.dispose();
    }

    public void createRefusesAttributesTheFileDeclaresReadOnly() {
        UniversalRestConnector connector = connector();
        for (String readOnly : List.of("groups", "locked", "lockReason", "lockedAt", "lockOrigin",
                "kind", "origin")) {
            assertThrows(InvalidAttributeValueException.class, () -> connector.create(ObjectClass.ACCOUNT,
                    Set.of(AttributeBuilder.build(Name.NAME, "x"), AttributeBuilder.build(readOnly, "v")), null));
        }
        assertTrue(server.requests().isEmpty(), "a refused create reached the wire");
        connector.dispose();
    }

    public void createRefusesAnAttributeTheFileDoesNotDeclare() {
        UniversalRestConnector connector = connector();
        try {
            connector.create(ObjectClass.ACCOUNT, Set.of(AttributeBuilder.build(Name.NAME, "x"),
                    AttributeBuilder.build("invented", "v")), null);
            throw new AssertionError("an undeclared attribute was not refused");
        } catch (InvalidAttributeValueException e) {
            assertTrue(e.getMessage().contains("is not declared on object class __ACCOUNT__"), e.getMessage());
        }
        connector.dispose();
    }

    // ---- the conditional create-only schema URN ------------------------------------

    @DataProvider
    public Object[][] conditionValues() {
        return new Object[][] {{"agent"}, {"service"}, {"Agent"}, {"SERVICE"}};
    }

    @Test(dataProvider = "conditionValues")
    public void theConditionalUrnFiresForEveryDeclaredValue(String userType) {
        server.stub("POST", "/scim/v2/Users", 201, "{\"id\":\"u-a\"}");
        UniversalRestConnector connector = connector();
        connector.create(ObjectClass.ACCOUNT, Set.of(
                AttributeBuilder.build(Name.NAME, "svc"), AttributeBuilder.build("userType", userType)), null);
        assertTrue(schemasOnWire().contains(AGENT_URN), "URN missing for userType=" + userType);
        connector.dispose();
    }

    @DataProvider
    public Object[][] nonConditionValues() {
        return new Object[][] {{"human"}, {"Human"}, {"robot"}};
    }

    @Test(dataProvider = "nonConditionValues")
    public void theConditionalUrnNeverFiresOutsideTheDeclaredSet(String userType) {
        server.stub("POST", "/scim/v2/Users", 201, "{\"id\":\"u-h\"}");
        UniversalRestConnector connector = connector();
        connector.create(ObjectClass.ACCOUNT, Set.of(
                AttributeBuilder.build(Name.NAME, "hum"), AttributeBuilder.build("userType", userType)), null);
        assertFalse(schemasOnWire().contains(AGENT_URN), "URN wrongly sent for userType=" + userType);
        connector.dispose();
    }

    public void theConditionalUrnNeverFiresWhenTheAttributeIsAbsent() {
        server.stub("POST", "/scim/v2/Users", 201, "{\"id\":\"u-p\"}");
        UniversalRestConnector connector = connector();
        connector.create(ObjectClass.ACCOUNT, Set.of(AttributeBuilder.build(Name.NAME, "plain")), null);
        assertFalse(schemasOnWire().contains(AGENT_URN));
        connector.dispose();
    }

    public void theConditionalUrnNeverFiresWhenItsGateIsOff() {
        server.stub("POST", "/scim/v2/Users", 201, "{\"id\":\"u-g\"}");
        UniversalRestConfiguration configuration = configuration();
        configuration.setEmitAgenticUrn(false);
        UniversalRestConnector connector = new UniversalRestConnector();
        connector.init(configuration);
        connector.create(ObjectClass.ACCOUNT, Set.of(
                AttributeBuilder.build(Name.NAME, "svc"), AttributeBuilder.build("userType", "agent")), null);
        assertFalse(schemasOnWire().contains(AGENT_URN), "the gate was off but the URN was sent");
        connector.dispose();
    }

    public void theConditionalUrnIsCreateOnly() {
        server.stub("GET", "/scim/v2/Users/u-1", 200,
                "{\"id\":\"u-1\",\"userName\":\"svc\",\"userType\":\"agent\",\"active\":true}");
        server.stub("PUT", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();
        connector.update(ObjectClass.ACCOUNT, new Uid("u-1"),
                Set.of(AttributeBuilder.build("displayName", "renamed")), null);
        assertFalse(schemasOnWire().contains(AGENT_URN), "a create-only mark rode a replacement document");
        connector.dispose();
    }

    // ---- replace -------------------------------------------------------------------

    public void replaceReadsCurrentThenCarriesEveryWritableAttribute() {
        server.stub("GET", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\",\"userName\":\"atlas\","
                + "\"displayName\":\"Atlas\",\"active\":true,\"userType\":\"service\","
                + "\"" + USER_URN + "\":{\"agencyMode\":\"supervised\",\"sponsor\":\"kim\","
                + "\"ephemeral\":true,\"locked\":false}}");
        server.stub("PUT", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        Uid uid = connector.update(ObjectClass.ACCOUNT, new Uid("u-1"),
                Set.of(AttributeBuilder.build("displayName", "Atlas (renamed)")), null);

        assertEquals(uid.getUidValue(), "u-1");
        JsonNode body = json(server.lastRequest().body());
        assertEquals(server.lastRequest().method(), "PUT");
        assertEquals(body.path("userName").asText(), "atlas");
        assertEquals(body.path("displayName").asText(), "Atlas (renamed)");
        assertEquals(body.path("userType").asText(), "service", "typology carried, not clobbered");
        assertTrue(body.path("active").asBoolean());
        assertEquals(body.path(USER_URN).path("agencyMode").asText(), "supervised");
        assertTrue(body.path(USER_URN).path("ephemeral").asBoolean());
        assertFalse(body.path(USER_URN).has("locked"), "a read-only attribute must never ride a body");
        connector.dispose();
    }

    public void replaceClearsAMappedAttributeByOmission() {
        server.stub("GET", "/scim/v2/Users/u-1", 200,
                "{\"id\":\"u-1\",\"userName\":\"atlas\",\"displayName\":\"Atlas\",\"active\":true}");
        server.stub("PUT", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        connector.update(ObjectClass.ACCOUNT, new Uid("u-1"),
                Set.of(AttributeBuilder.build("displayName")), null);

        assertFalse(json(server.lastRequest().body()).has("displayName"));
        connector.dispose();
    }

    public void replaceRefusesToClearWhatOnlyADeltaCanClear() {
        server.stub("GET", "/scim/v2/Users/u-1", 200,
                "{\"id\":\"u-1\",\"userName\":\"atlas\",\"active\":true,\"userType\":\"service\"}");
        UniversalRestConnector connector = connector();
        try {
            connector.update(ObjectClass.ACCOUNT, new Uid("u-1"),
                    Set.of(AttributeBuilder.build("userType")), null);
            throw new AssertionError("clearing a patchOnly attribute was not refused");
        } catch (InvalidAttributeValueException e) {
            assertTrue(e.getMessage().contains("is a delta operation"), e.getMessage());
        }
        connector.dispose();
    }

    public void groupReplaceIsTheMembersOnlyDocument() {
        server.stub("GET", "/scim/v2/Groups/r-1", 200,
                "{\"id\":\"r-1\",\"displayName\":\"dev\",\"members\":[{\"value\":\"u-1\"},{\"value\":\"u-2\"}]}");
        server.stub("PUT", "/scim/v2/Groups/r-1", 200, "{\"id\":\"r-1\"}");
        UniversalRestConnector connector = connector();

        connector.update(ObjectClass.GROUP, new Uid("r-1"), Set.of(), null);
        JsonNode kept = json(server.lastRequest().body());
        assertEquals(kept.path("members").size(), 2, "current membership is carried over");
        assertFalse(kept.has("displayName"), "a read-only name is never spelled in the document");

        connector.update(ObjectClass.GROUP, new Uid("r-1"),
                Set.of(AttributeBuilder.build("members", List.of("u-9"))), null);
        JsonNode replaced = json(server.lastRequest().body());
        assertEquals(replaced.path("members").size(), 1);
        assertEquals(replaced.path("members").get(0).path("value").asText(), "u-9");
        connector.dispose();
    }

    public void anEmptyMultivaluedReplacementIsSentAsAnEmptyArray() {
        // An omitted members key would leave membership unchanged, so an empty array is sent.
        server.stub("GET", "/scim/v2/Groups/r-1", 200,
                "{\"id\":\"r-1\",\"displayName\":\"dev\",\"members\":[{\"value\":\"u-1\"}]}");
        server.stub("PUT", "/scim/v2/Groups/r-1", 200, "{\"id\":\"r-1\"}");
        UniversalRestConnector connector = connector();

        connector.update(ObjectClass.GROUP, new Uid("r-1"),
                Set.of(AttributeBuilder.build("members", List.of())), null);
        JsonNode emptyList = json(server.lastRequest().body()).path("members");
        assertTrue(emptyList.isArray(), "members must be sent as an array: " + server.lastRequest().body());
        assertEquals(emptyList.size(), 0);

        connector.update(ObjectClass.GROUP, new Uid("r-1"),
                Set.of(AttributeBuilder.build("members")), null);
        JsonNode noValue = json(server.lastRequest().body()).path("members");
        assertTrue(noValue.isArray(), "members must be sent as an array: " + server.lastRequest().body());
        assertEquals(noValue.size(), 0);
        connector.dispose();
    }

    public void replaceCarriesAnArrayTheCallerDoesNotTouchVerbatim() {
        server.stub("GET", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\",\"userName\":\"atlas\","
                + "\"active\":true,\"emails\":[{\"value\":\"work@x\",\"primary\":true,\"type\":\"work\"},"
                + "{\"value\":\"home@x\",\"primary\":false,\"type\":\"home\"}]}");
        server.stub("PUT", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        connector.update(ObjectClass.ACCOUNT, new Uid("u-1"),
                Set.of(AttributeBuilder.build("displayName", "Atlas")), null);

        JsonNode emails = json(server.lastRequest().body()).path("emails");
        assertEquals(emails.size(), 2, "an entry the caller never touched was dropped");
        assertEquals(emails.get(0).path("type").asText(), "work", "a field the connector does not map was dropped");
        assertEquals(emails.get(1).path("value").asText(), "home@x");
        connector.dispose();
    }

    public void aCallerSuppliedArrayValueRewritesTheSelectedEntry() {
        server.stub("GET", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\",\"userName\":\"atlas\","
                + "\"active\":true,\"emails\":[{\"value\":\"work@x\",\"primary\":true,\"type\":\"work\"}]}");
        server.stub("PUT", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        connector.update(ObjectClass.ACCOUNT, new Uid("u-1"),
                Set.of(AttributeBuilder.build("email", "new@x")), null);

        JsonNode emails = json(server.lastRequest().body()).path("emails");
        assertEquals(emails.size(), 1);
        assertEquals(emails.get(0).path("value").asText(), "new@x");
        assertTrue(emails.get(0).path("primary").asBoolean(), "the selector rides the rewritten entry");
        connector.dispose();
    }

    public void groupReplaceToleratesTheCurrentNameAndRefusesARename() {
        server.stub("GET", "/scim/v2/Groups/r-1", 200,
                "{\"id\":\"r-1\",\"displayName\":\"dev\",\"members\":[]}");
        server.stub("PUT", "/scim/v2/Groups/r-1", 200, "{\"id\":\"r-1\"}");
        UniversalRestConnector connector = connector();

        connector.update(ObjectClass.GROUP, new Uid("r-1"),
                Set.of(AttributeBuilder.build(Name.NAME, "dev")), null);
        assertFalse(json(server.lastRequest().body()).has("displayName"));

        assertThrows(InvalidAttributeValueException.class, () -> connector.update(ObjectClass.GROUP,
                new Uid("r-1"), Set.of(AttributeBuilder.build(Name.NAME, "renamed")), null));
        connector.dispose();
    }

    public void replaceRefusesTheReadOnlyProjection() {
        server.stub("GET", "/scim/v2/Groups/r-1", 200, "{\"id\":\"r-1\",\"displayName\":\"dev\"}");
        UniversalRestConnector connector = connector();
        for (String readOnly : List.of("role", "roleKind", "plane", "description", "apps", "tools",
                "policies", "server", "administers")) {
            assertThrows(InvalidAttributeValueException.class, () -> connector.update(ObjectClass.GROUP,
                    new Uid("r-1"), Set.of(AttributeBuilder.build(readOnly, "evil")), null));
        }
        connector.dispose();
    }

    // ---- delta ---------------------------------------------------------------------

    public void enableDeltaRidesTheDeclaredEnablePath() {
        server.stub("PATCH", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        connector.updateDelta(ObjectClass.ACCOUNT, new Uid("u-1"), deltas(
                AttributeDeltaBuilder.build(OperationalAttributes.ENABLE_NAME, List.of(Boolean.FALSE))), null);

        JsonNode operation = json(server.lastRequest().body()).path("Operations").get(0);
        assertEquals(operation.path("op").asText(), "replace");
        assertEquals(operation.path("path").asText(), "active");
        assertFalse(operation.path("value").asBoolean());
        assertFalse(server.lastRequest().body().contains(OperationalAttributes.ENABLE_NAME),
                "__ENABLE__ leaked onto the wire");
        connector.dispose();
    }

    public void aFilteredArrayDeltaRebuildsTheArray() {
        server.stub("PATCH", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        connector.updateDelta(ObjectClass.ACCOUNT, new Uid("u-1"),
                deltas(AttributeDeltaBuilder.build("email", List.of("new@example.com"))), null);

        JsonNode operation = json(server.lastRequest().body()).path("Operations").get(0);
        assertEquals(operation.path("path").asText(), "emails");
        assertEquals(operation.path("value").get(0).path("value").asText(), "new@example.com");
        assertTrue(operation.path("value").get(0).path("primary").asBoolean());
        connector.dispose();
    }

    public void aNamespacedDeltaUsesTheQualifiedPath() {
        server.stub("PATCH", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        connector.updateDelta(ObjectClass.ACCOUNT, new Uid("u-1"),
                deltas(AttributeDeltaBuilder.build("agencyMode", List.of("supervised"))), null);

        JsonNode operation = json(server.lastRequest().body()).path("Operations").get(0);
        assertEquals(operation.path("path").asText(), USER_URN + ":agencyMode");
        assertEquals(operation.path("value").asText(), "supervised");
        connector.dispose();
    }

    public void clearingASingleValuedAttributeIsARemoveWithNoValue() {
        server.stub("PATCH", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        connector.updateDelta(ObjectClass.ACCOUNT, new Uid("u-1"),
                deltas(AttributeDeltaBuilder.build("swarmId", List.of())), null);

        JsonNode operation = json(server.lastRequest().body()).path("Operations").get(0);
        assertEquals(operation.path("op").asText(), "remove");
        assertEquals(operation.path("path").asText(), USER_URN + ":swarmId");
        assertNull(operation.get("value"));
        connector.dispose();
    }

    public void aNameDeltaReplacesTheDeclaredNameField() {
        server.stub("PATCH", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\"}");
        UniversalRestConnector connector = connector();

        connector.updateDelta(ObjectClass.ACCOUNT, new Uid("u-1"),
                deltas(AttributeDeltaBuilder.build(Name.NAME, List.of("renamed"))), null);

        JsonNode operation = json(server.lastRequest().body()).path("Operations").get(0);
        assertEquals(operation.path("path").asText(), "userName");
        assertEquals(operation.path("value").asText(), "renamed");
        connector.dispose();
    }

    public void addAndRemoveOnASingleValuedAttributeAreRefused() {
        UniversalRestConnector connector = connector();
        AttributeDelta delta = new AttributeDeltaBuilder().setName("title").addValueToAdd("x").build();
        try {
            connector.updateDelta(ObjectClass.ACCOUNT, new Uid("u-1"), deltas(delta), null);
            throw new AssertionError("an add on a single-valued attribute was not refused");
        } catch (InvalidAttributeValueException e) {
            assertTrue(e.getMessage().contains("is single-valued"), e.getMessage());
        }
        connector.dispose();
    }

    public void multivaluedDeltasUseTheProtocolForms() {
        server.stub("PATCH", "/scim/v2/Groups/r-1", 200, "{\"id\":\"r-1\"}");
        UniversalRestConnector connector = connector();

        connector.updateDelta(ObjectClass.GROUP, new Uid("r-1"), deltas(new AttributeDeltaBuilder()
                .setName("members").addValueToAdd("u-1").addValueToRemove("u-2").build()), null);

        JsonNode operations = json(server.lastRequest().body()).path("Operations");
        assertEquals(operations.size(), 2);
        assertEquals(operations.get(0).path("op").asText(), "add");
        assertEquals(operations.get(0).path("path").asText(), "members");
        assertEquals(operations.get(0).path("value").get(0).path("value").asText(), "u-1");
        assertEquals(operations.get(1).path("op").asText(), "remove");
        assertEquals(operations.get(1).path("path").asText(), "members[value eq \"u-2\"]");
        assertNull(operations.get(1).get("value"));
        connector.dispose();
    }

    public void aMultivaluedReplaceSendsTheWholeList() {
        server.stub("PATCH", "/scim/v2/Groups/r-1", 200, "{\"id\":\"r-1\"}");
        UniversalRestConnector connector = connector();

        connector.updateDelta(ObjectClass.GROUP, new Uid("r-1"), deltas(
                new AttributeDeltaBuilder().setName("members").addValueToReplace("u-9").build()), null);

        JsonNode operation = json(server.lastRequest().body()).path("Operations").get(0);
        assertEquals(operation.path("op").asText(), "replace");
        assertEquals(operation.path("path").asText(), "members");
        assertEquals(operation.path("value").get(0).path("value").asText(), "u-9");
        connector.dispose();
    }

    public void aRenameDeltaIsRefusedWhenTheNameIsReadOnly() {
        UniversalRestConnector connector = connector();
        assertThrows(InvalidAttributeValueException.class, () -> connector.updateDelta(ObjectClass.GROUP,
                new Uid("r-1"), deltas(AttributeDeltaBuilder.build(Name.NAME, List.of("renamed"))), null));
        assertTrue(server.requests().isEmpty(), "a refused rename reached the wire");
        connector.dispose();
    }

    public void deltasAgainstTheReadOnlyProjectionAreRefused() {
        UniversalRestConnector connector = connector();
        for (String readOnly : List.of("role", "roleKind", "plane", "description", "apps", "tools",
                "policies", "server", "administers")) {
            assertThrows(InvalidAttributeValueException.class, () -> connector.updateDelta(ObjectClass.GROUP,
                    new Uid("r-1"), deltas(AttributeDeltaBuilder.build(readOnly, List.of("evil"))), null));
        }
        for (String readOnly : List.of("groups", "locked", "lockReason", "kind", "origin")) {
            assertThrows(InvalidAttributeValueException.class, () -> connector.updateDelta(ObjectClass.ACCOUNT,
                    new Uid("u-1"), deltas(AttributeDeltaBuilder.build(readOnly, List.of("evil"))), null));
        }
        assertTrue(server.requests().isEmpty(), "a refused delta reached the wire");
        connector.dispose();
    }

    // ---- delete and the undeclared verbs --------------------------------------------

    public void deleteSendsTheDeclaredDeleteEndpoint() {
        server.stub("DELETE", "/scim/v2/Users/u-1", 204, "");
        UniversalRestConnector connector = connector();
        connector.delete(ObjectClass.ACCOUNT, new Uid("u-1"), null);
        assertEquals(server.lastRequest().method(), "DELETE");
        assertEquals(server.lastRequest().uri(), "/scim/v2/Users/u-1");
        connector.dispose();
    }

    public void deleteOfAnUnknownUidSurfaces() {
        server.stub("DELETE", "/scim/v2/Users/u-404", 404, "{\"status\":\"404\",\"detail\":\"no such user\"}");
        UniversalRestConnector connector = connector();
        assertThrows(UnknownUidException.class,
                () -> connector.delete(ObjectClass.ACCOUNT, new Uid("u-404"), null));
        connector.dispose();
    }

    public void aVerbTheFileDoesNotListIsRefusedBeforeTheWire() {
        UniversalRestConnector connector = connector();
        try {
            connector.create(ObjectClass.GROUP, Set.of(AttributeBuilder.build(Name.NAME, "new-team")), null);
            throw new AssertionError("an undeclared create was not refused");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("does not support create"), e.getMessage());
        }
        assertThrows(UnsupportedOperationException.class,
                () -> connector.delete(ObjectClass.GROUP, new Uid("r-1"), null));
        assertTrue(server.requests().isEmpty(), "a refused lifecycle verb reached the wire");
        connector.dispose();
    }

    public void aRefusedVerbCarriesTheHintTheFileWroteForIt() {
        // The refusal ends with the class's operationHints sentence.
        UniversalRestConnector connector = connector();
        try {
            connector.create(ObjectClass.GROUP, Set.of(AttributeBuilder.build(Name.NAME, "new-team")), null);
            throw new AssertionError("an undeclared create was not refused");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("Roles are born in Example Server"), e.getMessage());
        }
        try {
            connector.delete(ObjectClass.GROUP, new Uid("r-1"), null);
            throw new AssertionError("an undeclared delete was not refused");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("Roles are removed in Example Server"), e.getMessage());
        }
        connector.dispose();
    }

    public void aRefusedRenameCarriesTheHintTheFileWroteForIt() {
        server.stub("GET", "/scim/v2/Groups/r-1", 200,
                "{\"id\":\"r-1\",\"displayName\":\"dev\",\"members\":[]}");
        UniversalRestConnector connector = connector();
        try {
            connector.update(ObjectClass.GROUP, new Uid("r-1"),
                    Set.of(AttributeBuilder.build(Name.NAME, "renamed")), null);
            throw new AssertionError("a rename against a read-only name was not refused");
        } catch (InvalidAttributeValueException e) {
            assertTrue(e.getMessage().contains("A role is renamed in Example Server"), e.getMessage());
        }
        try {
            connector.updateDelta(ObjectClass.GROUP, new Uid("r-1"),
                    deltas(AttributeDeltaBuilder.build(Name.NAME, List.of("renamed"))), null);
            throw new AssertionError("a rename delta against a read-only name was not refused");
        } catch (InvalidAttributeValueException e) {
            assertTrue(e.getMessage().contains("A role is renamed in Example Server"), e.getMessage());
        }
        connector.dispose();
    }

    // ---- errors ---------------------------------------------------------------------

    public void aConflictSurfacesAsAlreadyExists() {
        server.stub("POST", "/scim/v2/Users", 409, "{\"status\":\"409\",\"scimType\":\"uniqueness\","
                + "\"detail\":\"userName already exists\"}");
        UniversalRestConnector connector = connector();
        assertThrows(AlreadyExistsException.class, () -> connector.create(ObjectClass.ACCOUNT,
                Set.of(AttributeBuilder.build(Name.NAME, "dup")), null));
        connector.dispose();
    }

    public void aVocabularyViolationSurfacesWithTheServerDetail() {
        server.stub("POST", "/scim/v2/Users", 400, "{\"status\":\"400\",\"scimType\":\"invalidValue\","
                + "\"detail\":\"userType \\\"robot\\\" is outside the vocabulary\"}");
        UniversalRestConnector connector = connector();
        try {
            connector.create(ObjectClass.ACCOUNT, Set.of(AttributeBuilder.build(Name.NAME, "x"),
                    AttributeBuilder.build("userType", "robot")), null);
            throw new AssertionError("expected InvalidAttributeValueException");
        } catch (InvalidAttributeValueException e) {
            assertTrue(e.getMessage().contains("invalidValue"), e.getMessage());
            assertTrue(e.getMessage().contains("outside the vocabulary"), e.getMessage());
        }
        connector.dispose();
    }

    public void aMutabilityRefusalSurfacesAsPermissionDenied() {
        server.stub("PATCH", "/scim/v2/Users/u-1", 400, "{\"status\":\"400\",\"scimType\":\"mutability\","
                + "\"detail\":\"the lock block is read-only\"}");
        UniversalRestConnector connector = connector();
        assertThrows(PermissionDeniedException.class, () -> connector.updateDelta(ObjectClass.ACCOUNT,
                new Uid("u-1"), deltas(AttributeDeltaBuilder.build("displayName", List.of("x"))), null));
        connector.dispose();
    }

    // ---- helpers ---------------------------------------------------------------------

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

    private List<String> schemasOnWire() {
        return schemas(json(server.lastRequest().body()));
    }

    private static List<String> schemas(JsonNode document) {
        List<String> urns = new java.util.ArrayList<>();
        document.path("schemas").forEach(urn -> urns.add(urn.asText()));
        return urns;
    }

    private static Set<AttributeDelta> deltas(AttributeDelta... items) {
        return Set.of(items);
    }


    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON: " + raw, e);
        }
    }
}
