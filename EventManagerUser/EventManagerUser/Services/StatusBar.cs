namespace EventManagerUser.Services;

/// <summary>
/// Status bar look per page (Android). Light pages (Discover, Saved, Not interested)
/// need dark icons, otherwise the clock / battery are white on white. The indigo
/// detail page needs light icons.
/// </summary>
public static class StatusBar
{
    /// <param name="background">Status bar colour (ignored on Android 15+, which is always edge-to-edge).</param>
    /// <param name="lightBackground">True if what is behind the status bar is light.</param>
    public static void Apply(Color background, bool lightBackground)
    {
#if ANDROID
        var activity = Platform.CurrentActivity;
        var window = activity?.Window;
        if (window == null) return;

        if (!OperatingSystem.IsAndroidVersionAtLeast(35))
        {
#pragma warning disable CA1422 // deprecated on Android 15+, still needed below it
            window.SetStatusBarColor(Microsoft.Maui.Platform.ColorExtensions.ToPlatform(background));
#pragma warning restore CA1422
        }
        var controller = AndroidX.Core.View.WindowCompat.GetInsetsController(window, window.DecorView);
        if (controller != null) controller.AppearanceLightStatusBars = lightBackground;
#endif
    }

    /// <summary>Page background colour for the current theme (light or dark).</summary>
    public static void ApplyForPage()
    {
        bool dark = Application.Current?.RequestedTheme == AppTheme.Dark;
        Apply(dark ? Color.FromArgb("#141418") : Colors.White, !dark);
    }
}
