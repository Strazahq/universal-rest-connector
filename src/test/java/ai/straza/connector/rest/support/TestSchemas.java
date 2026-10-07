package ai.straza.connector.rest.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Absolute paths of schema files for tests. The connector reads its schema from
 * disk, so classpath resources are copied to a temporary directory once per run.
 */
public final class TestSchemas {

    private static final Path TEMP_DIR;

    static {
        try {
            TEMP_DIR = Files.createTempDirectory("connector-test-schemas");
            TEMP_DIR.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException("cannot create the test schema directory", e);
        }
    }

    private TestSchemas() {
    }

    /** The shipped sample mapping, read from the checkout. */
    public static String sample() {
        return Path.of("samples/straza/straza-schema.json").toAbsolutePath().toString();
    }

    /** A schema file that declares both reserved ConnId classes and nothing else. */
    public static String scimClasses() {
        return copy("scim-classes");
    }

    /** The absolute path of {@code /schema/<name>.json}, copied out of the test classpath. */
    public static String copy(String name) {
        Path file = TEMP_DIR.resolve(name + ".json");
        if (Files.exists(file)) {
            return file.toAbsolutePath().toString();
        }
        String resource = "/schema/" + name + ".json";
        try (InputStream in = TestSchemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("missing test schema " + resource);
            }
            Files.write(file, in.readAllBytes());
            file.toFile().deleteOnExit();
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new AssertionError("cannot materialise test schema " + resource, e);
        }
    }
}
