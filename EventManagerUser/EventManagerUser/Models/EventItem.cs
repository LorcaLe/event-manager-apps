using System.Globalization;
using System.Text.Json.Serialization;
using CommunityToolkit.Mvvm.ComponentModel;

namespace EventManagerUser.Models;

/// <summary>
/// An event as stored in Firebase by the Android admin app (/events/{uuid}).
/// Property names match the JSON keys (camelCase, matched case-insensitively).
/// IsFavourite is local to this phone and never comes from the cloud.
/// </summary>
public partial class EventItem : ObservableObject
{
    [JsonIgnore] public string Uuid { get; set; } = "";

    public string Name { get; set; } = "";
    public string StartDate { get; set; } = "";     // yyyy-MM-dd
    public string EndDate { get; set; } = "";
    public string StartTime { get; set; } = "";     // HH:mm
    public string? EndTime { get; set; }
    public string Location { get; set; } = "";
    public double? Latitude { get; set; }
    public double? Longitude { get; set; }
    public string Organiser { get; set; } = "";
    public string Type { get; set; } = "";
    public string? Description { get; set; }
    public string? ContactEmail { get; set; }
    public string? ContactPhone { get; set; }

    /// <summary>Sessions keyed by their UUID, as in the JSON.</summary>
    [JsonPropertyName("sessions")]
    public Dictionary<string, SessionItem>? SessionMap { get; set; }

    /// <summary>Set from FavouritesService; the star in the UI updates automatically.</summary>
    [ObservableProperty]
    [property: JsonIgnore]
    [NotifyPropertyChangedFor(nameof(FavouriteIcon))]
    private bool isFavourite;

    /// <summary>Called after loading: copies the Firebase keys into the objects.</summary>
    public void Prepare(string uuid)
    {
        Uuid = uuid;
        if (SessionMap == null) return;
        foreach (var (key, session) in SessionMap)
        {
            session.Uuid = key;
        }
    }

    // ----- Data helpers -----
    [JsonIgnore]
    public List<SessionItem> Sessions =>
        SessionMap?.Values.OrderBy(s => s.Date).ThenBy(s => s.StartTime).ToList() ?? new();

    public DateTime StartDay => ParseIso(StartDate);
    public DateTime EndDay => string.IsNullOrEmpty(EndDate) ? StartDay : ParseIso(EndDate);
    public bool IsMultiDay => EndDay > StartDay;
    public bool HasCoordinates => Latitude.HasValue && Longitude.HasValue;

    /// <summary>True if the event is taking place on that day (multi-day aware).</summary>
    public bool IsOn(DateTime day) => day.Date >= StartDay && day.Date <= EndDay;

    // ----- Display helpers used by the XAML -----

    public string DateText => IsMultiDay
        ? $"{StartDay:dd/MM/yyyy} – {EndDay:dd/MM/yyyy} · {(EndDay - StartDay).Days + 1} days"
        : StartDay.ToString("dd/MM/yyyy");

    public string TimeText => string.IsNullOrEmpty(EndTime) ? StartTime : $"{StartTime} – {EndTime}";
    public string DateTimeText => $"{DateText} at {TimeText}";
    public string LocationText => $"Location: {Location}";
    public string OrganiserText => $"Organiser: {Organiser}";

    public string SessionCountText
    {
        get
        {
            int n = SessionMap?.Count ?? 0;
            return n == 1 ? "1 session" : $"{n} sessions";
        }
    }

    public string FavouriteIcon => IsFavourite ? "★" : "☆";
    public bool HasDescription => !string.IsNullOrWhiteSpace(Description);
    public bool HasEmail => !string.IsNullOrWhiteSpace(ContactEmail);
    public bool HasPhone => !string.IsNullOrWhiteSpace(ContactPhone);

    private static DateTime ParseIso(string iso) =>
        DateTime.TryParseExact(iso, "yyyy-MM-dd", CultureInfo.InvariantCulture,
            DateTimeStyles.None, out var d) ? d : DateTime.MinValue;
}
