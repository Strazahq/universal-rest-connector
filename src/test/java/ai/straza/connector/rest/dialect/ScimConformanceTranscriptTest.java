package ai.straza.connector.rest.dialect;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.schema.UniversalSchemaHandler;
import ai.straza.connector.rest.support.EmbeddedRestServer;
import ai.straza.connector.rest.support.EmbeddedRestServer.RecordedRequest;
import ai.straza.connector.rest.support.TestSchemas;
import ai.straza.connector.rest.transport.HttpClientManager;
import ai.straza.connector.rest.transport.ResourceClient;

import org.identityconnectors.common.security.GuardedString;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Replays the SCIM conformance transcripts, derived from the Straza SCIM profile's
 * conformance suite, through the client layer. The embedded server answers each
 * step with the transcript's expected response, so the test checks the request on
 * the wire and the client's parsing of the expected status and response subset.
 */
@Test(groups = "unit")
public class ScimConformanceTranscriptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "transcript-token";

    private EmbeddedRestServer server;
    private HttpClientManager httpClientManager;
    private ResourceClient client;
    private SchemaType account;

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws IOException {
        server = new EmbeddedRestServer();
        UniversalRestConfiguration configuration = new UniversalRestConfiguration();
        configuration.setBaseUrl(server.baseUrl());
        configuration.setApiToken(new GuardedString(TOKEN.toCharArray()));
        UniversalSchemaHandler schema = new UniversalSchemaHandler(TestSchemas.scimClasses());
        account = schema.getSchemaTypes().get("__ACCOUNT__");
        httpClientManager = new HttpClientManager(configuration);
        client = new ResourceClient(httpClientManager, configuration, schema.getTargetInfo());
    }

    public void theSchemaFileSuppliesTheBasePathAndMediaType() {
        assertEquals(account.getBasePath(), "/scim/v2");
        assertEquals(account.getDialect().mediaType(), ScimDialect.MEDIA_TYPE);
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @DataProvider
    public Object[][] transcripts() {
        return new Object[][] {
                {"agent-user"}, {"auth"}, {"errors"}, {"group-enrichment"}, {"groups-roles"},
                {"service-discovery"}, {"user-groups"}, {"user-lock"}, {"users-lifecycle"},
                {"user-title"}, {"user-typology"},
        };
    }

    @Test(dataProvider = "transcripts")
    public void replaysTranscriptVerbatim(String name) throws IOException {
        JsonNode transcript = load(name);
        assertEquals(transcript.path("name").asText(), name, "fixture name mismatch");
        Map<String, String> variables = new HashMap<>();
        int stepIndex = 0;
        for (JsonNode step : transcript.path("steps")) {
            stepIndex++;
            replayStep(name, stepIndex, step, variables);
        }
    }

    private void replayStep(String transcript, int stepIndex, JsonNode step, Map<String, String> variables) {
        String context = transcript + " step " + stepIndex;
        JsonNode request = step.path("request");
        String method = request.path("method").asText();
        String path = substitute(request.path("path").asText(), variables);
        JsonNode body = request.has("body") ? substituteTree(request.get("body"), variables) : null;
        boolean noAuth = request.path("noAuth").asBoolean(false);
        String headerAuthorization = request.path("headers").path("Authorization").asText(null);

        JsonNode expect = step.path("expect");
        int expectedStatus = expect.path("status").asInt();
        JsonNode subset = expect.has("subset") ? substituteTree(expect.get("subset"), variables) : null;

        // Canned response: the expected subset plus a placeholder for each captured field it lacks.
        ObjectNode canned = subset != null && subset.isObject()
                ? (ObjectNode) subset.deepCopy() : MAPPER.createObjectNode();
        JsonNode capture = step.path("capture");
        capture.properties().forEach(entry -> {
            String field = entry.getValue().asText();
            if (valueAt(canned, field).isMissingNode()) {
                ensurePath(canned, field, "fx-" + entry.getKey() + "-" + stepIndex);
            }
        });
        server.enqueue(expectedStatus, canned.toString());

        GuardedString token = tokenFor(noAuth, headerAuthorization);
        ResourceClient.Response response = client.send(method, path, body,
                account.getDialect().mediaType(), token);

        // The client parses the expected status and subset.
        assertEquals(response.status(), expectedStatus, context + ": status");
        if (subset != null) {
            assertTrue(subsetMatches(subset, response.body()),
                    context + ": subset mismatch\nexpected subset: " + subset + "\nactual: " + response.body());
        }

        // The request on the wire matches the transcript step.
        RecordedRequest recorded = server.lastRequest();
        assertEquals(recorded.method(), method, context + ": method");
        assertEquals(URLDecoder.decode(recorded.uri(), StandardCharsets.UTF_8), path, context + ": path");
        if (body != null) {
            assertEquals(parse(recorded.body()), body, context + ": body");
            assertTrue(recorded.contentType().startsWith(account.getDialect().mediaType()),
                    context + ": content type " + recorded.contentType());
        } else {
            assertTrue(recorded.body().isEmpty(), context + ": unexpected body " + recorded.body());
        }
        if (headerAuthorization != null) {
            assertEquals(recorded.authorization(), headerAuthorization, context + ": auth override");
        } else if (noAuth) {
            assertNull(recorded.authorization(), context + ": noAuth step sent Authorization");
        } else {
            assertEquals(recorded.authorization(), "Bearer " + TOKEN, context + ": bearer");
        }

        // Bind captured fields for later steps.
        capture.properties().forEach(entry ->
                variables.put(entry.getKey(), valueAt(response.body(), entry.getValue().asText()).asText()));
    }

    private static GuardedString tokenFor(boolean noAuth, String headerAuthorization) {
        if (headerAuthorization != null && headerAuthorization.startsWith("Bearer ")) {
            return new GuardedString(headerAuthorization.substring("Bearer ".length()).toCharArray());
        }
        return noAuth ? null : new GuardedString(TOKEN.toCharArray());
    }

    // ---- transcript mechanics ---------------------------------------------------

    /**
     * Resolves a capture path: a bare name reads top-level, a dotted path like
     * {@code Resources.0.id} walks objects and array indices.
     */
    private static JsonNode valueAt(JsonNode node, String path) {
        JsonNode current = node;
        for (String segment : path.split("\\.")) {
            current = segment.matches("\\d+")
                    ? current.path(Integer.parseInt(segment))
                    : current.path(segment);
        }
        return current;
    }

    /**
     * Creates the nested structure a dotted capture path needs in the canned
     * response, keeping what the subset already has. Numeric segments are array
     * indices.
     */
    private static void ensurePath(ObjectNode root, String path, String value) {
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            boolean nextIsIndex = segments[i + 1].matches("\\d+");
            if (segments[i].matches("\\d+")) {
                ArrayNode array = (ArrayNode) current;
                int index = Integer.parseInt(segments[i]);
                while (array.size() <= index) {
                    array.add(MAPPER.createObjectNode());
                }
                if (array.get(index).isMissingNode() || array.get(index).isNull()) {
                    array.set(index, nextIsIndex ? MAPPER.createArrayNode() : MAPPER.createObjectNode());
                }
                current = array.get(index);
            } else {
                ObjectNode object = (ObjectNode) current;
                if (!object.has(segments[i]) || object.get(segments[i]).isNull()) {
                    object.set(segments[i], nextIsIndex ? MAPPER.createArrayNode() : MAPPER.createObjectNode());
                }
                current = object.get(segments[i]);
            }
        }
        String last = segments[segments.length - 1];
        if (last.matches("\\d+")) {
            ArrayNode array = (ArrayNode) current;
            int index = Integer.parseInt(last);
            while (array.size() <= index) {
                array.add(MAPPER.createObjectNode());
            }
            array.set(index, TextNode.valueOf(value));
        } else {
            ((ObjectNode) current).put(last, value);
        }
    }

    /** {@code {var}} substitution in one string. */
    private static String substitute(String value, Map<String, String> variables) {
        String result = value;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            result = result.replace("{" + variable.getKey() + "}", variable.getValue());
        }
        return result;
    }

    /** {@code {var}} substitution over every string value of a JSON tree. */
    private static JsonNode substituteTree(JsonNode node, Map<String, String> variables) {
        if (node.isTextual()) {
            return TextNode.valueOf(substitute(node.asText(), variables));
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            node.properties().forEach(entry ->
                    copy.set(entry.getKey(), substituteTree(entry.getValue(), variables)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            node.forEach(element -> copy.add(substituteTree(element, variables)));
            return copy;
        }
        return node;
    }

    /**
     * Recursive subset match: objects must contain the listed keys, each expected
     * array element must match some actual element, and scalars must be equal.
     */
    private static boolean subsetMatches(JsonNode expected, JsonNode actual) {
        if (expected.isObject()) {
            if (!actual.isObject()) {
                return false;
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!actual.has(entry.getKey()) || !subsetMatches(entry.getValue(), actual.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            if (!actual.isArray()) {
                return false;
            }
            for (JsonNode expectedElement : expected) {
                boolean found = false;
                for (JsonNode actualElement : actual) {
                    if (subsetMatches(expectedElement, actualElement)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
    }

    private static JsonNode load(String name) throws IOException {
        String resource = "/scim-conformance/" + name + ".json";
        try (InputStream in = ScimConformanceTranscriptTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing fixture " + resource);
            }
            return MAPPER.readTree(in);
        }
    }

    private static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("recorded body is not JSON: " + raw, e);
        }
    }
}
