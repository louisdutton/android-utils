package app.organicmaps.util;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import app.organicmaps.MwmApplication;
import app.organicmaps.downloader.DownloaderStatusIcon;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.MapStyle;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.Config;

public enum ThemeSwitcher
{
  INSTANCE;

  private static boolean mRendererActive = false;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private Context mContext;

  public void initialize(@NonNull Context context)
  {
    mContext = context;
  }

  /**
   * Changes the UI theme of application and the map style if necessary. If the contract regarding
   * the input parameter is broken, the UI will be frozen during attempting to change the map style
   * through the synchronous method {@link MapStyle#set(MapStyle)}.
   *
   * @param isRendererActive Indicates whether OpenGL renderer is active or not. Must be
   *                         <code>true</code> only if the map is rendered and visible on the screen
   *                         at this moment, otherwise <code>false</code>.
   */
  @androidx.annotation.UiThread
  public void restart(boolean isRendererActive)
  {
    mRendererActive = isRendererActive;
    setThemeAndMapStyle(Config.UiTheme.getUiThemeSettings());
  }

  private void setThemeAndMapStyle(@NonNull String theme)
  {
    UiModeManager uiModeManager = (UiModeManager) mContext.getSystemService(Context.UI_MODE_SERVICE);
    String oldTheme = Config.UiTheme.getCurrent();
    final boolean useSystemTheme = Config.UiTheme.isSystem(theme);
    final String resolvedTheme = useSystemTheme ? getSystemTheme() : theme;

    if (useSystemTheme)
    {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO);
      AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    MapStyle style;
    if (Config.UiTheme.isNight(resolvedTheme))
    {
      if (!useSystemTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES);
      if (!useSystemTheme)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);

      if (RoutingController.get().isVehicleNavigation())
        style = MapStyle.VehicleDark;
      else if (Framework.nativeIsOutdoorsLayerEnabled())
        style = MapStyle.OutdoorsDark;
      else
        style = MapStyle.Dark;
    }
    else
    {
      if (!useSystemTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO);
      if (!useSystemTheme)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

      if (RoutingController.get().isVehicleNavigation())
        style = MapStyle.VehicleClear;
      else if (Framework.nativeIsOutdoorsLayerEnabled())
        style = MapStyle.OutdoorsClear;
      else
        style = MapStyle.Clear;
    }

    if (!resolvedTheme.equals(oldTheme))
    {
      Config.UiTheme.setCurrent(resolvedTheme);
      DownloaderStatusIcon.clearCache();

      final Activity a = MwmApplication.from(mContext).getTopActivity();
      if (a != null && !a.isFinishing())
        a.recreate();
    }
    else
    {
      // If the UI theme is not changed we just need to change the map style if needed.
      final MapStyle currentStyle = MapStyle.get();
      if (currentStyle == style)
        return;
      SetMapStyle(style);
    }
  }

  @NonNull
  private String getSystemTheme()
  {
    final int nightMode = mContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    return nightMode == Configuration.UI_MODE_NIGHT_YES ? Config.UiTheme.NIGHT : Config.UiTheme.DEFAULT;
  }

  private void SetMapStyle(MapStyle style)
  {
    // If rendering is not active we can mark map style, because all graphics
    // will be recreated after rendering activation.
    if (mRendererActive)
      MapStyle.set(style);
    else
      MapStyle.mark(style);
  }

}
