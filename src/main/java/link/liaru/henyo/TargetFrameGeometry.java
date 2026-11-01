package link.liaru.henyo;

/** An intentional continuous frame or four corner brackets, never arbitrary holes. */
final class TargetFrameGeometry {
    final float left, top, right, bottom, radius, cornerLength;
    final boolean cornersOnly;
    private final boolean visible;
    TargetFrameGeometry(int left, int top, int right, int bottom, long visibleArea, float density) {
        float safeDensity = Math.max(0.5f, density);
        float shortSide = Math.max(0f, Math.min(right - left, bottom - top));
        float inset = Math.min(6f * safeDensity, shortSide * 0.1f);
        this.left = left + inset; this.top = top + inset;
        this.right = right - inset; this.bottom = bottom - inset;
        radius = Math.min(18f * safeDensity, Math.max(0f, shortSide - 2f * inset) * 0.15f);
        cornerLength = Math.min(36f * safeDensity, Math.max(0f, shortSide - 2f * inset) * 0.3f);
        long area = Math.max(0L, (long) right - left) * Math.max(0L, (long) bottom - top);
        cornersOnly = area > 0 && Math.max(0L, visibleArea) < area * 0.65;
        visible = visibleArea > 0;
    }
    boolean valid() { return visible && right > left && bottom > top; }
}
