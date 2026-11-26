package com.greenwich.eventmanager.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.model.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * Local SQLite database for the Admin app.
 *
 * Design notes:
 *  - Two tables: events (parent) and sessions (child, FK with ON DELETE CASCADE).
 *  - Dates are stored as ISO text (YYYY-MM-DD) so they sort/compare correctly
 *    and can use indexes. The UI shows them as DD/MM/YYYY (see DateUtils).
 *  - Events can span several days: start_date..end_date (end = start for one-day events).
 *    Every session has its own session_date inside that range.
 *  - Events carry sync flags (is_dirty, is_deleted). Any change to an event OR to
 *    one of its sessions marks the event dirty, so the sync service only needs to
 *    re-upload dirty events (each event is uploaded together with its sessions).
 *  - Deleting an event is a "soft delete": its sessions are removed immediately,
 *    the event row is kept with is_deleted = 1 until the cloud copy is removed,
 *    then purged.
 *  - All queries use "?" parameters, never string concatenation of user input
 *    (prevents SQL injection).
 *  - Singleton so the whole app shares one connection.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "event_manager.db";
    // v2: start_time/end_time + latitude/longitude on events
    private static final int DB_VERSION = 2;

    // ----- Table: events -----
    public static final String T_EVENTS = "events";
    public static final String E_ID = "_id";
    public static final String E_UUID = "uuid";
    public static final String E_NAME = "name";
    public static final String E_START_DATE = "start_date";
    public static final String E_END_DATE = "end_date";
    public static final String E_START_TIME = "start_time";
    public static final String E_END_TIME = "end_time";
    public static final String E_LOCATION = "location";
    public static final String E_LATITUDE = "latitude";
    public static final String E_LONGITUDE = "longitude";
    public static final String E_ORGANISER = "organiser";
    public static final String E_TYPE = "type";
    public static final String E_DESCRIPTION = "description";
    public static final String E_EMAIL = "contact_email";
    public static final String E_PHONE = "contact_phone";
    public static final String E_UPDATED_AT = "updated_at";
    public static final String E_DIRTY = "is_dirty";
    public static final String E_DELETED = "is_deleted";

    // ----- Table: sessions -----
    public static final String T_SESSIONS = "sessions";
    public static final String S_ID = "_id";
    public static final String S_UUID = "uuid";
    public static final String S_EVENT_ID = "event_id";
    public static final String S_NAME = "name";
    public static final String S_DATE = "session_date";
    public static final String S_START_TIME = "start_time";
    public static final String S_END_TIME = "end_time";
    public static final String S_TYPE = "type";
    public static final String S_SPEAKER = "speaker";
    public static final String S_ROOM = "room";
    public static final String S_DESCRIPTION = "description";

    /** Alias for the computed number of sessions per event. */
    private static final String SESSION_COUNT = "session_count";

    private static final String EVENT_ORDER = "e." + E_START_DATE + ", e." + E_START_TIME;
    private static final String SESSION_ORDER = S_DATE + ", " + S_START_TIME;

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appContext = context;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // Foreign keys are OFF by default in SQLite; needed for ON DELETE CASCADE.
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_EVENTS + " ("
                + E_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + E_UUID + " TEXT NOT NULL UNIQUE, "
                + E_NAME + " TEXT NOT NULL, "
                + E_START_DATE + " TEXT NOT NULL, "
                + E_END_DATE + " TEXT NOT NULL, "
                + E_START_TIME + " TEXT NOT NULL, "
                + E_END_TIME + " TEXT, "
                + E_LOCATION + " TEXT NOT NULL, "
                + E_LATITUDE + " REAL, "
                + E_LONGITUDE + " REAL, "
                + E_ORGANISER + " TEXT NOT NULL, "
                + E_TYPE + " TEXT NOT NULL, "
                + E_DESCRIPTION + " TEXT, "
                + E_EMAIL + " TEXT, "
                + E_PHONE + " TEXT, "
                + E_UPDATED_AT + " INTEGER NOT NULL, "
                + E_DIRTY + " INTEGER NOT NULL DEFAULT 1, "
                + E_DELETED + " INTEGER NOT NULL DEFAULT 0, "
                + "CHECK (" + E_END_DATE + " >= " + E_START_DATE + "))");

        db.execSQL("CREATE TABLE " + T_SESSIONS + " ("
                + S_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + S_UUID + " TEXT NOT NULL UNIQUE, "
                + S_EVENT_ID + " INTEGER NOT NULL, "
                + S_NAME + " TEXT NOT NULL, "
                + S_DATE + " TEXT NOT NULL, "
                + S_START_TIME + " TEXT NOT NULL, "
                + S_END_TIME + " TEXT, "
                + S_TYPE + " TEXT NOT NULL, "
                + S_SPEAKER + " TEXT, "
                + S_ROOM + " TEXT, "
                + S_DESCRIPTION + " TEXT, "
                + "FOREIGN KEY (" + S_EVENT_ID + ") REFERENCES " + T_EVENTS + "(" + E_ID + ") "
                + "ON DELETE CASCADE)");

        db.execSQL("CREATE INDEX idx_events_start ON " + T_EVENTS + "(" + E_START_DATE + ")");
        db.execSQL("CREATE INDEX idx_sessions_event_date ON " + T_SESSIONS
                + "(" + S_EVENT_ID + ", " + S_DATE + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Coursework app: simply rebuild the schema on upgrade.
        db.execSQL("DROP TABLE IF EXISTS " + T_SESSIONS);
        db.execSQL("DROP TABLE IF EXISTS " + T_EVENTS);
        onCreate(db);
    }

    // =====================================================================
    // EVENTS
    // =====================================================================

    /** Inserts a new event and returns its new local id. */
    public long insertEvent(Event event) {
        long id = getWritableDatabase().insert(T_EVENTS, null, eventToValues(event));
        event.setId(id);
        onLocalDataChanged();
        return id;
    }

    /** Updates an existing event and marks it for sync. */
    public int updateEvent(Event event) {
        int rows = getWritableDatabase().update(T_EVENTS, eventToValues(event),
                E_ID + " = ?", new String[]{String.valueOf(event.getId())});
        onLocalDataChanged();
        return rows;
    }

    /**
     * Deletes an event and all its sessions.
     * Sessions are removed straight away; the event is soft-deleted so the
     * sync service knows to remove it from the cloud as well.
     */
    public void deleteEvent(long eventId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(T_SESSIONS, S_EVENT_ID + " = ?", new String[]{String.valueOf(eventId)});

            ContentValues values = new ContentValues();
            values.put(E_DELETED, 1);
            values.put(E_DIRTY, 1);
            values.put(E_UPDATED_AT, System.currentTimeMillis());
            db.update(T_EVENTS, values, E_ID + " = ?", new String[]{String.valueOf(eventId)});

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        onLocalDataChanged();
    }

    /** Returns one event (with its session count), or null if not found. */
    public Event getEvent(long eventId) {
        List<Event> result = queryEvents("e." + E_ID + " = ?",
                new String[]{String.valueOf(eventId)});
        return result.isEmpty() ? null : result.get(0);
    }

    /** All non-deleted events, ordered by start date, each with its session count. */
    public List<Event> getAllEvents() {
        return queryEvents(null, null);
    }

    /**
     * Feature a: true if another event already has the same name (case-insensitive)
     * starting on the same date. excludeId lets the edit screen ignore the event
     * being edited (pass -1 when adding).
     */
    public boolean isDuplicateEvent(String name, String startDateIso, long excludeId) {
        String sql = "SELECT 1 FROM " + T_EVENTS
                + " WHERE LOWER(TRIM(" + E_NAME + ")) = LOWER(TRIM(?))"
                + " AND " + E_START_DATE + " = ?"
                + " AND " + E_DELETED + " = 0"
                + " AND " + E_ID + " != ? LIMIT 1";
        try (Cursor c = getReadableDatabase().rawQuery(sql,
                new String[]{name, startDateIso, String.valueOf(excludeId)})) {
            return c.moveToFirst();
        }
    }

    /**
     * Edge case when editing an event's dates: how many existing sessions would
     * fall outside the new range? The UI blocks the change if this is > 0.
     */
    public int countSessionsOutsideRange(long eventId, String startIso, String endIso) {
        String sql = "SELECT COUNT(*) FROM " + T_SESSIONS
                + " WHERE " + S_EVENT_ID + " = ?"
                + " AND (" + S_DATE + " < ? OR " + S_DATE + " > ?)";
        try (Cursor c = getReadableDatabase().rawQuery(sql,
                new String[]{String.valueOf(eventId), startIso, endIso})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /**
     * Feature d: one search used by both the quick search and the advanced search.
     * Every criterion is optional (null/empty = ignored) and they are combined with AND.
     *
     * @param keyword   name STARTS WITH it (type the first few letters) or description CONTAINS it
     * @param dateIso   events taking place on this date: start <= date <= end (multi-day aware)
     * @param organiser organiser name contains it (case-insensitive)
     */
    public List<Event> findEvents(String keyword, String dateIso, String organiser) {
        StringBuilder where = new StringBuilder("1 = 1");
        List<String> args = new ArrayList<>();

        if (!TextUtils.isEmpty(keyword)) {
            where.append(" AND (e.").append(E_NAME).append(" LIKE ?")
                    .append(" OR e.").append(E_DESCRIPTION).append(" LIKE ?)");
            args.add(keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        if (!TextUtils.isEmpty(dateIso)) {
            where.append(" AND e.").append(E_START_DATE).append(" <= ?")
                    .append(" AND e.").append(E_END_DATE).append(" >= ?");
            args.add(dateIso);
            args.add(dateIso);
        }
        if (!TextUtils.isEmpty(organiser)) {
            where.append(" AND e.").append(E_ORGANISER).append(" LIKE ?");
            args.add("%" + organiser.trim() + "%");
        }
        return queryEvents(where.toString(), args.toArray(new String[0]));
    }

    /** Distinct organiser names, for the advanced search drop-down. */
    public List<String> getOrganisers() {
        List<String> organisers = new ArrayList<>();
        String sql = "SELECT DISTINCT " + E_ORGANISER + " FROM " + T_EVENTS
                + " WHERE " + E_DELETED + " = 0"
                + " ORDER BY " + E_ORGANISER + " COLLATE NOCASE";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                organisers.add(c.getString(0));
            }
        }
        return organisers;
    }

    /**
     * Shared query: events + COUNT of their sessions (feature c: total sessions per event).
     * Soft-deleted events are always excluded.
     */
    private List<Event> queryEvents(String extraWhere, String[] args) {
        String sql = "SELECT e.*, COUNT(s." + S_ID + ") AS " + SESSION_COUNT
                + " FROM " + T_EVENTS + " e"
                + " LEFT JOIN " + T_SESSIONS + " s ON s." + S_EVENT_ID + " = e." + E_ID
                + " WHERE e." + E_DELETED + " = 0"
                + (extraWhere != null ? " AND " + extraWhere : "")
                + " GROUP BY e." + E_ID
                + " ORDER BY " + EVENT_ORDER;

        List<Event> events = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                events.add(cursorToEvent(c));
            }
        }
        return events;
    }

    // =====================================================================
    // SESSIONS
    // =====================================================================

    /** Inserts a session and marks its parent event for sync. */
    public long insertSession(Session session) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = db.insert(T_SESSIONS, null, sessionToValues(session));
            session.setId(id);
            markEventDirty(db, session.getEventId());
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
            onLocalDataChanged();
        }
    }

    /** Updates a session and marks its parent event for sync. */
    public int updateSession(Session session) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.update(T_SESSIONS, sessionToValues(session),
                    S_ID + " = ?", new String[]{String.valueOf(session.getId())});
            markEventDirty(db, session.getEventId());
            db.setTransactionSuccessful();
            return rows;
        } finally {
            db.endTransaction();
            onLocalDataChanged();
        }
    }

    /** Deletes a session and marks its parent event for sync. */
    public void deleteSession(Session session) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(T_SESSIONS, S_ID + " = ?", new String[]{String.valueOf(session.getId())});
            markEventDirty(db, session.getEventId());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            onLocalDataChanged();
        }
    }

    public Session getSession(long sessionId) {
        try (Cursor c = getReadableDatabase().query(T_SESSIONS, null,
                S_ID + " = ?", new String[]{String.valueOf(sessionId)},
                null, null, null)) {
            return c.moveToFirst() ? cursorToSession(c) : null;
        }
    }

    /** Feature c: all sessions of one event, ordered by date then start time. */
    public List<Session> getSessionsForEvent(long eventId) {
        return querySessions(S_EVENT_ID + " = ?", new String[]{String.valueOf(eventId)});
    }

    /** Feature d: search sessions of one event by name or description. */
    public List<Session> searchSessions(long eventId, String keyword) {
        String kw = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        return querySessions(S_EVENT_ID + " = ? AND (" + S_NAME + " LIKE ? OR "
                        + S_DESCRIPTION + " LIKE ?)",
                new String[]{String.valueOf(eventId), kw, kw});
    }

    /**
     * Extra feature: sessions of the same event booked in the same room, on the
     * same day, whose times overlap the given session. Used to warn the admin.
     *
     * Two sessions overlap if they start at the same time, or if
     * existing.start < new.end AND new.start < existing.end.
     * A session without an end time is treated as ending when it starts.
     */
    public List<Session> findRoomClashes(Session s) {
        if (TextUtils.isEmpty(s.getRoom())) {
            return new ArrayList<>();
        }
        String newEnd = TextUtils.isEmpty(s.getEndTime()) ? s.getStartTime() : s.getEndTime();

        String where = S_EVENT_ID + " = ?"
                + " AND " + S_ID + " != ?"
                + " AND LOWER(TRIM(" + S_ROOM + ")) = LOWER(TRIM(?))"
                + " AND " + S_DATE + " = ?"
                + " AND (" + S_START_TIME + " = ?"
                + "      OR (" + S_START_TIME + " < ? AND ? < COALESCE(" + S_END_TIME + ", "
                + S_START_TIME + ")))";
        String[] args = {
                String.valueOf(s.getEventId()),
                String.valueOf(s.getId()),
                s.getRoom(),
                s.getSessionDate(),
                s.getStartTime(),
                newEnd,
                s.getStartTime()
        };
        return querySessions(where, args);
    }

    private List<Session> querySessions(String where, String[] args) {
        List<Session> sessions = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T_SESSIONS, null, where, args,
                null, null, SESSION_ORDER)) {
            while (c.moveToNext()) {
                sessions.add(cursorToSession(c));
            }
        }
        return sessions;
    }

    // =====================================================================
    // SYNC SUPPORT (feature e)
    // =====================================================================

    /** Events changed or deleted since the last successful upload. */
    public List<Event> getEventsToSync() {
        List<Event> events = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T_EVENTS, null,
                E_DIRTY + " = 1", null, null, null, null)) {
            while (c.moveToNext()) {
                events.add(cursorToEvent(c));
            }
        }
        return events;
    }

    /** Marks every live event dirty, used for "upload all events at once". */
    public void markAllEventsDirty() {
        ContentValues values = new ContentValues();
        values.put(E_DIRTY, 1);
        getWritableDatabase().update(T_EVENTS, values, E_DELETED + " = 0", null);
    }

    /**
     * Called after an event was uploaded successfully. Only clears the flag if the
     * event has not been edited again during the upload (same updatedAt); otherwise
     * it stays dirty so the newer version is sent on the next sync.
     */
    public void markEventSynced(long eventId, long uploadedVersion) {
        ContentValues values = new ContentValues();
        values.put(E_DIRTY, 0);
        getWritableDatabase().update(T_EVENTS, values,
                E_ID + " = ? AND " + E_UPDATED_AT + " = ?",
                new String[]{String.valueOf(eventId), String.valueOf(uploadedVersion)});
    }

    /** Every local change schedules a background sync (runs as soon as there is internet). */
    private void onLocalDataChanged() {
        SyncWorker.requestSync(appContext);
    }

    /** Called after a soft-deleted event was removed from the cloud. */
    public void purgeEvent(long eventId) {
        getWritableDatabase().delete(T_EVENTS, E_ID + " = ?",
                new String[]{String.valueOf(eventId)});
    }

    private void markEventDirty(SQLiteDatabase db, long eventId) {
        ContentValues values = new ContentValues();
        values.put(E_DIRTY, 1);
        values.put(E_UPDATED_AT, System.currentTimeMillis());
        db.update(T_EVENTS, values, E_ID + " = ?", new String[]{String.valueOf(eventId)});
    }

    // =====================================================================
    // MAPPING HELPERS
    // =====================================================================

    private ContentValues eventToValues(Event e) {
        // A one-day event has no explicit end date: store end = start so every
        // query can treat all events the same way.
        if (TextUtils.isEmpty(e.getEndDate())) {
            e.setEndDate(e.getStartDate());
        }
        ContentValues v = new ContentValues();
        v.put(E_UUID, e.getUuid());
        v.put(E_NAME, e.getName());
        v.put(E_START_DATE, e.getStartDate());
        v.put(E_END_DATE, e.getEndDate());
        v.put(E_START_TIME, e.getStartTime());
        v.put(E_END_TIME, e.getEndTime());
        v.put(E_LOCATION, e.getLocation());
        v.put(E_LATITUDE, e.getLatitude());
        v.put(E_LONGITUDE, e.getLongitude());
        v.put(E_ORGANISER, e.getOrganiser());
        v.put(E_TYPE, e.getType());
        v.put(E_DESCRIPTION, e.getDescription());
        v.put(E_EMAIL, e.getContactEmail());
        v.put(E_PHONE, e.getContactPhone());
        v.put(E_UPDATED_AT, System.currentTimeMillis());
        v.put(E_DIRTY, 1);   // every local change needs uploading
        v.put(E_DELETED, 0);
        return v;
    }

    private Event cursorToEvent(Cursor c) {
        Event e = new Event();
        e.setId(c.getLong(c.getColumnIndexOrThrow(E_ID)));
        e.setUuid(c.getString(c.getColumnIndexOrThrow(E_UUID)));
        e.setName(c.getString(c.getColumnIndexOrThrow(E_NAME)));
        e.setStartDate(c.getString(c.getColumnIndexOrThrow(E_START_DATE)));
        e.setEndDate(c.getString(c.getColumnIndexOrThrow(E_END_DATE)));
        e.setStartTime(c.getString(c.getColumnIndexOrThrow(E_START_TIME)));
        e.setEndTime(c.getString(c.getColumnIndexOrThrow(E_END_TIME)));
        e.setLocation(c.getString(c.getColumnIndexOrThrow(E_LOCATION)));
        e.setLatitude(getNullableDouble(c, E_LATITUDE));
        e.setLongitude(getNullableDouble(c, E_LONGITUDE));
        e.setOrganiser(c.getString(c.getColumnIndexOrThrow(E_ORGANISER)));
        e.setType(c.getString(c.getColumnIndexOrThrow(E_TYPE)));
        e.setDescription(c.getString(c.getColumnIndexOrThrow(E_DESCRIPTION)));
        e.setContactEmail(c.getString(c.getColumnIndexOrThrow(E_EMAIL)));
        e.setContactPhone(c.getString(c.getColumnIndexOrThrow(E_PHONE)));
        e.setUpdatedAt(c.getLong(c.getColumnIndexOrThrow(E_UPDATED_AT)));
        e.setDirty(c.getInt(c.getColumnIndexOrThrow(E_DIRTY)) == 1);
        e.setDeleted(c.getInt(c.getColumnIndexOrThrow(E_DELETED)) == 1);

        int countIdx = c.getColumnIndex(SESSION_COUNT); // only present in joined queries
        if (countIdx != -1) {
            e.setSessionCount(c.getInt(countIdx));
        }
        return e;
    }

    /** REAL columns can be NULL (no coordinates); getDouble() would turn that into 0.0. */
    private static Double getNullableDouble(Cursor c, String column) {
        int idx = c.getColumnIndexOrThrow(column);
        return c.isNull(idx) ? null : c.getDouble(idx);
    }

    private ContentValues sessionToValues(Session s) {
        ContentValues v = new ContentValues();
        v.put(S_UUID, s.getUuid());
        v.put(S_EVENT_ID, s.getEventId());
        v.put(S_NAME, s.getName());
        v.put(S_DATE, s.getSessionDate());
        v.put(S_START_TIME, s.getStartTime());
        v.put(S_END_TIME, s.getEndTime());
        v.put(S_TYPE, s.getType());
        v.put(S_SPEAKER, s.getSpeaker());
        v.put(S_ROOM, s.getRoom());
        v.put(S_DESCRIPTION, s.getDescription());
        return v;
    }

    private Session cursorToSession(Cursor c) {
        Session s = new Session();
        s.setId(c.getLong(c.getColumnIndexOrThrow(S_ID)));
        s.setUuid(c.getString(c.getColumnIndexOrThrow(S_UUID)));
        s.setEventId(c.getLong(c.getColumnIndexOrThrow(S_EVENT_ID)));
        s.setName(c.getString(c.getColumnIndexOrThrow(S_NAME)));
        s.setSessionDate(c.getString(c.getColumnIndexOrThrow(S_DATE)));
        s.setStartTime(c.getString(c.getColumnIndexOrThrow(S_START_TIME)));
        s.setEndTime(c.getString(c.getColumnIndexOrThrow(S_END_TIME)));
        s.setType(c.getString(c.getColumnIndexOrThrow(S_TYPE)));
        s.setSpeaker(c.getString(c.getColumnIndexOrThrow(S_SPEAKER)));
        s.setRoom(c.getString(c.getColumnIndexOrThrow(S_ROOM)));
        s.setDescription(c.getString(c.getColumnIndexOrThrow(S_DESCRIPTION)));
        return s;
    }
}