using EventManagerUser.Services;
using EventManagerUser.ViewModels;
using EventManagerUser.Views;
using Microsoft.Extensions.Logging;

namespace EventManagerUser;

public static class MauiProgram
{
    public static MauiApp CreateMauiApp()
    {
        var builder = MauiApp.CreateBuilder();
        builder
            .UseMauiApp<App>()
            .ConfigureFonts(fonts =>
            {
                fonts.AddFont("OpenSans-Regular.ttf", "OpenSansRegular");
                fonts.AddFont("OpenSans-Semibold.ttf", "OpenSansSemibold");
            });

        // Dependency injection: pages get their view model, view models get services.
        // Singletons are shared by every page (one list of events, one favourites store).
        builder.Services.AddSingleton<EventService>();
        builder.Services.AddSingleton<FavouritesService>();
        builder.Services.AddSingleton<NotInterestedService>();
        builder.Services.AddSingleton<EventPreferences>();

        builder.Services.AddSingleton<EventsViewModel>();
        builder.Services.AddSingleton<EventsPage>();
        builder.Services.AddSingleton<FavouritesViewModel>();
        builder.Services.AddSingleton<FavouritesPage>();
        builder.Services.AddSingleton<HiddenViewModel>();
        builder.Services.AddSingleton<HiddenPage>();
        builder.Services.AddTransient<EventDetailViewModel>();   // a fresh one per event opened
        builder.Services.AddTransient<EventDetailPage>();

#if ANDROID
        // The native SearchView draws an underline ("search_plate"); the rounded field replaces it.
        Microsoft.Maui.Handlers.SearchBarHandler.Mapper.AppendToMapping("NoUnderline", (handler, view) =>
        {
            var search = handler.PlatformView;
            int plateId = search.Context?.Resources?.GetIdentifier("android:id/search_plate", null, null) ?? 0;
            search.FindViewById(plateId)?.SetBackgroundColor(Android.Graphics.Color.Transparent);
        });
#endif

#if DEBUG
        builder.Logging.AddDebug();
#endif
        return builder.Build();
    }
}
