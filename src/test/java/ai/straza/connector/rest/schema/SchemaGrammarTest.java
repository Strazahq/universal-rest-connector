package ai.straza.connector.rest.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ai.straza.connector.rest.dialect.JsonDialect;
import ai.straza.connector.rest.dialect.ScimDialect;

import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The schema file grammar: its keys, their defaults and the load-time refusals. */
@Test(groups = "unit")
public class SchemaGrammarTest {

    // ---- defaults ---------------------------------------------------------------

    public void aClassThatDeclaresNothingIsReadOnlyJson() {
        SchemaType widget = parse("{ \"objects\": [ { \"objectClass\": \"widget\", "
                + "\"listEndpoint\": \"/v1/widgets\", \"icfsUid\": \"id\", \"icfsName\": \"id\", "
                + "\"attributes\": { \"label\": {} } } ] }").get("widget");

        assertEquals(widget.getDialect().name(), JsonDialect.NAME);
        assertEquals(widget.getOperations(), java.util.Set.of(Operation.SEARCH));
        assertEquals(widget.getBasePath(), "");
        assertNull(widget.getEnablePath());
        assertNull(widget.getCoreSchema());
        assertTrue(widget.getPushableFilters().isEmpty());

        SchemaTypeAttribute label = widget.getAttribute("label");
        assertFalse(label.isCreatable(), "creatable defaults to false");
        assertFalse(label.isUpdateable(), "updateable defaults to false");
        assertFalse(label.isRequired());
        assertEquals(label.getClearVia(), SchemaTypeAttribute.ClearVia.OMIT);
        assertEquals(label.getPath().field(), "label", "an undeclared path is the attribute name");
    }

    public void aSyncBlockAddsTheSyncOperation() {
        SchemaType widget = parse("{ \"objects\": [ { \"objectClass\": \"widget\", "
                + "\"listEndpoint\": \"/v1/widgets\", \"icfsUid\": \"id\", \"icfsName\": \"id\", "
                + "\"sync\": { \"requestTypes\": [\"widget\"] } } ] }").get("widget");
        assertEquals(widget.getOperations(), java.util.Set.of(Operation.SEARCH, Operation.SYNC));
    }

    // ---- the full scim shape ----------------------------------------------------

    public void aScimClassCarriesEveryTargetFact() {
        SchemaType account = parse(scimAccount()).get("__ACCOUNT__");

        assertEquals(account.getDialect().name(), ScimDialect.NAME);
        assertEquals(account.getBasePath(), "/scim/v2");
        assertEquals(account.getEndpoints().getCreate(), "/Users");
        assertEquals(account.getEndpoints().getById(), "/Users/{id}");
        assertEquals(account.getEndpoints().getDelete(), "/Users/{id}");
        assertEquals(account.getCoreSchema(), "urn:ietf:params:scim:schemas:core:2.0:User");
        assertEquals(account.getEnablePath().field(), "active");
        assertEquals(account.getPushableFilters(), Map.of("__NAME__", "userName", "externalId", "externalId"));
        assertEquals(account.getPageSize(), 50);
        assertTrue(account.supports(Operation.CREATE));
        assertTrue(account.supports(Operation.UPDATE_DELTA));

        SchemaTypeAttribute email = account.getAttribute("email");
        assertEquals(email.getPath().form(), AttributePath.Form.FILTERED_ARRAY);
        assertEquals(email.getPath().field(), "emails");
        assertEquals(email.getPath().selectorField(), "primary");
        assertEquals(email.getPath().selectorValue(), "true");
        assertEquals(email.getPath().elementField(), "value");

        SchemaTypeAttribute groups = account.getAttribute("groups");
        assertEquals(groups.getPath().form(), AttributePath.Form.ARRAY);
        assertFalse(groups.isWritable());

        SchemaTypeAttribute sponsor = account.getAttribute("sponsor");
        assertEquals(sponsor.getPath().namespace(), "urn:example:2.0:User");
        assertEquals(sponsor.getPath().field(), "sponsor");
        assertEquals(sponsor.getClearVia(), SchemaTypeAttribute.ClearVia.PATCH_ONLY);

        assertEquals(account.getNameAttribute().isRequired(), true);
        assertEquals(account.getNamePath().field(), "userName");

        ConditionalSchema conditional = account.getCreateOnlySchemas().get(0);
        assertEquals(conditional.getUrn(), "urn:example:agent");
        assertEquals(conditional.getConfigGate(), "emitAgenticUrn");
        assertTrue(conditional.matches("AGENT"), "caseInsensitive was declared");
        assertFalse(conditional.matches("human"));
        assertFalse(conditional.matches(null), "an absent value never earns a create-only mark");
    }

    public void theTargetBlockCarriesTheOperatorWording() {
        TargetInfo target = handler("{ \"target\": { \"name\": \"Example Server\", "
                + "\"credentialHint\": \"Mint a token with the admin tool.\" }, "
                + "\"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                + "\"icfsUid\": \"id\", \"icfsName\": \"id\" } ] }").getTargetInfo();
        assertEquals(target.getName(), "Example Server");
        assertEquals(target.getCredentialHint(), "Mint a token with the admin tool.");
    }

    public void aFileWithoutATargetBlockStillSpeaksPlainly() {
        TargetInfo target = handler("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                + "\"icfsUid\": \"id\", \"icfsName\": \"id\" } ] }").getTargetInfo();
        assertEquals(target.getName(), TargetInfo.fallback().getName());
        assertTrue(target.getCredentialHint().contains("token"));
    }

    // ---- refusals ---------------------------------------------------------------

    public void anUnknownObjectKeyIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", \"listEndpiont\": \"/typo\" } ] }",
                "Unknown key 'listEndpiont'", "listEndpoint");
    }

    public void anUnknownAttributeKeyIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"attributes\": { \"label\": { \"updatable\": true } } } ] }",
                "Unknown key 'updatable'", "updateable");
    }

    public void aCommentKeyIsIgnored() {
        SchemaType widget = parse("{ \"//\": \"a note\", \"objects\": [ { \"objectClass\": \"widget\", "
                + "\"//why\": \"another note\", \"listEndpoint\": \"/w\", \"icfsUid\": \"id\", "
                + "\"icfsName\": \"id\", \"attributes\": { \"label\": { \"//\": \"note\" } } } ] }").get("widget");
        assertEquals(widget.getAttributes().size(), 1);
    }

    public void anUnknownDialectIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"dialect\": \"soap\", "
                        + "\"listEndpoint\": \"/w\", \"icfsUid\": \"id\", \"icfsName\": \"id\" } ] }",
                "unknown dialect 'soap'", "scim");
    }

    public void pushableFiltersOnTheJsonDialectAreRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"filters\": { \"pushable\": { \"__NAME__\": \"name\" } } } ] }",
                "no server-side filter grammar", "narrows locally");
    }

    public void aPushableFilterOnAnAttributeTheClassDoesNotDeclareIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", \"dialect\": \"scim\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                        + "\"endpoints\": { \"list\": \"/Users\" }, "
                        + "\"filters\": { \"pushable\": { \"externalId\": \"externalId\" } } } ] }",
                "pushable filter 'externalId'", "__NAME__", "declare the attribute");
    }

    public void theReservedUidAndNameAreAlwaysPushable() {
        SchemaType account = parse("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", "
                + "\"dialect\": \"scim\", \"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                + "\"endpoints\": { \"list\": \"/Users\" }, "
                + "\"filters\": { \"pushable\": { \"__NAME__\": \"userName\", \"__UID__\": \"id\" } } } ] }")
                .get("__ACCOUNT__");
        assertEquals(account.getPushableFilters(), Map.of("__NAME__", "userName", "__UID__", "id"));
    }

    public void aPushableFilterWithoutAWireAttributeIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", \"dialect\": \"scim\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                        + "\"endpoints\": { \"list\": \"/Users\" }, "
                        + "\"filters\": { \"pushable\": { \"__NAME__\": \"\" } } } ] }",
                "pushable filter '__NAME__'", "wire attribute name");
    }

    public void aWriteOperationOnTheJsonDialectIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"operations\": [\"search\", \"create\"], "
                        + "\"endpoints\": { \"list\": \"/w\", \"create\": \"/w\" } } ] }",
                "no write document form", "create");
    }

    public void anOperationWithoutItsEndpointIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", \"dialect\": \"scim\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                        + "\"operations\": [\"search\", \"delete\"], "
                        + "\"endpoints\": { \"list\": \"/Users\" } } ] }",
                "declares no \"endpoints.delete\" path", "drop the operation");
    }

    public void anEndpointWithoutItsOperationIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", \"dialect\": \"scim\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                        + "\"endpoints\": { \"list\": \"/Users\", \"delete\": \"/Users/{id}\" } } ] }",
                "can never be used", "Add the operation");
    }

    public void aSyncBlockWithoutTheSyncOperationIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", \"operations\": [\"search\"], "
                        + "\"sync\": { \"requestTypes\": [\"widget\"] } } ] }",
                "declares a 'sync' routing block", "Add \"sync\" to 'operations'");
    }

    public void anUnknownNamespaceAliasIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"attributes\": { \"label\": { \"path\": \"ext:label\" } } } ] }",
                "no namespace alias 'ext'", "'namespaces' block");
    }

    public void anUnparseablePathIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"attributes\": { \"label\": { \"path\": \"emails[0]\" } } } ] }",
                "not a form this connector understands", "members[].value");
    }

    public void aPathOnTheNameAttributeIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"attributes\": { \"__NAME__\": { \"path\": \"displayName\" } } } ] }",
                "declares a 'path' on '__NAME__'", "'icfsName'");
    }

    public void aConditionalSchemaOnAnUndeclaredAttributeIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", \"dialect\": \"scim\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                        + "\"operations\": [\"search\", \"create\"], "
                        + "\"endpoints\": { \"list\": \"/Users\", \"create\": \"/Users\" }, "
                        + "\"createOnlySchemas\": [ { \"urn\": \"urn:x\", "
                        + "\"when\": { \"attribute\": \"userType\", \"in\": [\"agent\"] } } ] } ] }",
                "which the class does not declare", "point 'when.attribute'");
    }

    public void aDuplicateObjectClassIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\" }, "
                        + "{ \"objectClass\": \"widget\", \"listEndpoint\": \"/w2\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\" } ] }",
                "declared twice", "one entry");
    }

    public void aCreateClassSaysNothingAboutWhereTheUidComesFrom() {
        // A new object's uid always comes from the create response; there is no key for it.
        SchemaType account = parse("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", "
                + "\"dialect\": \"scim\", \"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                + "\"operations\": [\"search\", \"create\"], "
                + "\"endpoints\": { \"list\": \"/Users\", \"create\": \"/Users\" } } ] }").get("__ACCOUNT__");
        assertTrue(account.supports(Operation.CREATE));
    }

    public void anAdoptReturnedUidKeyIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", \"dialect\": \"scim\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"userName\", \"adoptReturnedUid\": true, "
                        + "\"operations\": [\"search\", \"create\"], "
                        + "\"endpoints\": { \"list\": \"/Users\", \"create\": \"/Users\" } } ] }",
                "Unknown key 'adoptReturnedUid'");
    }

    public void anUpdateWithoutAByIdPathIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", \"dialect\": \"scim\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                        + "\"operations\": [\"search\", \"update\"], "
                        + "\"endpoints\": { \"list\": \"/Users\", \"replace\": \"/Users/{id}\" } } ] }",
                "declares no \"endpoints.byId\" path", "reads the current resource before it writes");
    }

    public void anOperationHintForAnUnknownVerbIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"operationHints\": { \"destroy\": \"Ask the owner.\" } } ] }",
                "operationHints entry for 'destroy'", "rename");
    }

    public void anEmptyOperationHintIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"operationHints\": { \"create\": \"\" } } ] }",
                "operationHints entry for 'create'", "one sentence");
    }

    public void anOperationHintIsOneSentencePerVerb() {
        SchemaType widget = parse("{ \"objects\": [ { \"objectClass\": \"widget\", "
                + "\"listEndpoint\": \"/w\", \"icfsUid\": \"id\", \"icfsName\": \"id\", "
                + "\"operationHints\": { \"create\": \"Widgets are born elsewhere.\" } } ] }").get("widget");
        assertEquals(widget.getOperationHint("create"), "Widgets are born elsewhere.");
        assertNull(widget.getOperationHint("delete"), "a verb with no hint has none");
    }

    public void anUnknownClearViaIsRefused() {
        assertRefused("{ \"objects\": [ { \"objectClass\": \"widget\", \"listEndpoint\": \"/w\", "
                        + "\"icfsUid\": \"id\", \"icfsName\": \"id\", "
                        + "\"attributes\": { \"label\": { \"clearVia\": \"never\" } } } ] }",
                "\"clearVia\": \"never\"", "patchOnly");
    }

    // ---- helpers ----------------------------------------------------------------

    private static String scimAccount() {
        return "{ \"objects\": [ { \"objectClass\": \"__ACCOUNT__\", \"dialect\": \"scim\", "
                + "\"basePath\": \"/scim/v2\", "
                + "\"namespaces\": { \"ext\": \"urn:example:2.0:User\" }, "
                + "\"coreSchema\": \"urn:ietf:params:scim:schemas:core:2.0:User\", "
                + "\"icfsUid\": \"id\", \"icfsName\": \"userName\", "
                + "\"operations\": [\"search\", \"create\", \"update\", \"updateDelta\", \"delete\"], "
                + "\"endpoints\": { \"list\": \"/Users\", \"byId\": \"/Users/{id}\", \"create\": \"/Users\", "
                + "\"replace\": \"/Users/{id}\", \"patch\": \"/Users/{id}\", \"delete\": \"/Users/{id}\" }, "
                + "\"enable\": \"active\", "
                + "\"paging\": { \"pageSize\": 50 }, "
                + "\"filters\": { \"pushable\": { \"__NAME__\": \"userName\", \"externalId\": \"externalId\" } }, "
                + "\"createOnlySchemas\": [ { \"urn\": \"urn:example:agent\", "
                + "\"when\": { \"attribute\": \"userType\", \"in\": [\"agent\", \"service\"], "
                + "\"caseInsensitive\": true }, \"configGate\": \"emitAgenticUrn\" } ], "
                + "\"attributes\": { "
                + "\"__NAME__\": { \"required\": true, \"creatable\": true, \"updateable\": true }, "
                + "\"userType\": { \"creatable\": true, \"updateable\": true, \"clearVia\": \"patchOnly\" }, "
                + "\"externalId\": { \"creatable\": true, \"updateable\": true }, "
                + "\"email\": { \"path\": \"emails[primary=true].value\", \"creatable\": true, "
                + "\"updateable\": true }, "
                + "\"sponsor\": { \"path\": \"ext:sponsor\", \"creatable\": true, \"updateable\": true, "
                + "\"clearVia\": \"patchOnly\" }, "
                + "\"groups\": { \"path\": \"groups[].value\", \"multivalued\": true } } } ] }";
    }

    private static Map<String, SchemaType> parse(String json) {
        return handler(json).getSchemaTypes();
    }

    private static UniversalSchemaHandler handler(String json) {
        Path file = write(json);
        try {
            return new UniversalSchemaHandler(file.toString());
        } finally {
            delete(file);
        }
    }

    private static void assertRefused(String json, String... expectedFragments) {
        Path file = write(json);
        try {
            new UniversalSchemaHandler(file.toString());
            throw new AssertionError("the schema load was not refused");
        } catch (ConfigurationException e) {
            for (String fragment : List.of(expectedFragments)) {
                assertTrue(e.getMessage().contains(fragment),
                        "message must contain '" + fragment + "' but was: " + e.getMessage());
            }
        } finally {
            delete(file);
        }
    }

    private static Path write(String json) {
        try {
            Path file = Files.createTempFile("grammar-schema", ".json");
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
