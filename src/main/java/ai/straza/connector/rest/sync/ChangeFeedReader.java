package ai.straza.connector.rest.sync;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.straza.connector.rest.schema.SyncFeedConfig;
import ai.straza.connector.rest.transport.ResourceClient;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectorException;

/** Reads the cursored change feed described by a {@link SyncFeedConfig}. */
public class ChangeFeedReader {

    private static final Log LOG = Log.getLog(ChangeFeedReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Page limit per call, in case the feed never stops reporting {@code more}. */
    private static final int MAX_PAGES = 50;

    private final ResourceClient client;
    private final SyncFeedConfig config;

    public ChangeFeedReader(ResourceClient client, SyncFeedConfig config) {
        this.client = client;
        this.config = config;
    }

    /** The feed's newest cursor. */
    public String head() {
        FeedPage page = parsePage(client.getJson(buildUrl(null, List.of(), 1)), config);
        return page.head();
    }

    /** Reads every record after {@code since}, or from the start when blank, following {@code nextCursor}. */
    public FeedBatch changesSince(String since, List<String> requestTypes) {
        List<ChangeRecord> all = new ArrayList<>();
        String cursor = since;
        String head = null;
        int pages = 0;
        while (true) {
            FeedPage page = parsePage(client.getJson(buildUrl(cursor, requestTypes, config.getPageLimit())), config);
            all.addAll(page.records());
            head = page.head();
            String next = page.nextCursor();
            boolean advanced = next != null && !next.isBlank() && !next.equals(cursor);
            if (next != null && !next.isBlank()) {
                cursor = next;
            }
            pages++;
            if (!page.more() || !advanced || pages >= MAX_PAGES) {
                if (page.more() && pages >= MAX_PAGES) {
                    LOG.warn("Change feed still reports more after {0} pages; stopping this cycle at cursor {1}.",
                            MAX_PAGES, cursor);
                }
                break;
            }
        }
        return new FeedBatch(all, cursor, head);
    }

    /** The feed URL, unencoded; the client encodes the query. */
    private String buildUrl(String since, List<String> requestTypes, int limit) {
        StringBuilder url = new StringBuilder(config.getFeedEndpoint());
        char sep = '?';
        if (since != null && !since.isBlank()) {
            url.append(sep).append(config.getSinceParam()).append('=').append(since);
            sep = '&';
        }
        if (requestTypes != null && !requestTypes.isEmpty()) {
            url.append(sep).append(config.getTypesParam()).append('=').append(String.join(",", requestTypes));
            sep = '&';
        }
        url.append(sep).append(config.getLimitParam()).append('=').append(limit);
        return url.toString();
    }


    /** Parses one feed page from a raw body. */
    static FeedPage parsePage(String body, SyncFeedConfig config) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new ConnectorException("Change feed response is not valid JSON: " + e.getMessage(), e);
        }
        return parsePage(root, config);
    }

    static FeedPage parsePage(JsonNode root, SyncFeedConfig config) {
        if (root == null || !root.isObject()) {
            throw new ConnectorException("Change feed response is not a JSON object.");
        }
        if (root.has("error")) {
            throw new ConnectorException("Change feed returned an error: " + root.get("error").asText());
        }

        List<ChangeRecord> records = new ArrayList<>();
        JsonNode array = root.get(config.getRecordsField());
        if (array != null && array.isArray()) {
            for (JsonNode row : array) {
                records.add(new ChangeRecord(
                        text(row, config.getCursorField()),
                        text(row, config.getTypeField()),
                        text(row, config.getOpField()),
                        text(row, config.getIdField())));
            }
        }
        return new FeedPage(records,
                text(root, config.getNextCursorField()),
                bool(root, config.getMoreField()),
                text(root, config.getHeadField()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.asBoolean(false);
    }

    /** One parsed feed page. */
    record FeedPage(List<ChangeRecord> records, String nextCursor, boolean more, String head) {
    }
}
