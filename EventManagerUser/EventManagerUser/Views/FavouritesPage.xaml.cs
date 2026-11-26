using EventManagerUser.Models;
using EventManagerUser.ViewModels;

namespace EventManagerUser.Views;

public partial class FavouritesPage : ContentPage
{
    private readonly FavouritesViewModel _viewModel;

    public FavouritesPage(FavouritesViewModel viewModel)
    {
        InitializeComponent();
        BindingContext = _viewModel = viewModel;
    }

    /// <summary>Refresh every time the tab is shown, so newly saved events appear.</summary>
    protected override async void OnAppearing()
    {
        base.OnAppearing();
        Services.StatusBar.ApplyForPage();
        await _viewModel.LoadAsync();
    }

    private void OnCardTapped(object? sender, EventItem item) =>
        _viewModel.OpenEventCommand.Execute(item);

    private void OnCardStar(object? sender, EventItem item) =>
        _viewModel.ToggleFavouriteCommand.Execute(item);
}
