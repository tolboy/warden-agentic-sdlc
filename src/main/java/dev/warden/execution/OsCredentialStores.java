package dev.warden.execution;

import java.util.Locale;

/** Env mode does not require a vault. Other platforms stay unavailable rather than storing plaintext. */
public final class OsCredentialStores {
    private static final OsCredentialStore UNAVAILABLE = new OsCredentialStore() {
        @Override public boolean available() { return false; }
        @Override public String read(String target) { throw closed(); }
        @Override public void write(String target, String secret) { throw closed(); }
        @Override public void delete(String target) { throw closed(); }
    };
    private static final OsCredentialStore PLATFORM = windows() ? new WindowsOsCredentialStore() : UNAVAILABLE;

    private OsCredentialStores() {}

    public static OsCredentialStore platform() { return PLATFORM; }

    public static OsCredentialStore unavailable() { return UNAVAILABLE; }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static IllegalStateException closed() {
        return new IllegalStateException("connection credential source is unavailable");
    }
}
