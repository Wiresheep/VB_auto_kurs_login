package de.wiresheep.kursplaner;

import android.content.*;
import android.provider.Settings;

/** Target app is known; no undocumented booking endpoint is assumed. */
public final class VeniceBeach {
    public static final String PACKAGE = "com.actinate.venicebeach";
    private VeniceBeach() {}
    public static Intent launchIntent(Context context) {
        return context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
    }
    static String backgroundStartHint(Context context) {
        String hint="VeniceBeach wurde nach dem Startversuch nicht im Vordergrund erkannt. Noch kein Buchungsklick gesendet. ";
        return hint+(!Settings.canDrawOverlays(context)
                ? "Unter Einstellungen & Hilfe → App-Start nach Entsperren freigeben kannst du Androids Freigabe Über anderen Apps einblenden erlauben. Danach Jetzt anmelden wählen."
                : "Die zusätzliche App-Start-Freigabe ist aktiv. Bitte Buchungshelfer und Hintergrundnutzung in den Android-Einstellungen prüfen oder Jetzt anmelden wählen.");
    }
    public static boolean restart(Context context) {
        Intent intent=launchIntent(context);
        if(intent==null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try { context.startActivity(intent); return true; }
        catch(ActivityNotFoundException | SecurityException ex) { return false; }
    }
    public static boolean open(Context context) {
        Intent intent = launchIntent(context);
        if (intent == null) return false;
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (ActivityNotFoundException | SecurityException ex) { return false; }
    }
}
