package com.greenwich.eventmanager.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Checks whether the device is connected to a network that actually reaches the internet. */
public final class NetworkUtils {

    private NetworkUtils() { }

    /**
     * INTERNET   = the network is meant to reach the internet;
     * VALIDATED  = Android has confirmed it really does (e.g. not stuck on a Wi-Fi login page).
     */
    public static boolean isOnline(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}