package app.organicmaps.editor;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.base.BaseMwmFragment;
import app.organicmaps.sdk.editor.OpeningHours;
import app.organicmaps.util.InputUtils;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.textfield.TextInputEditText;

public class AdvancedTimetableFragment extends BaseMwmFragment implements TimetableProvider
{
  private TextInputEditText mInput;
  private static ShapeableImageView mSaveButton;
  @Nullable
  private String mInitTimetables;
  @Nullable
  TimetableChangedListener mListener;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
  {
    return inflater.inflate(R.layout.fragment_timetable_advanced, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    initViews(view);
    refreshTimetables();
  }

  @Override
  public void onResume()
  {
    super.onResume();
  }

  private void initViews(View view)
  {
    mInput = view.findViewById(R.id.et__timetable);
    setTextChangedListener(mInput, mListener);
    mSaveButton = getParentFragment().getParentFragment().getView().findViewById(R.id.save);
  }

  @Nullable
  @Override
  public String getTimetables()
  {
    return mInput.getText().toString();
  }

  @Override
  public void setTimetables(@Nullable String timetables)
  {
    mInitTimetables = timetables;
    refreshTimetables();
  }

  private void refreshTimetables()
  {
    if (mInput == null || mInitTimetables == null)
      return;

    mInput.setText(mInitTimetables);
    mInput.requestFocus();
    InputUtils.showKeyboard(mInput);
  }

  void setTimetableChangedListener(@NonNull TimetableChangedListener listener)
  {
    mListener = listener;
    setTextChangedListener(mInput, mListener);
  }

  private static void setTextChangedListener(@Nullable TextInputEditText input,
                                             @Nullable TimetableChangedListener listener)
  {
    if (input == null || listener == null)
      return;

    input.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after)
      {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count)
      {
        mSaveButton.setEnabled(OpeningHours.nativeIsTimetableStringValid(s.toString()));
      }

      @Override
      public void afterTextChanged(Editable s)
      {
        listener.onTimetableChanged(s.toString());
      }
    });
  }
}
