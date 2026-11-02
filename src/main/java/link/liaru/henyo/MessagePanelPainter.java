package link.liaru.henyo;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** Shared by the settings preview and the real overlay. Never changes text paint. */
final class MessagePanelPainter {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    void draw(Canvas canvas, float left, float top, float right, float bottom,
            float density, int percent, float envelope) {
        int alpha = MessagePanelStyle.fillAlpha(percent, envelope);
        if (alpha == 0) return;
        float radius = 18f * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setAlpha(Math.round(alpha * 22f / 240f));
        bounds.set(left, top + 3f * density, right, bottom + 3f * density);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        bounds.set(left, top, right, bottom);
        paint.setColor(Color.rgb(27, 30, 35));
        paint.setAlpha(alpha);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round(alpha * 30f / 240f));
        canvas.drawRoundRect(bounds, radius, radius, paint);
    }
}
