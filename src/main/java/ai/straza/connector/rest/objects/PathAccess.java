package ai.straza.connector.rest.objects;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ai.straza.connector.rest.schema.AttributePath;

/**
 * Reads and writes attribute values at an {@link AttributePath}. A missing field,
 * a JSON null and an empty string all read as no value.
 */
public final class PathAccess {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PathAccess() {
    }

    /** The values at {@code path}, converted to {@code dataType}. */
    public static List<Object> read(JsonNode resource, AttributePath path, Class<?> dataType) {
        JsonNode container = container(resource, path);
        List<Object> values = new ArrayList<>();
        switch (path.form()) {
            case FIELD:
                JsonNode field = container.path(path.field());
                if (field.isArray()) {
                    for (JsonNode element : field) {
                        addIfPresent(values, element, dataType);
                    }
                } else {
                    addIfPresent(values, field, dataType);
                }
                break;
            case ARRAY:
                for (JsonNode element : container.path(path.field())) {
                    addIfPresent(values, element.path(path.elementField()), dataType);
                }
                break;
            case FILTERED_ARRAY:
                JsonNode selected = select(container.path(path.field()), path);
                if (selected != null) {
                    addIfPresent(values, selected.path(path.elementField()), dataType);
                }
                break;
            default:
                break;
        }
        return values;
    }

    /** Writes {@code values} at {@code path}, creating the namespace block and array as needed. */
    public static void write(ObjectNode document, AttributePath path, List<Object> values, Class<?> dataType,
                             boolean multivalued) {
        ObjectNode target = writableContainer(document, path);
        switch (path.form()) {
            case FIELD:
                if (multivalued) {
                    ArrayNode array = target.putArray(path.field());
                    values.forEach(value -> array.add(scalar(value, dataType)));
                } else if (!values.isEmpty()) {
                    target.set(path.field(), scalar(values.get(0), dataType));
                }
                break;
            case ARRAY:
                ArrayNode elements = target.putArray(path.field());
                values.forEach(value -> elements.addObject().set(path.elementField(), scalar(value, dataType)));
                break;
            case FILTERED_ARRAY:
                ArrayNode filtered = target.putArray(path.field());
                for (Object value : values) {
                    ObjectNode element = filtered.addObject();
                    element.set(path.elementField(), scalar(value, dataType));
                    element.set(path.selectorField(), literal(path.selectorValue()));
                }
                break;
            default:
                break;
        }
    }

    /**
     * Copies the array at {@code path} from {@code current} unchanged; a missing
     * multivalued array is written as an empty array.
     */
    public static void carry(ObjectNode document, JsonNode current, AttributePath path, boolean multivalued) {
        JsonNode array = container(current, path).path(path.field());
        if (!array.isArray()) {
            if (multivalued) {
                writableContainer(document, path).putArray(path.field());
            }
            return;
        }
        writableContainer(document, path).set(path.field(), array.deepCopy());
    }

    /** Removes the field at {@code path}, and its namespace block when that is left empty. */
    public static void remove(ObjectNode document, AttributePath path) {
        if (path.namespace() == null) {
            document.remove(path.field());
            return;
        }
        if (document.get(path.namespace()) instanceof ObjectNode block) {
            block.remove(path.field());
            if (block.isEmpty()) {
                document.remove(path.namespace());
            }
        }
    }

    /**
     * The value of a patch operation: a scalar for a single-valued field, otherwise
     * the array the path wraps, without the namespace.
     */
    public static JsonNode deltaValue(AttributePath path, List<Object> values, Class<?> dataType,
                                      boolean multivalued) {
        if (path.form() == AttributePath.Form.FIELD && !multivalued) {
            return scalar(values.get(0), dataType);
        }
        ObjectNode holder = MAPPER.createObjectNode();
        switch (path.form()) {
            case FIELD:
                ArrayNode array = holder.putArray(path.field());
                values.forEach(value -> array.add(scalar(value, dataType)));
                break;
            case ARRAY:
                ArrayNode elements = holder.putArray(path.field());
                values.forEach(value -> elements.addObject().set(path.elementField(), scalar(value, dataType)));
                break;
            case FILTERED_ARRAY:
                ArrayNode filtered = holder.putArray(path.field());
                for (Object value : values) {
                    ObjectNode element = filtered.addObject();
                    element.set(path.elementField(), scalar(value, dataType));
                    element.set(path.selectorField(), literal(path.selectorValue()));
                }
                break;
            default:
                break;
        }
        return holder.get(path.field());
    }

    /** The namespace block for {@code path}, or the resource itself. */
    private static JsonNode container(JsonNode resource, AttributePath path) {
        if (resource == null) {
            return MAPPER.missingNode();
        }
        return path.namespace() == null ? resource : resource.path(path.namespace());
    }

    private static ObjectNode writableContainer(ObjectNode document, AttributePath path) {
        if (path.namespace() == null) {
            return document;
        }
        JsonNode existing = document.get(path.namespace());
        if (existing instanceof ObjectNode block) {
            return block;
        }
        return document.putObject(path.namespace());
    }

    private static JsonNode select(JsonNode array, AttributePath path) {
        if (!array.isArray() || array.isEmpty()) {
            return null;
        }
        for (JsonNode element : array) {
            if (literal(path.selectorValue()).equals(normalize(element.path(path.selectorField()),
                    path.selectorValue()))) {
                return element;
            }
        }
        return array.get(0);
    }

    /** Converts a string selector value to the type of the declared literal. */
    private static JsonNode normalize(JsonNode actual, String declared) {
        JsonNode expected = literal(declared);
        if (expected.isBoolean() && actual.isTextual()) {
            return MAPPER.getNodeFactory().booleanNode(Boolean.parseBoolean(actual.asText()));
        }
        if (expected.isNumber() && actual.isTextual()) {
            return MAPPER.getNodeFactory().numberNode(Long.parseLong(actual.asText()));
        }
        return actual;
    }

    /** Parses a declared selector value as a boolean, a number or a string. */
    private static JsonNode literal(String declared) {
        if ("true".equals(declared) || "false".equals(declared)) {
            return MAPPER.getNodeFactory().booleanNode(Boolean.parseBoolean(declared));
        }
        try {
            return MAPPER.getNodeFactory().numberNode(Long.parseLong(declared));
        } catch (NumberFormatException e) {
            return MAPPER.getNodeFactory().textNode(declared);
        }
    }

    private static void addIfPresent(List<Object> values, JsonNode node, Class<?> dataType) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isContainerNode()) {
            return;
        }
        if (dataType == Boolean.class) {
            values.add(node.asBoolean());
        } else if (dataType == Integer.class) {
            values.add(node.asInt());
        } else if (dataType == Long.class) {
            values.add(node.asLong());
        } else {
            String text = node.asText();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
    }

    private static JsonNode scalar(Object value, Class<?> dataType) {
        if (dataType == Boolean.class) {
            return MAPPER.getNodeFactory().booleanNode(value instanceof Boolean bool
                    ? bool : Boolean.parseBoolean(String.valueOf(value)));
        }
        if (dataType == Integer.class) {
            return MAPPER.getNodeFactory().numberNode(value instanceof Number number
                    ? number.intValue() : Integer.parseInt(String.valueOf(value)));
        }
        if (dataType == Long.class) {
            return MAPPER.getNodeFactory().numberNode(value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value)));
        }
        return MAPPER.getNodeFactory().textNode(String.valueOf(value));
    }
}
