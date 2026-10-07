package ai.straza.connector.rest.objects;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import ai.straza.connector.rest.schema.AttributePath;
import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.schema.SchemaTypeAttribute;

import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.Uid;

/** Maps a resource document to a {@link ConnectorObject} through the class's attribute paths. */
public final class ResourceMapper {

    private ResourceMapper() {
    }

    /** The ConnId object class for {@code name}; the reserved names map to the framework constants. */
    public static ObjectClass objectClassOf(String name) {
        if (ObjectClass.ACCOUNT_NAME.equalsIgnoreCase(name)) {
            return ObjectClass.ACCOUNT;
        }
        if (ObjectClass.GROUP_NAME.equalsIgnoreCase(name)) {
            return ObjectClass.GROUP;
        }
        return new ObjectClass(name);
    }

    public static ConnectorObject toConnectorObject(SchemaType type, JsonNode resource) {
        return toConnectorObject(type, resource, Map.of());
    }

    /** Maps a resource document, adding the resolved association values. */
    public static ConnectorObject toConnectorObject(SchemaType type, JsonNode resource,
                                                    Map<String, List<Object>> associationValues) {
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        builder.setObjectClass(objectClassOf(type.getObjectClassName()));

        String uid = single(resource, type.getUidPath());
        if (uid == null) {
            throw new IllegalStateException("Object is missing UID field '" + type.getIcfsUid()
                    + "' for object class " + type.getObjectClassName());
        }
        String name = single(resource, type.getNamePath());
        builder.setUid(new Uid(uid));
        builder.setName(new Name(name != null ? name : uid));

        AttributePath enablePath = type.getEnablePath();
        if (enablePath != null) {
            List<Object> enabled = PathAccess.read(resource, enablePath, Boolean.class);
            if (!enabled.isEmpty()) {
                builder.addAttribute(AttributeBuilder.buildEnabled((Boolean) enabled.get(0)));
            }
        }

        for (SchemaTypeAttribute attribute : type.getAttributes()) {
            List<Object> values = PathAccess.read(resource, attribute.getPath(), attribute.getDataType());
            if (!values.isEmpty()) {
                builder.addAttribute(AttributeBuilder.build(attribute.getName(), values));
            }
        }

        for (Map.Entry<String, List<Object>> association : associationValues.entrySet()) {
            if (!association.getValue().isEmpty()) {
                builder.addAttribute(AttributeBuilder.build(association.getKey(), association.getValue()));
            }
        }
        return builder.build();
    }

    /** The first value at {@code path} as a string, or {@code null}. */
    public static String single(JsonNode resource, AttributePath path) {
        List<Object> values = PathAccess.read(resource, path, String.class);
        return values.isEmpty() ? null : String.valueOf(values.get(0));
    }
}
