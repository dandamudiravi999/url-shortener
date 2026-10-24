package com.urlshortener.orchestrator.core;

public record GateResult(boolean passed, String gate, String reason) {

    public static GateResult pass(String gate) {
        return new GateResult(true, gate, "ok");
    }

    public static GateResult fail(String gate, String reason) {
        return new GateResult(false, gate, reason);
    }
}
