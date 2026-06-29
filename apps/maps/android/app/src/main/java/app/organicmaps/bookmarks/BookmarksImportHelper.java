package app.organicmaps.bookmarks;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.util.StorageUtils;
import app.organicmaps.sdk.util.concurrency.ThreadPool;
import app.organicmaps.sdk.util.concurrency.UiThread;
import app.organicmaps.sdk.util.log.Logger;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

final class BookmarksImportHelper
{
  private static final String TAG = BookmarksImportHelper.class.getSimpleName();

  private BookmarksImportHelper() {}

  static void startImportDirectory(@NonNull Activity activity,
                                   @NonNull ActivityResultLauncher<Intent> launcher)
  {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

    // Enable "Show SD card option"
    // http://stackoverflow.com/a/31334967/1615876
    intent.putExtra("android.content.extra.SHOW_ADVANCED", true);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
      intent.putExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, true);

    PackageManager packageManager = activity.getPackageManager();
    if (intent.resolveActivity(packageManager) != null)
      launcher.launch(intent);
    else
      showNoFileManagerError(activity);
  }

  private static void showNoFileManagerError(@NonNull Activity activity)
  {
    new MaterialAlertDialogBuilder(activity)
        .setMessage(R.string.error_no_file_manager_app)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
        .show();
  }

  static void onImportDirectoryResult(@NonNull Activity activity, @Nullable Intent data)
  {
    if (data == null)
      return;

    final Context context = activity;
    final Uri rootUri = data.getData();
    if (rootUri == null)
      return;

    final ProgressDialog dialog = new ProgressDialog(context, R.style.MwmTheme_ProgressDialog);
    dialog.setMessage(context.getString(R.string.wait_several_minutes));
    dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
    dialog.setIndeterminate(true);
    dialog.setCancelable(false);
    dialog.show();
    Logger.d(TAG, "Importing bookmarks from " + rootUri);

    MwmApplication app = MwmApplication.from(context);
    final File tempDir = new File(StorageUtils.getTempPath(app));
    final ContentResolver resolver = context.getContentResolver();
    ThreadPool.getStorage().execute(() -> {
      AtomicInteger found = new AtomicInteger(0);
      StorageUtils.listContentProviderFilesRecursively(resolver, rootUri, uri -> {
        if (BookmarkManager.INSTANCE.importBookmarksFile(resolver, uri, tempDir))
          found.incrementAndGet();
      });
      UiThread.run(() -> {
        if (dialog.isShowing())
          dialog.dismiss();
        int foundValue = found.get();
        String message =
            context.getResources().getQuantityString(R.plurals.bookmarks_detect_message, foundValue, foundValue);
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
      });
    });
  }
}
