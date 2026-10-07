package ai.straza.connector.rest.filter;

import java.util.List;

import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.Uid;

/**
 * An equality or contains-all-values query on one ConnId attribute. Every search
 * result is matched against it locally, also when the filter was sent to the
 * server.
 */
public class RestFilter {

    private final String attribute;
    private final List<Object> values;

    public RestFilter(String attribute, List<Object> values) {
        this.attribute = attribute;
        this.values = List.copyOf(values);
    }

    /** A single-value query. */
    public static RestFilter of(String attribute, Object value) {
        return new RestFilter(attribute, List.of(value));
    }

    public String getAttribute() {
        return attribute;
    }

    /** The values the attribute must contain. */
    public List<Object> getValues() {
        return values;
    }

    /** True for a single value; only those can be sent to the server. */
    public boolean isSingleValue() {
        return values.size() == 1;
    }

    /** The single value as a string. */
    public String singleValue() {
        return String.valueOf(values.get(0));
    }

    public boolean isUidQuery() {
        return Uid.NAME.equals(attribute);
    }

    /**
     * True when the object matches: uid and name by equality, other attributes
     * must contain every value. An absent attribute never matches.
     */
    public boolean matches(ConnectorObject object) {
        if (isUidQuery()) {
            return isSingleValue() && singleValue().equals(object.getUid().getUidValue());
        }
        if (Name.NAME.equals(attribute)) {
            return isSingleValue() && singleValue().equals(object.getName().getNameValue());
        }
        Attribute actual = object.getAttributeByName(attribute);
        List<Object> present = actual == null || actual.getValue() == null ? List.of() : actual.getValue();
        for (Object required : values) {
            boolean found = false;
            for (Object value : present) {
                if (String.valueOf(value).equals(String.valueOf(required))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return attribute + "=" + values;
    }
}
