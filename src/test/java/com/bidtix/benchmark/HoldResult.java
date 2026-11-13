package com.bidtix.benchmark;

/**
 * What a strategy reports for one request. The harness never trusts it blindly: it checks the database
 * afterwards ({@link InvariantChecker}), so a strategy that says HELD without really holding is caught.
 */
public record HoldResult(Outcome outcome, int retries) {

    public enum Outcome {
        /** The stock is ours. */
        HELD,
        /** Not enough stock / the seat is taken. A correct answer, not a failure. */
        SOLD_OUT,
        /** Gave up after the retry limit although stock may remain (optimistic locking). A false reject. */
        GAVE_UP,
        /** Something broke: deadlock, timeout, unexpected exception. */
        ERROR
    }

    public static HoldResult held() {
        return new HoldResult(Outcome.HELD, 0);
    }

    public static HoldResult held(int retries) {
        return new HoldResult(Outcome.HELD, retries);
    }

    public static HoldResult soldOut() {
        return new HoldResult(Outcome.SOLD_OUT, 0);
    }

    public static HoldResult soldOut(int retries) {
        return new HoldResult(Outcome.SOLD_OUT, retries);
    }

    public static HoldResult gaveUp(int retries) {
        return new HoldResult(Outcome.GAVE_UP, retries);
    }

    public static HoldResult error() {
        return new HoldResult(Outcome.ERROR, 0);
    }
}
