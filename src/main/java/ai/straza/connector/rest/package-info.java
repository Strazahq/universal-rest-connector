/**
 * Schema-driven ConnId REST connector for midPoint.
 *
 * <p>A JSON schema file declares every object class, {@code __ACCOUNT__} and
 * {@code __GROUP__} included. Packages: {@code schema} parses the file,
 * {@code dialect} holds the JSON and SCIM protocol rules, {@code objects} maps and
 * writes resources, {@code transport} sends requests, {@code filter} translates
 * ConnId filters and {@code sync} reads the change feed.</p>
 */
package ai.straza.connector.rest;
