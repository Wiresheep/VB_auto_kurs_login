package de.wiresheep.kursplaner;

import android.app.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.*;
import java.time.Instant;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=31,qualifiers="w411dp-h891dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityUxTest {
    private Application context;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication(); context.getSharedPreferences("course_plans",0).edit().clear().commit();
        context.getSharedPreferences("MainActivity",0).edit().clear().commit();
    }
    private Button button(View view,String label) {
        if(view instanceof Button && ((Button)view).getText().toString().equals(label)) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i),label); if(found!=null) return found;
        }
        return null;
    }
    private void preview(Activity activity,String name,boolean form) throws IOException {
        View decor=activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(891,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,411,891);
        if(form) {
            ScrollView scroll=org.robolectric.util.ReflectionHelpers.getField(activity,"scroll");
            LinearLayout panel=org.robolectric.util.ReflectionHelpers.getField(activity,"form"); scroll.scrollTo(0,panel.getTop());
        }
        Bitmap image=Bitmap.createBitmap(411,891,Bitmap.Config.ARGB_8888); decor.draw(new Canvas(image));
        File directory=new File("build/reports/ui-preview"); directory.mkdirs();
        try(var output=new FileOutputStream(new File(directory,name+".png"))) { image.compress(Bitmap.CompressFormat.PNG,100,output); }
        image.recycle();
    }
    @Test public void editingAPlannedCourseKeepsItsIdentityAndOneAlarm() throws Exception {
        CoursePlan original=new CoursePlan(UUID.randomUUID().toString(),"Pilates","VeniceBeach Grünstadt",Instant.now().plusSeconds(259200),CoursePlan.Status.PLANNED);
        new PlanStore(context).put(original);
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            var activity=controller.get(); preview(activity,"home",false);
            button(activity.getWindow().getDecorView(),"Details").performClick();
            var details=ShadowAlertDialog.getLatestAlertDialog(); button(details.getWindow().getDecorView(),"Angaben bearbeiten").performClick();
            ((EditText)activity.findViewById(1002)).setText("Pilates Core"); preview(activity,"edit-course",true);
            button(activity.getWindow().getDecorView(),"Änderungen speichern").performClick();
            var plans=new PlanStore(context).all(); assertEquals(1,plans.size()); assertEquals(original.id,plans.get(0).id);
            assertEquals("Pilates Core",plans.get(0).course);
            assertEquals(1,shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
        }
    }
    @Test public void symbolOnlyManualNamesCannotCreateAPlan() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            var activity=controller.get(); button(activity.getWindow().getDecorView(),"Kurs manuell eintragen").performClick();
            ((EditText)activity.findViewById(1002)).setText("😋"); ((EditText)activity.findViewById(1001)).setText("🍌");
            button(activity.getWindow().getDecorView(),"Plan speichern").performClick();
            assertTrue(new PlanStore(context).all().isEmpty());
            assertNotNull(((EditText)activity.findViewById(1002)).getError()); assertNotNull(((EditText)activity.findViewById(1001)).getError());
        }
    }
}
