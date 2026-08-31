package link.liaru.henyo;

/** Pure geometry policy for chrome that may remain visible in a target contour. */
final class TargetPresentationPolicy {
    private static final float MAX_STRIP_FRACTION = 0.16f;
    private static final float MIN_SPAN_FRACTION = 0.72f;

    private TargetPresentationPolicy() {}

    static boolean retainsSystemChrome(
            int targetLeft, int targetTop, int targetRight, int targetBottom,
            int blockerLeft, int blockerTop, int blockerRight, int blockerBottom,
            boolean systemWindow) {
        if (!systemWindow) return false;
        int targetWidth = targetRight - targetLeft;
        int targetHeight = targetBottom - targetTop;
        int blockerWidth = blockerRight - blockerLeft;
        int blockerHeight = blockerBottom - blockerTop;
        if (targetWidth <= 0 || targetHeight <= 0 || blockerWidth <= 0 || blockerHeight <= 0) {
            return false;
        }

        int overlapWidth = overlap(targetLeft, targetRight, blockerLeft, blockerRight);
        int overlapHeight = overlap(targetTop, targetBottom, blockerTop, blockerBottom);
        if (overlapWidth <= 0 || overlapHeight <= 0) return false;

        boolean horizontalStrip = blockerHeight <= targetHeight * MAX_STRIP_FRACTION
                && overlapWidth >= targetWidth * MIN_SPAN_FRACTION
                && (crosses(blockerTop, blockerBottom, targetTop)
                || crosses(blockerTop, blockerBottom, targetBottom));
        boolean verticalStrip = blockerWidth <= targetWidth * MAX_STRIP_FRACTION
                && overlapHeight >= targetHeight * MIN_SPAN_FRACTION
                && (crosses(blockerLeft, blockerRight, targetLeft)
                || crosses(blockerLeft, blockerRight, targetRight));
        return horizontalStrip || verticalStrip;
    }

    private static int overlap(int firstStart, int firstEnd, int secondStart, int secondEnd) {
        return Math.max(0, Math.min(firstEnd, secondEnd) - Math.max(firstStart, secondStart));
    }

    private static boolean crosses(int start, int end, int edge) {
        return start <= edge && end >= edge;
    }
}
