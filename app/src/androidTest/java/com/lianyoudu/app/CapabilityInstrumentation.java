package com.lianyoudu.app;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

/** Standalone, destructive fixtures: dedicated lab package AND disposable emulator only. */
public final class CapabilityInstrumentation extends Instrumentation {
  Activity activity;
  FitnessApp app;
  String mode;
  int checks;
  final StringBuilder evidence = new StringBuilder();
  @Override public void onCreate(Bundle args) {
    super.onCreate(args); mode = args.getString("case", "all"); start();
  }
  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      if (!getTargetContext().getPackageName().equals("com.lianyoudu.app.mutationtest")
          || !(Build.FINGERPRINT.contains("generic") || Build.MODEL.startsWith("sdk")))
        throw new IllegalStateException("Dedicated emulator and mutationtest package required");
      app = (FitnessApp)getTargetContext().getApplicationContext();
      activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      onUi(() -> { app.store.setSetting("reduceMotion", "true"); app.store.setSetting("healthPins", "energy,food"); });
      if (mode.equals("food") || mode.equals("all")) food();
      if (mode.equals("device") || mode.equals("all")) devices();
      if (mode.equals("bodyweight") || mode.equals("all")) bodyweight();
      result.putString("stream", evidence + "\nPASS capability " + mode + " checks=" + checks + "\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("stream", evidence + "\nFAIL capability " + mode + ": " + android.util.Log.getStackTraceString(error));
      finish(Activity.RESULT_CANCELED, result);
    }
  }
  interface Work { void run() throws Exception; }
  void onUi(Work work) throws Exception {
    Throwable[] failure = new Throwable[1];
    runOnMainSync(() -> { try { work.run(); } catch(Throwable e) { failure[0]=e; } });
    if(failure[0]!=null) throw new AssertionError("UI operation failed",failure[0]);
    waitForIdleSync(); SystemClock.sleep(100);
  }
  void check(boolean condition,String message) {
    if(!condition) throw new AssertionError(message);
    checks++; evidence.append("OK ").append(message).append('\n');
  }
  Object field(Object owner,String name) throws Exception {
    Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
  }
  void call(String name) throws Exception {
    Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);
  }
  void page(int number) throws Exception {
    onUi(() -> { Field f=MainActivity.class.getDeclaredField("page");f.setAccessible(true);f.setInt(activity,number);call("render"); });
  }
  View body() throws Exception { return (View)field(activity,"body"); }
  List<View> views(View v) {
    List<View> out=new ArrayList<>();out.add(v);
    if(v instanceof ViewGroup g) for(int i=0;i<g.getChildCount();i++)out.addAll(views(g.getChildAt(i)));
    return out;
  }
  List<TextView> texts(View root,String text) {
    List<TextView> out=new ArrayList<>();
    for(View v:views(root))if(v instanceof TextView t && t.getText().toString().equals(text))out.add(t);
    return out;
  }
  View card(String heading) throws Exception {
    ViewGroup root=(ViewGroup)body();
    for(int i=0;i<root.getChildCount();i++) {
      View c=root.getChildAt(i);
      if(c instanceof ViewGroup && !texts(c,heading).isEmpty())return c;
    }
    throw new AssertionError("Missing card "+heading);
  }
  void visibleText(View root,String expected,String forbidden,String context) throws Exception {
    List<TextView> found=texts(root,expected);
    check(found.size()==1,context+" actual text="+expected+" count="+found.size());
    TextView text=found.get(0);
    onUi(() -> text.requestRectangleOnScreen(new Rect(0,0,text.getWidth(),text.getHeight()),true));
    check(text.isShown() && text.getWidth()>0 && text.getGlobalVisibleRect(new Rect()),context+" text rendered and visible");
    check(texts(root,forbidden).isEmpty(),context+" forbids contradictory text="+forbidden);
  }
  void foodPages(String value,String state) throws Exception {
    String metric=value+" 千卡", bad=value.equals("—")?"0 千卡":"— 千卡";
    page(0);
    visibleText(card("每日能量"),"饮食摄入 "+(value.equals("—")?"—":metric),
      "饮食摄入 "+(value.equals("—")?"0 千卡":"—"),state+" home energy");
    visibleText(card("饮食能量"),metric,bad,state+" home food");
    page(9);
    // Summary card is identified by its metric plus date, not by the calculation method under test.
    ViewGroup root=(ViewGroup)body();View nutrition=null;
    for(int i=0;i<root.getChildCount();i++) {
      View candidate=root.getChildAt(i);
      if(candidate instanceof ViewGroup && !texts(candidate,"今天 · "+LocalDate.now()).isEmpty())nutrition=candidate;
    }
    check(nutrition!=null,state+" nutrition summary exists");
    View nutritionMetric = null;
    ViewGroup summary = (ViewGroup)nutrition;
    for(int i=0;i<summary.getChildCount();i++) {
      View child=summary.getChildAt(i);
      if(child instanceof TextView t && t.getText().toString().matches("(?:—|[0-9,]+) 千卡")) {
        check(nutritionMetric==null,state+" exactly one summary metric"); nutritionMetric=child;
      }
    }
    check(nutritionMetric!=null,state+" top-level nutrition total exists");
    visibleText(nutritionMetric,metric,bad,state+" nutrition");
    if(state.equals("empty") || state.equals("deleted")) check(!texts(nutrition,"今天还没有饮食记录").isEmpty(),state+" empty explanation");
    if(state.equals("unknown")) check(!texts(nutrition,"1 项食物未计入热量").isEmpty(),"unknown explanation");
    page(18);visibleText(card("饮食摄入"),metric,bad,state+" daily energy");
    page(17);page(0);
    visibleText(card("饮食能量"),metric,bad,state+" cached return");
  }
  void meal(String kind) throws Exception {
    onUi(() -> {
      for(JSONObject m:app.store.meals())app.store.removeMeal(m.getString("id"));
      if(kind.equals("empty"))return;
      JSONObject f=new JSONObject().put("name","检错食物").put("grams",100);
      if(!kind.equals("unknown"))f.put("kcal",kind.equals("zero")?0:123).put("kcal100",kind.equals("zero")?0:123);
      JSONObject m=new JSONObject().put("id","capability-meal").put("date",LocalDate.now().toString()).put("slot","加餐").put("foods",new JSONArray().put(f));
      app.store.meal(m,List.of(),true);
    });
  }
  void food() throws Exception {
    meal("empty");foodPages("—","empty");
    meal("zero");foodPages("0","zero");
    meal("unknown");foodPages("—","unknown");
    meal("known");foodPages("123","known");
    meal("empty");foodPages("—","deleted");
  }
  void devices() throws Exception {
    String a="00:11:22:33:44:55",b="00:11:22:33:44:66";
    BluetoothAdapter adapter=app.getSystemService(BluetoothManager.class).getAdapter();
    check(adapter!=null,"Bluetooth emulator fixture available");
    check(app.ble.permitted(),"nearby device permissions granted for the fixture");
    onUi(() -> {
      app.store.setSetting("heart.device.address",a);app.store.setSetting("heart.device.name","Remembered A");
      app.ble.devices.clear();app.ble.devices.put(a,adapter.getRemoteDevice(a));app.ble.devices.put(b,adapter.getRemoteDevice(b));
      ((Map<String,String>)field(app.ble,"advertisedNames")).put(b,"Nearby B");
      app.ble.status="请选择设备";
    });
    page(6);
    ViewGroup rows=(ViewGroup)field(activity,"deviceRows");
    check(rows.getChildCount()==1,"nearby count is one (necessary, insufficient alone)");
    check(texts(body(),"Remembered A").size()==1,"remembered A appears exactly once across page");
    visibleText(rows,"Nearby B","Remembered A","nearby B identity");
    onUi(() -> {
      View row = rows.getChildAt(0);
      View primary = texts(row, "Nearby B").get(0);
      while (primary != row && !primary.isClickable()) primary = (View)primary.getParent();
      check(primary != row && primary.isEnabled() && primary.isShown(),
          "B has a nested main connection control separate from device information");
      try {
        check(primary.performClick(), "B main connection control dispatched its action");
        check(b.equals(app.ble.targetAddress()), "B click targets B address, not A");
      } finally {
        app.ble.close();
      }
    });
  }
  void assertInputs(String stage) throws Exception {
    page(3);
    for(String name:List.of("徒手深蹲","哑铃划船")) {
      View c=card(name);boolean weighted=name.equals("哑铃划船");
      List<EditText> inputs=new ArrayList<>();
      for(View v:views(c))if(v instanceof EditText e && e.getVisibility()==View.VISIBLE)inputs.add(e);
      check(inputs.size()==(weighted?2:1),stage+" "+name+" input count="+inputs.size());
      check(texts(c,"重量 / 千克").size()==(weighted?1:0),stage+" "+name+" weight header ownership");
      check(!texts(c,"次数").isEmpty(),stage+" "+name+" rep unit");
      for(EditText e:inputs)check(e.isEnabled(),stage+" "+name+" next-set input editable");
      EditText reps=inputs.get(inputs.size()-1);
      onUi(() -> reps.setText("15"));
    }
  }
  void bodyweight() throws Exception {
    onUi(() -> {
      Domain.Exercise squat=new Domain.Exercise("squat","徒手深蹲","",false,"");squat.sets=1;
      Domain.Exercise row=new Domain.Exercise("row","哑铃划船","哑铃",false,"每只");row.sets=1;
      app.active=new Domain.Session("all",List.of(squat,row));
      app.store.saveSession(app.active,true);
    });
    assertInputs("initial paused mixed session");
    page(17);assertInputs("return to session");
    onUi(() -> { app.store.saveSession(app.active,true);app.active=app.store.active(); });
    assertInputs("storage reload");
    View c=card("徒手深蹲");View complete=null;
    for(View v:views(c))if("完成徒手深蹲第1组".contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))complete=v;
    check(complete!=null,"bodyweight completion control exists");
    View button=complete;
    onUi(() -> button.performClick());
    check(app.active.items.get(0).logs.size()==1 && app.active.items.get(0).logs.get(0).reps==15 && app.active.items.get(0).logs.get(0).weight==0,
      "bodyweight entered reps persist without load");
  }
}
