package app.organicmaps.widget.placepage.sections;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.R;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.transit.rail.RailDeparture;
import app.organicmaps.transit.rail.RailScheduleStore;
import app.organicmaps.transit.rail.RailStation;
import app.organicmaps.util.UiUtils;
import app.organicmaps.widget.placepage.PlacePageViewModel;
import com.google.android.material.textview.MaterialTextView;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

public class PlacePageRailDeparturesFragment extends Fragment implements Observer<MapObject>
{
  private static final int DEPARTURES_LIMIT = 5;

  private View mFrame;
  private MaterialTextView mStation;
  private LinearLayout mDepartures;
  private MaterialTextView mStatus;
  private PlacePageViewModel mViewModel;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    mViewModel = new ViewModelProvider(requireActivity()).get(PlacePageViewModel.class);
    return inflater.inflate(R.layout.place_page_rail_departures_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    mFrame = view;
    mStation = view.findViewById(R.id.rail_departures_station);
    mDepartures = view.findViewById(R.id.rail_departures_list);
    mStatus = view.findViewById(R.id.rail_departures_status);
  }

  @Override
  public void onStart()
  {
    super.onStart();
    mViewModel.getMapObject().observe(requireActivity(), this);
  }

  @Override
  public void onStop()
  {
    super.onStop();
    mViewModel.getMapObject().removeObserver(this);
  }

  @Override
  public void onChanged(@Nullable MapObject mapObject)
  {
    refresh(mapObject);
  }

  private void refresh(@Nullable MapObject mapObject)
  {
    if (mapObject == null || !isSupportedStation(mapObject))
    {
      UiUtils.hide(mFrame);
      return;
    }

    UiUtils.show(mFrame);
    mDepartures.removeAllViews();

    Context context = requireContext();
    if (!RailScheduleStore.isInstalled(context))
    {
      UiUtils.hide(mStation);
      UiUtils.setTextAndShow(mStatus, getString(R.string.rail_timetable_missing));
      return;
    }

    RailStation station = RailScheduleStore.findNearestDepartureStation(context, mapObject.getLat(), mapObject.getLon());
    if (station == null)
    {
      UiUtils.hide(mFrame);
      return;
    }

    UiUtils.setTextAndShow(mStation, station.displayName());

    LocalDate today = LocalDate.now();
    int nowSeconds = secondsSinceMidnight(LocalTime.now());
    List<RailDeparture> departures =
        RailScheduleStore.nextDepartures(context, station.stopId, today, nowSeconds, DEPARTURES_LIMIT);

    if (departures.isEmpty())
      UiUtils.setTextAndShow(mStatus, getString(R.string.rail_departures_empty_today));
    else
    {
      for (RailDeparture departure : departures)
        mDepartures.addView(createDepartureRow(context, departure, nowSeconds));
      setValidityStatus(context);
    }
  }

  private static boolean isSupportedStation(@NonNull MapObject mapObject)
  {
    return mapObject.isRailwayStation() && RailScheduleStore.isInsideRegion(mapObject.getLat(), mapObject.getLon());
  }

  @NonNull
  private View createDepartureRow(@NonNull Context context, @NonNull RailDeparture departure, int nowSeconds)
  {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, dp(context, 3), 0, dp(context, 3));

    MaterialTextView time = new MaterialTextView(context);
    time.setText(formatDepartureTime(context, departure.departureSeconds));
    time.setTextAppearance(R.style.MwmTextAppearance_Body2_Primary);
    row.addView(time, new LinearLayout.LayoutParams(dp(context, 58), ViewGroup.LayoutParams.WRAP_CONTENT));

    LinearLayout details = new LinearLayout(context);
    details.setOrientation(LinearLayout.VERTICAL);
    details.setGravity(Gravity.CENTER_VERTICAL);

    MaterialTextView destination = new MaterialTextView(context);
    destination.setSingleLine(true);
    destination.setEllipsize(TextUtils.TruncateAt.END);
    destination.setText(formatDestination(departure));
    destination.setTextAppearance(R.style.MwmTextAppearance_Body2_Primary);
    details.addView(destination, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    MaterialTextView service = new MaterialTextView(context);
    service.setSingleLine(true);
    service.setEllipsize(TextUtils.TruncateAt.END);
    service.setText(departure.routeName);
    service.setTextAppearance(R.style.MwmTextAppearance_Body4);
    details.addView(service, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    LinearLayout.LayoutParams destinationParams =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
    row.addView(details, destinationParams);

    MaterialTextView minutes = new MaterialTextView(context);
    minutes.setText(context.getString(R.string.rail_departure_minutes,
                                      Math.max(0, (departure.departureSeconds - nowSeconds) / 60)));
    minutes.setTextAppearance(R.style.MwmTextAppearance_Body4);
    minutes.setTextColor(ContextCompat.getColor(context, R.color.base_green));
    minutes.setGravity(Gravity.END);
    row.addView(minutes, new LinearLayout.LayoutParams(dp(context, 54), ViewGroup.LayoutParams.WRAP_CONTENT));

    return row;
  }

  @NonNull
  private static String formatDestination(@NonNull RailDeparture departure)
  {
    String destination = cleanDestination(departure.destination);
    if (destination.isEmpty())
      return departure.routeName;
    return destination;
  }

  @NonNull
  private static String cleanDestination(@Nullable String destination)
  {
    if (destination == null)
      return "";
    String text = destination.trim();
    int platformStart = text.indexOf(" (Platform ");
    if (platformStart > 0 && text.endsWith(")"))
      return text.substring(0, platformStart);
    return text;
  }

  @NonNull
  private static String formatDepartureTime(@NonNull Context context, int seconds)
  {
    int normalized = ((seconds % 86_400) + 86_400) % 86_400;
    int hour = normalized / 3600;
    int minute = normalized % 3600 / 60;
    if (DateFormat.is24HourFormat(context))
      return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);

    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()));
  }

  private void setValidityStatus(@NonNull Context context)
  {
    String validUntil = RailScheduleStore.validUntil(context);
    if (validUntil == null || validUntil.isEmpty())
    {
      UiUtils.hide(mStatus);
      return;
    }

    try
    {
      LocalDate date = LocalDate.parse(validUntil, DateTimeFormatter.BASIC_ISO_DATE);
      String formatted = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
      UiUtils.setTextAndShow(mStatus, getString(R.string.rail_timetable_valid_until, formatted));
    }
    catch (DateTimeParseException e)
    {
      UiUtils.hide(mStatus);
    }
  }

  private static int secondsSinceMidnight(@NonNull LocalTime time)
  {
    return time.getHour() * 3600 + time.getMinute() * 60 + time.getSecond();
  }

  private static int dp(@NonNull Context context, int value)
  {
    return Math.round(TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
  }
}
