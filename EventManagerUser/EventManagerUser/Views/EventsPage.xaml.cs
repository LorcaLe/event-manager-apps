using EventManagerUser.Models;
using EventManagerUser.ViewModels;

namespace EventManagerUser.Views;

public partial class EventsPage : ContentPage
{
    private readonly EventsViewModel _viewModel;
    private bool _visible;

    public EventsPage(EventsViewModel viewModel)
    {
        InitializeComponent();
        BindingContext = _viewModel = viewModel;
    }

    protected override async void OnAppearing()
    {
        base.OnAppearing();
        _visible = true;
        await _viewModel.LoadIfNeededAsync();

        // Countdowns and live / ended states move on while the page is open
        Dispatcher.StartTimer(TimeSpan.FromMinutes(1), () =>
        {
            if (_visible) _viewModel.Refresh();
            return _visible;
        });
    }

    protected override void OnDisappearing()
    {
        base.OnDisappearing();
        _visible = false;
    }

    // Cards and swipe items forward to the view model

    private void OnCardTapped(object? sender, EventItem item) =>
        _viewModel.OpenEventCommand.Execute(item);

    private void OnCardStar(object? sender, EventItem item) =>
        _viewModel.ToggleFavouriteCommand.Execute(item);

    private void OnSwipeSave(object? sender, EventArgs e)
    {
        if ((sender as BindableObject)?.BindingContext is EventItem item)
            _viewModel.ToggleFavouriteCommand.Execute(item);
    }

    private void OnSwipeNotInterested(object? sender, EventArgs e)
    {
        if ((sender as BindableObject)?.BindingContext is EventItem item)
            _viewModel.NotInterestedCommand.Execute(item);
    }
}
