package com.greenwich.eventmanager.util;

import android.content.Context;
import android.text.TextUtils;

import com.greenwich.eventmanager.R;
import com.greenwich.eventmanager.model.Event;

/**
 * Formatting shared by several screens (list, confirmation, detail),
 * kept in one place to avoid duplicate code.
 */
public final class EventFormat {

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
}