package dev.warden.approval;

import java.io.IOException;

/** A stable machine-readable failure from the durable human-decision boundary. */
@SuppressWarnings("serial")
public final class ApprovalException extends IOException {
    private final String code;

    public ApprovalException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public ApprovalException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
