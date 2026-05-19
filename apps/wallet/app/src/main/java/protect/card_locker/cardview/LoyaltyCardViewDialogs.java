package protect.card_locker.cardview;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.text.style.ForegroundColorSpan;
import android.text.util.Linkify;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.List;
import java.util.ArrayList;

import protect.card_locker.*;

final class LoyaltyCardViewDialogs {
    interface BalanceUpdateListener {
        void onBalanceUpdated(BigDecimal newBalance);
    }

    void showInfoDialog(Context context, LoyaltyCard loyaltyCard, List<Group> loyaltyCardGroups) {
        AlertDialog.Builder infoDialog = new MaterialAlertDialogBuilder(context);

        int dialogContentPadding = context.getResources().getDimensionPixelSize(R.dimen.alert_dialog_content_padding);
        infoDialog.setTitle(loyaltyCard.store);

        TextView infoTextview = new TextView(context);
        infoTextview.setPadding(
                dialogContentPadding,
                dialogContentPadding / 2,
                dialogContentPadding,
                0
        );
        infoTextview.setAutoLinkMask(Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS | Linkify.WEB_URLS);
        infoTextview.setTextIsSelectable(true);

        SpannableStringBuilder infoText = new SpannableStringBuilder();
        if (!loyaltyCard.note.isEmpty()) {
            infoText.append(loyaltyCard.note);
        }

        if (!loyaltyCardGroups.isEmpty()) {
            List<String> groupNames = new ArrayList<>();
            for (Group group : loyaltyCardGroups) {
                groupNames.add(group._id);
            }

            padSpannableString(infoText);
            infoText.append(context.getString(R.string.groupsList, TextUtils.join(", ", groupNames)));
        }

        if (hasBalance(loyaltyCard)) {
            padSpannableString(infoText);
            infoText.append(context.getString(
                    R.string.balanceSentence,
                    Utils.formatBalance(context, loyaltyCard.balance, loyaltyCard.balanceType)
            ));
        }

        appendValidityInfo(infoText, loyaltyCard);

        infoTextview.setText(infoText);
        infoDialog.setView(infoTextview);
        infoDialog.setPositiveButton(R.string.ok, (dialogInterface, i) -> dialogInterface.dismiss());
        infoDialog.create().show();
    }

    void showBalanceUpdateDialog(
            Context context,
            LoyaltyCard loyaltyCard,
            BalanceUpdateListener balanceUpdateListener
    ) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle(R.string.updateBalanceTitle);

        FrameLayout container = new FrameLayout(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        int contentPadding = context.getResources().getDimensionPixelSize(R.dimen.alert_dialog_content_padding);
        params.leftMargin = contentPadding;
        params.topMargin = contentPadding / 2;
        params.rightMargin = contentPadding;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView currentTextview = new TextView(context);
        currentTextview.setText(context.getString(
                R.string.currentBalanceSentence,
                Utils.formatBalance(context, loyaltyCard.balance, loyaltyCard.balanceType)
        ));
        layout.addView(currentTextview);

        final TextInputEditText input = new TextInputEditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789,."));
        input.setHint(R.string.updateBalanceHint);

        layout.addView(input);
        layout.setLayoutParams(params);
        container.addView(layout);
        builder.setView(container);

        builder.setPositiveButton(R.string.spend, (dialogInterface, i) -> {
            try {
                BigDecimal balanceChange = Utils.parseBalance(input.getText().toString(), loyaltyCard.balanceType);
                BigDecimal newBalance = loyaltyCard.balance.subtract(balanceChange).max(new BigDecimal(0));
                balanceUpdateListener.onBalanceUpdated(newBalance);
                Toast.makeText(
                        context,
                        context.getString(
                                R.string.newBalanceSentence,
                                Utils.formatBalance(context, newBalance, loyaltyCard.balanceType)
                        ),
                        Toast.LENGTH_LONG
                ).show();
            } catch (ParseException e) {
                Toast.makeText(context, R.string.amountParsingFailed, Toast.LENGTH_LONG).show();
            }
        });
        builder.setNegativeButton(R.string.receive, (dialogInterface, i) -> {
            try {
                BigDecimal balanceChange = Utils.parseBalance(input.getText().toString(), loyaltyCard.balanceType);
                BigDecimal newBalance = loyaltyCard.balance.add(balanceChange);
                balanceUpdateListener.onBalanceUpdated(newBalance);
                Toast.makeText(
                        context,
                        context.getString(
                                R.string.newBalanceSentence,
                                Utils.formatBalance(context, newBalance, loyaltyCard.balanceType)
                        ),
                        Toast.LENGTH_LONG
                ).show();
            } catch (ParseException e) {
                Toast.makeText(context, R.string.amountParsingFailed, Toast.LENGTH_LONG).show();
            }
        });
        builder.setNeutralButton(context.getString(R.string.cancel), (dialog, which) -> dialog.cancel());
        AlertDialog dialog = builder.create();

        // Button state depends on the parsed input, so listeners must be bound after the dialog exists.
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                BigDecimal balanceChange;

                try {
                    balanceChange = Utils.parseBalance(s.toString(), loyaltyCard.balanceType);
                } catch (ParseException e) {
                    input.setError(context.getString(R.string.amountParsingFailed));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                    return;
                }

                input.setError(null);
                boolean hasNonZeroValue = !balanceChange.equals(new BigDecimal(0));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(hasNonZeroValue);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(hasNonZeroValue);
            }
        });

        dialog.show();
        // Touching dialog buttons before show() can crash because they are not created yet.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        // Focus immediately so the keyboard opens on the amount field.
        input.requestFocus();
    }

    private boolean hasBalance(LoyaltyCard loyaltyCard) {
        return !loyaltyCard.balance.equals(new BigDecimal(0));
    }

    private SpannableStringBuilder padSpannableString(SpannableStringBuilder spannableStringBuilder) {
        if (spannableStringBuilder.length() > 0) {
            spannableStringBuilder.append("\n\n");
        }

        return spannableStringBuilder;
    }

    private void appendValidityInfo(SpannableStringBuilder infoText, LoyaltyCard loyaltyCard) {
        String validityRange = Utils.formatPassValidityRange(loyaltyCard.validFrom, loyaltyCard.expiry);
        if (validityRange.isEmpty()) {
            return;
        }

        padSpannableString(infoText);
        boolean invalidDate = (loyaltyCard.validFrom != null && Utils.isNotYetValid(loyaltyCard.validFrom))
                || (loyaltyCard.expiry != null && Utils.hasExpired(loyaltyCard.expiry));
        if (invalidDate) {
            int start = infoText.length();
            infoText.append(validityRange);
            infoText.setSpan(new ForegroundColorSpan(Color.RED), start, infoText.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        } else {
            infoText.append(validityRange);
        }
    }
}
