package com.urlshortener.orchestrator.core;

public enum RunStatus {
    SUCCEEDED,
    /** A node failed or was rejected; completed side effects were compensated. */
    FAILED,
    /** Safe stop: kill switch, deadline, attempt budget or re-plan limit. Nothing new was started. */
    STOPPED
}
