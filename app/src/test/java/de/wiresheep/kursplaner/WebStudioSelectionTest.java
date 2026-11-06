package de.wiresheep.kursplaner;

import android.app.*;
import android.content.Intent;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class WebStudioSelectionTest {
    Application context; JSONObject studios,course; List<Integer> requestedStudios;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();
        for(String name:List.of("course_plans","web_selection","web_account")) context.getSharedPreferences(name,0).edit().clear().commit();
        studios=fixture("studios.json");
        course=WidgetClient.parseCourses(fixture("gruenstadt.json")).get(0);
        course.put("start_at",LocalDate.now(CoursePlan.STUDIO_ZONE).plusDays(1).atTime(12,0).atZone(CoursePlan.STUDIO_ZONE).toEpochSecond());
        requestedStudios=new ArrayList<>();
    }
    private JSONObject fixture(String name) throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/web/"+name)) {
            return new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private WidgetClient fake(boolean failStudios) {
        return new WidgetClient((method,path,token,body) -> {
            assertEquals("GET",method); assertNull(token); assertNull(body);
            if(path.startsWith("/widgets/v1/classes/tenantInfo?")) {
                if(failStudios) throw new IOException("offline"); return studios;
            }
            int id=path.contains("studioIds%5B%5D=47&")?47:74;
            requestedStudios.add(id);
            JSONObject selected=new JSONObject(course.toString()).put("studioId",id);
            JSONObject other=new JSONObject(course.toString()).put("id",123).put("name","Fremdes Studio").put("studioId",id==74?47:74);
            return new JSONObject().put("days",new JSONObject().put("data",new JSONArray().put(new JSONObject()
                .put("occurences",new JSONArray().put(selected).put(other)))));
        });
    }
    private void await(WebBookingActivity activity) throws Exception {
        ExecutorService executor=ReflectionHelpers.getField(activity,"executor");
        executor.submit(() -> {}).get(3,TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle();
    }
    private Button button(View root,String startsWith) {
        if(root instanceof Button && ((Button)root).getText().toString().startsWith(startsWith)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            Button found=button(((ViewGroup)root).getChildAt(i),startsWith); if(found!=null) return found;
        }
        return null;
    }
    private Button button(Activity activity,String startsWith) { return button(activity.getWindow().getDecorView(),startsWith); }
    private void select(WebBookingActivity activity,String studio) throws Exception {
        button(activity,"VeniceBeach").performClick(); await(activity);
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing());
        ListView list=dialog.getListView(); int index=-1;
        for(int i=0;i<list.getCount();i++) if(studio.equals(list.getAdapter().getItem(i).toString())) index=i;
        assertTrue(index>=0); list.performItemClick(null,index,list.getAdapter().getItemId(index));
    }
    @Test public void providerStudiosAreLoadedSortedAndOnlyEnabledStudiosOffered() throws Exception {
        List<WidgetClient.Studio> all=WidgetClient.parseStudios(studios);
        assertEquals(39,all.size());
        assertEquals("VeniceBeach Frankenthal",all.stream().filter(s -> s.id==47).findFirst().get().name);
        JSONArray entries=studios.getJSONObject("studios").getJSONArray("data");
        entries.getJSONObject(0).put("enableCourses",false); entries.getJSONObject(1).put("enableCourseJoin",false);
        entries.getJSONObject(2).remove("enableCourses");
        List<WidgetClient.Studio> enabled=WidgetClient.parseStudios(studios); assertEquals(36,enabled.size());
        java.text.Collator order=java.text.Collator.getInstance(Locale.GERMAN);
        for(int i=1;i<enabled.size();i++) assertTrue(order.compare(enabled.get(i-1).name,enabled.get(i).name)<=0);
        assertTrue(enabled.stream().noneMatch(s -> s.name.endsWith(" ")));
    }
    @Test public void ambiguousOrWrongProviderStudioListsAreRejected() throws Exception {
        studios.put("tenant","other");
        try { WidgetClient.parseStudios(studios); fail(); } catch(JSONException expected) { }
        studios.put("tenant","venicebeach"); JSONArray entries=studios.getJSONObject("studios").getJSONArray("data");
        entries.put(entries.getJSONObject(0));
        try { WidgetClient.parseStudios(studios); fail(); } catch(JSONException expected) { }
    }
    @Test public void studioQueryUsesThePublicConfigurationAndTenant() throws Exception {
        WidgetClient client=new WidgetClient((method,path,token,body) -> {
            assertEquals("GET",method); assertTrue(path.startsWith("/widgets/v1/classes/tenantInfo?key="));
            assertTrue(path.endsWith("tenant=venicebeach")); assertNull(token); assertNull(body); return studios;
        });
        assertEquals(39,client.studios().size());
    }
    @Test public void selectingStudioQueriesItAndSavesItsIdentityInThePlan() throws Exception {
        try(var controller=Robolectric.buildActivity(WebBookingActivity.class).setup()) {
            WebBookingActivity activity=controller.get(); ReflectionHelpers.setField(activity,"client",fake(false));
            select(activity,"VeniceBeach Frankenthal");
            button(activity,"Kurse laden").performClick(); await(activity);
            assertEquals(List.of(47),requestedStudios); assertNull(button(activity,"Fremdes Studio"));
            // This test exercises plan confirmation only; no credentials are decrypted or sent.
            context.getSharedPreferences("web_account",0).edit().putString("credentials","test-only").commit();
            button(activity,course.getString("name")+" · ").performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(((TextView)dialog.findViewById(android.R.id.message)).getText().toString().contains("Studio: VeniceBeach Frankenthal"));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle();
            CoursePlan saved=new PlanStore(context).all().get(0);
            assertEquals(47,saved.webStudioId); assertEquals("VeniceBeach Frankenthal",saved.studio);
            assertEquals(course.getString("id"),saved.webOccurrenceId);
        }
    }
    @Test public void switchingStudiosClearsOldCoursesAndCannotPlanAStaleCard() throws Exception {
        try(var controller=Robolectric.buildActivity(WebBookingActivity.class).setup()) {
            WebBookingActivity activity=controller.get(); ReflectionHelpers.setField(activity,"client",fake(false));
            button(activity,"Kurse laden").performClick(); await(activity);
            Button old=button(activity,course.getString("name")+" · "); assertNotNull(old);
            select(activity,"VeniceBeach Frankenthal");
            assertNull(button(activity,course.getString("name")+" · "));
            old.performClick(); assertTrue(new PlanStore(context).all().isEmpty());
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            button(activity,"Kurse laden").performClick(); await(activity); assertEquals(List.of(74,47),requestedStudios);
        }
    }
    @Test public void studioSurvivesRecreationAndReopeningWithoutChangingExistingPlans() throws Exception {
        CoursePlan original=new CoursePlan(UUID.randomUUID().toString(),"Yoga","VeniceBeach Grünstadt",Instant.now().plusSeconds(3600),CoursePlan.Status.PLANNED,true,"123",74);
        new PlanStore(context).put(original);
        try(var controller=Robolectric.buildActivity(WebBookingActivity.class).setup()) {
            WebBookingActivity activity=controller.get(); ReflectionHelpers.setField(activity,"client",fake(false));
            select(activity,"VeniceBeach Frankenthal"); controller.recreate();
            assertNotNull(button(controller.get(),"VeniceBeach Frankenthal"));
        }
        try(var controller=Robolectric.buildActivity(WebBookingActivity.class).setup()) {
            assertNotNull(button(controller.get(),"VeniceBeach Frankenthal"));
        }
        CoursePlan saved=new PlanStore(context).find(original.id); assertEquals(74,saved.webStudioId);
        assertEquals("VeniceBeach Grünstadt",saved.studio);
    }
    @Test public void openingPlanStudioOverridesRememberedSelection() {
        context.getSharedPreferences("web_selection",0).edit().putInt("studioId",47).putString("studioName","VeniceBeach Frankenthal").commit();
        Intent intent=new Intent(context,WebBookingActivity.class).putExtra("studioId",68).putExtra("studioName","VeniceBeach Worms");
        try(var controller=Robolectric.buildActivity(WebBookingActivity.class,intent).setup()) {
            assertNotNull(button(controller.get(),"VeniceBeach Worms")); controller.recreate();
            assertNotNull(button(controller.get(),"VeniceBeach Worms"));
        }
    }
    @Test public void failingStudioLookupRetainsTheSelectedStudio() throws Exception {
        try(var controller=Robolectric.buildActivity(WebBookingActivity.class).setup()) {
            WebBookingActivity activity=controller.get(); ReflectionHelpers.setField(activity,"client",fake(true));
            button(activity,"VeniceBeach Grünstadt").performClick(); await(activity);
            assertNotNull(button(activity,"VeniceBeach Grünstadt")); assertTrue(new PlanStore(context).all().isEmpty());
            ReflectionHelpers.setField(activity,"client",fake(false)); select(activity,"VeniceBeach Frankenthal");
        }
    }
    @Test public void bookingUsesPersistedStudioEvenIfPickerNowShowsAnother() throws Exception {
        Instant start=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        CoursePlan plan=new CoursePlan(UUID.randomUUID().toString(),course.getString("name"),"VeniceBeach Frankenthal",start,CoursePlan.Status.PLANNED,true,course.getString("id"),47);
        new PlanStore(context).put(plan); course.put("studioId",47).put("start_at",start.getEpochSecond());
        context.getSharedPreferences("web_selection",0).edit().putInt("studioId",74).putString("studioName","VeniceBeach Grünstadt").commit();
        List<Integer> reads=new ArrayList<>();
        WebBookingEngine.Gateway gateway=new WebBookingEngine.Gateway() {
            public void login(String email,String password) { }
            public List<JSONObject> courses(LocalDate day,int studio) { reads.add(studio); return List.of(course); }
            public void join(JSONObject selected) throws Exception { assertEquals(47,selected.getInt("studioId")); course.put("joined",true); }
        };
        WebBookingEngine.run(context,plan.id,gateway,new String[]{"test","test"},() -> true);
        assertEquals(List.of(47,47),reads); assertEquals(CoursePlan.Status.BOOKED,new PlanStore(context).find(plan.id).status);
    }
}
