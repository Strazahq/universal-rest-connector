package ai.straza.connector.rest.objects;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.schema.UniversalSchemaHandler;
import ai.straza.connector.rest.support.TestSchemas;

import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Clearing a namespaced attribute never leaves an empty namespace block in a replace document. */
@Test(groups = "unit")
public class ReplaceDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXT = "urn:example:2.0:WidgetExtension";

    public void clearingANamespacedAttributeNeverCreatesTheBlock() {
        ObjectNode document = replace("{\"id\":\"w-1\",\"name\":\"widget\"}",
                AttributeBuilder.build("label"));
        assertFalse(document.has(EXT), "an empty namespace block rode the document: " + document);
    }

    public void clearingTheLastFieldOfANamespaceDropsTheBlock() {
        ObjectNode document = replace("{\"id\":\"w-1\",\"name\":\"widget\",\"" + EXT
                + "\":{\"label\":\"old\"}}", AttributeBuilder.build("label"));
        assertFalse(document.has(EXT), "an emptied namespace block rode the document: " + document);
    }

    public void clearingOneFieldLeavesTheRestOfTheBlock() {
        ObjectNode document = replace("{\"id\":\"w-1\",\"name\":\"widget\",\"" + EXT
                + "\":{\"label\":\"old\",\"note\":\"keep\"}}", AttributeBuilder.build("label"));
        assertFalse(document.path(EXT).has("label"), "the cleared field survived: " + document);
        assertEquals(document.path(EXT).path("note").asText(), "keep");
        assertTrue(schemas(document).contains(EXT), "a block that carries something is declared");
    }

    // ---- helpers -------------------------------------------------------------------

    private static ObjectNode replace(String current, Attribute... replacements) {
        SchemaType widget = new UniversalSchemaHandler(TestSchemas.copy("writer-edges"))
                .getSchemaTypes().get("widget");
        return ResourceWriter.toReplaceDocument(widget, json(current), Set.of(replacements));
    }

    private static java.util.List<String> schemas(JsonNode document) {
        java.util.List<String> urns = new java.util.ArrayList<>();
        document.path("schemas").forEach(urn -> urns.add(urn.asText()));
        return urns;
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON: " + raw, e);
        }
    }
}
