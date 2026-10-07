package ai.straza.connector.rest;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import ai.straza.connector.rest.filter.RestFilter;
import ai.straza.connector.rest.filter.RestFilterTranslator;
import ai.straza.connector.rest.objects.ResourceService;
import ai.straza.connector.rest.objects.UniversalObjectsHandler;
import ai.straza.connector.rest.schema.ConditionalSchema;
import ai.straza.connector.rest.schema.Operation;
import ai.straza.connector.rest.schema.SchemaType;
import ai.straza.connector.rest.schema.SyncFeedConfig;
import ai.straza.connector.rest.schema.SyncType;
import ai.straza.connector.rest.schema.TargetInfo;
import ai.straza.connector.rest.schema.UniversalSchemaHandler;
import ai.straza.connector.rest.sync.ChangeFeedReader;
import ai.straza.connector.rest.sync.FeedBatch;
import ai.straza.connector.rest.sync.SyncProcessor;
import ai.straza.connector.rest.transport.HttpClientManager;
import ai.straza.connector.rest.transport.ResourceClient;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.SearchResult;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.SearchResultsHandler;
import org.identityconnectors.framework.spi.SyncTokenResultsHandler;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.SyncOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateDeltaOp;
import org.identityconnectors.framework.spi.operations.UpdateOp;

/**
 * ConnId connector whose object classes all come from the configured schema file.
 * Classes are read-only by default; a verb a class does not list is removed from
 * the support matrix and refused before a request is built.
 */
@ConnectorClass(displayNameKey = "universal.rest.connector.display",
        configurationClass = UniversalRestConfiguration.class)
public class UniversalRestConnector
        implements Connector, TestOp, SchemaOp, SearchOp<RestFilter>, SyncOp,
        CreateOp, UpdateOp, UpdateDeltaOp, DeleteOp {

    private static final Log LOG = Log.getLog(UniversalRestConnector.class);

    private UniversalRestConfiguration configuration;
    private HttpClientManager httpClientManager;
    private UniversalSchemaHandler schemaHandler;
    private ResourceClient resourceClient;
    private ResourceService resourceService;
    private TargetInfo target = TargetInfo.fallback();

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void init(Configuration configuration) {
        this.configuration = (UniversalRestConfiguration) configuration;
        this.configuration.validate();
        this.httpClientManager = new HttpClientManager(this.configuration);

        String schemaFilePath = this.configuration.getSchemaFilePath();
        if (schemaFilePath != null && !schemaFilePath.isBlank()) {
            this.schemaHandler = new UniversalSchemaHandler(schemaFilePath);
            this.target = this.schemaHandler.getTargetInfo();
            requireKnownGates(this.schemaHandler, this.configuration);
        }
        this.resourceClient = new ResourceClient(this.httpClientManager, this.configuration, this.target);
        if (this.schemaHandler != null) {
            this.resourceService = new ResourceService(this.resourceClient, this.schemaHandler.getSchemaTypes());
        }
        LOG.ok("UniversalRestConnector initialised for baseUrl {0} (schema {1})",
                this.configuration.getBaseUrl(), schemaHandler != null ? "loaded" : "not configured");
    }

    @Override
    public void dispose() {
        if (this.httpClientManager != null) {
            this.httpClientManager.close();
            this.httpClientManager = null;
        }
        this.schemaHandler = null;
        this.resourceClient = null;
        this.resourceService = null;
        this.target = TargetInfo.fallback();
        this.configuration = null;
    }

    @Override
    public void test() {
        String path = configuration.getTestEndpoint();
        boolean hasEndpoint = path != null && !path.isBlank();
        try {
            HttpResponse<String> response = httpClientManager.get(hasEndpoint ? path : "");
            int status = response.statusCode();

            if (status == 401 || status == 403) {
                throw new InvalidCredentialException(target.getName() + " rejected the API token (HTTP "
                        + status + ") for " + (hasEndpoint ? path : "the base URL") + ". "
                        + target.getCredentialHint() + " Server said: " + summarize(response.body()));
            }
            if (!hasEndpoint) {
                LOG.ok("test() connectivity ok: HTTP {0} from base URL (no testEndpoint configured)", status);
                return;
            }
            if (status >= 200 && status < 300) {
                LOG.ok("test() ok: HTTP {0} from {1}", status, path);
                return;
            }
            throw new ConnectorException("Unexpected response HTTP " + status
                    + " from test endpoint '" + path + "': " + summarize(response.body()));

        } catch (IOException e) {
            throw new ConnectionFailedException(
                    "Cannot reach REST API at " + configuration.getBaseUrl() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("Interrupted while testing the connection.", e);
        }
    }

    /**
     * Declares each schema-file class with the operations it lists; {@code SyncOp}
     * also needs the file's top-level {@code sync} block.
     */
    @Override
    public Schema schema() {
        UniversalSchemaHandler schema = requireSchema();
        SchemaBuilder schemaBuilder = new SchemaBuilder(UniversalRestConnector.class);
        boolean feedAvailable = schema.getSyncFeedConfig() != null;
        for (SchemaType schemaType : schema.getSchemaTypes().values()) {
            ObjectClassInfo info = UniversalObjectsHandler.buildObjectClass(schemaBuilder, schemaType);
            removeUnless(schemaBuilder, info, CreateOp.class, schemaType.supports(Operation.CREATE));
            removeUnless(schemaBuilder, info, UpdateOp.class, schemaType.supports(Operation.UPDATE));
            removeUnless(schemaBuilder, info, UpdateDeltaOp.class, schemaType.supports(Operation.UPDATE_DELTA));
            removeUnless(schemaBuilder, info, DeleteOp.class, schemaType.supports(Operation.DELETE));
            removeUnless(schemaBuilder, info, SyncOp.class, feedAvailable && schemaType.supports(Operation.SYNC));
        }
        return schemaBuilder.build();
    }

    @Override
    public FilterTranslator<RestFilter> createFilterTranslator(ObjectClass objectClass, OperationOptions options) {
        return new RestFilterTranslator();
    }

    // ---- writes (only what a class's operations list declares) ----------------

    /** Creates an object; the uid is read from the create response. */
    @Override
    public Uid create(ObjectClass objectClass, Set<Attribute> createAttributes, OperationOptions options) {
        return resourceService().create(require(objectClass), createAttributes, configuration::isGateEnabled);
    }

    /** Full-document update through the class's replace endpoint. */
    @Override
    public Uid update(ObjectClass objectClass, Uid uid, Set<Attribute> replaceAttributes, OperationOptions options) {
        return resourceService().replace(require(objectClass), uid, replaceAttributes);
    }

    /** Sends the deltas as one patch document to the class's patch endpoint. */
    @Override
    public Set<AttributeDelta> updateDelta(ObjectClass objectClass, Uid uid, Set<AttributeDelta> modifications,
                                           OperationOptions options) {
        return resourceService().updateDelta(require(objectClass), uid, modifications);
    }

    @Override
    public void delete(ObjectClass objectClass, Uid uid, OperationOptions options) {
        resourceService().delete(require(objectClass), uid);
    }

    /** Searches one class: by-id read, pushed filter or full listing, always narrowed locally. */
    @Override
    public void executeQuery(ObjectClass objectClass, RestFilter query, ResultsHandler handler, OperationOptions options) {
        LOG.ok("executeQuery objectClass={0} query={1}", objectClass, query);
        SchemaType schemaType = require(objectClass);
        ResourceService.requireOperation(schemaType, Operation.SEARCH);
        resourceService().search(schemaType, query, handler, options);
    }

    // ---- LiveSync (SyncOp) ----------------------------------------------------

    /** Returns the feed head, so a first LiveSync run starts from now. */
    @Override
    public SyncToken getLatestSyncToken(ObjectClass objectClass) {
        UniversalSchemaHandler schema = requireSchema();
        SyncFeedConfig feedConfig = requireSyncFeed(schema);
        ChangeFeedReader reader = new ChangeFeedReader(resourceClient, feedConfig);
        String head = reader.head();
        LOG.ok("getLatestSyncToken {0}: head={1}", objectClass.getObjectClassValue(), head);
        return new SyncToken(head != null ? head : "");
    }

    /**
     * Reads the change feed from {@code token} and emits deltas for one object
     * class, routed by that class's {@code sync} block.
     */
    @Override
    public void sync(ObjectClass objectClass, SyncToken token, SyncResultsHandler handler, OperationOptions options) {
        UniversalSchemaHandler schema = requireSchema();
        SyncFeedConfig feedConfig = requireSyncFeed(schema);
        if (ObjectClass.ALL.equals(objectClass)) {
            throw new UnsupportedOperationException("LiveSync runs per object class; configure one LiveSync "
                    + "task per object class (e.g. __ACCOUNT__/__GROUP__/app/tool), not __ALL__.");
        }

        SchemaType schemaType = require(objectClass);
        if (!schemaType.supportsSync()) {
            throw new IllegalArgumentException("Object class does not support LiveSync (no 'sync' block): "
                    + objectClass.getObjectClassValue());
        }
        SyncType syncType = schemaType.getSyncType();
        SyncProcessor.CurrentObjects currentObjects = new ConnectorCurrentObjects(schemaType);

        ChangeFeedReader reader = new ChangeFeedReader(resourceClient, feedConfig);
        String since = token == null || token.getValue() == null ? null : String.valueOf(token.getValue());
        FeedBatch batch = reader.changesSince(since, syncType.getRequestTypes());
        LOG.ok("sync {0}: {1} change record(s) since {2}",
                objectClass.getObjectClassValue(), batch.records().size(), since);

        SyncToken watermark = SyncProcessor.process(
                objectClass, syncType, batch, since, currentObjects, handler);
        if (handler instanceof SyncTokenResultsHandler) {
            ((SyncTokenResultsHandler) handler).handleResult(watermark);
        }
    }

    /** Current objects of one class; the full listing is fetched at most once per call. */
    private final class ConnectorCurrentObjects implements SyncProcessor.CurrentObjects {

        private final SchemaType schemaType;
        private List<ConnectorObject> cache;

        private ConnectorCurrentObjects(SchemaType schemaType) {
            this.schemaType = schemaType;
        }

        private List<ConnectorObject> objects() {
            if (cache == null) {
                cache = resourceService.fetchAll(schemaType);
            }
            return cache;
        }

        @Override
        public ConnectorObject byMatch(boolean byName, String value) {
            if (value == null) {
                return null;
            }
            if (!byName && schemaType.getEndpoints().getById() != null) {
                // A 404 returns null, which becomes a delete delta.
                return resourceService.fetchByUid(schemaType, value);
            }
            for (ConnectorObject object : objects()) {
                String key = byName ? object.getName().getNameValue() : object.getUid().getUidValue();
                if (value.equals(key)) {
                    return object;
                }
            }
            return null;
        }

        @Override
        public List<ConnectorObject> all() {
            return objects();
        }

        @Override
        public List<ConnectorObject> derivedForParent(String parentValue) {
            return resourceService.derivedForParent(schemaType, parentValue);
        }
    }

    // ---- helpers --------------------------------------------------------------

    /** Fails init when a create-only schema URN names an unknown configuration gate. */
    private static void requireKnownGates(UniversalSchemaHandler schema, UniversalRestConfiguration configuration) {
        for (SchemaType schemaType : schema.getSchemaTypes().values()) {
            for (ConditionalSchema conditional : schemaType.getCreateOnlySchemas()) {
                if (conditional.getConfigGate() != null) {
                    configuration.requireGate(conditional.getConfigGate());
                }
            }
        }
    }

    /** Removes the class from {@code op}'s support matrix unless {@code supported}. */
    private static void removeUnless(SchemaBuilder schemaBuilder, ObjectClassInfo info,
                                     Class<? extends org.identityconnectors.framework.spi.operations.SPIOperation> op,
                                     boolean supported) {
        if (!supported) {
            schemaBuilder.removeSupportedObjectClass(op, info);
        }
    }

    /** The schema-file class for {@code objectClass}; throws for an undeclared class. */
    private SchemaType require(ObjectClass objectClass) {
        return resourceService().require(objectClass.getObjectClassValue());
    }

    /** The resource service; throws when no schema file is configured. */
    private ResourceService resourceService() {
        requireSchema();
        return resourceService;
    }

    private UniversalSchemaHandler requireSchema() {
        if (schemaHandler == null) {
            throw new ConfigurationException("schemaFilePath must be configured: every object class this "
                    + "connector offers is declared in the schema file, so without one there is no schema, "
                    + "no Search, no LiveSync and no write.");
        }
        return schemaHandler;
    }

    private static SyncFeedConfig requireSyncFeed(UniversalSchemaHandler schema) {
        SyncFeedConfig feedConfig = schema.getSyncFeedConfig();
        if (feedConfig == null) {
            throw new ConfigurationException(
                    "LiveSync requires a top-level 'sync' block (change-feed config) in the schema file.");
        }
        return feedConfig;
    }

    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String stripped = body.strip();
        return stripped.length() > 200 ? stripped.substring(0, 200) + "…" : stripped;
    }
}
