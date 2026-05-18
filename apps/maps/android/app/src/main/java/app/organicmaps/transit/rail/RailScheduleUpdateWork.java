package app.organicmaps.transit.rail;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import app.organicmaps.sdk.util.log.Logger;
import java.util.concurrent.TimeUnit;

public final class RailScheduleUpdateWork extends Worker
{
  private static final String TAG = RailScheduleUpdateWork.class.getSimpleName();
  private static final String UNIQUE_WORK_NAME = "RailScheduleUpdate";
  private static final String UNIQUE_BOOTSTRAP_WORK_NAME = "RailScheduleBootstrap";

  public RailScheduleUpdateWork(@NonNull Context context, @NonNull WorkerParameters workerParams)
  {
    super(context, workerParams);
  }

  public static void schedule(@NonNull Context context)
  {
    if (!RailScheduleConfig.isUpdateEnabled() && !RailScheduleUpdater.hasBundledPackage(context))
      return;

    OneTimeWorkRequest bootstrap = new OneTimeWorkRequest.Builder(RailScheduleUpdateWork.class).build();
    WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE_BOOTSTRAP_WORK_NAME, ExistingWorkPolicy.KEEP, bootstrap);

    if (!RailScheduleConfig.isUpdateEnabled())
      return;

    Constraints constraints = new Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build();
    PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(RailScheduleUpdateWork.class, 1, TimeUnit.DAYS)
        .setConstraints(constraints)
        .build();
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
  }

  @NonNull
  @Override
  public Result doWork()
  {
    try
    {
      RailScheduleUpdater updater = new RailScheduleUpdater(getApplicationContext());
      updater.installBundledIfAvailable();
      updater.update();
      return Result.success();
    }
    catch (Exception e)
    {
      Logger.w(TAG, "Rail schedule update failed", e);
      return Result.retry();
    }
  }
}
