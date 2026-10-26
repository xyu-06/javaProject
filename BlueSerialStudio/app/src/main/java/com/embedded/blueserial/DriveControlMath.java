package com.embedded.blueserial;

/** Shared signed control outputs for the four hold-to-accelerate buttons. */
public final class DriveControlMath {
    private DriveControlMath() { }
    /** Reach full speed after holding for about 1.6 seconds. */
    public static int heldSpeed(long heldMilliseconds) {
        long safe = Math.max(0L, heldMilliseconds);
        return (int) Math.min(100L, 18L + (safe * 82L) / 1600L);
    }
    /** Opposite keys held together cancel on their axis, preventing contradictory motion. */
    public static int buttonAxis(boolean positive, long positiveSince,
            boolean negative, long negativeSince, long now) {
        if (positive == negative) return 0;
        if (positive) return heldSpeed(now - positiveSince);
        return -heldSpeed(now - negativeSince);
    }
}
