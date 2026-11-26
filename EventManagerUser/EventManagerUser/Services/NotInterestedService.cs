using System.Text.Json;

namespace EventManagerUser.Services;

/// <summary>
/// "Not interested": events the user chose to hide from Discover and Saved.
/// Stored on this phone only (Preferences), like favourites, by event UUID.
/// </summary>
public class NotInterestedService
{
    private const string PreferenceKey = "not_interested_event_ids";
    private readonly HashSet<string> _ids;

    public NotInterestedService()
    {
        string json = Preferences.Default.Get(PreferenceKey, "[]");
        _ids = JsonSerializer.Deserialize<HashSet<string>>(json) ?? new HashSet<string>();
    }

    public bool IsHidden(string uuid) => _ids.Contains(uuid);

    public IReadOnlyCollection<string> All => _ids;

    public void Set(string uuid, bool hidden)
    {
        bool changed = hidden ? _ids.Add(uuid) : _ids.Remove(uuid);
        if (changed) Save();
    }

    public void Clear()
    {
        _ids.Clear();
        Save();
    }

    private void Save() =>
        Preferences.Default.Set(PreferenceKey, JsonSerializer.Serialize(_ids));
}
