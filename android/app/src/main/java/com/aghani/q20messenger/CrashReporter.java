package com.aghani.q20messenger;
import android.content.Context;
import org.json.JSONObject;
import java.io.PrintWriter;
import java.io.StringWriter;
/** Retains one bounded failure report and uploads it to this app's configured server. */
final class CrashReporter {
    static void install(final Context context) {
        final Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler(){public void uncaughtException(Thread thread,Throwable error){save(context,"uncaught:"+thread.getName(),error);if(original!=null)original.uncaughtException(thread,error);}});
        flush(context);
    }
    static void report(Context context,String event,Throwable error){save(context,event,error);flush(context.getApplicationContext());}
    private static void save(Context context,String event,Throwable error){
        try{StringWriter text=new StringWriter();error.printStackTrace(new PrintWriter(text));String stack=text.toString();
            JSONObject body=new JSONObject();body.put("event",event);body.put("stack",stack.substring(0,Math.min(8000,stack.length())));body.put("version",BuildConfig.VERSION_NAME);body.put("sdk",android.os.Build.VERSION.SDK_INT);body.put("model",android.os.Build.MODEL);
            android.util.DisplayMetrics m=context.getResources().getDisplayMetrics();body.put("screen",m.widthPixels+"x"+m.heightPixels+"@"+m.density);
            context.getSharedPreferences("crash_report",0).edit().putString("pending",body.toString()).commit();
        }catch(Exception ignored){}
    }
    static void flush(final Context context){
        final String report=context.getSharedPreferences("crash_report",0).getString("pending","");
        if(report.length()==0 || AppPrefs.getServerUrl(context).length()==0 || AppPrefs.getApiToken(context).length()==0)return;
        AppExecutors.NETWORK.execute(new Runnable(){public void run(){try{new ApiClient(context).reportClientDiagnostic(new JSONObject(report));if(report.equals(context.getSharedPreferences("crash_report",0).getString("pending","")))context.getSharedPreferences("crash_report",0).edit().remove("pending").commit();}catch(Exception ignored){}}});
    }
}
