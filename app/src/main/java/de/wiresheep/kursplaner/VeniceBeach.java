package de.wiresheep.kursplaner;

import android.content.*;

/** Target app is known; no undocumented booking endpoint is assumed. */
public final class VeniceBeach {
    public static final String PACKAGE = "com.actinate.venicebeach";
    private VeniceBeach() {}
    public static Intent launchIntent(Context context) {
        return context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
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
