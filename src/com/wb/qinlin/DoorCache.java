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
        /** 上游 authorizeExpired：-1 未返回 / 0 无到期限制 / ≥1 有到期限制。 */
        public final int authorizeExpired;

        Data(String communityName, List<QinlinApi.Door> doors, long savedAt,
             String note, boolean fromCache, int authorizeExpired) {
            this.communityName = communityName == null ? "" : communityName;
            this.doors = doors == null ? new ArrayList<QinlinApi.Door>() : doors;
            this.savedAt = savedAt;
            this.note = note == null ? "" : note;
            this.fromCache = fromCache;
            this.authorizeExpired = authorizeExpired;
        }

        /**
         * 授权到期提示（没有可说的就返回空串）。
         * 官方 FAQ：开门权限有期限，业主自动续期、租客需在官方 App 申请；
         * 到期后表现就是「开门失败」，本机提前把上游标记翻出来，避免到时误判成 App 坏了。
         */
        public String authorizeLine() {
            if (authorizeExpired <= 0) return "";
            return "门禁授权：上游标记「有到期限制」（authorizeExpired=" + authorizeExpired
                    + "），当前仍可正常开门；到期后需在官方 App 续期，届时本 App 会表现为开门被拒。";
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
                    o.optString("note", ""), true, o.optInt("ax", -1));
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
                             long at, String note, int authorizeExpired) {
        try {
            JSONObject o = new JSONObject();
            o.put("at", at);
            o.put("cname", communityName);
            o.put("note", note);
            o.put("ax", authorizeExpired);
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

        List<QinlinApi.Door> all = new ArrayList<QinlinApi.Door>();
        StringBuilder errs = new StringBuilder();
        /** 本次读取失败的小区：部分失败时用旧缓存把它们的门补回来。 */
        java.util.HashSet<Integer> failedCids = new java.util.HashSet<Integer>();
        /** 是否有网络层失败（IOException）：决定最终抛 NETWORK 还是 COMMUNITY_FAILED。 */
        boolean netFail = false;
        int authorizeExpired = -1;
        for (QinlinApi.Community cm : cs) {
            try {
                QinlinApi.DoorsResult dr = QinlinApi.doorsDetailed(token, cm.id);
                all.addAll(dr.doors);
                // 任一小区标记了到期限制就保留最"严重"的值，宁可多提醒一次
                if (dr.authorizeExpired > authorizeExpired) authorizeExpired = dr.authorizeExpired;
            } catch (QinlinApi.ApiException e) {
                if ("SESSION_EXPIRED".equals(e.code)) throw e;   // 凭证失效要交给界面处理
                if ("NETWORK".equals(e.code)) netFail = true;
                failedCids.add(Integer.valueOf(cm.id));
                errs.append(cm.name).append(": ").append(e.getMessage()).append("\n");
                AppLog.w(AppLog.DOORS, "小区「" + cm.name + "」门列表读取失败 [" + e.code + "]");
            } catch (Exception e) {
                if (e instanceof java.io.IOException) netFail = true;
                failedCids.add(Integer.valueOf(cm.id));
                errs.append(cm.name).append(": ").append(e.getClass().getSimpleName()).append("\n");
                AppLog.e(AppLog.DOORS, "小区「" + cm.name + "」门列表异常 " + AppLog.stack(e));
            }
        }

        String diag = QinlinApi.diag();
        StringBuilder note = new StringBuilder(diag);
        if (errs.length() > 0) {
            if (note.length() > 0) note.append("\n");
            note.append("部分小区读取失败：").append(errs);
        }

        long at = System.currentTimeMillis();
        // 「所有小区都失败」≠「这个账号没有门」：前者必须保留上次可用缓存，
        // 否则一次弱网就会把用户已缓存的整份门列表覆盖成空，下次打开连门都选不了。
        // 直接抛给界面（message 不带技术细节），由界面走「失败 + 仍在显示上次缓存」的既有路径。
        boolean readFailed = !cs.isEmpty() && all.isEmpty() && errs.length() > 0;
        if (readFailed) {
            AppLog.w(AppLog.DOORS, "所有小区门列表均读取失败 → 不覆盖已有缓存与小区名");
            if (netFail) {
                // 断网 / 超时不是「网关拒绝」，界面要按可重试的网络问题提示
                throw new QinlinApi.ApiException("NETWORK",
                        "网络异常，读不到门列表。请检查网络后点「↻ 刷新门列表」重试。");
            }
            throw new QinlinApi.ApiException("COMMUNITY_FAILED",
                    "这 " + cs.size() + " 个小区都没读到门，可能是网络或网关异常。");
        }

        // 部分小区失败：把上次缓存里这些小区的门补回来。
        // 旧实现直接用「本次成功子集」整体覆盖缓存，弱网下某个小区的门会从列表里消失，
        // 用户看到的是「门少了」，而不是「这一个小区没读到」。
        if (!failedCids.isEmpty()) {
            Data old = load(c);
            int kept = 0;
            if (old != null) {
                for (QinlinApi.Door d : old.doors) {
                    if (!failedCids.contains(Integer.valueOf(d.communityId))) continue;
                    boolean dup = false;
                    for (QinlinApi.Door x : all) {
                        if (x.id == d.id) { dup = true; break; }
                    }
                    if (!dup) {
                        all.add(d);
                        kept++;
                    }
                }
            }
            if (kept > 0) {
                note.append("\n其中 ").append(kept).append(" 扇门沿用上次缓存（对应小区本次没读到）");
                AppLog.w(AppLog.DOORS, "部分小区失败 → 沿用旧缓存补回 " + kept + " 扇门");
            }
        }

        Prefs.setCommunityName(c, cname);
        save(c, cname, all, at, note.toString(), authorizeExpired);
        AppLog.i(AppLog.DOORS, "门列表读取完成 " + all.size() + " 扇门，耗时 " + (at - t0) + "ms"
                + (errs.length() > 0 ? "（部分小区失败）" : "")
                + " | authorizeExpired=" + authorizeExpired);
        return new Data(cname, all, at, note.toString(), false, authorizeExpired);
    }
}
