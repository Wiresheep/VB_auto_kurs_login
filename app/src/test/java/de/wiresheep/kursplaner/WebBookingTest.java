package de.wiresheep.kursplaner;

import android.app.Application;
import android.app.KeyguardManager;
import android.os.PowerManager;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class WebBookingTest {
    Application context; PlanStore store; CoursePlan plan; JSONObject course;
    class Fake implements WebBookingEngine.Gateway {
        int posts,reads; boolean failLogin,loseResponse,confirmed=true,failVerification; Runnable beforePost=() -> {};
        public void login(String email,String password) throws Exception { if(failLogin) throw new IOException("SECRET"); }
        public List<JSONObject> courses(LocalDate day,int studio) throws Exception {
            reads++; if(reads>1 && failVerification) throw new IOException("SECRET");
            return List.of(course);
        }
        public void join(JSONObject selected) throws Exception {
            assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(plan.id).status);
            beforePost.run(); posts++; course.put("joined",confirmed);
            if(loseResponse) throw new IOException("SECRET");
        }
    }
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication(); store=new PlanStore(context);
        context.getSharedPreferences("course_plans",0).edit().clear().commit();
        String fixture=new String(getClass().getResourceAsStream("/web/gruenstadt.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        course=WidgetClient.parseCourses(new JSONObject(fixture)).get(0);
        Instant start=Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        course.put("start_at",start.getEpochSecond());
        plan=new CoursePlan(UUID.randomUUID().toString(),course.getString("name"),"VeniceBeach Grünstadt",start,CoursePlan.Status.PLANNED,true,course.getString("id"),74);
        store.put(plan);
    }
    private void run(Fake gateway) { WebBookingEngine.run(context,plan.id,gateway,new String[]{"email","secret"},() -> true); }
    @Test public void booksWithLockedDisplayAndNoAccessibilityService() {
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(false);
        Fake fake=new Fake(); run(fake);
        assertEquals(1,fake.posts); assertEquals(CoursePlan.Status.BOOKED,store.find(plan.id).status);
    }
    @Test public void confirmsDespiteLostPostResponseWithoutRepeatingPost() {
        Fake fake=new Fake(); fake.loseResponse=true; run(fake); run(fake);
        assertEquals(1,fake.posts); assertEquals(CoursePlan.Status.BOOKED,store.find(plan.id).status);
    }
    @Test public void unconfirmedAttemptIsReadOnlyOnSubsequentRun() {
        Fake fake=new Fake(); fake.confirmed=false; run(fake); run(fake);
        assertEquals(1,fake.posts); assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(plan.id).status);
        course.remove("joined"); run(fake); assertEquals(1,fake.posts);
    }
    @Test public void processRestartMarkerNeverResendsEvenWhenNotJoined() {
        store.put(plan.withStatus(CoursePlan.Status.VERIFY_REQUIRED));
        PlanScheduler.restore(context); Fake fake=new Fake(); run(fake);
        assertEquals(0,fake.posts); assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(plan.id).status);
    }
    @Test public void existingParticipationRequiresNoPost() throws Exception {
        course.put("joined",true); Fake fake=new Fake(); run(fake);
        assertEquals(0,fake.posts); assertEquals(CoursePlan.Status.BOOKED,store.find(plan.id).status);
    }
    @Test public void loginFailureRetainsSafeRetryAndDoesNotLeakSecrets() {
        Fake fake=new Fake(); fake.failLogin=true; run(fake);
        assertEquals(0,fake.posts); assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(plan.id).status);
        assertFalse(store.diagnostic(plan.id).contains("SECRET"));
        fake.failLogin=false; run(fake); assertEquals(1,fake.posts);
    }
    @Test public void changedIdentityAndChangedScheduleStopBeforePost() throws Exception {
        for(String field:List.of("id","studioId","start_at","name")) {
            setup(); course.put(field,field.equals("name")?"Other":5); Fake fake=new Fake(); run(fake);
            assertEquals(field,0,fake.posts); assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(plan.id).status);
        }
    }
    @Test public void fullPrivateCancelledAndClosedCoursesStopBeforePost() throws Exception {
        for(String field:List.of("attendees_count","private","canceled_at","enableJoin","is_livestream","join_open_prior_start_seconds")) {
            setup(); course.put(field,switch(field) {
                case "attendees_count" -> 100;
                case "canceled_at" -> 1;
                case "enableJoin" -> false;
                case "join_open_prior_start_seconds" -> 3600;
                default -> true;
            });
            Fake fake=new Fake(); run(fake); assertEquals(field,0,fake.posts);
        }
    }
    @Test public void mismatchedOrExternalJoinLinkIsRejected() throws Exception {
        for(String path:List.of("https://elsewhere.example/join","//elsewhere.example/join","/widgets/v1/classes/occurences/42")) {
            setup(); course.getJSONArray("links").getJSONObject(1).put("uri",path);
            Fake fake=new Fake(); run(fake); assertEquals(0,fake.posts);
        }
    }
    @Test public void accountChangeOrDeletedPlanPreventsSubmission() {
        Fake fake=new Fake(); WebBookingEngine.run(context,plan.id,fake,new String[]{"email","secret"},() -> false);
        assertEquals(0,fake.posts);
        store.remove(plan.id); run(fake); assertNull(store.find(plan.id));
    }
    @Test public void verificationFailureNeverTurnsIntoSafeRetry() {
        Fake fake=new Fake(); fake.failVerification=true; run(fake);
        assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(plan.id).status);
        run(fake); assertEquals(1,fake.posts);
    }
    @Test public void webIdsSurvivePersistenceAndStatusChangesAndLegacyPlansRemainLegacy() {
        CoursePlan loaded=new PlanStore(context).find(plan.id).withStatus(CoursePlan.Status.BOOKED).withAutomatic(false);
        assertEquals(plan.webOccurrenceId,loaded.webOccurrenceId); assertEquals(74,loaded.webStudioId);
        assertEquals(plan.startsAt.minusSeconds(86400),loaded.opensAt());
        CoursePlan old=new CoursePlan(UUID.randomUUID().toString(),"Yoga","Studio",plan.startsAt,CoursePlan.Status.PLANNED);
        store.put(old); assertFalse(new PlanStore(context).find(old.id).isWeb());
    }
    @Test public void widgetRequestUsesFormLoginBearerAnd64BitIds() throws Exception {
        List<String> requests=new ArrayList<>();
        WidgetClient client=new WidgetClient((method,path,token,body) -> {
            requests.add(method+" "+path+" TOKEN="+token+" BODY="+body);
            if(path.startsWith("/oauth/")) {
                assertTrue(body.contains("username=a%2Bb%40example.com")); assertTrue(body.contains("password=p%26%3D%2B"));
                assertNull(token); return new JSONObject().put("access_token","test-token");
            }
            assertEquals("test-token",token);
            if(path.startsWith("/widgets/v1/users/me")) return new JSONObject("{user:{accessTypeSettings:{attendStudioClasses:true}}}");
            if(method.equals("POST")) return new JSONObject();
            return new JSONObject().put("days",new JSONObject().put("data",new JSONArray().put(new JSONObject().put("occurences",new JSONArray().put(course)))));
        });
        client.login("a+b@example.com","p&=+");
        JSONObject selected=client.courses(LocalDate.of(2026,11,6),74).get(0); client.join(selected);
        assertEquals("8400000084641347",selected.getString("id"));
        assertTrue(requests.get(2).contains("studioIds%5B%5D=74"));
        assertTrue(requests.get(3).startsWith("POST /widgets/v1/classes/occurences/8400000084641347?tenant=venicebeach"));
    }
    @Test public void earlyAndExpiredPlansDoNotSend() {
        store.put(new CoursePlan(plan.id,plan.course,plan.studio,Instant.now().plusSeconds(172800),CoursePlan.Status.PLANNED,true,plan.webOccurrenceId,74));
        Fake early=new Fake(); run(early); assertEquals(0,early.reads); assertEquals(0,early.posts);
        store.put(new CoursePlan(plan.id,plan.course,plan.studio,Instant.now().minusSeconds(1),CoursePlan.Status.PLANNED,true,plan.webOccurrenceId,74));
        Fake expired=new Fake(); run(expired); assertEquals(0,expired.posts); assertEquals(CoursePlan.Status.EXPIRED,store.find(plan.id).status);
    }
    @Test public void duplicateCourseIdsAreNotBooked() throws Exception {
        try { WebBookingEngine.find(plan,List.of(course,course)); fail("Duplicate accepted"); }
        catch(IOException expected) { }
    }
    @Test public void lockedAlarmStartsWebServiceWithoutLaunchingAnActivity() {
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(false);
        new BookingAlarmReceiver().onReceive(context,new android.content.Intent().setData(android.net.Uri.parse("kursfenster://plan/"+plan.id)));
        android.content.Intent service=shadowOf(context).getNextStartedService();
        assertNotNull(service); assertEquals(WebBookingService.class.getName(),service.getComponent().getClassName());
        assertEquals(plan.id,service.getStringExtra("plan")); assertNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void webCourseScreenHasDistinctSetupAndDoesNotRequireAccessibility() {
        try(var controller=Robolectric.buildActivity(WebBookingActivity.class).setup()) {
            android.app.Activity activity=controller.get();
            assertTrue((activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);
            assertEquals(0,store.all().stream().filter(p -> !p.id.equals(plan.id)).count());
        }
    }

}
