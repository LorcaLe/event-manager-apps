using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EventManagerUser.Models;
using EventManagerUser.Services;

namespace EventManagerUser.ViewModels;

/// <summary>
/// Event details for attendees: information, favourite star, schedule grouped
/// by day, and shortcuts to open the map, email or call the organiser.
/// </summary>
public partial class EventDetailViewModel : ObservableObject, IQueryAttributable
{
    private readonly FavouritesService _favourites;

    [ObservableProperty] private EventItem? item;

    public ObservableCollection<SessionGroup> SessionGroups { get; } = new();
    public bool HasSessions => SessionGroups.Count > 0;

    public EventDetailViewModel(FavouritesService favourites)
    {
        _favourites = favourites;
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
            string title = e.IsMultiDay ? $"Day {dayNumber} · {date:dd/MM/yyyy}" : $"{date:dd/MM/yyyy}";
            SessionGroups.Add(new SessionGroup(title, day));
        }
        OnPropertyChanged(nameof(HasSessions));
    }

    [RelayCommand]
    private void ToggleFavourite()
    {
        if (Item != null)
        {
            Item.IsFavourite = _favourites.Toggle(Item.Uuid);
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
