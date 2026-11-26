package com.greenwich.eventmanager.util;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.StringRes;

import com.greenwich.eventmanager.R;
import com.greenwich.eventmanager.model.Event;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Formatting shared by several screens (list, confirmation, detail),
 * kept in one place to avoid duplicate code.
 */
public final class EventFormat {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private EventFormat() { }

    /** "05/11/2026" for one-day events, "10/11/2026 – 12/11/2026 · 3 days" otherwise. */
    public static String formatDates(Context ctx, Event event) {
        String start = DateUtils.isoToDisplay(event.getStartDate());
        if (!event.isMultiDay()) {
            return start;
        }
        String end = DateUtils.isoToDisplay(event.getEndDate());
        int days = DateUtils.daysBetweenInclusive(event.getStartDate(), event.getEndDate());
        String range = ctx.getString(R.string.format_date_range, start, end);
        String dayLabel = ctx.getResources().getQuantityString(R.plurals.day_count, days, days);
        return ctx.getString(R.string.format_dates_with_days, range, dayLabel);
    }

    /** Compact dates for cards: "05/11" or "25/11 – 27/11". */
    public static String formatShortDates(Context ctx, Event event) {
        String start = shortDate(event.getStartDate());
        if (!event.isMultiDay()) {
            return start;
        }
        return ctx.getString(R.string.format_date_range, start, shortDate(event.getEndDate()));
    }

    /** "09:00 – 11:00", or just "09:00" when no end time was given. */
    public static String formatTimes(Context ctx, Event event) {
        return formatTimeRange(ctx, event.getStartTime(), event.getEndTime());
    }

    /** Shared by events and sessions. */
    public static String formatTimeRange(Context ctx, String start, String end) {
        if (TextUtils.isEmpty(end)) {
            return start;
        }
        return ctx.getString(R.string.format_time_range, start, end);
    }

    /** "NOV" for the date block on cards. */
    public static String monthShort(String iso) {
        return pattern(iso, "MMM").toUpperCase(Locale.US);
    }

    /** "05" for the date block on cards. */
    public static String dayOfMonth(String iso) {
        return pattern(iso, "dd");
    }

    /** "Tue 25/11 08:30" for start / end labels. */
    public static String formatDayDateTime(long millis) {
        return new SimpleDateFormat("EEE dd/MM HH:mm", Locale.US).format(new Date(millis));
    }

    // ---------------------------------------------------------------------
    // Status labels
    // ---------------------------------------------------------------------

    /** Human label ("Draft", "Published", "Live", ...) for a manual or display status. */
    public static String statusLabel(Context ctx, String status) {
        return ctx.getString(statusLabelRes(status));
    }

    @StringRes
    private static int statusLabelRes(String status) {
        if (status == null) return R.string.status_label_published;
        switch (status) {
            case Event.STATUS_DRAFT: return R.string.status_label_draft;
            case Event.STATUS_POSTPONED: return R.string.status_label_postponed;
            case Event.STATUS_CANCELLED: return R.string.status_label_cancelled;
            case EventStatus.UPCOMING: return R.string.status_label_upcoming;
            case EventStatus.ONGOING: return R.string.status_label_live;
            case EventStatus.ENDED: return R.string.status_label_ended;
            default: return R.string.status_label_published;
        }
    }

    /** Upper-case badge text ("LIVE", "POSTPONED", ...) for a display status. */
    public static String statusBadge(Context ctx, String displayStatus) {
        @StringRes int res;
        switch (displayStatus == null ? "" : displayStatus) {
            case Event.STATUS_DRAFT: res = R.string.badge_draft; break;
            case Event.STATUS_POSTPONED: res = R.string.badge_postponed; break;
            case Event.STATUS_CANCELLED: res = R.string.badge_cancelled; break;
            case EventStatus.ONGOING: res = R.string.badge_live; break;
            case EventStatus.ENDED: res = R.string.badge_ended; break;
            default: res = R.string.badge_upcoming; break;
        }
        return ctx.getString(res);
    }

    /** Labels of the manual statuses, in the order of Event.STATUSES. */
    public static String[] manualStatusLabels(Context ctx) {
        String[] labels = new String[Event.STATUSES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = statusLabel(ctx, Event.STATUSES[i]);
        }
        return labels;
    }

    /** Inverse of statusLabel for the manual statuses; null if nothing matches. */
    public static String manualStatusFromLabel(Context ctx, String label) {
        for (String status : Event.STATUSES) {
            if (statusLabel(ctx, status).equals(label)) return status;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Countdown / progress
    // ---------------------------------------------------------------------

    /**
     * Short text describing where the event is in time:
     *  - upcoming:  "Starts in 2 d 4 h" / "Starts in 3 h 20 min"
     *  - ongoing:   "Day 2 of 3" (multi-day) or "Ends in 1 h 10 min"
     *  - ended:     "Ended"
     * Returns "" for draft / postponed / cancelled events.
     */
    public static String countdown(Context ctx, Event e, long now) {
        String status = EventStatus.displayStatus(e, now);
        switch (status) {
            case EventStatus.UPCOMING:
                return ctx.getString(R.string.countdown_starts_in,
                        duration(ctx, EventStatus.startMillis(e) - now));
            case EventStatus.ONGOING:
                int total = EventStatus.totalDays(e);
                if (total > 1) {
                    return dayOf(ctx, e, now);
                }
                return ctx.getString(R.string.countdown_ends_in,
                        duration(ctx, EventStatus.endMillis(e) - now));
            case EventStatus.ENDED:
                return ctx.getString(R.string.countdown_ended);
            default:
                return "";
        }
    }

    /** "Day 2 of 3". */
    public static String dayOf(Context ctx, Event e, long now) {
        return ctx.getString(R.string.format_day_of,
                EventStatus.currentDay(e, now), EventStatus.totalDays(e));
    }

    /** "today", "tomorrow", "in 5 days" (calendar days until the start). */
    public static String relativeStart(Context ctx, Event e, long now) {
        long start = EventStatus.startMillis(e);
        if (start < 0) return "";
        int days = EventStatus.daysBetween(EventStatus.startOfDay(now), EventStatus.startOfDay(start));
        if (days <= 0) return ctx.getString(R.string.relative_today);
        if (days == 1) return ctx.getString(R.string.relative_tomorrow);
        return ctx.getResources().getQuantityString(R.plurals.relative_in_days, days, days);
    }

    /** "2 d 4 h", "3 h 20 min" or "15 min" (never negative). */
    public static String duration(Context ctx, long millis) {
        long ms = Math.max(0, millis);
        long days = ms / DAY;
        long hours = (ms % DAY) / HOUR;
        long minutes = (ms % HOUR) / MINUTE;
        if (days > 0) {
            return ctx.getString(R.string.duration_days_hours, (int) days, (int) hours);
        }
        if (hours > 0) {
            return ctx.getString(R.string.duration_hours_minutes, (int) hours, (int) minutes);
        }
        return ctx.getString(R.string.duration_minutes, (int) Math.max(1, minutes));
    }

    // ---------------------------------------------------------------------

    private static String shortDate(String iso) {
        return pattern(iso, "dd/MM");
    }

    private static String pattern(String iso, String pattern) {
        long millis = EventStatus.toMillis(iso, null);
        if (millis < 0) return "";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(millis));
    }
}
