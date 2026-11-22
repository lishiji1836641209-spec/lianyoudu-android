package com.lianyoudu.app;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** R1 user-visible regression: synthetic data, isolated package and disposable emulator only. */
public final class R1Instrumentation extends Instrumentation {
  static final String PACKAGE = "com.lianyoudu.app.r1test";
  FitnessApp app;
  Activity activity;
  String scenario;
  final StringBuilder evidence = new StringBuilder();
  int checks;
  static final class RequirementFailure extends AssertionError {
    RequirementFailure(String message) { super(message); }
  }
  interface Work { void run() throws Exception; }
  void require(boolean condition,String id) {
    if(!condition) throw new RequirementFailure(id);
    checks++; evidence.append("CHECK ").append(id).append('\n');
  }
  void ui(Work work) throws Exception {
    Throwable[] problem=new Throwable[1];
    runOnMainSync(() -> { try { work.run(); } catch(Throwable e) { problem[0]=e; } });
    if(problem[0] instanceof RequirementFailure) throw (RequirementFailure)problem[0];
    if(problem[0]!=null) throw new IllegalStateException("UI action failed",problem[0]);
    waitForIdleSync(); SystemClock.sleep(120);
  }
  @Override public void onCreate(Bundle args) { super.onCreate(args);scenario=args.getString("case","");start(); }
  @Override public void onStart() {
    Bundle output=new Bundle();
    try {
      if(!getTargetContext().getPackageName().equals(PACKAGE)
          || !(Build.FINGERPRINT.contains("generic") || Build.MODEL.startsWith("sdk")))
        throw new IllegalStateException("R1 requires disposable emulator and exclusive test package");
      app=(FitnessApp)getTargetContext().getApplicationContext();
      activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync(); ui(() -> app.store.setSetting("reduceMotion","true"));
      switch(scenario) {
        case "resume_duplicate": resumeDuplicate(); break;
        case "resume_rest_duplicate": resumeRestDuplicate(); break;
        case "notify_same_pause": notifySamePause(); break;
        case "notify_stale_control": notifyStale(); break;
        case "finish_rotate_control": finishRotate(false); break;
        case "notify_pause_rotate": finishRotate(true); break;
        case "notify_interleave": notifyInterleave(); break;
        case "notify_paused_resume_control": notifyPausedResume(); break;
        case "notify_explicit_resume_control": notifyExplicitResume(); break;
        case "finish_running_control": finishControl(true); break;
        case "finish_paused_control": finishControl(false); break;
        case "finish_explicit_after_error": finishExplicitAfterError(); break;
        case "finish_recovered_storage": finishRecoveredStorage(); break;
        case "undo_conflict": undo(true); break;
        case "undo_one_ms": undoBoundary(false); break;
        case "undo_noncurrent": undoBoundary(true); break;
        case "undo_unticked": undoUnticked(); break;
        case "undo_unticked_rest_control": undoUntickedRest(); break;
        case "undo_safe_control": undo(false); break;
        case "rest_status": restStatus(); break;
        case "rest_end_control": restEnd(); break;
        case "rest_end_paused_control": restEndPaused(); break;
        case "plan_cancel": plan("cancel"); break;
        case "plan_cancel_back": plan("back"); break;
        case "plan_keep": plan("keep"); break;
        case "plan_settings_change": plan("settings"); break;
        case "plan_replace": plan("replace"); break;
        case "plan_empty_control": plan("empty"); break;
        case "plan_same_control": plan("same"); break;
        case "unfinished_warning": unfinished(true); break;
        case "unfinished_cancel_control": unfinished(false); break;
        case "unfinished_save_control": unfinishedSave(false); break;
        case "unfinished_weight_control": unfinishedWeight(); break;
        case "timed_partial_save_control": unfinishedSave(true); break;
        case "export_disclosure_cancel": exportDisclosureCancel(false); break;
        case "export_cancel_back": exportDisclosureCancel(true); break;
        case "health_privacy": healthPrivacy(); break;
        case "export_payload_control": exportPayload(); break;
        default: throw new IllegalArgumentException("Unknown R1 case: "+scenario);
      }
      output.putString("stream",evidence+"\nPASS "+scenario+" checks="+checks+"\n");
      finish(Activity.RESULT_OK,output);
    } catch(Throwable error) {
      Throwable p=error;
      while(p!=null && !(p instanceof RequirementFailure))p=p.getCause();
      String category=p==null?"HARNESS_ERROR":"PRODUCT_FAIL";
      output.putString("stream",evidence+"\n"+category+" "+scenario+": "+android.util.Log.getStackTraceString(error));
      finish(Activity.RESULT_CANCELED,output);
    }
  }
  Object field(Object target,String name) throws Exception {
    Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
  }
  void invoke(String name) throws Exception { Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity); }
  void page(int index) throws Exception {
    ui(() -> {Field f=MainActivity.class.getDeclaredField("page");f.setAccessible(true);f.setInt(activity,index);invoke("render");});
  }
  View body() throws Exception { return (View)field(activity,"body"); }
  List<View> all(View view) {
    List<View> out=new ArrayList<>();out.add(view);
    if(view instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)out.addAll(all(g.getChildAt(i)));
    return out;
  }
  View find(View root,String label) {
    for(View v:all(root)) if((v instanceof TextView t && label.contentEquals(t.getText()))
       || label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
    return null;
  }
  String text(View root) {
    StringBuilder b=new StringBuilder();for(View v:all(root))if(v instanceof TextView t)b.append(t.getText()).append('\n');return b.toString();
  }
  void click(View view,String id) throws Exception {
    if(view==null)throw new IllegalStateException("test setup could not find control: "+id);
    ui(() -> { if(!view.performClick())throw new IllegalStateException("test setup control not clickable: "+id); });
  }
  void clickBody(String label) throws Exception {click(find(body(),label),label);}
  AlertDialog dialog() throws Exception {
    List<?> dialogs=(List<?>)field(activity,"dialogs");
    for(int i=dialogs.size()-1;i>=0;i--)if(dialogs.get(i) instanceof AlertDialog d && d.isShowing())return d;
    return null;
  }
  void button(AlertDialog d,int which,String action) throws Exception {
    if(d==null)throw new IllegalStateException("Missing test dialog for "+action);
    click(d.getButton(which),action);
  }
  Domain.Exercise exercise(boolean timed) {
    Domain.Exercise e=new Domain.Exercise(timed?"plank":"squat",timed?"平板支撑":"徒手深蹲",timed?"瑜伽垫":"",timed,"");
    e.sets=2;return e;
  }
  void session(boolean timed,boolean running) throws Exception {
    ui(() -> {
      app.active=new Domain.Session("all",List.of(exercise(timed)));
      if(running)app.active.resume(SystemClock.elapsedRealtime());
      app.store.saveSession(app.active,true);
    });
    page(3);
  }
  void sendNotificationAction(String action) throws Exception {
    String id=app.active.id;
    ui(() -> activity.startService(new Intent(activity,WorkoutService.class).setAction(action).putExtra("session",id)));
    long until=SystemClock.elapsedRealtime()+4000;
    boolean expected=action.equals("resume");
    while(SystemClock.elapsedRealtime()<until) {
      boolean[] state=new boolean[1];ui(() -> state[0]=app.active!=null && app.active.running);
      if(state[0]==expected)return;SystemClock.sleep(80);
    }
    throw new IllegalStateException("Notification action not processed: "+action);
  }
  void resumeDuplicate() {
    Domain.Session s=new Domain.Session("all",List.of(exercise(true)));
    s.resume(1000);s.tick(2000);s.resume(3000);s.tick(4000);
    require(s.elapsed==3000 && s.item().currentMs==3000,"R1-01 duplicate resume keeps all 3000ms of active time; elapsed="+s.elapsed+" item="+s.item().currentMs);
  }
  void resumeRestDuplicate() {
    Domain.Session s=new Domain.Session("all",List.of(exercise(true)));
    s.rest=15000;s.resume(1000);s.tick(4000);s.resume(9000);s.tick(11000);
    require(s.elapsed==10000 && s.rest==5000 && s.item().currentMs==0,
        "R1-01 duplicate resume retains active time and rest; elapsed="+s.elapsed+" rest="+s.rest);
  }
  void notifySamePause() throws Exception {
    session(false,true);String id=app.active.id;clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");
    long revision=app.store.uiRevision;
    sendNotificationAction("pause");
    require(app.store.uiRevision>revision && !app.active.running,"same-state pause intent reached service and saved");
    ui(d::cancel);
    require(app.active!=null && app.active.id.equals(id) && !app.active.running,"R1-01 same-state notification pause is a new explicit intent");
  }
  void notifyStale() throws Exception {
    session(false,true);String id=app.active.id;clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");
    ui(() -> activity.startService(new Intent(activity,WorkoutService.class).setAction("pause").putExtra("session","stale-other-id")));
    SystemClock.sleep(350);require(!app.active.running && app.active.id.equals(id),"old session notification leaves current session unchanged");
    ui(d::cancel);require(app.active.running && app.active.id.equals(id),"R1-01 old notification must not change ordinary cancel behavior");
  }
  void recreateActivity() throws Exception {
    ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
    ui(() -> activity.recreate());
    Activity replacement=waitForMonitorWithTimeout(monitor,8000);
    removeMonitor(monitor);
    if(replacement==null || replacement==activity)throw new IllegalStateException("Activity recreation was not observed");
    activity=replacement;waitForIdleSync();SystemClock.sleep(500);
  }
  void finishRotate(boolean samePause) throws Exception {
    session(false,true);String id=app.active.id;clickBody("结束");
    if(dialog()==null)throw new IllegalStateException("finish dialog missing");
    if(samePause)sendNotificationAction("pause");
    recreateActivity();
    require(app.active!=null && app.active.id.equals(id) && !app.active.running,"R1-01 configuration recreation does not itself resume paused confirmation");
    AlertDialog recreated=dialog();
    require(recreated!=null && recreated.isShowing(),"R1-01 confirmation survives configuration recreation");
    ui(recreated::cancel);
    require(app.active.running!=samePause,"R1-01 recreation preserves "+(samePause?"same-state pause intent":"ordinary cancel behavior"));
  }
  void finishControl(boolean running) throws Exception {
    session(false,running);clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");
    require(!app.active.running,"finish control temporarily pauses");
    button(d,AlertDialog.BUTTON_NEGATIVE,"cancel finish");
    require(app.active!=null && app.active.running==running,"R1-01 ordinary finish cancel preserves pre-dialog running="+running);
  }
  void notifyInterleave() throws Exception {
    session(false,true);String id=app.active.id;clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");
    require(!app.active.running,"finish first pauses the workout");
    sendNotificationAction("resume");require(app.active.running,"notification resumes the same workout");
    sendNotificationAction("pause");require(!app.active.running,"notification subsequently pauses workout");
    ui(d::cancel);
    evidence.append("ACTUAL cancel: session_same=").append(app.active!=null && app.active.id.equals(id)).append(" running=").append(app.active!=null && app.active.running).append('\n');
    require(app.active!=null && app.active.id.equals(id) && !app.active.running,"R1-01 cancel honors latest notification pause");
  }
  void notifyPausedResume() throws Exception {
    session(false,false);String id=app.active.id;clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");sendNotificationAction("resume");ui(d::cancel);
    require(app.active!=null && app.active.id.equals(id) && app.active.running,"R1-01 notification resume survives cancel when initially paused");
  }
  void notifyExplicitResume() throws Exception {
    session(false,true);clickBody("结束");AlertDialog d=dialog();if(d==null)throw new IllegalStateException("finish dialog missing");
    sendNotificationAction("resume");sendNotificationAction("pause");
    View continueAction=find(d.getWindow().getDecorView(),"继续训练");
    if(continueAction!=null)click(continueAction,"explicit continue from confirmation");
    else {View back=find(d.getWindow().getDecorView(),"返回训练");if(back==null)throw new IllegalStateException("No return or continue action");click(back,"return from confirmation");clickBody("继续");}
    require(app.active!=null && app.active.running,"R1-01 latest explicit continue is effective after notification pause");
  }
  void finishExplicitAfterError() throws Exception {
    session(false,true);clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");
    ui(() -> app.failure="历史失败已解决");
    button(d,AlertDialog.BUTTON_NEGATIVE,"explicit continue");
    require(app.active!=null && app.active.running,"R1-01 explicit continue works after stale error");
  }
  void finishRecoveredStorage() throws Exception {
    session(false,true);String id=app.active.id;clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");
    ui(() -> {
      SQLiteDatabase db=app.store.getWritableDatabase();
      db.execSQL("CREATE TRIGGER r1_fail_save BEFORE UPDATE ON workouts BEGIN SELECT RAISE(ABORT, 'R1 synthetic write failure'); END");
      boolean failed=false;
      try {app.mutate(q -> q.resume(SystemClock.elapsedRealtime()));} catch(Exception expected) {failed=true;}
      finally {db.execSQL("DROP TRIGGER r1_fail_save");}
      require(failed && app.active!=null && app.active.id.equals(id) && !app.active.running && !app.failure.isEmpty(),
          "R1-01 injected save failure pauses same session and reports failure");
    });
    button(d,AlertDialog.BUTTON_NEGATIVE,"explicit continue after storage recovered");
    require(app.active!=null && app.active.id.equals(id) && app.active.running,
        "R1-01 explicit continue succeeds after controlled storage recovery");
  }
  void undo(boolean conflict) throws Exception {
    session(true,false);
    ui(() -> {app.active.item().logs.add(new Domain.Log(0,0,30000,false));app.active.item().currentMs=conflict?20000:0;app.active.rest=0;app.store.saveSession(app.active,true);});
    page(3);String sessionBefore=Store.session(app.active).toString();
    List<String> notices=Collections.synchronizedList(new ArrayList<>());
    getUiAutomation().setOnAccessibilityEventListener(event -> {
      for(CharSequence t:event.getText())if(t!=null)notices.add(t.toString());
    });
    View undo=find(body(),"撤销最后一组");
    if(undo!=null && undo.isEnabled() && undo.isClickable())click(undo,"撤销最后一组");
    else if(!conflict)throw new IllegalStateException("safe undo control missing or disabled");
    SystemClock.sleep(300);
    getUiAutomation().setOnAccessibilityEventListener(null);
    Domain.Session s=app.active;
    evidence.append("ACTUAL undo: running=").append(s!=null && s.running).append(" current=").append(s==null?-1:s.current)
      .append(" rest=").append(s==null?-1:s.rest).append(" logs=").append(s==null?-1:s.item().logs.size()).append(" currentMs=").append(s==null?-1:s.item().currentMs).append('\n');
    if(conflict) {
      require(s!=null && !s.running && s.item().logs.size()==1 && s.item().logs.get(0).ms==30000 && s.item().currentMs==20000 && s.rest==0 && Store.session(s).toString().equals(sessionBefore),
          "R1-02 conflicting undo has no data or state side effects");
      String screen=text(body()); AlertDialog d=dialog();if(d!=null)screen+=text(d.getWindow().getDecorView());screen+="\n"+String.join("\n",notices);
      evidence.append("UNDO_EXPLANATION_UI ").append(screen.replace('\n',' ')).append('\n');
      boolean explained=false;
      for(String line:screen.split("\n")) {
        boolean hasContext=line.contains("计时")||line.contains("本组")||line.contains("下一组")||line.contains("当前组");
        boolean hasReason=line.contains("已")||line.contains("先")||line.contains("不能")||line.contains("无法")||line.contains("不能");
        if(hasContext && hasReason)explained=true;
      }
      require(explained,"R1-02 conflicting undo explains why");
    } else require(s!=null && !s.running && s.item().logs.isEmpty() && s.item().currentMs==30000,"R1-02 undo without conflict remains available");
  }
  void undoBoundary(boolean other) throws Exception {
    session(true,false);
    ui(() -> {
      Domain.Item target=app.active.item();target.logs.add(new Domain.Log(0,0,30000,false));target.currentMs=1;
      if(other) {Domain.Exercise second=new Domain.Exercise("squat","徒手深蹲","",false,"");app.active.items.add(new Domain.Item(second));app.active.current=1;app.active.rest=12000;}
      app.store.saveSession(app.active,true);
    });
    page(3);String before=Store.session(app.active).toString();clickBody("撤销最后一组");
    evidence.append("UNDO_BEFORE ").append(before).append("\nUNDO_AFTER ").append(Store.session(app.active).toString()).append('\n');
    require(Store.session(app.active).toString().equals(before),"R1-02 "+(other?"non-current project":"one millisecond")+" undo changes no persisted session state");
  }
  void undoUnticked() throws Exception {
    session(true,false);
    ui(() -> {app.active.item().logs.add(new Domain.Log(0,0,30000,false));app.active.item().currentMs=0;app.active.rest=0;app.active.resume(SystemClock.elapsedRealtime()-700);app.store.saveSession(app.active,true);});
    page(3);clickBody("撤销最后一组");
    evidence.append("ACTUAL unticked undo: running=").append(app.active!=null && app.active.running).append(" logs=").append(app.active==null?-1:app.active.item().logs.size()).append(" currentMs=").append(app.active==null?-1:app.active.item().currentMs).append('\n');
    require(app.active!=null && app.active.running && app.active.item().logs.size()==1,
        "R1-02 unticked running time blocks undo without losing elapsed time");
    ui(() -> app.active.pause(SystemClock.elapsedRealtime()));
    require(app.active.item().currentMs>=500,"R1-02 unticked active time remains recoverable when next settled");
  }
  void undoUntickedRest() throws Exception {
    session(true,false);
    ui(() -> {app.active.item().logs.add(new Domain.Log(0,0,30000,false));app.active.item().currentMs=0;app.active.rest=60000;app.active.resume(SystemClock.elapsedRealtime()-700);app.store.saveSession(app.active,true);});
    page(3);clickBody("撤销最后一组");
    require(app.active!=null && app.active.running && app.active.item().logs.isEmpty() && app.active.item().currentMs>=30000 && app.active.item().currentMs<32000,
        "R1-02 unticked interval fully covered by rest permits ordinary undo");
  }
  void restFixture() throws Exception {
    session(true,true);ui(() -> {app.active.item().logs.add(new Domain.Log(0,0,30000,false));app.active.rest=60000;app.store.saveSession(app.active,true);});page(3);
  }
  void restStatus() throws Exception {
    restFixture();View status=find(body(),"计时中");long before=app.active.rest;
    require(text(body()).contains("休息"),"rest state is visible for status test");
    if(status!=null && status.isEnabled() && status.isClickable())click(status,"计时中");
    evidence.append("ACTUAL rest before=").append(before).append(" after=").append(app.active.rest).append('\n');
    require(app.active.rest>before-5000 && app.active.running,"R1-02 status label must not silently end rest");
  }
  void restEnd() throws Exception {restFixture();clickBody("结束休息");require(app.active.rest==0 && app.active.running,"R1-02 explicit end rest remains available and retains running");}
  void restEndPaused() throws Exception {
    session(true,false);ui(() -> {app.active.item().logs.add(new Domain.Log(0,0,30000,false));app.active.rest=60000;app.store.saveSession(app.active,true);});
    page(3);clickBody("结束休息");require(app.active.rest==0 && !app.active.running,"R1-02 explicit end rest preserves paused status");
  }
  JSONArray draft(Domain.Exercise... exercises) throws Exception {JSONArray a=new JSONArray();for(Domain.Exercise e:exercises)a.put(Store.exercise(e));return a;}
  void plan(String mode) throws Exception {
    Domain.Exercise a=exercise(false),b=new Domain.Exercise("push","俯卧撑","",false,"");b.sets=4;
    Domain.Exercise changed=a.copy();changed.sets=a.sets+1;
    String before=(mode.equals("settings")?draft(changed):draft(a,b)).toString();
    ui(() -> {app.store.plan("all",List.of(a));app.store.setSetting("workoutPreparation",mode.equals("empty")?"[]":mode.equals("same")?draft(a).toString():before);app.store.setSetting("workoutPreparation.kind","力量");});
    page(1);clickBody("按计划开始");AlertDialog d=dialog();
    if(mode.equals("empty")||mode.equals("same")) {
      require(d==null && ((Integer)field(activity,"page"))==19 && app.active==null,"R1-03 "+mode+" draft enters preparation without confirmation");return;
    }
    evidence.append("PLAN_BEFORE ").append(before).append("\nPLAN_AFTER ").append(app.store.setting("workoutPreparation","")).append(" dialog=").append(d!=null).append('\n');
    require(d!=null && app.active==null && app.store.setting("workoutPreparation","").equals(before),"R1-03 different draft requires choice before replacement");
    if(mode.equals("back")) {
      sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
      require(app.active==null && ((Integer)field(activity,"page"))==1 && app.store.setting("workoutPreparation","").equals(before) && app.store.setting("workoutPreparation.kind","").equals("力量"),"R1-03 system back preserves pending draft");
    } else if(mode.equals("cancel")) {
      View cancel=find(d.getWindow().getDecorView(),"取消");
      require(cancel!=null,"R1-03 replacement offers explicit cancel");click(cancel,"cancel replacement");
      require(app.active==null && ((Integer)field(activity,"page"))==1 && app.store.setting("workoutPreparation","").equals(before) && app.store.setting("workoutPreparation.kind","").equals("力量"),"R1-03 cancellation keeps draft items/order/sets/kind and does not start");
    } else if(mode.equals("keep")) {
      View keep=find(d.getWindow().getDecorView(),"保留待开始运动");if(keep==null)keep=find(d.getWindow().getDecorView(),"保留草稿");
      require(keep!=null,"R1-03 replacement offers explicit keep draft");click(keep,"keep draft");
      require(app.active==null && ((Integer)field(activity,"page"))==19 && app.store.setting("workoutPreparation","").equals(before),"R1-03 keep enters preparation with draft intact");
    } else if(mode.equals("settings")) {
      require(d.isShowing(),"R1-03 same ID but different sets requires choice");
    } else {
      View replacement=find(d.getWindow().getDecorView(),"使用计划替换");
      if(replacement==null) throw new IllegalStateException("missing replacement action");
      click(replacement,"replace draft");
      JSONArray after=new JSONArray(app.store.setting("workoutPreparation","[]"));
      require(app.active==null && after.length()==1 && Store.exercise(after.getJSONObject(0)).id.equals(a.id),"R1-03 explicit replacement selects plan and does not start");
    }
  }
  void unfinished(boolean warning) throws Exception {
    session(false,false);View v=find(body(),"徒手深蹲第1组次数");
    if(!(v instanceof EditText))throw new IllegalStateException("fixture input missing");
    ui(() -> ((EditText)v).setText("8"));clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");
    String message=text(d.getWindow().getDecorView());evidence.append("FINISH_DIALOG ").append(message.replace('\n',' ')).append('\n');
    if(warning)require((message.contains("输入")||message.contains("填写")||message.contains("填入"))
       && (message.contains("不保存")||message.contains("不会保存")||message.contains("丢失")||message.contains("丢弃")||message.contains("清除")||message.contains("未保存")),
       "R1-03 finish warns unfinished typed values will be discarded");
    button(d,AlertDialog.BUTTON_NEGATIVE,"return to unfinished entry");
    View returned=find(body(),"徒手深蹲第1组次数");
    require(returned instanceof EditText && ((EditText)returned).getText().toString().equals("8") && app.active.count()==0,
       "R1-03 cancel finish retains unfinished typed value without logging");
  }
  void unfinishedSave(boolean timed) throws Exception {
    session(timed,false);
    if(timed) {
      ui(() -> {app.active.item().currentMs=1500;app.store.saveSession(app.active,true);});page(3);
      View input=find(body(),"平板支撑第1组秒数");if(!(input instanceof EditText))throw new IllegalStateException("timed input missing");
      ui(() -> ((EditText)input).setText("99"));
    } else {
      ui(() -> {app.active.item().logs.add(new Domain.Log(10,0,0,false));app.store.saveSession(app.active,true);});page(3);
      View input=find(body(),"徒手深蹲第2组次数");if(!(input instanceof EditText))throw new IllegalStateException("unfinished input missing");
      ui(() -> ((EditText)input).setText("8"));
    }
    clickBody("结束");AlertDialog d=dialog();if(d==null)throw new IllegalStateException("finish dialog missing");
    button(d,AlertDialog.BUTTON_POSITIVE,"save synthetic training");
    require(app.active==null,"finish saves isolated session");Domain.Session saved=app.store.latestWorkout();
    require(saved!=null && saved.items.get(0).logs.size()==1,"saved workout contains one intended recorded group");
    Domain.Log log=saved.items.get(0).logs.get(0);
    require(timed?(log.partial && log.ms==1500):(!log.partial && log.reps==10),
        "R1-03 actual partial time retained; unfinished manual input is not a completed group");
  }
  void unfinishedWeight() throws Exception {
    ui(() -> {Domain.Exercise row=new Domain.Exercise("row","哑铃划船","哑铃",false,"每只");row.sets=2;
      app.active=new Domain.Session("all",List.of(row));app.active.item().logs.add(new Domain.Log(10,7.5,0,false));app.store.saveSession(app.active,true);});
    page(3);View reps=find(body(),"哑铃划船第2组次数"),weight=find(body(),"哑铃划船第2组重量");
    if(!(reps instanceof EditText) || !(weight instanceof EditText))throw new IllegalStateException("weighted fixture input missing");
    ui(() -> {((EditText)reps).setText("8");((EditText)weight).setText("12.5");});clickBody("结束");AlertDialog d=dialog();
    if(d==null)throw new IllegalStateException("finish dialog missing");button(d,AlertDialog.BUTTON_NEGATIVE,"return to unfinished weighted entry");
    View r=find(body(),"哑铃划船第2组次数"),w=find(body(),"哑铃划船第2组重量");
    require(r instanceof EditText && w instanceof EditText && ((EditText)r).getText().toString().equals("8") && ((EditText)w).getText().toString().equals("12.5") && app.active.item().logs.size()==1 && app.active.item().logs.get(0).weight==7.5,
        "R1-03 return preserves unfinished reps and weight and completed set");
  }
  void healthPrivacy() throws Exception {
    Activity screen=startActivitySync(new Intent(getTargetContext(),HealthPrivacyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    waitForIdleSync();String wording=text(screen.findViewById(android.R.id.content));
    evidence.append("HEALTH_PRIVACY_UI ").append(wording.replace('\n',' ')).append('\n');
    require(wording.contains("备份") && wording.contains("步数") && (wording.contains("能量")||wording.contains("热量"))
        && wording.contains("可能") && (wording.contains("已保存")||wording.contains("保存的")) && !wording.contains("备份不包含"),
        "R1-04 health privacy correctly discloses stored health summaries in manual backup");
  }
  File[] backups() {File dir=new File(activity.getCacheDir(),"shared");File[] f=dir.listFiles((d,n)->n.startsWith("backup-")&&n.endsWith(".zip"));return f==null?new File[0]:f;}
  static final class ShareGuard {
    ActivityMonitor chooser,send,multiple;
    int total() {return chooser.getHits()+send.getHits()+multiple.getHits();}
  }
  ShareGuard blockShare() throws Exception {
    ShareGuard g=new ShareGuard();
    g.chooser=addMonitor(new IntentFilter(Intent.ACTION_CHOOSER),new ActivityResult(Activity.RESULT_CANCELED,null),true);
    IntentFilter sendFilter=new IntentFilter(Intent.ACTION_SEND);sendFilter.addDataType("*/*");
    IntentFilter multipleFilter=new IntentFilter(Intent.ACTION_SEND_MULTIPLE);multipleFilter.addDataType("*/*");
    g.send=addMonitor(sendFilter,new ActivityResult(Activity.RESULT_CANCELED,null),true);
    g.multiple=addMonitor(multipleFilter,new ActivityResult(Activity.RESULT_CANCELED,null),true);
    return g;
  }
  void unblockShare(ShareGuard g) {removeMonitor(g.chooser);removeMonitor(g.send);removeMonitor(g.multiple);}
  void exportDisclosureCancel(boolean useBack) throws Exception {
    seedExport();JSONObject snapshot=app.store.exportData();snapshot.remove("exportedAt");
    ShareGuard monitor=blockShare();page(15);require(backups().length==0,"export control starts with no archive");clickBody("导出备份");SystemClock.sleep(700);
    AlertDialog d=dialog();
    evidence.append("ACTUAL export: dialog=").append(d!=null && d.isShowing()).append(" archives=").append(backups().length).append(" chooserCalls=").append(monitor.total()).append('\n');
    require(d!=null && d.isShowing() && backups().length==0 && monitor.total()==0,"R1-04 disclosure appears before archive creation or sharing");
    String msg=text(d.getWindow().getDecorView());evidence.append("EXPORT_DIALOG ").append(msg.replace('\n',' ')).append('\n');
    require(msg.contains("训练")&&msg.contains("体重")&&msg.contains("饮食")&&msg.contains("路线")&&msg.contains("照片")&&msg.contains("步数")&&(msg.contains("能量")||msg.contains("热量"))&&(msg.contains("未加密")||msg.contains("不加密")||msg.contains("没有加密"))&&msg.contains("恢复")&&(msg.contains("不支持")||msg.contains("没有")||msg.contains("无法")||msg.contains("不可")),"R1-04 disclosure covers backup contents, encryption and restore limitation");
    if(useBack) {sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();}
    else button(d,AlertDialog.BUTTON_NEGATIVE,"cancel export");SystemClock.sleep(400);
    require(backups().length==0 && monitor.total()==0,"R1-04 cancelled export creates no archive and no chooser");
    JSONObject after=app.store.exportData();after.remove("exportedAt");
    require(snapshot.toString().equals(after.toString()),"R1-04 cancel preserves all synthetic records and settings");
    unblockShare(monitor);
  }
  void seedExport() throws Exception {
    ui(() -> {
      Domain.Session q=new Domain.Session("all",List.of(exercise(false)));q.id="synthetic-workout";app.store.saveSession(q,false);
      app.store.weight(null,LocalDate.now().toString(),71.2,true);
      JSONObject f=new JSONObject().put("name","合成食物").put("grams",100).put("kcal100",123).put("kcal",123);
      app.store.meal(new JSONObject().put("id","synthetic-meal").put("date",LocalDate.now().toString()).put("slot","加餐").put("foods",new JSONArray().put(f)),List.of(),true);
      app.store.setSetting("energy.day."+LocalDate.now(),"{\"steps\":4321,\"total\":789}");
      File photo=new File(activity.getFilesDir(),"synthetic.jpg");try(FileOutputStream out=new FileOutputStream(photo)){out.write("SYNTHETIC_PHOTO".getBytes(StandardCharsets.UTF_8));}
      app.store.photo("synthetic-photo",photo.getAbsolutePath());
      ContentValues cv=new ContentValues();cv.put("workout",q.id);cv.put("lat",1.25);cv.put("lon",2.5);cv.put("accuracy",3);cv.put("wall",1000);cv.put("elapsed",500);cv.put("segment",1);
      app.store.getWritableDatabase().insertOrThrow("route_points",null,cv);
    });
  }
  void exportPayload() throws Exception {
    seedExport();ShareGuard monitor=blockShare();page(15);clickBody("导出备份");
    AlertDialog d=dialog();
    if(d!=null && d.isShowing() && !text(d.getWindow().getDecorView()).contains("正在处理"))button(d,AlertDialog.BUTTON_POSITIVE,"confirm synthetic export");
    long until=SystemClock.elapsedRealtime()+10000;
    while(SystemClock.elapsedRealtime()<until && (backups().length==0 || app.operationBusy))SystemClock.sleep(100);
    require(backups().length==1 && !app.operationBusy,"synthetic export produces one completed archive");
    File file=backups()[0];
    try(ZipFile zip=new ZipFile(file)) {
      ZipEntry records=zip.getEntry("records.json");require(records!=null,"export archive contains records.json");
      String raw=new String(zip.getInputStream(records).readAllBytes(),StandardCharsets.UTF_8);JSONObject json=new JSONObject(raw);
      require(json.getJSONArray("workouts").toString().contains("synthetic-workout"),"export contains synthetic workout");
      require(json.getJSONArray("weights").toString().contains("71.2"),"export contains synthetic weight");
      require(json.getJSONArray("meals").toString().contains("synthetic-meal"),"export contains synthetic meal");
      require(json.getJSONArray("route_points").toString().contains("synthetic-workout"),"export contains synthetic route");
      require(json.getJSONArray("settings").toString().contains("4321") && json.getJSONArray("settings").toString().contains("789"),"export contains stored synthetic steps and energy");
      ZipEntry photo=zip.getEntry("photos/synthetic-photo.jpg");require(photo!=null && new String(zip.getInputStream(photo).readAllBytes(),StandardCharsets.UTF_8).equals("SYNTHETIC_PHOTO"),"export contains synthetic old photo bytes");
    }
    long chooserUntil=SystemClock.elapsedRealtime()+3000;
    while(monitor.total()==0 && SystemClock.elapsedRealtime()<chooserUntil)SystemClock.sleep(50);
    require(monitor.chooser.getHits()==1 && monitor.send.getHits()==0 && monitor.multiple.getHits()==0,
        "synthetic export chooser is intercepted; no direct send or multiple-send occurred");
    evidence.append("SHARE_CHOOSER_INTERCEPTED ").append(monitor.chooser.getHits()).append(" DIRECT_SEND ").append(monitor.send.getHits()).append(" MULTIPLE_SEND ").append(monitor.multiple.getHits()).append('\n');unblockShare(monitor);
  }
}
