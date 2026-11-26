using System.Text.Json.Serialization;

namespace EventManagerUser.Models;

/// <summary>
/// A session of an event, exactly as the Android admin app uploads it to Firebase:
/// /events/{eventUuid}/sessions/{sessionUuid}
/// </summary>
public class SessionItem
{
    /// <summary>The Firebase key; not inside the JSON object, set after loading.</summary>
    [JsonIgnore] public string Uuid { get; set; } = "";

    public long SessionId { get; set; }
    public string Name { get; set; } = "";
    public string Date { get; set; } = "";          // yyyy-MM-dd
    public string StartTime { get; set; } = "";     // HH:mm
    public string? EndTime { get; set; }
    public string Type { get; set; } = "";
    public string? Speaker { get; set; }
    public string? Room { get; set; }
    public string? Description { get; set; }

    // ----- Display helpers used by the XAML -----

    public string TimeText => string.IsNullOrEmpty(EndTime) ? StartTime : $"{StartTime} – {EndTime}";
    public bool HasSpeaker => !string.IsNullOrWhiteSpace(Speaker);
    public bool HasRoom => !string.IsNullOrWhiteSpace(Room);
    public bool HasDescription => !string.IsNullOrWhiteSpace(Description);
    public string SpeakerText => $"Speaker: {Speaker}";
    public string RoomText => $"Room: {Room}";
}
