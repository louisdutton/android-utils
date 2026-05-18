package app.organicmaps.transit.rail;

import androidx.annotation.NonNull;
import app.organicmaps.BuildConfig;

final class RailScheduleConfig
{
  static final int SCHEMA_VERSION = 2;
  @NonNull
  static final String REGION_ID = "gb-cornwall-rail";
  @NonNull
  static final String DATABASE_FILE_NAME = REGION_ID + ".sqlite";
  @NonNull
  static final String MANIFEST_URL = BuildConfig.RAIL_SCHEDULE_MANIFEST_URL;
  @NonNull
  static final String BUNDLED_MANIFEST_ASSET = "rail-schedules/" + REGION_ID + ".manifest.json";
  static final double MIN_LAT = 49.90;
  static final double MAX_LAT = 50.95;
  static final double MIN_LON = -5.85;
  static final double MAX_LON = -4.00;

  private RailScheduleConfig() {}

  static boolean isUpdateEnabled()
  {
    return !MANIFEST_URL.isEmpty();
  }

  static boolean isInsideRegion(double lat, double lon)
  {
    return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
  }
}
