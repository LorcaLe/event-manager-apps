using System.Text.Json;

namespace EventManagerUser.Services;

/// <summary>
/// Feature h: favourite events, stored on this phone only (Preferences), so no
/// account is needed. Events are remembered by their UUID, which never changes
/// even when the admin edits the event.
/// </summary>
public class FavouritesService
{
    private const string PreferenceKey = "favourite_event_ids";
    private readonly HashSet<string> _ids;

    public FavouritesService()
    {
        string json = Preferences.Default.Get(PreferenceKey, "[]");
        _ids = JsonSerializer.Deserialize<HashSet<string>>(json) ?? new HashSet<string>();
    }

    public bool IsFavourite(string uuid) => _ids.Contains(uuid);

    /// <summary>Adds or removes the event; returns true if it is now a favourite.</summary>
    public bool Toggle(string uuid)
    {
        bool removed = _ids.Remove(uuid);
        if (!removed)
        {
            _ids.Add(uuid);
        }
        Preferences.Default.Set(PreferenceKey, JsonSerializer.Serialize(_ids));
        return !removed;
    }
}
