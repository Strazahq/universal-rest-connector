package ai.straza.connector.rest.schema;

import java.util.List;

/**
 * A verb in a class's {@code operations} list. An unlisted verb is removed from
 * the ConnId support matrix and refused before a request is built. The default is
 * {@link #SEARCH}, plus {@link #SYNC} when the class has a {@code sync} block.
 */
public enum Operation {

    /** Create one resource. */
    CREATE("create"),
    /** Replace one resource with a full document. */
    UPDATE("update"),
    /** Change one resource with a delta document. */
    UPDATE_DELTA("updateDelta"),
    /** Delete one resource. */
    DELETE("delete"),
    /** List and read resources. */
    SEARCH("search"),
    /** Consume the change feed for this class. */
    SYNC("sync");

    private final String key;

    Operation(String key) {
        this.key = key;
    }

    /** The spelling the schema file uses. */
    public String key() {
        return key;
    }

    /** The operation named {@code key}, or {@code null}. */
    public static Operation byKey(String key) {
        for (Operation operation : values()) {
            if (operation.key.equals(key)) {
                return operation;
            }
        }
        return null;
    }

    /** All schema-file spellings. */
    public static List<String> keys() {
        return List.of(values()).stream().map(Operation::key).toList();
    }
}
