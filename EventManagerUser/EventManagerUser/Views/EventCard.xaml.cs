using EventManagerUser.Models;

namespace EventManagerUser.Views;

/// <summary>
/// Reusable event card. The page decides what a tap / star does, so the same card
/// works on Discover and Saved (each with its own view model).
/// </summary>
public partial class EventCard : ContentView
{
    public event EventHandler<EventItem>? Tapped;
    public event EventHandler<EventItem>? StarTapped;

    public EventCard()
    {
        InitializeComponent();
    }

    private void OnCardTapped(object? sender, TappedEventArgs e)
    {
        if (BindingContext is EventItem item) Tapped?.Invoke(this, item);
    }

    private void OnStarClicked(object? sender, EventArgs e)
    {
        if (BindingContext is EventItem item) StarTapped?.Invoke(this, item);
    }
}
