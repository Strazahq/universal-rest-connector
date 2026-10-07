package ai.straza.connector.rest.objects;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import ai.straza.connector.rest.schema.AssociationType;
import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.schema.SchemaTypeAttribute;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationalAttributeInfos;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.Uid;

/** Builds object class definitions, derived-class members and association values. */
public final class UniversalObjectsHandler {

    private static final Log LOG = Log.getLog(UniversalObjectsHandler.class);

    private UniversalObjectsHandler() {
    }

    /** Defines the class on {@code schemaBuilder} and returns its definition. */
    public static ObjectClassInfo buildObjectClass(SchemaBuilder schemaBuilder, SchemaType schemaType) {
        ObjectClassInfoBuilder objectClassBuilder = new ObjectClassInfoBuilder();
        objectClassBuilder.setType(schemaType.getObjectClassName());

        SchemaTypeAttribute nameAttribute = schemaType.getNameAttribute();
        AttributeInfoBuilder nameBuilder = new AttributeInfoBuilder(Name.NAME, String.class);
        nameBuilder.setCreateable(nameAttribute != null && nameAttribute.isCreatable());
        nameBuilder.setUpdateable(nameAttribute != null && nameAttribute.isUpdateable());
        nameBuilder.setRequired(nameAttribute != null && nameAttribute.isRequired());
        nameBuilder.setReadable(true);
        objectClassBuilder.addAttributeInfo(nameBuilder.build());

        if (schemaType.getEnablePath() != null) {
            objectClassBuilder.addAttributeInfo(OperationalAttributeInfos.ENABLE);
        }

        if (schemaType.isDerived()) {
            AttributeInfoBuilder refBuilder = new AttributeInfoBuilder(
                    schemaType.getDerived().getParentRefAttribute(), String.class);
            refBuilder.setCreateable(false);
            refBuilder.setUpdateable(false);
            refBuilder.setReadable(true);
            objectClassBuilder.addAttributeInfo(refBuilder.build());
        }

        for (SchemaTypeAttribute attribute : schemaType.getAttributes()) {
            AttributeInfoBuilder attributeBuilder = new AttributeInfoBuilder(attribute.getName(), attribute.getDataType());
            attributeBuilder.setCreateable(attribute.isCreatable());
            attributeBuilder.setUpdateable(attribute.isUpdateable());
            attributeBuilder.setRequired(attribute.isRequired());
            attributeBuilder.setReadable(true);
            attributeBuilder.setMultiValued(attribute.isMultivalued());
            attributeBuilder.setReturnedByDefault(attribute.isReturnedByDefault());
            objectClassBuilder.addAttributeInfo(attributeBuilder.build());
        }

        for (AssociationType association : schemaType.getAssociations()) {
            AttributeInfoBuilder associationBuilder = new AttributeInfoBuilder(association.getAttributeName(), String.class);
            associationBuilder.setCreateable(false);
            associationBuilder.setUpdateable(false);
            associationBuilder.setReadable(true);
            associationBuilder.setMultiValued(true);
            associationBuilder.setReturnedByDefault(true);
            objectClassBuilder.addAttributeInfo(associationBuilder.build());
        }

        LOG.ok("building object class {0}", schemaType.getObjectClassName());
        ObjectClassInfo objectClassInfo = objectClassBuilder.build();
        schemaBuilder.defineObjectClass(objectClassInfo);
        return objectClassInfo;
    }

    /** A derived-class object whose uid and name are the array element. */
    public static ConnectorObject toDerivedObject(SchemaType schemaType, String elementId, String parentRefValue) {
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        builder.setObjectClass(ResourceMapper.objectClassOf(schemaType.getObjectClassName()));
        builder.setUid(new Uid(elementId));
        builder.setName(new Name(elementId));
        if (parentRefValue != null) {
            builder.addAttribute(AttributeBuilder.build(schemaType.getDerived().getParentRefAttribute(), parentRefValue));
        }
        return builder.build();
    }

    /** Association values for {@code object}, keyed by attribute name, from pre-fetched rows. */
    public static Map<String, List<Object>> resolveAssociations(SchemaType schemaType, JsonNode object,
                                                                Map<String, List<JsonNode>> endpointRows) {
        Map<String, List<Object>> result = new LinkedHashMap<>();
        for (AssociationType association : schemaType.getAssociations()) {
            String localValue = textField(object, association.getLocalField());
            List<Object> values = new ArrayList<>();
            if (localValue != null) {
                for (JsonNode related : endpointRows.getOrDefault(association.getEndpoint(), List.of())) {
                    if (matchesWhere(related, association.getWhere())
                            && localValue.equals(textField(related, association.getMatchField()))) {
                        String value = textField(related, association.getValueField());
                        if (value != null) {
                            values.add(value);
                        }
                    }
                }
            }
            result.put(association.getAttributeName(), values);
        }
        return result;
    }

    private static boolean matchesWhere(JsonNode node, Map<String, String> where) {
        if (where == null || where.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> constraint : where.entrySet()) {
            if (!constraint.getValue().equals(textField(node, constraint.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String textField(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

}
