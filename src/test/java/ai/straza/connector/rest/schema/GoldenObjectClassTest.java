package ai.straza.connector.rest.schema;

import java.util.ArrayList;
import java.util.List;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.UniversalRestConnector;
import ai.straza.connector.rest.support.SchemaRender;
import ai.straza.connector.rest.support.TestSchemas;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.objects.Schema;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * Compares each object class with its golden capture under
 * {@code src/test/resources/golden/}. midPoint caches the schema and resource XML
 * names these attributes, so a changed name, type or flag breaks a deployment.
 * The reserved classes are checked from both the test schema and the shipped sample.
 */
@Test(groups = "unit")
public class GoldenObjectClassTest {

    /** The two read-only group attributes checked on top of the {@code group} capture. */
    private static final List<String> NEW_GROUP_ATTRIBUTES = List.of(
            "administers type=java.lang.String flags=MULTIVALUED,NOT_CREATABLE,NOT_UPDATEABLE",
            "server type=java.lang.String flags=NOT_CREATABLE,NOT_UPDATEABLE");

    public void fileDrivenAccountMatchesTheGoldenCapture() {
        assertEquals(render(TestSchemas.scimClasses(), "__ACCOUNT__"), SchemaRender.golden("account"));
    }

    public void fileDrivenGroupMatchesTheGoldenCapturePlusServerAndAdministers() {
        List<String> expected = new ArrayList<>(SchemaRender.golden("group"));
        expected.addAll(NEW_GROUP_ATTRIBUTES);
        assertEquals(render(TestSchemas.scimClasses(), "__GROUP__"), sortedBody(expected));
    }

    public void theShippedSampleAccountMatchesTheGoldenCapture() {
        assertEquals(render(TestSchemas.sample(), "__ACCOUNT__"), SchemaRender.golden("account"));
    }

    public void theShippedSampleGroupMatchesTheGoldenCapturePlusServerAndAdministers() {
        List<String> expected = new ArrayList<>(SchemaRender.golden("group"));
        expected.addAll(NEW_GROUP_ATTRIBUTES);
        assertEquals(render(TestSchemas.sample(), "__GROUP__"), sortedBody(expected));
    }

    public void appMatchesTheGoldenCapture() {
        assertEquals(render(TestSchemas.sample(), "app"), SchemaRender.golden("app"));
    }

    public void toolMatchesTheGoldenCapture() {
        assertEquals(render(TestSchemas.sample(), "tool"), SchemaRender.golden("tool"));
    }

    public void userMatchesTheGoldenCapture() {
        assertEquals(render(TestSchemas.sample(), "user"), SchemaRender.golden("user"));
    }

    /** Keeps the header line first and sorts the attribute lines, as SchemaRender does. */
    private static List<String> sortedBody(List<String> lines) {
        List<String> result = new ArrayList<>();
        result.add(lines.get(0));
        result.addAll(lines.subList(1, lines.size()).stream().sorted().toList());
        return result;
    }

    private static List<String> render(String schemaFilePath, String type) {
        UniversalRestConfiguration configuration = new UniversalRestConfiguration();
        configuration.setBaseUrl("http://127.0.0.1:1");
        configuration.setApiToken(new GuardedString("t".toCharArray()));
        configuration.setSchemaFilePath(schemaFilePath);
        UniversalRestConnector connector = new UniversalRestConnector();
        connector.init(configuration);
        try {
            Schema schema = connector.schema();
            return SchemaRender.render(schema, type);
        } finally {
            connector.dispose();
        }
    }
}
