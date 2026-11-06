package de.wiresheep.kursplaner;

import android.content.Context;

/** Scheduling policy; neither option bypasses the lock screen or enables hidden UI booking. */
final class BookingPreferences {
    private BookingPreferences() {}
    private static android.content.SharedPreferences data(Context context) {
        return context.getSharedPreferences("booking_preferences",Context.MODE_PRIVATE);
    }
    static boolean waitForUnlock(Context context) { return data(context).getBoolean("wait_for_unlock",false); }
    static void waitForUnlock(Context context,boolean enabled) { data(context).edit().putBoolean("wait_for_unlock",enabled).apply(); }
    static boolean automaticOpen(Context context) { return data(context).getBoolean("automatic_open",true); }
    static void automaticOpen(Context context,boolean enabled) { data(context).edit().putBoolean("automatic_open",enabled).apply(); }
}
