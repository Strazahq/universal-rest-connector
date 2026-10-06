package ai.straza.connector.rest.schema;

/**
 * Source of a derived object class, whose members are the elements of a parent
 * class's array field. Each element becomes an object with the element as uid and
 * name, plus a reference to its parent.
 */
public class DerivedSource {

    private final String parentObjectClass;
    private final String arrayField;
    private final String parentRefAttribute;
    private final String parentRefValueField;

    public DerivedSource(String parentObjectClass, String arrayField,
                         String parentRefAttribute, String parentRefValueField) {
        this.parentObjectClass = parentObjectClass;
        this.arrayField = arrayField;
        this.parentRefAttribute = parentRefAttribute;
        this.parentRefValueField = parentRefValueField;
    }

    /** The parent object class, e.g. {@code app}. */
    public String getParentObjectClass() {
        return parentObjectClass;
    }

    /** The parent's array field, e.g. {@code tools}. */
    public String getArrayField() {
        return arrayField;
    }

    /** The derived object's parent reference attribute. */
    public String getParentRefAttribute() {
        return parentRefAttribute;
    }

    /** The parent field used as the reference value, e.g. {@code name}. */
    public String getParentRefValueField() {
        return parentRefValueField;
    }
}
