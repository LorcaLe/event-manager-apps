using System.Globalization;
using System.Text.Json.Serialization;
using CommunityToolkit.Mvvm.ComponentModel;

namespace EventManagerUser.Models;

/// <summary>
/// An event as stored in Firebase by the Android admin app (/events/{uuid}).
/// Property names match the JSON keys (camelCase, matched case-insensitively).
/// IsFavourite / IsNotInterested are local to this phone and never come from the cloud.
///
/// Status model (same as the admin app):
///  - manual status set by the admin: DRAFT, PUBLISHED, POSTPONED, CANCELLED
///    (missing in older data = PUBLISHED; DRAFT events are never shown to users);
///  - time-based status: UPCOMING, ONGOING, ENDED, from the start/end date + time;
///  - displayed status = POSTPONED / CANCELLED when set, otherwise the time-based one.
/// </summary>
public partial class EventItem : ObservableObject
{
    public const string Draft = "DRAFT";
    public const string Published = "PUBLISHED";
    public const string Postponed = "POSTPONED";
    public const string Cancelled = "CANCELLED";
    public const string Upcoming = "UPCOMING";
    public const string Ongoing = "ONGOING";
    public const string Ended = "ENDED";

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
    public string? Status { get; set; }
    public string? StatusNote { get; set; }

    /// <summary>Sessions keyed by their UUID, as in the JSON.</summary>
    [JsonPropertyName("sessions")]
    public Dictionary<string, SessionItem>? SessionMap { get; set; }

    /// <summary>Set from FavouritesService; the star in the UI updates automatically.</summary>
    [ObservableProperty]
    [property: JsonIgnore]
    [NotifyPropertyChangedFor(nameof(FavouriteIcon), nameof(SaveText), nameof(ShowSavedWarning))]
    private bool isFavourite;

    /// <summary>Set from NotInterestedService: the user hid this event.</summary>
    [ObservableProperty]
    [property: JsonIgnore]
    [NotifyPropertyChangedFor(nameof(NotInterestedText))]
    private bool isNotInterested;

    /// <summary>Called after loading: copies the Firebase keys into the objects.</summary>
    public void Prepare(string uuid)
    {
        Uuid = uuid;
        if (SessionMap == null) return;
        foreach (var (key, session) in SessionMap)
        {
            session.Uuid = key;
            session.EventIsActive = ManualStatus == Published;
        }
    }

    /// <summary>Re-evaluates every time-based property (countdowns, live state).</summary>
    public void RefreshTime() => OnPropertyChanged(string.Empty);

    // =====================================================================
    // Data helpers
    // =====================================================================

    [JsonIgnore]
    public List<SessionItem> Sessions =>
        SessionMap?.Values.OrderBy(s => s.Date).ThenBy(s => s.StartTime).ToList() ?? new();

    public DateTime StartDay => ParseIso(StartDate);
    public DateTime EndDay => string.IsNullOrEmpty(EndDate) ? StartDay : ParseIso(EndDate);
    public bool IsMultiDay => EndDay > StartDay;
    public bool HasCoordinates => Latitude.HasValue && Longitude.HasValue;

    /// <summary>True if the event is taking place on that day (multi-day aware).</summary>
    public bool IsOn(DateTime day) => day.Date >= StartDay && day.Date <= EndDay;

    public DateTime StartAt => StartDay + ParseTime(StartTime);

    /// <summary>End date + end time, or the end of the last day when there is no end time.</summary>
    public DateTime EndAt
    {
        get
        {
            if (string.IsNullOrEmpty(EndTime)) return EndDay.AddDays(1);
            var end = EndDay + ParseTime(EndTime);
            return end > StartAt ? end : StartAt.AddHours(1);
        }
    }

    // =====================================================================
    // Status
    // =====================================================================

    public string ManualStatus =>
        string.IsNullOrWhiteSpace(Status) ? Published : Status.Trim().ToUpperInvariant();

    public bool IsDraft => ManualStatus == Draft;

    public string TimeStatus
    {
        get
        {
            var now = DateTime.Now;
            if (now < StartAt) return Upcoming;
            return now >= EndAt ? Ended : Ongoing;
        }
    }

    public string DisplayStatus => ManualStatus switch
    {
        Draft => Draft,
        Postponed => Postponed,
        Cancelled => Cancelled,
        _ => TimeStatus
    };

    public bool IsLive => DisplayStatus == Ongoing;
    public bool IsUpcoming => DisplayStatus == Upcoming;
    public bool IsEnded => DisplayStatus == Ended;
    public bool IsPostponed => DisplayStatus == Postponed;
    public bool IsCancelled => DisplayStatus == Cancelled;
    /// <summary>Postponed or cancelled: shown with a coloured banner and the reason.</summary>
    public bool IsChanged => IsPostponed || IsCancelled;

    public string StatusText => DisplayStatus switch
    {
        Ongoing => "LIVE NOW",
        Ended => "ENDED",
        Postponed => "POSTPONED",
        Cancelled => "CANCELLED",
        Draft => "DRAFT",
        _ => "UPCOMING"
    };

    public bool HasStatusNote => IsChanged && !string.IsNullOrWhiteSpace(StatusNote);

    public string ChangedTitle => IsCancelled ? "This event has been cancelled" : "This event has been postponed";

    // Colours (same palette as the admin app; light / dark aware)
    public Color StatusFg => StatusPalette.Fg(DisplayStatus);
    public Color StatusBg => StatusPalette.Bg(DisplayStatus);
    public Color StatusAccent => StatusPalette.Accent(DisplayStatus);

    /// <summary>Calendar block on cards: indigo normally, the status colour when changed.</summary>
    public Color DateBlockBg => IsChanged || IsEnded ? StatusBg : StatusPalette.CalendarBg;
    public Color DateBlockFg => IsChanged || IsEnded ? StatusFg : StatusPalette.CalendarFg;
    public Color CardBg => IsChanged ? StatusPalette.ChangedCardBg(DisplayStatus) : StatusPalette.CardBg;
    public Color CardStroke => IsChanged ? StatusPalette.ChangedCardStroke(DisplayStatus) : StatusPalette.CardStroke;
    public Brush CardStrokeBrush => new SolidColorBrush(CardStroke);

    /// <summary>Cards show a badge for changed / ended events, a countdown otherwise.</summary>
    public bool ShowBadge => IsChanged || IsEnded;
    public bool ShowCountdown => !ShowBadge;

    public string SavedWarningText => IsCancelled
        ? "You saved this event – it has been cancelled."
        : "You saved this event – it has been postponed.";

    /// <summary>"Meetup · 19:00 · Student Hub" or "Ended · Greenwich Park" (Not interested list).</summary>
    public string HiddenMetaText => IsEnded || IsChanged
        ? $"{CountdownText} · {Location}"
        : $"{Type} · {StartTime} · {Location}";

    // =====================================================================
    // Countdown / progress
    // =====================================================================

    public int TotalDays => (EndDay - StartDay).Days + 1;

    public int CurrentDay
    {
        get
        {
            int day = (DateTime.Today - StartDay).Days + 1;
            return Math.Clamp(day, 1, Math.Max(1, TotalDays));
        }
    }

    /// <summary>Fraction of the event that has elapsed, 0..1.</summary>
    public double Progress
    {
        get
        {
            var now = DateTime.Now;
            if (now <= StartAt) return 0;
            if (now >= EndAt) return 1;
            return (now - StartAt).TotalMinutes / (EndAt - StartAt).TotalMinutes;
        }
    }

    public string DayOfText => $"Day {CurrentDay} of {TotalDays}";

    /// <summary>"Starts in 2 h", "In 3 days", "Day 2 of 3", "Ends in 40 min", "Ended"...</summary>
    public string CountdownText
    {
        get
        {
            switch (DisplayStatus)
            {
                case Upcoming:
                    var left = StartAt - DateTime.Now;
                    if (left.TotalMinutes < 60) return $"Starts in {Math.Max(1, (int)left.TotalMinutes)} min";
                    int days = (StartAt.Date - DateTime.Today).Days;
                    if (days == 0) return $"Starts in {(int)left.TotalHours} h";
                    if (days == 1) return "Tomorrow";
                    return $"In {days} days";
                case Ongoing:
                    if (TotalDays > 1) return DayOfText;
                    var remaining = EndAt - DateTime.Now;
                    return remaining.TotalMinutes < 60
                        ? $"Ends in {Math.Max(1, (int)remaining.TotalMinutes)} min"
                        : $"Ends in {(int)remaining.TotalHours} h";
                case Ended: return "Ended";
                case Postponed: return "Postponed";
                case Cancelled: return "Cancelled";
                default: return "";
            }
        }
    }

    // Big countdown on the detail screen (upcoming events)
    private TimeSpan TimeLeft => IsUpcoming ? StartAt - DateTime.Now : TimeSpan.Zero;
    public string CountdownDays => ((int)TimeLeft.TotalDays).ToString();
    public string CountdownHours => TimeLeft.Hours.ToString("00");
    public string CountdownMinutes => TimeLeft.Minutes.ToString("00");

    /// <summary>The session running right now, if any (for the "Happening now" card).</summary>
    public SessionItem? CurrentSession => Sessions.FirstOrDefault(s => s.IsLiveNow);
    public bool HasCurrentSession => IsLive && CurrentSession != null;
    public string CurrentSessionText
    {
        get
        {
            var s = CurrentSession;
            if (s == null) return "";
            return s.HasRoom ? $"Now: {s.Name} · {s.Room}" : $"Now: {s.Name}";
        }
    }

    // =====================================================================
    // Display helpers used by the XAML
    // =====================================================================

    public string DayOfWeekShort => StartDay.ToString("ddd", CultureInfo.InvariantCulture).ToUpperInvariant();
    public string DayNumber => StartDay.ToString("dd", CultureInfo.InvariantCulture);
    public string MonthShort => StartDay.ToString("MMM", CultureInfo.InvariantCulture).ToUpperInvariant();

    public string DateText => IsMultiDay
        ? $"{StartDay:dd/MM/yyyy} – {EndDay:dd/MM/yyyy} · {TotalDays} days"
        : StartDay.ToString("dd/MM/yyyy");

    /// <summary>"Fri 28 – Sun 30 Nov 2026" / "Fri 28 Nov 2026".</summary>
    public string LongDateText
    {
        get
        {
            var c = CultureInfo.InvariantCulture;
            if (!IsMultiDay) return StartDay.ToString("ddd d MMM yyyy", c);
            string start = StartDay.Month == EndDay.Month
                ? StartDay.ToString("ddd d", c)
                : StartDay.ToString("ddd d MMM", c);
            return $"{start} – {EndDay.ToString("ddd d MMM yyyy", c)}";
        }
    }

    public string TimeText => string.IsNullOrEmpty(EndTime) ? StartTime : $"{StartTime} – {EndTime}";
    public string TimeAndDaysText => IsMultiDay ? $"{TimeText} · {TotalDays} days" : TimeText;
    public string DateTimeText => $"{DateText} at {TimeText}";
    public string TimeLocationText => $"{StartTime} · {Location}";
    public string LiveUntilText => $"{Location} · until {EndAt.ToString("ddd HH:mm", CultureInfo.InvariantCulture)}";
    public string OrganiserText => $"by {Organiser}";

    public int SessionCount => SessionMap?.Count ?? 0;
    public string SessionCountText => SessionCount == 1 ? "1 session" : $"{SessionCount} sessions";

    public string FavouriteIcon => IsFavourite ? "★" : "☆";
    public string SaveText => IsFavourite ? "Saved" : "Save event";
    public string NotInterestedText => IsNotInterested ? "Restore" : "Not interested";

    /// <summary>On the Saved tab: warn when a saved event was postponed or cancelled.</summary>
    public bool ShowSavedWarning => IsFavourite && IsChanged;

    public bool HasDescription => !string.IsNullOrWhiteSpace(Description);
    public bool HasEmail => !string.IsNullOrWhiteSpace(ContactEmail);
    public bool HasPhone => !string.IsNullOrWhiteSpace(ContactPhone);

    private static DateTime ParseIso(string iso) =>
        DateTime.TryParseExact(iso, "yyyy-MM-dd", CultureInfo.InvariantCulture,
            DateTimeStyles.None, out var d) ? d : DateTime.MinValue;

    internal static TimeSpan ParseTime(string? hhmm) =>
        TimeSpan.TryParseExact(hhmm ?? "", @"hh\:mm", CultureInfo.InvariantCulture, out var t)
            ? t : TimeSpan.Zero;
}
