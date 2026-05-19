package protect.card_locker.cardview;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.text.util.Linkify;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import protect.card_locker.*;
import protect.card_locker.databinding.LoyaltyCardPassPageBinding;
import protect.card_locker.databinding.LoyaltyCardViewLayoutBinding;
import protect.card_locker.preferences.Settings;
import protect.card_locker.preferences.SettingsActivity;

public class LoyaltyCardViewActivity extends CatimaAppCompatActivity implements BarcodeImageWriterResultCallback {
    private LoyaltyCardViewLayoutBinding binding;
    private static final String TAG = "Catima";

    int loyaltyCardId;
    ArrayList<Integer> bundledCardList = new ArrayList<>();

    LoyaltyCard loyaltyCard;
    List<Group> loyaltyCardGroups;
    boolean rotationEnabled;
    SQLiteDatabase database;
    ImportURIHelper importURIHelper;
    Settings settings;

    String cardIdString;
    String barcodeIdString;
    CatimaBarcode format;
    Charset barcodeEncoding;

    Bitmap frontImageBitmap;
    Bitmap backImageBitmap;

    LoyaltyCardImageNavigator cardNavigator = new LoyaltyCardImageNavigator(new ArrayList<>(), 0);
    private final LoyaltyCardViewDialogs dialogs = new LoyaltyCardViewDialogs();
    // Used only to seed the first navigator after recreation, before card data has been reloaded.
    private Integer restoredImageIndex = null;
    private PassPagerAdapter cardPagerAdapter;
    private ViewPager2.OnPageChangeCallback cardPagerCallback;
    private boolean syncingPagerSelection = false;

    public static final String STATE_IMAGEINDEX = "imageIndex";

    public static final String BUNDLE_ID = "id";
    public static final String BUNDLE_TRANSITION_RIGHT = "transition_right";

    private long initTime = System.currentTimeMillis();

    private enum AdjacentCardDirection {
        PREVIOUS,
        NEXT
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (settings.useVolumeKeysForNavigation()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (initTime < (System.currentTimeMillis() - 1000)) {
                    navigateToAdjacentBundleCard(AdjacentCardDirection.PREVIOUS);
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (initTime < (System.currentTimeMillis() - 1000)) {
                    navigateToAdjacentBundleCard(AdjacentCardDirection.NEXT);
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void onMainImageTap() {
        LoyaltyCardImageType imageType = cardNavigator.getCurrent();
        if (imageType == LoyaltyCardImageType.BARCODE) {
            showCardIdDialog(loyaltyCard);
            return;
        }

        // If this is an image, open it in the gallery.
        openImageInGallery(imageType);
    }

    private void openImageInGallery(LoyaltyCardImageType imageType) {
        openImageInGallery(loyaltyCardId, imageType);
    }

    private void openImageInGallery(int cardId, LoyaltyCardImageType imageType) {
        File file = null;

        switch (imageType) {
            case NONE:
                return;
            case ICON:
                file = Utils.retrieveCardImageAsFile(this, cardId, ImageLocationType.icon);
                break;
            case IMAGE_FRONT:
                file = Utils.retrieveCardImageAsFile(this, cardId, ImageLocationType.front);
                break;
            case IMAGE_BACK:
                file = Utils.retrieveCardImageAsFile(this, cardId, ImageLocationType.back);
                break;
            case BARCODE:
                Toast.makeText(this, R.string.barcodeLongPressMessage, Toast.LENGTH_SHORT).show();
                return;
        }

        // Do nothing if there is no file
        if (file == null) {
            Toast.makeText(this, R.string.failedToRetrieveImageFile, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID, file), "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Display a toast message if an image viewer is not installed on device
            Toast.makeText(this, R.string.failedLaunchingPhotoPicker, Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    @Override
    public void onBarcodeImageWriterResult(boolean success) {
        if (!success) {
            // If barcode rendering fails, drop that slot so the user falls back to working content.
            cardNavigator.remove(LoyaltyCardImageType.BARCODE);

            if (cardPagerAdapter != null) {
                cardPagerAdapter.notifyDataSetChanged();
            }

            Toast.makeText(LoyaltyCardViewActivity.this, getString(R.string.wrongValueForBarcodeType), Toast.LENGTH_LONG).show();
        }
    }

    private void extractIntentFields(Intent intent) {
        final Bundle b = intent.getExtras();
        loyaltyCardId = b != null ? b.getInt(BUNDLE_ID) : 0;
        Log.d(TAG, "View activity: id=" + loyaltyCardId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            Bundle incomingIntentExtras = getIntent().getExtras();

            if (incomingIntentExtras == null) {
                Toast.makeText(this, R.string.noCardExistsError, Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            int transitionRight = incomingIntentExtras.getInt(BUNDLE_TRANSITION_RIGHT, -1);
            if (transitionRight == 1) {
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            } else if (transitionRight == 0) {
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
            }
        }

        super.onCreate(savedInstanceState);
        binding = LoyaltyCardViewLayoutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Utils.applyWindowInsets(binding.getRoot());
        styleBundlePositionIndicator();
        Toolbar toolbar = binding.toolbar;
        setSupportActionBar(toolbar);

        settings = new Settings(this);

        if (savedInstanceState != null) {
            restoredImageIndex = savedInstanceState.getInt(STATE_IMAGEINDEX, 0);
        }

        extractIntentFields(getIntent());

        database = new DBHelper(this).getWritableDatabase();
        importURIHelper = new ImportURIHelper(this);
        setupCardPager();

        rotationEnabled = true;

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }

    private void setupCardPager() {
        binding.cardPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
        cardPagerAdapter = new PassPagerAdapter(new ArrayList<>());
        binding.cardPager.setAdapter(cardPagerAdapter);
        binding.cardPager.setOffscreenPageLimit(1);

        cardPagerCallback = new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (syncingPagerSelection || position < 0 || position >= bundledCardList.size()) {
                    return;
                }

                selectDisplayedCard(bundledCardList.get(position));
                updateBundlePositionIndicator();
            }
        };
        binding.cardPager.registerOnPageChangeCallback(cardPagerCallback);
    }

    private void styleBundlePositionIndicator() {
        int backgroundColor = MaterialColors.getColor(
                binding.bundlePositionIndicator,
                com.google.android.material.R.attr.colorSurfaceVariant
        );
        int strokeColor = MaterialColors.getColor(
                binding.bundlePositionIndicator,
                com.google.android.material.R.attr.colorOutline
        );
        int textColor = MaterialColors.getColor(
                binding.bundlePositionIndicator,
                com.google.android.material.R.attr.colorOnSurfaceVariant
        );

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(backgroundColor);
        background.setCornerRadius(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16,
                getResources().getDisplayMetrics()
        ));
        background.setStroke(1, strokeColor);
        binding.bundlePositionIndicator.setBackground(background);
        binding.bundlePositionIndicator.setTextColor(textColor);
    }

    private boolean hasBalance(LoyaltyCard loyaltyCard) {
        return !loyaltyCard.balance.equals(new BigDecimal(0));
    }

    private void navigateToAdjacentBundleCard(AdjacentCardDirection direction) {
        if (bundledCardList == null || bundledCardList.size() < 2) {
            return;
        }

        boolean next = direction == AdjacentCardDirection.NEXT;
        if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            next = !next;
        }

        int currentItem = binding.cardPager.getCurrentItem();
        int nextItem = next
                ? (currentItem == bundledCardList.size() - 1 ? 0 : currentItem + 1)
                : (currentItem == 0 ? bundledCardList.size() - 1 : currentItem - 1);
        binding.cardPager.setCurrentItem(nextItem, true);
    }

    private void updateIntentCardId(int cardId) {
        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            bundle = new Bundle();
        }

        bundle.putInt(BUNDLE_ID, cardId);
        bundle.remove(BUNDLE_TRANSITION_RIGHT);

        intent.putExtras(bundle);
        setIntent(intent);
    }

    private boolean selectDisplayedCard(int cardId) {
        boolean cardChanged = loyaltyCardId != cardId;
        loyaltyCardId = cardId;
        if (cardChanged) {
            restoredImageIndex = 0;
            cardNavigator = new LoyaltyCardImageNavigator(new ArrayList<>(), 0);
        }
        updateIntentCardId(cardId);

        if (!loadCurrentCardFromDatabase()) {
            finish();
            return false;
        }

        updateBundleNavigationState();
        populateStateFromCurrentCard();
        showHideElementsForScreenSize();
        applyCardStyling(getWindow());
        if (cardChanged || cardNavigator.isEmpty()) {
            cardNavigator = createCardNavigator(isBarcodeSupported());
        }

        DBHelper.updateLoyaltyCardLastUsed(database, loyaltyCard.id);
        invalidateOptionsMenu();
        ShortcutHelper.updateShortcuts(this);
        return true;
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        Log.i(TAG, "Received new intent");
        setIntent(intent);
        extractIntentFields(intent);
        if (database != null && database.isOpen()) {
            selectDisplayedCard(loyaltyCardId);
            refreshBundlePager(false);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putInt(STATE_IMAGEINDEX, cardNavigator.getCurrentIndex());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void onResume() {
        activityOverridesNavBarColor = true;
        super.onResume();

        Log.i(TAG, "To view card: " + loyaltyCardId);

        Window window = getWindow();
        applyWindowPreferences(window);
        configurePausedNfc(settings.getDisableNfcWhileViewingCard());

        selectDisplayedCard(loyaltyCardId);
        refreshBundlePager(false);
    }

    @Override
    protected void onPause() {
        // Some devices have broken NFC, which will cause a crash if reader mode is enabled
        // So ensure we disable it explicitly before letting Android "save" the NFC state
        configurePausedNfc(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (cardPagerCallback != null) {
            binding.cardPager.unregisterOnPageChangeCallback(cardPagerCallback);
            cardPagerCallback = null;
        }
        if (database != null && database.isOpen()) {
            database.close();
        }
        super.onDestroy();
    }

    private void applyWindowPreferences(Window window) {
        if (window == null) {
            return;
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        WindowManager.LayoutParams attributes = window.getAttributes();

        // Brightening the screen improves scan reliability when the barcode is displayed on-device.
        if (settings.useMaxBrightnessDisplayingBarcode()) {
            attributes.screenBrightness = 1F;
        }

        if (settings.getKeepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Some users scan cards directly from the lock screen, so keep the historical unlock behavior.
        if (settings.getDisableLockscreenWhileViewingCard()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
            } else {
                showWhenLockedSdkLessThan27(window);
            }
        }

        window.setAttributes(attributes);
    }

    private void configurePausedNfc(boolean pause) {
        // Pause NFC to prevent NFC payments from triggering while showing a barcode
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            return;
        }

        if (pause) {
            try {
                nfcAdapter.enableReaderMode(this, tag -> {
                    Snackbar snackbar = Snackbar.make(binding.container, R.string.nfc_blocked_while_viewing_card, Snackbar.LENGTH_LONG)
                            .setAction(R.string.change_settings, view -> {
                                // Open settings activity
                                Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
                                startActivity(intent);
                            });
                    snackbar.show();
                }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                        | NfcAdapter.FLAG_READER_NFC_BARCODE
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                        | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null);
            } catch (Exception e) {
                // For some unknown reason, this can throw a DeadObjectException.
                // Mostly got reports from FOSS users, which implies it may be more common with custom ROMs? Uncertain.
                Toast.makeText(this, R.string.nfc_block_system_error, Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Failed to pause NFC: " + e);
                e.printStackTrace();
            }
        } else {
            nfcAdapter.disableReaderMode(this);
        }
    }

    private boolean loadCurrentCardFromDatabase() {
        loyaltyCard = DBHelper.getLoyaltyCard(this, database, loyaltyCardId);
        if (loyaltyCard != null) {
            return true;
        }

        Log.w(TAG, "Could not lookup loyalty card " + loyaltyCardId);
        Toast.makeText(this, R.string.noCardExistsError, Toast.LENGTH_LONG).show();
        return false;
    }

    private void populateStateFromCurrentCard() {
        setTitle(loyaltyCard.store);
        loyaltyCardGroups = DBHelper.getLoyaltyCardGroups(database, loyaltyCardId);
        format = loyaltyCard.barcodeType;
        cardIdString = loyaltyCard.cardId;
        barcodeIdString = loyaltyCard.barcodeId;
        barcodeEncoding = loyaltyCard.barcodeEncoding;
    }

    private void updateBundleNavigationState() {
        List<Integer> bundledCardIds = DBHelper.getBundleCardIds(database, loyaltyCardId);
        bundledCardList = new ArrayList<>(bundledCardIds);
    }

    private void refreshBundlePager(boolean smoothScroll) {
        if (cardPagerAdapter == null) {
            return;
        }

        List<Integer> cardIds = DBHelper.getBundleCardIds(database, loyaltyCardId);
        if (cardIds.isEmpty()) {
            cardIds = Arrays.asList(loyaltyCardId);
        }

        boolean sameCards = cardPagerAdapter.hasPassIds(cardIds);
        bundledCardList = new ArrayList<>(cardIds);
        if (!sameCards) {
            cardPagerAdapter.setPassIds(bundledCardList);
        }

        int position = bundledCardList.indexOf(loyaltyCardId);
        if (position < 0) {
            position = 0;
        }
        syncingPagerSelection = true;
        binding.cardPager.setCurrentItem(position, smoothScroll && sameCards);
        syncingPagerSelection = false;
        updateBundlePositionIndicator();
    }

    private void updateBundlePositionIndicator() {
        if (bundledCardList == null || bundledCardList.size() < 2) {
            binding.bundlePositionIndicator.setVisibility(View.GONE);
            return;
        }

        int total = bundledCardList.size();
        int position = bundledCardList.indexOf(loyaltyCardId);
        if (position < 0) {
            position = binding.cardPager.getCurrentItem();
        }
        position = Math.max(0, Math.min(position, total - 1));

        binding.bundlePositionIndicator.setText(
                getString(R.string.bundle_position_indicator, position + 1, total)
        );
        binding.bundlePositionIndicator.setContentDescription(
                getString(R.string.bundle_position_indicator_description, position + 1, total)
        );
        binding.bundlePositionIndicator.setVisibility(View.VISIBLE);
    }

    private void applyCardStyling(Window window) {
        int backgroundHeaderColor = Utils.getHeaderColor(this, loyaltyCard);
        Utils.setNavigationBarColor(null, window, backgroundHeaderColor, Utils.needsDarkForeground(backgroundHeaderColor));
    }

    private boolean isBarcodeSupported() {
        if (format == null) {
            return false;
        }

        if (format.isSupported()) {
            return true;
        }

        Toast.makeText(this, getString(R.string.unsupportedBarcodeType), Toast.LENGTH_LONG).show();
        return false;
    }

    private LoyaltyCardImageNavigator createCardNavigator(boolean isBarcodeSupported) {
        List<LoyaltyCardImageType> availableImageTypes = new ArrayList<>();

        if (isBarcodeSupported) {
            availableImageTypes.add(LoyaltyCardImageType.BARCODE);
        }

        frontImageBitmap = loyaltyCard.getImageFront(this);
        if (frontImageBitmap != null) {
            availableImageTypes.add(LoyaltyCardImageType.IMAGE_FRONT);
        }

        backImageBitmap = loyaltyCard.getImageBack(this);
        if (backImageBitmap != null) {
            availableImageTypes.add(LoyaltyCardImageType.IMAGE_BACK);
        }

        // Card edits may remove barcode/front/back images, so keep the previously selected index in range.
        int initialIndex = cardNavigator.isEmpty()
                ? (restoredImageIndex != null ? restoredImageIndex : 0)
                : cardNavigator.getCurrentIndex();
        LoyaltyCardImageNavigator navigator =
                new LoyaltyCardImageNavigator(availableImageTypes, initialIndex);
        restoredImageIndex = null;
        return navigator;
    }

    @SuppressWarnings("deprecation")
    private void showWhenLockedSdkLessThan27(Window window) {
        // Pre-O_MR1 devices still need the legacy window flags because setShowWhenLocked(true) is unavailable.
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }

    private void syncSelectedImageState(
            int cardId,
            LoyaltyCardImageNavigator navigator,
            Bitmap frontImage,
            Bitmap backImage
    ) {
        if (cardId != loyaltyCardId) {
            return;
        }

        cardNavigator = navigator;
        frontImageBitmap = frontImage;
        backImageBitmap = backImage;
    }

    private void syncSelectedPageState(
            int cardId,
            LoyaltyCard card,
            LoyaltyCardImageNavigator navigator,
            Bitmap frontImage,
            Bitmap backImage
    ) {
        loyaltyCardId = cardId;
        loyaltyCard = card;
        loyaltyCardGroups = DBHelper.getLoyaltyCardGroups(database, cardId);
        format = card.barcodeType;
        cardIdString = card.cardId;
        barcodeIdString = card.barcodeId;
        barcodeEncoding = card.barcodeEncoding;
        cardNavigator = navigator;
        frontImageBitmap = frontImage;
        backImageBitmap = backImage;
        updateIntentCardId(cardId);
        updateBundleNavigationState();
        applyCardStyling(getWindow());
        invalidateOptionsMenu();
    }

    private void showCardIdDialog(LoyaltyCard card) {
        TextView cardIdView = new TextView(LoyaltyCardViewActivity.this);
        cardIdView.setAutoLinkMask(Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS | Linkify.WEB_URLS);
        cardIdView.setText(card.cardId);
        cardIdView.setTextIsSelectable(true);
        int contentPadding = getResources().getDimensionPixelSize(R.dimen.alert_dialog_content_padding);
        cardIdView.setPadding(contentPadding, contentPadding / 2, contentPadding, 0);

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(LoyaltyCardViewActivity.this);
        builder.setTitle(R.string.cardId);
        builder.setView(cardIdView);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());
        builder.setNeutralButton(R.string.copy_value, (dialog, which) -> copyCardIdToClipboard(card.cardId));
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void editCardIcon(int cardId) {
        Intent intent = new Intent(getApplicationContext(), LoyaltyCardEditActivity.class);
        Bundle bundle = new Bundle();
        bundle.putInt(LoyaltyCardEditActivity.BUNDLE_ID, cardId);
        bundle.putBoolean(LoyaltyCardEditActivity.BUNDLE_UPDATE, true);
        bundle.putBoolean(LoyaltyCardEditActivity.BUNDLE_OPEN_SET_ICON_MENU, true);
        intent.putExtras(bundle);
        startActivity(intent);
        finish();
    }

    private LoyaltyCardImageNavigator createNavigatorForCard(
            LoyaltyCard card,
            Bitmap frontImage,
            Bitmap backImage,
            Integer initialIndex
    ) {
        List<LoyaltyCardImageType> availableImageTypes = new ArrayList<>();
        if (card.barcodeType != null && card.barcodeType.isSupported()) {
            availableImageTypes.add(LoyaltyCardImageType.BARCODE);
        }
        if (frontImage != null) {
            availableImageTypes.add(LoyaltyCardImageType.IMAGE_FRONT);
        }
        if (backImage != null) {
            availableImageTypes.add(LoyaltyCardImageType.IMAGE_BACK);
        }

        return new LoyaltyCardImageNavigator(availableImageTypes, initialIndex != null ? initialIndex : 0);
    }

    private final class PassPagerAdapter extends RecyclerView.Adapter<PassPageViewHolder> {
        private final List<Integer> passIds = new ArrayList<>();

        private PassPagerAdapter(List<Integer> passIds) {
            setHasStableIds(true);
            setPassIds(passIds);
        }

        private void setPassIds(List<Integer> nextPassIds) {
            passIds.clear();
            passIds.addAll(nextPassIds);
            notifyDataSetChanged();
        }

        private boolean hasPassIds(List<Integer> nextPassIds) {
            return passIds.equals(nextPassIds);
        }

        @Override
        public int getItemCount() {
            return passIds.size();
        }

        @Override
        public long getItemId(int position) {
            return passIds.get(position);
        }

        @Override
        public PassPageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LoyaltyCardPassPageBinding pageBinding = LoyaltyCardPassPageBinding.inflate(
                    LayoutInflater.from(parent.getContext()),
                    parent,
                    false
            );
            return new PassPageViewHolder(pageBinding);
        }

        @Override
        public void onBindViewHolder(PassPageViewHolder holder, int position) {
            holder.bind(passIds.get(position));
        }
    }

    private final class PassPageViewHolder extends RecyclerView.ViewHolder {
        private final LoyaltyCardPassPageBinding pageBinding;
        private final LoyaltyCardMainImageRenderer renderer;
        private LoyaltyCard card;
        private LoyaltyCardImageNavigator navigator;
        private Bitmap frontImage;
        private Bitmap backImage;

        private PassPageViewHolder(LoyaltyCardPassPageBinding pageBinding) {
            super(pageBinding.getRoot());
            this.pageBinding = pageBinding;
            this.renderer = new LoyaltyCardMainImageRenderer(LoyaltyCardViewActivity.this, LoyaltyCardViewActivity.this);
        }

        private void bind(int cardId) {
            card = DBHelper.getLoyaltyCard(LoyaltyCardViewActivity.this, database, cardId);
            if (card == null) {
                return;
            }

            frontImage = card.getImageFront(LoyaltyCardViewActivity.this);
            backImage = card.getImageBack(LoyaltyCardViewActivity.this);
            navigator = createNavigatorForCard(card, frontImage, backImage, null);

            pageBinding.iconContainer.setVisibility(shouldHideIconContainer() ? View.GONE : View.VISIBLE);
            Bitmap icon = card.getImageThumbnail(LoyaltyCardViewActivity.this);
            Utils.setIconOrTextWithBackground(LoyaltyCardViewActivity.this, card, icon, pageBinding.iconImage, pageBinding.iconText, 1);

            pageBinding.iconContainer.setOnClickListener(view -> {
                if (card.getImageThumbnail(LoyaltyCardViewActivity.this) != null) {
                    openImageInGallery(cardId, LoyaltyCardImageType.ICON);
                } else {
                    Toast.makeText(LoyaltyCardViewActivity.this, R.string.icon_header_click_text, Toast.LENGTH_LONG).show();
                }
            });
            pageBinding.iconContainer.setOnLongClickListener(view -> {
                editCardIcon(cardId);
                return true;
            });

            pageBinding.mainImage.setOnClickListener(view -> {
                syncSelectedPageState(cardId, card, navigator, frontImage, backImage);
                if (navigator.getCurrent() == LoyaltyCardImageType.BARCODE) {
                    showCardIdDialog(card);
                } else {
                    openImageInGallery(cardId, navigator.getCurrent());
                }
            });
            pageBinding.mainImage.setOnLongClickListener(view -> {
                setPageImage(true, true);
                return true;
            });

            pageBinding.mainImageDescription.setOnClickListener(view -> {
                if (isShowingCardIdDescription(navigator)) {
                    showCardIdDialog(card);
                }
            });
            pageBinding.mainImageDescription.setOnLongClickListener(view -> {
                if (!isShowingCardIdDescription(navigator)) {
                    return false;
                }

                copyCardIdToClipboard(card.cardId);
                return true;
            });

            if (cardId == loyaltyCardId) {
                syncSelectedPageState(cardId, card, navigator, frontImage, backImage);
            }
            renderPageImage(true);
            updatePageImageUiState();
        }

        private void setPageImage(boolean next, boolean overflow) {
            boolean moved = next ? navigator.moveNext(overflow) : navigator.movePrevious();
            if (!moved) {
                return;
            }

            renderPageImage(false);
            updatePageImageUiState();
            syncSelectedImageState(card.id, navigator, frontImage, backImage);
        }

        private void renderPageImage(boolean waitForResize) {
            ViewGroup.LayoutParams cardHolderLayoutParams = pageBinding.cardHolder.getLayoutParams();
            cardHolderLayoutParams.height = navigator.isEmpty()
                    ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : ViewGroup.LayoutParams.MATCH_PARENT;
            pageBinding.cardHolder.setLayoutParams(cardHolderLayoutParams);

            renderer.renderCurrent(
                    navigator.getCurrent(),
                    frontImage,
                    backImage,
                    card.barcodeType,
                    card.barcodeEncoding,
                    card.cardId,
                    card.barcodeId,
                    pageBinding.mainImage,
                    pageBinding.mainImageDescription,
                    pageBinding.mainCardView,
                    waitForResize
            );

            syncSelectedImageState(card.id, navigator, frontImage, backImage);
        }

        private void updatePageImageUiState() {
            updatePageImagePreviousNextButtons();
            updatePageImageAccessibility();
        }

        private void updatePageImageAccessibility() {
            int accessibilityClickAction;
            LoyaltyCardImageType currentImageType = navigator.getCurrent();
            if (currentImageType == LoyaltyCardImageType.IMAGE_FRONT) {
                accessibilityClickAction = R.string.openFrontImageInGalleryApp;
            } else if (currentImageType == LoyaltyCardImageType.IMAGE_BACK) {
                accessibilityClickAction = R.string.openBackImageInGalleryApp;
            } else {
                accessibilityClickAction = R.string.cardId;
            }

            ViewCompat.replaceAccessibilityAction(
                    pageBinding.mainImage,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    getString(accessibilityClickAction),
                    null
            );

            int accessibilityLongPressAction;
            LoyaltyCardImageType nextImageType = navigator.peekNext(true);
            if (nextImageType == LoyaltyCardImageType.IMAGE_FRONT) {
                accessibilityLongPressAction = R.string.switchToFrontImage;
            } else if (nextImageType == LoyaltyCardImageType.IMAGE_BACK) {
                accessibilityLongPressAction = R.string.switchToBackImage;
            } else {
                accessibilityLongPressAction = R.string.switchToBarcode;
            }

            ViewCompat.replaceAccessibilityAction(
                    pageBinding.mainImage,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    getString(accessibilityLongPressAction),
                    null
            );
        }

        private void updatePageImagePreviousNextButtons() {
            if (navigator.size() < 2) {
                pageBinding.mainLeftButton.setVisibility(View.INVISIBLE);
                pageBinding.mainRightButton.setVisibility(View.INVISIBLE);
                pageBinding.mainLeftButton.setOnClickListener(null);
                pageBinding.mainRightButton.setOnClickListener(null);
                return;
            }

            final ImageButton previousButton;
            final ImageButton nextButton;
            if (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                previousButton = pageBinding.mainRightButton;
                nextButton = pageBinding.mainLeftButton;
            } else {
                previousButton = pageBinding.mainLeftButton;
                nextButton = pageBinding.mainRightButton;
            }

            if (navigator.canGoPrevious()) {
                previousButton.setVisibility(View.VISIBLE);
                previousButton.setOnClickListener(view -> setPageImage(false, false));
            } else {
                previousButton.setVisibility(View.INVISIBLE);
                previousButton.setOnClickListener(null);
            }

            if (navigator.canGoNext()) {
                nextButton.setVisibility(View.VISIBLE);
                nextButton.setOnClickListener(view -> setPageImage(true, false));
            } else {
                nextButton.setVisibility(View.INVISIBLE);
                nextButton.setOnClickListener(null);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.card_view_menu, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        if (loyaltyCard != null) {
            if (loyaltyCard.starStatus == 1) {
                menu.findItem(R.id.action_star_unstar).setIcon(R.drawable.ic_starred);
                menu.findItem(R.id.action_star_unstar).setTitle(R.string.unstar);
            } else {
                menu.findItem(R.id.action_star_unstar).setIcon(R.drawable.ic_unstarred);
                menu.findItem(R.id.action_star_unstar).setTitle(R.string.star);
            }

            if (loyaltyCard.archiveStatus != 0) {
                menu.findItem(R.id.action_unarchive).setVisible(true);
                menu.findItem(R.id.action_archive).setVisible(false);
            } else {
                menu.findItem(R.id.action_unarchive).setVisible(false);
                menu.findItem(R.id.action_archive).setVisible(true);
            }

            menu.findItem(R.id.action_info).setVisible(
                    !loyaltyCard.note.isEmpty() ||
                            !loyaltyCardGroups.isEmpty() ||
                            hasBalance(loyaltyCard) ||
                            loyaltyCard.validFrom != null ||
                            loyaltyCard.expiry != null
            );
            menu.findItem(R.id.action_update_balance).setVisible(hasBalance(loyaltyCard));
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.action_edit) {
            Intent intent = new Intent(getApplicationContext(), LoyaltyCardEditActivity.class);
            Bundle bundle = new Bundle();
            bundle.putInt(LoyaltyCardEditActivity.BUNDLE_ID, loyaltyCardId);
            bundle.putBoolean(LoyaltyCardEditActivity.BUNDLE_UPDATE, true);
            intent.putExtras(bundle);
            startActivity(intent);

            return true;
        } else if (id == R.id.action_info) {
            dialogs.showInfoDialog(this, loyaltyCard, loyaltyCardGroups);

            return true;
        } else if (id == R.id.action_update_balance) {
            dialogs.showBalanceUpdateDialog(this, loyaltyCard, newBalance -> {
                DBHelper.updateLoyaltyCardBalance(database, loyaltyCardId, newBalance);
                onResume();
            });

            return true;
        } else if (id == R.id.action_share) {
            try {
                importURIHelper.startShareIntent(Arrays.asList(loyaltyCard));
            } catch (UnsupportedEncodingException e) {
                Toast.makeText(LoyaltyCardViewActivity.this, R.string.failedGeneratingShareURL, Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }

            return true;
        } else if (id == R.id.action_duplicate) {
            Intent intent = new Intent(getApplicationContext(), LoyaltyCardEditActivity.class);
            Bundle bundle = new Bundle();
            bundle.putInt(LoyaltyCardEditActivity.BUNDLE_ID, loyaltyCardId);
            bundle.putBoolean(LoyaltyCardEditActivity.BUNDLE_DUPLICATE_ID, true);
            intent.putExtras(bundle);
            startActivity(intent);

            return true;
        } else if (id == R.id.action_star_unstar) {
            DBHelper.updateLoyaltyCardStarStatus(database, loyaltyCardId, loyaltyCard.starStatus == 0 ? 1 : 0);

            new ListWidget().updateAll(LoyaltyCardViewActivity.this);

            // Re-init loyaltyCard with new data from DB
            onResume();
            invalidateOptionsMenu();

            return true;
        } else if (id == R.id.action_archive) {
            DBHelper.updateLoyaltyCardArchiveStatus(database, loyaltyCardId, 1);
            Toast.makeText(LoyaltyCardViewActivity.this, R.string.archived, Toast.LENGTH_LONG).show();

            new ListWidget().updateAll(LoyaltyCardViewActivity.this);

            // Re-init loyaltyCard with new data from DB
            onResume();
            invalidateOptionsMenu();

            return true;
        } else if (id == R.id.action_unarchive) {
            DBHelper.updateLoyaltyCardArchiveStatus(database, loyaltyCardId, 0);
            Toast.makeText(LoyaltyCardViewActivity.this, R.string.unarchived, Toast.LENGTH_LONG).show();

            // Re-init loyaltyCard with new data from DB
            onResume();
            invalidateOptionsMenu();

            return true;
        } else if (id == R.id.action_delete) {
            AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle(R.string.deleteTitle);
            builder.setMessage(R.string.deleteConfirmation);
            builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
                Log.e(TAG, "Deleting card: " + loyaltyCardId);

                DBHelper.deleteLoyaltyCard(database, LoyaltyCardViewActivity.this, loyaltyCardId);

                new ListWidget().updateAll(LoyaltyCardViewActivity.this);

                finish();
                dialog.dismiss();
            });
            builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss());
            AlertDialog dialog = builder.create();
            dialog.show();

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showHideElementsForScreenSize() {
        enableToolbarBackButton();
    }

    private boolean shouldHideIconContainer() {
        int orientation = getResources().getConfiguration().orientation;
        // Treat square-ish devices such as the Unihertz Titan like landscape to avoid a cramped header layout.
        boolean isSmallHeight = getResources().getDisplayMetrics().heightPixels < (getResources().getDisplayMetrics().widthPixels * 1.5);

        return orientation == Configuration.ORIENTATION_LANDSCAPE || isSmallHeight;
    }

    private boolean isShowingCardIdDescription(LoyaltyCardImageNavigator navigator) {
        return navigator.isEmpty() || navigator.getCurrent() == LoyaltyCardImageType.BARCODE;
    }

    private void copyCardIdToClipboard(String value) {
        if (value == null || value.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getString(R.string.cardId), value);
        cm.setPrimaryClip(clip);

        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    private void copyCardIdToClipboard() {
        copyCardIdToClipboard(loyaltyCard.cardId);
    }
}
