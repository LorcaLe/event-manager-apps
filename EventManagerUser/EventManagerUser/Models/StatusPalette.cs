namespace EventManagerUser.Models;

/// <summary>
/// Status colours shared with the admin app (text / badge fill / accent),
/// with a dark-mode variant of each. Picked from the app's current theme.
/// </summary>
public static class StatusPalette
{
    private static bool Dark => Application.Current?.RequestedTheme == AppTheme.Dark;

    private static Color C(string light, string dark) => Color.FromArgb(Dark ? dark : light);

    public static Color Fg(string status) => status switch
    {
        EventItem.Draft => C("#3C4043", "#D6D9DE"),
        EventItem.Ongoing => C("#14532D", "#BDE8C8"),
        EventItem.Ended => C("#5A5D6B", "#C4C6CF"),
        EventItem.Postponed => C("#7C2D12", "#FBD3A8"),
        EventItem.Cancelled => C("#7F1D1D", "#F8C4BF"),
        _ => C("#123E77", "#C4DAF8")
    };

    public static Color Bg(string status) => status switch
    {
        EventItem.Draft => C("#ECEEF1", "#34373C"),
        EventItem.Ongoing => C("#DFF3E4", "#1B3D26"),
        EventItem.Ended => C("#F0F0F0", "#33343A"),
        EventItem.Postponed => C("#FDEBD3", "#4A2A12"),
        EventItem.Cancelled => C("#FBE4E2", "#4C1D1D"),
        _ => C("#E3EDFB", "#1D3557")
    };

    public static Color Accent(string status) => status switch
    {
        EventItem.Draft => C("#5F6368", "#A0A4AA"),
        EventItem.Ongoing => C("#1B7F3B", "#6CCB88"),
        EventItem.Ended => C("#8A8D99", "#8A8D99"),
        EventItem.Postponed => C("#C2410C", "#F59E5B"),
        EventItem.Cancelled => C("#B42318", "#F0857A"),
        _ => C("#1A5FB4", "#7FB0F0")
    };

    public static Color CalendarBg => C("#E8EAFB", "#283BA3");
    public static Color CalendarFg => C("#232F7A", "#FFFFFF");
    public static Color CardBg => C("#FFFFFF", "#1E1F25");
    public static Color CardStroke => C("#ECEDF2", "#34353D");

    public static Color ChangedCardBg(string status) => status == EventItem.Cancelled
        ? C("#FFF8F7", "#2A1A1A")
        : C("#FFFBF5", "#2A2015");

    public static Color ChangedCardStroke(string status) => status == EventItem.Cancelled
        ? C("#F3C5C0", "#5A2A2A")
        : C("#F5D3B0", "#5A3A1E");
}
