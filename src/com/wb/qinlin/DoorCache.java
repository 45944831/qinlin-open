package com.wb.qinlin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 门列表的本地缓存与统一读取入口。
 *
 * 之前每次打开「门列表」都要现走一轮「小区列表 → 逐小区门列表」的网络请求，
 * 弱网下要愣住好几秒。现在改成两级：
 *   ① 打开即渲染上一次的结果（{@link #load}，本机 JSON，毫秒级）；
 *   ② 同时后台刷新（{@link #fetch}），成功后覆盖缓存与界面。
 *
 * 缓存只在同一账号内有效：退出登录 / 重新登录时会清掉，避免串号。
 */
public final class DoorCache {

    /** 超过该时长即视为「陈旧」：仍会先展示，但提示里会写明是缓存。 */
    public static final long FRESH_MS = 10 * 60 * 1000L;

    public static final class Data {
        public final String communityName;
        public final List<QinlinApi.Door> doors;
        public final long savedAt;
        /** 附加说明：小区列表使用的请求姿势，或「部分小区读取失败」。 */
        public final String note;
        public final boolean fromCache;

        Data(String communityName, List<QinlinApi.Door> doors, long savedAt,
             String note, boolean fromCache) {
            this.communityName = communityName == null ? "" : communityName;
            this.doors = doors == null ? new ArrayList<QinlinApi.Door>() : doors;
            this.savedAt = savedAt;
            this.note = note == null ? "" : note;
            this.fromCache = fromCache;
        }

        public boolean fresh() {
            return savedAt > 0 && System.currentTimeMillis() - savedAt < FRESH_MS;
        }

        /** 「刚刚 / 12 分钟前 / 3 小时前」，用于告诉用户看到的是多久前的数据。 */
        public String age() {
            if (savedAt <= 0) return "时间未知";
            long ms = System.currentTimeMillis() - savedAt;
            if (ms < 0) ms = 0;
            long min = ms / 60000L;
            if (min < 1) return "刚刚";
            if (min < 60) return min + " 分钟前";
            long hour = min / 60;
            if (hour < 24) return hour + " 小时前";
            return (hour / 24) + " 天前";
        }
    }

    private DoorCache() {}

    /** 读本机缓存；没有、为空或解析失败都返回 null（调用方直接走网络）。 */
    public static Data load(Context c) {
        String raw = Prefs.doorCache(c);
        if (raw == null || raw.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray arr = o.optJSONArray("doors");
            List<QinlinApi.Door> doors = new ArrayList<QinlinApi.Door>();
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                int id = d.optInt("id", -1);
                if (id <= 0) continue;
                doors.add(new QinlinApi.Door(
                        d.optInt("cid", 0), id,
                        d.optString("n", "门禁 " + id),
                        d.optString("k", "其他门"),
                        d.optString("o", "")));
            }
            if (doors.isEmpty()) return null;
            return new Data(o.optString("cname", ""), doors, o.optLong("at", 0),
                    o.optString("note", ""), true);
        } catch (Exception e) {
            // 缓存坏了不影响主流程，当作没有缓存
            AppLog.w(AppLog.DOORS, "门列表缓存解析失败，已忽略（" +
                    e.getClass().getSimpleName() + "）");
            return null;
        }
    }

    /** 缓存属于某个账号：退出登录 / 重新登录时清掉。 */
    public static void clear(Context c) {
        Prefs.setDoorCache(c, "");
    }

    private static void save(Context c, String communityName, List<QinlinApi.Door> doors,
                             long at, String note) {
        try {
            JSONObject o = new JSONObject();
            o.put("at", at);
            o.put("cname", communityName);
            o.put("note", note);
            JSONArray arr = new JSONArray();
            for (QinlinApi.Door d : doors) {
                JSONObject j = new JSONObject();
                j.put("cid", d.communityId);
                j.put("id", d.id);
                j.put("n", d.name);
                j.put("k", d.kind);
                j.put("o", d.online);
                arr.put(j);
            }
            o.put("doors", arr);
            Prefs.setDoorCache(c, o.toString());
        } catch (Exception e) {
            AppLog.w(AppLog.DOORS, "门列表缓存写入失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 联网读取「小区 + 各小区门列表」并写缓存。必须在后台线程调用。
     * 失败时抛异常，不动已有缓存（下次打开仍能先看到上次结果）。
     */
    public static Data fetch(Context c, String token) throws Exception {
        long t0 = System.currentTimeMillis();
        List<QinlinApi.Community> cs = QinlinApi.communities(token);
        Prefs.setCommMode(c, QinlinApi.PREFERRED_COMM_MODE);

        StringBuilder names = new StringBuilder();
        for (QinlinApi.Community cm : cs) {
            if (names.length() > 0) names.append("、");
            names.append(cm.name);
        }
        String cname = names.length() > 0 ? names.toString() : "未读取到小区信息";
        Prefs.setCommunityName(c, cname);

        List<QinlinApi.Door> all = new ArrayList<QinlinApi.Door>();
        StringBuilder errs = new StringBuilder();
        for (QinlinApi.Community cm : cs) {
            try {
                all.addAll(QinlinApi.doors(token, cm.id));
            } catch (QinlinApi.ApiException e) {
                if ("SESSION_EXPIRED".equals(e.code)) throw e;   // 凭证失效要交给界面处理
                errs.append(cm.name).append(": ").append(e.getMessage()).append("\n");
                AppLog.w(AppLog.DOORS, "小区「" + cm.name + "」门列表读取失败 [" + e.code + "]");
            } catch (Exception e) {
                errs.append(cm.name).append(": ").append(e.getClass().getSimpleName()).append("\n");
                AppLog.e(AppLog.DOORS, "小区「" + cm.name + "」门列表异常 " + AppLog.stack(e));
            }
        }

        String diag = QinlinApi.diag();
        String note = diag;
        if (errs.length() > 0) {
            note = (diag.length() > 0 ? diag + "\n" : "") + "部分小区读取失败：" + errs;
        }

        long at = System.currentTimeMillis();
        save(c, cname, all, at, errs.length() > 0 ? note : "");
        AppLog.i(AppLog.DOORS, "门列表读取完成 " + all.size() + " 扇门，耗时 " + (at - t0) + "ms"
                + (errs.length() > 0 ? "（部分小区失败）" : ""));
        return new Data(cname, all, at, note, false);
    }
}
