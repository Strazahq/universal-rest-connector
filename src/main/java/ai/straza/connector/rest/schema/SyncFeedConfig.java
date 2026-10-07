package ai.straza.connector.rest.schema;

/**
 * The change feed from the schema file's top-level {@code sync} block; only
 * {@code feedEndpoint} is required. A page has the shape
 * {@code { records: [ { cursor, type, op, id } ], nextCursor, more, head }}.
 */
public class SyncFeedConfig {

    private final String feedEndpoint;
    private final String sinceParam;
    private final String typesParam;
    private final String limitParam;
    private final int pageLimit;

    private final String recordsField;
    private final String headField;
    private final String nextCursorField;
    private final String moreField;

    private final String cursorField;
    private final String typeField;
    private final String opField;
    private final String idField;

    public SyncFeedConfig(String feedEndpoint, String sinceParam, String typesParam, String limitParam,
                          int pageLimit, String recordsField, String headField, String nextCursorField,
                          String moreField, String cursorField, String typeField, String opField, String idField) {
        this.feedEndpoint = feedEndpoint;
        this.sinceParam = sinceParam;
        this.typesParam = typesParam;
        this.limitParam = limitParam;
        this.pageLimit = pageLimit;
        this.recordsField = recordsField;
        this.headField = headField;
        this.nextCursorField = nextCursorField;
        this.moreField = moreField;
        this.cursorField = cursorField;
        this.typeField = typeField;
        this.opField = opField;
        this.idField = idField;
    }

    public String getFeedEndpoint() {
        return feedEndpoint;
    }

    public String getSinceParam() {
        return sinceParam;
    }

    public String getTypesParam() {
        return typesParam;
    }

    public String getLimitParam() {
        return limitParam;
    }

    public int getPageLimit() {
        return pageLimit;
    }

    public String getRecordsField() {
        return recordsField;
    }

    public String getHeadField() {
        return headField;
    }

    public String getNextCursorField() {
        return nextCursorField;
    }

    public String getMoreField() {
        return moreField;
    }

    public String getCursorField() {
        return cursorField;
    }

    public String getTypeField() {
        return typeField;
    }

    public String getOpField() {
        return opField;
    }

    public String getIdField() {
        return idField;
    }
}
