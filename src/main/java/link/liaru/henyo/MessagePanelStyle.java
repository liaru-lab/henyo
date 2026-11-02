package link.liaru.henyo;

/** Panel-only opacity, independent of text opacity and presentation fade timing. */
final class MessagePanelStyle {
    static final int DEFAULT_OPACITY = 50;
    static int clamp(int value) { return Math.max(0, Math.min(100, value)); }
    static int fillAlpha(int percent, float envelope) {
        return Math.round(255f * clamp(percent) / 100f * Math.max(0f, Math.min(1f, envelope)));
    }
}
