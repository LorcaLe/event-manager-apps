package com.greenwich.eventmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.model.Session;
import com.greenwich.eventmanager.util.EventStatus;

import org.junit.Test;

/**
 * Unit tests for the time-based / manual status logic (runs on the JVM, no device).
 * Times are built with EventStatus.toMillis, so they use the same local time zone.
 */
public class EventStatusTest {

    private static long at(String date, String time) {
        return EventStatus.toMillis(date, time);
    }

    private static Event event(String start, String end, String startTime, String endTime) {
        Event e = new Event();
        e.setName("Test");
        e.setStartDate(start);
        e.setEndDate(end);
        e.setStartTime(startTime);
        e.setEndTime(endTime);
        return e;
    }

    // ----- One-day event without an end time: runs until the end of that day -----

    @Test
    public void oneDayNoEndTime_beforeStart_isUpcoming() {
        Event e = event("2026-11-26", "2026-11-26", "09:00", null);
        assertEquals(EventStatus.UPCOMING, EventStatus.timeStatus(e, at("2026-11-26", "08:59")));
    }

    @Test
    public void oneDayNoEndTime_lateEvening_isOngoing() {
        Event e = event("2026-11-26", "2026-11-26", "09:00", null);
        assertEquals(EventStatus.ONGOING, EventStatus.timeStatus(e, at("2026-11-26", "23:30")));
        assertTrue(EventStatus.isLive(e, at("2026-11-26", "12:00")));
    }

    @Test
    public void oneDayNoEndTime_nextDay_isEnded() {
        Event e = event("2026-11-26", "2026-11-26", "09:00", null);
        assertEquals(EventStatus.ENDED, EventStatus.timeStatus(e, at("2026-11-27", "00:00")));
    }

    @Test
    public void oneDayWithEndTime_afterEnd_isEnded() {
        Event e = event("2026-11-26", "2026-11-26", "09:00", "11:00");
        assertEquals(EventStatus.ONGOING, EventStatus.timeStatus(e, at("2026-11-26", "10:59")));
        assertEquals(EventStatus.ENDED, EventStatus.timeStatus(e, at("2026-11-26", "11:00")));
    }

    // ----- Multi-day event -----

    @Test
    public void multiDay_secondDay_isOngoingDay2Of3() {
        Event e = event("2026-11-25", "2026-11-27", "08:30", "17:00");
        long now = at("2026-11-26", "10:00");
        assertEquals(EventStatus.ONGOING, EventStatus.timeStatus(e, now));
        assertEquals(3, EventStatus.totalDays(e));
        assertEquals(2, EventStatus.currentDay(e, now));
        float p = EventStatus.progress(e, now);
        assertTrue(p > 0.3f && p < 0.5f);
    }

    @Test
    public void multiDay_progressClampedOutsideRange() {
        Event e = event("2026-11-25", "2026-11-27", "08:30", "17:00");
        assertEquals(0f, EventStatus.progress(e, at("2026-11-24", "12:00")), 0.0001f);
        assertEquals(1f, EventStatus.progress(e, at("2026-11-28", "12:00")), 0.0001f);
        assertEquals(1, EventStatus.currentDay(e, at("2026-11-20", "12:00")));
        assertEquals(3, EventStatus.currentDay(e, at("2026-11-30", "12:00")));
    }

    @Test
    public void multiDay_afterLastDayEndTime_isEnded() {
        Event e = event("2026-11-25", "2026-11-27", "08:30", "17:00");
        assertEquals(EventStatus.ENDED, EventStatus.timeStatus(e, at("2026-11-27", "17:01")));
    }

    // ----- Manual status overrides the time-based one -----

    @Test
    public void draftOverridesTime() {
        Event e = event("2026-11-26", "2026-11-26", "09:00", null);
        e.setStatus(Event.STATUS_DRAFT);
        assertEquals(EventStatus.DRAFT, EventStatus.displayStatus(e, at("2026-11-26", "12:00")));
    }

    @Test
    public void cancelledOverridesTime() {
        Event e = event("2026-11-20", "2026-11-20", "09:00", null);
        e.setStatus(Event.STATUS_CANCELLED);
        assertEquals(EventStatus.CANCELLED, EventStatus.displayStatus(e, at("2026-11-26", "12:00")));
    }

    @Test
    public void postponedOverridesTime() {
        Event e = event("2026-12-01", "2026-12-01", "09:00", null);
        e.setStatus(Event.STATUS_POSTPONED);
        assertEquals(EventStatus.POSTPONED, EventStatus.displayStatus(e, at("2026-11-26", "12:00")));
    }

    @Test
    public void publishedUsesTime() {
        Event e = event("2026-11-26", "2026-11-26", "09:00", null);
        e.setStatus(Event.STATUS_PUBLISHED);
        assertEquals(EventStatus.ONGOING, EventStatus.displayStatus(e, at("2026-11-26", "12:00")));
        e.setStatus(null);   // missing status = published
        assertEquals(EventStatus.ONGOING, EventStatus.displayStatus(e, at("2026-11-26", "12:00")));
    }

    // ----- Sessions -----

    @Test
    public void sessionWithoutEndTime_lastsSixtyMinutes() {
        Session s = new Session();
        s.setSessionDate("2026-11-26");
        s.setStartTime("10:00");
        assertEquals(EventStatus.UPCOMING, EventStatus.sessionTimeStatus(s, at("2026-11-26", "09:59")));
        assertEquals(EventStatus.ONGOING, EventStatus.sessionTimeStatus(s, at("2026-11-26", "10:59")));
        assertEquals(EventStatus.ENDED, EventStatus.sessionTimeStatus(s, at("2026-11-26", "11:00")));
    }

    @Test
    public void sessionWithEndTime() {
        Session s = new Session();
        s.setSessionDate("2026-11-26");
        s.setStartTime("10:30");
        s.setEndTime("12:00");
        assertEquals(EventStatus.ONGOING, EventStatus.sessionTimeStatus(s, at("2026-11-26", "11:45")));
        assertEquals(EventStatus.ENDED, EventStatus.sessionTimeStatus(s, at("2026-11-26", "12:00")));
    }
}
