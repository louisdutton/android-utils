package app.organicmaps.transit.rail;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class RailStation
{
  @NonNull
  public final String stopId;
  @Nullable
  public final String stopCode;
  @NonNull
  public final String name;
  public final double lat;
  public final double lon;
  public final double distanceMeters;

  RailStation(@NonNull String stopId, @Nullable String stopCode, @NonNull String name,
              double lat, double lon, double distanceMeters)
  {
    this.stopId = stopId;
    this.stopCode = stopCode;
    this.name = name;
    this.lat = lat;
    this.lon = lon;
    this.distanceMeters = distanceMeters;
  }

  @NonNull
  public String displayName()
  {
    if (stopCode == null || stopCode.isEmpty())
      return name;
    return name + " (" + stopCode + ")";
  }
}
