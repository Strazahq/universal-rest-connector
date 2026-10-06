package ai.straza.connector.rest.schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.straza.connector.rest.dialect.Dialect;
import ai.straza.connector.rest.dialect.Dialects;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.Uid;

/**
 * Parses and validates the JSON schema file into {@link SchemaType}s keyed by
 * object class name. Unknown keys and contradictory declarations fail the load;
 * keys starting with {@code //} are comments.
 */
public class UniversalSchemaHandler {

    private static final Log LOG = Log.getLog(UniversalSchemaHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> ROOT_KEYS = Set.of("objects", "sync", "target");
    private static final Set<String> OBJECT_KEYS = Set.of("objectClass", "derivedFrom", "listEndpoint",
            "icfsUid", "icfsName", "attributes", "associations", "sync", "dialect", "basePath",
            "endpoints", "operations", "operationHints", "namespaces", "coreSchema", "createOnlySchemas",
            "enable", "filters", "paging");
    private static final Set<String> ATTRIBUTE_KEYS = Set.of("dataType", "multivalued", "returnedByDefault",
            "required", "creatable", "updateable", "path", "clearVia");
    private static final Set<String> ENDPOINT_KEYS = Set.of("list", "byId", "create", "replace", "patch", "delete");
    private static final Set<String> DERIVED_KEYS = Set.of("objectClass", "arrayField", "parentRef", "parentRefValue");
    private static final Set<String> ASSOCIATION_KEYS = Set.of("endpoint", "localField", "matchField",
            "valueField", "where");
    private static final Set<String> SYNC_KEYS = Set.of("requestTypes", "self", "delete", "reemitAll",
            "rederive", "matchField");
    private static final Set<String> FEED_KEYS = Set.of("feedEndpoint", "sinceParam", "typesParam", "limitParam",
            "pageLimit", "recordsField", "headField", "nextCursorField", "moreField", "cursorField",
            "typeField", "opField", "idField");
    private static final Set<String> TARGET_KEYS = Set.of("name", "credentialHint");
    private static final Set<String> CONDITIONAL_KEYS = Set.of("urn", "when", "configGate");
    private static final Set<String> WHEN_KEYS = Set.of("attribute", "in", "caseInsensitive");

    /** Valid operationHints keys: every operation plus {@code rename}. */
    private static final List<String> HINT_KEYS = hintKeys();

    private final Map<String, SchemaType> schemaTypes = new LinkedHashMap<>();
    private final String fileSha256;
    private SyncFeedConfig syncFeedConfig;
    private TargetInfo targetInfo = TargetInfo.fallback();

    public UniversalSchemaHandler(String schemaFilePath) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(schemaFilePath));
        } catch (IOException e) {
            throw new ConfigurationException("Cannot read schema file: " + schemaFilePath
                    + " (" + e.getMessage() + ")", e);
        }
        this.fileSha256 = sha256(bytes);
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new ConfigurationException("Schema file is not valid JSON: " + schemaFilePath
                    + " (" + e.getMessage() + ")", e);
        }
        load(root, schemaFilePath);
        if (schemaTypes.isEmpty()) {
            throw new ConfigurationException("Schema file declares no object classes: " + schemaFilePath);
        }
    }

    private void load(JsonNode root, String schemaFilePath) {
        JsonNode objects = root == null ? null : root.get("objects");
        if (objects == null || !objects.isArray()) {
            throw new ConfigurationException("Schema file must have a top-level 'objects' array: " + schemaFilePath);
        }
        rejectUnknownKeys(root, ROOT_KEYS, "the schema file's top level");

        for (JsonNode obj : objects) {
            String objectClass = requireText(obj, "objectClass");
            rejectUnknownKeys(obj, OBJECT_KEYS, "object class '" + objectClass + "'");
            if (schemaTypes.containsKey(objectClass)) {
                throw new ConfigurationException("Object class '" + objectClass + "' is declared twice. "
                        + "Give each object class one entry in 'objects'.");
            }
            schemaTypes.put(objectClass, parseObject(objectClass, obj));
        }

        this.targetInfo = parseTarget(root.get("target"));
        this.syncFeedConfig = parseSyncFeedConfig(root.get("sync"));
        if (syncFeedConfig != null) {
            LOG.ok("Loaded change-feed config: {0}", syncFeedConfig.getFeedEndpoint());
        }
    }

    private SchemaType parseObject(String objectClass, JsonNode obj) {
        JsonNode derivedNode = obj.get("derivedFrom");
        if (derivedNode != null && derivedNode.isObject()) {
            rejectUnknownKeys(derivedNode, DERIVED_KEYS, "the 'derivedFrom' block of object class '"
                    + objectClass + "'");
            DerivedSource derived = new DerivedSource(
                    requireText(derivedNode, "objectClass"),
                    requireText(derivedNode, "arrayField"),
                    requireText(derivedNode, "parentRef"),
                    requireText(derivedNode, "parentRefValue"));
            SchemaType derivedType = SchemaType.derived(objectClass, derived);
            derivedType.setSyncType(parseSyncType(objectClass, obj.get("sync")));
            derivedType.setOperations(parseOperations(objectClass, obj, derivedType.supportsSync()));
            derivedType.setOperationHints(parseOperationHints(objectClass, obj.get("operationHints")));
            LOG.ok("Loaded derived object class {0} from {1}.{2}",
                    objectClass, derived.getParentObjectClass(), derived.getArrayField());
            return derivedType;
        }

        Dialect dialect = parseDialect(objectClass, obj);
        Map<String, String> namespaces = parseNamespaces(objectClass, obj.get("namespaces"));
        ObjectEndpoints endpoints = parseEndpoints(objectClass, obj);
        String icfsUid = requireText(obj, "icfsUid");
        String icfsName = requireText(obj, "icfsName");

        List<SchemaTypeAttribute> attributes = new ArrayList<>();
        SchemaTypeAttribute nameAttribute = null;
        JsonNode attrs = obj.get("attributes");
        if (attrs != null && attrs.isObject()) {
            for (Map.Entry<String, JsonNode> entry : attrs.properties()) {
                String name = entry.getKey();
                if (name.startsWith("//")) {
                    continue;
                }
                SchemaTypeAttribute attribute = parseAttribute(objectClass, name, entry.getValue(), namespaces);
                if (Name.NAME.equals(name)) {
                    nameAttribute = attribute;
                } else {
                    attributes.add(attribute);
                }
            }
        }
        List<AssociationType> associations = new ArrayList<>();
        JsonNode associationsNode = obj.get("associations");
        if (associationsNode != null && associationsNode.isObject()) {
            for (Map.Entry<String, JsonNode> entry : associationsNode.properties()) {
                if (!entry.getKey().startsWith("//")) {
                    associations.add(parseAssociation(objectClass, entry.getKey(), entry.getValue()));
                }
            }
        }

        SchemaType schemaType = new SchemaType(objectClass, endpoints.getList(), icfsUid, icfsName,
                attributes, associations);
        schemaType.setDialect(dialect);
        schemaType.setBasePath(text(obj, "basePath", ""));
        schemaType.setEndpoints(endpoints);
        schemaType.setNamespaces(namespaces);
        schemaType.setCoreSchema(text(obj, "coreSchema", null));
        schemaType.setNameAttribute(nameAttribute);
        schemaType.setPushableFilters(parsePushableFilters(objectClass, obj.get("filters")));
        schemaType.setPageSize(parsePageSize(objectClass, obj.get("paging")));
        schemaType.setCreateOnlySchemas(parseCreateOnlySchemas(objectClass, obj.get("createOnlySchemas")));
        String enable = text(obj, "enable", null);
        if (enable != null) {
            schemaType.setEnablePath(AttributePath.parse(objectClass, "__ENABLE__", enable, namespaces));
        }
        schemaType.setSyncType(parseSyncType(objectClass, obj.get("sync")));
        schemaType.setOperations(parseOperations(objectClass, obj, schemaType.supportsSync()));
        schemaType.setOperationHints(parseOperationHints(objectClass, obj.get("operationHints")));

        validate(schemaType, obj);
        LOG.ok("Loaded object class {0} -> {1} ({2} attributes, {3} associations, dialect {4})",
                objectClass, endpoints.getList(), attributes.size(), associations.size(), dialect.name());
        return schemaType;
    }

    // ---- per-key parsing --------------------------------------------------------

    private static Dialect parseDialect(String objectClass, JsonNode obj) {
        String name = text(obj, "dialect", null);
        if (name == null) {
            return Dialects.defaultDialect();
        }
        Dialect dialect = Dialects.byName(name);
        if (dialect == null) {
            throw new ConfigurationException("Object class '" + objectClass + "' names the unknown dialect '"
                    + name + "'. Write one of " + String.join(", ", Dialects.names())
                    + ", or leave 'dialect' out for " + Dialects.defaultDialect().name() + ".");
        }
        return dialect;
    }

    private static Map<String, String> parseNamespaces(String objectClass, JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'namespaces' as "
                    + "something other than an object. Write a map of alias to URN, for example "
                    + "{ \"ext\": \"urn:example:2.0:User\" }.");
        }
        Map<String, String> namespaces = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (entry.getKey().startsWith("//")) {
                continue;
            }
            if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                throw new ConfigurationException("Object class '" + objectClass + "' maps the namespace alias '"
                        + entry.getKey() + "' to something that is not a URN string. Write the URN the server "
                        + "keys that sub-document with.");
            }
            namespaces.put(entry.getKey(), entry.getValue().asText());
        }
        return namespaces;
    }

    private static ObjectEndpoints parseEndpoints(String objectClass, JsonNode obj) {
        JsonNode node = obj.get("endpoints");
        String shorthand = text(obj, "listEndpoint", null);
        if (node == null || node.isNull()) {
            if (shorthand == null) {
                throw new ConfigurationException("Object class '" + objectClass + "' declares no list endpoint. "
                        + "Write \"listEndpoint\": \"/your/path\", or an 'endpoints' block with a 'list' path.");
            }
            return new ObjectEndpoints(shorthand, null, null, null, null, null);
        }
        if (!node.isObject()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'endpoints' as "
                    + "something other than an object. Write a map of verb to path, for example "
                    + "{ \"list\": \"/Users\", \"byId\": \"/Users/{id}\" }.");
        }
        rejectUnknownKeys(node, ENDPOINT_KEYS, "the 'endpoints' block of object class '" + objectClass + "'");
        String list = text(node, "list", shorthand);
        if (shorthand != null && node.has("list") && !shorthand.equals(text(node, "list", null))) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares both \"listEndpoint\": \""
                    + shorthand + "\" and \"endpoints.list\": \"" + text(node, "list", null)
                    + "\". Keep one of them.");
        }
        if (list == null) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares no list endpoint. "
                    + "Add \"list\" to its 'endpoints' block, or write \"listEndpoint\": \"/your/path\".");
        }
        return new ObjectEndpoints(list,
                text(node, "byId", null), text(node, "create", null), text(node, "replace", null),
                text(node, "patch", null), text(node, "delete", null));
    }

    private static Set<Operation> parseOperations(String objectClass, JsonNode obj, boolean hasSyncBlock) {
        JsonNode node = obj.get("operations");
        if (node == null || node.isNull()) {
            Set<Operation> defaults = EnumSet.of(Operation.SEARCH);
            if (hasSyncBlock) {
                defaults.add(Operation.SYNC);
            }
            return defaults;
        }
        if (!node.isArray()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'operations' as "
                    + "something other than an array. Write a list, for example [\"search\", \"sync\"].");
        }
        Set<Operation> operations = EnumSet.noneOf(Operation.class);
        for (JsonNode element : node) {
            Operation operation = element.isTextual() ? Operation.byKey(element.asText()) : null;
            if (operation == null) {
                throw new ConfigurationException("Object class '" + objectClass + "' lists the unknown operation "
                        + element + ". Write one of " + String.join(", ", Operation.keys()) + ".");
            }
            operations.add(operation);
        }
        return operations;
    }

    private static Map<String, String> parseOperationHints(String objectClass, JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'operationHints' as "
                    + "something other than an object. Write a map of operation to one sentence, for example "
                    + "{ \"create\": \"Roles are created in the service desk.\" }.");
        }
        Map<String, String> hints = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String key = entry.getKey();
            if (key.startsWith("//")) {
                continue;
            }
            if (!HINT_KEYS.contains(key)) {
                throw new ConfigurationException("Object class '" + objectClass + "' writes an operationHints "
                        + "entry for '" + key + "', which is not something a class refuses. Write one of "
                        + String.join(", ", HINT_KEYS) + ".");
            }
            if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                throw new ConfigurationException("Object class '" + objectClass + "' writes an operationHints "
                        + "entry for '" + key + "' that says nothing. Write one sentence telling the operator "
                        + "what happens instead, or remove the entry.");
            }
            hints.put(key, entry.getValue().asText());
        }
        return hints;
    }

    private static List<String> hintKeys() {
        List<String> keys = new ArrayList<>(Operation.keys());
        keys.add("rename");
        keys.sort(String::compareTo);
        return List.copyOf(keys);
    }

    private static Map<String, String> parsePushableFilters(String objectClass, JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'filters' as "
                    + "something other than an object. Write { \"pushable\": { \"__NAME__\": \"userName\" } }.");
        }
        rejectUnknownKeys(node, Set.of("pushable"), "the 'filters' block of object class '" + objectClass + "'");
        JsonNode pushable = node.get("pushable");
        if (pushable == null || pushable.isNull()) {
            return Map.of();
        }
        if (!pushable.isObject()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'filters.pushable' as "
                    + "something other than an object. Write a map of ConnId attribute to wire attribute, for "
                    + "example { \"__NAME__\": \"userName\" }.");
        }
        Map<String, String> filters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : pushable.properties()) {
            if (entry.getKey().startsWith("//")) {
                continue;
            }
            if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                throw new ConfigurationException("Object class '" + objectClass + "' maps the pushable filter '"
                        + entry.getKey() + "' to something that is not a wire attribute name.");
            }
            filters.put(entry.getKey(), entry.getValue().asText());
        }
        return filters;
    }

    private static int parsePageSize(String objectClass, JsonNode node) {
        if (node == null || node.isNull()) {
            return 200;
        }
        if (!node.isObject()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'paging' as "
                    + "something other than an object. Write { \"pageSize\": 200 }.");
        }
        rejectUnknownKeys(node, Set.of("pageSize"), "the 'paging' block of object class '" + objectClass + "'");
        int pageSize = intVal(node, "pageSize", 200);
        if (pageSize <= 0) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares \"paging.pageSize\": "
                    + pageSize + ". Write a positive number of rows per page, for example 200.");
        }
        return pageSize;
    }

    private static List<ConditionalSchema> parseCreateOnlySchemas(String objectClass, JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'createOnlySchemas' as "
                    + "something other than an array. Write a list of { \"urn\": ..., \"when\": ... } entries.");
        }
        List<ConditionalSchema> conditionals = new ArrayList<>();
        for (JsonNode entry : node) {
            rejectUnknownKeys(entry, CONDITIONAL_KEYS,
                    "a 'createOnlySchemas' entry of object class '" + objectClass + "'");
            String urn = requireText(entry, "urn");
            JsonNode when = entry.get("when");
            if (when == null || !when.isObject()) {
                throw new ConfigurationException("The createOnlySchemas entry '" + urn + "' on object class '"
                        + objectClass + "' has no 'when' block. Write \"when\": { \"attribute\": \"userType\", "
                        + "\"in\": [\"agent\"] }; attribute-in-set is the only predicate there is.");
            }
            rejectUnknownKeys(when, WHEN_KEYS, "the 'when' block of createOnlySchemas entry '" + urn
                    + "' on object class '" + objectClass + "'");
            String attribute = requireText(when, "attribute");
            List<String> values = stringArray(when, "in");
            if (values.isEmpty()) {
                throw new ConfigurationException("The createOnlySchemas entry '" + urn + "' on object class '"
                        + objectClass + "' has an empty 'in' list. Write the attribute values that earn the URN, "
                        + "for example \"in\": [\"agent\", \"service\"].");
            }
            conditionals.add(new ConditionalSchema(urn, attribute, values,
                    bool(when, "caseInsensitive", false), text(entry, "configGate", null)));
        }
        return conditionals;
    }

    private static SchemaTypeAttribute parseAttribute(String objectClass, String name, JsonNode flags,
                                                      Map<String, String> namespaces) {
        if (Uid.NAME.equals(name)) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares an attribute named '"
                    + Uid.NAME + "'. The uid is declared with 'icfsUid'; remove the attribute entry.");
        }
        rejectUnknownKeys(flags, ATTRIBUTE_KEYS, "attribute '" + name + "' of object class '" + objectClass + "'");
        String rawPath = text(flags, "path", null);
        if (Name.NAME.equals(name) && rawPath != null) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares a 'path' on '"
                    + Name.NAME + "'. The name field is declared with 'icfsName'; keep only the flags here.");
        }
        String clearViaKey = text(flags, "clearVia", SchemaTypeAttribute.ClearVia.OMIT.key());
        SchemaTypeAttribute.ClearVia clearVia = SchemaTypeAttribute.ClearVia.byKey(clearViaKey);
        if (clearVia == null) {
            throw new ConfigurationException("Attribute '" + name + "' of object class '" + objectClass
                    + "' declares \"clearVia\": \"" + clearViaKey + "\". Write \""
                    + SchemaTypeAttribute.ClearVia.OMIT.key() + "\" when omitting the field clears it, or \""
                    + SchemaTypeAttribute.ClearVia.PATCH_ONLY.key()
                    + "\" when the server reads an absent field as leave alone.");
        }
        return new SchemaTypeAttribute(name,
                text(flags, "dataType", "String"),
                bool(flags, "multivalued", false),
                bool(flags, "returnedByDefault", true),
                bool(flags, "required", false),
                bool(flags, "creatable", false),
                bool(flags, "updateable", false),
                AttributePath.parse(objectClass, name, rawPath == null ? name : rawPath, namespaces),
                clearVia);
    }

    private static TargetInfo parseTarget(JsonNode node) {
        if (node == null || node.isNull()) {
            return TargetInfo.fallback();
        }
        if (!node.isObject()) {
            throw new ConfigurationException("The schema file declares 'target' as something other than an "
                    + "object. Write { \"name\": \"Your Server\", \"credentialHint\": \"how to mint a token\" }.");
        }
        rejectUnknownKeys(node, TARGET_KEYS, "the schema file's 'target' block");
        return new TargetInfo(
                text(node, "name", TargetInfo.fallback().getName()),
                text(node, "credentialHint", TargetInfo.fallback().getCredentialHint()));
    }

    private static SyncFeedConfig parseSyncFeedConfig(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        rejectUnknownKeys(node, FEED_KEYS, "the schema file's top-level 'sync' block");
        return new SyncFeedConfig(
                requireText(node, "feedEndpoint"),
                text(node, "sinceParam", "since"),
                text(node, "typesParam", "types"),
                text(node, "limitParam", "limit"),
                intVal(node, "pageLimit", 500),
                text(node, "recordsField", "changes"),
                text(node, "headField", "head"),
                text(node, "nextCursorField", "nextCursor"),
                text(node, "moreField", "more"),
                text(node, "cursorField", "cursor"),
                text(node, "typeField", "type"),
                text(node, "opField", "op"),
                text(node, "idField", "id"));
    }

    private static SyncType parseSyncType(String objectClass, JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        rejectUnknownKeys(node, SYNC_KEYS, "the 'sync' block of object class '" + objectClass + "'");
        List<String> requestTypes = stringArray(node, "requestTypes");
        if (requestTypes.isEmpty()) {
            throw new ConfigurationException("An object 'sync' block requires a non-empty 'requestTypes' array.");
        }
        return new SyncType(requestTypes,
                stringArray(node, "self"),
                stringArray(node, "delete"),
                stringArray(node, "reemitAll"),
                stringArray(node, "rederive"),
                text(node, "matchField", "uid"));
    }

    private static AssociationType parseAssociation(String objectClass, String attributeName, JsonNode node) {
        rejectUnknownKeys(node, ASSOCIATION_KEYS, "association '" + attributeName + "' of object class '"
                + objectClass + "'");
        Map<String, String> where = new LinkedHashMap<>();
        JsonNode whereNode = node.get("where");
        if (whereNode != null && whereNode.isObject()) {
            whereNode.properties().forEach(w -> where.put(w.getKey(), w.getValue().asText()));
        }
        return new AssociationType(attributeName,
                requireText(node, "endpoint"),
                requireText(node, "localField"),
                requireText(node, "matchField"),
                requireText(node, "valueField"),
                where);
    }

    // ---- contradiction checks ---------------------------------------------------

    private static void validate(SchemaType type, JsonNode obj) {
        String objectClass = type.getObjectClassName();
        Dialect dialect = type.getDialect();
        ObjectEndpoints endpoints = type.getEndpoints();

        if (!type.getPushableFilters().isEmpty() && !dialect.supportsPushedFilters()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'filters.pushable' but "
                    + "speaks the '" + dialect.name() + "' dialect, which has no server-side filter grammar. "
                    + "Remove the block so the connector narrows locally, or name a dialect that filters.");
        }
        if (type.getCoreSchema() != null && !dialect.supportsWrites()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'coreSchema' but speaks "
                    + "the '" + dialect.name() + "' dialect, which has no schema envelope. Remove the key, or "
                    + "name a dialect that has one.");
        }
        for (Operation write : List.of(Operation.CREATE, Operation.UPDATE, Operation.UPDATE_DELTA)) {
            if (type.supports(write) && !dialect.supportsWrites()) {
                throw new ConfigurationException("Object class '" + objectClass + "' lists the operation '"
                        + write.key() + "' but speaks the '" + dialect.name() + "' dialect, which has no write "
                        + "document form. Drop the operation, or name a dialect that writes.");
            }
        }

        requirePairing(objectClass, type, Operation.CREATE, endpoints.getCreate(), "create");
        requirePairing(objectClass, type, Operation.UPDATE, endpoints.getReplace(), "replace");
        requirePairing(objectClass, type, Operation.UPDATE_DELTA, endpoints.getPatch(), "patch");
        requirePairing(objectClass, type, Operation.DELETE, endpoints.getDelete(), "delete");

        if (type.supports(Operation.UPDATE) && endpoints.getById() == null) {
            throw new ConfigurationException("Object class '" + objectClass + "' lists the operation 'update' "
                    + "but declares no \"endpoints.byId\" path. A full-document update reads the current "
                    + "resource before it writes, so it needs one.");
        }

        if (type.supports(Operation.SYNC) != type.supportsSync()) {
            throw new ConfigurationException("Object class '" + objectClass + "' lists the operation 'sync' "
                    + (type.supports(Operation.SYNC) ? "without a 'sync' routing block. Add the block, or drop "
                    + "the operation." : "nowhere, yet declares a 'sync' routing block. Add \"sync\" to "
                    + "'operations', or drop the block."));
        }

        for (String connIdAttribute : type.getPushableFilters().keySet()) {
            if (Uid.NAME.equals(connIdAttribute) || Name.NAME.equals(connIdAttribute)
                    || type.getAttribute(connIdAttribute) != null) {
                continue;
            }
            throw new ConfigurationException("Object class '" + objectClass + "' declares the pushable filter '"
                    + connIdAttribute + "', which is not an attribute it has. Write " + Uid.NAME + ", "
                    + Name.NAME + ", or the ConnId name of one of the class's attributes; declare the attribute "
                    + "if it is missing, or remove the entry.");
        }

        SchemaTypeAttribute nameAttribute = type.getNameAttribute();
        if (nameAttribute != null && nameAttribute.isMultivalued()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares '" + Name.NAME
                    + "' as multivalued. A ConnId name is a single value; remove the flag.");
        }
        for (ConditionalSchema conditional : type.getCreateOnlySchemas()) {
            if (type.getAttribute(conditional.getAttribute()) == null) {
                throw new ConfigurationException("The createOnlySchemas entry '" + conditional.getUrn()
                        + "' on object class '" + objectClass + "' reads the attribute '"
                        + conditional.getAttribute() + "', which the class does not declare. Declare the "
                        + "attribute, or point 'when.attribute' at one that exists.");
            }
        }
        if (!type.getCreateOnlySchemas().isEmpty() && !type.supports(Operation.CREATE)) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares 'createOnlySchemas' but "
                    + "does not list the 'create' operation, so the block can never fire. Add the operation, or "
                    + "remove the block.");
        }
        if (obj.has("enable") && type.getEnablePath() == null) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares an empty 'enable'. "
                    + "Write the wire path ConnId __ENABLE__ maps to, for example \"enable\": \"active\".");
        }
    }

    private static void requirePairing(String objectClass, SchemaType type, Operation operation,
                                       String endpoint, String endpointKey) {
        boolean declared = type.supports(operation);
        boolean routed = endpoint != null;
        if (declared && !routed) {
            throw new ConfigurationException("Object class '" + objectClass + "' lists the operation '"
                    + operation.key() + "' but declares no \"endpoints." + endpointKey + "\" path. Add the path, "
                    + "or drop the operation.");
        }
        if (!declared && routed) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares \"endpoints."
                    + endpointKey + "\" but does not list the operation '" + operation.key()
                    + "', so the path can never be used. Add the operation, or remove the path.");
        }
    }

    // ---- accessors --------------------------------------------------------------

    public Map<String, SchemaType> getSchemaTypes() {
        return schemaTypes;
    }

    /** The feed config, or {@code null} without a top-level {@code sync} block. */
    public SyncFeedConfig getSyncFeedConfig() {
        return syncFeedConfig;
    }

    /** The {@code target} block, or its fallback. */
    public TargetInfo getTargetInfo() {
        return targetInfo;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    // ---- helpers ----------------------------------------------------------------

    /** Throws on any key not in {@code known}, ignoring {@code //} comment keys. */
    private static void rejectUnknownKeys(JsonNode node, Set<String> known, String where) {
        if (node == null || !node.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String key = entry.getKey();
            if (!key.startsWith("//") && !known.contains(key)) {
                List<String> sorted = new ArrayList<>(known);
                sorted.sort(String::compareTo);
                throw new ConfigurationException("Unknown key '" + key + "' in " + where
                        + ". The keys understood here are " + String.join(", ", sorted)
                        + "; a key starting with // is a comment.");
            }
        }
    }

    private static List<String> stringArray(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        JsonNode array = node.get(field);
        if (array != null && array.isArray()) {
            for (JsonNode element : array) {
                if (element != null && !element.isNull()) {
                    values.add(element.asText());
                }
            }
        }
        return values;
    }

    private static int intVal(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ConfigurationException("Schema object is missing required string field '" + field + "'.");
        }
        return value.asText();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asBoolean();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
