package app.organicmaps.transit.rail;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class RailScheduleStore
{
  private static final double MAX_STATION_DISTANCE_METERS = 1200.0;
  private static final String PREFS = "rail_schedule";
  private static final String KEY_PACKAGE_SHA256 = "package_sha256";
  private static final String KEY_GENERATED_AT = "generated_at";
  private static final String KEY_VALID_FROM = "valid_from";
  private static final String KEY_VALID_UNTIL = "valid_until";
  private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private RailScheduleStore() {}

  @NonNull
  static File directory(@NonNull Context context)
  {
    return new File(context.getFilesDir(), "rail-schedules");
  }

  @NonNull
  public static File databaseFile(@NonNull Context context)
  {
    return new File(directory(context), RailScheduleConfig.DATABASE_FILE_NAME);
  }

  public static boolean isInstalled(@NonNull Context context)
  {
    return databaseFile(context).isFile();
  }

  public static boolean isInsideRegion(double lat, double lon)
  {
    return RailScheduleConfig.isInsideRegion(lat, lon);
  }

  @Nullable
  public static String validUntil(@NonNull Context context)
  {
    return prefs(context).getString(KEY_VALID_UNTIL, null);
  }

  @Nullable
  static String packageSha256(@NonNull Context context)
  {
    return prefs(context).getString(KEY_PACKAGE_SHA256, null);
  }

  static void saveInstalledPackage(@NonNull Context context, @NonNull String packageSha256,
                                   @NonNull String generatedAt, @NonNull String validFrom,
                                   @NonNull String validUntil)
  {
    prefs(context)
        .edit()
        .putString(KEY_PACKAGE_SHA256, packageSha256)
        .putString(KEY_GENERATED_AT, generatedAt)
        .putString(KEY_VALID_FROM, validFrom)
        .putString(KEY_VALID_UNTIL, validUntil)
        .apply();
  }

  @NonNull
  public static List<RailDeparture> nextDepartures(@NonNull Context context, @NonNull String stationStopId,
                                                   @NonNull LocalDate date, int fromSeconds, int limit)
  {
    File dbFile = databaseFile(context);
    if (!dbFile.isFile())
      return List.of();

    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY))
    {
      return nextDepartures(db, stationStopId, date, fromSeconds, limit);
    }
  }

  @Nullable
  public static RailStation findNearestDepartureStation(@NonNull Context context, double lat, double lon)
  {
    File dbFile = databaseFile(context);
    if (!dbFile.isFile() || !RailScheduleConfig.isInsideRegion(lat, lon))
      return null;

    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY))
    {
      return findNearestDepartureStation(db, lat, lon);
    }
  }

  @Nullable
  static RailStation findNearestDepartureStation(@NonNull SQLiteDatabase db, double lat, double lon)
  {
    double latDelta = MAX_STATION_DISTANCE_METERS / 111_320.0;
    double lonScale = Math.max(0.2, Math.cos(Math.toRadians(lat)));
    double lonDelta = MAX_STATION_DISTANCE_METERS / (111_320.0 * lonScale);
    String sql =
        "SELECT stop_id, stop_code, stop_name, stop_lat, stop_lon" +
        " FROM stations" +
        " WHERE is_region_station = 1" +
        "   AND (parent_station = '' OR location_type = 1)" +
        "   AND stop_lat BETWEEN ? AND ?" +
        "   AND stop_lon BETWEEN ? AND ?";
    String[] args = {
        Double.toString(lat - latDelta), Double.toString(lat + latDelta),
        Double.toString(lon - lonDelta), Double.toString(lon + lonDelta)
    };

    RailStation best = null;
    try (Cursor cursor = db.rawQuery(sql, args))
    {
      while (cursor.moveToNext())
      {
        double stationLat = cursor.getDouble(3);
        double stationLon = cursor.getDouble(4);
        double distance = distanceMeters(lat, lon, stationLat, stationLon);
        if (distance > MAX_STATION_DISTANCE_METERS)
          continue;
        if (best == null || distance < best.distanceMeters)
        {
          String code = cursor.getString(1);
          best = new RailStation(cursor.getString(0), code == null || code.isEmpty() ? null : code,
                                 cursor.getString(2), stationLat, stationLon, distance);
        }
      }
    }
    return best;
  }

  @NonNull
  static List<RailDeparture> nextDepartures(@NonNull SQLiteDatabase db, @NonNull String stationStopId,
                                            @NonNull LocalDate date, int fromSeconds, int limit)
  {
    String dateText = GTFS_DATE.format(date);
    String weekdayColumn = weekdayColumn(date.getDayOfWeek());
    String sql =
        "WITH active AS (" +
        "  SELECT c.service_id FROM calendar c" +
        "  WHERE c.start_date <= ? AND c.end_date >= ? AND c." + weekdayColumn + " = 1" +
        "    AND NOT EXISTS (" +
        "      SELECT 1 FROM calendar_dates removed" +
        "      WHERE removed.service_id = c.service_id AND removed.date = ? AND removed.exception_type = 2" +
        "    )" +
        "  UNION" +
        "  SELECT added.service_id FROM calendar_dates added" +
        "  WHERE added.date = ? AND added.exception_type = 1" +
        "), requested_station AS (" +
        "  SELECT COALESCE(NULLIF(parent_station, ''), stop_id) AS station_id" +
        "  FROM stations WHERE stop_id = ?" +
        "), station_stops AS (" +
        "  SELECT stop_id FROM stations" +
        "  WHERE stop_id IN (SELECT station_id FROM requested_station)" +
        "     OR parent_station IN (SELECT station_id FROM requested_station)" +
        ")" +
        "SELECT st.trip_id, st.stop_id, st.departure_time, st.departure_seconds," +
        "       COALESCE(NULLIF(r.route_short_name, ''), NULLIF(r.route_long_name, ''), r.route_id) AS route_name," +
        "       COALESCE(NULLIF(st.stop_headsign, '')," +
        "                (SELECT s2.stop_name" +
        "                 FROM stop_times st2" +
        "                 JOIN stations s2 ON s2.stop_id = st2.stop_id" +
        "                 WHERE st2.trip_id = st.trip_id AND st2.stop_sequence > st.stop_sequence" +
        "                 ORDER BY st2.stop_sequence DESC" +
        "                 LIMIT 1)," +
        "                NULLIF(tr.trip_headsign, ''), NULLIF(tr.trip_short_name, '')) AS destination" +
        " FROM stop_times st" +
        " JOIN trips tr ON tr.trip_id = st.trip_id" +
        " JOIN active a ON a.service_id = tr.service_id" +
        " JOIN routes r ON r.route_id = tr.route_id" +
        " WHERE st.stop_id IN (SELECT stop_id FROM station_stops)" +
        "   AND st.departure_seconds >= ?" +
        "   AND EXISTS (" +
        "     SELECT 1 FROM stop_times later" +
        "     WHERE later.trip_id = st.trip_id AND later.stop_sequence > st.stop_sequence" +
        "   )" +
        " ORDER BY st.departure_seconds ASC" +
        " LIMIT ?";

    List<RailDeparture> departures = new ArrayList<>();
    String[] args = {
        dateText, dateText, dateText, dateText, stationStopId,
        Integer.toString(Math.max(0, fromSeconds)), Integer.toString(Math.max(1, limit))
    };
    try (Cursor cursor = db.rawQuery(sql, args))
    {
      while (cursor.moveToNext())
      {
        departures.add(new RailDeparture(
            cursor.getString(0),
            cursor.getString(1),
            cursor.getString(2),
            cursor.getInt(3),
            cursor.getString(4),
            cursor.isNull(5) ? null : cursor.getString(5)));
      }
    }
    return departures;
  }

  @NonNull
  private static SharedPreferences prefs(@NonNull Context context)
  {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  @NonNull
  private static String weekdayColumn(@NonNull DayOfWeek dayOfWeek)
  {
    return switch (dayOfWeek)
    {
      case MONDAY -> "monday";
      case TUESDAY -> "tuesday";
      case WEDNESDAY -> "wednesday";
      case THURSDAY -> "thursday";
      case FRIDAY -> "friday";
      case SATURDAY -> "saturday";
      case SUNDAY -> "sunday";
    };
  }

  private static double distanceMeters(double lat1, double lon1, double lat2, double lon2)
  {
    double earthRadiusMeters = 6_371_000.0;
    double lat1Rad = Math.toRadians(lat1);
    double lat2Rad = Math.toRadians(lat2);
    double deltaLat = Math.toRadians(lat2 - lat1);
    double deltaLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0)
               + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                     * Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0);
    double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    return earthRadiusMeters * c;
  }
}
