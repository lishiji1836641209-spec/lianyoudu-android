package com.lianyoudu.app;

import java.text.Normalizer;
import java.util.*;

/** Stable IDs link guides, discovery and record semantics. No persisted plan is rewritten. */
final class ExerciseCatalog {
  record Entry(
      String id,
      String name,
      String gear,
      String unit,
      String category,
      String muscles,
      String aliases,
      boolean timed,
      boolean perSide,
      String guidance) {
    Domain.Exercise exercise() {
      Domain.Exercise e = new Domain.Exercise(id, name, gear, timed, unit);
      if (e.distanceBased()) e.sets = 1;
      if (timed && perSide) e.sets = 2;
      if (category.equals("拉伸") || id.equals("side_plank") || id.equals("wall_sit")) e.seconds = 30;
      return e;
    }
  }

  static final String[] CATEGORIES = {"全部", "收藏", "力量", "有氧", "核心", "拉伸"};
  static final String[] MUSCLES = {"全部部位", "腿臀", "胸", "背", "肩", "手臂", "核心", "全身"};
  static final String[] EQUIPMENT = {"全部器材", "徒手", "哑铃", "弹力带", "壶铃", "杠铃", "固定器械", "自行车"};
  private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>();

  static {
    old("squat", "力量", "腿臀", "深蹲 下肢 股四头肌");
    old("push", "力量", "胸 手臂 核心", "俯卧撑 胸肌 肱三头肌");
    old("aerobics", "有氧", "全身", "健身操 跳操");
    old("stretch", "拉伸", "全身", "柔韧 放松");
    old("plank", "核心", "核心", "腹肌 平板支撑");
    old("row", "力量", "背 手臂", "划船 背阔肌");
    old("band", "力量", "背 手臂", "弹力带 阻力带");
    old("bell", "力量", "腿臀 背", "硬拉 臀肌");
    old("bench", "力量", "胸 手臂", "杠铃 胸肌 卧推");
    old("pull", "力量", "背 手臂", "背阔肌 下拉");
    old("press", "力量", "胸 手臂", "胸肌 推胸");
    old("leg", "力量", "腿臀", "倒蹬 股四头肌");
    old("run", "有氧", "全身 腿臀", "室内 跑步 跑步机");
    old("trainer", "有氧", "腿臀", "室内 骑车 自行车");
    old("bike", "有氧", "腿臀", "室外 户外 骑车 自行车 公路车");
    old("walk", "有氧", "全身 腿臀", "室外 散步 快走 步行");
    add(
        "incline_treadmill", "跑步机爬坡", "跑步机", "", "有氧", "腿臀", "坡度 爬坡 跑步机", true, false,
        "先确认跑带停稳，站上跑带并夹好安全夹。以低速平地走起，步态稳定后在面板上逐步调高坡度。"
            + "结束时减速，等跑带停稳再下机。在训练的「填写本次数据」中按面板读数填写距离和坡度百分比。");
    add("stair_climb", "爬楼", "", "", "有氧", "腿臀", "台阶 爬楼 梯级", true, false, "按实际完成的台阶级数记录。" );
    add(
        "reverse_lunge",
        "后撤箭步蹲",
        "",
        "",
        "力量",
        "腿臀",
        "弓步蹲 弓箭步",
        false,
        true,
        "双脚与髋同宽，一脚向后迈出，屈膝下蹲。前脚踩稳，蹬地回到站姿，左右交替。");
    add(
        "glute_bridge",
        "臀桥",
        "瑜伽垫",
        "",
        "力量",
        "腿臀",
        "臀肌 桥式",
        false,
        false,
        "仰卧屈膝，双脚踩地。收紧臀部抬髋至肩、髋、膝接近一线，再缓慢落下，避免腰部过度后仰。");
    add(
        "calf_raise",
        "哑铃站姿提踵",
        "哑铃",
        "每只",
        "力量",
        "腿臀",
        "小腿 提踵",
        false,
        false,
        "双手持哑铃垂于体侧，脚掌稳定着地。缓慢抬起脚跟，稍停后控制落下；初练可不持哑铃。");
    add(
        "wall_sit",
        "靠墙静蹲",
        "",
        "",
        "力量",
        "腿臀",
        "靠墙蹲 静力",
        true,
        false,
        "背部贴墙，双脚向前移。缓慢下滑到可稳定保持的深度，膝盖朝向脚尖，自然呼吸。");
    add(
        "dead_bug",
        "死虫式",
        "瑜伽垫",
        "",
        "核心",
        "核心",
        "腹肌 dead bug",
        false,
        true,
        "仰卧抬起双臂和屈膝的双腿。收紧腹部，缓慢伸展对侧手脚，再回到起始位换边，腰背保持稳定。");
    add(
        "bird_dog",
        "鸟狗式",
        "瑜伽垫",
        "",
        "核心",
        "核心 背",
        "四点支撑 bird dog",
        false,
        true,
        "四点支撑，双手位于肩下、膝盖位于髋下。伸直对侧手脚，骨盆保持平稳，收回后换边。");
    add(
        "side_plank",
        "侧平板支撑",
        "瑜伽垫",
        "",
        "核心",
        "核心",
        "侧桥 腹斜肌",
        true,
        true,
        "侧卧，用一侧前臂和下方脚的外侧支撑。肘部在肩下，抬髋保持身体一线；需要降低难度时可屈膝支撑。");
    add(
        "mountain_climber",
        "登山跑",
        "瑜伽垫",
        "",
        "核心",
        "核心 全身",
        "登山者 提膝",
        true,
        false,
        "从高位平板支撑开始，双手位于肩下。双膝交替向胸前收，肩部和骨盆尽量稳定，按体力调整速度。");
    add(
        "db_goblet_squat",
        "哑铃高脚杯深蹲",
        "哑铃",
        "总重",
        "力量",
        "腿臀",
        "杯式深蹲 高脚杯",
        false,
        false,
        "双手将一只哑铃稳握在胸前。屈髋屈膝下蹲，膝盖朝向脚尖，脚掌踩稳后起身。");
    add(
        "db_rdl",
        "哑铃罗马尼亚硬拉",
        "哑铃",
        "每只",
        "力量",
        "腿臀 背",
        "RDL 腘绳肌 髋铰链",
        false,
        false,
        "双手持哑铃，膝盖微屈，臀部向后移。哑铃贴近腿部下放，背部保持自然姿态，感到大腿后侧牵拉后起身。");
    add(
        "db_split_squat",
        "哑铃分腿蹲",
        "哑铃",
        "每只",
        "力量",
        "腿臀",
        "弓步 静态箭步蹲",
        false,
        true,
        "双脚前后分开，双手持哑铃垂于体侧。屈膝下蹲，前脚踩稳后起身，完成一侧后换边。");
    add(
        "step_up",
        "踏台上步",
        "踏台",
        "",
        "力量",
        "腿臀",
        "登阶 上台阶",
        false,
        true,
        "选择稳固、适合高度的踏台。一脚完全踩上台面，靠前腿发力站上去，再控制下台，左右轮换。");
    add(
        "prone_leg_curl",
        "俯卧腿弯举",
        "腿弯举机",
        "器械标称",
        "力量",
        "腿臀",
        "腘绳肌 大腿后侧",
        false,
        false,
        "俯卧贴稳靠垫，滚垫位于脚踝上方、小腿后侧。屈膝将脚跟向臀部收，再缓慢伸回，避免抬髋。");
    add(
        "leg_extension",
        "坐姿腿屈伸",
        "腿屈伸机",
        "器械标称",
        "力量",
        "腿臀",
        "腿伸展 股四头肌",
        false,
        false,
        "调整座椅，使膝关节与器械转轴对齐。滚垫贴在脚踝上方，平稳伸膝并控制回程，避免甩动。");
    add(
        "hip_abduction",
        "坐姿髋外展",
        "髋外展机",
        "器械标称",
        "力量",
        "腿臀",
        "臀中肌 外展 大腿外侧",
        false,
        false,
        "坐稳并贴靠背垫，将腿垫调到大腿外侧、靠近膝盖的位置，双脚踩住支撑。双腿向外推开腿垫，再缓慢收回，身体不左右摇晃。");
    add(
        "back_extension",
        "罗马椅挺身",
        "罗马椅",
        "",
        "力量",
        "背 腿臀",
        "背伸 山羊挺身",
        false,
        false,
        "髋部靠在支撑垫边缘，双脚固定。屈髋下放，再抬起至身体接近一线，不要过度反弓腰部。");
    add(
        "db_shoulder_press",
        "哑铃推举",
        "哑铃",
        "每只",
        "力量",
        "肩 手臂",
        "肩推 三角肌",
        false,
        false,
        "坐稳，双脚踩地，哑铃置于肩部两侧。收紧腹部向上推起，控制下放，避免耸肩和塌腰。");
    add(
        "db_lateral_raise",
        "哑铃侧平举",
        "哑铃",
        "每只",
        "力量",
        "肩",
        "三角肌 中束",
        false,
        false,
        "站稳，双肘微屈。将哑铃向身体两侧抬起至接近肩高，再缓慢放下，避免借力甩动。");
    add(
        "db_curl",
        "哑铃弯举",
        "哑铃",
        "每只",
        "力量",
        "手臂",
        "二头肌 肱二头肌",
        false,
        false,
        "双肘靠近身体两侧，屈肘抬起哑铃，再控制下放。上臂尽量不动，避免身体后仰借力。");
    add(
        "db_triceps_extension",
        "哑铃过顶臂屈伸",
        "哑铃",
        "总重",
        "力量",
        "手臂",
        "三头肌 肱三头肌",
        false,
        false,
        "双手稳握一只哑铃举过头顶。上臂保持稳定，屈肘下放后再伸肘推起，选择能控制的重量。");
    add(
        "db_floor_press",
        "哑铃地板卧推",
        "哑铃",
        "每只",
        "力量",
        "胸 手臂",
        "胸肌 地板推",
        false,
        false,
        "仰卧屈膝，双脚踩地。双手持哑铃向上推起，缓慢下放至上臂轻触地面，避免撞击地面。");
    add(
        "cable_row",
        "坐姿绳索划船",
        "绳索器械",
        "器械标称",
        "力量",
        "背 手臂",
        "坐姿划船 背阔肌",
        false,
        false,
        "双脚踩稳踏板，脊柱保持自然姿态。将把手拉向腹部附近，肘部向后移动，再控制回放。");
    add(
        "cable_face_pull",
        "绳索面拉",
        "绳索器械",
        "器械标称",
        "力量",
        "肩 背",
        "后束 面拉",
        false,
        false,
        "滑轮调至面部附近高度，双手握住绳索两端。将绳索两端拉到脸颊两侧，绳索中部留在脸的前方，肘部向两侧打开。缓慢伸回手臂，不耸肩、不后仰借力。");
    add(
        "assisted_pullup",
        "辅助引体向上",
        "辅助引体机",
        "",
        "力量",
        "背 手臂",
        "引体 助力",
        false,
        false,
        "先在器械上设置合适助力，再握稳横杆、跪稳助力垫。平稳拉起并控制下放。助力越大通常越省力，本项只记录次数，不把助力计为负重。");
    add(
        "elliptical",
        "椭圆机",
        "椭圆机",
        "",
        "有氧",
        "全身 腿臀",
        "椭圆仪 室内",
        true,
        false,
        "双脚踩稳踏板，保持躯干稳定，平顺踩动。距离按器械读数填写，不使用 GPS。");
    add(
        "stationary_bike",
        "动感单车",
        "动感单车",
        "",
        "有氧",
        "腿臀",
        "室内 自行车 骑车 健身车",
        true,
        false,
        "调好坐垫和把手高度，先以较轻阻力热身。距离按单车读数填写，不使用 GPS。");
    add(
        "jump_rope",
        "跳绳",
        "跳绳",
        "",
        "有氧",
        "全身 腿臀",
        "跳跃 有氧",
        true,
        false,
        "选择平整场地，肘部靠近身体，用手腕带动绳子。小幅跳起，前脚掌轻缓落地，按时长记录。");
    add(
        "cat_cow",
        "猫牛式",
        "瑜伽垫",
        "",
        "拉伸",
        "背 核心",
        "猫式 牛式 脊柱活动",
        true,
        false,
        "四点支撑，缓慢交替拱背与舒展背部。动作配合自然呼吸，不强行抬头或压腰。");
    add(
        "hamstring_stretch",
        "大腿后侧拉伸",
        "瑜伽垫",
        "",
        "拉伸",
        "腿臀",
        "腘绳肌 腿后侧",
        true,
        true,
        "仰卧，一腿屈膝踩地，双手托住另一侧大腿后方。头部放松贴垫，缓慢伸直抬起的腿，膝盖不必完全伸直。感到大腿后侧轻微牵拉即可，双手不要抓膝关节；放下后换另一侧。");
    add(
        "hip_flexor_stretch",
        "髋屈肌拉伸",
        "瑜伽垫",
        "",
        "拉伸",
        "腿臀",
        "髂腰肌 髋前侧",
        true,
        true,
        "半跪姿，前脚踩稳。轻收腹和臀部，骨盆缓慢前移至髋前侧有牵拉感，避免塌腰。");
    add(
        "chest_stretch",
        "胸部拉伸",
        "",
        "",
        "拉伸",
        "胸 肩",
        "胸肌 门框",
        true,
        true,
        "前臂贴在稳固门框上，肘部约与肩同高。身体轻轻转向另一侧，至胸前有轻微牵拉感。");
    add(
        "calf_stretch",
        "小腿拉伸",
        "",
        "",
        "拉伸",
        "腿臀",
        "腓肠肌 小腿后侧",
        true,
        true,
        "双手扶墙，双脚前后站立。后腿伸直、脚跟着地，身体缓慢向前移至小腿有牵拉感。");
    add(
        "incline_pushup",
        "上斜俯卧撑",
        "训练凳",
        "",
        "力量",
        "胸 手臂 核心",
        "incline push up 高位俯卧撑",
        false,
        false,
        "双手撑在稳固的高台边缘，双脚后撤，身体保持一线。屈肘让胸部靠近台面，再平稳推回起始位。");
    add(
        "kneeling_pushup",
        "跪姿俯卧撑",
        "瑜伽垫",
        "",
        "力量",
        "胸 手臂 核心",
        "knee push up 跪姿",
        false,
        false,
        "双手略宽于肩，双膝着垫，肩、髋和膝保持一线。屈肘降低胸部，腹部和髋部不贴地。双手推地回到起始姿势，身体不要塌腰。");
    add(
        "chair_squat",
        "坐椅深蹲",
        "椅子",
        "",
        "力量",
        "腿臀",
        "chair squat 坐站 椅子深蹲",
        false,
        false,
        "将稳固椅子靠墙放置，双脚约与肩同宽。臀部向后移，控制坐到椅面，双脚踩地站起。");
    add(
        "lateral_lunge",
        "侧弓步",
        "",
        "",
        "力量",
        "腿臀",
        "side lunge 侧向弓步 侧蹲",
        false,
        true,
        "双脚站稳，向一侧迈步并将臀部后移。迈出侧屈膝，另一腿伸展，蹬地回到站姿后换边。");
    add(
        "barbell_rdl",
        "杠铃罗马尼亚硬拉",
        "杠铃",
        "总重",
        "力量",
        "腿臀 背",
        "barbell RDL 腘绳肌 髋铰链",
        false,
        false,
        "双手握杠站直，膝盖微屈。臀部向后移，杠铃贴近腿部下放至可保持背部稳定的位置，再伸髋站起。");
    add(
        "barbell_row",
        "杠铃俯身划船",
        "杠铃",
        "总重",
        "力量",
        "背 手臂",
        "barbell row 杠铃划船",
        false,
        false,
        "双脚踩稳，屈髋俯身并保持背部自然姿态。将杠铃拉向腹部，再控制下放，躯干位置尽量不变。");
    add(
        "cable_fly",
        "绳索夹胸",
        "龙门架",
        "器械标称",
        "力量",
        "胸 肩",
        "cable fly 飞鸟 夹胸",
        false,
        false,
        "调好两侧滑轮，双脚前后站稳，双手握把。肘部微屈，将双手向胸前合拢，再控制打开。");
    add(
        "straight_arm_pulldown",
        "直臂下拉",
        "龙门架",
        "器械标称",
        "力量",
        "背",
        "straight arm pulldown 背阔肌",
        false,
        false,
        "面对高位滑轮，双手握杆，双膝微屈并略微俯身。肘部保持轻微弯曲，将杆下压到大腿前，再缓慢还原。");
    add(
        "hammer_curl",
        "哑铃锤式弯举",
        "哑铃",
        "每只",
        "力量",
        "手臂",
        "hammer curl 锤举 二头肌",
        false,
        false,
        "双手持哑铃垂于体侧，掌心相对。上臂贴近身体，屈肘举起哑铃，再缓慢放下。");
    add(
        "db_front_raise",
        "哑铃前平举",
        "哑铃",
        "每只",
        "力量",
        "肩",
        "front raise 肩前束",
        false,
        false,
        "双手持哑铃站稳，肘部微屈。将哑铃向前抬至约肩高，再缓慢落回，身体不后仰。");
    add(
        "db_reverse_fly",
        "哑铃俯身飞鸟",
        "哑铃",
        "每只",
        "力量",
        "肩 背",
        "reverse fly rear delt 肩后束",
        false,
        false,
        "臀部向后移并俯身，膝盖微屈，背部保持自然伸直。双臂向两侧展开，肘部略弯，再控制收回。");
    add(
        "db_shrug",
        "哑铃耸肩",
        "哑铃",
        "每只",
        "力量",
        "肩 背",
        "shrug 斜方肌",
        false,
        false,
        "双手持哑铃垂于体侧，头颈保持自然。双肩向上抬起，稍停后缓慢放下，手臂保持伸展。");
    add(
        "seated_calf_raise",
        "哑铃坐姿提踵",
        "哑铃",
        "每只",
        "力量",
        "腿臀",
        "seated calf raise 小腿 比目鱼肌",
        false,
        false,
        "坐在稳固训练凳上，双手将哑铃固定在大腿靠近膝盖的位置。脚掌踩稳，缓慢抬起脚跟，再控制落下。");
    add(
        "crunch",
        "卷腹",
        "瑜伽垫",
        "",
        "核心",
        "核心",
        "crunch 腹直肌",
        false,
        false,
        "仰卧屈膝，双脚着地，双臂交叉放在胸前。缓慢抬起肩部，让肋骨靠近骨盆，再控制落回。");
    add(
        "heel_touch",
        "仰卧交替触踝",
        "瑜伽垫",
        "",
        "核心",
        "核心",
        "heel touch 腹斜肌 触脚跟",
        false,
        true,
        "仰卧屈膝，双脚着地，肩部略微抬起。身体向一侧轻屈，同侧手伸向脚跟，回到中间后换边。");
    add(
        "child_pose",
        "婴儿式伸展",
        "瑜伽垫",
        "",
        "拉伸",
        "背 肩",
        "child pose 婴儿式",
        true,
        false,
        "跪在垫上，臀部缓慢向脚跟靠近。双臂向前伸展，额头自然降低，在舒适范围内保持呼吸。");
    add(
        "supported_leg_raise",
        "支撑提腿",
        "提膝架",
        "",
        "核心",
        "核心",
        "提膝 举腿 captain chair knee raise",
        false,
        false,
        "前臂放在提膝架肘垫上，握住把手，背部贴稳靠垫。收紧腹部，屈膝向前上方抬起双腿，再缓慢落下；不靠摆动借力。");
  }

  private static void old(String id, String category, String muscles, String aliases) {
    ENTRIES.put(id, new Entry(id, "", "", "", category, muscles, aliases, false, false, ""));
  }

  private static void add(
      String id,
      String name,
      String gear,
      String unit,
      String category,
      String muscles,
      String aliases,
      boolean timed,
      boolean perSide,
      String guidance) {
    ENTRIES.put(
        id, new Entry(id, name, gear, unit, category, muscles, aliases, timed, perSide, guidance));
  }

  static final List<String> IDS = List.copyOf(ENTRIES.keySet());

  static List<Domain.Exercise> additions() {
    List<Domain.Exercise> result = new ArrayList<>();
    for (Entry entry : ENTRIES.values()) if (!entry.name.isEmpty()) result.add(entry.exercise());
    return result;
  }

  static Entry entry(Domain.Exercise e) {
    return ENTRIES.get(e.id);
  }

  static String weightLabel(Domain.Exercise exercise) {
    if (exercise.unit.equals("每只")) return exercise.gear.contains("哑铃") ? "单只重量" : "单件重量";
    return exercise.unit.equals("总重") ? "总重量" : "器械重量";
  }

  static boolean perSide(Domain.Exercise e) {
    Entry m = entry(e);
    return m != null && m.perSide;
  }

  static String countNote(Domain.Exercise e) {
    return !perSide(e) ? "" : e.timed ? "每侧分别计时；完成一侧记一组，下一组换边。" : "次数按每侧记录；左右两侧完成后记一组。";
  }

  static String subtitle(Domain.Exercise e) {
    Entry m = entry(e);
    return (m == null ? "" : m.muscles.replace(" ", " · ") + " · ")
        + (e.gear.isEmpty() ? "徒手" : e.gear);
  }

  private static String normalize(String s) {
    return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC)
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  static boolean matches(
      Domain.Exercise e,
      String query,
      String category,
      String muscles,
      String gear,
      Set<String> favorites) {
    Entry m = entry(e);
    if (category.equals("收藏") && !favorites.contains(e.id)) return false;
    if (List.of("力量", "有氧", "核心", "拉伸").contains(category)
        && (m == null || !m.category.equals(category))) return false;
    boolean bodyweight = e.gear.isEmpty() || e.gear.equals("瑜伽垫");
    if (category.equals("徒手") && !bodyweight) return false;
    if (category.equals("器械") && bodyweight) return false;
    if (category.equals("计时") && !e.timed) return false;
    if (!muscles.equals("全部部位")
        && (m == null || !Arrays.asList(m.muscles.split(" ")).contains(muscles))) return false;
    if (!gear.equals("全部器材")) {
      boolean accepts =
          switch (gear) {
            case "徒手" -> bodyweight;
            case "杠铃" -> e.gear.equals("杠铃") || e.id.equals("bench");
            case "自行车" -> Set.of("bike", "trainer", "stationary_bike").contains(e.id);
            case "固定器械" ->
                !bodyweight
                    && !Set.of("哑铃", "壶铃", "弹力带", "公路车", "自行车", "跳绳", "踏台", "杠铃", "训练凳", "椅子")
                        .contains(e.gear);
            default -> e.gear.equals(gear);
          };
      if (!accepts) return false;
    }
    String haystack =
        normalize(
            e.name
                + " "
                + e.gear
                + " "
                + (m == null ? "" : m.muscles + " " + m.aliases + " " + m.category));
    for (String word : normalize(query).split("\\s+")) if (!haystack.contains(word)) return false;
    return true;
  }

  static Set<String> favorites(String saved) {
    Set<String> result = new LinkedHashSet<>(Arrays.asList(saved.split(",")));
    result.removeIf(id -> !IDS.contains(id) && !id.matches("user_[a-f0-9-]{36}"));
    return result;
  }
}
