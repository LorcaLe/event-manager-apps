using System.Text.Json;
using EventManagerUser.Models;

namespace EventManagerUser.Services;

/// <summary>
/// Reads events from the SAME Firebase Realtime Database the Android admin app
/// uploads to (feature g). Uses Firebase's REST API: GET {database}/events.json
/// returns plain JSON, so no Firebase SDK is needed.
///
/// The last successful response is saved on the phone, so the app still shows
/// events (read-only) when there is no internet connection.
/// </summary>
public class EventService
{
    // Realtime Database created for the admin app (Firebase console -> Realtime Database)
    private const string DatabaseUrl =
        "https://eventmanager-1d187-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true   // "startDate" in JSON -> StartDate in C#
    };

    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };
    private readonly string _cachePath = Path.Combine(FileSystem.AppDataDirectory, "events_cache.json");

    /// <summary>The events loaded most recently. Shared by all pages, so a favourite
    /// set on one page shows on the others too.</summary>
    public IReadOnlyList<EventItem> Current { get; private set; } = new List<EventItem>();

    /// <summary>True if the last load came from the saved copy (offline).</summary>
    public bool LastLoadFromCache { get; private set; }

    public async Task<IReadOnlyList<EventItem>> GetEventsAsync()
    {
        string? json = null;
        LastLoadFromCache = false;

        if (Connectivity.Current.NetworkAccess == NetworkAccess.Internet)
        {
            try
            {
                json = await _http.GetStringAsync(DatabaseUrl + "events.json");
                await File.WriteAllTextAsync(_cachePath, json);   // keep a copy for offline use
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
            {
                json = null;                                      // fall back to the saved copy
            }
        }

        if (json == null)
        {
            if (!File.Exists(_cachePath))
            {
                throw new InvalidOperationException("No internet connection and no saved events.");
            }
            json = await File.ReadAllTextAsync(_cachePath);
            LastLoadFromCache = true;
        }

        Current = Parse(json);
        return Current;
    }

    /// <summary>Firebase returns { "uuid": { ...event... }, ... } or "null" when empty.</summary>
    private static List<EventItem> Parse(string json)
    {
        if (string.IsNullOrWhiteSpace(json) || json.Trim() == "null")
        {
            return new List<EventItem>();
        }

        var byUuid = JsonSerializer.Deserialize<Dictionary<string, EventItem>>(json, JsonOptions)
                     ?? new Dictionary<string, EventItem>();
        foreach (var (uuid, item) in byUuid)
        {
            item.Prepare(uuid);
        }
        return byUuid.Values
            .OrderBy(e => e.StartDate)
            .ThenBy(e => e.StartTime)
            .ToList();
    }
}
