package com.example.mafia;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Вся логика перехода игры между стадиями (ночные роли → следующая роль → день →
 * голосование → следующая ночь) теперь выполняется ВНУТРИ Firestore-транзакций.
 *
 * Почему это важно и что было не так раньше:
 *  - Раньше переходы стадий мог выполнять ТОЛЬКО хост (проверка isHost) — если у хоста
 *    приложение уходило в фон, терялась сеть, либо его слушатель Firestore просто не
 *    успевал сработать, игра у ВСЕХ игроков замирала намертво на одной и той же стадии.
 *  - Раньше использовалось чтение документа (.get()) и затем отдельная запись (.update()) —
 *    классическая гонка "прочитать-изменить-записать". Если два события приходили почти
 *    одновременно (например, два последних голоса мафии), оба читали одно и то же старое
 *    состояние и оба пытались посчитать и записать результат — итог становился случайным.
 *
 * Теперь:
 *  - Каждое действие игрока (submitNightAction / submitDayVote) проверяет завершённость
 *    стадии и, если нужно, сразу выполняет переход СВОИМ ЖЕ устройством, внутри одной
 *    атомарной транзакции. Это работает одинаково для хоста и не-хоста.
 *  - На случай, если кто-то из игроков "завис" (отключился, не сделал выбор) — у КАЖДОГО
 *    подключённого клиента работает свой таймер обратного отсчёта; по истечении времени
 *    он сам пытается принудительно продвинуть игру (forceAdvanceNightStage /
 *    forceResolveVoting / forceStartVoting). Поскольку это тоже транзакции, конкурентные
 *    попытки от нескольких устройств не портят данные — побеждает первая закоммиченная,
 *    остальные видят уже обновлённое состояние и просто ничего не делают.
 */
public class GameTransactions {

    public interface OnTxComplete {
        void onSuccess();
        void onError(Exception e);
    }

    private static DocumentReference ref(FirebaseFirestore db, String roomId) {
        return db.collection("games").document(roomId);
    }

    private static void run(com.google.android.gms.tasks.Task<Void> task, OnTxComplete cb) {
        task.addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> { if (cb != null) cb.onError(e); });
    }

    // ── Игрок отправляет ночное действие (мафия/доктор/шериф) ─────────────
    public static void submitNightAction(FirebaseFirestore db, String roomId, String uid,
                                          String targetId, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        run(db.runTransaction(transaction -> {
            DocumentSnapshot snap = transaction.get(docRef);
            if (!snap.exists()) return null;
            Map<String, Object> data = snap.getData();
            if (data == null) return null;
            if (!"night".equals(data.get("phase"))) return null; // фаза уже сменилась — поздно

            Map<String, Object> players = asMap(data.get("players"));
            String myRole = roleOf(players, uid);
            String stage = (String) data.get("nightStage");
            if (stage == null) stage = "mafia";

            // Не моя стадия, или я не имею права действовать — игнорируем (защита от устаревшего тапа)
            if (myRole == null || !myRole.equals(stage) || !isAlive(players, uid)) return null;

            Map<String, Object> nightActions = asMap(data.get("nightActions"));
            Map<String, Object> updatedNightActions = new HashMap<>(nightActions);
            boolean stageComplete;
            Map<String, Object> writeNow = new HashMap<>();

            if ("mafia".equals(stage)) {
                Map<String, Object> mafiaVotes = new HashMap<>(asMap(nightActions.get("mafia")));
                mafiaVotes.put(uid, targetId);
                updatedNightActions.put("mafia", mafiaVotes);
                writeNow.put("nightActions.mafia." + uid, targetId);
                stageComplete = mafiaVotes.size() >= countAliveRole(players, "mafia");
            } else {
                updatedNightActions.put(stage, targetId);
                writeNow.put("nightActions." + stage, targetId);
                stageComplete = true; // у доктора и шерифа всего один действующий игрок
            }

            if (!stageComplete) {
                transaction.update(docRef, writeNow);
                return null;
            }

            String next = nextLivingStage(players, stage);
            if (next != null) {
                writeNow.put("nightStage", next);
                transaction.update(docRef, writeNow);
                return null;
            }

            // Это было последнее необходимое действие этой ночи — сразу считаем итог.
            int currentDay = intOf(data.get("dayNumber"), 1);
            NightResultProcessor.NightResult result =
                    NightResultProcessor.resolve(players, updatedNightActions, currentDay);
            transaction.update(docRef, result.updates);
            return null;
        }), cb);
    }

    // ── Игрок отправляет дневной голос ─────────────────────────────────────
    public static void submitDayVote(FirebaseFirestore db, String roomId, String uid,
                                      String targetId, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        run(db.runTransaction(transaction -> {
            DocumentSnapshot snap = transaction.get(docRef);
            if (!snap.exists()) return null;
            Map<String, Object> data = snap.getData();
            if (data == null) return null;
            if (!"voting".equals(data.get("phase"))) return null;

            Map<String, Object> players = asMap(data.get("players"));
            if (!isAlive(players, uid)) return null;

            Map<String, Object> dayVotes = new HashMap<>(asMap(data.get("dayVotes")));
            dayVotes.put(uid, targetId);

            int aliveCount = countAliveRole(players, null);
            if (dayVotes.size() < aliveCount) {
                transaction.update(docRef, "dayVotes." + uid, targetId);
                return null;
            }

            int currentDay = intOf(data.get("dayNumber"), 1);
            DayVotingProcessor.VoteResult result =
                    DayVotingProcessor.resolve(players, dayVotes, currentDay);
            transaction.update(docRef, result.updates);
            return null;
        }), cb);
    }

    // ── Принудительные переходы по таймауту (может вызвать ЛЮБОЙ клиент) ───

    public static void forceAdvanceNightStage(FirebaseFirestore db, String roomId,
                                               String expectedStage, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        run(db.runTransaction(transaction -> {
            DocumentSnapshot snap = transaction.get(docRef);
            if (!snap.exists()) return null;
            Map<String, Object> data = snap.getData();
            if (data == null) return null;
            if (!"night".equals(data.get("phase"))) return null;

            String stage = (String) data.get("nightStage");
            if (stage == null) stage = "mafia";
            if (!stage.equals(expectedStage)) return null; // кто-то уже продвинул стадию

            Map<String, Object> players = asMap(data.get("players"));
            Map<String, Object> nightActions = asMap(data.get("nightActions"));

            String next = nextLivingStage(players, stage);
            if (next != null) {
                transaction.update(docRef, "nightStage", next);
                return null;
            }

            int currentDay = intOf(data.get("dayNumber"), 1);
            NightResultProcessor.NightResult result =
                    NightResultProcessor.resolve(players, nightActions, currentDay);
            transaction.update(docRef, result.updates);
            return null;
        }), cb);
    }

    public static void forceResolveVoting(FirebaseFirestore db, String roomId, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        run(db.runTransaction(transaction -> {
            DocumentSnapshot snap = transaction.get(docRef);
            if (!snap.exists()) return null;
            Map<String, Object> data = snap.getData();
            if (data == null) return null;
            if (!"voting".equals(data.get("phase"))) return null;

            Map<String, Object> players = asMap(data.get("players"));
            Map<String, Object> dayVotes = asMap(data.get("dayVotes"));
            int currentDay = intOf(data.get("dayNumber"), 1);
            DayVotingProcessor.VoteResult result =
                    DayVotingProcessor.resolve(players, dayVotes, currentDay);
            transaction.update(docRef, result.updates);
            return null;
        }), cb);
    }

    public static void forceStartVoting(FirebaseFirestore db, String roomId, OnTxComplete cb) {
        DocumentReference docRef = ref(db, roomId);
        run(db.runTransaction(transaction -> {
            DocumentSnapshot snap = transaction.get(docRef);
            if (!snap.exists()) return null;
            Map<String, Object> data = snap.getData();
            if (data == null) return null;
            if (!"day".equals(data.get("phase"))) return null; // уже не "день" — поздно/не нужно

            transaction.update(docRef, "phase", "voting");
            return null;
        }), cb);
    }

    // ── Вспомогательные функции ─────────────────────────────────────────────

    /** Следующая ночная стадия после currentStage, пропуская роли без живых носителей. null = ночь окончена. */
    private static String nextLivingStage(Map<String, Object> players, String currentStage) {
        String s = currentStage;
        while (true) {
            if ("mafia".equals(s)) s = "doctor";
            else if ("doctor".equals(s)) s = "sheriff";
            else return null;

            if (countAliveRole(players, s) > 0) return s;
        }
    }

    private static int countAliveRole(Map<String, Object> players, String role) {
        int n = 0;
        if (players == null) return 0;
        for (Object v : players.values()) {
            if (!(v instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) v;
            if (!isAliveFlag(p.get("alive"))) continue;
            Object r = p.get("role");
            String rr = r != null ? String.valueOf(r).trim().toLowerCase() : null;
            if (role == null || role.equals(rr)) n++;
        }
        return n;
    }

    private static String roleOf(Map<String, Object> players, String uid) {
        Object p = players != null ? players.get(uid) : null;
        if (p instanceof Map) {
            Object r = ((Map<?, ?>) p).get("role");
            if (r != null) {
                String s = String.valueOf(r).trim().toLowerCase();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private static boolean isAlive(Map<String, Object> players, String uid) {
        Object p = players != null ? players.get(uid) : null;
        if (!(p instanceof Map)) return false;
        return isAliveFlag(((Map<?, ?>) p).get("alive"));
    }

    private static boolean isAliveFlag(Object aliveObj) {
        if (aliveObj == null) return false;
        if (aliveObj instanceof Boolean) return (Boolean) aliveObj;
        if (aliveObj instanceof Number) return ((Number) aliveObj).intValue() != 0;
        String s = String.valueOf(aliveObj).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static int intOf(Object o, int def) {
        return (o instanceof Number) ? ((Number) o).intValue() : def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? new HashMap<>((Map<String, Object>) o) : new HashMap<>();
    }
}
