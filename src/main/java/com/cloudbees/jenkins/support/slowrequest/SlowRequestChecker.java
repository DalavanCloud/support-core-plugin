package com.cloudbees.jenkins.support.slowrequest;

import com.cloudbees.jenkins.support.timer.FileListCap;
import com.google.inject.Inject;
import hudson.Extension;
import hudson.Functions;
import hudson.model.PeriodicWork;
import hudson.util.IOUtils;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ThreadInfo;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Run periodically to find slow requests and track them.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class SlowRequestChecker extends PeriodicWork {
    /**
     * How often to run the slow request checker
     *
     * @since 2.12
     */
    public static final int RECURRENCE_PERIOD_SEC =
            Integer.getInteger(SlowRequestChecker.class.getName() + ".RECURRENCE_PERIOD_SEC", 3);

    /**
     * Time in milliseconds that's considered too slow for requests.
     * Starting with a bit conservative value to catch serious offenders first.
     * If this value is less than twice {@link #RECURRENCE_PERIOD_SEC} then that will be used instead.
     */
    public static final int THRESHOLD =
            Integer.getInteger(SlowRequestChecker.class.getName() + ".THRESHOLD_MS", 10000);

    /**
     * If there is a slow request, rather than repeatedly capturing the thread dump every {@link #RECURRENCE_PERIOD_SEC}
     * Capture thread dumps of the slow request at a slower cadence.
     */
    public static final long REPEAT_THREAD_DUMP_AFTER_MS =
            Long.getLong(SlowRequestChecker.class.getName() + ".REPEAT_THREAD_DUMP_AFTER_MS", 15000);

    /**
     * Provide a means to disable the slow request checker. This is a volatile non-final field as if you run into
     * issues in a running Jenkins you may need to disable without restarting Jenkins.
     *
     * @since 2.12
     */
    public static volatile boolean DISABLED = Boolean.getBoolean(SlowRequestChecker.class.getName() + ".DISABLED");

    @Inject
    SlowRequestFilter filter;

    @Inject
    Jenkins jenkins;

    final FileListCap logs = new FileListCap(new File(Jenkins.getInstance().getRootDir(), "slow-requests"), 50);

    final SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS");

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(RECURRENCE_PERIOD_SEC);
    }

    @Override
    protected void doRun() throws Exception {
        if (DISABLED) {
            return;
        }
        ThreadInfo[] threads = null;
        long threadsTimestamp = 0;

        final long now = System.currentTimeMillis();

        long iota = System.currentTimeMillis();

        final long recurrencePeriosMillis = TimeUnit.SECONDS.toMillis(RECURRENCE_PERIOD_SEC);
        long thresholdMillis = recurrencePeriosMillis > THRESHOLD ?
                recurrencePeriosMillis * 2 : THRESHOLD;

        OUTER:
        for (InflightRequest req : filter.tracker.values()) {
            long totalTime = now - req.startTime;

            if (totalTime > thresholdMillis) {
                if (req.lastThreadDump + REPEAT_THREAD_DUMP_AFTER_MS > System.currentTimeMillis()) {
                    continue;
                }
                if (threads == null) {
                    threads = Functions.getThreadInfos();
                    threadsTimestamp = System.currentTimeMillis();
                }
                req.lastThreadDump = threadsTimestamp;

                // if the thread has exited while we are taking the thread dump, ignore this.
                if (req.ended) {
                    continue;
                }

                PrintWriter w = null;
                try {
                    if (req.record == null) {
                        req.record = logs.file(format.format(new Date(iota++)) + ".txt");
                        logs.add(req.record);

                        w = new PrintWriter(req.record, "UTF-8");
                        req.writeHeader(w);
                    } else {
                        w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(req.record, true), "UTF-8"));
                        logs.touch(req.record);
                    }

                    for (ThreadInfo thread : threads) {
                        if (req.is(thread)) {
                            w.println("TimeElapsed: " + totalTime + "ms");
                            for (StackTraceElement st : thread.getStackTrace()) {
                                w.println("    " + st);
                            }
                            continue OUTER;
                        }
                    }
                } finally {
                    IOUtils.closeQuietly(w);
                }
            }
        }
    }

}
