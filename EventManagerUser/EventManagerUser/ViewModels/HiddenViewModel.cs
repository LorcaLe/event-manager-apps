using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EventManagerUser.Models;
using EventManagerUser.Services;
using EventManagerUser.Views;

namespace EventManagerUser.ViewModels;

/// <summary>
/// "Not interested" tab: events the user hid from Discover and Saved.
/// Each can be restored, or all at once.
/// </summary>
public partial class HiddenViewModel : ObservableObject
{
    private readonly EventService _eventService;
    private readonly EventPreferences _preferences;

    public ObservableCollection<EventItem> Hidden { get; } = new();

    [ObservableProperty] private bool isRefreshing;
    [ObservableProperty] private bool hasHidden;

    public HiddenViewModel(EventService eventService, EventPreferences preferences)
    {
        _eventService = eventService;
        _preferences = preferences;
    }

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

            Hidden.Clear();
            foreach (var e in events.Where(e => e.IsNotInterested && !e.IsDraft).OrderBy(e => e.StartAt))
            {
                e.RefreshTime();
                Hidden.Add(e);
            }
        }
        catch (Exception)
        {
            // Nothing saved yet and no connection: the empty view explains the feature
        }
        finally
        {
            HasHidden = Hidden.Count > 0;
            IsRefreshing = false;
        }
    }

    [RelayCommand]
    private void Restore(EventItem item)
    {
        _preferences.SetNotInterested(item, false);
        Hidden.Remove(item);
        HasHidden = Hidden.Count > 0;
    }

    [RelayCommand]
    private void RestoreAll()
    {
        foreach (var e in Hidden.ToList())
        {
            _preferences.SetNotInterested(e, false);
        }
        Hidden.Clear();
        HasHidden = false;
    }

    [RelayCommand]
    private Task OpenEvent(EventItem item) =>
        Shell.Current.GoToAsync(nameof(EventDetailPage),
            new Dictionary<string, object> { ["Event"] = item });
}
