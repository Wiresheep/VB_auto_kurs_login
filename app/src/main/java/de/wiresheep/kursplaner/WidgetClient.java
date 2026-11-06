package de.wiresheep.kursplaner;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Only the HTTPS widget origin is allowed; no redirects or automatic mutation retries. */
final class WidgetClient implements WebBookingEngine.Gateway {
    static final String ORIGIN="https://widget.actinate.com";
    static final String TENANT="venicebeach";
    static final int GRUENSTADT=74;
    private static final String WIDGET_KEY="dQwvUoeuoACEFD2B";
    static final class Studio {
        final int id;
        final String name;
        Studio(int id,String name) {
            if(id<=0 || name==null || name.trim().isEmpty() || name.trim().length()>120)
                throw new IllegalArgumentException("Ungültiges Studio.");
            this.id=id; this.name=name.trim();
        }
    }
    List<Studio> studios() throws Exception {
        return parseStudios(call("GET","/widgets/v1/classes/tenantInfo?key="+WIDGET_KEY
                +"&courseCategoryFilterEnabled=0&courseTrainingGoalFilterEnabled=0&tenantLevelFilterEnabled=0&courseFilterEnabled=0",null));
    }
    static List<Studio> parseStudios(JSONObject response) throws JSONException {
        if(!TENANT.equals(response.getString("tenant"))) throw new JSONException("Unbekannter Anbieter.");
        JSONArray data=response.getJSONObject("studios").getJSONArray("data");
        List<Studio> result=new ArrayList<>(); Set<Integer> ids=new HashSet<>();
        for(int i=0;i<data.length();i++) {
            JSONObject studio=data.getJSONObject(i);
            if(!studio.optBoolean("enableCourses",false) || !studio.optBoolean("enableCourseJoin",false)) continue;
            Studio entry=new Studio(studio.getInt("id"),studio.getString("name"));
            if(!ids.add(entry.id)) throw new JSONException("Doppelte Studiokennung.");
            result.add(entry);
        }
        java.text.Collator order=java.text.Collator.getInstance(Locale.GERMAN);
        result.sort((a,b) -> order.compare(a.name,b.name));
        return result;
    }
    interface Transport { JSONObject request(String method,String path,String token,String body) throws Exception; }
    private final Transport transport;
    private String token;
    WidgetClient() { this(WidgetClient::http); }
    WidgetClient(Transport transport) { this.transport=transport; }
    static String encode(String value) {
        try { return URLEncoder.encode(value,"UTF-8"); }
        catch(UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private JSONObject call(String method,String path,String body) throws Exception {
        return transport.request(method,path+(path.contains("?")?"&":"?")+"tenant="+TENANT,token,body);
    }
    @Override public void login(String email,String password) throws Exception {
        JSONObject reply=call("POST","/oauth/v6/oauth/token","username="+encode(email)+"&password="+encode(password)
                +"&grant_type=password&app_version=widget&os=Android&os_version="+android.os.Build.VERSION.RELEASE);
        token=reply.getString("access_token");
        if(token.isEmpty()) throw new IOException("Login ohne Zugriffstoken.");
        JSONObject user=call("GET","/widgets/v1/users/me",null).getJSONObject("user");
        if(!user.getJSONObject("accessTypeSettings").optBoolean("attendStudioClasses",false))
            throw new IOException("Keine Kursbuchungsberechtigung.");
    }
    @Override public List<JSONObject> courses(LocalDate day,int studio) throws Exception {
        long start=day.atStartOfDay(CoursePlan.STUDIO_ZONE).toEpochSecond();
        long end=day.plusDays(1).atStartOfDay(CoursePlan.STUDIO_ZONE).toEpochSecond()-1;
        String offset=day.atTime(12,0).atZone(CoursePlan.STUDIO_ZONE).getOffset().getId();
        JSONObject response=call("GET","/widgets/v1/classes/occurences?startAt="+start+"&endAt="+end
                +"&studioIds%5B%5D="+studio+"&timzoneForDayBuckets="+encode(offset)
                +"&courseCategoryFilterEnabled=0&courseTrainingGoalFilterEnabled=0&tenantLevelFilterEnabled=0&courseFilterEnabled=0",null);
        return parseCourses(response);
    }
    static List<JSONObject> parseCourses(JSONObject response) throws JSONException {
        JSONArray days=response.getJSONObject("days").getJSONArray("data");
        List<JSONObject> result=new ArrayList<>();
        for(int d=0;d<days.length();d++) {
            JSONArray courses=days.getJSONObject(d).getJSONArray("occurences");
            for(int i=0;i<courses.length();i++) result.add(courses.getJSONObject(i));
        }
        return result;
    }
    static String joinPath(JSONObject course) throws Exception {
        String expected="/widgets/v1/classes/occurences/"+course.getString("id");
        JSONArray links=course.getJSONArray("links"); String path=null;
        for(int i=0;i<links.length();i++) {
            JSONObject link=links.getJSONObject(i);
            if("joinWidgetOccurence".equals(link.optString("rel"))) {
                if(path!=null || !"POST".equals(link.getString("method")) || !expected.equals(link.getString("uri")))
                    throw new IOException("Unbekannter Buchungslink.");
                path=link.getString("uri");
            }
        }
        if(path==null) throw new IOException("Buchungslink fehlt.");
        return path;
    }
    @Override public void join(JSONObject course) throws Exception { call("POST",joinPath(course),null); }
    private static JSONObject http(String method,String path,String token,String body) throws Exception {
        if(!path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.contains("#"))
            throw new IOException("Ungültiger Widget-Pfad.");
        HttpURLConnection connection=(HttpURLConnection)new URL(ORIGIN+path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        connection.setUseCaches(false); connection.setRequestMethod(method);
        connection.setRequestProperty("Accept","application/json");
        if(token!=null) connection.setRequestProperty("Authorization","Bearer "+token);
        long deadline=android.os.SystemClock.elapsedRealtime()+20000;
        try {
            if(body!=null || "POST".equals(method)) {
                byte[] bytes=body==null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                try(OutputStream out=connection.getOutputStream()) { out.write(bytes); }
            }
            int status=connection.getResponseCode();
            if(status<200 || status>=300) throw new IOException("Webzugang HTTP "+status);
            try(InputStream input=connection.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[8192]; int size;
                while((size=input.read(bytes))!=-1) {
                    if(Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime()>deadline) throw new IOException("Webanfrage abgelaufen.");
                    if(out.size()+size>4*1024*1024) throw new IOException("Webantwort zu groß.");
                    out.write(bytes,0,size);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }
}
