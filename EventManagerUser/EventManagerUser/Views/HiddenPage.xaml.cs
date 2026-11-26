using EventManagerUser.ViewModels;

namespace EventManagerUser.Views;

public partial class HiddenPage : ContentPage
{
    private readonly HiddenViewModel _viewModel;

    public HiddenPage(HiddenViewModel viewModel)
    {
        InitializeComponent();
        BindingContext = _viewModel = viewModel;
    }

    /// <summary>Refresh every time the tab is shown, so newly hidden events appear.</summary>
    protected override async void OnAppearing()
    {
        base.OnAppearing();
        await _viewModel.LoadAsync();
    }
}
