using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EventManagerUser.Models;
using EventManagerUser.Services;
using EventManagerUser.Views;

namespace EventManagerUser.ViewModels;

/// <summary>A pill tab on the Discover screen (All / Live now / Upcoming / Past / Changed).</summary>
public partial class DiscoverTab : ObservableObject
{
    public string Key { get; }
    public string Title { get; }
    public bool ShowLiveDot { get; }

    [ObservableProperty] private bool isSelected;

    public DiscoverTab(string key, string title, bool showLiveDot = false)
    {
        Key = key;
        Title = title;
        ShowLiveDot = showLiveDot;
    }
}

/// <summary>
/// "Discover" tab (feature g), optimised for browsing:
///  - search by name / place / organiser, optional date filter;
///  - tabs by status; a "Happening now" section and a "Coming up" list;
///  - order: live → upcoming (soonest first, postponed/cancelled in date order) → ended;
///  - DRAFT events and events marked "Not interested" are never shown;
///  - swipe left on a card to Save it or mark it Not interested (with Undo).
/// </summary>
public partial class EventsViewModel : ObservableObject
{
    public const string TabAll = "all";
    public const string TabLive = "live";
    public const string TabUpcoming = "upcoming";
    public const string TabPast = "past";
    public const string TabChanged = "changed";

    private readonly EventService _eventService;
    private readonly EventPreferences _preferences;
    private IReadOnlyList<EventItem> _all = new List<EventItem>();
    private bool _loadedOnce;
    private CancellationTokenSource? _undoTimer;

    public ObservableCollection<DiscoverTab> Tabs { get; } = new()
    {
        new DiscoverTab(TabAll, "All") { IsSelected = true },
        new DiscoverTab(TabLive, "Live now", showLiveDot: true),
        new DiscoverTab(TabUpcoming, "Upcoming"),
        new DiscoverTab(TabPast, "Past"),
        new DiscoverTab(TabChanged, "Changed"),
    };

    /// <summary>"Happening now" section.</summary>
    public ObservableCollection<EventItem> LiveEvents { get; } = new();

    /// <summary>Main list under the section title.</summary>
    public ObservableCollection<EventItem> Events { get; } = new();

    [ObservableProperty] private string searchText = "";
    [ObservableProperty] private bool useDateFilter;
    [ObservableProperty] private DateTime filterDate = DateTime.Today;
    [ObservableProperty] private bool showDateFilter;
    [ObservableProperty] private bool isRefreshing;
    [ObservableProperty] private string selectedTab = TabAll;
    [ObservableProperty] private string listTitle = "Coming up";
    [ObservableProperty] private bool hasLive;
    [ObservableProperty] private bool hasList;
    [ObservableProperty] private bool isEmpty;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasStatus))]
    private string? statusMessage;

    public bool HasStatus => !string.IsNullOrEmpty(StatusMessage);

    // Undo banner after hiding an event
    [ObservableProperty] private bool showUndo;
    [ObservableProperty] private string undoText = "";
    private EventItem? _lastHidden;

    public EventsViewModel(EventService eventService, EventPreferences preferences)
    {
        _eventService = eventService;
        _preferences = preferences;
    }

    // Re-filter whenever a search criterion changes
    partial void OnSearchTextChanged(string value) => ApplyFilter();
    partial void OnUseDateFilterChanged(bool value) => ApplyFilter();
    partial void OnFilterDateChanged(DateTime value) => ApplyFilter();
    partial void OnSelectedTabChanged(string value)
    {
        foreach (var t in Tabs) t.IsSelected = t.Key == value;
        ApplyFilter();
    }

    /// <summary>Loads once when the page first opens; later loads come from pull-to-refresh.</summary>
    public async Task LoadIfNeededAsync()
    {
        if (_loadedOnce)
        {
            Refresh();       // e.g. an event was restored on the Not interested tab
            return;
        }
        _loadedOnce = true;
        await LoadAsync();
    }

    /// <summary>Called every minute and when the page reappears: statuses / countdowns move on.</summary>
    public void Refresh()
    {
        foreach (var e in _all) e.RefreshTime();
        ApplyFilter();
    }

    [RelayCommand]
    private async Task LoadAsync()
    {
        IsRefreshing = true;
        try
        {
            _all = await _eventService.GetEventsAsync();
            _preferences.Apply(_all);
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
        IEnumerable<EventItem> visible = _all.Where(e => !e.IsDraft && !e.IsNotInterested);

        if (query.Length > 0)
        {
            visible = visible.Where(e =>
                e.Name.Contains(query, StringComparison.OrdinalIgnoreCase)
                || e.Location.Contains(query, StringComparison.OrdinalIgnoreCase)
                || e.Organiser.Contains(query, StringComparison.OrdinalIgnoreCase)
                || e.Sessions.Any(s => s.Speaker?.Contains(query, StringComparison.OrdinalIgnoreCase) == true));
        }
        if (UseDateFilter)
        {
            visible = visible.Where(e => e.IsOn(FilterDate));
        }

        var list = visible.ToList();
        var live = list.Where(e => e.IsLive).OrderBy(e => e.EndAt).ToList();
        // Upcoming + postponed/cancelled in date order (soonest first), then ended (latest first)
        var comingUp = list.Where(e => e.IsUpcoming || (e.IsChanged && e.EndAt > DateTime.Now))
            .OrderBy(e => e.StartAt).ToList();
        var past = list.Where(e => e.IsEnded || (e.IsChanged && e.EndAt <= DateTime.Now))
            .OrderByDescending(e => e.StartAt).ToList();

        List<EventItem> section;
        List<EventItem> main;
        switch (SelectedTab)
        {
            case TabLive:
                section = live;
                main = new();
                ListTitle = "";
                break;
            case TabUpcoming:
                section = new();
                main = comingUp.Where(e => e.IsUpcoming).ToList();
                ListTitle = "Upcoming";
                break;
            case TabPast:
                section = new();
                main = past;
                ListTitle = "Past events";
                break;
            case TabChanged:
                section = new();
                main = list.Where(e => e.IsChanged).OrderBy(e => e.StartAt).ToList();
                ListTitle = "Postponed or cancelled";
                break;
            default:
                section = live;
                main = comingUp.Concat(past).ToList();
                ListTitle = "Coming up";
                break;
        }

        Replace(LiveEvents, section);
        Replace(Events, main);
        HasLive = LiveEvents.Count > 0;
        HasList = Events.Count > 0;
        IsEmpty = !HasLive && !HasList;
    }

    private static void Replace(ObservableCollection<EventItem> target, List<EventItem> items)
    {
        target.Clear();
        foreach (var e in items) target.Add(e);
    }

    // =====================================================================
    // Commands
    // =====================================================================

    [RelayCommand]
    private void SelectTab(string key) => SelectedTab = key;

    [RelayCommand]
    private void ToggleDateFilter()
    {
        ShowDateFilter = !ShowDateFilter;
        if (!ShowDateFilter) UseDateFilter = false;
    }

    [RelayCommand]
    private void ClearFilters()
    {
        SearchText = "";
        UseDateFilter = false;
        ShowDateFilter = false;
        SelectedTab = TabAll;
    }

    [RelayCommand]
    private Task OpenEvent(EventItem item) =>
        Shell.Current.GoToAsync(nameof(EventDetailPage),
            new Dictionary<string, object> { ["Event"] = item });

    [RelayCommand]
    private void ToggleFavourite(EventItem item) => _preferences.ToggleSaved(item);

    /// <summary>Swipe action: hide the event and offer Undo for a few seconds.</summary>
    [RelayCommand]
    private async Task NotInterested(EventItem item)
    {
        _preferences.SetNotInterested(item, true);
        ApplyFilter();

        _lastHidden = item;
        UndoText = $"Hidden “{item.Name}”";
        ShowUndo = true;

        _undoTimer?.Cancel();
        _undoTimer = new CancellationTokenSource();
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(5), _undoTimer.Token);
            ShowUndo = false;
        }
        catch (TaskCanceledException)
        {
            // a newer hide or Undo replaced this banner
        }
    }

    [RelayCommand]
    private void UndoHide()
    {
        _undoTimer?.Cancel();
        ShowUndo = false;
        if (_lastHidden == null) return;
        _preferences.SetNotInterested(_lastHidden, false);
        _lastHidden = null;
        ApplyFilter();
    }
}
