package com.aghani.q20messenger;
import android.app.Activity;import android.app.NotificationManager;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import org.json.JSONObject;
/** Presents ringing only. No call is accepted until Answer is pressed. */
public class IncomingCallActivity extends Activity {
 private String callId="";private final Handler handler=new Handler();private boolean checking;
 private final Runnable poll=new Runnable(){public void run(){if(isFinishing())return;if(!checking){checking=true;AppExecutors.CALLS.execute(new Runnable(){public void run(){JSONObject value=null;try{value=new ApiClient(IncomingCallActivity.this).getVoiceCallStatus();}catch(Exception ignored){}final JSONObject status=value;handler.post(new Runnable(){public void run(){checking=false;if(status!=null&&(!status.optBoolean("incoming")||!status.optBoolean("active")||!callId.equals(status.optString("call_id"))))finish();}});}});}handler.postDelayed(this,2000);}};
 @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);showIncoming();}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);showIncoming();}
 private void showIncoming(){
  callId=getIntent().getStringExtra("call_id");if(callId==null||callId.length()==0){finish();return;}
  LinearLayout root=Ui.vertical(this);root.setPadding(Ui.dp(this,16),Ui.dp(this,20),Ui.dp(this,16),Ui.dp(this,16));root.setBackgroundColor(0xff0b141a);
  TextView label=Ui.text(this,"Incoming WhatsApp voice call",14,0xff8696a0);label.setGravity(Gravity.CENTER);root.addView(label,new LinearLayout.LayoutParams(-1,Ui.dp(this,36)));
  TextView name=Ui.text(this,getIntent().getStringExtra("peer_name"),24,0xffe9edef);name.setGravity(Gravity.CENTER);root.addView(name,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout actions=Ui.horizontal(this);Button decline=Ui.button(this,"Decline"),answer=Ui.button(this,"Answer");decline.setId(android.R.id.button2);answer.setId(android.R.id.button1);decline.setTextColor(-1);answer.setTextColor(0xff0b141a);decline.setBackgroundDrawable(Ui.rounded(0xffbc3b49,24,this));answer.setBackgroundDrawable(Ui.rounded(0xff21c063,24,this));
  LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,Ui.dp(this,48),1);left.rightMargin=Ui.dp(this,8);actions.addView(decline,left);actions.addView(answer,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));root.addView(actions);setContentView(root);HardwareNavigation.decorate(root);
  decline.setOnClickListener(new View.OnClickListener(){public void onClick(View v){sendBroadcast(new Intent(IncomingCallActivity.this,DeclineCallReceiver.class).putExtra("call_id",callId));finish();}});
  answer.setOnClickListener(new View.OnClickListener(){public void onClick(View v){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(201);Intent open=new Intent(IncomingCallActivity.this,CallActivity.class).putExtras(getIntent()).putExtra("incoming",true);startActivity(open);finish();}});
 }
 @Override protected void onResume(){super.onResume();handler.post(poll);}
 @Override protected void onPause(){handler.removeCallbacks(poll);super.onPause();}
 @Override protected void onStop(){super.onStop();if(!isFinishing())finish();}
 @Override public boolean dispatchTrackballEvent(android.view.MotionEvent event){return HardwareNavigation.trackball(this,event)||super.dispatchTrackballEvent(event);}
}
