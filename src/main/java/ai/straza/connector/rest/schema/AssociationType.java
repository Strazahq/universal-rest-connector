package ai.straza.connector.rest.schema;

import java.util.Map;

/**
 * A read-only multivalued attribute filled from another endpoint: the
 * {@code valueField} of every row whose {@code matchField} equals the object's
 * {@code localField} and that meets every {@code where} constraint.
 */
public class AssociationType {

    private final String attributeName;
    private final String endpoint;
    private final String localField;
    private final String matchField;
    private final String valueField;
    private final Map<String, String> where;

    public AssociationType(String attributeName, String endpoint, String localField,
                           String matchField, String valueField, Map<String, String> where) {
        this.attributeName = attributeName;
        this.endpoint = endpoint;
        this.localField = localField;
        this.matchField = matchField;
        this.valueField = valueField;
        this.where = where;
    }

    public String getAttributeName() {
        return attributeName;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getLocalField() {
        return localField;
    }

    public String getMatchField() {
        return matchField;
    }

    public String getValueField() {
        return valueField;
    }

    public Map<String, String> getWhere() {
        return where;
    }
}
