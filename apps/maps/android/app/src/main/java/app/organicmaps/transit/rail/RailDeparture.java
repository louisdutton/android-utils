package app.organicmaps.transit.rail;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class RailDeparture
{
  @NonNull
  public final String tripId;
  @NonNull
  public final String stopId;
  @NonNull
  public final String departureTime;
  public final int departureSeconds;
  @NonNull
  public final String routeName;
  @Nullable
  public final String destination;

  RailDeparture(@NonNull String tripId, @NonNull String stopId, @NonNull String departureTime,
                int departureSeconds, @NonNull String routeName, @Nullable String destination)
  {
    this.tripId = tripId;
    this.stopId = stopId;
    this.departureTime = departureTime;
    this.departureSeconds = departureSeconds;
    this.routeName = routeName;
    this.destination = destination;
  }
}
