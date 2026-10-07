package ai.straza.connector.rest.dialect;

import java.util.List;

/** Resolves a schema file's {@code dialect} key. */
public final class Dialects {

    private static final List<Dialect> ALL = List.of(JsonDialect.INSTANCE, ScimDialect.INSTANCE);

    private Dialects() {
    }

    /** The dialect used when a class names none. */
    public static Dialect defaultDialect() {
        return JsonDialect.INSTANCE;
    }

    /** The dialect named {@code name}, or {@code null}. */
    public static Dialect byName(String name) {
        for (Dialect dialect : ALL) {
            if (dialect.name().equals(name)) {
                return dialect;
            }
        }
        return null;
    }

    /** All dialect names. */
    public static List<String> names() {
        return ALL.stream().map(Dialect::name).toList();
    }
}
