package app.organicmaps.location;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.location.AndroidNativeProvider;
import app.organicmaps.sdk.location.BaseLocationProvider;
import app.organicmaps.sdk.location.LocationProviderFactory;
import app.organicmaps.sdk.util.log.Logger;

public class LocationProviderFactoryImpl implements LocationProviderFactory
{
  private static final String TAG = LocationProviderFactoryImpl.class.getSimpleName();

  @Override
  public BaseLocationProvider getProvider(@NonNull Context context, @NonNull BaseLocationProvider.Listener listener)
  {
    Logger.d(TAG, "Use native provider");
    return new AndroidNativeProvider(context, listener);
  }
}
