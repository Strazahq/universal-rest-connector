package ai.straza.connector.rest.dialect;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * SCIM 2.0 (RFC 7643, RFC 7644): {@code schemas} envelope, PatchOp documents,
 * {@code eq} filters, {@code startIndex}/{@code count} paging and
 * {@code scimType}/{@code detail} errors.
 */
public final class ScimDialect implements Dialect {

    /** Shared stateless instance. */
    public static final ScimDialect INSTANCE = new ScimDialect();

    /** The name the schema file's {@code dialect} key uses. */
    public static final String NAME = "scim";

    /** The media type sent as Accept and, for bodies, Content-Type (RFC 7644 section 3.1). */
    public static final String MEDIA_TYPE = "application/scim+json";

    /** The PatchOp message URN (RFC 7644 section 3.5.2). */
    public static final String PATCH_OP_URN = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScimDialect() {
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
        List<JsonNode> rows = new ArrayList<>();
        if (body == null) {
            return rows;
        }
        JsonNode resources = body.path("Resources");
        if (resources.isArray()) {
            resources.forEach(rows::add);
        }
        return rows;
    }

    @Override
    public boolean pagesServerSide() {
        return true;
    }

    @Override
    public String pageQuery(int startIndex, int pageSize) {
        return "startIndex=" + startIndex + "&count=" + pageSize;
    }

    @Override
    public int totalResults(JsonNode body, int fallback) {
        return body == null ? fallback : body.path("totalResults").asInt(fallback);
    }

    @Override
    public boolean supportsPushedFilters() {
        return true;
    }

    /** {@code attr eq "value"}, with backslash and quote escaped. */
    @Override
    public String filterQuery(String wireAttribute, String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "filter=" + wireAttribute + " eq \"" + escaped + "\"";
    }

    @Override
    public boolean supportsWrites() {
        return true;
    }

    @Override
    public ObjectNode newResourceDocument(String coreSchema) {
        ObjectNode document = MAPPER.createObjectNode();
        ArrayNode schemas = document.putArray("schemas");
        if (coreSchema != null && !coreSchema.isBlank()) {
            schemas.add(coreSchema);
        }
        return document;
    }

    @Override
    public void addSchema(ObjectNode document, String urn) {
        ArrayNode schemas = document.withArray("schemas");
        for (JsonNode existing : schemas) {
            if (urn.equals(existing.asText())) {
                return;
            }
        }
        schemas.add(urn);
    }

    @Override
    public ObjectNode newPatchDocument() {
        ObjectNode patch = MAPPER.createObjectNode();
        patch.putArray("schemas").add(PATCH_OP_URN);
        patch.putArray("Operations");
        return patch;
    }

    @Override
    public void addOperation(ObjectNode patch, String operation, String path, JsonNode value) {
        ObjectNode entry = patch.withArray("Operations").addObject();
        entry.put("op", operation);
        entry.put("path", path);
        if (value != null) {
            entry.set("value", value);
        }
    }

    @Override
    public boolean isEmptyPatch(ObjectNode patch) {
        return patch.path("Operations").isEmpty();
    }

    @Override
    public String valueFilterPath(String path, String elementField, String value) {
        return path + "[" + elementField + " eq \"" + value + "\"]";
    }

    @Override
    public String qualifiedPath(String namespace, String field) {
        return namespace == null || namespace.isBlank() ? field : namespace + ":" + field;
    }

    @Override
    public String errorHint(JsonNode body) {
        return body == null ? "" : body.path("scimType").asText("");
    }

    @Override
    public String errorDetail(JsonNode body) {
        return body == null ? "" : body.path("detail").asText("");
    }
}
