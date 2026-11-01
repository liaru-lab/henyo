package link.liaru.henyo;

/** Presentation-only observation pulse; no operation waits depend on this state. */
final class ObservationEffectModel {
    static final long FRAME_DURATION_MS = 700L;
    enum Mode {
        FRAME_GLOW("frame_glow", "Frame glow"), SCAN_LINE("scan_line", "Scan line"), OFF("off", "Off");
        final String value, label;
        Mode(String value, String label) { this.value = value; this.label = label; }
        static Mode fromValue(String value) {
            for (Mode mode : values()) if (mode.value.equals(value)) return mode;
            return FRAME_GLOW;
        }
    }

    private volatile Mode mode = Mode.FRAME_GLOW;
    private long frameStartedAt = Long.MIN_VALUE;
    private String lastId;
    Mode mode() { return mode; }
    boolean setMode(Mode next) {
        if (mode == next) return false;
        mode = next; clear(); return true;
    }
    boolean beginFrame(String id, long now) {
        if (mode != Mode.FRAME_GLOW || frameActive(now) || id != null && id.equals(lastId)) return false;
        lastId = id; frameStartedAt = now; return true;
    }
    boolean frameActive(long now) {
        return mode == Mode.FRAME_GLOW && frameStartedAt != Long.MIN_VALUE
                && now >= frameStartedAt && now - frameStartedAt < FRAME_DURATION_MS;
    }
    float frameAlpha(long now) {
        if (!frameActive(now)) return 0f;
        long age = now - frameStartedAt;
        if (age < 140L) return smooth(age / 140f);
        if (age <= 230L) return 1f;
        return 1f - smooth((age - 230L) / 470f);
    }
    void clear() { frameStartedAt = Long.MIN_VALUE; }
    private static float smooth(float value) {
        float x = Math.max(0f, Math.min(1f, value));
        return x * x * (3f - 2f * x);
    }
}
