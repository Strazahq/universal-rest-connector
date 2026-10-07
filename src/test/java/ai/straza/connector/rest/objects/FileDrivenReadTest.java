package ai.straza.connector.rest.objects;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ai.straza.connector.rest.UniversalRestConfiguration;
import ai.straza.connector.rest.UniversalRestConnector;
import ai.straza.connector.rest.filter.RestFilter;
import ai.straza.connector.rest.support.EmbeddedRestServer;
import ai.straza.connector.rest.support.TestSchemas;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.ContainsAllValuesFilter;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Reads of the reserved ConnId classes as a schema file declares them: attribute
 * paths, paging, pushed filters, by-id reads and local narrowing.
 */
@Test(groups = "unit")
public class FileDrivenReadTest {

    private EmbeddedRestServer server;

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws IOException {
        server = new EmbeddedRestServer();
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    // ---- paths -------------------------------------------------------------------

    public void groupSurfacesTheExtensionProjectionIncludingServerAndAdministers() {
        server.stub("GET", "/scim/v2/Groups/r-1", 200, "{\"id\":\"r-1\",\"displayName\":\"dev\","
                + "\"members\":[{\"value\":\"u-1\",\"display\":\"bob\"}],"
                + "\"" + GROUP_URN + "\":{\"role\":\"dev\",\"roleKind\":\"business\",\"plane\":\"access\","
                + "\"description\":\"Developer access\",\"apps\":[\"demo-tools\"],"
                + "\"tools\":[\"demo-tools:echo\"],\"policies\":[\"dev-guardrails\"],"
                + "\"administers\":[\"scratch\",\"demo-tools\"],\"server\":\"scratch\"}}");
        UniversalRestConnector connector = connector();

        ConnectorObject group = one(connector, ObjectClass.GROUP, RestFilter.of(Uid.NAME, "r-1"));

        assertEquals(group.getUid().getUidValue(), "r-1");
        assertEquals(group.getName().getNameValue(), "dev");
        assertEquals(group.getAttributeByName("members").getValue(), List.of("u-1"));
        assertEquals(group.getAttributeByName("role").getValue().get(0), "dev");
        assertEquals(group.getAttributeByName("roleKind").getValue().get(0), "business");
        assertEquals(group.getAttributeByName("plane").getValue().get(0), "access");
        assertEquals(group.getAttributeByName("description").getValue().get(0), "Developer access");
        assertEquals(group.getAttributeByName("apps").getValue(), List.of("demo-tools"));
        assertEquals(group.getAttributeByName("tools").getValue(), List.of("demo-tools:echo"));
        assertEquals(group.getAttributeByName("policies").getValue(), List.of("dev-guardrails"));
        assertEquals(group.getAttributeByName("administers").getValue(), List.of("scratch", "demo-tools"));
        assertEquals(group.getAttributeByName("server").getValue().get(0), "scratch");
        connector.dispose();
    }

    public void aGroupWithoutServerOrAdministersSurfacesNeither() {
        server.stub("GET", "/scim/v2/Groups/r-2", 200, "{\"id\":\"r-2\",\"displayName\":\"plain\","
                + "\"" + GROUP_URN + "\":{\"role\":\"plain\"}}");
        UniversalRestConnector connector = connector();

        ConnectorObject group = one(connector, ObjectClass.GROUP, RestFilter.of(Uid.NAME, "r-2"));

        assertNull(group.getAttributeByName("server"));
        assertNull(group.getAttributeByName("administers"));
        assertEquals(group.getAttributeByName("role").getValue().get(0), "plain");
        connector.dispose();
    }

    public void aGroupWithoutAnyProjectionStillReads() {
        server.stub("GET", "/scim/v2/Groups/r-3", 200, "{\"id\":\"r-3\",\"displayName\":\"bare\"}");
        UniversalRestConnector connector = connector();

        ConnectorObject group = one(connector, ObjectClass.GROUP, RestFilter.of(Uid.NAME, "r-3"));

        assertEquals(group.getName().getNameValue(), "bare");
        assertNull(group.getAttributeByName("role"));
        assertNull(group.getAttributeByName("apps"));
        connector.dispose();
    }

    public void theFilteredArrayPathPrefersTheSelectedElement() {
        server.stub("GET", "/scim/v2/Users/u-1", 200, "{\"id\":\"u-1\",\"userName\":\"bob\",\"active\":false,"
                + "\"emails\":[{\"value\":\"other@x\",\"primary\":false},{\"value\":\"bob@x\",\"primary\":true}],"
                + "\"title\":\"Deploy bot\","
                + "\"groups\":[{\"value\":\"g-1\",\"display\":\"devs\"}],"
                + "\"" + USER_URN + "\":{\"locked\":true,\"lockReason\":\"hold\",\"kind\":\"nhi\","
                + "\"origin\":\"scim\",\"ephemeral\":true}}");
        UniversalRestConnector connector = connector();

        ConnectorObject account = one(connector, ObjectClass.ACCOUNT, RestFilter.of(Uid.NAME, "u-1"));

        assertEquals(account.getAttributeByName("email").getValue().get(0), "bob@x", "the primary entry wins");
        assertEquals(account.getAttributeByName("groups").getValue(), List.of("g-1"));
        assertEquals(account.getAttributeByName(OperationalAttributes.ENABLE_NAME).getValue().get(0),
                Boolean.FALSE);
        assertEquals(account.getAttributeByName("locked").getValue().get(0), Boolean.TRUE);
        assertEquals(account.getAttributeByName("ephemeral").getValue().get(0), Boolean.TRUE);
        assertEquals(account.getAttributeByName("lockReason").getValue().get(0), "hold");
        assertEquals(account.getAttributeByName("kind").getValue().get(0), "nhi");
        assertEquals(account.getAttributeByName("origin").getValue().get(0), "scim");
        assertEquals(account.getAttributeByName("title").getValue().get(0), "Deploy bot");
        connector.dispose();
    }

    public void theFilteredArrayPathFallsBackToTheFirstElement() {
        server.stub("GET", "/scim/v2/Users/u-2", 200, "{\"id\":\"u-2\",\"userName\":\"carol\","
                + "\"emails\":[{\"value\":\"carol@x\"}]}");
        UniversalRestConnector connector = connector();

        ConnectorObject account = one(connector, ObjectClass.ACCOUNT, RestFilter.of(Uid.NAME, "u-2"));

        assertEquals(account.getAttributeByName("email").getValue().get(0), "carol@x");
        connector.dispose();
    }

    // ---- lanes -------------------------------------------------------------------

    public void aUidQueryIsAPerIdRead() {
        server.stub("GET", "/scim/v2/Users/u-7", 200, "{\"id\":\"u-7\",\"userName\":\"carol\"}");
        UniversalRestConnector connector = connector();

        List<ConnectorObject> results = search(connector, ObjectClass.ACCOUNT, RestFilter.of(Uid.NAME, "u-7"));

        assertEquals(results.size(), 1);
        assertEquals(server.lastRequest().uri(), "/scim/v2/Users/u-7");
        connector.dispose();
    }

    public void anUnknownUidIsAnEmptyResult() {
        server.stub("GET", "/scim/v2/Users/gone", 404, "{\"status\":\"404\"}");
        UniversalRestConnector connector = connector();
        assertTrue(search(connector, ObjectClass.ACCOUNT, RestFilter.of(Uid.NAME, "gone")).isEmpty());
        connector.dispose();
    }

    public void aDeclaredPushableFilterRidesTheWire() {
        server.stub("GET", "/scim/v2/Groups", 200,
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"r-1\",\"displayName\":\"dev\"}]}");
        UniversalRestConnector connector = connector();

        List<ConnectorObject> results = search(connector, ObjectClass.GROUP, RestFilter.of(Name.NAME, "dev"));

        assertEquals(results.size(), 1);
        assertEquals(decodedUri(), "/scim/v2/Groups?filter=displayName eq \"dev\"");
        connector.dispose();
    }

    public void everyDeclaredPushableAttributeRidesTheWire() {
        server.stub("GET", "/scim/v2/Users", 200,
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"u-1\",\"userName\":\"bob\"}]}");
        UniversalRestConnector connector = connector();

        search(connector, ObjectClass.ACCOUNT, RestFilter.of(Name.NAME, "bob"));
        assertEquals(decodedUri(), "/scim/v2/Users?filter=userName eq \"bob\"");

        search(connector, ObjectClass.ACCOUNT, RestFilter.of("externalId", "idm-u-1"));
        assertEquals(decodedUri(), "/scim/v2/Users?filter=externalId eq \"idm-u-1\"");
        connector.dispose();
    }

    public void aPushedFilterIsNarrowedLocallyToo() {
        // The server ignores the filter and returns everything; the connector still narrows.
        stubTwoGroups();
        UniversalRestConnector connector = connector();

        List<ConnectorObject> results = search(connector, ObjectClass.GROUP, RestFilter.of(Name.NAME, "dev"));

        assertTrue(decodedUri().contains("filter=displayName eq \"dev\""), decodedUri());
        assertEquals(results.size(), 1, "a server that ignored the filter must not widen the answer");
        assertEquals(results.get(0).getName().getNameValue(), "dev");
        connector.dispose();
    }

    public void anAttributeOneClassPushesAndAnotherDoesNotSplitsByClass() {
        // __ACCOUNT__ pushes externalId and __GROUP__ does not; on __GROUP__ it narrows to nothing.
        server.stub("GET", "/scim/v2/Groups", 200,
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"r-1\",\"displayName\":\"dev\"}]}");
        UniversalRestConnector connector = connector();

        RestFilter translated = translate(connector, ObjectClass.GROUP,
                new EqualsFilter(AttributeBuilder.build("externalId", "idm-g-1")));
        assertEquals(translated.getAttribute(), "externalId");
        assertTrue(search(connector, ObjectClass.GROUP, translated).isEmpty(),
                "the match must be honestly empty, never unfiltered");
        assertFalse(decodedUri().contains("filter="), "the filter must not ride the wire: " + decodedUri());
        connector.dispose();
    }

    public void anUndeclaredFilterNarrowsLocallyAndNeverWidens() {
        // midPoint resolves a simulated association by searching __GROUP__ for a member
        // uid, which SCIM cannot filter; an unfiltered answer would match every group.
        stubTwoGroups();
        UniversalRestConnector connector = connector();

        List<ConnectorObject> holding = search(connector, ObjectClass.GROUP,
                translate(connector, ObjectClass.GROUP, new EqualsFilter(AttributeBuilder.build("members", "u-1"))));
        assertEquals(holding.size(), 1, "only the holding group may match");
        assertEquals(holding.get(0).getUid().getUidValue(), "r-1");
        assertFalse(decodedUri().contains("filter="), "members must never be pushed: " + decodedUri());

        List<ConnectorObject> both = search(connector, ObjectClass.GROUP, translate(connector, ObjectClass.GROUP,
                new ContainsAllValuesFilter(AttributeBuilder.build("members", "u-2"))));
        assertEquals(both.size(), 2, "u-2 holds both");

        List<ConnectorObject> one = search(connector, ObjectClass.GROUP, translate(connector, ObjectClass.GROUP,
                new ContainsAllValuesFilter(AttributeBuilder.build("members", List.of("u-1", "u-2")))));
        assertEquals(one.size(), 1, "only the group carrying BOTH uids matches");

        List<ConnectorObject> none = search(connector, ObjectClass.GROUP,
                translate(connector, ObjectClass.GROUP, new EqualsFilter(AttributeBuilder.build("members", "u-9"))));
        assertTrue(none.isEmpty(), "a non-member uid must match no group");
        connector.dispose();
    }

    public void anUndeclaredAttributeFilterOnAccountsAlsoNarrowsLocally() {
        server.stub("GET", "/scim/v2/Users", 200, "{\"totalResults\":2,\"Resources\":["
                + "{\"id\":\"u-1\",\"userName\":\"bob\",\"emails\":[{\"value\":\"bob@x\",\"primary\":true}]},"
                + "{\"id\":\"u-2\",\"userName\":\"carol\",\"emails\":[{\"value\":\"carol@x\",\"primary\":true}]}]}");
        UniversalRestConnector connector = connector();

        List<ConnectorObject> results = search(connector, ObjectClass.ACCOUNT, translate(connector,
                ObjectClass.ACCOUNT, new EqualsFilter(AttributeBuilder.build("email", "carol@x"))));

        assertEquals(results.size(), 1);
        assertEquals(results.get(0).getName().getNameValue(), "carol");
        assertFalse(decodedUri().contains("filter="), "email is not declared pushable: " + decodedUri());
        connector.dispose();
    }

    public void anUnfilteredListFollowsTheDialectPaging() {
        server.stubSequence("/scim/v2/Users", List.of(
                "{\"totalResults\":3,\"startIndex\":1,\"itemsPerPage\":2,\"Resources\":["
                        + "{\"id\":\"u-1\",\"userName\":\"a\"},{\"id\":\"u-2\",\"userName\":\"b\"}]}",
                "{\"totalResults\":3,\"startIndex\":3,\"itemsPerPage\":1,\"Resources\":["
                        + "{\"id\":\"u-3\",\"userName\":\"c\"}]}"));
        UniversalRestConnector connector = connector();

        assertEquals(search(connector, ObjectClass.ACCOUNT, null).size(), 3);
        assertTrue(server.requestUris().get(0).contains("startIndex=1&count=200"),
                server.requestUris().get(0));
        connector.dispose();
    }

    // ---- helpers -----------------------------------------------------------------

    private static final String USER_URN = "urn:straza:params:scim:schemas:extension:2.0:User";
    private static final String GROUP_URN = "urn:straza:params:scim:schemas:extension:2.0:Group";

    private void stubTwoGroups() {
        server.stub("GET", "/scim/v2/Groups", 200, "{\"totalResults\":2,\"Resources\":["
                + "{\"id\":\"r-1\",\"displayName\":\"dev\",\"members\":[{\"value\":\"u-1\"},{\"value\":\"u-2\"}]},"
                + "{\"id\":\"r-2\",\"displayName\":\"ops\",\"members\":[{\"value\":\"u-2\"}]}]}");
    }

    private UniversalRestConnector connector() {
        UniversalRestConfiguration configuration = new UniversalRestConfiguration();
        configuration.setBaseUrl(server.baseUrl());
        configuration.setApiToken(new GuardedString("admin-token".toCharArray()));
        configuration.setSchemaFilePath(TestSchemas.scimClasses());
        UniversalRestConnector connector = new UniversalRestConnector();
        connector.init(configuration);
        return connector;
    }

    private String decodedUri() {
        return URLDecoder.decode(server.lastRequest().uri(), StandardCharsets.UTF_8);
    }

    private static RestFilter translate(UniversalRestConnector connector, ObjectClass objectClass,
                                        org.identityconnectors.framework.common.objects.filter.Filter filter) {
        return connector.createFilterTranslator(objectClass, null).translate(filter).get(0);
    }

    private static ConnectorObject one(UniversalRestConnector connector, ObjectClass objectClass,
                                       RestFilter filter) {
        List<ConnectorObject> results = search(connector, objectClass, filter);
        assertEquals(results.size(), 1, "expected exactly one object");
        return results.get(0);
    }

    private static List<ConnectorObject> search(UniversalRestConnector connector, ObjectClass objectClass,
                                                RestFilter filter) {
        List<ConnectorObject> results = new ArrayList<>();
        connector.executeQuery(objectClass, filter, object -> {
            results.add(object);
            return true;
        }, null);
        return results;
    }
}
