package app.organicmaps.sdk.location;

import android.location.Location;
import androidx.annotation.NonNull;

public interface LocationListener
{
  void onLocationUpdated(@NonNull Location location);

  default void onLocationUpdateTimeout()
  {
    // No op.
  }

  default void onLocationDisabled()
  {
    // No op.
  }

}
