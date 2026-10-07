package ai.straza.connector.rest.schema;

/**
 * The schema file's {@code target} block: the server name and credential hint used
 * in error messages. {@link #fallback()} applies when the block is absent.
 */
public final class TargetInfo {

    private static final TargetInfo FALLBACK =
            new TargetInfo("The server", "Check that the configured API token is valid and carries the "
                    + "scopes this resource needs.");

    private final String name;
    private final String credentialHint;

    public TargetInfo(String name, String credentialHint) {
        this.name = name;
        this.credentialHint = credentialHint;
    }

    /** Generic wording for a file without a {@code target} block. */
    public static TargetInfo fallback() {
        return FALLBACK;
    }

    /** The server name used in messages. */
    public String getName() {
        return name;
    }

    /** How to obtain or fix the token. */
    public String getCredentialHint() {
        return credentialHint;
    }
}
