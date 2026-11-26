using EventManagerUser.ViewModels;

namespace EventManagerUser.Views;

public partial class EventDetailPage : ContentPage
{
    private readonly EventDetailViewModel _viewModel;
    private bool _visible;

    public EventDetailPage(EventDetailViewModel viewModel)
    {
        InitializeComponent();
        BindingContext = _viewModel = viewModel;
    }

    /// <summary>Keep the countdown and the LIVE NOW badges current while the page is open.</summary>
    protected override void OnAppearing()
    {
        base.OnAppearing();
        Services.StatusBar.Apply(Color.FromArgb("#2E3A8C"), lightBackground: false);
        _visible = true;
        Dispatcher.StartTimer(TimeSpan.FromSeconds(30), () =>
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
}
