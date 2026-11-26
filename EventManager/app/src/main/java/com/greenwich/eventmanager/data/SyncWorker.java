package com.greenwich.eventmanager.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Background synchronisation with WorkManager (extra feature f: uploading on a thread).
 *
 * Whenever local data changes, a sync is scheduled with the condition "network connected":
 *  - online  -> it runs straight away on a background thread;
 *  - offline -> Android runs it automatically once the connection comes back,
 *               even if the app has been closed in the meantime.
 * If the upload fails it is retried later with an increasing delay (exponential backoff).
 */
public class SyncWorker extends Worker {

    public static final String UNIQUE_NAME = "event-sync";
    private static final int MAX_ATTEMPTS = 5;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            SyncManager.syncBlocking(getApplicationContext(), false);
            return Result.success();
        } catch (SyncManager.SyncException e) {
            return getRunAttemptCount() < MAX_ATTEMPTS ? Result.retry() : Result.failure();
        }
    }

    /** Schedules a sync. Called by DatabaseHelper after every local change. */
    public static void requestSync(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        // APPEND_OR_REPLACE: a change made while a sync is running gets its own run after it
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }
}