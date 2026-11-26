package com.greenwich.eventmanager.util;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Location helpers for the event form.
 *
 * 1. Place suggestions while typing (searchPlaces):
 *    Uses the Photon geocoding API (https://photon.komoot.io), built on
 *    OpenStreetMap data and designed for search-as-you-type. Free, no API key.
 *    If Photon is unreachable or finds nothing, falls back to Android's Geocoder.
 *    Reference: Komoot (2024) Photon - search-as-you-type geocoder for OpenStreetMap.
 *
 * 2. Address of the current GPS position (reverseGeocode):
 *    Uses Android's built-in Geocoder.
 *
 * Network calls must never run on the main (UI) thread, so they run on a
 * background thread and the result is posted back to the main thread.
 */
public final class LocationHelper {

    private static final String TAG = "LocationHelper";
    private static final String PHOTON_URL = "https://photon.komoot.io/api/";
    private static final String USER_AGENT = "EventManagerCoursework/1.0 (Android)";
    private static final int TIMEOUT_MS = 5000;

    public interface Callback<T> {
        void onResult(T result);
    }

    /** A suggested place: a readable label plus its map coordinates. */
    public static final class Place {
        public final String label;
        public final double lat;
        public final double lng;

        Place(String label, double lat, double lng) {
            this.label = label;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LocationHelper() { }

    // =====================================================================
    // Suggestions while typing
    // =====================================================================

    /** Finds up to maxResults places matching a partial name, e.g. "greenwich uni". */
    public static void searchPlaces(Context ctx, String query, int maxResults,
                                    Callback<List<Place>> callback) {
        Context appContext = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            List<Place> places = searchPhoton(query, maxResults);
            if (places.isEmpty()) {
                places = searchGeocoder(appContext, query, maxResults);   // fallback
            }
            List<Place> result = places;
            MAIN.post(() -> callback.onResult(result));
        });
    }

    /** Runs on the background thread. Returns an empty list on any failure. */
    private static List<Place> searchPhoton(String query, int maxResults) {
        List<Place> places = new ArrayList<>();
        Set<String> seenLabels = new LinkedHashSet<>();   // Photon can return duplicates
        HttpURLConnection conn = null;
        try {
            String url = PHOTON_URL + "?q=" + URLEncoder.encode(query, "UTF-8")
                    + "&limit=" + maxResults;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Photon HTTP " + conn.getResponseCode());
                return places;
            }

            JSONArray features = new JSONObject(readAll(conn.getInputStream()))
                    .getJSONArray("features");
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject props = feature.getJSONObject("properties");
                // GeoJSON stores coordinates as [longitude, latitude]
                JSONArray coords = feature.getJSONObject("geometry").getJSONArray("coordinates");

                String label = buildLabel(props);
                if (!label.isEmpty() && seenLabels.add(label)) {
                    places.add(new Place(label, coords.getDouble(1), coords.getDouble(0)));
                }
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Photon search failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return places;
    }

    /** "Old Royal Naval College, King William Walk, London, United Kingdom" */
    private static String buildLabel(JSONObject p) {
        Set<String> parts = new LinkedHashSet<>();   // keeps order, skips repeats
        addPart(parts, p.optString("name"));
        String street = p.optString("street");
        String number = p.optString("housenumber");
        addPart(parts, number.isEmpty() ? street : number + " " + street);
        String city = p.optString("city");
        addPart(parts, city.isEmpty() ? p.optString("county") : city);
        addPart(parts, p.optString("country"));
        return String.join(", ", parts);
    }

    private static void addPart(Set<String> parts, String value) {
        if (value != null && !value.trim().isEmpty()) parts.add(value.trim());
    }

    @SuppressWarnings("deprecation")  // blocking call is fine: we are on a background thread
    private static List<Place> searchGeocoder(Context ctx, String query, int maxResults) {
        List<Place> places = new ArrayList<>();
        if (!Geocoder.isPresent()) return places;
        try {
            List<Address> list = new Geocoder(ctx, Locale.getDefault())
                    .getFromLocationName(query, maxResults);
            if (list != null) {
                for (Address a : list) {
                    places.add(new Place(format(a), a.getLatitude(), a.getLongitude()));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "Geocoder search failed", e);
        }
        return places;
    }

    // =====================================================================
    // Current position -> address
    // =====================================================================

    /** Turns GPS coordinates into the nearest address (null if none found). */
    @SuppressWarnings("deprecation")
    public static void reverseGeocode(Context ctx, double lat, double lng,
                                      Callback<Address> callback) {
        if (!Geocoder.isPresent()) {
            callback.onResult(null);
            return;
        }
        Geocoder geocoder = new Geocoder(ctx.getApplicationContext(), Locale.getDefault());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lng, 1, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(@NonNull List<Address> addresses) {
                    Address first = addresses.isEmpty() ? null : addresses.get(0);
                    MAIN.post(() -> callback.onResult(first));
                }

                @Override
                public void onError(@Nullable String message) {
                    MAIN.post(() -> callback.onResult(null));
                }
            });
        } else {
            EXECUTOR.execute(() -> {
                Address first = null;
                try {
                    List<Address> list = geocoder.getFromLocation(lat, lng, 1);
                    if (list != null && !list.isEmpty()) first = list.get(0);
                } catch (IOException | IllegalArgumentException e) {
                    Log.w(TAG, "Reverse geocoding failed", e);
                }
                Address result = first;
                MAIN.post(() -> callback.onResult(result));
            });
        }
    }

    /** One readable line for an Android Address. */
    public static String format(Address a) {
        if (a.getMaxAddressLineIndex() >= 0 && a.getAddressLine(0) != null) {
            return a.getAddressLine(0);
        }
        Set<String> parts = new LinkedHashSet<>();
        addPart(parts, a.getFeatureName());
        addPart(parts, a.getLocality());
        addPart(parts, a.getCountryName());
        return String.join(", ", parts);
    }

    // =====================================================================

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}