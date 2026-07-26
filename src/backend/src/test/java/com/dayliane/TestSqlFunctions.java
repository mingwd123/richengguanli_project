package com.dayliane;

import java.sql.Timestamp;
import java.time.Instant;

public final class TestSqlFunctions {
    private TestSqlFunctions() {
    }

    public static Timestamp utcTimestamp() {
        return Timestamp.from(Instant.now());
    }
}