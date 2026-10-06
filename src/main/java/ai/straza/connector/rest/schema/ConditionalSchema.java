package ai.straza.connector.rest.schema;

import java.util.List;
import java.util.Locale;

/**
 * A {@code createOnlySchemas} entry: a schema URN added to a create document when
 * one attribute's value is in a set and the optional {@code configGate} is on.
 */
public final class ConditionalSchema {

    private final String urn;
    private final String attribute;
    private final List<String> values;
    private final boolean caseInsensitive;
    private final String configGate;

    public ConditionalSchema(String urn, String attribute, List<String> values,
                             boolean caseInsensitive, String configGate) {
        this.urn = urn;
        this.attribute = attribute;
        this.values = List.copyOf(values);
        this.caseInsensitive = caseInsensitive;
        this.configGate = configGate;
    }

    public String getUrn() {
        return urn;
    }

    /** The ConnId attribute the condition reads. */
    public String getAttribute() {
        return attribute;
    }

    /** The values that satisfy the condition. */
    public List<String> getValues() {
        return values;
    }

    /** The gating configuration property, or {@code null}. */
    public String getConfigGate() {
        return configGate;
    }

    /** True when {@code value} is in the set; a null or blank value never matches. */
    public boolean matches(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String candidate = caseInsensitive ? value.trim().toLowerCase(Locale.ROOT) : value;
        for (String declared : values) {
            String expected = caseInsensitive ? declared.toLowerCase(Locale.ROOT) : declared;
            if (expected.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
