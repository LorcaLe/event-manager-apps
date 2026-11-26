using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EventManagerUser.Models;
using EventManagerUser.Services;
using EventManagerUser.Views;

namespace EventManagerUser.ViewModels;

/// <summary>
/// "Events" tab (feature g): list of events from the cloud, searchable by name
/// and by date. Filtering happens in memory, so results update as you type.
/// </summary>
public partial class EventsViewModel : ObservableObject
{
    private readonly EventService _eventService;
    private readonly FavouritesService _favourites;
    private IReadOnlyList<EventItem> _all = new List<EventItem>();
    private bool _loadedOnce;

    public ObservableCollection<EventItem> Events { get; } = new();

    [ObservableProperty] private string searchText = "";
    [ObservableProperty] private bool useDateFilter;
    [ObservableProperty] private DateTime filterDate = DateTime.Today;
    [ObservableProperty] private bool isRefreshing;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasStatus))]
    private string? statusMessage;

    public bool HasStatus => !string.IsNullOrEmpty(StatusMessage);

    public EventsViewModel(EventService eventService, FavouritesService favourites)
    {
        _eventService = eventService;
        _favourites = favourites;
    }

    // Re-filter whenever a search criterion changes
    partial void OnSearchTextChanged(string value) => ApplyFilter();
    partial void OnUseDateFilterChanged(bool value) => ApplyFilter();
    partial void OnFilterDateChanged(DateTime value) => ApplyFilter();

    /// <summary>Loads once when the page first opens; later loads come from pull-to-refresh.</summary>
    public async Task LoadIfNeededAsync()
    {
        if (_loadedOnce) return;
        _loadedOnce = true;
        await LoadAsync();
    }

    [RelayCommand]
    private async Task LoadAsync()
    {
        IsRefreshing = true;
        try
        {
            _all = await _eventService.GetEventsAsync();
            foreach (var e in _all)
            {
                e.IsFavourite = _favourites.IsFavourite(e.Uuid);
            }
            StatusMessage = _eventService.LastLoadFromCache
                ? "You are offline – showing the events saved on this phone."
                : null;
        }
        catch (Exception ex)
        {
            // Show the reason (e.g. no connection, permission denied) and log the details
            System.Diagnostics.Debug.WriteLine($"[EventsViewModel] Load failed: {ex}");
            StatusMessage = $"Couldn't load events ({ex.Message}). Pull down to retry.";
        }
        finally
        {
            IsRefreshing = false;
        }
        ApplyFilter();
    }

    private void ApplyFilter()
    {
        string query = SearchText?.Trim() ?? "";
        IEnumerable<EventItem> result = _all;

        if (query.Length > 0)
        {
            result = result.Where(e => e.Name.Contains(query, StringComparison.OrdinalIgnoreCase));
        }
        if (UseDateFilter)
        {
            result = result.Where(e => e.IsOn(FilterDate));
        }

        Events.Clear();
        foreach (var e in result)
        {
            Events.Add(e);
        }
    }

    [RelayCommand]
    private void ClearFilters()
    {
        SearchText = "";
        UseDateFilter = false;
    }

    [RelayCommand]
    private Task OpenEvent(EventItem item) =>
        Shell.Current.GoToAsync(nameof(EventDetailPage),
            new Dictionary<string, object> { ["Event"] = item });

    [RelayCommand]
    private void ToggleFavourite(EventItem item) =>
        item.IsFavourite = _favourites.Toggle(item.Uuid);
}
