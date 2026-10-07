package ai.straza.connector.rest.schema;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.straza.connector.rest.dialect.Dialect;
import ai.straza.connector.rest.dialect.Dialects;

/**
 * One object class from the schema file. A derived class has a
 * {@link DerivedSource} instead of a list endpoint. Classes and attributes are
 * read-only unless the file says otherwise.
 */
public class SchemaType {

    private final String objectClassName;
    private final String icfsUid;
    private final String icfsName;
    private final List<SchemaTypeAttribute> attributes;
    private final List<AssociationType> associations;
    private final DerivedSource derived;

    private Dialect dialect = Dialects.defaultDialect();
    private String basePath = "";
    private ObjectEndpoints endpoints = new ObjectEndpoints(null, null, null, null, null, null);
    private Set<Operation> operations = EnumSet.of(Operation.SEARCH);
    private Map<String, String> operationHints = Map.of();
    private Map<String, String> namespaces = Map.of();
    private String coreSchema;
    private List<ConditionalSchema> createOnlySchemas = List.of();
    private AttributePath enablePath;
    private Map<String, String> pushableFilters = Map.of();
    private int pageSize = 200;
    private AttributePath uidPath;
    private AttributePath namePath;
    private SchemaTypeAttribute nameAttribute;
    private SyncType syncType;

    public SchemaType(String objectClassName, String listEndpoint, String icfsUid, String icfsName,
                      List<SchemaTypeAttribute> attributes) {
        this(objectClassName, listEndpoint, icfsUid, icfsName, attributes, List.of(), null);
    }

    public SchemaType(String objectClassName, String listEndpoint, String icfsUid, String icfsName,
                      List<SchemaTypeAttribute> attributes, List<AssociationType> associations) {
        this(objectClassName, listEndpoint, icfsUid, icfsName, attributes, associations, null);
    }

    private SchemaType(String objectClassName, String listEndpoint, String icfsUid, String icfsName,
                       List<SchemaTypeAttribute> attributes, List<AssociationType> associations,
                       DerivedSource derived) {
        this.objectClassName = objectClassName;
        this.icfsUid = icfsUid;
        this.icfsName = icfsName;
        this.attributes = attributes;
        this.associations = associations;
        this.derived = derived;
        if (listEndpoint != null) {
            this.endpoints = new ObjectEndpoints(listEndpoint, null, null, null, null, null);
        }
        if (icfsUid != null) {
            this.uidPath = AttributePath.parse(objectClassName, "__UID__", icfsUid, Map.of());
        }
        if (icfsName != null) {
            this.namePath = AttributePath.parse(objectClassName, "__NAME__", icfsName, Map.of());
        }
    }

    /** Creates a derived class, which has no list endpoint. */
    public static SchemaType derived(String objectClassName, DerivedSource derived) {
        return new SchemaType(objectClassName, null, null, null, List.of(), List.of(), derived);
    }

    public String getObjectClassName() {
        return objectClassName;
    }

    /** The class's list endpoint, or {@code null} when it is derived. */
    public String getListEndpoint() {
        return endpoints.getList();
    }

    public String getIcfsUid() {
        return icfsUid;
    }

    public String getIcfsName() {
        return icfsName;
    }

    public List<SchemaTypeAttribute> getAttributes() {
        return attributes;
    }

    /** The attribute named {@code name}, or {@code null}. */
    public SchemaTypeAttribute getAttribute(String name) {
        for (SchemaTypeAttribute attribute : attributes) {
            if (attribute.getName().equals(name)) {
                return attribute;
            }
        }
        return null;
    }

    public List<AssociationType> getAssociations() {
        return associations;
    }

    public boolean isDerived() {
        return derived != null;
    }

    public DerivedSource getDerived() {
        return derived;
    }

    public boolean isUidAndNameSame() {
        return icfsUid != null && icfsUid.equals(icfsName);
    }

    public Dialect getDialect() {
        return dialect;
    }

    public void setDialect(Dialect dialect) {
        this.dialect = dialect;
    }

    /** Prefix for every endpoint of the class; empty when none. */
    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public ObjectEndpoints getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(ObjectEndpoints endpoints) {
        this.endpoints = endpoints;
    }

    /** The verbs this class offers. */
    public Set<Operation> getOperations() {
        return operations;
    }

    public void setOperations(Set<Operation> operations) {
        this.operations = operations;
    }

    public boolean supports(Operation operation) {
        return operations.contains(operation);
    }

    /** The hint appended when {@code operationKey} is refused, or {@code null}. */
    public String getOperationHint(String operationKey) {
        return operationHints.get(operationKey);
    }

    public void setOperationHints(Map<String, String> operationHints) {
        this.operationHints = operationHints;
    }

    /** Namespace alias to URN. */
    public Map<String, String> getNamespaces() {
        return namespaces;
    }

    public void setNamespaces(Map<String, String> namespaces) {
        this.namespaces = namespaces;
    }

    /** The core schema URN of the write envelope. */
    public String getCoreSchema() {
        return coreSchema;
    }

    public void setCoreSchema(String coreSchema) {
        this.coreSchema = coreSchema;
    }

    public List<ConditionalSchema> getCreateOnlySchemas() {
        return createOnlySchemas;
    }

    public void setCreateOnlySchemas(List<ConditionalSchema> createOnlySchemas) {
        this.createOnlySchemas = createOnlySchemas;
    }

    /** The wire path of {@code __ENABLE__}, or {@code null}. */
    public AttributePath getEnablePath() {
        return enablePath;
    }

    public void setEnablePath(AttributePath enablePath) {
        this.enablePath = enablePath;
    }

    /** ConnId attribute to wire attribute, for the filters the server accepts. */
    public Map<String, String> getPushableFilters() {
        return pushableFilters;
    }

    public void setPushableFilters(Map<String, String> pushableFilters) {
        this.pushableFilters = pushableFilters;
    }

    /** Rows per server-side page. */
    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /** Where the ConnId Uid lives in a resource document. */
    public AttributePath getUidPath() {
        return uidPath;
    }

    /** Where the ConnId Name lives in a resource document. */
    public AttributePath getNamePath() {
        return namePath;
    }

    /** The flags declared for {@code __NAME__}, or {@code null}. */
    public SchemaTypeAttribute getNameAttribute() {
        return nameAttribute;
    }

    public void setNameAttribute(SchemaTypeAttribute nameAttribute) {
        this.nameAttribute = nameAttribute;
    }

    /** True when {@code __NAME__} may be written. */
    public boolean isNameWritable() {
        return nameAttribute != null && nameAttribute.isWritable();
    }

    /** LiveSync routing, or {@code null} without a {@code sync} block. */
    public SyncType getSyncType() {
        return syncType;
    }

    public void setSyncType(SyncType syncType) {
        this.syncType = syncType;
    }

    public boolean supportsSync() {
        return syncType != null;
    }
}
