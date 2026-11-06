package de.wiresheep.kursplaner;

import android.app.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=31,qualifiers="w411dp-h891dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WebBookingPreviewTest {
    @Before public void setup() {
        Application context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("web_account",0).edit().clear().commit();
        context.getSharedPreferences("web_selection",0).edit().clear().commit();
    }
    private Button button(View root,String text) {
        if(root instanceof Button && ((Button)root).getText().toString().equals(text)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            Button found=button(((ViewGroup)root).getChildAt(i),text); if(found!=null) return found;
        }
        return null;
    }
    private void preview(View decor,String name) throws IOException {
        decor.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(891,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,411,891);
        Bitmap bitmap=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888); decor.draw(new Canvas(bitmap));
        File directory=new File("build/reports/ui-preview"); directory.mkdirs();
        try(var out=new FileOutputStream(new File(directory,name+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
        bitmap.recycle();
    }
    @Test public void previewCourseSelectionAndOpenSetupWithoutLosingStudio() throws Exception {
        try(var controller=Robolectric.buildActivity(WebBookingActivity.class).setup()) {
            WebBookingActivity activity=controller.get(); View decor=activity.getWindow().getDecorView();
            preview(decor,"webbooking-start");
            String fixture;
            try(InputStream in=getClass().getResourceAsStream("/web/gruenstadt.json")) { fixture=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
            JSONObject response=new JSONObject(fixture);
            JSONObject first=response.getJSONObject("days").getJSONArray("data").getJSONObject(0).getJSONArray("occurences").getJSONObject(0);
            long start=LocalDate.now(CoursePlan.STUDIO_ZONE).plusDays(1).atTime(9,0).atZone(CoursePlan.STUDIO_ZONE).toEpochSecond();
            first.put("start_at",start).put("end_at",start+3600);
            JSONObject second=new JSONObject(first.toString()).put("id",123).put("name","Yoga").put("start_at",start+7200).put("end_at",start+10800);
            JSONArray links=second.getJSONArray("links");
            for(int i=0;i<links.length();i++) links.getJSONObject(i).put("uri","/widgets/v1/classes/occurences/123");
            response.getJSONObject("days").getJSONArray("data").getJSONObject(0).getJSONArray("occurences").put(second);
            ReflectionHelpers.setField(activity,"client",new WidgetClient((method,path,token,body) -> response));
            button(decor,"Kurse laden").performClick();
            ExecutorService executor=ReflectionHelpers.getField(activity,"executor"); executor.submit(() -> {}).get(3,TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            LinearLayout list=ReflectionHelpers.getField(activity,"list"); assertEquals(2,list.getChildCount());
            preview(decor,"webbooking-courses");
            ScrollView scroll=ReflectionHelpers.getField(activity,"scroll"); scroll.scrollTo(0,220);
            preview(decor,"webbooking-courses-scrolled");
            button(decor,"Webzugang & Einstellungen").performClick();
            AlertDialog settings=ShadowAlertDialog.getLatestAlertDialog(); assertTrue(settings.isShowing());
            assertNotNull(button(settings.getWindow().getDecorView(),"Zeitgenaue Alarme erlauben"));
            preview(settings.getWindow().getDecorView(),"webbooking-settings");
            button(settings.getWindow().getDecorView(),"Webzugang einrichten").performClick(); shadowOf(Looper.getMainLooper()).idle();
            AlertDialog login=ShadowAlertDialog.getLatestAlertDialog(); assertTrue(login.isShowing());
            assertTrue((login.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
            assertNotNull(button(decor,"VeniceBeach Grünstadt"));
        }
    }
}
