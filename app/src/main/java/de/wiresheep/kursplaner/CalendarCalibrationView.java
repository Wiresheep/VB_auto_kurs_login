package de.wiresheep.kursplaner;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Captures two explicit user taps without forwarding them to VeniceBeach. */
// This overlay is constructed only by the service, with an explicit completion listener.
@android.annotation.SuppressLint("ViewConstructor")
final class CalendarCalibrationView extends View {
    interface Listener { void finished(PointF today,PointF tomorrow); void cancelled(); }
    private final Listener listener;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LocalDate today=LocalDate.now(CoursePlan.STUDIO_ZONE);
    private PointF first;
    CalendarCalibrationView(Context context,Listener listener) {
        super(context); this.listener=listener; setContentDescription("Kalenderleiste einrichten: heute und morgen markieren");
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.argb(25,0,0,0));
        paint.setColor(Color.argb(245,15,48,43)); canvas.drawRect(0,getHeight()*.7f,getWidth(),getHeight(),paint);
        paint.setColor(Color.WHITE); paint.setTextSize(16*getResources().getDisplayMetrics().scaledDensity);
        float x=16*getResources().getDisplayMetrics().density, line=paint.getTextSize()*1.6f;
        String date=DateTimeFormatter.ofPattern("EE dd.MM.",Locale.GERMAN).format(first==null ? today : today.plusDays(1));
        float y=getHeight()*.7f+line;
        canvas.drawText(first==null ? "1/2: Tippe in die Mitte von HEUTE" : "2/2: Tippe in die Mitte von MORGEN",x,y,paint);
        canvas.drawText("Tagesreiter: "+date,x,y+line,paint);
        canvas.drawText("Dein Tippen markiert nur die Position.",x,y+line*2,paint);
        canvas.drawText("Hier unten tippen: Abbrechen",x,y+line*3,paint);
        if(first!=null) {
            paint.setColor(Color.CYAN); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4);
            PointF local=markerPosition();
            canvas.drawCircle(local.x,local.y,20,paint); paint.setStyle(Paint.Style.FILL);
        }
    }
    PointF markerPosition() {
        if(first==null) return null;
        int[] origin=new int[2]; getLocationOnScreen(origin);
        return new PointF(first.x-origin[0],first.y-origin[1]);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(event.getActionMasked()==MotionEvent.ACTION_UP) {
            performClick();
            if(event.getY()>=getHeight()*.7f) listener.cancelled();
            else if(first==null) { first=new PointF(event.getRawX(),event.getRawY()); invalidate(); }
            else listener.finished(first,new PointF(event.getRawX(),event.getRawY()));
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
