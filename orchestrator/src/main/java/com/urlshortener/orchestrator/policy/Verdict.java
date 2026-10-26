package com.urlshortener.orchestrator.policy;

/** Ordered by severity so the strictest finding wins. */
public enum Verdict {
    ALLOW, REQUIRE_APPROVAL, DENY
}
