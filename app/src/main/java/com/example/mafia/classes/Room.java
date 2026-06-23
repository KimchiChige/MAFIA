package com.example.mafia.classes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Room {

    private String id;
    private String name;
    private String creatorId;
    private String creatorName;
    private int maxPlayers;
    private int currentPlayers;
    private String status;
    private String code;
    private boolean isPrivate;
    private long createdAt;

    private List<String> participants = new ArrayList<>();
    private Map<String, Boolean> readyStatus = new HashMap<>();

    public Room() {}

    public static Room createNewRoom(String name, String creatorId, String creatorName, int maxPlayers, boolean isPrivate) {

        Room room = new Room();

        room.setName(name);
        room.setCreatorId(creatorId);
        room.setCreatorName(creatorName);
        room.setMaxPlayers(maxPlayers);
        room.setStatus("waiting");
        room.setCode(generateRoomCode());
        room.setPrivate(isPrivate);

        room.participants = new ArrayList<>();
        room.readyStatus = new HashMap<>();

        room.participants.add(creatorId);
        room.readyStatus.put(creatorId,false);

        room.currentPlayers = 1;

        // ИСПРАВЛЕНО: раньше createdAt никогда не устанавливался (оставался 0),
        // из-за чего сортировка списка комнат по дате в LobbyActivity была случайной.
        room.createdAt = System.currentTimeMillis();

        return room;
    }

    private static String generateRoomCode() {
        return UUID.randomUUID().toString().substring(0,6).toUpperCase();
    }

    public boolean addParticipant(String userId){

        if(participants == null)
            participants = new ArrayList<>();

        if(readyStatus == null)
            readyStatus = new HashMap<>();

        if(participants.contains(userId))
            return false;

        participants.add(userId);

        if(!readyStatus.containsKey(userId))
            readyStatus.put(userId,false);

        currentPlayers = participants.size();

        return true;
    }

    public void removeParticipant(String userId){

        if(participants != null)
            participants.remove(userId);

        if(readyStatus != null)
            readyStatus.remove(userId);

        currentPlayers = participants.size();
    }

    public boolean isParticipant(String userId){
        return participants != null && participants.contains(userId);
    }

    public boolean isReady(String userId){

        if(readyStatus == null)
            return false;

        Boolean ready = readyStatus.get(userId);

        return ready != null && ready;
    }

    public void setReady(String userId, boolean ready){

        if(readyStatus == null)
            readyStatus = new HashMap<>();

        readyStatus.put(userId,ready);
    }

    /**
     * ИСПРАВЛЕННАЯ ПРОВЕРКА ГОТОВНОСТИ
     */
    public boolean areAllPlayersReady(){

        if(participants == null || participants.size() < 2)
            return false;

        if(readyStatus == null)
            return false;

        for(String player : participants){

            Boolean ready = readyStatus.get(player);

            if(ready == null || !ready)
                return false;
        }

        return true;
    }

    public String getId(){ return id; }
    public void setId(String id){ this.id = id; }

    public String getName(){ return name; }
    public void setName(String name){ this.name = name; }

    public String getCreatorId(){ return creatorId; }
    public void setCreatorId(String creatorId){ this.creatorId = creatorId; }

    public String getCreatorName(){ return creatorName; }
    public void setCreatorName(String creatorName){ this.creatorName = creatorName; }

    public int getMaxPlayers(){ return maxPlayers; }
    public void setMaxPlayers(int maxPlayers){ this.maxPlayers = maxPlayers; }

    public int getCurrentPlayers(){ return currentPlayers; }
    public void setCurrentPlayers(int currentPlayers){ this.currentPlayers = currentPlayers; }

    public String getStatus(){ return status; }
    public void setStatus(String status){ this.status = status; }

    public String getCode(){ return code; }
    public void setCode(String code){ this.code = code; }

    public boolean isPrivate(){ return isPrivate; }
    public void setPrivate(boolean aPrivate){ isPrivate = aPrivate; }

    public long getCreatedAt(){ return createdAt; }
    public void setCreatedAt(long createdAt){ this.createdAt = createdAt; }

    public List<String> getParticipants(){ return participants; }
    public void setParticipants(List<String> participants){ this.participants = participants; }

    public Map<String, Boolean> getReadyStatus(){ return readyStatus; }
    public void setReadyStatus(Map<String, Boolean> readyStatus){ this.readyStatus = readyStatus; }
}