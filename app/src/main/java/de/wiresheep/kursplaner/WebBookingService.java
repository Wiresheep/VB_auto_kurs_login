package de.wiresheep.kursplaner;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.Set;
import java.util.concurrent.*;

/** Exact alarms start a short dataSync foreground service; no Activity or screen wake is needed. */
public final class WebBookingService extends Service {
    private static final Set<String> active=ConcurrentHashMap.newKeySet();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private volatile boolean stopped;
    static boolean running(String id) { return active.contains(id); }
    static void start(Context context,String id) {
        if(!active.add(id)) return;
        try { context.startForegroundService(new Intent(context,WebBookingService.class).putExtra("plan",id)); }
        catch(RuntimeException ex) {
            active.remove(id);
            PlanStore store=new PlanStore(context); CoursePlan plan=store.find(id);
            if(plan!=null && plan.status!=CoursePlan.Status.VERIFY_REQUIRED) {
                CoursePlan stopped=plan.withStatus(CoursePlan.Status.ACTION_REQUIRED); store.put(stopped);
                store.diagnostic(id,"Android hat den Hintergrundstart abgelehnt. Exakte Alarme erlauben oder Webanmeldung in Kursfenster starten.");
                PlanScheduler.notifyOutcome(context,stopped,false);
            }
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        String channel="web_booking";
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(channel,"Webbuchung läuft",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,channel).setSmallIcon(R.drawable.ic_course)
                .setContentTitle("Kursfenster bucht im Web").setContentText("Die Anmeldung läuft auf diesem Smartphone.")
                .setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29) startForeground(2401,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(2401,notification);
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Kursfenster:Webbuchung");
        wakeLock.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String id=intent==null?null:intent.getStringExtra("plan");
        if(id==null) { stopSelf(startId); return START_NOT_STICKY; }
        active.add(id);
        executor.execute(() -> {
            wakeLock.acquire(180000);
            try {
                WebAccount account=new WebAccount(this); String revision=account.revision();
                String[] credentials=account.read();
                try { WebBookingEngine.run(this,id,new WidgetClient(),credentials,() -> !stopped && revision.equals(account.revision())); }
                finally { java.util.Arrays.fill(credentials,null); }
            } catch(Exception ex) {
                synchronized(PlanStore.LOCK) {
                    PlanStore store=new PlanStore(this); CoursePlan plan=store.find(id);
                    if(plan!=null && plan.status!=CoursePlan.Status.BOOKED) {
                        CoursePlan stopped=plan.withStatus(plan.status==CoursePlan.Status.VERIFY_REQUIRED?plan.status:CoursePlan.Status.ACTION_REQUIRED);
                        store.put(stopped); store.diagnostic(id,"Webzugang nicht verfügbar. Bitte in Kursfenster erneut einrichten.");
                        PlanScheduler.notifyOutcome(this,stopped,stopped.status==CoursePlan.Status.VERIFY_REQUIRED);
                    }
                }
            } finally {
                if(wakeLock.isHeld()) wakeLock.release();
                active.remove(id); new Handler(getMainLooper()).post(() -> stopSelf(startId));
            }
        });
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId,int fgsType) { stopSelf(); }
    @Override public void onDestroy() {
        stopped=true; executor.shutdownNow(); if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
        active.clear(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
