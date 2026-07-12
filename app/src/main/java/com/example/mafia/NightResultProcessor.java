package com.example.mafia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Обрабатывает итог ночи.
 *
 * Логика плюшек (флаги activePerk_* читаются из players.{uid} документа игры):
 *
 *  • activePerk_shield    — если мафия выбрала этого игрока → атака блокируется,
 *                           плюшка расходуется (perk_shield -= 1 в users/{uid}).
 *  • activePerk_invisible — мафия вообще не может выбрать этого игрока,
 *                           плюшка расходуется после ночи.
 *  • activePerk_selfheal  — доктор (сам игрок) может выбрать себя в ночное действие,
 *                           плюшка расходуется если доктор реально выбрал себя.
 *
 * После использования флаг activePerk_* сбрасывается в false в документе игры,
 * а счётчик perk_* уменьшается на 1 в users/{uid} через отдельное обновление.
 */
public class NightResultProcessor {

    public static class NightResult {
        public String killedPlayerId;
        public String killedPlayerName;
        public String killedPlayerRole;
        public String killedPlayerPhoto;
        public boolean wasKillBlocked;
        public String sheriffTargetId;
        public String sheriffTargetName;
        public String sheriffTargetRole;
        public String gameEndWinner;
        public Map<String, Object> updatedPlayers;
        public Map<String, Object> updates;
        // uid -> perk_type — плюшки, которые нужно списать из инвентаря после транзакции
        public Map<String, String> perksToConsume = new HashMap<>();
    }

    public static NightResult resolve(Map<String, Object> players,
                                      Map<String, Object> nightActions,
                                      int currentDayNumber) {
        return resolve(players, nightActions, currentDayNumber, null);
    }

    /**
     * @param perksData  устарело, оставлено для совместимости; теперь плюшки
     *                   читаются прямо из players.{uid}.activePerk_* (второй аргумент — тот же map).
     *                   Передавайте null или тот же players — не важно.
     *                   Начиная с версии Lover Role, этот параметр также используется как
     *                   полный gameData для чтения loverPair.
     */
    @SuppressWarnings("unchecked")
    public static NightResult resolve(Map<String, Object> players,
                                      Map<String, Object> nightActions,
                                      int currentDayNumber,
                                      Map<String, Object> perksData) {
        NightResult result = new NightResult();
        if (players == null) players = new HashMap<>();

        // 1. Жертва мафии — с учётом невидимки
        Map<String, Object> mafiaVotes = nightActions != null ? asMap(nightActions.get("mafia")) : null;
        String mafiaTarget = getMafiaTarget(mafiaVotes, players);

        // 2. Щит: активен у жертвы?
        boolean shieldBlocked = false;
        if (mafiaTarget != null) {
            Map<String, Object> victimData = asMap(players.get(mafiaTarget));
            if (victimData != null && Boolean.TRUE.equals(victimData.get("activePerk_shield"))) {
                shieldBlocked = true;
            }
        }

        // 3. Доктор
        String doctorSave = nightActions != null ? asString(nightActions.get("doctor")) : null;
        // Самолечение: доктор выбрал себя — это валидно только если у него активна плюшка
        boolean selfhealUsed = false;
        if (doctorSave != null) {
            Map<String, Object> doctorData = findDoctorData(players);
            String doctorId = findDoctorId(players);
            if (doctorId != null && doctorId.equals(doctorSave)) {
                // Доктор выбрал себя
                if (doctorData != null && Boolean.TRUE.equals(doctorData.get("activePerk_selfheal"))) {
                    selfhealUsed = true; // разрешено и будет расходовать плюшку
                } else {
                    doctorSave = null; // без плюшки самолечение недействительно
                }
            }
        }

        boolean doctorSaved = mafiaTarget != null && mafiaTarget.equals(doctorSave);
        boolean saved = doctorSaved || shieldBlocked;

        // 4. Шериф
        String sheriffTarget = nightActions != null ? asString(nightActions.get("sheriff")) : null;
        if (sheriffTarget != null) {
            result.sheriffTargetId   = sheriffTarget;
            result.sheriffTargetRole = getPlayerRole(sheriffTarget, players);
            result.sheriffTargetName = getPlayerName(sheriffTarget, players);
        }

        // 5. Применяем смерть
        Map<String, Object> updates = new HashMap<>();
        Map<String, Object> updatedPlayers = deepCopyPlayers(players);

        int deathOrder = 0;
        for (Map.Entry<String, Object> e : players.entrySet()) {
            Map<String, Object> p = asMap(e.getValue());
            if (p == null) continue;
            Object dp = p.get("deathPosition");
            if (dp instanceof Number && ((Number) dp).intValue() > 0) deathOrder++;
        }

        if (mafiaTarget != null && !saved) {
            Map<String, Object> victim = asMap(updatedPlayers.get(mafiaTarget));
            int newDeathPos = deathOrder + 1;
            victim.put("alive", false);
            victim.put("deathPosition", newDeathPos);
            updatedPlayers.put(mafiaTarget, victim);
            updates.put("players." + mafiaTarget + ".alive", false);
            updates.put("players." + mafiaTarget + ".deathPosition", newDeathPos);

            result.killedPlayerId   = mafiaTarget;
            result.killedPlayerName = getPlayerName(mafiaTarget, players);
            result.killedPlayerRole = getPlayerRole(mafiaTarget, players);
            result.killedPlayerPhoto= getPlayerPhoto(mafiaTarget, players);
            result.wasKillBlocked   = false;
        } else if (mafiaTarget != null) {
            result.wasKillBlocked = true;
        }

        // 5b. Chain death from lover pair (after main death, before win check)
        applyLoverChainDeath(updatedPlayers, updates, perksData);

        // 6. Сбрасываем флаги активных плюшек в документе игры и планируем списание

        // Невидимка — расходуется у всех игроков с activePerk_invisible (ночь прошла)
        for (Map.Entry<String, Object> e : players.entrySet()) {
            Map<String, Object> pd = asMap(e.getValue());
            if (pd == null) continue;
            if (Boolean.TRUE.equals(pd.get("activePerk_invisible"))) {
                updates.put("players." + e.getKey() + ".activePerk_invisible", false);
                result.perksToConsume.put(e.getKey(), "invisible");
            }
        }

        // Щит — расходуется только если заблокировал атаку
        if (shieldBlocked && mafiaTarget != null) {
            updates.put("players." + mafiaTarget + ".activePerk_shield", false);
            result.perksToConsume.put(mafiaTarget, "shield");
        }

        // Самолечение — расходуется если доктор реально выбрал себя
        if (selfhealUsed) {
            String doctorId = findDoctorId(players);
            if (doctorId != null) {
                updates.put("players." + doctorId + ".activePerk_selfheal", false);
                result.perksToConsume.put(doctorId, "selfheal");
            }
        }

        // 7. Победа?
        String winner = checkWinCondition(updatedPlayers);
        result.gameEndWinner  = winner;
        result.updatedPlayers = updatedPlayers;

        if (winner != null) {
            updates.put("phase",  "ended");
            updates.put("winner", winner);
        } else {
            updates.put("phase",     "day");
            updates.put("dayNumber", currentDayNumber + 1);
            updates.put("phaseStartAt", System.currentTimeMillis());
        }

        // Очистка ночных действий
        Map<String, Object> emptyNightActions = new HashMap<>();
        emptyNightActions.put("mafia", new HashMap<String, String>());
        emptyNightActions.put("doctor", null);
        emptyNightActions.put("sheriff", null);
        emptyNightActions.put("lover", null);
        updates.put("nightActions", emptyNightActions);
        updates.put("nightStage", "mafia");

        Map<String, Object> nightResultData = new HashMap<>();
        nightResultData.put("killedPlayerId",   result.killedPlayerId);
        nightResultData.put("killedPlayerName", result.killedPlayerName);
        nightResultData.put("killedPlayerRole", result.killedPlayerRole);
        nightResultData.put("killedPlayerPhoto",result.killedPlayerPhoto);
        nightResultData.put("wasKillBlocked",   result.wasKillBlocked);
        nightResultData.put("sheriffTargetId",  result.sheriffTargetId);
        nightResultData.put("sheriffTargetName",result.sheriffTargetName);
        nightResultData.put("sheriffTargetRole",result.sheriffTargetRole);
        nightResultData.put("gameEndWinner",    result.gameEndWinner);
        updates.put("lastNightResult", nightResultData);
        updates.put("lastVoteResult",  null);

        result.updates = updates;
        return result;
    }

    // ── Lover chain death (shared between Night and Day processors) ──

    /**
     * If loverPair exists and one member is dead, kill the other (chain death).
     * Operates on updatedPlayers (deep copy) and adds to updates map.
     * Clears loverPair in updates after chain death.
     */
    @SuppressWarnings("unchecked")
    public static void applyLoverChainDeath(Map<String, Object> updatedPlayers,
                                            Map<String, Object> updates,
                                            Map<String, Object> gameData) {
        if (gameData == null) return;
        List<String> loverPair = null;
        Object lpObj = gameData.get("loverPair");
        if (lpObj instanceof List) {
            loverPair = new ArrayList<>((List<String>) lpObj);
        }
        if (loverPair == null || loverPair.size() != 2) return;
        if (loverPair.get(0) == null || loverPair.get(1) == null) return;

        for (int i = 0; i < 2; i++) {
            String uid1 = loverPair.get(i);
            Map<String, Object> p1 = asMap(updatedPlayers.get(uid1));
            if (p1 == null) continue;
            if (!isAlive(p1.get("alive"))) {
                // This one is dead, kill the other
                String uid2 = loverPair.get(1 - i);
                Map<String, Object> p2 = asMap(updatedPlayers.get(uid2));
                if (p2 == null || !isAlive(p2.get("alive"))) continue;

                int newDeathPos = 0;
                for (Map.Entry<String, Object> e : updatedPlayers.entrySet()) {
                    Map<String, Object> pd = asMap(e.getValue());
                    if (pd == null) continue;
                    Object dp = pd.get("deathPosition");
                    if (dp instanceof Number && ((Number) dp).intValue() > 0) newDeathPos++;
                }
                newDeathPos++;

                p2.put("alive", false);
                p2.put("deathPosition", newDeathPos);
                updatedPlayers.put(uid2, p2);
                updates.put("players." + uid2 + ".alive", false);
                updates.put("players." + uid2 + ".deathPosition", newDeathPos);

                // Clear the pair
                updates.put("loverPair", new ArrayList<>());
                break; // Only one chain death per resolve
            }
        }
    }

    /** Находит жертву мафии, пропуская игроков с activePerk_invisible. */
    @SuppressWarnings("unchecked")
    private static String getMafiaTarget(Map<String, Object> mafiaVotes,
                                         Map<String, Object> players) {
        if (mafiaVotes == null || mafiaVotes.isEmpty()) return null;

        Map<String, Integer> voteCounts = new HashMap<>();
        for (Object targetId : mafiaVotes.values()) {
            if (targetId == null) continue;
            String id = targetId.toString();
            // Пропускаем невидимых игроков
            Map<String, Object> targetData = asMap(players.get(id));
            if (targetData != null && Boolean.TRUE.equals(targetData.get("activePerk_invisible"))) continue;
            voteCounts.put(id, voteCounts.getOrDefault(id, 0) + 1);
        }
        if (voteCounts.isEmpty()) return null;

        int maxVotes = Collections.max(voteCounts.values());
        List<String> top = new ArrayList<>();
        for (Map.Entry<String, Integer> e : voteCounts.entrySet())
            if (e.getValue() == maxVotes) top.add(e.getKey());
        return top.size() == 1 ? top.get(0) : null;
    }

    /** Возвращает uid доктора среди живых игроков. */
    private static String findDoctorId(Map<String, Object> players) {
        for (Map.Entry<String, Object> e : players.entrySet()) {
            Map<String, Object> p = asMap(e.getValue());
            if (p == null || !isAlive(p.get("alive"))) continue;
            if ("doctor".equals(getPlayerRole(e.getKey(), players))) return e.getKey();
        }
        return null;
    }

    private static Map<String, Object> findDoctorData(Map<String, Object> players) {
        String id = findDoctorId(players);
        return id != null ? asMap(players.get(id)) : null;
    }

    // ── Вспомогательные ──────────────────────────────────────────

    static String getPlayerRole(String playerId, Map<String, Object> players) {
        if (playerId == null || players == null) return null;
        Object pObj = players.get(playerId);
        if (pObj instanceof Map) {
            Object r = ((Map<?, ?>) pObj).get("role");
            if (r != null) { String s = String.valueOf(r).trim().toLowerCase(); if (!s.isEmpty()) return s; }
        }
        return null;
    }

    static String getPlayerName(String playerId, Map<String, Object> players) {
        if (playerId == null || players == null) return "Игрок";
        Object pObj = players.get(playerId);
        if (pObj instanceof Map) {
            Object n = ((Map<?, ?>) pObj).get("name");
            if (n != null) { String s = String.valueOf(n).trim(); if (!s.isEmpty()) return s; }
        }
        return "Игрок";
    }

    static String getPlayerPhoto(String playerId, Map<String, Object> players) {
        if (playerId == null || players == null) return null;
        Object pObj = players.get(playerId);
        if (pObj instanceof Map) {
            Object p = ((Map<?, ?>) pObj).get("photoBase64");
            if (p != null) return String.valueOf(p);
        }
        return null;
    }

    static String checkWinCondition(Map<String, Object> players) {
        int aliveCity = 0, aliveMafia = 0;
        if (players == null) return null;
        for (Map.Entry<String, Object> e : players.entrySet()) {
            Map<String, Object> p = asMap(e.getValue());
            if (p == null || !isAlive(p.get("alive"))) continue;
            String role = getPlayerRole(e.getKey(), players);
            if ("mafia".equals(role)) aliveMafia++;
            else if (role != null)    aliveCity++;
        }
        if (aliveMafia == 0)         return "city";
        if (aliveMafia >= aliveCity) return "mafia";
        return null;
    }

    static boolean isAlive(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number)  return ((Number) o).intValue() != 0;
        String s = String.valueOf(o).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    static String asString(Object o) { return o != null ? String.valueOf(o) : null; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopyPlayers(Map<String, Object> players) {
        Map<String, Object> copy = new HashMap<>();
        if (players == null) return copy;
        for (Map.Entry<String, Object> e : players.entrySet()) {
            Object v = e.getValue();
            copy.put(e.getKey(), (v instanceof Map) ? new HashMap<>((Map<String, Object>) v) : v);
        }
        return copy;
    }

    static int countAliveRole(Map<String, Object> players, String role) {
        int n = 0;
        if (players == null) return 0;
        for (Map.Entry<String, Object> e : players.entrySet()) {
            Map<String, Object> p = asMap(e.getValue());
            if (p == null || !isAlive(p.get("alive"))) continue;
            if (role == null || role.equals(getPlayerRole(e.getKey(), players))) n++;
        }
        return n;
    }
}