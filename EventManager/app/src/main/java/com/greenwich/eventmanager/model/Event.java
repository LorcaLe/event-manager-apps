package com.greenwich.eventmanager.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * An event created by the admin. Can span one or several days.
 * Stored locally in SQLite and later synchronised to Firebase.
 *
 * - id   : local SQLite primary key (only meaningful on this device)
 * - uuid : global identifier, used as the key in the cloud database so the
 *          same event is updated (not duplicated) on every sync.
 *
 * Dates are held in ISO format (YYYY-MM-DD); the UI converts to/from
 * DD/MM/YYYY with DateUtils. Times are HH:mm (24-hour).
 */
public class Event implements Serializable {

    public static final String TYPE_OTHER = "Other";

    /** Values shown in the event type drop-down. "Other" lets the user type their own. */
    public static final String[] TYPES = {
            "Conference", "Workshop", "Seminar", "Meetup", "Exhibition", TYPE_OTHER
    };

    // Manual status set by the admin. The time-based status (upcoming / ongoing /
    // ended) is computed from the dates, see util.EventStatus.
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_POSTPONED = "POSTPONED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** Manual statuses in the order shown in the form and the change-status sheet. */
    public static final String[] STATUSES = {
            STATUS_DRAFT, STATUS_PUBLISHED, STATUS_POSTPONED, STATUS_CANCELLED
    };

    private long id;
    private String uuid;

    // Required fields
    private String name;
    private String startDate;     // YYYY-MM-DD
    private String endDate;       // YYYY-MM-DD, equals startDate for a one-day event
    private String startTime;     // HH:mm
    private String location;
    private String organiser;
    private String type;          // one of TYPES, or a custom type when "Other" was chosen

    // Optional fields
    private String endTime;       // HH:mm, time the event finishes on endDate
    private String description;
    private String contactEmail;
    private String contactPhone;
    private Double latitude;      // set when the location is picked from a search/GPS
    private Double longitude;

    // Status
    private String status = STATUS_PUBLISHED;
    private String statusNote;    // reason, required when postponed or cancelled

    // Sync bookkeeping (feature e)
    private long updatedAt;       // epoch millis of the last local change
    private boolean dirty;        // true = changed locally, not yet uploaded
    private boolean deleted;      // true = deleted locally, must be removed from cloud

    // Computed by queries, not stored as a column
    private int sessionCount;

    public Event() {
        this.uuid = UUID.randomUUID().toString();
    }

    /** True if the event lasts more than one day. */
    public boolean isMultiDay() {
        return endDate != null && !endDate.equals(startDate);
    }

    /** True when a reason must be given for the status (postponed / cancelled). */
    public static boolean statusNeedsReason(String status) {
        return STATUS_POSTPONED.equals(status) || STATUS_CANCELLED.equals(status);
    }

    /** True if the location has map coordinates. */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    // ---------- Getters & setters ----------

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }

    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getOrganiser() { return organiser; }
    public void setOrganiser(String organiser) { this.organiser = organiser; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getStatus() { return status == null ? STATUS_PUBLISHED : status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusNote() { return statusNote; }
    public void setStatusNote(String statusNote) { this.statusNote = statusNote; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public int getSessionCount() { return sessionCount; }
    public void setSessionCount(int sessionCount) { this.sessionCount = sessionCount; }
}