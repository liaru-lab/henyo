package link.liaru.henyo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

final class PairingRingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final float density;
    private float fraction;
    private int seconds;
    private boolean warning;

    PairingRingView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        track.setColor(Color.rgb(210, 214, 220));
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(dp(8));
        progress.setStyle(Paint.Style.STROKE);
        progress.setStrokeCap(Paint.Cap.ROUND);
        progress.setStrokeWidth(dp(8));
        text.setColor(Color.rgb(35, 39, 47));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(dp(24));
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setCountdown(float fraction, int seconds, boolean warning) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        this.seconds = Math.max(0, seconds);
        this.warning = warning;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = dp(120);
        int width = resolveSize(size, widthMeasureSpec);
        int height = resolveSize(size, heightMeasureSpec);
        int resolved = Math.min(width, height);
        setMeasuredDimension(resolved, resolved);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = dp(10);
        arc.set(inset, inset, getWidth() - inset, getHeight() - inset);
        progress.setColor(warning ? Color.rgb(198, 84, 36) : Color.rgb(32, 127, 96));
        canvas.drawArc(arc, -90, 360, false, track);
        canvas.drawArc(arc, -90, 360 * fraction, false, progress);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float baseline = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(seconds + "s", getWidth() / 2f, baseline, text);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
