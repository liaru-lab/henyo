package link.liaru.henyo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** A noninteractive sample; no accessibility events or agent tasks are produced. */
final class MessagePanelPreview extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final MessagePanelPainter panel = new MessagePanelPainter();
    private int opacity;
    private boolean dark;
    MessagePanelPreview(Context context, int opacity) {
        super(context); this.opacity = opacity;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void setOpacity(int value) { opacity = value; invalidate(); }
    void setDark(boolean value) { dark = value; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        canvas.drawColor(dark ? Color.rgb(40, 44, 52) : Color.rgb(234, 239, 247));
        paint.setColor(dark ? 0xff9ba6ba : 0xff68768e); paint.setTextSize(14f*d);
        canvas.drawText("Sample app content", 16f*d, 28f*d, paint);
        for (int i=0; i<5; i++) {
            paint.setColor(dark ? 0xff536078 : 0xffc6d1e3);
            canvas.drawRoundRect(16f*d, (46+i*28)*d, getWidth()-16f*d,
                    (58+i*28)*d, 4f*d, 4f*d, paint);
        }
        panel.draw(canvas, 8f*d, 58f*d, getWidth()-8f*d, 199f*d, d, opacity, 1f);
        paint.setColor(0xfff2f4f7); paint.setTextSize(14f*d);
        String[] lines={"Finding the matching result…", "✓ Enter search", "• Check result", "  Open the selected item", "Completed"};
        for(int i=0;i<lines.length;i++) canvas.drawText(lines[i],22f*d,(83+i*24)*d,paint);
    }
}
