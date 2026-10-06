package ai.straza.connector.rest.dialect;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.identityconnectors.framework.common.exceptions.ConnectorException;

/**
 * Plain REST over {@code application/json}: a list answers a bare array and a
 * top-level {@code error} field is a server error. No filter syntax, no paging
 * and no write documents.
 */
public final class JsonDialect implements Dialect {

    /** Shared stateless instance. */
    public static final JsonDialect INSTANCE = new JsonDialect();

    /** The name the schema file's {@code dialect} key uses. */
    public static final String NAME = "json";

    /** The media type sent as Accept and, for bodies, Content-Type. */
    public static final String MEDIA_TYPE = "application/json";

    private JsonDialect() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String mediaType() {
        return MEDIA_TYPE;
    }

    @Override
    public List<JsonNode> rows(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return List.of();
        }
        if (body.isArray()) {
            List<JsonNode> rows = new ArrayList<>(body.size());
            body.forEach(rows::add);
            return rows;
        }
        if (body.isObject() && body.has("error")) {
            throw new ConnectorException("Server returned an error: " + body.get("error").asText());
        }
        return List.of(body);
    }

    @Override
    public boolean pagesServerSide() {
        return false;
    }

    @Override
    public String pageQuery(int startIndex, int pageSize) {
        return null;
    }

    @Override
    public int totalResults(JsonNode body, int fallback) {
        return fallback;
    }

    @Override
    public boolean supportsPushedFilters() {
        return false;
    }

    @Override
    public String filterQuery(String wireAttribute, String value) {
        throw new UnsupportedOperationException("The json dialect has no filter grammar.");
    }

    @Override
    public boolean supportsWrites() {
        return false;
    }

    @Override
    public ObjectNode newResourceDocument(String coreSchema) {
        throw new UnsupportedOperationException("The json dialect has no write document form.");
    }

    @Override
    public void addSchema(ObjectNode document, String urn) {
        throw new UnsupportedOperationException("The json dialect has no write document form.");
    }

    @Override
    public ObjectNode newPatchDocument() {
        throw new UnsupportedOperationException("The json dialect has no delta document form.");
    }

    @Override
    public void addOperation(ObjectNode patch, String operation, String path, JsonNode value) {
        throw new UnsupportedOperationException("The json dialect has no delta document form.");
    }

    @Override
    public boolean isEmptyPatch(ObjectNode patch) {
        return true;
    }

    @Override
    public String valueFilterPath(String path, String elementField, String value) {
        throw new UnsupportedOperationException("The json dialect has no delta document form.");
    }

    @Override
    public String qualifiedPath(String namespace, String field) {
        return field;
    }

    @Override
    public String errorHint(JsonNode body) {
        return "";
    }

    @Override
    public String errorDetail(JsonNode body) {
        if (body == null) {
            return "";
        }
        return body.path("error").asText("");
    }
}
