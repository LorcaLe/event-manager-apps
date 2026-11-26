package com.greenwich.eventmanager.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Date/time helpers.
 *
 * Convention used across the app:
 *  - DISPLAY format (what the user types and sees): DD/MM/YYYY
 *  - STORAGE format (SQLite + Firebase):            YYYY-MM-DD (ISO 8601)
 *  - Times are always HH:mm (24-hour).
 *
 * ISO strings sort and compare correctly as plain text, so SQL can use
 * ORDER BY / < / > directly (and indexes) without reformatting.
 *
 * SimpleDateFormat is used instead of java.time because java.time needs
 * API 26+ (or desugaring) and the app supports API 24. A new formatter is
 * created per call because SimpleDateFormat is not thread-safe.
 */
public final class DateUtils {

    private static final String DISPLAY_PATTERN = "dd/MM/yyyy";
    private static final String ISO_PATTERN = "yyyy-MM-dd";

    private static final Pattern DISPLAY_REGEX = Pattern.compile("^\\d{2}/\\d{2}/\\d{4}$");
    private static final Pattern TIME_REGEX = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private DateUtils() { }  // utility class, no instances

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    /** True if text is a real calendar date in DD/MM/YYYY (rejects 31/02/2026). */
    public static boolean isValidDisplayDate(String text) {
        return parse(text, DISPLAY_PATTERN) != null;
    }

    /** True if text is a valid 24-hour time HH:mm. */
    public static boolean isValidTime(String text) {
        return text != null && TIME_REGEX.matcher(text.trim()).matches();
    }

    // ---------------------------------------------------------------------
    // Conversion between display and storage formats
    // ---------------------------------------------------------------------

    /** "05/11/2026" -> "2026-11-05", or null if the input is invalid. */
    public static String displayToIso(String display) {
        Date date = parse(display, DISPLAY_PATTERN);
        return date == null ? null : format(date, ISO_PATTERN);
    }

    /** "2026-11-05" -> "05/11/2026". Returns "" for null/empty input. */
    public static String isoToDisplay(String iso) {
        Date date = parse(iso, ISO_PATTERN);
        return date == null ? "" : format(date, DISPLAY_PATTERN);
    }

    /** Today's date in storage format. */
    public static String todayIso() {
        return format(new Date(), ISO_PATTERN);
    }

    // ---------------------------------------------------------------------
    // Comparisons (ISO strings compare correctly as text)
    // ---------------------------------------------------------------------

    /** True if isoDate lies within [startIso, endIso], inclusive. */
    public static boolean isWithinRange(String isoDate, String startIso, String endIso) {
        return isoDate.compareTo(startIso) >= 0 && isoDate.compareTo(endIso) <= 0;
    }

    /** True if HH:mm time a is strictly before HH:mm time b. */
    public static boolean isTimeBefore(String a, String b) {
        return a.compareTo(b) < 0;
    }

    /**
     * Adds minutes to an HH:mm time, capped at 23:59 so it never rolls over to
     * the next day. Used to suggest a default end time ("start + 2 hours").
     */
    public static String addMinutesCapped(String time, int minutes) {
        int h = Integer.parseInt(time.substring(0, 2));
        int m = Integer.parseInt(time.substring(3, 5));
        int total = Math.min(h * 60 + m + minutes, 23 * 60 + 59);
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    /** Number of days an event covers, e.g. 10th-12th = 3. */
    public static int daysBetweenInclusive(String startIso, String endIso) {
        Date start = parse(startIso, ISO_PATTERN);
        Date end = parse(endIso, ISO_PATTERN);
        if (start == null || end == null) return 1;
        long diff = end.getTime() - start.getTime();
        return (int) (diff / (24L * 60 * 60 * 1000)) + 1;
    }

    // ---------------------------------------------------------------------
    // MaterialDatePicker support (the picker works in UTC milliseconds)
    // ---------------------------------------------------------------------

    /** "2026-11-05" -> UTC millis at midnight, for picker selection/constraints. */
    public static long isoToUtcMillis(String iso) {
        Date date = parseUtc(iso);
        return date == null ? System.currentTimeMillis() : date.getTime();
    }

    /** UTC millis returned by the picker -> "2026-11-05". */
    public static String utcMillisToIso(long millis) {
        SimpleDateFormat f = new SimpleDateFormat(ISO_PATTERN, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private static Date parse(String text, String pattern) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (pattern.equals(DISPLAY_PATTERN) && !DISPLAY_REGEX.matcher(trimmed).matches()) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setLenient(false);  // strict: 31/02 is rejected instead of rolling over to March
        try {
            return f.parse(trimmed);
        } catch (ParseException e) {
            return null;
        }
    }

    private static Date parseUtc(String iso) {
        if (iso == null) return null;
        SimpleDateFormat f = new SimpleDateFormat(ISO_PATTERN, Locale.US);
        f.setLenient(false);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(iso.trim());
        } catch (ParseException e) {
            return null;
        }
    }

    private static String format(Date date, String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(date);
    }

    /** Clears the time part of a Calendar (kept for UI helpers). */
    public static void clearTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }
}