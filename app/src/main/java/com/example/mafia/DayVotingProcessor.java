package com.example.mafia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.mafia.NightResultProcessor.asMap;
import static com.example.mafia.NightResultProcessor.checkWinCondition;
import static com.example.mafia.NightResultProcessor.deepCopyPlayers;
import static com.example.mafia.NightResultProcessor.getPlayerName;
import static com.example.mafia.NightResultProcessor.getPlayerRole;

/**
 * ЧИСТЫЙ (без обращений к Firestore) калькулятор итогов дневного голосования.
 * См. комментарий в NightResultProcessor — та же логика: считаем результат,
 * запись делает вызывающий код внутри Firestore-транзакции.
 */
public class DayVotingProcessor {

    public static class VoteResult {
        public String executedPlayerId;
        public String executedPlayerName;
        public String executedPlayerRole;
        public boolean wasTie;
        public int topVoteCount;
        public int totalVotes;
        public String gameEndWinner;
        public Map<String, Object> updatedPlayers;
        public Map<String, Object> updates;
    }

    public static VoteResult resolve(Map<String, Object> players,
                                     Map<String, Object> dayVotes,
                                     int currentDayNumber) {
        VoteResult result = new VoteResult();
        if (players == null) players = new HashMap<>();

        String topTarget = null;
        Map<String, Integer> voteCounts = new HashMap<>();

        if (dayVotes != null) {
            for (Object rawTarget : dayVotes.values()) {
                if (rawTarget == null) continue;
                String id = String.valueOf(rawTarget).trim();
                if (id.isEmpty() || !players.containsKey(id)) continue;
                voteCounts.put(id, voteCounts.getOrDefault(id, 0) + 1);
            }
        }
        result.totalVotes = dayVotes != null ? dayVotes.size() : 0;

        if (!voteCounts.isEmpty()) {
            int maxVotes = Collections.max(voteCounts.values());
            List<String> topTargets = new ArrayList<>();
            for (Map.Entry<String, Integer> e : voteCounts.entrySet()) {
                if (e.getValue() == maxVotes) topTargets.add(e.getKey());
            }
            if (topTargets.size() == 1) {
                topTarget = topTargets.get(0);
                result.topVoteCount = maxVotes;
            } else {
                result.wasTie = true;
            }
        }

        Map<String, Object> updates = new HashMap<>();
        Map<String, Object> updatedPlayers = deepCopyPlayers(players);

        if (topTarget != null && !result.wasTie) {
            Map<String, Object> victim = asMap(updatedPlayers.get(topTarget));
            if (victim != null) {
                victim.put("alive", false);
                updatedPlayers.put(topTarget, victim);
            }
            updates.put("players." + topTarget + ".alive", false);
            result.executedPlayerId = topTarget;
            result.executedPlayerName = getPlayerName(topTarget, players);
            result.executedPlayerRole = getPlayerRole(topTarget, players);
        }

        String winner = checkWinCondition(updatedPlayers);
        result.gameEndWinner = winner;
        result.updatedPlayers = updatedPlayers;

        if (winner != null) {
            updates.put("phase", "ended");
            updates.put("winner", winner);
        } else {
            updates.put("phase", "night");
            updates.put("nightStage", "mafia");
            updates.put("dayNumber", currentDayNumber + 1);
        }

        Map<String, Object> voteResultData = new HashMap<>();
        voteResultData.put("executedPlayerId", result.executedPlayerId != null ? result.executedPlayerId : "");
        voteResultData.put("executedPlayerName", result.executedPlayerName != null ? result.executedPlayerName : "");
        voteResultData.put("executedPlayerRole", result.executedPlayerRole != null ? result.executedPlayerRole : "");
        voteResultData.put("wasTie", result.wasTie);
        voteResultData.put("topVoteCount", result.topVoteCount);
        voteResultData.put("totalVotes", result.totalVotes);
        if (winner != null) voteResultData.put("gameEndWinner", winner);
        updates.put("lastVoteResult", voteResultData);
        updates.put("dayVotes", new HashMap<String, String>());
        // Очищаем результат прошлой ночи — при переподключении игрок не должен его снова видеть
        updates.put("lastNightResult", null);

        result.updates = updates;
        return result;
    }
}
