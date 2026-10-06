package ai.straza.connector.rest.objects;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.straza.connector.rest.UniversalRestConnector;
import ai.straza.connector.rest.schema.AssociationType;
import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.schema.SchemaTypeAttribute;

import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Test(groups = "unit")
public class UniversalObjectsHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaType appSchema() {
        return new SchemaType("app", "/v1/admin/apps", "id", "name", List.of(
                new SchemaTypeAttribute("version", "String", false, true),
                new SchemaTypeAttribute("tools", "String", true, true)));
    }

    public void buildObjectClassDefinesNameAndAttributes() {
        SchemaBuilder schemaBuilder = new SchemaBuilder(UniversalRestConnector.class);
        UniversalObjectsHandler.buildObjectClass(schemaBuilder, appSchema());
        Schema schema = schemaBuilder.build();

        ObjectClassInfo app = schema.getObjectClassInfo().stream()
                .filter(oc -> oc.getType().equals("app"))
                .findFirst().orElseThrow();

        assertNotNull(info(app, Name.NAME));
        AttributeInfo tools = info(app, "tools");
        assertNotNull(tools);
        assertTrue(tools.isMultiValued());
        assertTrue(!info(app, "version").isMultiValued());
        // read-only: not creatable/updateable
        assertTrue(!info(app, "version").isCreateable());
        assertTrue(!info(app, "version").isUpdateable());
    }

    public void toConnectorObjectMapsUidNameAndAttributes() throws Exception {
        JsonNode json = MAPPER.readTree("{\"id\":\"a1\",\"name\":\"github\",\"version\":\"1.2\","
                + "\"tools\":[\"create_issue\",\"list_repos\"],\"status\":\"running\"}");
        ConnectorObject object = ResourceMapper.toConnectorObject(appSchema(), json);

        assertEquals(object.getUid().getUidValue(), "a1");
        assertEquals(object.getName().getNameValue(), "github");
        assertEquals(object.getAttributeByName("version").getValue().get(0), "1.2");
        assertEquals(object.getAttributeByName("tools").getValue().size(), 2);
        // status is not declared, so it is not mapped
        assertTrue(object.getAttributeByName("status") == null);
    }

    public void buildObjectClassIncludesMultivaluedAssociation() {
        SchemaType role = new SchemaType("role", "/v1/admin/roles", "id", "name",
                List.of(new SchemaTypeAttribute("kind", "String", false, true)),
                List.of(new AssociationType("apps", "/v1/admin/bindings", "name", "role", "app", Map.of())));
        SchemaBuilder schemaBuilder = new SchemaBuilder(UniversalRestConnector.class);
        UniversalObjectsHandler.buildObjectClass(schemaBuilder, role);

        ObjectClassInfo roleInfo = schemaBuilder.build().getObjectClassInfo().stream()
                .filter(oc -> oc.getType().equals("role")).findFirst().orElseThrow();
        AttributeInfo apps = info(roleInfo, "apps");
        assertNotNull(apps);
        assertTrue(apps.isMultiValued());
    }

    public void resolveAssociationsCorrelatesByField() throws Exception {
        SchemaType role = new SchemaType("role", "/v1/admin/roles", "id", "name", List.of(),
                List.of(new AssociationType("apps", "/v1/admin/bindings", "name", "role", "app", Map.of())));
        JsonNode dev = MAPPER.readTree("{\"id\":\"r1\",\"name\":\"dev\"}");
        Map<String, List<JsonNode>> endpointRows = Map.of("/v1/admin/bindings", List.of(
                MAPPER.readTree("{\"role\":\"dev\",\"app\":\"github\"}"),
                MAPPER.readTree("{\"role\":\"ops\",\"app\":\"pagerduty\"}")));

        Map<String, List<Object>> resolved = UniversalObjectsHandler.resolveAssociations(role, dev, endpointRows);
        assertEquals(resolved.get("apps").size(), 1);
        assertEquals(resolved.get("apps").get(0), "github");
    }

    private static AttributeInfo info(ObjectClassInfo objectClass, String name) {
        return objectClass.getAttributeInfo().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst().orElse(null);
    }
}
