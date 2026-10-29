package se.jobbdialer;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * Runs {@link CallLogCleaner#sweep} when the call log changes (content trigger) or when the
 * override deadline is reached, and re-schedules itself as long as calls are still pending.
 * Content-triggered jobs fire once, so re-scheduling is what keeps the observation alive.
 */
public class CallLogCleanupJobService extends JobService {

    private static final String TAG = "Jobbdialer";

    private volatile Thread worker;

    @Override
    public boolean onStartJob(final JobParameters params) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean stillPending = false;
                try {
                    stillPending = CallLogCleaner.sweep(getApplicationContext());
                } catch (RuntimeException e) {
                    Log.w(TAG, "Cleanup job failed", e);
                } finally {
                    worker = null;
                    try {
                        jobFinished(params, false);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "jobFinished failed", e);
                    }
                }
                if (stillPending) {
                    CallLogCleaner.scheduleJob(getApplicationContext());
                }
            }
        }, "jobbdialer-calllog-cleanup");
        worker = t;
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Stopped by the system before the sweep finished: ask for a retry. The sweep itself is
        // short and idempotent, so letting the running thread complete is harmless.
        return worker != null;
    }
}
