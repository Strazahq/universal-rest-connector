package ai.straza.connector.rest.objects;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ai.straza.connector.rest.dialect.Dialect;
import ai.straza.connector.rest.schema.AttributePath;
import ai.straza.connector.rest.schema.ConditionalSchema;
import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.schema.SchemaTypeAttribute;

import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.Uid;

/**
 * Builds create, replace and patch documents from ConnId attributes. An attribute
 * the class does not declare writable is refused before a request is built.
 */
public final class ResourceWriter {

    private ResourceWriter() {
    }

    /**
     * The create document. Empty attributes are omitted; a create-only schema URN
     * is added when its condition holds and its gate is on.
     */
    public static ObjectNode toCreateDocument(SchemaType type, Set<Attribute> attributes,
                                              Predicate<String> gateEnabled) {
        Dialect dialect = type.getDialect();
        ObjectNode document = dialect.newResourceDocument(type.getCoreSchema());

        String name = null;
        for (Attribute attribute : attributes) {
            String attrName = attribute.getName();
            if (Uid.NAME.equals(attrName)) {
                throw new InvalidAttributeValueException(
                        "__UID__ is server-assigned and cannot be set on create.");
            }
            if (Name.NAME.equals(attrName)) {
                name = isEmpty(attribute) ? null : String.valueOf(attribute.getValue().get(0));
                continue;
            }
            if (OperationalAttributes.ENABLE_NAME.equals(attrName)) {
                requireEnablePath(type);
                if (isEmpty(attribute)) {
                    continue;
                }
                PathAccess.write(document, type.getEnablePath(), List.of(toBoolean(attribute)),
                        Boolean.class, false);
                continue;
            }
            SchemaTypeAttribute spec = require(type, attrName);
            if (!spec.isCreatable()) {
                throw new InvalidAttributeValueException(refusal(type, attrName, "creatable"));
            }
            if (isEmpty(attribute)) {
                continue;
            }
            PathAccess.write(document, spec.getPath(), attribute.getValue(), spec.getDataType(),
                    spec.isMultivalued());
        }

        SchemaTypeAttribute nameSpec = type.getNameAttribute();
        if (name != null && (nameSpec == null || !nameSpec.isCreatable())) {
            throw new InvalidAttributeValueException(refusal(type, Name.NAME, "creatable"));
        }
        if (nameSpec != null && nameSpec.isRequired() && (name == null || name.isBlank())) {
            throw new InvalidAttributeValueException("__NAME__ is required to create an object of class "
                    + type.getObjectClassName() + "; nothing was sent.");
        }
        if (name != null) {
            PathAccess.write(document, type.getNamePath(), List.of(name), String.class, false);
        }

        declareNamespaces(type, dialect, document);
        for (ConditionalSchema conditional : type.getCreateOnlySchemas()) {
            if (conditional.getConfigGate() != null && !gateEnabled.test(conditional.getConfigGate())) {
                continue;
            }
            SchemaTypeAttribute spec = type.getAttribute(conditional.getAttribute());
            if (conditional.matches(ResourceMapper.single(document, spec.getPath()))) {
                dialect.addSchema(document, conditional.getUrn());
            }
        }
        return document;
    }

    /**
     * The full replacement document: the current writable attributes with the
     * replacements applied. Clearing a {@code patchOnly} attribute is refused.
     */
    public static ObjectNode toReplaceDocument(SchemaType type, JsonNode current, Set<Attribute> replacements) {
        Dialect dialect = type.getDialect();
        ObjectNode document = dialect.newResourceDocument(type.getCoreSchema());

        AttributePath enablePath = type.getEnablePath();
        if (enablePath != null) {
            List<Object> enabled = PathAccess.read(current, enablePath, Boolean.class);
            PathAccess.write(document, enablePath,
                    List.of(enabled.isEmpty() ? Boolean.TRUE : enabled.get(0)), Boolean.class, false);
        }
        for (SchemaTypeAttribute spec : type.getAttributes()) {
            if (!spec.isWritable()) {
                continue;
            }
            if (spec.getPath().isArray()) {
                // Copy arrays as rendered so unmapped entries and fields survive.
                PathAccess.carry(document, current, spec.getPath(), spec.isMultivalued());
                continue;
            }
            List<Object> values = PathAccess.read(current, spec.getPath(), spec.getDataType());
            if (spec.isMultivalued() || !values.isEmpty()) {
                PathAccess.write(document, spec.getPath(), values, spec.getDataType(), spec.isMultivalued());
            }
        }
        String name = ResourceMapper.single(current, type.getNamePath());

        for (Attribute attribute : replacements) {
            String attrName = attribute.getName();
            if (Uid.NAME.equals(attrName)) {
                throw new InvalidAttributeValueException("__UID__ is server-assigned and never writable.");
            }
            if (Name.NAME.equals(attrName)) {
                name = applyName(type, attribute, name);
                continue;
            }
            if (OperationalAttributes.ENABLE_NAME.equals(attrName)) {
                PathAccess.write(document, type.getEnablePath(),
                        List.of(requireEnable(type, attribute)), Boolean.class, false);
                continue;
            }
            SchemaTypeAttribute spec = require(type, attrName);
            if (!spec.isUpdateable()) {
                throw new InvalidAttributeValueException(refusal(type, attrName, "updateable"));
            }
            if (isEmpty(attribute)) {
                if (spec.isMultivalued()) {
                    // Send an empty array; an absent field may mean "leave alone".
                    PathAccess.write(document, spec.getPath(), List.of(), spec.getDataType(), true);
                    continue;
                }
                if (spec.getClearVia() == SchemaTypeAttribute.ClearVia.PATCH_ONLY) {
                    throw new InvalidAttributeValueException("Clearing " + attrName + " on object class "
                            + type.getObjectClassName() + " is a delta operation: this server reads an absent "
                            + "field in a full-document update as leave alone, so omitting it would not clear it.");
                }
                PathAccess.remove(document, spec.getPath());
            } else {
                PathAccess.write(document, spec.getPath(), attribute.getValue(), spec.getDataType(),
                        spec.isMultivalued());
            }
        }

        if (type.isNameWritable()) {
            if (name == null || name.isBlank()) {
                throw new InvalidAttributeValueException("__NAME__ cannot be cleared on object class "
                        + type.getObjectClassName() + ".");
            }
            PathAccess.write(document, type.getNamePath(), List.of(name), String.class, false);
        }
        declareNamespaces(type, dialect, document);
        return document;
    }

    /**
     * The patch document. Single-valued attributes take replace or clear;
     * multivalued ones take add, replace and per-value remove.
     */
    public static ObjectNode toPatchDocument(SchemaType type, Set<AttributeDelta> deltas) {
        Dialect dialect = type.getDialect();
        ObjectNode patch = dialect.newPatchDocument();

        for (AttributeDelta delta : deltas) {
            String attrName = delta.getName();
            if (OperationalAttributes.ENABLE_NAME.equals(attrName)) {
                requireEnablePath(type);
                List<Object> values = delta.getValuesToReplace();
                if (isClear(values)) {
                    throw new InvalidAttributeValueException("__ENABLE__ requires a boolean value.");
                }
                AttributePath path = type.getEnablePath();
                dialect.addOperation(patch, "replace", dialect.qualifiedPath(path.namespace(), path.field()),
                        PathAccess.deltaValue(path, List.of(values.get(0)), Boolean.class, false));
                continue;
            }
            if (Name.NAME.equals(attrName)) {
                if (!type.isNameWritable()) {
                    throw new InvalidAttributeValueException(renameRefusal(type));
                }
                List<Object> values = delta.getValuesToReplace();
                if (isClear(values)) {
                    throw new InvalidAttributeValueException("__NAME__ cannot be cleared on object class "
                            + type.getObjectClassName() + ".");
                }
                AttributePath path = type.getNamePath();
                dialect.addOperation(patch, "replace", dialect.qualifiedPath(path.namespace(), path.field()),
                        PathAccess.deltaValue(path, List.of(values.get(0)), String.class, false));
                continue;
            }
            SchemaTypeAttribute spec = require(type, attrName);
            if (!spec.isUpdateable()) {
                throw new InvalidAttributeValueException(refusal(type, attrName, "updateable"));
            }
            String path = dialect.qualifiedPath(spec.getPath().namespace(), spec.getPath().field());
            if (spec.isMultivalued()) {
                addMultivaluedOperations(dialect, patch, spec, path, delta);
            } else {
                addSingleValuedOperation(dialect, patch, type, spec, path, delta);
            }
        }
        return patch;
    }

    // ---- helpers ------------------------------------------------------------------

    private static void addMultivaluedOperations(Dialect dialect, ObjectNode patch, SchemaTypeAttribute spec,
                                                 String path, AttributeDelta delta) {
        if (delta.getValuesToReplace() != null) {
            dialect.addOperation(patch, "replace", path, PathAccess.deltaValue(spec.getPath(),
                    delta.getValuesToReplace(), spec.getDataType(), true));
        }
        if (delta.getValuesToAdd() != null && !delta.getValuesToAdd().isEmpty()) {
            dialect.addOperation(patch, "add", path, PathAccess.deltaValue(spec.getPath(),
                    delta.getValuesToAdd(), spec.getDataType(), true));
        }
        if (delta.getValuesToRemove() != null) {
            String elementField = spec.getPath().elementField() == null ? "value" : spec.getPath().elementField();
            for (Object value : delta.getValuesToRemove()) {
                dialect.addOperation(patch, "remove",
                        dialect.valueFilterPath(path, elementField, String.valueOf(value)), null);
            }
        }
    }

    private static void addSingleValuedOperation(Dialect dialect, ObjectNode patch, SchemaType type,
                                                 SchemaTypeAttribute spec, String path, AttributeDelta delta) {
        boolean hasAddOrRemove = (delta.getValuesToAdd() != null && !delta.getValuesToAdd().isEmpty())
                || (delta.getValuesToRemove() != null && !delta.getValuesToRemove().isEmpty());
        if (hasAddOrRemove) {
            throw new InvalidAttributeValueException("Attribute '" + delta.getName()
                    + "' is single-valued on object class " + type.getObjectClassName()
                    + ": use replace, not add or remove values.");
        }
        List<Object> values = delta.getValuesToReplace();
        if (isClear(values)) {
            dialect.addOperation(patch, "remove", path, null);
            return;
        }
        dialect.addOperation(patch, "replace", path,
                PathAccess.deltaValue(spec.getPath(), List.of(values.get(0)), spec.getDataType(), false));
    }

    /** A writable name takes the new value; a read-only name accepts only its current value. */
    private static String applyName(SchemaType type, Attribute attribute, String current) {
        String requested = isEmpty(attribute) ? null : String.valueOf(attribute.getValue().get(0));
        if (type.isNameWritable()) {
            return requested;
        }
        if (requested == null || !requested.equals(current)) {
            throw new InvalidAttributeValueException(renameRefusal(type));
        }
        return current;
    }

    /** Adds the URN of each non-empty namespace block to the envelope. */
    private static void declareNamespaces(SchemaType type, Dialect dialect, ObjectNode document) {
        for (String urn : type.getNamespaces().values()) {
            JsonNode block = document.get(urn);
            if (block != null && block.isObject() && !block.isEmpty()) {
                dialect.addSchema(document, urn);
            }
        }
    }

    private static SchemaTypeAttribute require(SchemaType type, String attrName) {
        SchemaTypeAttribute spec = type.getAttribute(attrName);
        if (spec == null) {
            throw new InvalidAttributeValueException("Attribute '" + attrName
                    + "' is not declared on object class " + type.getObjectClassName() + "; never sent.");
        }
        return spec;
    }

    private static Boolean requireEnable(SchemaType type, Attribute attribute) {
        requireEnablePath(type);
        if (isEmpty(attribute)) {
            throw new InvalidAttributeValueException("__ENABLE__ requires a boolean value.");
        }
        return toBoolean(attribute);
    }

    private static Boolean toBoolean(Attribute attribute) {
        Object value = attribute.getValue().get(0);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static void requireEnablePath(SchemaType type) {
        if (type.getEnablePath() == null) {
            throw new InvalidAttributeValueException("Object class " + type.getObjectClassName()
                    + " does not map __ENABLE__: the schema file declares no 'enable' path for it; never sent.");
        }
    }

    /** The rename refusal, with the class's {@code rename} hint appended. */
    private static String renameRefusal(SchemaType type) {
        String hint = type.getOperationHint("rename");
        return refusal(type, Name.NAME, "updateable") + (hint == null ? "" : " " + hint);
    }

    private static String refusal(SchemaType type, String attrName, String flag) {
        return "Attribute '" + attrName + "' is not " + flag + " on object class "
                + type.getObjectClassName() + ": the schema file declares it read-only; never sent.";
    }

    private static boolean isEmpty(Attribute attribute) {
        return attribute.getValue() == null || attribute.getValue().isEmpty()
                || attribute.getValue().get(0) == null;
    }

    private static boolean isClear(List<Object> values) {
        return values == null || values.isEmpty() || values.get(0) == null;
    }
}
