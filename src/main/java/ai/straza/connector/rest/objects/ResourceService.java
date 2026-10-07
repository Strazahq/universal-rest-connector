package ai.straza.connector.rest.objects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ai.straza.connector.rest.filter.RestFilter;
import ai.straza.connector.rest.schema.AssociationType;
import ai.straza.connector.rest.schema.AttributePath;
import ai.straza.connector.rest.schema.DerivedSource;
import ai.straza.connector.rest.schema.Operation;
import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.transport.ResourceClient;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.SearchResult;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.spi.SearchResultsHandler;

/**
 * Runs searches and writes for schema-file classes. A uid query uses
 * {@code endpoints.byId} when declared, a single value on a
 * {@code filters.pushable} attribute is sent as a server filter, and anything
 * else lists the class; every result is then narrowed locally.
 */
public class ResourceService {

    private static final Log LOG = Log.getLog(ResourceService.class);

    private final ResourceClient client;
    private final Map<String, SchemaType> schemaTypes;

    public ResourceService(ResourceClient client, Map<String, SchemaType> schemaTypes) {
        this.client = client;
        this.schemaTypes = schemaTypes;
    }

    /** Reads matching objects, sorts them by uid and hands the requested page to {@code handler}. */
    public void search(SchemaType type, RestFilter query, ResultsHandler handler, OperationOptions options) {
        List<ConnectorObject> objects = read(type, query);
        objects.sort(Comparator.comparing(object -> object.getUid().getUidValue()));

        Integer pageSize = options == null ? null : options.getPageSize();
        int from = 0;
        int to = objects.size();
        if (pageSize != null && pageSize > 0) {
            int offset = options.getPagedResultsOffset() == null ? 1 : options.getPagedResultsOffset();
            from = Math.min(Math.max(offset - 1, 0), objects.size());
            to = Math.min(from + pageSize, objects.size());
        }
        for (int i = from; i < to; i++) {
            if (!handler.handle(objects.get(i))) {
                break;
            }
        }
        if (handler instanceof SearchResultsHandler) {
            ((SearchResultsHandler) handler).handleResult(new SearchResult(null, objects.size() - to));
        }
    }

    /** Creates a resource and returns the uid read from the create response. */
    public Uid create(SchemaType type, Set<Attribute> attributes, Predicate<String> gateEnabled) {
        requireOperation(type, Operation.CREATE);
        JsonNode created = client.create(type, ResourceWriter.toCreateDocument(type, attributes, gateEnabled));
        String uid = ResourceMapper.single(created, type.getUidPath());
        if (uid == null) {
            throw new ConnectorException("The create response for object class " + type.getObjectClassName()
                    + " carries no '" + type.getIcfsUid() + "', so the new object has no uid.");
        }
        LOG.ok("create {0}: uid={1}", type.getObjectClassName(), uid);
        return new Uid(uid);
    }

    /** Reads the current resource, applies the replacements and writes the full document back. */
    public Uid replace(SchemaType type, Uid uid, Set<Attribute> replacements) {
        requireOperation(type, Operation.UPDATE);
        JsonNode current = client.getById(type, uid.getUidValue());
        client.replace(type, uid.getUidValue(), ResourceWriter.toReplaceDocument(type, current, replacements));
        return uid;
    }

    /** Sends the deltas as one patch document; an empty patch is not sent. */
    public Set<AttributeDelta> updateDelta(SchemaType type, Uid uid, Set<AttributeDelta> deltas) {
        requireOperation(type, Operation.UPDATE_DELTA);
        ObjectNode patch = ResourceWriter.toPatchDocument(type, deltas);
        if (type.getDialect().isEmptyPatch(patch)) {
            return Set.of();
        }
        client.patch(type, uid.getUidValue(), patch);
        return Set.of();
    }

    public void delete(SchemaType type, Uid uid) {
        requireOperation(type, Operation.DELETE);
        client.delete(type, uid.getUidValue());
    }

    /** All objects of the class matching {@code query}, unsorted and unpaged. */
    public List<ConnectorObject> read(SchemaType type, RestFilter query) {
        if (type.isDerived()) {
            return narrow(derive(type), query);
        }
        List<JsonNode> rows;
        String pushed = pushedQuery(type, query);
        if (query != null && query.isUidQuery() && query.isSingleValue()
                && type.getEndpoints().getById() != null) {
            rows = byId(type, query.singleValue());
        } else if (pushed != null) {
            rows = client.list(type, pushed);
        } else {
            rows = client.list(type, null);
        }

        Map<String, List<JsonNode>> associationRows = new HashMap<>();
        for (AssociationType association : type.getAssociations()) {
            associationRows.computeIfAbsent(association.getEndpoint(), client::getRows);
        }

        List<ConnectorObject> objects = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            Map<String, List<Object>> associations =
                    UniversalObjectsHandler.resolveAssociations(type, row, associationRows);
            objects.add(ResourceMapper.toConnectorObject(type, row, associations));
        }
        // Narrow on every lane: the server may have ignored a pushed filter.
        return narrow(objects, query);
    }

    /** The object with uid {@code id}, or {@code null} when it is gone or the class has no by-id endpoint. */
    public ConnectorObject fetchByUid(SchemaType type, String id) {
        if (type.getEndpoints().getById() == null) {
            return null;
        }
        List<JsonNode> rows = byId(type, id);
        return rows.isEmpty() ? null : ResourceMapper.toConnectorObject(type, rows.get(0));
    }

    /** All current objects of the class. */
    public List<ConnectorObject> fetchAll(SchemaType type) {
        return read(type, null);
    }

    /** The members of a derived class whose parent reference equals {@code parentValue}. */
    public List<ConnectorObject> derivedForParent(SchemaType type, String parentValue) {
        if (parentValue == null) {
            return List.of();
        }
        String refAttribute = type.getDerived().getParentRefAttribute();
        List<ConnectorObject> matched = new ArrayList<>();
        for (ConnectorObject object : derive(type)) {
            Attribute attribute = object.getAttributeByName(refAttribute);
            if (attribute != null && attribute.getValue() != null && attribute.getValue().contains(parentValue)) {
                matched.add(object);
            }
        }
        return matched;
    }

    /** The class named {@code objectClass}; throws for an undeclared class. */
    public SchemaType require(String objectClass) {
        SchemaType type = schemaTypes.get(objectClass);
        if (type == null) {
            throw new IllegalArgumentException("Unsupported object class: " + objectClass
                    + ". The schema file declares " + String.join(", ", schemaTypes.keySet()) + ".");
        }
        return type;
    }

    /** Throws before any request when the class does not list {@code operation}, adding its hint. */
    public static void requireOperation(SchemaType type, Operation operation) {
        if (!type.supports(operation)) {
            String hint = type.getOperationHint(operation.key());
            throw new UnsupportedOperationException("Object class " + type.getObjectClassName()
                    + " does not support " + operation.key() + ": the schema file does not list it among the "
                    + "class's operations. Nothing was sent." + (hint == null ? "" : " " + hint));
        }
    }

    // ---- lanes --------------------------------------------------------------------

    /** The dialect filter query for a pushable single-value query, or {@code null}. */
    private static String pushedQuery(SchemaType type, RestFilter query) {
        if (query == null || !query.isSingleValue()) {
            return null;
        }
        String wireAttribute = type.getPushableFilters().get(query.getAttribute());
        if (wireAttribute == null) {
            return null;
        }
        return type.getDialect().filterQuery(wireAttribute, query.singleValue());
    }

    private List<JsonNode> byId(SchemaType type, String id) {
        try {
            return List.of(client.getById(type, id));
        } catch (UnknownUidException e) {
            return List.of(); // an unknown uid is an empty result
        }
    }

    private static List<ConnectorObject> narrow(List<ConnectorObject> objects, RestFilter query) {
        if (query == null) {
            return objects;
        }
        List<ConnectorObject> matched = new ArrayList<>(objects.size());
        for (ConnectorObject object : objects) {
            if (query.matches(object)) {
                matched.add(object);
            }
        }
        return matched;
    }

    /** Builds the members of a derived class from its parents' array field. */
    private List<ConnectorObject> derive(SchemaType type) {
        DerivedSource derived = type.getDerived();
        SchemaType parent = schemaTypes.get(derived.getParentObjectClass());
        if (parent == null || parent.isDerived()) {
            throw new ConfigurationException("Derived object class '" + type.getObjectClassName()
                    + "' references unknown or non-listed parent '" + derived.getParentObjectClass() + "'.");
        }
        List<JsonNode> parentRows = client.list(parent, null);
        AttributePath parentRefPath = AttributePath.parse(parent.getObjectClassName(),
                derived.getParentRefAttribute(), derived.getParentRefValueField(), Map.of());

        // Element ids should be unique; keep the first.
        Map<String, ConnectorObject> byUid = new LinkedHashMap<>();
        for (JsonNode parentRow : parentRows) {
            String parentRefValue = ResourceMapper.single(parentRow, parentRefPath);
            JsonNode array = parentRow.get(derived.getArrayField());
            if (array != null && array.isArray()) {
                for (JsonNode element : array) {
                    if (element == null || element.isNull()) {
                        continue;
                    }
                    String elementId = element.asText();
                    byUid.putIfAbsent(elementId,
                            UniversalObjectsHandler.toDerivedObject(type, elementId, parentRefValue));
                }
            }
        }
        LOG.ok("derived {0}: {1} member(s)", type.getObjectClassName(), byUid.size());
        return new ArrayList<>(byUid.values());
    }
}
