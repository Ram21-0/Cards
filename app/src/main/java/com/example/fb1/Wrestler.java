package com.example.fb1;

import android.os.Parcel;
import android.os.Parcelable;

public class Wrestler implements Parcelable {

    private String name, brand, img;
    private int rank, fight, feet, inches, weight;
    private double chest, biceps;

    public Wrestler() {

    }

    public Wrestler(String name, String brand, String img, int rank, int fight, int feet, int inches, double chest, double biceps, int weight) {
        this.name = name;
        this.brand = brand;
        this.img = img;
        this.rank = rank;
        this.fight = fight;
        this.weight = weight;
        this.chest = chest;
        this.biceps = biceps;
        this.feet = feet;
        this.inches = inches;
    }

    protected Wrestler(Parcel in) {
        name = in.readString();
        brand = in.readString();
        img = in.readString();
        rank = in.readInt();
        fight = in.readInt();
        feet = in.readInt();
        inches = in.readInt();
        weight = in.readInt();
        chest = in.readDouble();
        biceps = in.readDouble();
    }

    public static final Creator<Wrestler> CREATOR = new Creator<Wrestler>() {
        @Override
        public Wrestler createFromParcel(Parcel in) {
            return new Wrestler(in);
        }

        @Override
        public Wrestler[] newArray(int size) {
            return new Wrestler[size];
        }
    };

    public String getImg() {
        return img;
    }

    public void setImg(String img) {
        this.img = img;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public int getFight() {
        return fight;
    }

    public void setFight(int fight) {
        this.fight = fight;
    }

    public int getWeight() {
        return weight;
    }

    public String getWeightString() {
        return weight + "lbs";
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public double getChest() {
        return chest;
    }

    public int getFeet() {
        return feet;
    }

    public void setFeet(int feet) {
        this.feet = feet;
    }

    public int getInches() {
        return inches;
    }

    public void setInches(int inches) {
        this.inches = inches;
    }

    public void setChest(double chest) {
        this.chest = chest;
    }

    public double getBiceps() {
        return biceps;
    }

    public String getHeightString() {
        return feet + "\'" + inches + "\"";
    }

    public void setBiceps(double biceps) {
        this.biceps = biceps;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(brand);
        dest.writeString(img);
        dest.writeInt(rank);
        dest.writeInt(fight);
        dest.writeInt(feet);
        dest.writeInt(inches);
        dest.writeInt(weight);
        dest.writeDouble(chest);
        dest.writeDouble(biceps);
    }
}

