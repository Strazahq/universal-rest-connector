package ai.straza.connector.rest.dialect;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Protocol rules shared by every server that speaks one protocol: list format,
 * paging, filter syntax, write documents and error bodies. A class picks one with
 * the schema file's {@code dialect} key; see {@link JsonDialect} and
 * {@link ScimDialect}.
 */
public interface Dialect {

    /** The name the schema file's {@code dialect} key uses. */
    String name();

    /** The media type sent as Accept and, for bodies, Content-Type. */
    String mediaType();

    /** The rows of a list response. */
    List<JsonNode> rows(JsonNode body);

    /** True when the protocol pages a listing server-side. */
    boolean pagesServerSide();

    /** The query string for one page, or {@code null} when the protocol does not page. */
    String pageQuery(int startIndex, int pageSize);

    /** How many rows the server says exist in total, or {@code fallback} when it does not say. */
    int totalResults(JsonNode body, int fallback);

    /** True when the protocol has a server-side filter syntax. */
    boolean supportsPushedFilters();

    /** The query string for one equality filter on a wire attribute. */
    String filterQuery(String wireAttribute, String value);

    /** True when the protocol has create, replace and patch document forms. */
    boolean supportsWrites();

    /** A new resource document; {@code coreSchema} is ignored by dialects without an envelope. */
    ObjectNode newResourceDocument(String coreSchema);

    /** Adds a schema URN to a resource document's envelope, where the protocol has one. */
    void addSchema(ObjectNode document, String urn);

    /** An empty patch document. */
    ObjectNode newPatchDocument();

    /** Appends one {@code add}, {@code replace} or {@code remove} operation. */
    void addOperation(ObjectNode patch, String operation, String path, JsonNode value);

    /** True when the patch has no operations. */
    boolean isEmptyPatch(ObjectNode patch);

    /** The patch path that addresses one value of a multivalued attribute. */
    String valueFilterPath(String path, String elementField, String value);

    /** The patch path for a field, qualified by its namespace when it has one. */
    String qualifiedPath(String namespace, String field);

    /** The server's short machine-readable error hint, or an empty string. */
    String errorHint(JsonNode body);

    /** The server's human-readable error detail, or an empty string. */
    String errorDetail(JsonNode body);
}
