package ai.straza.connector.rest.schema;

import java.util.List;

/**
 * LiveSync routing from a class's {@code sync} block, by record type:
 * <ul>
 *   <li>{@code self}: the record id is an object of this class; it is re-read, or
 *       deleted when the op is {@code delete}.</li>
 *   <li>{@code delete}: the record id is deleted.</li>
 *   <li>{@code reemitAll}: every object of the class is re-emitted.</li>
 *   <li>{@code rederive}: derived classes only; the record id is a parent
 *       reference and that parent's members are re-emitted.</li>
 * </ul>
 * {@code requestTypes} is sent as the feed's types parameter, and
 * {@code matchField} is {@code uid} (default) or {@code name}.
 */
public class SyncType {

    private final List<String> requestTypes;
    private final List<String> self;
    private final List<String> delete;
    private final List<String> reemitAll;
    private final List<String> rederive;
    private final String matchField;

    public SyncType(List<String> requestTypes, List<String> self, List<String> delete,
                    List<String> reemitAll, List<String> rederive, String matchField) {
        this.requestTypes = requestTypes;
        this.self = self;
        this.delete = delete;
        this.reemitAll = reemitAll;
        this.rederive = rederive;
        this.matchField = matchField;
    }

    public List<String> getRequestTypes() {
        return requestTypes;
    }

    public boolean isSelf(String type) {
        return self.contains(type);
    }

    public boolean isDelete(String type) {
        return delete.contains(type);
    }

    public boolean isReemitAll(String type) {
        return reemitAll.contains(type);
    }

    public boolean isRederive(String type) {
        return rederive.contains(type);
    }

    /** True when record ids match object names rather than uids. */
    public boolean matchesByName() {
        return "name".equalsIgnoreCase(matchField);
    }
}
