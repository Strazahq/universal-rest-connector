package ai.straza.connector.rest.sync;

/** One change-feed record: cursor, object type, op ({@code create}, {@code update} or {@code delete}) and object id. */
public record ChangeRecord(String cursor, String type, String op, String id) {

    public boolean isDeleteOp() {
        return "delete".equalsIgnoreCase(op);
    }
}
