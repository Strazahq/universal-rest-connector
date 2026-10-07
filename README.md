# universal-rest-connector

A schema-driven [ConnId](https://connid.tirasa.net/) connector for midPoint 4.10.
One JSON schema file declares every object class the connector offers, the
reserved `__ACCOUNT__` and `__GROUP__` included: endpoints, attributes, wire
paths, mutability, the filters the server accepts, the operations each class
supports, and change-feed routing. Onboarding a REST resource is configuration,
not code.

Protocol mechanics live in Java as two dialects, picked per class: `json` for
plain REST and `scim` for SCIM 2.0 (RFC 7643 and 7644). Read-only is the default
for every class and every attribute.

Operations: Test, Schema, Search (with paging), LiveSync over a cursored change
feed, and per class Create, Update, UpdateDelta and Delete. `__ENABLE__` maps to a
wire path the class declares.

A complete Straza configuration (schema file, resource, roles and LiveSync
tasks) ships in [`samples/straza/`](samples/straza/), with the versions it was
tested against.

## Build

Requires JDK 21 and Maven 3. The build fails on any other JDK major version.

```bash
mvn clean verify
```

The build runs the unit tests and produces
`target/universal-rest-connector-<version>.jar`, a self-contained ConnId bundle:
connector classes at the archive root, runtime dependencies under `lib/`, and the
`ConnectorBundle-*` manifest headers. The ConnId framework is not bundled; midPoint
provides it. The bundle is built against ConnId 1.5.2.0 and targets Java 21, so it
loads in midPoint 4.10 (ConnId 1.6.0.0) running on Java 21, as the official images do.

The build also writes a CycloneDX SBOM of the bundled libraries,
`target/universal-rest-connector-<version>.cdx.json`. Builds are reproducible: the
same source and JDK major version give byte-identical files.

Tests against a running Straza server are opt-in TestNG groups (`integration`,
`live`); each test class documents the environment variables it reads.

## Configure

### Connector properties

| Property | Required | Default | Description |
|---|---|---|---|
| `baseUrl` | yes | | REST API base URL, `http` or `https` with a host. |
| `apiToken` | yes | | Bearer token sent on every request. One token serves every object class and the change feed, so its scope must cover everything the schema file declares. |
| `schemaFilePath` | yes, except for Test | | Absolute path to the schema file. |
| `testEndpoint` | no | | Relative path the Test operation GETs, e.g. `/v1/admin/overview`. When blank, Test only checks that the base URL is reachable. |
| `emitAgenticUrn` | no | `true` | Gate for the create-only schema URNs whose `configGate` is `emitAgenticUrn`. |
| `trustAllCertificates` | no | `false` | Development only: accept any TLS certificate. Use the JVM truststore in production. |
| `connectTimeout` | no | `10` | TCP connect timeout in seconds (1 to 300). |
| `readTimeout` | no | `30` | Response read timeout in seconds (1 to 600). |

For Straza, mint the token with `strazactl api-token create --scope
scim:read,scim:write,identity:read,apps:read,changes:read,config:read`.

### Schema file

```json
{
  "target": { "name": "Straza", "credentialHint": "Mint one with strazactl api-token create." },
  "sync": { "feedEndpoint": "/v1/admin/changes" },
  "objects": [
    { "objectClass": "app", "listEndpoint": "/v1/admin/apps",
      "icfsUid": "id", "icfsName": "name",
      "attributes": { "version": {}, "tools": { "multivalued": true } } }
  ]
}
```

`creatable` and `updateable` are false unless the file says otherwise, and
`operations` defaults to `search`, plus `sync` when the class declares a `sync`
block. An unknown key or a contradictory pair of declarations fails the schema
load with a message that says what is wrong and what to write instead. A key
beginning with `//` is a comment.

Top level:

| Key | Meaning |
|---|---|
| `objects` | The object classes, in order. Required. |
| `target` | `name`, the server's name in operator messages, and `credentialHint`, the sentence an operator sees when the token is rejected. |
| `sync` | The change-feed transport, shared by every class that syncs (see LiveSync below). |

Per object class, all optional unless noted:

| Key | Meaning | Example |
|---|---|---|
| `objectClass` | The ConnId class name. Required. `__ACCOUNT__` and `__GROUP__` map to ConnId's reserved classes. | `"__ACCOUNT__"` |
| `dialect` | `json` (default) or `scim`. Only `scim` has a filter grammar and create, replace and patch documents. | `"scim"` |
| `basePath` | A prefix every endpoint of the class hangs under. | `"/scim/v2"` |
| `listEndpoint` | Shorthand for `endpoints.list`. Required unless the class is derived or declares `endpoints.list`. | `"/v1/admin/apps"` |
| `endpoints` | One path per verb: `list`, `byId`, `create`, `replace`, `patch`, `delete`. `{id}` is replaced by the uid. | `{ "list": "/Users", "byId": "/Users/{id}" }` |
| `operations` | The verbs the class offers: `search`, `sync`, `create`, `update`, `updateDelta`, `delete`. Each write verb needs its endpoint, and `update` also needs `byId`. | `["search", "sync", "updateDelta"]` |
| `operationHints` | One sentence per refused verb (or `rename`), appended to the refusal. | `{ "create": "Roles are created in Straza." }` |
| `icfsUid` | Where the ConnId uid lives. Required unless derived. | `"id"` |
| `icfsName` | Where the ConnId name lives. Required unless derived. | `"userName"` |
| `namespaces` | Alias to URN, for attribute paths and the write envelope. | `{ "ext": "urn:example:2.0:User" }` |
| `coreSchema` | The URN that identifies the resource kind in the write envelope. | `"urn:ietf:params:scim:schemas:core:2.0:User"` |
| `enable` | The wire path ConnId `__ENABLE__` maps to. | `"active"` |
| `createOnlySchemas` | Schema URNs added on create only, when one attribute's outgoing value is in a set; `configGate` names a connector property that must also be on. | `[ { "urn": "urn:example:agent", "when": { "attribute": "userType", "in": ["agent"] }, "configGate": "emitAgenticUrn" } ]` |
| `filters.pushable` | ConnId attribute to wire attribute, for the equality filters the server accepts. | `{ "pushable": { "__NAME__": "userName" } }` |
| `paging.pageSize` | Rows per server-side page (default 200). | `{ "pageSize": 200 }` |
| `attributes` | The class's attributes, keyed by ConnId name. `__NAME__` may carry flags here. | see below |
| `associations` | Multivalued reference attributes filled by correlating another endpoint. | see below |
| `derivedFrom` | Take members from a parent class's array field instead of a list endpoint. | see below |
| `sync` | Which feed record types this class consumes and how each maps. | see LiveSync below |

Per attribute, all optional:

| Key | Meaning |
|---|---|
| `path` | Where the value lives; defaults to the attribute name. A bare field (`title`), a namespaced field (`ext:roleKind`), every element of an array (`members[].value`), or the element whose selector matches, falling back to the first (`emails[primary=true].value`). |
| `dataType` | `String` (default), `Boolean`, `Integer` or `Long`. |
| `multivalued` | Default false. |
| `returnedByDefault` | Default true. |
| `required` | Default false. |
| `creatable` | Default false. |
| `updateable` | Default false. |
| `clearVia` | `omit` (default) when dropping the field from a full-document update clears it, or `patchOnly` when the server reads an absent field as "leave alone". It applies to single-valued attributes; an empty multivalued replacement is always sent as an empty array. |

A **derived** class takes its members from a parent's array field. Each element
becomes an object whose uid and name are the element verbatim, plus a parent
reference attribute:

```json
{ "objectClass": "tool", "derivedFrom": { "objectClass": "app", "arrayField": "tools",
    "parentRef": "app", "parentRefValue": "name" } }
```

An **association** collects `valueField` from every row of `endpoint` where
`row[matchField]` equals the object's `localField` and every `where` constraint
holds. Each association endpoint is fetched once per search.

```json
"associations": {
  "roles": { "endpoint": "/v1/admin/assignments", "localField": "id",
             "matchField": "subject_id", "valueField": "role_id",
             "where": { "subject_kind": "user" } }
}
```

A uid query becomes a by-id read when the class declares `endpoints.byId`, and a
single-value query on a `filters.pushable` attribute becomes a server-side filter.
Every other query lists the class. Whatever the lane, the connector narrows the
answer locally before returning it, so a filter the server ignores never widens a
result set.

### LiveSync

The top-level `sync` block describes the feed. `feedEndpoint` is required; the
other keys have defaults: `sinceParam` (`since`), `typesParam` (`types`),
`limitParam` (`limit`), `pageLimit` (`500`), `recordsField` (`changes`),
`headField` (`head`), `nextCursorField` (`nextCursor`), `moreField` (`more`),
`cursorField` (`cursor`), `typeField` (`type`), `opField` (`op`) and `idField`
(`id`).

A class's own `sync` block routes feed records to it:

| Key | Meaning |
|---|---|
| `requestTypes` | Record types requested from the feed. Required. |
| `self` | The record id is an object of this class: create and update re-read it, delete emits a delete. |
| `delete` | The record is always a delete of this class's object with that id. |
| `reemitAll` | The record cannot name the affected object, so every object of the class is re-emitted. |
| `rederive` | Derived classes only: the record id is a parent reference value; that parent's members are re-emitted. |
| `matchField` | `uid` (default) or `name`: how a record id matches an object. |

Each delta carries its record's cursor as the sync token, and delivery is
at-least-once.

## Load into midPoint

1. Copy `target/universal-rest-connector-<version>.jar` into midPoint's
   `icf-connectors/` directory (`/opt/midpoint/var/icf-connectors/` in the
   `evolveum/midpoint` image) and restart midPoint. The connector is listed as
   `ai.straza.connector.rest.UniversalRestConnector`.
2. Copy the schema file onto the midPoint host, for example
   `samples/straza/straza-schema.json` to `/opt/midpoint/var/straza/`.
3. Create the resource and import it under **Configuration → Import object**.
   For Straza, use the objects in [`samples/straza/`](samples/straza/), set
   `baseUrl`, the `apiToken` clear value and `schemaFilePath`, and follow its
   README for the import order. The connector configuration namespace is
   `http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/ai.straza.universal-rest-connector/ai.straza.connector.rest.UniversalRestConnector`.
4. Run **Test connection** on the resource. An authentication error means the
   token is missing, revoked or short on scope; a connection error means the base
   URL is wrong or the server is unreachable.
5. Pull objects with an import task. For incremental pulls, schedule one LiveSync
   task per object class; the Straza configuration includes them.

When the schema file declares `__ACCOUNT__` or `__GROUP__`, midPoint names those
`ri:AccountObjectClass` and `ri:GroupObjectClass` and every other class
`ri:Custom<name>ObjectClass`.

## Releases

Pushing a tag `vX.Y.Z` (or `vX.Y.Z-rc.1` for a pre-release) builds that version
with JDK 21, runs the tests and publishes a GitHub release with:

- `universal-rest-connector-X.Y.Z.jar`, the connector bundle
- `universal-rest-connector-X.Y.Z.cdx.json`, its CycloneDX SBOM
- `SHA256SUMS`
- build provenance and SBOM attestations for the bundle

To verify a downloaded bundle:

```bash
sha256sum --check --ignore-missing SHA256SUMS
gh attestation verify universal-rest-connector-X.Y.Z.jar --repo strazahq/universal-rest-connector
```

To rebuild it byte for byte, check out the tag and run, with JDK 21:

```bash
mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
mvn package -Dproject.build.outputTimestamp="$(git log -1 --format=%ct)"
```

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and
[NOTICE](NOTICE).
