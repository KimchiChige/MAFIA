package com.example.mafia.classes;

import java.util.ArrayList;
import java.util.List;

public class Game {
    private List<Player> players;
    private String phase;
    private int dayNumber;

    public Game(){
        this.players = new ArrayList<>();
        this.phase = "night";
        this.dayNumber = 1;
    }

    public void addPlayer(Player player){
        players.add(player);
    }

    public List<Player> getPlayers() { return players; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public int getDayNumber() { return dayNumber; }
    public void setDayNumber(int dayNumber) { this.dayNumber = dayNumber; }
}
