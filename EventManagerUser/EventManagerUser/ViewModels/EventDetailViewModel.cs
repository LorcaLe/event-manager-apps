using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EventManagerUser.Models;
using EventManagerUser.Services;

namespace EventManagerUser.ViewModels;

/// <summary>
/// Event details for attendees: hero with status + countdown, banner when postponed /
/// cancelled, information, schedule grouped by day (LIVE NOW badge), and the
/// "Not interested" / "Save event" actions.
/// </summary>
public partial class EventDetailViewModel : ObservableObject, IQueryAttributable
{
    private readonly EventPreferences _preferences;

    [ObservableProperty] private EventItem? item;

    public ObservableCollection<SessionGroup> SessionGroups { get; } = new();
    public bool HasSessions => SessionGroups.Count > 0;

    public EventDetailViewModel(EventPreferences preferences)
    {
        _preferences = preferences;
    }

    /// <summary>Called every minute by the page so the countdown and LIVE badges move on.</summary>
    public void Refresh()
    {
        if (Item == null) return;
        Item.RefreshTime();
        BuildSessionGroups(Item);
    }

    /// <summary>Receives the event passed by GoToAsync(..., { ["Event"] = item }).</summary>
    public void ApplyQueryAttributes(IDictionary<string, object> query)
    {
        if (query.TryGetValue("Event", out var value) && value is EventItem e)
        {
            Item = e;
            BuildSessionGroups(e);
        }
    }

    private void BuildSessionGroups(EventItem e)
    {
        SessionGroups.Clear();
        foreach (var day in e.Sessions.GroupBy(s => s.Date))
        {
            DateTime date = DateTime.ParseExact(day.Key, "yyyy-MM-dd",
                System.Globalization.CultureInfo.InvariantCulture);
            int dayNumber = (date - e.StartDay).Days + 1;
            string dayText = date.ToString("ddd d MMM", System.Globalization.CultureInfo.InvariantCulture);
            string title = e.IsMultiDay ? $"Day {dayNumber} · {dayText}" : dayText;
            SessionGroups.Add(new SessionGroup(title, day));
        }
        OnPropertyChanged(nameof(HasSessions));
    }

    [RelayCommand]
    private void ToggleFavourite()
    {
        if (Item != null)
        {
            _preferences.ToggleSaved(Item);
        }
    }

    /// <summary>Hide the event (it disappears from Discover / Saved) or restore it.</summary>
    [RelayCommand]
    private async Task ToggleNotInterested()
    {
        if (Item == null) return;
        bool hide = !Item.IsNotInterested;
        _preferences.SetNotInterested(Item, hide);
        if (hide)
        {
            await Shell.Current.GoToAsync("..");
        }
    }

    /// <summary>Exact pin when the admin picked the place on the map, otherwise an address search.</summary>
    [RelayCommand]
    private async Task OpenMap()
    {
        if (Item == null) return;
        try
        {
            if (Item.HasCoordinates)
            {
                await Map.Default.OpenAsync(Item.Latitude!.Value, Item.Longitude!.Value,
                    new MapLaunchOptions { Name = Item.Name });
            }
            else
            {
                await Launcher.Default.OpenAsync(
                    "https://www.google.com/maps/search/?api=1&query=" + Uri.EscapeDataString(Item.Location));
            }
        }
        catch (Exception)
        {
            await Shell.Current.DisplayAlert("Maps", "No maps app is available on this device.", "OK");
        }
    }

    [RelayCommand]
    private async Task EmailOrganiser()
    {
        if (Item?.HasEmail != true || !Email.Default.IsComposeSupported) return;
        await Email.Default.ComposeAsync(new EmailMessage
        {
            Subject = $"About: {Item.Name}",
            To = new List<string> { Item.ContactEmail! }
        });
    }

    [RelayCommand]
    private void CallOrganiser()
    {
        if (Item?.HasPhone == true && PhoneDialer.Default.IsSupported)
        {
            PhoneDialer.Default.Open(Item.ContactPhone!);
        }
    }
}
