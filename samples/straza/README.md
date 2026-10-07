# Straza configuration

A complete midPoint configuration for [Straza](https://straza.ai) on
universal-rest-connector: the connector's schema file and the midPoint objects
that use it.

| Component | Version |
|---|---|
| Straza | 1.1 or later |
| midPoint | 4.10.3, Java 21 |
| universal-rest-connector | 0.1.0 |

## What it does

- **Users:** midPoint masters accounts in Straza over SCIM 2.0. Disabling a
  user deactivates the account and revokes its sessions; deleting it
  deactivates it in Straza. Agent typing (`userType`, `agencyMode`, `sponsor`,
  `swarmId`) is sent from the user's extension, and Straza's `kind` and
  `origin` are read back.
- **Roles:** every Straza role is imported as a midPoint role named
  `BR:<role>` (business roles) or `AR:<role>` (every other kind). Assigning one
  to a user writes the Straza role membership through the Straza role
  metarole. Roles are created, deleted and renamed in Straza.
- **Apps and tools:** every MCP server is imported as a service named
  `MCS:<app>` and every tool as a role named `MCT:<app:tool>`, read-only.
- **Synchronization:** LiveSync tasks follow the Straza change feed. Straza
  accounts without a matching midPoint user are left unlinked, so they are
  never provisioned back.

## Contents

| Path | Object |
|---|---|
| `straza-schema.json` | Connector schema file: object classes, endpoints, attributes, operations |
| `objects/resources/straza-resource.xml` | The Straza resource: object types, mappings, correlation, synchronization, associations |
| `objects/roles/role-straza-metarole.xml` | Held by every imported role; turns a role assignment into Straza membership |
| `objects/roles/role-ar-straza-account.xml` | `AR:Straza:Account`, which owns the Straza account |
| `objects/tasks/livesync-tasks.xml` | LiveSync per object class, every 10 seconds |

## Install

1. Mint the token in Straza:
   `strazactl api-token create --scope scim:read,scim:write,identity:read,apps:read,changes:read,config:read`
2. Copy `straza-schema.json` to the midPoint host, for example
   `/opt/midpoint/var/straza/straza-schema.json`, and the connector JAR to
   `/opt/midpoint/var/icf-connectors/`. Restart midPoint.
3. In `objects/resources/straza-resource.xml` set `baseUrl`, the `apiToken`
   clear value and `schemaFilePath`.
4. Define the extension items below, or remove the mappings that use them.
5. Import the resource, then the roles, and test the resource connection.
6. LiveSync starts from now, so import the existing objects once: run an
   import for each object type of the resource (accounts, apps, tools, roles).
7. Import the LiveSync tasks.

The objects use fixed OIDs (`b6f9f2c8-0d5a-4e77-9a1e-9d3c0e5a1cXX`) and refer
to each other by them; change them together if they collide with yours.
midPoint names the object classes `ri:AccountObjectClass`,
`ri:GroupObjectClass`, `ri:CustomappObjectClass` and
`ri:CustomtoolObjectClass`.

## Extension items

The mappings read and write these items in the namespace
`http://straza.example.com/xml/ns/straza-extension` (prefix `ext`). All are
single-valued strings unless noted.

| Focus type | Item | Use |
|---|---|---|
| UserType | `strazaUserType` | sent as `userType` (`human`, `agent` or `service`) |
| UserType | `strazaAgencyMode` | sent as `agencyMode` (`interactive`, `supervised` or `autonomous`) |
| UserType | `strazaSponsor` | sent as `sponsor`, the accountable human's username |
| UserType | `strazaSwarmId` | sent as `swarmId` |
| UserType | `strazaExternalId` | sent as `externalId`, only on agents with a pinned IdP subject |
| UserType | `strazaPersona` | read from Straza `kind` |
| UserType | `strazaOrigin` | read from Straza `origin` |
| ServiceType | `strazaVersion`, `strazaRuntime`, `strazaStatus`, `strazaSource` | read from the app |
| RoleType | `strazaApp` | read from the tool's app |
| RoleType | `strazaKind` | read from the role kind |
| RoleType | `strazaServer` | read from the role's owning MCP server |
| RoleType | `strazaAdministers` (multivalued) | read from the servers the role administers |
