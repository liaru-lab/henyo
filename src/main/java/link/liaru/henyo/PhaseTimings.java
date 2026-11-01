package link.liaru.henyo;

import java.util.Arrays;

/** Fixed-size recent samples; never retains request identifiers or UI content. */
final class PhaseTimings {
    enum Phase { REQUEST_QUEUE, TARGET_RESOLUTION, UI_TRAVERSAL, GESTURE_DISPATCH,
        GESTURE_COMPLETION, CAPTURE_ACQUISITION, BITMAP_COPY, PNG_ENCODING, PRESENTATION }
    private static final int CAPACITY = 256;
    private final long[][] samples = new long[Phase.values().length][CAPACITY];
    private final long[] counts = new long[Phase.values().length];
    synchronized void record(Phase phase, long nanos) {
        int index = phase.ordinal();
        samples[index][(int) (counts[index]++ % CAPACITY)] = Math.max(0L, nanos / 1000L);
    }
    synchronized void reset() { Arrays.fill(counts, 0L); }
    synchronized String toJson() {
        StringBuilder out = new StringBuilder("{");
        for (Phase phase : Phase.values()) {
            int index = phase.ordinal(), size = (int) Math.min(CAPACITY, counts[index]);
            long[] sorted = Arrays.copyOf(samples[index], size); Arrays.sort(sorted);
            if (index > 0) out.append(',');
            out.append('"').append(phase.name().toLowerCase(java.util.Locale.ROOT)).append("\":{\"count\":")
                    .append(counts[index]).append(",\"retained\":").append(size)
                    .append(",\"p50Us\":").append(percentile(sorted, .5))
                    .append(",\"p95Us\":").append(percentile(sorted, .95)).append('}');
        }
        return out.append('}').toString();
    }
    private static long percentile(long[] values, double fraction) {
        return values.length == 0 ? 0L : values[(int) Math.ceil(values.length * fraction) - 1];
    }
}
