using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EventManagerUser.Models;
using EventManagerUser.Services;
using EventManagerUser.Views;

namespace EventManagerUser.ViewModels;

/// <summary>
/// "Saved" tab (feature h): quick access to the events the user saved.
/// Saved events that were postponed or cancelled show a warning banner.
/// </summary>
public partial class FavouritesViewModel : ObservableObject
{
    private readonly EventService _eventService;
    private readonly EventPreferences _preferences;

    public ObservableCollection<EventItem> Favourites { get; } = new();

    [ObservableProperty] private bool isRefreshing;

    public FavouritesViewModel(EventService eventService, EventPreferences preferences)
    {
        _eventService = eventService;
        _preferences = preferences;
    }

    /// <summary>Uses the events already loaded by the Discover tab; loads them if needed.</summary>
    [RelayCommand]
    public async Task LoadAsync()
    {
        IsRefreshing = true;
        try
        {
            var events = _eventService.Current;
            if (events.Count == 0)
            {
                events = await _eventService.GetEventsAsync();
                _preferences.Apply(events);
            }

            Favourites.Clear();
            // Live first, then upcoming / changed by date, then past events
            var saved = events.Where(e => e.IsFavourite && !e.IsDraft && !e.IsNotInterested)
                .OrderBy(e => e.IsEnded ? 2 : e.IsLive ? 0 : 1)
                .ThenBy(e => e.StartAt);
            foreach (var e in saved)
            {
                e.RefreshTime();
                Favourites.Add(e);
            }
        }
        catch (Exception)
        {
            // Nothing saved yet and no connection: the empty view explains what to do
        }
        finally
        {
            IsRefreshing = false;
        }
    }

    [RelayCommand]
    private Task OpenEvent(EventItem item) =>
        Shell.Current.GoToAsync(nameof(EventDetailPage),
            new Dictionary<string, object> { ["Event"] = item });

    /// <summary>Un-saving removes the event from this list straight away.</summary>
    [RelayCommand]
    private void ToggleFavourite(EventItem item)
    {
        _preferences.ToggleSaved(item);
        if (!item.IsFavourite)
        {
            Favourites.Remove(item);
        }
    }
}
