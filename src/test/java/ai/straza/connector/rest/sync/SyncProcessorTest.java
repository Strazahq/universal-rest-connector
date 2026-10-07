package ai.straza.connector.rest.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.straza.connector.rest.schema.SyncType;

import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

@Test(groups = "unit")
public class SyncProcessorTest {

    private static final ObjectClass USER = new ObjectClass("user");
    private static final ObjectClass APP = new ObjectClass("app");
    private static final ObjectClass ROLE = new ObjectClass("role");
    private static final ObjectClass TOOL = new ObjectClass("tool");

    // ---- self: create/update re-fetch, delete, and vanished-object ------------

    public void selfCreateEmitsUpdateAndIdentityDeleteEmitsDelete() {
        SyncType user = new SyncType(List.of("user"), List.of("user"), List.of("identity"),
                List.of(), List.of(), "uid");
        Collecting handler = new Collecting();
        FeedBatch batch = new FeedBatch(List.of(
                new ChangeRecord("c1", "user", "create", "u1"),
                new ChangeRecord("c2", "identity", "delete", "dead")), "c2", "c9");

        SyncToken watermark = SyncProcessor.process(USER, user, batch, null,
                current(Map.of("u1", obj("user", "u1", "alice")), Map.of(), List.of(), Map.of()), handler);

        assertEquals(handler.deltas.size(), 2);
        assertUpdate(handler.deltas.get(0), "u1", "c1");
        assertDelete(handler.deltas.get(1), USER, "dead", "c2");
        assertEquals(watermark.getValue(), "c2"); // normal completion resumes at nextCursor
    }

    public void selfVanishedBetweenNotifyAndRefetchEmitsDelete() {
        SyncType user = new SyncType(List.of("user"), List.of("user"), List.of(), List.of(), List.of(), "uid");
        Collecting handler = new Collecting();
        FeedBatch batch = new FeedBatch(List.of(new ChangeRecord("c1", "user", "update", "ghost")), "c1", "c1");

        SyncProcessor.process(USER, user, batch, null, current(Map.of(), Map.of(), List.of(), Map.of()), handler);

        assertEquals(handler.deltas.size(), 1);
        assertDelete(handler.deltas.get(0), USER, "ghost", "c1");
    }

    // ---- self matched by name ----------------------------------------------------
    // matchField="name", for a feed that identifies objects by name.

    public void selfMatchByNameCapabilityKeepsRealUid() {
        SyncType app = new SyncType(List.of("app"), List.of("app"), List.of(), List.of(), List.of(), "name");
        ConnectorObject github = obj("app", "a1-uuid", "github");
        Collecting handler = new Collecting();
        FeedBatch batch = new FeedBatch(List.of(
                new ChangeRecord("c1", "app", "create", "github"),
                new ChangeRecord("c2", "app", "delete", "slack")), "c2", "c2");

        SyncProcessor.process(APP, app, batch, "c0",
                current(Map.of(), Map.of("github", github), List.of(github), Map.of()), handler);

        assertEquals(handler.deltas.size(), 2);
        assertUpdate(handler.deltas.get(0), "a1-uuid", "c1"); // re-fetched by name, uid from the object
        assertDelete(handler.deltas.get(1), APP, "slack", "c2"); // best-effort delete by name
    }

    // ---- reemitAll: a binding change re-emits every role ----------------------

    public void bindingReemitsAllRoles() {
        SyncType role = new SyncType(List.of("role", "binding"), List.of("role"), List.of("identity"),
                List.of("binding"), List.of(), "uid");
        ConnectorObject dev = obj("role", "r1", "dev");
        ConnectorObject ops = obj("role", "r2", "ops");
        Collecting handler = new Collecting();
        FeedBatch batch = new FeedBatch(List.of(new ChangeRecord("c5", "binding", "update", "someapp")), "c5", "c5");

        SyncProcessor.process(ROLE, role, batch, "c0",
                current(Map.of("r1", dev, "r2", ops), Map.of(), List.of(dev, ops), Map.of()), handler);

        assertEquals(handler.deltas.size(), 2);
        assertUpdate(handler.deltas.get(0), "r1", "c5");
        assertUpdate(handler.deltas.get(1), "r2", "c5");
    }

    // ---- rederive: tool drift re-derives that app's tools ---------------------

    public void toolDriftRederivesParentTools() {
        SyncType tool = new SyncType(List.of("tool"), List.of(), List.of(), List.of(), List.of("tool"), "uid");
        ConnectorObject ci = obj("tool", "github__ci", "github__ci");
        ConnectorObject cd = obj("tool", "github__cd", "github__cd");
        Collecting handler = new Collecting();
        FeedBatch batch = new FeedBatch(List.of(new ChangeRecord("c7", "tool", "update", "github")), "c7", "c7");

        SyncProcessor.process(TOOL, tool, batch, "c0",
                current(Map.of(), Map.of(), List.of(), Map.of("github", List.of(ci, cd))), handler);

        assertEquals(handler.deltas.size(), 2);
        assertUpdate(handler.deltas.get(0), "github__ci", "c7");
        assertUpdate(handler.deltas.get(1), "github__cd", "c7");
    }

    // ---- not-routed types are ignored; empty batch keeps the incoming token ---

    public void unroutedTypesIgnoredAndEmptyBatchKeepsToken() {
        SyncType user = new SyncType(List.of("user"), List.of("user"), List.of(), List.of(), List.of(), "uid");
        Collecting handler = new Collecting();
        FeedBatch batch = new FeedBatch(List.of(new ChangeRecord("c1", "app", "create", "x")), "", "cH");

        SyncToken watermark = SyncProcessor.process(USER, user, batch, "c0",
                current(Map.of(), Map.of(), List.of(), Map.of()), handler);

        assertEquals(handler.deltas.size(), 0);
        assertEquals(watermark.getValue(), "c0"); // blank nextCursor keeps the incoming token
    }

    // ---- abort resumes from the last fully-processed record -------------------

    public void abortResumesFromLastCompletedRecord() {
        SyncType user = new SyncType(List.of("user"), List.of("user"), List.of(), List.of(), List.of(), "uid");
        ConnectorObject u1 = obj("user", "u1", "alice");
        FeedBatch batch = new FeedBatch(List.of(
                new ChangeRecord("c1", "user", "update", "u1"),
                new ChangeRecord("c2", "user", "update", "u1")), "c2", "c2");
        Collecting handler = new Collecting();
        handler.stopAfter = 2; // accept the first delta (c1), reject the second (c2)

        SyncToken watermark = SyncProcessor.process(USER, user, batch, "c0",
                current(Map.of("u1", u1), Map.of(), List.of(u1), Map.of()), handler);

        assertEquals(watermark.getValue(), "c1"); // c2 aborted, so resume after c1
    }

    // ---- helpers --------------------------------------------------------------

    private static void assertUpdate(SyncDelta delta, String uid, String token) {
        assertEquals(delta.getDeltaType(), SyncDeltaType.CREATE_OR_UPDATE);
        assertTrue(delta.getObject() != null, "expected an object on a CREATE_OR_UPDATE delta");
        assertEquals(delta.getObject().getUid().getUidValue(), uid);
        assertEquals(delta.getToken().getValue(), token);
    }

    private static void assertDelete(SyncDelta delta, ObjectClass oc, String uid, String token) {
        assertEquals(delta.getDeltaType(), SyncDeltaType.DELETE);
        assertNull(delta.getObject());
        assertEquals(delta.getObjectClass(), oc);
        assertEquals(delta.getUid().getUidValue(), uid);
        assertEquals(delta.getToken().getValue(), token);
    }

    private static ConnectorObject obj(String objectClass, String uid, String name) {
        return new ConnectorObjectBuilder()
                .setObjectClass(new ObjectClass(objectClass))
                .setUid(uid)
                .setName(name)
                .addAttribute(AttributeBuilder.build("name", name))
                .build();
    }

    private static SyncProcessor.CurrentObjects current(Map<String, ConnectorObject> byUid,
                                                        Map<String, ConnectorObject> byName,
                                                        List<ConnectorObject> all,
                                                        Map<String, List<ConnectorObject>> derived) {
        return new SyncProcessor.CurrentObjects() {
            @Override
            public ConnectorObject byMatch(boolean matchByName, String value) {
                return (matchByName ? byName : byUid).get(value);
            }

            @Override
            public List<ConnectorObject> all() {
                return all;
            }

            @Override
            public List<ConnectorObject> derivedForParent(String parentValue) {
                return derived.getOrDefault(parentValue, List.of());
            }
        };
    }

    private static final class Collecting implements SyncResultsHandler {
        private final List<SyncDelta> deltas = new ArrayList<>();
        private int stopAfter = Integer.MAX_VALUE;

        @Override
        public boolean handle(SyncDelta delta) {
            deltas.add(delta);
            return deltas.size() < stopAfter;
        }
    }
}
