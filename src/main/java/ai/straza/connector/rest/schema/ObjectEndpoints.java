package ai.straza.connector.rest.schema;

/**
 * The per-verb paths from a class's {@code endpoints} block, {@code null} when not
 * declared. By-id paths contain an {@code {id}} placeholder.
 */
public final class ObjectEndpoints {

    private final String list;
    private final String byId;
    private final String create;
    private final String replace;
    private final String patch;
    private final String delete;

    public ObjectEndpoints(String list, String byId, String create, String replace, String patch, String delete) {
        this.list = list;
        this.byId = byId;
        this.create = create;
        this.replace = replace;
        this.patch = patch;
        this.delete = delete;
    }

    /** The list path; {@code listEndpoint} sets it too. */
    public String getList() {
        return list;
    }

    /** The by-id read path, or {@code null}. */
    public String getById() {
        return byId;
    }

    public String getCreate() {
        return create;
    }

    public String getReplace() {
        return replace;
    }

    public String getPatch() {
        return patch;
    }

    public String getDelete() {
        return delete;
    }

    /** {@code path} with {@code {id}} replaced by {@code id}. */
    public static String resolve(String path, String id) {
        return path.replace("{id}", id);
    }
}
