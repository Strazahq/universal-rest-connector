package ai.straza.connector.rest.transport;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.dialect.Dialect;
import ai.straza.connector.rest.dialect.JsonDialect;
import ai.straza.connector.rest.schema.ObjectEndpoints;
import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.schema.TargetInfo;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;

/**
 * Sends a class's requests to its endpoints with the dialect's media type and maps
 * error statuses to ConnId exceptions.
 */
public class ResourceClient {

    /** Success statuses per verb. */
    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final int NO_CONTENT = 204;

    private static final Log LOG = Log.getLog(ResourceClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP status and parsed body; {@code NullNode} when empty. */
    public record Response(int status, JsonNode body) { }

    private final HttpClientManager httpClientManager;
    private final UniversalRestConfiguration configuration;
    private final TargetInfo target;

    public ResourceClient(HttpClientManager httpClientManager, UniversalRestConfiguration configuration,
                          TargetInfo target) {
        this.httpClientManager = httpClientManager;
        this.configuration = configuration;
        this.target = target;
    }

    // ---- raw layer ---------------------------------------------------------------

    /** Sends one request with the configured token; error statuses are returned, not thrown. */
    public Response send(String method, String pathWithQuery, JsonNode body, String mediaType) {
        return send(method, pathWithQuery, body, mediaType, configuration.getApiToken());
    }

    /** As {@link #send(String, String, JsonNode, String)} with an explicit token, or none when null. */
    public Response send(String method, String pathWithQuery, JsonNode body, String mediaType, GuardedString token) {
        String encoded = encodePath(pathWithQuery);
        String payload = body == null || body.isNull() ? null : body.toString();
        try {
            HttpResponse<String> response =
                    httpClientManager.send(method, encoded, payload, mediaType, token);
            return new Response(response.statusCode(), parse(response.body()));
        } catch (IOException e) {
            throw new ConnectionFailedException(
                    "Cannot reach " + pathWithQuery + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("Interrupted during " + method + " " + pathWithQuery + ".", e);
        }
    }

    /** GETs a plain JSON endpoint and returns its rows. */
    public List<JsonNode> getRows(String endpoint) {
        return JsonDialect.INSTANCE.rows(getJson(endpoint));
    }

    /**
     * GETs a plain JSON endpoint. A 404 is a plain error, never
     * {@link UnknownUidException}, so a wrong feed path cannot become delete deltas.
     */
    public JsonNode getJson(String endpoint) {
        Response response = send("GET", endpoint, null, JsonDialect.MEDIA_TYPE);
        int status = response.status();
        if (status == 401 || status == 403) {
            throw new InvalidCredentialException(target.getName() + " rejected the API token (HTTP " + status
                    + ") for " + endpoint + ". " + target.getCredentialHint());
        }
        if (!isSuccess(status)) {
            String detail = JsonDialect.INSTANCE.errorDetail(response.body());
            throw new ConnectorException("Unexpected response HTTP " + status + " from " + endpoint + "."
                    + (detail.isEmpty() ? "" : " Server said: " + detail));
        }
        LOG.ok("GET {0}: HTTP {1}", endpoint, status);
        return response.body();
    }

    // ---- per-class verbs ----------------------------------------------------------

    /** POSTs a new resource and returns the response body. */
    public JsonNode create(SchemaType type, JsonNode document) {
        return expect(type, "POST", type.getEndpoints().getCreate(), null, document, CREATED);
    }

    /** GETs one resource by id; a 404 surfaces as {@link UnknownUidException}. */
    public JsonNode getById(SchemaType type, String id) {
        return expect(type, "GET", type.getEndpoints().getById(), id, null, OK);
    }

    /** PUTs a full document. */
    public JsonNode replace(SchemaType type, String id, JsonNode document) {
        return expect(type, "PUT", type.getEndpoints().getReplace(), id, document, OK);
    }

    /** Sends a PATCH document. */
    public JsonNode patch(SchemaType type, String id, JsonNode document) {
        return expect(type, "PATCH", type.getEndpoints().getPatch(), id, document, OK);
    }

    public void delete(SchemaType type, String id) {
        expect(type, "DELETE", type.getEndpoints().getDelete(), id, null, NO_CONTENT);
    }

    /** Lists the class with a dialect filter {@code query}, or every page when {@code query} is null. */
    public List<JsonNode> list(SchemaType type, String query) {
        Dialect dialect = type.getDialect();
        String path = type.getBasePath() + type.getEndpoints().getList();
        if (query != null || !dialect.pagesServerSide()) {
            Response response = send("GET", query == null ? path : path + "?" + query, null, dialect.mediaType());
            if (!isSuccess(response.status())) {
                throw mapError(dialect, response, "GET " + type.getEndpoints().getList());
            }
            return dialect.rows(response.body());
        }
        List<JsonNode> all = new ArrayList<>();
        int startIndex = 1;
        while (true) {
            Response response = send("GET", path + "?" + dialect.pageQuery(startIndex, type.getPageSize()),
                    null, dialect.mediaType());
            if (!isSuccess(response.status())) {
                throw mapError(dialect, response, "GET " + type.getEndpoints().getList());
            }
            List<JsonNode> page = dialect.rows(response.body());
            all.addAll(page);
            int total = dialect.totalResults(response.body(), all.size());
            if (page.isEmpty() || all.size() >= total) {
                return all;
            }
            startIndex += page.size();
        }
    }

    /** Maps an error response to a ConnId exception carrying the server's hint and detail. */
    public ConnectorException mapError(Dialect dialect, Response response, String context) {
        String hint = dialect.errorHint(response.body());
        String detail = dialect.errorDetail(response.body());
        String message = context + " failed: HTTP " + response.status()
                + (hint.isEmpty() ? "" : " " + hint)
                + (detail.isEmpty() ? "" : " (" + detail + ")");
        LOG.info("{0}", message);
        switch (response.status()) {
            case 401:
            case 403:
                return new InvalidCredentialException(target.getName() + " rejected the API token (HTTP "
                        + response.status() + ") for " + context + ". " + target.getCredentialHint());
            case 404:
                return new UnknownUidException(message);
            case 409:
                return new AlreadyExistsException(message);
            case 400:
                if ("mutability".equals(hint)) {
                    return new PermissionDeniedException(message);
                }
                return new InvalidAttributeValueException(message);
            default:
                return new ConnectorException(message);
        }
    }

    // ---- helpers ------------------------------------------------------------------

    private JsonNode expect(SchemaType type, String method, String endpoint, String id, JsonNode document,
                            int expected) {
        Dialect dialect = type.getDialect();
        String path = type.getBasePath() + (id == null ? endpoint : ObjectEndpoints.resolve(endpoint, id));
        Response response = send(method, path, document, dialect.mediaType());
        if (response.status() != expected) {
            throw mapError(dialect, response, method + " " + (id == null ? endpoint
                    : ObjectEndpoints.resolve(endpoint, id)));
        }
        return response.body();
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new ConnectorException("Response is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Percent-encodes each query key and value, leaving the path as is. */
    static String encodePath(String pathWithQuery) {
        int q = pathWithQuery.indexOf('?');
        if (q < 0) {
            return pathWithQuery;
        }
        StringBuilder encoded = new StringBuilder(pathWithQuery.substring(0, q + 1));
        String[] params = pathWithQuery.substring(q + 1).split("&");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                encoded.append('&');
            }
            int eq = params[i].indexOf('=');
            if (eq < 0) {
                encoded.append(component(params[i]));
            } else {
                encoded.append(component(params[i].substring(0, eq)))
                        .append('=')
                        .append(component(params[i].substring(eq + 1)));
            }
        }
        return encoded.toString();
    }

    private static String component(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
