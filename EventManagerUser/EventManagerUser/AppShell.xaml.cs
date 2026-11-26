using EventManagerUser.Views;

namespace EventManagerUser;

public partial class AppShell : Shell
{
    public AppShell()
    {
        InitializeComponent();
        // Detail page is opened with Shell.Current.GoToAsync(nameof(EventDetailPage), ...)
        Routing.RegisterRoute(nameof(EventDetailPage), typeof(EventDetailPage));
    }
}
