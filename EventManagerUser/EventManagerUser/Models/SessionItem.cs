using System.Globalization;
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

    /// <summary>False when the parent event is postponed / cancelled: never shown as live.</summary>
    [JsonIgnore] public bool EventIsActive { get; set; } = true;

    public long SessionId { get; set; }
    public string Name { get; set; } = "";
    public string Date { get; set; } = "";          // yyyy-MM-dd
    public string StartTime { get; set; } = "";     // HH:mm
    public string? EndTime { get; set; }
    public string Type { get; set; } = "";
    public string? Speaker { get; set; }
    public string? Room { get; set; }
    public string? Description { get; set; }

    // ----- Time -----

    public DateTime Day =>
        DateTime.TryParseExact(Date, "yyyy-MM-dd", CultureInfo.InvariantCulture,
            DateTimeStyles.None, out var d) ? d : DateTime.MinValue;

    public DateTime StartAt => Day + EventItem.ParseTime(StartTime);

    /// <summary>No end time = 60 minutes (same rule as the admin app).</summary>
    public DateTime EndAt
    {
        get
        {
            if (string.IsNullOrEmpty(EndTime)) return StartAt.AddHours(1);
            var end = Day + EventItem.ParseTime(EndTime);
            return end > StartAt ? end : StartAt.AddHours(1);
        }
    }

    public bool IsLiveNow => EventIsActive && DateTime.Now >= StartAt && DateTime.Now < EndAt;
    public bool IsPast => DateTime.Now >= EndAt;
    public double RowOpacity => IsPast && !IsLiveNow ? 0.55 : 1.0;

    // ----- Display helpers used by the XAML -----

    public string TimeText => string.IsNullOrEmpty(EndTime) ? StartTime : $"{StartTime} – {EndTime}";
    public bool HasSpeaker => !string.IsNullOrWhiteSpace(Speaker);
    public bool HasRoom => !string.IsNullOrWhiteSpace(Room);
    public bool HasDescription => !string.IsNullOrWhiteSpace(Description);
    public string SpeakerText => $"Speaker: {Speaker}";
    public string RoomText => $"Room: {Room}";

    /// <summary>"Keynote · Sarah Lee · Main hall".</summary>
    public string MetaText
    {
        get
        {
            var parts = new List<string> { Type };
            if (HasSpeaker) parts.Add(Speaker!);
            if (HasRoom) parts.Add(Room!);
            return string.Join(" · ", parts.Where(p => !string.IsNullOrWhiteSpace(p)));
        }
    }
}
