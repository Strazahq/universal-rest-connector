package ai.straza.connector.rest.sync;

import java.util.List;

/** The records read from the feed in order, the cursor to resume from, and the feed head. */
public record FeedBatch(List<ChangeRecord> records, String nextCursor, String head) {
}
