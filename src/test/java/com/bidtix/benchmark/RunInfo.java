package com.bidtix.benchmark;

/** What a strategy is told before each run, so it can prepare (e.g. warm a Redis counter from the database). */
public record RunInfo(Kind kind, long zoneId, int capacity, java.util.List<Long> seatIds) {

    public enum Kind {SEATED, GENERAL_ADMISSION}
}
