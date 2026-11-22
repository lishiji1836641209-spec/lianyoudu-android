package com.lianyoudu.app;

import java.util.*;

/** Original teaching text; functional concepts studied from openGym, no upstream code or media. */
final class ExerciseLearning {
  record Lesson(
      List<String> steps, String cue, String avoid, String breathing, List<String> alternatives) {}

  private static final Map<String, String[]> NOTES = new LinkedHashMap<>();

  static {
    NOTES.put(
        "incline_treadmill",
        new String[] {"步幅保持舒适，先走稳，再调整坡度。", "把坡度档位或速度填进坡度百分比，或在跑带还在移动时下机。", ""});
    NOTES.put(
        "supported_leg_raise",
        new String[] {"前臂和背部稳靠支撑，抬腿时呼气，放下时吸气。", "耸肩、腰背离开靠垫，或来回甩腿。", "dead_bug,crunch"});
    NOTES.put(
        "squat",
        new String[] {"脚掌均匀踩地，起身时髋和肩一起向上。", "膝盖内扣、脚跟抬起，或为蹲得更低而弓背。", "chair_squat,db_goblet_squat"});
    NOTES.put(
        "push",
        new String[] {"胸部和髋部一起下降、一起推起。", "塌腰、抬头，或只让头部靠近地面。", "incline_pushup,kneeling_pushup"});
    NOTES.put(
        "plank", new String[] {"轻收腹部和臀部，保持肩、髋、踝接近一线。", "腰部下沉、臀部过高，或憋气硬撑。", "dead_bug,bird_dog"});
    NOTES.put(
        "aerobics",
        new String[] {"先做低冲击动作，熟悉节拍后再增加幅度。", "跟不上节奏时仍勉强跳跃，落地僵硬。", "walk,stationary_bike"});
    NOTES.put(
        "stretch",
        new String[] {"每次进入伸展位置都放慢，保持轻微牵拉感。", "弹震、憋气，或把疼痛当作拉伸到位。", "child_pose,hamstring_stretch"});
    NOTES.put("row", new String[] {"肘部向髋部方向移动，肩部远离耳朵。", "借转腰甩起重量，或耸肩拉到胸口。", "cable_row,band"});
    NOTES.put("band", new String[] {"先确认弹力带完好，固定点牢靠。", "松手让弹力带弹回，或用身体后仰代替划船。", "cable_row,row"});
    NOTES.put(
        "bell", new String[] {"先练习臀部向后移动，再逐渐加重量。", "靠弯腰够壶铃，或起身后过度挺腰。", "db_rdl,glute_bridge"});
    NOTES.put(
        "bench",
        new String[] {"双脚踩地，肩背稳定，手腕尽量在前臂上方。", "杠铃砸胸反弹，或未设保护就挑战大重量。", "db_floor_press,press"});
    NOTES.put(
        "pull",
        new String[] {
          "用肘部向下带动握杆，拉到胸前舒适位置。", "颈后下拉、耸肩，或大幅前后甩动身体。", "assisted_pullup,straight_arm_pulldown"
        });
    NOTES.put(
        "press",
        new String[] {
          "先调座椅，让把手约与胸部齐平；背部贴住靠垫。", "肩部向前顶，或回程时让配重突然砸下。", "db_floor_press,incline_pushup"
        });
    NOTES.put(
        "leg",
        new String[] {"腰背和骨盆贴紧靠垫，脚掌稳放踏板。", "下放过深导致骨盆卷起，或猛力锁膝。", "chair_squat,db_goblet_squat"});
    NOTES.put("run", new String[] {"先低速步行，夹好安全夹，再逐渐提速。", "跳上高速运行的跑带，或长时间扶把跑步。", "walk,elliptical"});
    NOTES.put(
        "trainer", new String[] {"检查固定、胎压和阻力，先轻松踩踏。", "未确认车辆锁紧便开始大力骑行。", "stationary_bike,bike"});
    NOTES.put(
        "bike",
        new String[] {"戴好头盔，检查刹车和轮胎；停车后再操作。", "边骑行边看手机，或忽略道路安全。", "trainer,stationary_bike"});
    NOTES.put("walk", new String[] {"步幅自然，肩部放松，前方道路优先。", "刻意跨大步，或走路时持续低头操作手机。", "run,elliptical"});
    NOTES.put(
        "reverse_lunge",
        new String[] {"前脚保持踩稳，起身时以该侧腿发力。", "前膝内扣，或后撤步太短导致失去平衡。", "db_split_squat,chair_squat"});
    NOTES.put("glute_bridge", new String[] {"用臀部抬髋，在肩髋膝接近一线处停下。", "用腰向上顶，或脚离臀部太远。", "bell,db_rdl"});
    NOTES.put(
        "calf_raise", new String[] {"抬起和落下都放慢，保持脚踝稳定。", "借弹跳提踵，或脚踝向内外翻。", "seated_calf_raise"});
    NOTES.put(
        "wall_sit", new String[] {"选择能稳定保持的高度，不必勉强蹲到水平。", "膝盖内扣，或靠憋气延长时间。", "chair_squat,squat"});
    NOTES.put("dead_bug", new String[] {"手脚伸展幅度以腰背仍稳定为限。", "腰部离地明显增大，或快速甩动手脚。", "bird_dog,plank"});
    NOTES.put("bird_dog", new String[] {"伸远手和脚，躯干和骨盆尽量不旋转。", "把腿抬得过高导致塌腰。", "dead_bug,plank"});
    NOTES.put(
        "side_plank", new String[] {"支撑肘在肩下，主动抬起髋部。", "肩部耸起，或髋部向前后翻转。", "dead_bug,heel_touch"});
    NOTES.put(
        "mountain_climber",
        new String[] {"先慢速交替提膝，再按稳定程度加速。", "臀部上下弹跳，或肩部退到双手后方。", "dead_bug,plank"});
    NOTES.put(
        "db_goblet_squat", new String[] {"哑铃贴近胸前，手肘自然向下。", "哑铃离身体太远，或脚跟抬起。", "squat,chair_squat"});
    NOTES.put(
        "db_rdl", new String[] {"膝角变化不大，重点是髋部向后移动。", "把硬拉做成深蹲，或弓背追求更低位置。", "bell,barbell_rdl"});
    NOTES.put(
        "db_split_squat",
        new String[] {"前后脚保留横向间距，下降时保持稳定。", "双脚排成一条直线导致左右摇晃。", "reverse_lunge,lateral_lunge"});
    NOTES.put(
        "step_up",
        new String[] {"整只脚踩上稳固踏台，由台上的腿带动起身。", "后脚大力蹬地借力，或使用会滑动的台面。", "chair_squat,reverse_lunge"});
    NOTES.put(
        "prone_leg_curl",
        new String[] {"调整器械转轴与膝部位置，保持臀部贴垫。", "甩起配重，或膝部位置与器械转轴明显错开。", "db_rdl,glute_bridge"});
    NOTES.put(
        "leg_extension",
        new String[] {"调整靠背和腿垫，缓慢伸膝再控制回落。", "快速踢起配重，或膝部不适时继续加重。", "chair_squat,leg"});
    NOTES.put(
        "hip_abduction",
        new String[] {"躯干贴稳靠背，在可控制的幅度打开双腿。", "前后甩动上身，或用反弹带动配重。", "lateral_lunge,glute_bridge"});
    NOTES.put(
        "back_extension",
        new String[] {"保持背部自然弧度，抬起至与腿部接近一线。", "抬得过高而挤压腰部，或反复猛甩躯干。", "bird_dog,db_rdl"});
    NOTES.put(
        "db_shoulder_press",
        new String[] {
          "手腕在前臂上方，推起时保持腹部稳定。", "过度挺腰，或为了举高而耸肩挤压颈部。", "db_front_raise,db_lateral_raise"
        });
    NOTES.put(
        "db_lateral_raise",
        new String[] {"肘部微屈，手臂抬到约肩高即可。", "摆腰借力，或耸肩把哑铃甩起来。", "db_front_raise,db_reverse_fly"});
    NOTES.put("db_curl", new String[] {"上臂靠近体侧，回程时也控制重量。", "前后摆动躯干，或手腕大幅折弯。", "hammer_curl"});
    NOTES.put(
        "db_triceps_extension",
        new String[] {"肘部朝前上方，选择能稳定控制的重量。", "过度挺腰，或肘部向外大幅散开。", "kneeling_pushup,db_floor_press"});
    NOTES.put(
        "db_floor_press",
        new String[] {"肩背贴地，前臂尽量竖直，上臂轻触地面。", "手肘砸地反弹，或手腕后折。", "press,incline_pushup"});
    NOTES.put("cable_row", new String[] {"先坐稳、保持背部自然伸直，再把肘部向后拉。", "大幅后仰带动把手，或耸肩缩颈。", "row,band"});
    NOTES.put(
        "cable_face_pull",
        new String[] {"绳索向面部两侧拉开，肩部保持放松。", "用腰向后甩，或把重量拉向下巴。", "db_reverse_fly,band"});
    NOTES.put(
        "assisted_pullup",
        new String[] {"先选择足够辅助量，控制下放。", "把辅助重量当作举起重量，或身体大幅摆动。", "pull,straight_arm_pulldown"});
    NOTES.put(
        "elliptical",
        new String[] {"脚掌贴踏板，躯干自然直立。", "上身趴在扶手上，或阻力过大导致动作失控。", "walk,stationary_bike"});
    NOTES.put(
        "stationary_bike",
        new String[] {"调整座高，使踏到低点时膝盖仍略屈。", "座位过低，或阻力过大导致骨盆左右摇晃。", "trainer,elliptical"});
    NOTES.put(
        "jump_rope", new String[] {"用手腕带动绳子，小幅起跳、轻缓落地。", "甩动整条手臂，或为了过绳跳得很高。", "aerobics,walk"});
    NOTES.put(
        "cat_cow", new String[] {"让脊柱缓慢屈伸，不追求极限幅度。", "猛抬头后仰，或快速甩动腰背。", "child_pose,bird_dog"});
    NOTES.put(
        "hamstring_stretch",
        new String[] {"支撑在大腿后侧，保持膝部舒适。", "双手抓在膝关节上用力拉，或抬腿时腰背明显离地。", "calf_stretch,child_pose"});
    NOTES.put(
        "hip_flexor_stretch",
        new String[] {"收紧臀部、轻收小腹，腰部不后仰，再缓慢前移。", "只靠挺腰制造伸展感。", "child_pose,glute_bridge"});
    NOTES.put(
        "chest_stretch",
        new String[] {"前臂轻靠门框，转动躯干到轻微牵拉即可。", "把肩关节硬推到极限，或出现刺痛仍坚持。", "child_pose,cat_cow"});
    NOTES.put(
        "calf_stretch", new String[] {"后脚跟踩地，脚尖大致朝前。", "后脚外翻、脚跟抬起，或弹震拉伸。", "hamstring_stretch"});
    NOTES.put(
        "incline_pushup",
        new String[] {"选择不会滑动的台面，越高通常越容易。", "台面不稳，或腰部下塌。", "kneeling_pushup,push"});
    NOTES.put(
        "kneeling_pushup",
        new String[] {"肩、髋、膝保持一线，胸部随身体下降。", "臀部留在后方，只弯曲手肘。", "incline_pushup,push"});
    NOTES.put(
        "chair_squat", new String[] {"控制落座，不要跌坐；起身时脚掌踩稳。", "椅子滑动，或借前后猛摆起身。", "squat,wall_sit"});
    NOTES.put(
        "lateral_lunge",
        new String[] {"屈膝侧的脚掌保持着地，臀部向后坐。", "膝盖向内倒，或迈步过大失去平衡。", "reverse_lunge,db_split_squat"});
    NOTES.put("barbell_rdl", new String[] {"杠铃贴近腿部，背部保持自然姿态。", "弓背够地，或站起后过度向后挺腰。", "db_rdl,bell"});
    NOTES.put("barbell_row", new String[] {"保持上身角度不变，把杠铃拉向腹部。", "每次都用起身的惯性甩起杠铃。", "row,cable_row"});
    NOTES.put(
        "cable_fly",
        new String[] {"双侧肘角基本不变，手在胸前合拢。", "变成屈伸肘推举，或过度向后打开肩部。", "press,db_floor_press"});
    NOTES.put(
        "straight_arm_pulldown",
        new String[] {"保持肘部微屈，用上臂向下带动横杆。", "反复屈伸手肘，或挺腰压杆。", "pull,assisted_pullup"});
    NOTES.put("hammer_curl", new String[] {"掌心保持相对，上臂不随重量前后摆动。", "腰部后仰借力，或握得过紧导致手腕折弯。", "db_curl"});
    NOTES.put(
        "db_front_raise",
        new String[] {"选择轻重量，抬至肩高即可。", "耸肩或后仰来完成最后几次。", "db_lateral_raise,db_shoulder_press"});
    NOTES.put(
        "db_reverse_fly",
        new String[] {"保持髋部后移，用肩后侧控制展开。", "用惯性甩臂，或背部塌陷。", "cable_face_pull,band"});
    NOTES.put(
        "db_shrug",
        new String[] {"肩部向上再放下，颈部保持自然。", "绕肩画大圈，或弯肘提起哑铃。", "cable_face_pull,db_reverse_fly"});
    NOTES.put(
        "seated_calf_raise",
        new String[] {"哑铃固定在腿上，脚踝缓慢上下移动。", "重量直接压在膝盖骨上，或脚踝向两侧倒。", "calf_raise"});
    NOTES.put(
        "crunch", new String[] {"抬起肩部即可，腰背保持稳定。", "拉扯颈部，或把卷腹做成快速仰卧起坐。", "dead_bug,heel_touch"});
    NOTES.put(
        "heel_touch", new String[] {"小幅侧屈，左右交替，不必勉强摸到脚跟。", "头颈前伸用力，或腰部大幅扭转。", "crunch,dead_bug"});
    NOTES.put(
        "child_pose",
        new String[] {"臀部自然后坐，在舒适范围延展背部。", "强压臀部到脚跟，或膝部不适仍保持。", "cat_cow,hamstring_stretch"});
  }

  static Lesson lesson(Domain.Exercise e) {
    String[] notes = NOTES.get(e.id);
    List<String> steps =
        Arrays.stream(basic(e).split("[。；]"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toList());
    if (notes == null)
      return new Lesson(steps, "按熟悉的动作要求完成训练。", "尚未收录此动作的专属教学。", "保持自然呼吸。", List.of());
    ExerciseCatalog.Entry metadata = ExerciseCatalog.entry(e);
    boolean relaxed = e.timed || (metadata != null && metadata.category().equals("拉伸"));
    return new Lesson(
        steps,
        notes[0],
        notes[1],
        relaxed ? "保持自然呼吸，不要屏气。" : "用力时呼气，还原时吸气；避免长时间屏气。",
        notes[2].isEmpty() ? List.of() : List.of(notes[2].split(",")));
  }

  static List<Domain.Exercise> alternatives(Domain.Exercise e) {
    List<String> ids = lesson(e).alternatives();
    return Domain.catalog("all", Set.of()).stream()
        .filter(x -> ids.contains(x.id) && !x.id.equals(e.id) && !relation(e.id, x.id).isEmpty())
        .collect(java.util.stream.Collectors.toList());
  }

  // Reviewed movement-pattern pairs only. Similar muscle groups alone do not imply equivalence.
  static String relation(String from, String to) {
    String pair = from + ":" + to;
    return switch (pair) {
      case "squat:chair_squat", "chair_squat:squat" ->
          "同为深蹲；徒手深蹲无需器材，坐椅深蹲借助椅面坐下再站起。";
      case "squat:db_goblet_squat", "db_goblet_squat:squat" ->
          "同为深蹲；徒手深蹲无需器材，哑铃高脚杯深蹲在胸前持哑铃。";
      case "db_goblet_squat:chair_squat" ->
          "同为深蹲；哑铃高脚杯深蹲在胸前持哑铃，坐椅深蹲借助椅面坐下再站起。";
      case "push:incline_pushup", "incline_pushup:push" ->
          "同为俯卧撑；普通俯卧撑用手掌和脚尖支撑，上斜俯卧撑将双手放在稳固高台上。";
      case "push:kneeling_pushup", "kneeling_pushup:push" ->
          "同为俯卧撑；普通俯卧撑用手掌和脚尖支撑，跪姿俯卧撑以双手和双膝支撑。";
      case "incline_pushup:kneeling_pushup", "kneeling_pushup:incline_pushup" ->
          "同为俯卧撑；上斜俯卧撑将双手放在稳固高台上，跪姿俯卧撑以双手和双膝支撑。";
      case "row:cable_row", "cable_row:row" ->
          "同为划船；哑铃划船使用哑铃，坐姿绳索划船使用绳索器械。";
      case "row:band", "band:row" ->
          "同为划船；哑铃划船使用哑铃，弹力带划船使用弹力带。";
      case "band:cable_row", "cable_row:band" ->
          "同为坐姿划船；弹力带划船使用弹力带，坐姿绳索划船使用绳索器械。";
      case "barbell_row:row" -> "同为划船；杠铃俯身划船使用杠铃，哑铃划船使用哑铃。";
      case "barbell_row:cable_row" ->
          "同为划船；杠铃俯身划船站姿持杠铃，坐姿绳索划船使用绳索器械。";
      case "bench:db_floor_press" ->
          "同为水平推举；杠铃卧推在训练凳上持杠铃，哑铃地板卧推在地板上持哑铃。";
      case "bench:press" -> "同为水平推举；杠铃卧推使用杠铃和训练凳，器械推胸使用推胸器械。";
      case "press:db_floor_press", "db_floor_press:press" ->
          "同为水平推举；器械推胸使用推胸器械，哑铃地板卧推在地板上持哑铃。";
      case "pull:assisted_pullup", "assisted_pullup:pull" ->
          "同为垂直拉动作：高位下拉拉动握杆，辅助引体向上则拉起身体。";
      case "db_rdl:barbell_rdl", "barbell_rdl:db_rdl" ->
          "同为罗马尼亚硬拉；哑铃罗马尼亚硬拉持哑铃，杠铃罗马尼亚硬拉持杠铃。";
      case "db_curl:hammer_curl", "hammer_curl:db_curl" ->
          "都用哑铃屈肘；锤式弯举以双掌相对的握法完成。";
      case "calf_raise:seated_calf_raise", "seated_calf_raise:calf_raise" ->
          "同为提踵；哑铃站姿提踵站立完成，哑铃坐姿提踵坐姿完成。";
      case "trainer:bike", "bike:trainer" ->
          "同为踩踏骑行；骑行台在室内固定自行车，骑行在户外进行。";
      case "trainer:stationary_bike", "stationary_bike:trainer" ->
          "同为室内踩踏；骑行台固定自行车，动感单车为固定式单车。";
      case "bike:stationary_bike" ->
          "同为踩踏骑行；骑行在户外使用自行车，动感单车在室内使用固定式单车。";
      case "dead_bug:bird_dog", "bird_dog:dead_bug" ->
          "都交替伸展对侧手脚；死虫式仰卧，鸟狗式用手和膝支撑，均可在垫上练习。";
      case "reverse_lunge:db_split_squat", "db_split_squat:reverse_lunge" ->
          "都有前后分腿屈膝动作；后撤箭步蹲向后迈步，哑铃分腿蹲在前后站姿持哑铃下蹲。";
      case "bell:db_rdl", "db_rdl:bell" ->
          "都以臀部后移后起身；壶铃硬拉从地面拿起壶铃，哑铃罗马尼亚硬拉持哑铃沿腿下放。";
      case "barbell_rdl:bell" ->
          "都以臀部后移后起身；壶铃硬拉从地面拿起壶铃，杠铃罗马尼亚硬拉持杠铃沿腿下放。";
      case "db_lateral_raise:db_front_raise", "db_front_raise:db_lateral_raise" ->
          "两者都用哑铃抬臂至接近肩高；前平举向前抬，侧平举向身体两侧抬。";
      case "pull:straight_arm_pulldown", "straight_arm_pulldown:pull" ->
          "都将高位握杆向下拉；高位下拉在下拉机上拉向胸前，直臂下拉在龙门架上保持肘部微屈并下压至大腿前。";
      case "back_extension:db_rdl" ->
          "都有屈髋下放后抬起的动作；罗马椅挺身使用罗马椅支撑，哑铃罗马尼亚硬拉站立并持哑铃。";
      case "mountain_climber:plank" ->
          "登山跑从双手撑地的高位平板开始并交替提膝；平板支撑以肘部支撑并保持，均可在垫上进行。";
      default -> "";
    };
  }

  static String basic(Domain.Exercise e) {
    ExerciseCatalog.Entry metadata = ExerciseCatalog.entry(e);
    if (metadata != null && !metadata.guidance().isEmpty()) return metadata.guidance();
    return switch (e.id) {
      case "squat" -> "双脚约与肩同宽，脚掌踩稳。臀部向后、屈膝下蹲，膝盖与脚尖朝同一方向。蹲到能保持稳定的深度，再踩地站起。";
      case "push" -> "双手略宽于肩，手掌和脚尖支撑身体，收紧腹部。屈肘降低胸部，头、背、髋保持一线。双手推地回到起始姿势。";
      case "aerobics" -> "先以轻松步伐热身，再逐渐加快节奏。落地轻缓，按体力调整幅度。";
      case "stretch" -> "坐在垫上，一腿伸直，另一腿自然弯曲。背部保持舒展，从髋部缓慢前倾，到大腿后侧有轻微牵拉感即可。保持自然呼吸，慢慢起身后换另一侧。";
      case "plank" -> "肘部位于肩下，腹部收紧。头、背和髋部保持一线，避免塌腰。";
      case "row" -> "一手支撑，背部保持稳定。将哑铃拉向髋部附近，控制下放。";
      case "band" -> "坐稳，弹力带绕过穿鞋的双脚中部，确认不会滑脱。握住两端，把肘部向后拉到身体两侧，背部不后仰。缓慢伸回手臂，保持弹力带受力。";
      case "bell" -> "壶铃放在双脚之间，臀部向后移、膝盖微屈，双手握住把手。背部保持自然姿态，脚掌踩地站起。臀部再次向后移，控制壶铃落回地面。";
      case "bench" ->
          "仰卧在训练凳上，双脚踩地、肩背稳贴凳面，先确认保护装置。双手稳握杠铃，缓慢降到胸前舒适位置，不砸胸。向上推起并保持手腕稳定；无法安全起落杠铃时请人协助。";
      case "pull" -> "调整坐垫并固定大腿。将握杆拉到上胸前方，肩部远离耳朵，身体不过度后仰。缓慢伸回手臂，握杆始终留在头部前方。";
      case "press" -> "调整座椅，使把手约与胸部齐平。肩背贴靠垫，平稳推起并控制回程。";
      case "leg" -> "腰背贴紧靠垫，双脚稳放踏板。膝盖朝向脚尖，避免过度屈膝或锁死。";
      case "run" -> "夹好安全夹，先走后跑。距离以跑步机读数填写，速度由距离和运动时间计算。";
      case "trainer" -> "检查车辆固定和阻力设置。距离以骑行台读数填写，室内不使用 GPS。";
      case "bike" -> "佩戴头盔，出发前检查车辆，停车后再操作手机。";
      case "walk" -> "自然摆臂，保持舒适步幅，按实际距离填写。";
      default -> "自定义运动尚无专属示意图。请按照熟悉的动作或专业指导训练。";
    };
  }
}
