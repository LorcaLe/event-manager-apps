using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EventManagerUser.Models;
using EventManagerUser.Services;
using EventManagerUser.Views;

namespace EventManagerUser.ViewModels;

/// <summary>"Favourites" tab (feature h): quick access to the events the user starred.</summary>
public partial class FavouritesViewModel : ObservableObject
{
    private readonly EventService _eventService;
    private readonly FavouritesService _favourites;

    public ObservableCollection<EventItem> Favourites { get; } = new();

    [ObservableProperty] private bool isRefreshing;

    public FavouritesViewModel(EventService eventService, FavouritesService favourites)
    {
        _eventService = eventService;
        _favourites = favourites;
    }

    /// <summary>Uses the events already loaded by the Events tab; loads them if needed.</summary>
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
            }

            Favourites.Clear();
            foreach (var e in events.Where(e => _favourites.IsFavourite(e.Uuid)))
            {
                e.IsFavourite = true;
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

    /// <summary>Un-starring removes the event from this list straight away.</summary>
    [RelayCommand]
    private void ToggleFavourite(EventItem item)
    {
        item.IsFavourite = _favourites.Toggle(item.Uuid);
        if (!item.IsFavourite)
        {
            Favourites.Remove(item);
        }
    }
}
