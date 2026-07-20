/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.keyboard.internal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.annotation.NonNull;

import helium314.keyboard.keyboard.PointerTracker;

/** Draws simple diagnostic tap and gesture points for mixed tap/gesture input. */
public final class TapGestureDrawingPreview extends AbstractDrawingPreview {
    private static final int MAX_POINTS = 192;
    private static final float TAP_RADIUS = 9.0f;
    private static final float GESTURE_RADIUS = 5.0f;

    private final int[] mXs = new int[MAX_POINTS];
    private final int[] mYs = new int[MAX_POINTS];
    private final boolean[] mGesture = new boolean[MAX_POINTS];
    private int mSize;
    private int mKeyboardHeight;

    private final Paint mTapPaint = new Paint();
    private final Paint mGesturePaint = new Paint();
    private final Paint mLinePaint = new Paint();

    public TapGestureDrawingPreview() {
        mTapPaint.setAntiAlias(true);
        mTapPaint.setColor(0xCCFF9800);
        mTapPaint.setStyle(Paint.Style.FILL);

        mGesturePaint.setAntiAlias(true);
        mGesturePaint.setColor(0xCC03A9F4);
        mGesturePaint.setStyle(Paint.Style.FILL);

        mLinePaint.setAntiAlias(true);
        mLinePaint.setColor(0x9903A9F4);
        mLinePaint.setStrokeWidth(3.0f);
        mLinePaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void setKeyboardViewGeometry(@NonNull final int[] originCoords, final int width,
            final int height) {
        super.setKeyboardViewGeometry(originCoords, width, height);
        mKeyboardHeight = height;
    }

    public void clear() {
        mSize = 0;
        invalidateDrawingView();
    }

    public void addPoint(final int x, final int y, final boolean gesture) {
        if (!isPreviewEnabled()) {
            return;
        }
        if (mSize == MAX_POINTS) {
            System.arraycopy(mXs, 1, mXs, 0, MAX_POINTS - 1);
            System.arraycopy(mYs, 1, mYs, 0, MAX_POINTS - 1);
            System.arraycopy(mGesture, 1, mGesture, 0, MAX_POINTS - 1);
            mSize = MAX_POINTS - 1;
        }
        mXs[mSize] = x;
        mYs[mSize] = y;
        mGesture[mSize] = gesture;
        mSize++;
        invalidateDrawingView();
    }

    @Override
    public void onDeallocateMemory() {
        clear();
    }

    @Override
    public void drawPreview(@NonNull final Canvas canvas) {
        if (!isPreviewEnabled() || mSize == 0) {
            return;
        }
        int lastGesture = -1;
        for (int i = 0; i < mSize; i++) {
            if (mGesture[i]) {
                if (lastGesture >= 0) {
                    canvas.drawLine(mXs[lastGesture], mYs[lastGesture], mXs[i], mYs[i], mLinePaint);
                }
                canvas.drawCircle(mXs[i], mYs[i], GESTURE_RADIUS, mGesturePaint);
                lastGesture = i;
            } else {
                canvas.drawCircle(mXs[i], mYs[i], TAP_RADIUS, mTapPaint);
                lastGesture = -1;
            }
        }
    }

    @Override
    public void setPreviewPosition(@NonNull final PointerTracker tracker) {
        // Points are pushed explicitly by PointerTracker.
    }
}
