package ai.straza.connector.rest.sync;

import java.util.ArrayList;
import java.util.List;

import ai.straza.connector.rest.schema.SyncType;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaBuilder;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.Uid;

/**
 * Turns a {@link FeedBatch} into sync deltas for one object class. Delivery is
 * at-least-once: each delta carries its record's cursor, and after an abort the
 * returned token resumes at the last fully handled record.
 */
public final class SyncProcessor {

    private static final Log LOG = Log.getLog(SyncProcessor.class);

    /** Current objects of the class being synced. */
    public interface CurrentObjects {
        /** The object whose uid, or name when {@code byName}, equals {@code value}; or null. */
        ConnectorObject byMatch(boolean byName, String value);

        /** All current objects of the class. */
        List<ConnectorObject> all();

        /** Derived objects whose parent reference equals {@code parentValue}. */
        List<ConnectorObject> derivedForParent(String parentValue);
    }

    private SyncProcessor() {
    }

    /** Emits the batch's deltas to {@code handler} and returns the token to store. */
    public static SyncToken process(ObjectClass objectClass, SyncType syncType, FeedBatch batch,
                                    String incomingToken, CurrentObjects current, SyncResultsHandler handler) {
        String lastCompleted = null;
        boolean aborted = false;

        for (ChangeRecord record : batch.records()) {
            List<SyncDelta> deltas = route(objectClass, syncType, record, current);
            if (deltas == null) {
                continue; // type not routed to this class
            }
            for (SyncDelta delta : deltas) {
                if (!handler.handle(delta)) {
                    aborted = true;
                    break;
                }
            }
            if (aborted) {
                break;
            }
            lastCompleted = record.cursor();
        }

        if (aborted) {
            LOG.ok("sync aborted by handler; resuming next cycle from {0}", lastCompleted);
            return new SyncToken(nonNull(lastCompleted, incomingToken, batch.head()));
        }
        String nextCursor = batch.nextCursor();
        if (nextCursor != null && !nextCursor.isBlank()) {
            return new SyncToken(nextCursor);
        }
        return new SyncToken(nonNull(incomingToken, batch.head(), ""));
    }

    /** The deltas for one record, or {@code null} when the class does not route its type. */
    private static List<SyncDelta> route(ObjectClass objectClass, SyncType syncType,
                                         ChangeRecord record, CurrentObjects current) {
        String type = record.type();
        if (type == null) {
            return null;
        }
        if (syncType.isDelete(type)) {
            return List.of(deleteDelta(objectClass, record));
        }
        if (syncType.isSelf(type)) {
            if (record.isDeleteOp()) {
                return List.of(deleteDelta(objectClass, record));
            }
            ConnectorObject object = current.byMatch(syncType.matchesByName(), record.id());
            // An object gone by the time it is re-read becomes a delete.
            return List.of(object != null
                    ? updateDelta(record, object)
                    : deleteDelta(objectClass, record));
        }
        if (syncType.isReemitAll(type)) {
            return updateDeltas(record, current.all());
        }
        if (syncType.isRederive(type)) {
            return updateDeltas(record, current.derivedForParent(record.id()));
        }
        return null;
    }

    private static List<SyncDelta> updateDeltas(ChangeRecord record, List<ConnectorObject> objects) {
        List<SyncDelta> deltas = new ArrayList<>(objects.size());
        for (ConnectorObject object : objects) {
            deltas.add(updateDelta(record, object));
        }
        return deltas;
    }

    private static SyncDelta updateDelta(ChangeRecord record, ConnectorObject object) {
        return new SyncDeltaBuilder()
                .setDeltaType(SyncDeltaType.CREATE_OR_UPDATE)
                .setToken(new SyncToken(record.cursor()))
                .setObject(object)
                .build();
    }

    private static SyncDelta deleteDelta(ObjectClass objectClass, ChangeRecord record) {
        return new SyncDeltaBuilder()
                .setDeltaType(SyncDeltaType.DELETE)
                .setToken(new SyncToken(record.cursor()))
                .setObjectClass(objectClass)
                .setUid(new Uid(record.id()))
                .build();
    }

    private static String nonNull(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return "";
    }
}
