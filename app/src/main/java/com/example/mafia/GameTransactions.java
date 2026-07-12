package com.example.mafia;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Все переходы игровых стадий через атомарные Firestore-транзакции.
 */
public class GameTransactions {

    public interface OnTxComplete {
        void onSuccess();
        void onError(Exception e);
    }

    private static DocumentReference ref(FirebaseFirestore db, String roomId) {
        return db.collection("games").document(roomId);
    }

    // ── Ночное действие ────────────────────────────────────────────────────
    public static void submitNightAction(FirebaseFirestore db, String roomId, String uid,
                                         String targetId, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        db.runTransaction(transaction -> {
                    DocumentSnapshot snap = transaction.get(docRef);
                    if (!snap.exists()) return null;
                    Map<String, Object> data = toMap(snap.getData());
                    if (!"night".equals(data.get("phase"))) return null;

                    Map<String, Object> players = toMap(data.get("players"));
                    String myRole = roleOf(players, uid);
                    String stage  = str(data.get("nightStage"), "mafia");

                    if (myRole == null || !myRole.equals(stage) || !isAlive(players, uid)) return null;

                    Map<String, Object> nightActions = toMap(data.get("nightActions"));
                    boolean stageComplete;
                    Map<String, Object> writeNow = new HashMap<>();

                    if ("mafia".equals(stage)) {
                        // Читаем уже сохранённые голоса из Firestore (не из локальной копии)
                        Map<String, Object> existingVotes = toMap(snap.get("nightActions.mafia"));
                        existingVotes.put(uid, targetId);
                        writeNow.put("nightActions.mafia." + uid, targetId);
                        stageComplete = existingVotes.size() >= countAliveRole(players, "mafia");
                        if (stageComplete) {
                            // Кладём обновлённые голоса в nightActions для resolve()
                            nightActions.put("mafia", existingVotes);
                        }
                    } else {
                        nightActions.put(stage, targetId);
                        writeNow.put("nightActions." + stage, targetId);
                        stageComplete = true;
                    }

                    if (!stageComplete) {
                        transaction.update(docRef, writeNow);
                        return null;
                    }

                    String next = nextLivingStage(players, stage);
                    if (next != null) {
                        writeNow.put("nightStage", next);
                        writeNow.put("phaseStartAt", System.currentTimeMillis());
                        transaction.update(docRef, writeNow);
                        return null;
                    }

                    // Последнее действие — разрешаем итог ночи
                    int currentDay = intOf(data.get("dayNumber"), 1);
                    NightResultProcessor.NightResult result =
                            NightResultProcessor.resolve(players, nightActions, currentDay, null);
                    transaction.update(docRef, result.updates);

                    if (!result.perksToConsume.isEmpty()) {
                        consumeUsedPerks(db, result.perksToConsume);
                    }
                    return null;
                }).addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> { if (cb != null) cb.onError(e); });
    }

    // ── Дневное голосование ────────────────────────────────────────────────
    public static void submitDayVote(FirebaseFirestore db, String roomId, String uid,
                                     String targetId, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        db.runTransaction(transaction -> {
                    DocumentSnapshot snap = transaction.get(docRef);
                    if (!snap.exists()) return null;
                    Map<String, Object> data = toMap(snap.getData());
                    if (!"voting".equals(data.get("phase"))) return null;

                    Map<String, Object> players = toMap(data.get("players"));
                    if (!isAlive(players, uid)) return null;

                    // Читаем голоса из Firestore напрямую
                    Map<String, Object> existingVotes = toMap(snap.get("dayVotes"));
                    existingVotes.put(uid, targetId);

                    int aliveCount = countAliveRole(players, null);
                    if (existingVotes.size() < aliveCount) {
                        transaction.update(docRef, "dayVotes." + uid, targetId);
                        return null;
                    }

                    int currentDay = intOf(data.get("dayNumber"), 1);
                    DayVotingProcessor.VoteResult result =
                            DayVotingProcessor.resolve(players, existingVotes, currentDay);
                    transaction.update(docRef, result.updates);
                    return null;
                }).addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> { if (cb != null) cb.onError(e); });
    }

    // ── Принудительный переход ночной стадии ──────────────────────────────
    public static void forceAdvanceNightStage(FirebaseFirestore db, String roomId,
                                              String expectedStage, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        db.runTransaction(transaction -> {
                    DocumentSnapshot snap = transaction.get(docRef);
                    if (!snap.exists()) return null;
                    Map<String, Object> data = toMap(snap.getData());
                    if (!"night".equals(data.get("phase"))) return null;

                    String stage = str(data.get("nightStage"), "mafia");
                    if (!stage.equals(expectedStage)) return null;

                    Map<String, Object> players      = toMap(data.get("players"));
                    Map<String, Object> nightActions = toMap(data.get("nightActions"));

                    // Читаем голоса мафии из Firestore
                    Map<String, Object> mafiaVotes = toMap(snap.get("nightActions.mafia"));
                    nightActions.put("mafia", mafiaVotes);

                    String next = nextLivingStage(players, stage);
                    if (next != null) {
                        transaction.update(docRef,
                                "nightStage", next,
                                "phaseStartAt", System.currentTimeMillis());
                        return null;
                    }

                    int currentDay = intOf(data.get("dayNumber"), 1);
                    NightResultProcessor.NightResult result =
                            NightResultProcessor.resolve(players, nightActions, currentDay, null);
                    transaction.update(docRef, result.updates);

                    if (!result.perksToConsume.isEmpty()) {
                        consumeUsedPerks(db, result.perksToConsume);
                    }
                    return null;
                }).addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> { if (cb != null) cb.onError(e); });
    }

    // ── Принудительное завершение голосования ─────────────────────────────
    public static void forceResolveVoting(FirebaseFirestore db, String roomId, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        db.runTransaction(transaction -> {
                    DocumentSnapshot snap = transaction.get(docRef);
                    if (!snap.exists()) return null;
                    Map<String, Object> data = toMap(snap.getData());
                    if (!"voting".equals(data.get("phase"))) return null;

                    Map<String, Object> players  = toMap(data.get("players"));
                    Map<String, Object> dayVotes = toMap(snap.get("dayVotes"));
                    int currentDay = intOf(data.get("dayNumber"), 1);
                    DayVotingProcessor.VoteResult result =
                            DayVotingProcessor.resolve(players, dayVotes, currentDay);
                    transaction.update(docRef, result.updates);
                    return null;
                }).addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> { if (cb != null) cb.onError(e); });
    }

    // ── Начало голосования ─────────────────────────────────────────────────
    public static void forceStartVoting(FirebaseFirestore db, String roomId, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        db.runTransaction(transaction -> {
                    DocumentSnapshot snap = transaction.get(docRef);
                    if (!snap.exists()) return null;
                    Map<String, Object> data = toMap(snap.getData());
                    if (!"day".equals(data.get("phase"))) return null;
                    transaction.update(docRef,
                            "phase", "voting",
                            "phaseStartAt", System.currentTimeMillis());
                    return null;
                }).addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> { if (cb != null) cb.onError(e); });
    }

    // ── Списание плюшек из инвентаря (после транзакции) ───────────────────
    static void consumeUsedPerks(FirebaseFirestore db, Map<String, String> perksToConsume) {
        for (Map.Entry<String, String> entry : perksToConsume.entrySet()) {
            String uid   = entry.getKey();
            String pType = entry.getValue();
            String field = DiamondManager.getPerkField(pType);
            if (field == null) continue;

            DocumentReference userRef = db.collection("users").document(uid);
            db.runTransaction(transaction -> {
                DocumentSnapshot snap = transaction.get(userRef);
                long current = snap.getLong(field) != null ? snap.getLong(field) : 0L;
                transaction.update(userRef, field, Math.max(0, current - 1));
                return null;
            });
        }
    }

    // ── Вспомогательные ────────────────────────────────────────────────────

    private static String nextLivingStage(Map<String, Object> players, String current) {
        String s = current;
        while (true) {
            if      ("mafia".equals(s))  s = "doctor";
            else if ("doctor".equals(s)) s = "sheriff";
            else return null;
            if (countAliveRole(players, s) > 0) return s;
        }
    }

    static int countAliveRole(Map<String, Object> players, String role) {
        int n = 0;
        if (players == null) return 0;
        for (Map.Entry<String, Object> e : players.entrySet()) {
            Map<String, Object> p = toMap(e.getValue());
            if (!isAliveFlag(p.get("alive"))) continue;
            if (role == null) { n++; continue; }
            Object r = p.get("role");
            if (r != null && role.equals(String.valueOf(r).trim().toLowerCase())) n++;
        }
        return n;
    }

    private static String roleOf(Map<String, Object> players, String uid) {
        Map<String, Object> p = toMap(players != null ? players.get(uid) : null);
        Object r = p.get("role");
        if (r == null) return null;
        String s = String.valueOf(r).trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }

    private static boolean isAlive(Map<String, Object> players, String uid) {
        Map<String, Object> p = toMap(players != null ? players.get(uid) : null);
        return isAliveFlag(p.get("alive"));
    }

    static boolean isAliveFlag(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number)  return ((Number) o).intValue() != 0;
        String s = String.valueOf(o).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s);
    }

    static int intOf(Object o, int def) {
        return (o instanceof Number) ? ((Number) o).intValue() : def;
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? def : s;
    }

    /**
     * Безопасное приведение к Map. Если объект не Map — возвращает пустой HashMap.
     * ВАЖНО: не возвращаем ссылку на оригинал, чтобы изменения не затронули Firestore-данные.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(Object o) {
        if (o instanceof Map) return new HashMap<>((Map<String, Object>) o);
        return new HashMap<>();
    }

    // ── Передача управления голосованием живому игроку ────────────────────
    /**
     * Когда votingManagerId умер (или не задан), выбирает следующего живого игрока
     * по детерминированному порядку (сортировка uid) и записывает нового votingManagerId.
     * Вызывать КАЖДЫЙ раз при получении снепшота в GameActivity,
     * если фаза day И текущий votingManagerId мёртв.
     */
    public static void transferVotingManagerIfNeeded(FirebaseFirestore db, String roomId,
                                                     OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        db.runTransaction(transaction -> {
                    DocumentSnapshot snap = transaction.get(docRef);
                    if (!snap.exists()) return null;
                    Map<String, Object> data = toMap(snap.getData());
                    if (!"day".equals(data.get("phase"))) return null;

                    Map<String, Object> players = toMap(data.get("players"));
                    Object vmObj = data.get("votingManagerId");
                    String currentManager = (vmObj instanceof String) ? (String) vmObj : null;

                    // Проверяем жив ли текущий менеджер
                    boolean managerAlive = currentManager != null && isAlive(players, currentManager);
                    if (managerAlive) return null; // Всё хорошо, передавать не нужно

                    // Ищем следующего живого по отсортированным uid
                    java.util.List<String> aliveUids = new java.util.ArrayList<>();
                    for (Map.Entry<String, Object> e : players.entrySet()) {
                        Map<String, Object> p = toMap(e.getValue());
                        if (isAliveFlag(p.get("alive"))) aliveUids.add(e.getKey());
                    }
                    if (aliveUids.isEmpty()) return null;
                    java.util.Collections.sort(aliveUids);

                    // Если есть текущий менеджер — берём следующего после него по кругу
                    String newManager;
                    if (currentManager != null) {
                        int idx = aliveUids.indexOf(currentManager);
                        newManager = aliveUids.get((idx + 1) % aliveUids.size());
                    } else {
                        newManager = aliveUids.get(0);
                    }

                    transaction.update(docRef, "votingManagerId", newManager);
                    return null;
                }).addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> { if (cb != null) cb.onError(e); });
    }

}