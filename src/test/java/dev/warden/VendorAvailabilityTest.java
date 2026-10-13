package dev.warden;

import dev.warden.execution.VendorAvailability;
import dev.warden.json.Json;
import dev.warden.ledger.MeasurementProjector;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A location or eligibility refusal is a vendor failure of its own, and only when the
 * call already failed. A review that quotes the sentence is not one.
 */
public final class VendorAvailabilityTest implements Suite {

    @Override public String name() { return "vendor availability"; }

    private static final String GEO = "PERMISSION_DENIED (code 403): Your current account is not "
            + "eligible for Gemini Code Assist for individuals because it is not currently "
            + "available in your location";

    @Override public void run(Check check) {
        VendorAvailability.Detection stderr = VendorAvailability.detect("", GEO);
        check.that("the live agy sentence on stderr is a refusal", stderr.matched());
        check.eq("from stderr text", "stderr_text", stderr.detectedBy());
        check.eq("the location phrase is the one recorded",
                "available in your location", stderr.signature());
        check.eq("the role code is not a generic command failure",
                "role_vendor_unavailable", stderr.roleCode());
        check.eq("and the kind is vendor_unavailable",
                "vendor_unavailable", stderr.report().get("kind"));

        String event = "{\"type\":\"error\",\"message\":\"" + GEO + "\"}";
        VendorAvailability.Detection structured = VendorAvailability.detect(event, "");
        check.eq("a structured error event is the stronger source",
                "structured_error_event", structured.detectedBy());

        String envelope = "{\"status\":\"ERROR\",\"error\":\"" + GEO + "\",\"retryable\":false}";
        check.that("an exit-0 error envelope still matches",
                VendorAvailability.detect(envelope, "").matched());

        check.that("the digits alone are not a signature",
                !VendorAvailability.detect("", "status=403").matched());
        check.that("a dropped connection is not this",
                !VendorAvailability.detect("", "connect: connection reset by peer").matched());

        String review = """
                {"role":"reviewer","task_id":"hello","status":"completed","verdict":"fail",
                "summary":"the vendor said the account is not eligible and not available in your location",
                "findings":[]}
                """;
        check.that("a review that quotes the sentence is not reclassified",
                !VendorAvailability.detect(review, "").matched());
        check.that("nor is the same sentence as loose stdout with an empty stderr",
                !VendorAvailability.detect("not eligible because it is not available in your location", "")
                        .matched());

        check.that("permission denied on stderr matches",
                VendorAvailability.detect("", "HTTP 403 permission denied").matched());
        check.eq("by the http 403 phrase, ahead of the words",
                "http 403", VendorAvailability.detect("", "HTTP 403 permission denied").signature());

        Map<String, Object> local = new LinkedHashMap<>();
        local.put("type", "vendor_attempt");
        local.put("ok", false);
        local.put("code", "role_vendor_unavailable");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "vendor_unavailable");
        body.put("detected_by", "stderr_text");
        body.put("matched_signature", "available in your location");
        body.put("vendor_message", GEO);
        local.put("vendor_unavailable", body);
        String projected = Json.write(MeasurementProjector.project(local).body());
        check.that("the corpus keeps the signature", projected.contains("available in your location"));
        check.that("and drops the vendor sentence", !projected.contains("PERMISSION_DENIED"));
        check.that("and drops the words not eligible from that sentence",
                !projected.contains("Gemini"));
    }
}
