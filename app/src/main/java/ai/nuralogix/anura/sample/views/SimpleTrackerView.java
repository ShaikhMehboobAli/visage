package ai.nuralogix.anura.sample.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.support.annotation.Nullable;
import android.util.AttributeSet;

import ai.nuralogix.anurasdk.views.AbstractTrackerView;


public class SimpleTrackerView extends AbstractTrackerView {

    private static final String TAG = SimpleTrackerView.class.getSimpleName();

    private Paint eraser;
    private Paint redPaint;
    private Paint redCirclePaint;
    private Paint greenArcPaint;
    private Paint greenDotPaint;
    private Paint blackTextPaint;
    final float scale = getResources().getDisplayMetrics().density;
    private Rect textBounds = new Rect();

    public SimpleTrackerView(Context context) {
        super(context);
    }

    public SimpleTrackerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SimpleTrackerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (null != listener) {
            listener.onSizeChanged(w, h, oldw, oldh);
        }
    }

    @Override
    protected void setup() {
        eraser = new Paint();
        eraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        eraser.setAntiAlias(true);

        redPaint = new Paint();
        redPaint.setAntiAlias(true);
        redPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        redPaint.setStrokeWidth(3);
        redPaint.setARGB(255, 255, 0, 0);

        redCirclePaint = new Paint();
        redCirclePaint.setAntiAlias(true);
        redCirclePaint.setStyle(Paint.Style.STROKE);
        redCirclePaint.setStrokeWidth(3);
        redCirclePaint.setARGB(255, 255, 87, 87);

        greenArcPaint = new Paint();
        greenArcPaint.setAntiAlias(true);
        greenArcPaint.setARGB(255, 98, 219, 153);

        greenDotPaint = new Paint();
        greenDotPaint.setAntiAlias(true);
        greenDotPaint.setARGB(255, 98, 219, 153);

        blackTextPaint = new Paint();
        blackTextPaint.setAntiAlias(true);
        blackTextPaint.setTextAlign(Paint.Align.CENTER);
        blackTextPaint.setTextSize((14 * scale) + 0.5f);
        blackTextPaint.setARGB(255, 0, 0, 0);
    }

    @Override
    protected void drawMask(Canvas canvas) {
        canvas.drawColor(Color.argb(192, 0, 0, 0));
        int w = getWidth();
        int h = getHeight();
        float width = (float) ((targetBox.boxWidth_pct * w) / 100);
        float height = (float) ((targetBox.boxHeight_pct * h) / 100);

        float radius = Math.min(width, height) / 2;
        float centerX = (float) (targetBox.boxCenterX_pct * w / 100);
        float centerY = (float) (targetBox.boxCenterY_pct * h / 100);

        RectF rectF = new RectF(centerX - radius - 5, centerY - radius - 12, centerX + radius + 5, centerY + radius + 14);
        canvas.drawArc(rectF, 78, 24, true, redPaint);

        canvas.drawCircle(centerX, centerY, radius, eraser);
    }

    @Override
    protected void drawMeasurementProgress(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float width = (float) ((targetBox.boxWidth_pct * w) / 100);
        float height = (float) ((targetBox.boxHeight_pct * h) / 100);

        float radius = Math.min(width, height) / 2;
        float centerX = (float) (targetBox.boxCenterX_pct * w / 100);
        float centerY = (float) (targetBox.boxCenterY_pct * h / 100);

        RectF rectF = new RectF(centerX - radius - 5, centerY - radius - 5, centerX + radius + 5, centerY + radius + 5);

        // Draw red circle
        canvas.drawCircle((float) centerX, centerY, radius, redCirclePaint);

        // Draw green arc
        canvas.drawArc(rectF, 270, (float) (360 * progressPercent / 100), true, greenArcPaint);
        canvas.drawCircle((float) centerX, centerY, radius - ((4.0f * scale) + 0.5f), eraser);

        // Draw green moving dot
        double angle = 360 * progressPercent / 100.0f;

        int xDot = (int) (Math.sin(Math.toRadians(angle)) * radius);
        int yDot = -(int) (Math.cos(Math.toRadians(angle)) * radius);

        xDot += centerX;
        yDot += centerY;

        canvas.drawCircle(xDot, yDot, (14.0f * scale) + 0.5f, greenDotPaint);

        // Draw elapsed seconds
        String txt = "" + (int) (measurementDurationSecs - measurementDurationSecs * progressPercent / 100);
        blackTextPaint.getTextBounds(txt, 0, txt.length(), textBounds);
        canvas.drawText(txt, xDot, yDot - textBounds.exactCenterY(), blackTextPaint);
    }
}
