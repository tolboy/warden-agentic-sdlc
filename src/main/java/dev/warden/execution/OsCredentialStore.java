package dev.warden.execution;

/**
 * A desktop credential vault. Values are dispatch data: never log them, and never
 * substitute another source when this one is absent or unavailable.
 */
public interface OsCredentialStore {
    /** False when this platform has no verified vault. Callers then keep using env references. */
    boolean available();

    /** The stored value, or null when the target is absent. */
    String read(String target);

    void write(String target, String secret);

    /** Absent targets are already removed. */
    void delete(String target);
}
