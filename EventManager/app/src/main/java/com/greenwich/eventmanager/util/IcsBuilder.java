package com.greenwich.eventmanager.util;

import android.text.TextUtils;

import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.model.Session;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds iCalendar (RFC 5545) text, the standard calendar format understood by
 * Google Calendar, Outlook and Apple Calendar.
 *
 * Used by two extra features (f):
 *  - sessionAsVEvent()   -> content of a session's QR code. Phone cameras recognise
 *                           "BEGIN:VEVENT" and offer to add it to the calendar.
 *  - scheduleAsCalendar() -> a .ics file with every session of an event (export).
 *
 * Times are written as "floating" local times (no time zone), so they appear at
 * the same clock time on the attendee's calendar.
 */
public final class IcsBuilder {

    private static final String CRLF = "\r\n";              // required by the standard
    private static final int DEFAULT_SESSION_MINUTES = 60;
    private static final int DEFAULT_EVENT_MINUTES = 120;

    private IcsBuilder() { }

    /** One session, for a QR code (kept short so the code stays easy to scan). */
    public static String sessionAsVEvent(Event event, Session session) {
        StringBuilder sb = new StringBuilder();
        appendSession(sb, event, session, false);
        return sb.toString();
    }

    /** Whole schedule of an event as a calendar file. */
    public static String scheduleAsCalendar(Event event, List<Session> sessions) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//University of Greenwich//Event Manager//EN");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "X-WR-CALNAME:" + escape(event.getName()));

        if (sessions.isEmpty()) {
            appendEventItself(sb, event);       // no sessions yet: export the event as one entry
        } else {
            for (Session s : sessions) {
                appendSession(sb, event, s, true);
            }
        }
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    // ---------------------------------------------------------------------

    private static void appendSession(StringBuilder sb, Event e, Session s, boolean withDescription) {
        String end = !TextUtils.isEmpty(s.getEndTime())
                ? s.getEndTime()
                : DateUtils.addMinutesCapped(s.getStartTime(), DEFAULT_SESSION_MINUTES);

        List<String> details = new ArrayList<>();
        details.add(e.getName());
        details.add(s.getType());
        if (!TextUtils.isEmpty(s.getSpeaker())) details.add("Speaker: " + s.getSpeaker());
        if (withDescription && !TextUtils.isEmpty(s.getDescription())) details.add(s.getDescription());

        String location = TextUtils.isEmpty(s.getRoom())
                ? e.getLocation()
                : s.getRoom() + ", " + e.getLocation();

        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + s.getUuid() + "@eventmanager.greenwich");
        line(sb, "DTSTAMP:" + nowUtc());
        line(sb, "DTSTART:" + dateTime(s.getSessionDate(), s.getStartTime()));
        line(sb, "DTEND:" + dateTime(s.getSessionDate(), end));
        line(sb, "SUMMARY:" + escape(s.getName()));
        line(sb, "LOCATION:" + escape(location));
        line(sb, "DESCRIPTION:" + escape(TextUtils.join("\n", details)));
        line(sb, "END:VEVENT");
    }

    private static void appendEventItself(StringBuilder sb, Event e) {
        String end = !TextUtils.isEmpty(e.getEndTime())
                ? e.getEndTime()
                : DateUtils.addMinutesCapped(e.getStartTime(), DEFAULT_EVENT_MINUTES);

        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + e.getUuid() + "@eventmanager.greenwich");
        line(sb, "DTSTAMP:" + nowUtc());
        line(sb, "DTSTART:" + dateTime(e.getStartDate(), e.getStartTime()));
        line(sb, "DTEND:" + dateTime(e.getEndDate(), end));
        line(sb, "SUMMARY:" + escape(e.getName()));
        line(sb, "LOCATION:" + escape(e.getLocation()));
        if (!TextUtils.isEmpty(e.getDescription())) {
            line(sb, "DESCRIPTION:" + escape(e.getDescription()));
        }
        line(sb, "END:VEVENT");
    }

    /** "2026-11-10" + "09:30" -> "20261110T093000" */
    private static String dateTime(String isoDate, String time) {
        return isoDate.replace("-", "") + "T" + time.replace(":", "") + "00";
    }

    private static String nowUtc() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /** Commas, semicolons, backslashes and new lines must be escaped in iCalendar text. */
    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    private static void line(StringBuilder sb, String text) {
        sb.append(text).append(CRLF);
    }
}