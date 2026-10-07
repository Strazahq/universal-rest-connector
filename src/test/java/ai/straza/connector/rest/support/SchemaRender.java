package ai.straza.connector.rest.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.Schema;

/**
 * Renders a ConnId {@link ObjectClassInfo} as sorted lines, one per attribute with
 * its type and flags, for comparison with a golden capture.
 */
public final class SchemaRender {

    private SchemaRender() {
    }

    /** One line per attribute, sorted by name, preceded by the object-class header line. */
    public static List<String> render(ObjectClassInfo info) {
        List<String> lines = new ArrayList<>();
        lines.add("objectClass " + info.getType() + " container=" + info.isContainer());
        lines.addAll(info.getAttributeInfo().stream().map(SchemaRender::renderAttribute).sorted().toList());
        return lines;
    }

    /** The object class of {@code type} in {@code schema}, rendered. */
    public static List<String> render(Schema schema, String type) {
        ObjectClassInfo info = schema.getObjectClassInfo().stream()
                .filter(candidate -> candidate.getType().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("schema has no object class " + type));
        return render(info);
    }

    private static String renderAttribute(AttributeInfo attribute) {
        Set<String> flags = attribute.getFlags().stream()
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        return attribute.getName()
                + " type=" + attribute.getType().getName()
                + " flags=" + String.join(",", flags);
    }

    /** Reads a golden capture from the test classpath, ignoring blank lines. */
    public static List<String> golden(String name) {
        String resource = "/golden/" + name + ".txt";
        try (InputStream in = SchemaRender.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("missing golden capture " + resource);
            }
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new AssertionError("cannot read golden capture " + resource, e);
        }
    }
}
