package com.greenwich.eventmanager.util;

import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.model.Session;

import java.util.Calendar;

/**
 * Works out the status of an event / session at a given moment.
 *
 * Two kinds of status:
 *  - MANUAL, set by the admin (Event.status): DRAFT, PUBLISHED, POSTPONED, CANCELLED.
 *  - TIME-BASED, computed from the dates: UPCOMING, ONGOING, ENDED.
 *
 * The DISPLAYED status is the manual one when it is DRAFT / POSTPONED / CANCELLED,
 * otherwise (PUBLISHED) the time-based one.
 *
 * start = startDate + startTime.
 * end   = endDate + endTime, or the end of endDate when there is no end time.
 *
 * Plain Java (no Android classes) so it can be unit tested on the JVM.
 * "now" is always passed in so results are deterministic in tests.
 */
public final class EventStatus {

    public static final String UPCOMING = "UPCOMING";
    public static final String ONGOING = "ONGOING";
    public static final String ENDED = "ENDED";
    public static final String DRAFT = Event.STATUS_DRAFT;
    public static final String POSTPONED = Event.STATUS_POSTPONED;
    public static final String CANCELLED = Event.STATUS_CANCELLED;

    /** A session without an end time is assumed to last this long. */
    public static final int DEFAULT_SESSION_MINUTES = 60;

    private static final long MINUTE = 60_000L;
    private static final long DAY = 24 * 60 * MINUTE;

    private EventStatus() { }

    // ---------------------------------------------------------------------
    // Event
    // ---------------------------------------------------------------------

    /** Epoch millis when the event starts (local time zone), or -1 if the date is invalid. */
    public static long startMillis(Event e) {
        return toMillis(e.getStartDate(), e.getStartTime());
    }

    /** Epoch millis when the event ends (exclusive), or -1 if the date is invalid. */
    public static long endMillis(Event e) {
        String endDate = isEmpty(e.getEndDate()) ? e.getStartDate() : e.getEndDate();
        if (isEmpty(e.getEndTime())) {
            long midnight = toMillis(endDate, "00:00");
            return midnight < 0 ? -1 : addDays(midnight, 1);
        }
        long end = toMillis(endDate, e.getEndTime());
        long start = startMillis(e);
        // Guard against an end time earlier than the start on a one-day event.
        if (end >= 0 && start >= 0 && end <= start) {
            return start + DEFAULT_SESSION_MINUTES * MINUTE;
        }
        return end;
    }

    /** UPCOMING, ONGOING or ENDED from the dates alone. */
    public static String timeStatus(Event e, long now) {
        long start = startMillis(e);
        long end = endMillis(e);
        if (start < 0 || now < start) return UPCOMING;
        if (end >= 0 && now >= end) return ENDED;
        return ONGOING;
    }

    /** The status to show: manual override when set, otherwise the time-based one. */
    public static String displayStatus(Event e, long now) {
        String manual = e.getStatus();
        if (DRAFT.equals(manual) || POSTPONED.equals(manual) || CANCELLED.equals(manual)) {
            return manual;
        }
        return timeStatus(e, now);
    }

    /** True if the event is published and running right now. */
    public static boolean isLive(Event e, long now) {
        return ONGOING.equals(displayStatus(e, now));
    }

    /** Fraction of the event that has elapsed, clamped to 0..1. */
    public static float progress(Event e, long now) {
        long start = startMillis(e);
        long end = endMillis(e);
        if (start < 0 || end <= start || now <= start) return 0f;
        if (now >= end) return 1f;
        return (float) (now - start) / (float) (end - start);
    }

    /** Total number of calendar days the event covers (at least 1). */
    public static int totalDays(Event e) {
        long first = toMillis(e.getStartDate(), "00:00");
        long last = toMillis(isEmpty(e.getEndDate()) ? e.getStartDate() : e.getEndDate(), "00:00");
        if (first < 0 || last < first) return 1;
        return daysBetween(first, last) + 1;
    }

    /**
     * Which day of the event "now" falls on (1-based), clamped to 1..totalDays.
     * E.g. the second day of a three-day event returns 2.
     */
    public static int currentDay(Event e, long now) {
        long first = toMillis(e.getStartDate(), "00:00");
        if (first < 0) return 1;
        int day = daysBetween(first, startOfDay(now)) + 1;
        return Math.max(1, Math.min(day, totalDays(e)));
    }

    // ---------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------

    public static long sessionStartMillis(Session s) {
        return toMillis(s.getSessionDate(), s.getStartTime());
    }

    /** Session end; no end time (or an invalid one) means start + 60 minutes. */
    public static long sessionEndMillis(Session s) {
        long start = sessionStartMillis(s);
        if (start < 0) return -1;
        if (isEmpty(s.getEndTime())) return start + DEFAULT_SESSION_MINUTES * MINUTE;
        long end = toMillis(s.getSessionDate(), s.getEndTime());
        return end > start ? end : start + DEFAULT_SESSION_MINUTES * MINUTE;
    }

    /** UPCOMING, ONGOING or ENDED for one session. */
    public static String sessionTimeStatus(Session s, long now) {
        long start = sessionStartMillis(s);
        if (start < 0 || now < start) return UPCOMING;
        return now >= sessionEndMillis(s) ? ENDED : ONGOING;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** "2026-11-05" + "09:30" -> epoch millis in the default time zone, or -1. */
    public static long toMillis(String isoDate, String time) {
        if (isEmpty(isoDate) || isoDate.trim().length() < 10) return -1;
        try {
            String d = isoDate.trim();
            int y = Integer.parseInt(d.substring(0, 4));
            int mo = Integer.parseInt(d.substring(5, 7));
            int day = Integer.parseInt(d.substring(8, 10));
            int h = 0;
            int mi = 0;
            if (!isEmpty(time) && time.trim().length() >= 5) {
                String t = time.trim();
                h = Integer.parseInt(t.substring(0, 2));
                mi = Integer.parseInt(t.substring(3, 5));
            }
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(y, mo - 1, day, h, mi, 0);
            return c.getTimeInMillis();
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long addDays(long millis, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /** Whole calendar days between two local midnights (DST safe via rounding). */
    public static int daysBetween(long fromMidnight, long toMidnight) {
        return (int) Math.round((toMidnight - fromMidnight) / (double) DAY);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
