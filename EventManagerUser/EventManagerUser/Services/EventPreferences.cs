using EventManagerUser.Models;

namespace EventManagerUser.Services;

/// <summary>
/// Keeps "Saved" and "Not interested" mutually exclusive, and keeps the flags on the
/// shared EventItem objects in sync with what is stored on the phone.
/// </summary>
public class EventPreferences
{
    private readonly FavouritesService _favourites;
    private readonly NotInterestedService _notInterested;

    public EventPreferences(FavouritesService favourites, NotInterestedService notInterested)
    {
        _favourites = favourites;
        _notInterested = notInterested;
    }

    /// <summary>Copies the stored flags onto freshly loaded events.</summary>
    public void Apply(IEnumerable<EventItem> events)
    {
        foreach (var e in events)
        {
            e.IsFavourite = _favourites.IsFavourite(e.Uuid);
            e.IsNotInterested = _notInterested.IsHidden(e.Uuid);
        }
    }

    /// <summary>Save / unsave. Saving an event also restores it if it was hidden.</summary>
    public void ToggleSaved(EventItem e)
    {
        e.IsFavourite = _favourites.Toggle(e.Uuid);
        if (e.IsFavourite && e.IsNotInterested)
        {
            SetNotInterested(e, false);
        }
    }

    /// <summary>Hide / restore. Hiding an event also removes it from Saved.</summary>
    public void SetNotInterested(EventItem e, bool hidden)
    {
        _notInterested.Set(e.Uuid, hidden);
        e.IsNotInterested = hidden;
        if (hidden && e.IsFavourite)
        {
            _favourites.Set(e.Uuid, false);
            e.IsFavourite = false;
        }
    }
}
