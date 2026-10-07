package ai.straza.connector.rest.schema;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.identityconnectors.framework.common.exceptions.ConfigurationException;

/**
 * Where an attribute lives in a resource document: a field ({@code userName}), a
 * namespaced field ({@code alias:roleKind}), every element of an array
 * ({@code members[].value}), or the element whose selector matches, else the
 * first ({@code emails[primary=true].value}). A namespace prefix also works with
 * the array forms.
 */
public final class AttributePath {

    public enum Form {
        /** A single field, possibly inside a namespace block. */
        FIELD,
        /** Every element of an array, read through one element field. */
        ARRAY,
        /** The element whose selector field matches, read through one element field. */
        FILTERED_ARRAY
    }

    private static final Pattern ARRAY_FORM = Pattern.compile("^([^\\[\\].]+)\\[([^\\[\\]]*)]\\.([^\\[\\].]+)$");

    private final String raw;
    private final String namespace;
    private final String field;
    private final String elementField;
    private final String selectorField;
    private final String selectorValue;
    private final Form form;

    private AttributePath(String raw, String namespace, String field, String elementField,
                          String selectorField, String selectorValue, Form form) {
        this.raw = raw;
        this.namespace = namespace;
        this.field = field;
        this.elementField = elementField;
        this.selectorField = selectorField;
        this.selectorValue = selectorValue;
        this.form = form;
    }

    /** Parses {@code raw}, resolving a namespace alias against {@code namespaces}. */
    public static AttributePath parse(String objectClass, String attributeName, String raw,
                                      Map<String, String> namespaces) {
        if (raw == null || raw.isBlank()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares an empty 'path' on "
                    + "attribute '" + attributeName + "'. Write the wire field name, for example "
                    + "\"path\": \"" + attributeName + "\".");
        }
        String remainder = raw;
        String namespace = null;
        int colon = raw.indexOf(':');
        if (colon > 0) {
            String alias = raw.substring(0, colon);
            if (!namespaces.containsKey(alias)) {
                throw new ConfigurationException("Object class '" + objectClass + "' declares path '" + raw
                        + "' on attribute '" + attributeName + "', but it has no namespace alias '" + alias
                        + "'. Add \"" + alias + "\" to the object class's 'namespaces' block, or drop the prefix.");
            }
            namespace = namespaces.get(alias);
            remainder = raw.substring(colon + 1);
        }
        if (remainder.indexOf('[') < 0 && remainder.indexOf(']') < 0) {
            if (remainder.indexOf('.') >= 0) {
                throw new ConfigurationException("Object class '" + objectClass + "' declares path '" + raw
                        + "' on attribute '" + attributeName + "'. A dotted path is only understood inside an "
                        + "array form such as \"" + remainder.replace('.', '_') + "[].value\"; a plain field "
                        + "carries no dot.");
            }
            return new AttributePath(raw, namespace, remainder, null, null, null, Form.FIELD);
        }
        Matcher matcher = ARRAY_FORM.matcher(remainder);
        if (!matcher.matches()) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares path '" + raw
                    + "' on attribute '" + attributeName + "', which is not a form this connector understands. "
                    + "Write a field (\"userName\"), a namespaced field (\"alias:roleKind\"), or an array "
                    + "element (\"members[].value\" or \"emails[primary=true].value\").");
        }
        String arrayField = matcher.group(1);
        String selector = matcher.group(2);
        String elementField = matcher.group(3);
        if (selector.isEmpty()) {
            return new AttributePath(raw, namespace, arrayField, elementField, null, null, Form.ARRAY);
        }
        int equals = selector.indexOf('=');
        if (equals <= 0 || equals == selector.length() - 1) {
            throw new ConfigurationException("Object class '" + objectClass + "' declares path '" + raw
                    + "' on attribute '" + attributeName + "' with the selector '" + selector
                    + "'. A selector is one field and one value, for example \"" + arrayField
                    + "[primary=true]." + elementField + "\", or empty to take every element.");
        }
        return new AttributePath(raw, namespace, arrayField, elementField,
                selector.substring(0, equals), selector.substring(equals + 1), Form.FILTERED_ARRAY);
    }

    /** The path as written in the schema file. */
    public String raw() {
        return raw;
    }

    /** The namespace URN, or {@code null} for a top-level field. */
    public String namespace() {
        return namespace;
    }

    /** The field name, or the array name for an array form. */
    public String field() {
        return field;
    }

    /** The field read from each array element, or {@code null} for {@link Form#FIELD}. */
    public String elementField() {
        return elementField;
    }

    /** The selector field of a {@link Form#FILTERED_ARRAY}, or {@code null}. */
    public String selectorField() {
        return selectorField;
    }

    /** The selector value of a {@link Form#FILTERED_ARRAY}, or {@code null}. */
    public String selectorValue() {
        return selectorValue;
    }

    public Form form() {
        return form;
    }

    /** True when the value lives inside an array of objects. */
    public boolean isArray() {
        return form != Form.FIELD;
    }

    @Override
    public String toString() {
        return raw;
    }
}
