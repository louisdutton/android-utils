package app.organicmaps.sdk.location;

import android.content.Context;
import androidx.annotation.NonNull;

public interface LocationProviderFactory
{
  BaseLocationProvider getProvider(@NonNull Context context, @NonNull BaseLocationProvider.Listener listener);
}
