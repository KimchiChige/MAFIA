package com.example.mafia.classes;

public class Player {
    private String id;
    private String name;
    private String role;
    private boolean isAlive;
    private boolean isReady;
    private boolean isHost;
    private String photoUrl;

    public Player() {
        this.isAlive = true;
        this.isReady = false;
        this.isHost = false;
    }

    public Player(String id, String name){
        this.id = id;
        this.name = name;
        this.isAlive = true;
        this.isReady = false;
        this.isHost = false;
        this.role = "civilian";
    }

    public String getId() {return id;}
    public void setId(String id) {this.id = id;}

    public String getName() {return name;}
    public void setName(String name) {this.name = name;}

    public String getRole() {return role;}
    public void setRole(String role) {this.role = role;}

    public boolean isAlive() {return isAlive;}
    public void setAlive(boolean alive) {this.isAlive = alive;}

    public boolean isReady() {return isReady;}
    public void setReady(boolean ready) {isReady = ready;}

    public boolean isHost() {return isHost;}
    public void setHost(boolean host) {isHost = host;}

    public String getPhotoUrl() {return photoUrl;}
    public void setPhotoUrl(String photoUrl) {this.photoUrl = photoUrl;}
}