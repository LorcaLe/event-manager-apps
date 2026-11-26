package com.greenwich.eventmanager.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * A session (talk, workshop, panel...) that belongs to one Event.
 * Session ID is the auto-generated SQLite primary key.
 *
 * sessionDate must fall within the parent event's [startDate, endDate].
 */
public class Session implements Serializable {

    public static final String TYPE_OTHERS = "Others";

    /** Session types required by the specification. "Others" lets the user type their own. */
    public static final String[] TYPES = {
            "Workshop", "Presentation", "Panel Discussion", "Networking", "Keynote", TYPE_OTHERS
    };

    private long id;              // Session ID (auto generated)
    private String uuid;          // key in the cloud database
    private long eventId;         // foreign key -> events._id

    // Required fields
    private String name;
    private String sessionDate;   // YYYY-MM-DD
    private String startTime;     // HH:mm
    private String type;

    // Optional fields
    private String endTime;       // HH:mm, enables room-clash detection
    private String speaker;
    private String room;
    private String description;

    public Session() {
        this.uuid = UUID.randomUUID().toString();
    }

    // ---------- Getters & setters ----------

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public long getEventId() { return eventId; }
    public void setEventId(long eventId) { this.eventId = eventId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSessionDate() { return sessionDate; }
    public void setSessionDate(String sessionDate) { this.sessionDate = sessionDate; }

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }

    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }

    public String getRoom() { return room; }
    public void setRoom(String room) { this.room = room; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}