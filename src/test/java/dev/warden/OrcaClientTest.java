package dev.warden;

import dev.warden.execution.orca.OrcaClient;
import dev.warden.json.Json;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.Map;

public final class OrcaClientTest implements Suite {
    @Override public String name() { return "orca-client"; }

    @Override public void run(Check check) {
        Map<String, Object> ready = OrcaClient.summarize(Json.parseObject("""
                {"result":{"app":{"running":true},"runtime":{"reachable":true,"state":"ready",
                "runtimeId":"r1","appVersion":"1.2.3","capabilities":["orchestration.contract.v1"]}}}
                """));
        check.eq("ready runtime is available", true, ready.get("available"));
        check.eq("orchestration capability detected", true, ready.get("orchestration_contract"));
        check.eq("version retained", "1.2.3", ready.get("app_version"));

        Map<String, Object> stopped = OrcaClient.summarize(Json.parseObject("""
                {"result":{"app":{"running":false},"runtime":{"reachable":false,"capabilities":[]}}}
                """));
        check.eq("stopped runtime is unavailable", false, stopped.get("available"));
        check.eq("missing envelope fails closed", false, OrcaClient.summarize(Map.of()).get("available"));
    }
}
