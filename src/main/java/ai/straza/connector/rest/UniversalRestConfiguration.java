package ai.straza.connector.rest;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;
import org.identityconnectors.framework.spi.StatefulConfiguration;

/**
 * Connector configuration: base URL, bearer token, TLS, timeouts and the schema
 * file path. Stateful, so one validated instance is shared by pooled connectors.
 */
public class UniversalRestConfiguration extends AbstractConfiguration implements StatefulConfiguration {

    /** REST API base URL, for example {@code https://api.example.com:8443}. */
    private String baseUrl;

    /** Bearer token sent on every request, the change feed included. */
    private GuardedString apiToken;

    /** Enables create-only schema URNs whose {@code configGate} is {@code emitAgenticUrn}. */
    private boolean emitAgenticUrn = true;

    /** Accept any TLS certificate and skip hostname checks. Development only. */
    private boolean trustAllCertificates = false;

    /** TCP connection timeout, in seconds. */
    private int connectTimeout = 10;

    /** Response read timeout, in seconds. */
    private int readTimeout = 30;

    /** Absolute path to the schema file; every operation except {@code test()} needs it. */
    private String schemaFilePath;

    /** Relative path {@code test()} GETs; when blank, {@code test()} only checks the base URL. */
    private String testEndpoint;

    @ConfigurationProperty(order = 10, required = true,
            displayMessageKey = "baseUrl.display",
            helpMessageKey = "baseUrl.help")
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @ConfigurationProperty(order = 20, required = true, confidential = true,
            displayMessageKey = "apiToken.display",
            helpMessageKey = "apiToken.help")
    public GuardedString getApiToken() {
        return apiToken;
    }

    public void setApiToken(GuardedString apiToken) {
        this.apiToken = apiToken;
    }

    @ConfigurationProperty(order = 24,
            displayMessageKey = "emitAgenticUrn.display",
            helpMessageKey = "emitAgenticUrn.help")
    public boolean isEmitAgenticUrn() {
        return emitAgenticUrn;
    }

    public void setEmitAgenticUrn(boolean emitAgenticUrn) {
        this.emitAgenticUrn = emitAgenticUrn;
    }

    public boolean isGateEnabled(String gate) {
        requireGate(gate);
        return emitAgenticUrn;
    }

    /** Throws when {@code gate} is not a configuration gate of this connector. */
    public void requireGate(String gate) {
        if (!"emitAgenticUrn".equals(gate)) {
            throw new ConfigurationException("The schema file gates a create-only schema URN on the "
                    + "configuration property '" + gate + "', which this connector does not have. The gates "
                    + "it offers are: emitAgenticUrn.");
        }
    }

    @ConfigurationProperty(order = 30,
            displayMessageKey = "trustAllCertificates.display",
            helpMessageKey = "trustAllCertificates.help")
    public boolean isTrustAllCertificates() {
        return trustAllCertificates;
    }

    public void setTrustAllCertificates(boolean trustAllCertificates) {
        this.trustAllCertificates = trustAllCertificates;
    }

    @ConfigurationProperty(order = 40,
            displayMessageKey = "connectTimeout.display",
            helpMessageKey = "connectTimeout.help")
    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    @ConfigurationProperty(order = 50,
            displayMessageKey = "readTimeout.display",
            helpMessageKey = "readTimeout.help")
    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    @ConfigurationProperty(order = 60,
            displayMessageKey = "schemaFilePath.display",
            helpMessageKey = "schemaFilePath.help")
    public String getSchemaFilePath() {
        return schemaFilePath;
    }

    public void setSchemaFilePath(String schemaFilePath) {
        this.schemaFilePath = schemaFilePath;
    }

    @ConfigurationProperty(order = 70,
            displayMessageKey = "testEndpoint.display",
            helpMessageKey = "testEndpoint.help")
    public String getTestEndpoint() {
        return testEndpoint;
    }

    public void setTestEndpoint(String testEndpoint) {
        this.testEndpoint = testEndpoint;
    }

    @Override
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ConfigurationException("Base URL must be provided.");
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ConfigurationException("Base URL must use http or https: " + baseUrl);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new ConfigurationException("Base URL must contain a host: " + baseUrl);
            }
        } catch (URISyntaxException e) {
            throw new ConfigurationException("Base URL is not a valid URI: " + baseUrl, e);
        }

        if (apiToken == null) {
            throw new ConfigurationException("API token must be provided.");
        }

        if (connectTimeout < 1 || connectTimeout > 300) {
            throw new ConfigurationException("Connect timeout must be between 1 and 300 seconds.");
        }
        if (readTimeout < 1 || readTimeout > 600) {
            throw new ConfigurationException("Read timeout must be between 1 and 600 seconds.");
        }

        // test() works without a schema file, so the path is checked only when set.
        if (schemaFilePath != null && !schemaFilePath.isBlank()) {
            File schemaFile = new File(schemaFilePath);
            if (!schemaFile.exists() || !schemaFile.isFile()) {
                throw new ConfigurationException("Schema file not found: " + schemaFile.getPath());
            }
        }
    }

    @Override
    public void release() {
        // Nothing to release.
    }
}
