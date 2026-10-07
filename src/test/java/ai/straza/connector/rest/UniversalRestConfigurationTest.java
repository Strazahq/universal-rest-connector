package ai.straza.connector.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.testng.annotations.Test;

import static org.testng.Assert.assertThrows;

@Test(groups = "unit")
public class UniversalRestConfigurationTest {

    private UniversalRestConfiguration validConfig() {
        UniversalRestConfiguration c = new UniversalRestConfiguration();
        c.setBaseUrl("https://straza.example.com:8443");
        c.setApiToken(new GuardedString("token".toCharArray()));
        return c;
    }

    public void validConfigPasses() {
        validConfig().validate();
    }

    public void nullBaseUrlThrows() {
        UniversalRestConfiguration c = validConfig();
        c.setBaseUrl(null);
        assertThrows(ConfigurationException.class, c::validate);
    }

    public void blankBaseUrlThrows() {
        UniversalRestConfiguration c = validConfig();
        c.setBaseUrl("   ");
        assertThrows(ConfigurationException.class, c::validate);
    }

    public void nonHttpSchemeThrows() {
        UniversalRestConfiguration c = validConfig();
        c.setBaseUrl("ftp://straza.example.com");
        assertThrows(ConfigurationException.class, c::validate);
    }

    public void missingHostThrows() {
        UniversalRestConfiguration c = validConfig();
        c.setBaseUrl("https:///no-host");
        assertThrows(ConfigurationException.class, c::validate);
    }

    public void nullTokenThrows() {
        UniversalRestConfiguration c = validConfig();
        c.setApiToken(null);
        assertThrows(ConfigurationException.class, c::validate);
    }

    public void connectTimeoutOutOfRangeThrows() {
        UniversalRestConfiguration low = validConfig();
        low.setConnectTimeout(0);
        assertThrows(ConfigurationException.class, low::validate);

        UniversalRestConfiguration high = validConfig();
        high.setConnectTimeout(9999);
        assertThrows(ConfigurationException.class, high::validate);
    }

    public void readTimeoutOutOfRangeThrows() {
        UniversalRestConfiguration c = validConfig();
        c.setReadTimeout(0);
        assertThrows(ConfigurationException.class, c::validate);
    }

    public void missingSchemaFileThrows() throws IOException {
        Path tmp = Files.createTempFile("uni-rest-nope", ".json");
        Files.delete(tmp);
        UniversalRestConfiguration c = validConfig();
        c.setSchemaFilePath(tmp.toString());
        assertThrows(ConfigurationException.class, c::validate);
    }

    public void existingSchemaFilePasses() throws IOException {
        Path tmp = Files.createTempFile("uni-rest-schema", ".json");
        try {
            UniversalRestConfiguration c = validConfig();
            c.setSchemaFilePath(tmp.toString());
            c.validate();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
