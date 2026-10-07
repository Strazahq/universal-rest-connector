package ai.straza.connector.rest.schema;

import java.util.Map;

/**
 * One attribute of a {@link SchemaType}: name, type, multiplicity, wire path and
 * write flags. {@code creatable} and {@code updateable} default to false.
 */
public class SchemaTypeAttribute {

    /** How an empty replacement clears this attribute in a full-document update. */
    public enum ClearVia {
        /** Omitting the field clears it. */
        OMIT("omit"),
        /** An absent field is left unchanged, so only a patch can clear it. */
        PATCH_ONLY("patchOnly");

        private final String key;

        ClearVia(String key) {
            this.key = key;
        }

        /** The spelling the schema file uses. */
        public String key() {
            return key;
        }

        /** The mode named {@code key}, or {@code null}. */
        public static ClearVia byKey(String key) {
            for (ClearVia mode : values()) {
                if (mode.key.equals(key)) {
                    return mode;
                }
            }
            return null;
        }
    }

    private final String name;
    private final Class<?> dataType;
    private final boolean multivalued;
    private final boolean returnedByDefault;
    private final boolean required;
    private final boolean creatable;
    private final boolean updateable;
    private final AttributePath path;
    private final ClearVia clearVia;

    /** A read-only attribute whose wire field is its own name. */
    public SchemaTypeAttribute(String name, String dataType, boolean multivalued, boolean returnedByDefault) {
        this(name, dataType, multivalued, returnedByDefault, false, false, false,
                AttributePath.parse("", name, name, Map.of()), ClearVia.OMIT);
    }

    public SchemaTypeAttribute(String name, String dataType, boolean multivalued, boolean returnedByDefault,
                               boolean required, boolean creatable, boolean updateable,
                               AttributePath path, ClearVia clearVia) {
        this.name = name;
        this.dataType = resolveDataType(dataType);
        this.multivalued = multivalued;
        this.returnedByDefault = returnedByDefault;
        this.required = required;
        this.creatable = creatable;
        this.updateable = updateable;
        this.path = path;
        this.clearVia = clearVia;
    }

    public String getName() {
        return name;
    }

    public Class<?> getDataType() {
        return dataType;
    }

    public boolean isMultivalued() {
        return multivalued;
    }

    public boolean isReturnedByDefault() {
        return returnedByDefault;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isCreatable() {
        return creatable;
    }

    public boolean isUpdateable() {
        return updateable;
    }

    /** True when the attribute is creatable or updateable. */
    public boolean isWritable() {
        return creatable || updateable;
    }

    /** Where the value lives in the resource document. */
    public AttributePath getPath() {
        return path;
    }

    public ClearVia getClearVia() {
        return clearVia;
    }

    private static Class<?> resolveDataType(String dataType) {
        if (dataType == null) {
            return String.class;
        }
        switch (dataType.toLowerCase()) {
            case "string":
                return String.class;
            case "boolean":
                return Boolean.class;
            case "int":
            case "integer":
                return Integer.class;
            case "long":
                return Long.class;
            default:
                throw new IllegalArgumentException("Unsupported dataType '" + dataType
                        + "' in schema file. Supported: String, Boolean, Integer, Long.");
        }
    }
}
