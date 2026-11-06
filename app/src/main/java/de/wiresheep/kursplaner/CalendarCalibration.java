package de.wiresheep.kursplaner;

import android.content.Context;
import android.graphics.PointF;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.time.LocalDate;

/** User-recorded tab centers, valid only for the same display geometry. No image capture. */
public final class CalendarCalibration {
    private CalendarCalibration() {}
    static DisplayMetrics screen(Context context) {
        DisplayMetrics metrics=new DisplayMetrics();
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }
    private static int rotation(Context context) {
        return context.getSystemService(WindowManager.class).getDefaultDisplay().getRotation();
    }
    private static android.content.SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("calendar_calibration",Context.MODE_PRIVATE);
    }
    public static void clear(Context context) { preferences(context).edit().clear().apply(); }
    public static boolean available(Context context) {
        LocalDate today=LocalDate.now(CoursePlan.STUDIO_ZONE);
        return point(context,today,today)!=null;
    }
    public static void save(Context context,PointF today,PointF tomorrow) {
        DisplayMetrics metrics=screen(context);
        int width=metrics.widthPixels,height=metrics.heightPixels;
        if(!validPoint(today,width,height) || !validPoint(tomorrow,width,height)
                || tomorrow.x-today.x<width*.05f || tomorrow.x-today.x>width*.3f
                || Math.abs(tomorrow.y-today.y)>height*.08f)
            throw new IllegalArgumentException("Bitte die Mitte von zwei benachbarten Tagesreitern antippen: erst heute, dann morgen.");
        if(!preferences(context).edit().putInt("width",width).putInt("height",height)
                .putInt("density",metrics.densityDpi).putInt("rotation",rotation(context))
                .putFloat("font_scale",context.getResources().getConfiguration().fontScale)
                .putFloat("today_x",today.x).putFloat("today_y",today.y)
                .putFloat("tomorrow_x",tomorrow.x).putFloat("tomorrow_y",tomorrow.y).commit())
            throw new IllegalStateException("Kalenderpositionen konnten nicht gespeichert werden.");
    }
    private static boolean validPoint(PointF point,int width,int height) {
        return point!=null && Float.isFinite(point.x) && Float.isFinite(point.y)
                && point.x>0 && point.x<width && point.y>height*.1f && point.y<height*.65f;
    }
    public static PointF point(Context context,LocalDate today,LocalDate target) {
        if(!target.equals(today) && !target.equals(today.plusDays(1))) return null;
        var data=preferences(context); DisplayMetrics metrics=screen(context);
        if(data.getInt("width",0)!=metrics.widthPixels || data.getInt("height",0)!=metrics.heightPixels
                || data.getInt("density",0)!=metrics.densityDpi || data.getInt("rotation",-1)!=rotation(context)
                || Float.compare(data.getFloat("font_scale",-1),context.getResources().getConfiguration().fontScale)!=0) return null;
        PointF first=new PointF(data.getFloat("today_x",-1),data.getFloat("today_y",-1));
        PointF next=new PointF(data.getFloat("tomorrow_x",-1),data.getFloat("tomorrow_y",-1));
        if(!validPoint(first,metrics.widthPixels,metrics.heightPixels)
                || !validPoint(next,metrics.widthPixels,metrics.heightPixels)) return null;
        return target.equals(today) ? first : next;
    }
}
