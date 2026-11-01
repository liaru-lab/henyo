package link.liaru.henyo;

public final class ActivityPanelLayoutTest {
    public static void main(String[] args) {
        ActivityPanelLayout panel = new ActivityPanelLayout();
        int[] captions = {44, 44, 44};
        panel.measure(96, 744, 12, 12, captions, 3, 5, 2, 28, 0);
        check(panel.visible && panel.planCount == 5 && panel.firstCaption == 0, "ordinary plan and comment history fit");
        check(panel.top == panel.planTop - 12 && panel.bottom == 756, "one measured padded surface");
        check(panel.planTop + 5 * 28 + 12 <= panel.captionY[0], "plan cannot overlap captions");
        check(panel.captionY[2] + captions[2] == 744, "caption stack stays bottom anchored");
        float oldTop = panel.top;
        panel.measure(96, 744, 12, 12, captions, 1, 5, 2, 28, 0);
        check(panel.top > oldTop, "less content uses a smaller panel");
        panel.measure(96, 300, 12, 12, captions, 3, 7, 5, 38, 0);
        check(panel.firstCaption == 3 && panel.planCount == 4 && panel.hiddenPlanRows == 3,
                "small viewport drops history before compacting plan");
        boolean activeVisible = false;
        for (int i = 0; i < panel.planCount; i++) activeVisible |= panel.planIndices[i] == 5;
        check(activeVisible && panel.planIndices[0] == 0 && panel.showPlanSummary,
                "compacted plan keeps goal, active step, and explicit overflow");
        check(panel.top >= 84 && panel.summaryY + 38 <= 300, "compacted content stays within safe bounds");
        panel.measure(96, 176, 12, 12, captions, 3, 7, 5, 38, 0);
        check(panel.planCount == 1 && panel.planIndices[0] == 5, "very small panel prioritizes current work");
        panel.measure(96, 744, 12, 12, captions, 0, 0, 0, 28, 400);
        check(panel.visible && panel.top == 332 && panel.bottom == 756, "completion shares the same measured surface");
        panel.measure(96, 744, 12, 12, captions, 0, 0, 0, 28, 0);
        check(!panel.visible, "caption-free activity does not draw an empty dark panel");
        panel.measure(96, 560, 12, 12, captions, 2, 5, 2, 38, 0);
        check(panel.bottom == 572 && panel.captionY[1] + captions[1] == 560,
                "keyboard-reduced content viewport keeps the complete surface above input controls");
        panel.measure(96, 40, 12, 12, captions, 3, 5, 2, 38, 0);
        check(!panel.visible, "an unavailable presentation area cannot cover essential controls");
        for (float scale : new float[]{1f, 1.3f, 1.5f, 2f, 3f}) {
            for (int viewport : new int[]{200, 400, 800}) {
                float rowHeight = Math.max(28, 20 * scale + 8);
                int[] measured = {(int) (44 * scale), (int) (44 * scale), (int) (44 * scale)};
                for (int rows = 1; rows <= 7; rows++) {
                    panel.measure(60, viewport - 44, 12, 12, measured, 3, rows, rows - 1, rowHeight, 0);
                    if (!panel.visible) continue;
                    check(panel.top >= 48 && panel.bottom <= viewport, "scaled surface stays in viewport");
                    check(panel.planTop >= 60, "scaled content preserves safe top");
                    if (panel.planCount > 0 && panel.hiddenPlanRows > 0) {
                        boolean found = false;
                        for (int i = 0; i < panel.planCount; i++) found |= panel.planIndices[i] == rows - 1;
                        check(found, "overflow retains current step across font/viewport combinations");
                    }
                }
            }
        }
        System.out.println("ActivityPanelLayoutTest passed");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
