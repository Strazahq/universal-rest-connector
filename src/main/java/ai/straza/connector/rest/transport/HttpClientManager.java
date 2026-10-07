package ai.straza.connector.rest.transport;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;

import ai.straza.connector.rest.UniversalRestConfiguration;

/** One JDK {@link HttpClient} per connector instance, with bearer auth, TLS policy and timeouts. */
public class HttpClientManager implements Closeable {

    private static final Log LOG = Log.getLog(HttpClientManager.class);

    private final UniversalRestConfiguration configuration;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration readTimeout;

    public HttpClientManager(UniversalRestConfiguration configuration) {
        this.configuration = configuration;
        this.baseUrl = normalizeBaseUrl(configuration.getBaseUrl());
        this.readTimeout = Duration.ofSeconds(configuration.getReadTimeout());

        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(configuration.getConnectTimeout()));

        if (configuration.isTrustAllCertificates()) {
            LOG.warn("trustAllCertificates is enabled — TLS certificate and hostname "
                    + "validation are DISABLED. Use for development only.");
            builder.sslContext(trustAllSslContext());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);
            builder.sslParameters(sslParameters);
        }

        this.httpClient = builder.build();
    }

    /** GETs {@code baseUrl + path} as JSON; a blank {@code path} is the base URL. */
    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        URI uri = resolve(path);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Accept", "application/json")
                .GET();
        applyBearerAuth(requestBuilder);
        LOG.ok("GET {0}", uri);
        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Sends a request to an encoded {@code path}; a null {@code body} or {@code token} is omitted. */
    public HttpResponse<String> send(String method, String path, String body, String contentType,
                                     GuardedString token) throws IOException, InterruptedException {
        URI uri = resolve(path);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Accept", contentType)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            requestBuilder.header("Content-Type", contentType);
        }
        if (token != null) {
            // Decrypt only for the moment it takes to set the header.
            token.access(clear -> requestBuilder.header("Authorization", "Bearer " + new String(clear)));
        }
        LOG.ok("{0} {1}", method, uri);
        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** The base URL without a trailing slash. */
    public String getBaseUrl() {
        return baseUrl;
    }

    private void applyBearerAuth(HttpRequest.Builder requestBuilder) {
        GuardedString token = configuration.getApiToken();
        if (token != null) {
            // Decrypt only for the moment it takes to set the header.
            token.access(clear -> requestBuilder.header("Authorization", "Bearer " + new String(clear)));
        }
    }

    private URI resolve(String path) {
        if (path == null || path.isBlank()) {
            return URI.create(baseUrl);
        }
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(baseUrl + suffix);
    }

    private static String normalizeBaseUrl(String url) {
        String trimmed = url.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    private static SSLContext trustAllSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        // trust everything (development only)
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        // trust everything (development only)
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new ConfigurationException("Failed to build trust-all SSL context: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // The client is not closed here; its threads end once it is unreachable.
    }
}
