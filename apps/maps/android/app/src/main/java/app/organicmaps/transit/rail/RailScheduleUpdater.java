package app.organicmaps.transit.rail;

import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;
import org.json.JSONException;
import org.json.JSONObject;

final class RailScheduleUpdater
{
  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 60_000;

  @NonNull
  private final Context mContext;

  RailScheduleUpdater(@NonNull Context context)
  {
    mContext = context.getApplicationContext();
  }

  boolean update() throws IOException, JSONException
  {
    if (!RailScheduleConfig.isUpdateEnabled())
      return false;

    JSONObject manifest = readJson(new URL(RailScheduleConfig.MANIFEST_URL));
    validateManifest(manifest);

    JSONObject packageInfo = manifest.getJSONObject("package");
    String packageSha256 = packageInfo.getString("sha256");
    if (packageSha256.equals(RailScheduleStore.packageSha256(mContext)) && RailScheduleStore.isInstalled(mContext))
      return false;

    URL packageUrl = new URL(new URL(RailScheduleConfig.MANIFEST_URL), packageInfo.getString("file"));
    File dir = RailScheduleStore.directory(mContext);
    if (!dir.exists() && !dir.mkdirs())
      throw new IOException("Could not create rail schedule directory: " + dir);

    File compressed = new File(dir, packageInfo.getString("file") + ".download");
    download(packageUrl, compressed);
    verifyFile(compressed, packageInfo.getLong("bytes"), packageSha256);

    File candidate = new File(dir, RailScheduleConfig.DATABASE_FILE_NAME + ".download");
    decompressGzip(compressed, candidate);
    verifyDatabase(candidate);

    install(candidate, RailScheduleStore.databaseFile(mContext));
    compressed.delete();

    RailScheduleStore.saveInstalledPackage(
        mContext,
        packageSha256,
        manifest.optString("generated_at", ""),
        manifest.optString("valid_from", ""),
        manifest.optString("valid_until", ""));
    return true;
  }

  boolean installBundledIfAvailable() throws IOException, JSONException
  {
    JSONObject manifest = readBundledManifest();
    if (manifest == null)
      return false;
    validateManifest(manifest);

    JSONObject packageInfo = manifest.getJSONObject("package");
    String packageSha256 = packageInfo.getString("sha256");
    if (packageSha256.equals(RailScheduleStore.packageSha256(mContext)) && RailScheduleStore.isInstalled(mContext))
      return false;

    File dir = RailScheduleStore.directory(mContext);
    if (!dir.exists() && !dir.mkdirs())
      throw new IOException("Could not create rail schedule directory: " + dir);

    File compressed = new File(dir, packageInfo.getString("file") + ".bundled");
    copyBundledPackage(packageInfo.getString("file"), compressed);
    verifyFile(compressed, packageInfo.getLong("bytes"), packageSha256);

    File candidate = new File(dir, RailScheduleConfig.DATABASE_FILE_NAME + ".bundled");
    decompressGzip(compressed, candidate);
    verifyDatabase(candidate);

    install(candidate, RailScheduleStore.databaseFile(mContext));
    compressed.delete();

    RailScheduleStore.saveInstalledPackage(
        mContext,
        packageSha256,
        manifest.optString("generated_at", ""),
        manifest.optString("valid_from", ""),
        manifest.optString("valid_until", ""));
    return true;
  }

  static boolean hasBundledPackage(@NonNull Context context)
  {
    try (InputStream ignored = context.getAssets().open(RailScheduleConfig.BUNDLED_MANIFEST_ASSET))
    {
      return true;
    }
    catch (IOException ignored)
    {
      return false;
    }
  }

  private static void validateManifest(@NonNull JSONObject manifest) throws JSONException
  {
    if (manifest.getInt("schema_version") != RailScheduleConfig.SCHEMA_VERSION)
      throw new JSONException("Unsupported rail schedule schema version");
    if (!RailScheduleConfig.REGION_ID.equals(manifest.getString("region_id")))
      throw new JSONException("Unexpected rail schedule region");

    JSONObject packageInfo = manifest.getJSONObject("package");
    if (!"gzip".equals(packageInfo.getString("compression")))
      throw new JSONException("Unsupported rail schedule package compression");
  }

  @NonNull
  private static JSONObject readJson(@NonNull URL url) throws IOException, JSONException
  {
    HttpURLConnection connection = openConnection(url);
    try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream()))
    {
      return readJson(input);
    }
    finally
    {
      connection.disconnect();
    }
  }

  @Nullable
  private JSONObject readBundledManifest() throws IOException, JSONException
  {
    InputStream input;
    try
    {
      input = mContext.getAssets().open(RailScheduleConfig.BUNDLED_MANIFEST_ASSET);
    }
    catch (IOException e)
    {
      return null;
    }
    try (InputStream closeableInput = input)
    {
      return readJson(closeableInput);
    }
  }

  @NonNull
  private static JSONObject readJson(@NonNull InputStream input) throws IOException, JSONException
  {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    copy(input, output);
    byte[] data = output.toByteArray();
    return new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
  }

  private void copyBundledPackage(@NonNull String fileName, @NonNull File target) throws IOException
  {
    String manifestAsset = RailScheduleConfig.BUNDLED_MANIFEST_ASSET;
    int separator = manifestAsset.lastIndexOf('/');
    String asset = separator >= 0 ? manifestAsset.substring(0, separator + 1) + fileName : fileName;
    File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
    AssetManager assets = mContext.getAssets();
    try (InputStream input = new BufferedInputStream(assets.open(asset));
         FileOutputStream output = new FileOutputStream(tmp))
    {
      copy(input, output);
    }
    replace(tmp, target);
  }

  private static void download(@NonNull URL url, @NonNull File target) throws IOException
  {
    File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
    HttpURLConnection connection = openConnection(url);
    try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
         FileOutputStream output = new FileOutputStream(tmp))
    {
      copy(input, output);
    }
    finally
    {
      connection.disconnect();
    }

    replace(tmp, target);
  }

  @NonNull
  private static HttpURLConnection openConnection(@NonNull URL url) throws IOException
  {
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("Accept", "application/json, application/octet-stream");
    int code = connection.getResponseCode();
    if (code < 200 || code >= 300)
      throw new IOException("Unexpected HTTP " + code + " for " + url);
    return connection;
  }

  private static void verifyFile(@NonNull File file, long expectedBytes, @NonNull String expectedSha256) throws IOException
  {
    if (file.length() != expectedBytes)
      throw new IOException("Rail schedule package size mismatch");
    String actualSha256 = sha256(file);
    if (!expectedSha256.equalsIgnoreCase(actualSha256))
      throw new IOException("Rail schedule package checksum mismatch");
  }

  @NonNull
  private static String sha256(@NonNull File file) throws IOException
  {
    try
    {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (DigestInputStream input = new DigestInputStream(new FileInputStream(file), digest))
      {
        copy(input, null);
      }
      return toHex(digest.digest());
    }
    catch (NoSuchAlgorithmException e)
    {
      throw new IOException("SHA-256 is not available", e);
    }
  }

  private static void decompressGzip(@NonNull File source, @NonNull File target) throws IOException
  {
    File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
    try (GZIPInputStream input = new GZIPInputStream(new FileInputStream(source));
         FileOutputStream output = new FileOutputStream(tmp))
    {
      copy(input, output);
    }
    replace(tmp, target);
  }

  private static void verifyDatabase(@NonNull File candidate) throws IOException
  {
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(candidate.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY))
    {
      if (readMetadataInt(db, "schema_version") != RailScheduleConfig.SCHEMA_VERSION)
        throw new IOException("Rail schedule database schema mismatch");
      String region = readMetadataString(db, "region_id");
      if (!RailScheduleConfig.REGION_ID.equals(region))
        throw new IOException("Rail schedule database region mismatch");
    }
  }

  private static int readMetadataInt(@NonNull SQLiteDatabase db, @NonNull String key) throws IOException
  {
    String value = readMetadataString(db, key);
    try
    {
      return Integer.parseInt(value);
    }
    catch (NumberFormatException e)
    {
      throw new IOException("Invalid rail schedule metadata: " + key, e);
    }
  }

  @NonNull
  private static String readMetadataString(@NonNull SQLiteDatabase db, @NonNull String key) throws IOException
  {
    try (Cursor cursor = db.rawQuery("SELECT value FROM metadata WHERE key = ?", new String[] {key}))
    {
      if (!cursor.moveToFirst())
        throw new IOException("Missing rail schedule metadata: " + key);
      return cursor.getString(0);
    }
  }

  private static void install(@NonNull File candidate, @NonNull File finalFile) throws IOException
  {
    File backup = new File(finalFile.getParentFile(), finalFile.getName() + ".bak");
    if (backup.exists() && !backup.delete())
      throw new IOException("Could not remove old rail schedule backup");
    if (finalFile.exists() && !finalFile.renameTo(backup))
      throw new IOException("Could not back up old rail schedule database");

    if (!candidate.renameTo(finalFile))
    {
      if (backup.exists())
        backup.renameTo(finalFile);
      throw new IOException("Could not install rail schedule database");
    }

    if (backup.exists())
      backup.delete();
  }

  private static void replace(@NonNull File source, @NonNull File target) throws IOException
  {
    if (target.exists() && !target.delete())
      throw new IOException("Could not replace " + target);
    if (!source.renameTo(target))
      throw new IOException("Could not move " + source + " to " + target);
  }

  private static void copy(@NonNull InputStream input, @Nullable OutputStream output) throws IOException
  {
    byte[] buffer = new byte[64 * 1024];
    int read;
    while ((read = input.read(buffer)) != -1)
    {
      if (output != null)
        output.write(buffer, 0, read);
    }
  }

  @NonNull
  private static String toHex(@NonNull byte[] data)
  {
    char[] out = new char[data.length * 2];
    char[] hex = "0123456789abcdef".toCharArray();
    for (int i = 0; i < data.length; ++i)
    {
      int value = data[i] & 0xff;
      out[i * 2] = hex[value >>> 4];
      out[i * 2 + 1] = hex[value & 0xf];
    }
    return new String(out);
  }
}
