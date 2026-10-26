package com.martinb.downloados;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Layout helpers so DownloadOS fits every screen: small phones, landscape, tablets. */
final class Layouts {
    private Layouts() {}

    /** Keeps its content at most maxWidth wide and centred (tablets, landscape). */
    static final class MaxWidth extends FrameLayout {
        private final int maxWidth;

        MaxWidth(Context c, int maxWidthPx) {
            super(c);
            maxWidth = maxWidthPx;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int pad = Math.max(0, (w - maxWidth) / 2);
            setPadding(pad, 0, pad, 0);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** A row of buttons that wraps onto the next line instead of squeezing or clipping them. */
    static final class FlowRow extends ViewGroup {
        FlowRow(Context c) { super(c); }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                MarginLayoutParams lp = (MarginLayoutParams) ch.getLayoutParams();
                ch.measure(MeasureSpec.makeMeasureSpec(Math.max(0, maxW - lp.leftMargin - lp.rightMargin), MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = ch.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int chh = ch.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (x > 0 && x + cw > maxW) { y += lineH; x = 0; lineH = 0; }
                x += cw;
                widest = Math.max(widest, x);
                lineH = Math.max(lineH, chh);
            }
            int h = y + lineH + getPaddingTop() + getPaddingBottom();
            int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec)
                    : Math.min(MeasureSpec.getSize(widthSpec), widest + getPaddingLeft() + getPaddingRight());
            setMeasuredDimension(w, resolveSize(h, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                MarginLayoutParams lp = (MarginLayoutParams) ch.getLayoutParams();
                int cw = ch.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                int chh = ch.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
                if (x > 0 && x + cw > maxW) { y += lineH; x = 0; lineH = 0; }
                int cl = getPaddingLeft() + x + lp.leftMargin, ct = getPaddingTop() + y + lp.topMargin;
                ch.layout(cl, ct, cl + ch.getMeasuredWidth(), ct + ch.getMeasuredHeight());
                x += cw;
                lineH = Math.max(lineH, chh);
            }
        }

        @Override
        protected LayoutParams generateDefaultLayoutParams() {
            return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        }

        @Override
        protected LayoutParams generateLayoutParams(LayoutParams p) { return new MarginLayoutParams(p); }

        @Override
        public LayoutParams generateLayoutParams(android.util.AttributeSet attrs) {
            return new MarginLayoutParams(getContext(), attrs);
        }

        @Override
        protected boolean checkLayoutParams(LayoutParams p) { return p instanceof MarginLayoutParams; }
    }
}
