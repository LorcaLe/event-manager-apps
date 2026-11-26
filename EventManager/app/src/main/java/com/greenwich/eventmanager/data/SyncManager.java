package com.greenwich.eventmanager.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.greenwich.eventmanager.model.Event;
import com.greenwich.eventmanager.model.Session;
import com.greenwich.eventmanager.util.NetworkUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Uploads local changes to the Firebase Realtime Database (feature e).
 *
 * Cloud structure (one node per event, keyed by its UUID, sessions nested inside):
 *   /events/{eventUuid}/name, startDate, ..., sessions/{sessionUuid}/name, ...
 *
 * How synchronisation works:
 *  1. Every local change marks the event "dirty" (DatabaseHelper).
 *  2. Sync collects all dirty events and sends them in ONE multi-path update:
 *       - changed event -> its full data (with its current sessions) replaces the cloud copy
 *       - deleted event -> null, which removes it from the cloud
 *     The update is atomic: either every change is saved, or none is.
 *  3. Only after the server confirms, events are marked clean / deleted rows are purged.
 *     If an event was edited again while uploading, its updatedAt no longer matches,
 *     so it stays dirty and goes up on the next sync (no change is ever lost).
 */
public final class SyncManager {

    public static final String EVENTS_NODE = "events";
    private static final long TIMEOUT_SECONDS = 30;

    /** Only one sync at a time (manual "Sync now" and the background worker share it). */
    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SyncManager() { }

    /** What a sync did. */
    public static final class Result {
        public final int uploaded;
        public final int deleted;

        Result(int uploaded, int deleted) {
            this.uploaded = uploaded;
            this.deleted = deleted;
        }

        public boolean nothingToDo() {
            return uploaded == 0 && deleted == 0;
        }
    }

    /** Why a sync failed. */
    public static final class SyncException extends Exception {
        public final boolean noNetwork;

        SyncException(boolean noNetwork, Throwable cause) {
            super(cause);
            this.noNetwork = noNetwork;
        }
    }

    public interface Callback {
        void onSuccess(Result result);
        void onError(SyncException error);
    }

    /** For the UI: runs on a background thread, reports back on the main thread. */
    public static void syncAsync(Context ctx, boolean uploadAll, Callback callback) {
        Context app = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                Result result = syncBlocking(app, uploadAll);
                MAIN.post(() -> callback.onSuccess(result));
            } catch (SyncException e) {
                MAIN.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Does the actual work. Blocks until the server answers, so it must NOT be
     * called on the main thread (used by syncAsync and by SyncWorker).
     *
     * @param uploadAll true = re-upload every event, false = only what changed
     */
    public static Result syncBlocking(Context ctx, boolean uploadAll) throws SyncException {
        // Check the network first, as the specification requires
        if (!NetworkUtils.isOnline(ctx)) {
            throw new SyncException(true, null);
        }

        synchronized (LOCK) {
            DatabaseHelper db = DatabaseHelper.getInstance(ctx);
            if (uploadAll) {
                db.markAllEventsDirty();
            }
            List<Event> pending = db.getEventsToSync();
            if (pending.isEmpty()) {
                return new Result(0, 0);
            }

            // Build one multi-path update: key = event UUID, value = data or null (delete)
            Map<String, Object> updates = new HashMap<>();
            for (Event e : pending) {
                updates.put(e.getUuid(),
                        e.isDeleted() ? null : toCloud(e, db.getSessionsForEvent(e.getId())));
            }

            DatabaseReference eventsRef = FirebaseDatabase.getInstance().getReference(EVENTS_NODE);
            try {
                Tasks.await(eventsRef.updateChildren(updates), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new SyncException(false, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SyncException(false, e);
            }

            // Server confirmed: update local flags
            int uploaded = 0;
            int deleted = 0;
            for (Event e : pending) {
                if (e.isDeleted()) {
                    db.purgeEvent(e.getId());
                    deleted++;
                } else {
                    db.markEventSynced(e.getId(), e.getUpdatedAt());
                    uploaded++;
                }
            }
            return new Result(uploaded, deleted);
        }
    }

    /** Event (+ its sessions) as a plain map for Firebase. Null values are simply left out. */
    private static Map<String, Object> toCloud(Event e, List<Session> sessions) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", e.getName());
        m.put("startDate", e.getStartDate());
        m.put("endDate", e.getEndDate());
        m.put("startTime", e.getStartTime());
        m.put("endTime", e.getEndTime());
        m.put("location", e.getLocation());
        m.put("latitude", e.getLatitude());
        m.put("longitude", e.getLongitude());
        m.put("organiser", e.getOrganiser());
        m.put("type", e.getType());
        m.put("description", e.getDescription());
        m.put("contactEmail", e.getContactEmail());
        m.put("contactPhone", e.getContactPhone());
        m.put("status", e.getStatus());
        m.put("statusNote", Event.statusNeedsReason(e.getStatus()) ? e.getStatusNote() : null);
        m.put("updatedAt", e.getUpdatedAt());
        m.put("syncedAt", ServerValue.TIMESTAMP);      // filled in by the server
        m.put("sessionCount", sessions.size());

        Map<String, Object> sessionMap = new HashMap<>();
        for (Session s : sessions) {
            Map<String, Object> sm = new HashMap<>();
            sm.put("sessionId", s.getId());
            sm.put("name", s.getName());
            sm.put("date", s.getSessionDate());
            sm.put("startTime", s.getStartTime());
            sm.put("endTime", s.getEndTime());
            sm.put("type", s.getType());
            sm.put("speaker", s.getSpeaker());
            sm.put("room", s.getRoom());
            sm.put("description", s.getDescription());
            sessionMap.put(s.getUuid(), sm);
        }
        m.put("sessions", sessionMap);
        return m;
    }
}