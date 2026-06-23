package com.example.mafia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ЧИСТЫЙ (без обращений к Firestore) калькулятор итогов ночи.
 *
 * ВАЖНО: раньше этот класс сам читал документ игры и сам писал в Firestore через
 * .get().addOnSuccessListener(...).update(...). Это классический паттерн "прочитать-изменить-
 * записать" без какой-либо защиты от гонки: если два устройства (например, из-за дублирования
 * хоста в RoomActivity) одновременно вызывали process(), оба читали одно и то же старое
 * состояние и оба писали результат — итог зависел от того, чья запись попадёт в базу последней.
 * Это было одной из главных причин зависаний и "съеденных" ночей.
 *
 * Теперь класс ничего не пишет сам — он только СЧИТАЕТ результат на основе уже прочитанных
 * данных и возвращает набор полей для записи. Запись выполняется снаружи, внутри единой
 * Firestore-транзакции (см. GameTransactions), которая атомарно читает и пишет документ —
 * благодаря этому повторный/параллельный вызов больше не может испортить состояние игры.
 */
public class NightResultProcessor {

    public static class NightResult {
        public String killedPlayerId;
        public String killedPlayerName;
        public String killedPlayerRole;
        public boolean wasKillBlocked;
        public String sheriffTargetId;
        public String sheriffTargetName;
        public String sheriffTargetRole;
        public String gameEndWinner;

        /** Итоговое состояние players после применения смерти (для пересчёта дальнейшей логики). */
        public Map<String, Object> updatedPlayers;

        /** Готовый набор полей для transaction.update(gameRef, updates). */
        public Map<String, Object> updates;
    }

    public static NightResult resolve(Map<String, Object> players,
                                      Map<String, Object> nightActions,
                                      int currentDayNumber) {
        NightResult result = new NightResult();

        if (players == null) players = new HashMap<>();

        // 1. Жертва мафии (большинство голосов; при ничьей — никто не умирает)
        Map<String, Object> mafiaVotes = nightActions != null ? asMap(nightActions.get("mafia")) : null;
        String mafiaTarget = getMafiaTarget(mafiaVotes);

        // 2. Спас ли доктор именно эту жертву
        String doctorSave = nightActions != null ? asString(nightActions.get("doctor")) : null;
        boolean saved = mafiaTarget != null && mafiaTarget.equals(doctorSave);

        // 3. Кого проверил шериф
        String sheriffTarget = nightActions != null ? asString(nightActions.get("sheriff")) : null;
        if (sheriffTarget != null) {
            result.sheriffTargetId = sheriffTarget;
            result.sheriffTargetRole = getPlayerRole(sheriffTarget, players);
            result.sheriffTargetName = getPlayerName(sheriffTarget, players);
        }

        // 4. Применяем смерть (если есть и не спасена)
        Map<String, Object> updates = new HashMap<>();
        int nextDay = currentDayNumber + 1;

        Map<String, Object> updatedPlayers = deepCopyPlayers(players);

        if (mafiaTarget != null && !saved && updatedPlayers.containsKey(mafiaTarget)) {
            Map<String, Object> victim = asMap(updatedPlayers.get(mafiaTarget));
            victim.put("alive", false);
            updatedPlayers.put(mafiaTarget, victim);

            updates.put("players." + mafiaTarget + ".alive", false);
            result.killedPlayerId = mafiaTarget;
            result.killedPlayerName = getPlayerName(mafiaTarget, players);
            result.killedPlayerRole = getPlayerRole(mafiaTarget, players);
            result.wasKillBlocked = false;
        } else if (mafiaTarget != null) {
            result.wasKillBlocked = true;
        } else {
            result.wasKillBlocked = false;
        }

        // 5. Проверяем условие победы по итогам ночи
        String winner = checkWinCondition(updatedPlayers);
        result.gameEndWinner = winner;
        result.updatedPlayers = updatedPlayers;

        if (winner != null) {
            updates.put("phase", "ended");
            updates.put("winner", winner);
        } else {
            updates.put("phase", "day");
            updates.put("dayNumber", nextDay);
        }

        // Очищаем ночные действия для следующей ночи
        Map<String, Object> emptyNightActions = new HashMap<>();
        emptyNightActions.put("mafia", new HashMap<String, String>());
        emptyNightActions.put("doctor", null);
        emptyNightActions.put("sheriff", null);
        updates.put("nightActions", emptyNightActions);
        updates.put("nightStage", "mafia");

        // Сохраняем итог ночи для экрана-объявления (NightResultActivity)
        Map<String, Object> nightResultData = new HashMap<>();
        nightResultData.put("killedPlayerId", result.killedPlayerId);
        nightResultData.put("killedPlayerName", result.killedPlayerName);
        nightResultData.put("killedPlayerRole", result.killedPlayerRole);
        nightResultData.put("wasKillBlocked", result.wasKillBlocked);
        nightResultData.put("sheriffTargetId", result.sheriffTargetId);
        nightResultData.put("sheriffTargetName", result.sheriffTargetName);
        nightResultData.put("sheriffTargetRole", result.sheriffTargetRole);
        nightResultData.put("gameEndWinner", result.gameEndWinner);
        updates.put("lastNightResult", nightResultData);
        // Очищаем результат прошлого голосования — при переподключении не должен показываться снова
        updates.put("lastVoteResult", null);

        result.updates = updates;
        return result;
    }

    private static String getMafiaTarget(Map<String, Object> mafiaVotes) {
        if (mafiaVotes == null || mafiaVotes.isEmpty()) return null;

        Map<String, Integer> voteCounts = new HashMap<>();
        for (Object targetId : mafiaVotes.values()) {
            if (targetId != null) {
                String id = targetId.toString();
                voteCounts.put(id, voteCounts.getOrDefault(id, 0) + 1);
            }
        }
        if (voteCounts.isEmpty()) return null;

        int maxVotes = Collections.max(voteCounts.values());
        List<String> topTargets = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() == maxVotes) topTargets.add(entry.getKey());
        }
        return topTargets.size() == 1 ? topTargets.get(0) : null; // ничья = никто не умирает
    }

    static String getPlayerRole(String playerId, Map<String, Object> players) {
        if (playerId == null || players == null) return null;
        Object pObj = players.get(playerId);
        if (pObj instanceof Map) {
            Object r = ((Map<?, ?>) pObj).get("role");
            if (r != null) {
                String s = String.valueOf(r).trim().toLowerCase();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    static String getPlayerName(String playerId, Map<String, Object> players) {
        if (playerId == null || players == null) return "Игрок";
        Object pObj = players.get(playerId);
        if (pObj instanceof Map) {
            Object n = ((Map<?, ?>) pObj).get("name");
            if (n != null) {
                String s = String.valueOf(n).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "Игрок";
    }

    static String checkWinCondition(Map<String, Object> players) {
        int aliveCity = 0;
        int aliveMafia = 0;

        if (players == null) return null;

        for (Map.Entry<String, Object> entry : players.entrySet()) {
            Map<String, Object> p = asMap(entry.getValue());
            if (p == null) continue;
            if (!isAlive(p.get("alive"))) continue;

            String role = getPlayerRole(entry.getKey(), players);
            if ("mafia".equals(role)) aliveMafia++;
            else if (role != null) aliveCity++;
        }

        if (aliveMafia == 0) return "city";
        if (aliveMafia >= aliveCity) return "mafia";
        return null;
    }

    static boolean isAlive(Object aliveObj) {
        if (aliveObj == null) return false;
        if (aliveObj instanceof Boolean) return (Boolean) aliveObj;
        if (aliveObj instanceof Number) return ((Number) aliveObj).intValue() != 0;
        String s = String.valueOf(aliveObj).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    static String asString(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopyPlayers(Map<String, Object> players) {
        Map<String, Object> copy = new HashMap<>();
        if (players == null) return copy;
        for (Map.Entry<String, Object> e : players.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) {
                copy.put(e.getKey(), new HashMap<>((Map<String, Object>) v));
            } else {
                copy.put(e.getKey(), v);
            }
        }
        return copy;
    }
}
