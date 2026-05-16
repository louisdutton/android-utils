package app.organicmaps.intent;

import static app.organicmaps.api.Const.EXTRA_PICK_POINT;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentCompat;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.editor.OsmLoginActivity;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.api.ParsedRoutingData;
import app.organicmaps.sdk.api.ParsedSearchRequest;
import app.organicmaps.sdk.api.RequestType;
import app.organicmaps.sdk.api.RoutePoint;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.util.StorageUtils;
import app.organicmaps.sdk.util.concurrency.ThreadPool;
import app.organicmaps.search.SearchActivity;
import java.io.File;
import java.util.Collections;
import java.util.List;

public class Factory
{
  public static boolean isStartedForApiResult(@NonNull Intent intent)
  {
    // Previously, we relied on the implicit FORWARD_RESULT_FLAG to detect if the caller was
    // waiting for a result. However, this approach proved to be less reliable than using
    // the explicit EXTRA_PICK_POINT flag.
    // https://github.com/organicmaps/organicmaps/pull/8910
    return intent.getBooleanExtra(EXTRA_PICK_POINT, false);
  }

  public static class KmzKmlProcessor implements IntentProcessor
  {
    @Override
    public boolean process(@NonNull Intent intent, @NonNull MwmActivity activity)
    {
      // See KML/KMZ/KMB intent filters in manifest.
      final List<Uri> uris;
      if (Intent.ACTION_VIEW.equals(intent.getAction()))
        uris = Collections.singletonList(intent.getData());
      else if (Intent.ACTION_SEND.equals(intent.getAction()))
        uris = Collections.singletonList(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class));
      else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))
        uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      else
        uris = null;
      if (uris == null)
        return false;

      MwmApplication app = MwmApplication.from(activity);
      final File tempDir = new File(StorageUtils.getTempPath(app));
      final ContentResolver resolver = activity.getContentResolver();
      ThreadPool.getStorage().execute(() -> BookmarkManager.INSTANCE.importBookmarksFiles(resolver, uris, tempDir));
      return false;
    }
  }

  public static class CalendarLocationProcessor implements IntentProcessor
  {
    private static final String EXTRA_SOURCE = "digital.dutton.essentials.locations.extra.SOURCE";
    private static final String EXTRA_RAW_PROVIDER_LOCATION =
        "digital.dutton.essentials.locations.extra.RAW_PROVIDER_LOCATION";
    private static final String SOURCE_CALENDAR = "calendar";

    @Override
    public boolean process(@NonNull Intent intent, @NonNull MwmActivity target)
    {
      if (!Intent.ACTION_VIEW.equals(intent.getAction()))
        return false;

      if (!SOURCE_CALENDAR.equals(intent.getStringExtra(EXTRA_SOURCE)))
        return false;

      String location = intent.getStringExtra(EXTRA_RAW_PROVIDER_LOCATION);
      if (TextUtils.isEmpty(location))
        location = extractGeoQuery(intent.getData());

      if (location != null)
        location = location.trim();

      if (TextUtils.isEmpty(location))
        return false;

      SearchEngine.INSTANCE.cancelInteractiveSearch();
      SearchActivity.start(target, location);
      return true;
    }

    @Nullable
    private static String extractGeoQuery(@Nullable Uri uri)
    {
      if (uri == null || !"geo".equalsIgnoreCase(uri.getScheme()))
        return null;

      final String raw = uri.toString();
      final int queryStart = raw.indexOf('?');
      if (queryStart == -1 || queryStart == raw.length() - 1)
        return null;

      final String[] params = raw.substring(queryStart + 1).split("&");
      for (String param : params)
      {
        final int equals = param.indexOf('=');
        if (equals <= 0)
          continue;

        if ("q".equals(param.substring(0, equals)))
          return Uri.decode(param.substring(equals + 1));
      }

      return null;
    }
  }

  public static class UrlProcessor implements IntentProcessor
  {
    private static final int SEARCH_IN_VIEWPORT_ZOOM = 16;

    @Override
    public boolean process(@NonNull Intent intent, @NonNull MwmActivity target)
    {
      final Uri uri = intent.getData();
      if (uri == null)
        return false;

      switch (Framework.nativeParseAndSetApiUrl(uri.toString()))
      {
      case RequestType.INCORRECT: return false;

      case RequestType.MAP:
        SearchEngine.INSTANCE.cancelInteractiveSearch();
        Map.executeMapApiRequest();
        return true;

      case RequestType.ROUTE:
        SearchEngine.INSTANCE.cancelInteractiveSearch();
        final ParsedRoutingData data = Framework.nativeGetParsedRoutingData();
        RoutingController.get().setRouterType(data.mRouterType);
        final RoutePoint from = data.mPoints[0];
        final RoutePoint to = data.mPoints[1];
        RoutingController.get().prepare(
            MapObject.createMapObject(FeatureId.EMPTY, MapObject.API_POINT, from.mName, "", from.mLat, from.mLon),
            MapObject.createMapObject(FeatureId.EMPTY, MapObject.API_POINT, to.mName, "", to.mLat, to.mLon));
        return true;
      case RequestType.SEARCH:
      {
        SearchEngine.INSTANCE.cancelInteractiveSearch();
        final ParsedSearchRequest request = Framework.nativeGetParsedSearchRequest();
        final double[] latlon = Framework.nativeGetParsedCenterLatLon();
        if (latlon != null)
        {
          Framework.nativeStopLocationFollow();
          Framework.nativeSetViewportCenter(latlon[0], latlon[1], SEARCH_IN_VIEWPORT_ZOOM);
          // We need to update viewport for search api manually because of drape engine
          // will not notify subscribers when search activity is shown.
          if (!request.mIsSearchOnMap)
            Framework.nativeSetSearchViewport(latlon[0], latlon[1], SEARCH_IN_VIEWPORT_ZOOM);
        }
        SearchActivity.start(target, request.mQuery, request.mLocale, request.mIsSearchOnMap);
        return true;
      }
      case RequestType.CROSSHAIR:
      {
        SearchEngine.INSTANCE.cancelInteractiveSearch();
        target.showPositionChooserForAPI(Framework.nativeGetParsedAppName());

        final double[] latlon = Framework.nativeGetParsedCenterLatLon();
        if (latlon != null)
        {
          Framework.nativeStopLocationFollow();
          Framework.nativeSetViewportCenter(latlon[0], latlon[1], SEARCH_IN_VIEWPORT_ZOOM);
        }

        return true;
      }
      case RequestType.OAUTH2:
      {
        SearchEngine.INSTANCE.cancelInteractiveSearch();

        final String oauth2code = Framework.nativeGetParsedOAuth2Code();
        OsmLoginActivity.OAuth2Callback(target, oauth2code);

        return true;
      }

      // Menu and Settings url types should be implemented to support deeplinking.
      case RequestType.MENU:
      case RequestType.SETTINGS:
      }

      return false;
    }
  }
}
