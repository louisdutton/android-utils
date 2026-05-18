package protect.card_locker;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quickaccesswallet.GetWalletCardsCallback;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletService;
import android.service.quickaccesswallet.SelectWalletCardRequest;
import android.service.quickaccesswallet.WalletCard;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;

import protect.card_locker.cardview.LoyaltyCardViewActivity;

@RequiresApi(Build.VERSION_CODES.R)
public class WalletQuickAccessService extends QuickAccessWalletService {
    private static final String TAG = "WalletQuickAccess";
    private static final int DEFAULT_CARD_WIDTH_PX = 720;
    private static final int DEFAULT_CARD_HEIGHT_PX = 450;
    private static final int DEFAULT_ICON_SIZE_PX = 96;

    @Override
    public PendingIntent getTargetActivityPendingIntent() {
        return getActivityPendingIntent(mainActivityIntent(), 0);
    }

    @Override
    public PendingIntent getGestureTargetActivityPendingIntent() {
        return getTargetActivityPendingIntent();
    }

    @Override
    public void onWalletCardsRequested(
            @NonNull GetWalletCardsRequest request,
            @NonNull GetWalletCardsCallback callback) {
        try (SQLiteDatabase database = new DBHelper(this).getReadableDatabase();
             Cursor cursor = DBHelper.getLoyaltyCardCursor(
                     database,
                     DBHelper.LoyaltyCardArchiveFilter.Unarchived)) {
            int maxCards = Math.max(0, request.getMaxCards());
            List<WalletCard> walletCards = new ArrayList<>();

            while (cursor.moveToNext() && walletCards.size() < maxCards) {
                LoyaltyCard card = LoyaltyCard.fromCursor(this, cursor);
                walletCards.add(toWalletCard(card, request));
            }

            callback.onSuccess(new GetWalletCardsResponse(walletCards, 0));
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to provide quick access wallet cards", exception);
            callback.onFailure(new GetWalletCardsError(
                    Icon.createWithResource(this, R.drawable.ic_launcher_foreground),
                    getString(R.string.wallet_quick_access_error)));
        }
    }

    @Override
    public void onWalletCardSelected(@NonNull SelectWalletCardRequest request) {
        // Loyalty cards are non-payment passes, so selection has no NFC side effect.
    }

    @Override
    public void onWalletDismissed() {
    }

    private WalletCard toWalletCard(LoyaltyCard card, GetWalletCardsRequest request) {
        Icon cardImage = Icon.createWithBitmap(renderCardImage(card, request));
        Intent openIntent = cardActivityIntent(card.id);
        PendingIntent pendingIntent = getActivityPendingIntent(openIntent, card.id);
        CharSequence contentDescription = contentDescription(card);

        WalletCard.Builder builder = new WalletCard.Builder(
                String.valueOf(card.id),
                WalletCard.CARD_TYPE_NON_PAYMENT,
                cardImage,
                contentDescription,
                pendingIntent);
        builder.setCardLabel(label(card));
        builder.setCardIcon(cardIcon(card, request));
        return builder.build();
    }

    private Icon cardIcon(LoyaltyCard card, GetWalletCardsRequest request) {
        Bitmap thumbnail = card.getImageThumbnail(this);
        if (thumbnail != null) {
            return Icon.createWithBitmap(scaleBitmap(thumbnail, iconSize(request), iconSize(request)));
        }

        return Icon.createWithBitmap(
                Utils.generateIcon(this, card.store, card.headerColor).getLetterTile());
    }

    private Bitmap renderCardImage(LoyaltyCard card, GetWalletCardsRequest request) {
        int width = request.getCardWidthPx() > 0 ? request.getCardWidthPx() : DEFAULT_CARD_WIDTH_PX;
        int height = request.getCardHeightPx() > 0 ? request.getCardHeightPx() : DEFAULT_CARD_HEIGHT_PX;
        Bitmap frontImage = card.getImageFront(this);
        if (frontImage != null) {
            return centerCrop(frontImage, width, height);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        int background = dark ? Color.rgb(30, 30, 30) : Color.rgb(250, 250, 250);
        int text = dark ? Color.WHITE : Color.rgb(25, 25, 25);
        int secondaryText = dark ? Color.rgb(198, 198, 198) : Color.rgb(92, 92, 92);
        int accent = card.headerColor != null ? card.headerColor : (dark ? Color.WHITE : Color.BLACK);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(background);
        canvas.drawRoundRect(new RectF(0, 0, width, height), 28, 28, paint);

        paint.setColor(accent);
        canvas.drawRoundRect(new RectF(0, 0, Math.max(8, width / 48f), height), 28, 28, paint);

        float padding = width * 0.09f;
        float titleBaseline = height * 0.37f;
        float subtitleBaseline = height * 0.55f;

        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(text);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titlePaint.setTextSize(Math.max(36f, height * 0.14f));

        TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        subtitlePaint.setColor(secondaryText);
        subtitlePaint.setTextSize(Math.max(24f, height * 0.075f));

        float textWidth = width - (padding * 2);
        canvas.drawText(ellipsize(card.store, titlePaint, textWidth), padding, titleBaseline, titlePaint);

        String subtitle = label(card).toString();
        if (!subtitle.isEmpty()) {
            canvas.drawText(ellipsize(subtitle, subtitlePaint, textWidth), padding, subtitleBaseline, subtitlePaint);
        }

        return bitmap;
    }

    private CharSequence label(LoyaltyCard card) {
        if (card.note != null && !card.note.trim().isEmpty()) {
            return card.note.trim();
        }
        if (card.barcodeType != null) {
            return card.barcodeType.prettyName();
        }
        return "";
    }

    private CharSequence contentDescription(LoyaltyCard card) {
        CharSequence label = label(card);
        if (label.length() == 0) {
            return card.store;
        }
        return getString(R.string.wallet_quick_access_card_description, card.store, label);
    }

    private String ellipsize(String text, TextPaint paint, float width) {
        return TextUtils.ellipsize(text == null ? "" : text, paint, width, TextUtils.TruncateAt.END)
                .toString();
    }

    private int iconSize(GetWalletCardsRequest request) {
        return request.getIconSizePx() > 0 ? request.getIconSizePx() : DEFAULT_ICON_SIZE_PX;
    }

    private Bitmap scaleBitmap(Bitmap source, int width, int height) {
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    private Bitmap centerCrop(Bitmap source, int targetWidth, int targetHeight) {
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        float scale = Math.max(
                targetWidth / (float) source.getWidth(),
                targetHeight / (float) source.getHeight());
        float scaledWidth = source.getWidth() * scale;
        float scaledHeight = source.getHeight() * scale;
        RectF destination = new RectF(
                (targetWidth - scaledWidth) / 2f,
                (targetHeight - scaledHeight) / 2f,
                (targetWidth + scaledWidth) / 2f,
                (targetHeight + scaledHeight) / 2f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, destination, paint);
        return output;
    }

    private Intent mainActivityIntent() {
        return new Intent(this, MainActivity.class)
                .setAction(QuickAccessWalletService.ACTION_VIEW_WALLET)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private Intent cardActivityIntent(int cardId) {
        return new Intent(this, LoyaltyCardViewActivity.class)
                .setAction(QuickAccessWalletService.ACTION_VIEW_WALLET)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LoyaltyCardViewActivity.BUNDLE_ID, cardId);
    }

    private PendingIntent getActivityPendingIntent(Intent intent, int requestCode) {
        return PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
