package link.liaru.henyo;

/** Reusable measured geometry for one bottom-anchored comment/plan surface. */
final class ActivityPanelLayout {
    final float[] captionY = new float[3];
    final int[] planIndices = new int[7];
    int firstCaption, planCount, hiddenPlanRows;
    float top, bottom, planTop, summaryY;
    boolean visible, showPlanSummary;

    void measure(float minimumTop, float contentBottom, float padding, float gap,
            int[] captionHeights, int captionCount, int planRows, int activePlanRow,
            float rowHeight, int completionHeight) {
        firstCaption = 0; planCount = 0; hiddenPlanRows = 0; visible = false; showPlanSummary = false;
        float available = Math.max(0f, contentBottom - minimumTop);
        bottom = contentBottom + padding;
        if (completionHeight > 0) {
            top = Math.max(minimumTop, contentBottom - completionHeight) - padding;
            visible = true;
            return;
        }
        int slots = Math.min(planRows, Math.max(0, (int) (available / rowHeight)));
        if (slots < planRows) {
            // Keep the goal and the active neighborhood; make omitted rows explicit.
            planCount = slots == 1 ? 1 : Math.max(0, slots - 1);
            hiddenPlanRows = planRows - planCount;
            if (planCount > 0) planIndices[0] = planCount == 1 ? Math.max(0, activePlanRow) : 0;
            int extra = Math.max(0, planCount - 1);
            int start = Math.max(1, Math.min(Math.max(1, activePlanRow - (extra - 1) / 2), planRows - extra));
            for (int i = 1; i < planCount; i++) planIndices[i] = start + i - 1;
        } else {
            planCount = planRows;
            for (int i = 0; i < planCount; i++) planIndices[i] = i;
        }
        showPlanSummary = hiddenPlanRows > 0 && slots > 1;
        float planHeight = (planCount + (showPlanSummary ? 1 : 0)) * rowHeight;
        float captionHeight = captionHeight(captionHeights, firstCaption, captionCount, gap);
        while (firstCaption < captionCount
                && planHeight + captionHeight + (planHeight > 0 && captionHeight > 0 ? gap : 0) > available) {
            firstCaption++;
            captionHeight = captionHeight(captionHeights, firstCaption, captionCount, gap);
        }
        float cursor = contentBottom;
        for (int i = captionCount - 1; i >= firstCaption; i--) {
            cursor -= captionHeights[i]; captionY[i] = cursor;
            if (i > firstCaption) cursor -= gap;
        }
        if (captionHeight > 0 && planHeight > 0) cursor -= gap;
        planTop = cursor - planHeight;
        summaryY = planTop + planCount * rowHeight;
        top = planTop - padding;
        visible = captionHeight > 0 || planHeight > 0;
    }

    private static float captionHeight(int[] heights, int first, int count, float gap) {
        float result = 0;
        for (int i = first; i < count; i++) result += heights[i] + (i > first ? gap : 0);
        return result;
    }
}
