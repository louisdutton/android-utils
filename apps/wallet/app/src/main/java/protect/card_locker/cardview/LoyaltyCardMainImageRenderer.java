package protect.card_locker.cardview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.nio.charset.Charset;

import protect.card_locker.*;
import protect.card_locker.async.TaskHandler;

final class LoyaltyCardMainImageRenderer {
    private static final float SCANNABLE_CODE_WIDTH_FRACTION = 0.82f;

    private final Context context;
    private final TaskHandler tasks = new TaskHandler();
    private final BarcodeImageWriterResultCallback barcodeCallback;

    LoyaltyCardMainImageRenderer(
            Context context,
            BarcodeImageWriterResultCallback barcodeCallback
    ) {
        this.context = context;
        this.barcodeCallback = barcodeCallback;
    }

    void renderCurrent(
            LoyaltyCardImageType imageType,
            Bitmap frontImageBitmap,
            Bitmap backImageBitmap,
            CatimaBarcode format,
            Charset barcodeEncoding,
            String cardIdString,
            String barcodeIdString,
            ImageView barcodeRenderTarget,
            TextView mainImageDescription,
            MaterialCardView mainCardView,
            boolean waitForResize
    ) {
        if (imageType == LoyaltyCardImageType.NONE) {
            // With no renderable media left, show the raw card ID instead of an empty card area.
            resetImageLayout(barcodeRenderTarget, mainImageDescription, mainCardView);
            barcodeRenderTarget.setVisibility(View.GONE);
            if (mainCardView != null) {
                mainCardView.setCardBackgroundColor(Color.TRANSPARENT);
                mainCardView.setContentPadding(0, 0, 0, 0);
            }
            mainImageDescription.setVisibility(View.VISIBLE);
            mainImageDescription.setTextColor(
                    MaterialColors.getColor(
                            mainImageDescription,
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
            );
            mainImageDescription.setText(cardIdString);
            return;
        }

        if (imageType == LoyaltyCardImageType.BARCODE) {
            applyBarcodeLayout(format, barcodeRenderTarget, mainImageDescription, mainCardView);
            barcodeRenderTarget.setBackgroundColor(Color.WHITE);
            if (mainCardView != null) {
                mainCardView.setCardBackgroundColor(Color.WHITE);
            }
            mainImageDescription.setVisibility(View.VISIBLE);
            mainImageDescription.setTextColor(context.getResources().getColor(R.color.md_theme_light_onSurfaceVariant));

            redrawBarcodeAfterResize(
                    barcodeRenderTarget,
                    barcodeIdString,
                    cardIdString,
                    format,
                    barcodeEncoding,
                    true
            );

            mainImageDescription.setText(cardIdString);
            barcodeRenderTarget.setContentDescription(
                    context.getString(R.string.barcodeImageDescriptionWithType, format.prettyName())
            );
        } else if (imageType == LoyaltyCardImageType.IMAGE_FRONT) {
            resetImageLayout(barcodeRenderTarget, mainImageDescription, mainCardView);
            barcodeRenderTarget.setImageBitmap(frontImageBitmap);
            barcodeRenderTarget.setBackgroundColor(Color.TRANSPARENT);
            if (mainCardView != null) {
                mainCardView.setCardBackgroundColor(Color.TRANSPARENT);
                mainCardView.setContentPadding(0, 0, 0, 0);
            }
            mainImageDescription.setVisibility(View.VISIBLE);
            mainImageDescription.setTextColor(
                    MaterialColors.getColor(
                            mainImageDescription,
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
            );
            mainImageDescription.setText(context.getString(R.string.frontImageDescription));
            barcodeRenderTarget.setContentDescription(context.getString(R.string.frontImageDescription));
        } else if (imageType == LoyaltyCardImageType.IMAGE_BACK) {
            resetImageLayout(barcodeRenderTarget, mainImageDescription, mainCardView);
            barcodeRenderTarget.setImageBitmap(backImageBitmap);
            barcodeRenderTarget.setBackgroundColor(Color.TRANSPARENT);
            if (mainCardView != null) {
                mainCardView.setCardBackgroundColor(Color.TRANSPARENT);
                mainCardView.setContentPadding(0, 0, 0, 0);
            }
            mainImageDescription.setVisibility(View.VISIBLE);
            mainImageDescription.setTextColor(
                    MaterialColors.getColor(
                            mainImageDescription,
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
            );
            mainImageDescription.setText(context.getString(R.string.backImageDescription));
            barcodeRenderTarget.setContentDescription(context.getString(R.string.backImageDescription));
        } else {
            throw new IllegalArgumentException("Unknown image type: " + imageType);
        }

        barcodeRenderTarget.setVisibility(View.VISIBLE);
    }

    private void applyBarcodeLayout(
            CatimaBarcode format,
            ImageView barcodeRenderTarget,
            TextView mainImageDescription,
            MaterialCardView mainCardView
    ) {
        int cardPadding = context.getResources().getDimensionPixelSize(R.dimen.scannable_code_padding);
        int textSpacing = context.getResources().getDimensionPixelSize(R.dimen.scannable_code_text_spacing);
        int textHeightEstimate = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                28,
                context.getResources().getDisplayMetrics()
        ));

        int maxCardWidth = getAvailableCardWidth(mainCardView);
        int maxCardHeight = getAvailableCardHeight(mainCardView);
        int maxCodeWidth = Math.max(1, maxCardWidth - (cardPadding * 2));
        int maxCodeHeight = Math.max(1, maxCardHeight - (cardPadding * 2) - textSpacing - textHeightEstimate);

        int targetCodeWidth = Math.max(1, Math.round(maxCodeWidth * SCANNABLE_CODE_WIDTH_FRACTION));
        int codeWidth = Math.min(targetCodeWidth, maxCodeHeight);
        int codeHeight = codeWidth;
        codeWidth = Math.max(1, codeWidth);
        codeHeight = Math.max(1, codeHeight);

        if (mainCardView != null) {
            ViewGroup.LayoutParams cardLayoutParams = mainCardView.getLayoutParams();
            cardLayoutParams.width = codeWidth + (cardPadding * 2);
            cardLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mainCardView.setLayoutParams(cardLayoutParams);
            mainCardView.setContentPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        }

        ViewGroup parent = barcodeRenderTarget.getParent() instanceof ViewGroup
                ? (ViewGroup) barcodeRenderTarget.getParent()
                : null;
        if (parent != null) {
            ViewGroup.LayoutParams parentLayoutParams = parent.getLayoutParams();
            parentLayoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            parentLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            parent.setLayoutParams(parentLayoutParams);
        }

        LinearLayout.LayoutParams imageLayoutParams = new LinearLayout.LayoutParams(codeWidth, codeHeight);
        barcodeRenderTarget.setLayoutParams(imageLayoutParams);
        barcodeRenderTarget.setScaleType(ImageView.ScaleType.FIT_CENTER);

        LinearLayout.LayoutParams descriptionLayoutParams = new LinearLayout.LayoutParams(
                codeWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        descriptionLayoutParams.topMargin = textSpacing;
        mainImageDescription.setLayoutParams(descriptionLayoutParams);
        mainImageDescription.setGravity(Gravity.CENTER);
        mainImageDescription.setSingleLine(true);
        mainImageDescription.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mainImageDescription.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.scannable_code_text_size)
        );
        barcodeRenderTarget.requestLayout();
    }

    private int getAvailableCardWidth(MaterialCardView mainCardView) {
        int fallbackWidth = context.getResources().getDisplayMetrics().widthPixels
                - Math.round(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        32,
                        context.getResources().getDisplayMetrics()
                ));
        int parentWidth = fallbackWidth;
        if (mainCardView != null && mainCardView.getParent() instanceof View) {
            int measuredParentWidth = ((View) mainCardView.getParent()).getWidth();
            if (measuredParentWidth > 0) {
                parentWidth = measuredParentWidth;
            }
        }

        int horizontalMargins = getHorizontalMargins(mainCardView);
        return Math.max(1, parentWidth - horizontalMargins);
    }

    private int getAvailableCardHeight(MaterialCardView mainCardView) {
        int fallbackHeight = context.getResources().getDisplayMetrics().heightPixels / 2;
        int parentHeight = fallbackHeight;
        if (mainCardView != null && mainCardView.getParent() instanceof View) {
            int measuredParentHeight = ((View) mainCardView.getParent()).getHeight();
            if (measuredParentHeight > 0) {
                parentHeight = measuredParentHeight;
            }
        }

        int verticalMargins = getVerticalMargins(mainCardView);
        return Math.max(1, parentHeight - verticalMargins);
    }

    private int getHorizontalMargins(View view) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return 0;
        }

        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        return layoutParams.leftMargin + layoutParams.rightMargin;
    }

    private int getVerticalMargins(View view) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return 0;
        }

        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        return layoutParams.topMargin + layoutParams.bottomMargin;
    }

    private void resetImageLayout(
            ImageView barcodeRenderTarget,
            TextView mainImageDescription,
            MaterialCardView mainCardView
    ) {
        if (mainCardView != null) {
            ViewGroup.LayoutParams cardLayoutParams = mainCardView.getLayoutParams();
            cardLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            cardLayoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mainCardView.setLayoutParams(cardLayoutParams);
        }

        ViewGroup parent = barcodeRenderTarget.getParent() instanceof ViewGroup
                ? (ViewGroup) barcodeRenderTarget.getParent()
                : null;
        if (parent != null) {
            ViewGroup.LayoutParams parentLayoutParams = parent.getLayoutParams();
            parentLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            parentLayoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            parent.setLayoutParams(parentLayoutParams);
        }

        LinearLayout.LayoutParams imageLayoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        );
        barcodeRenderTarget.setLayoutParams(imageLayoutParams);
        barcodeRenderTarget.setScaleType(ImageView.ScaleType.FIT_CENTER);

        LinearLayout.LayoutParams descriptionLayoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mainImageDescription.setLayoutParams(descriptionLayoutParams);
        mainImageDescription.setGravity(Gravity.CENTER);
        mainImageDescription.setSingleLine(true);
        mainImageDescription.setEllipsize(TextUtils.TruncateAt.END);
        mainImageDescription.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.text_size_large)
        );
    }

    private void redrawBarcodeAfterResize(
            ImageView barcodeRenderTarget,
            String barcodeIdString,
            String cardIdString,
            CatimaBarcode format,
            Charset barcodeEncoding,
            boolean addPadding
    ) {
        if (format == null) {
            return;
        }

        // Barcode dimensions depend on the final ImageView size, so wait for layout before rendering.
        barcodeRenderTarget.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        barcodeRenderTarget.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        drawBarcode(
                                barcodeRenderTarget,
                                barcodeIdString,
                                cardIdString,
                                format,
                                barcodeEncoding,
                                addPadding
                        );
                    }
                });
        barcodeRenderTarget.requestLayout();
    }

    private void drawBarcode(
            ImageView barcodeRenderTarget,
            String barcodeIdString,
            String cardIdString,
            CatimaBarcode format,
            Charset barcodeEncoding,
            boolean addPadding
    ) {
        // Barcodes are regenerated eagerly because the output bitmap depends on the measured target size.
        tasks.flushTaskList(TaskHandler.TYPE.BARCODE, true, false, false);
        if (format == null) {
            return;
        }

        BarcodeImageWriterTask barcodeWriter = new BarcodeImageWriterTask(
                context.getApplicationContext(),
                barcodeRenderTarget,
                barcodeIdString != null ? barcodeIdString : cardIdString,
                format,
                barcodeEncoding,
                null,
                false,
                barcodeCallback,
                addPadding
        );
        tasks.executeTask(TaskHandler.TYPE.BARCODE, barcodeWriter);
    }
}
