package com.epicgames.ue4;

/**
 * Created by jverb on 5/27/2018.
 */

public class Vector3 {
    public static final Vector3 UNIT_X = new Vector3(1, 0, 0);
    public static final Vector3 UNIT_Y = new Vector3(0, 1, 0);
    public static final Vector3 UNIT_Z = new Vector3(0, 0, 1);
    public float x, y, z;

    public Vector3() {

    }

    public Vector3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3 perpendicular() {
        Vector3 perp = crossProduct(UNIT_X);
        if (perp.squaredLength() < 0.00001) {
            perp = crossProduct(UNIT_Y);
        }
        return perp;
    }

    public Vector3 crossProduct(Vector3 v) {
        return new Vector3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
    }

    public float squaredLength() {
        return x * x + y * y + z * z;
    }

    public Vector3 multiply(Vector3 v) {
        return new Vector3(x * v.x, y * v.y, z * v.z);
    }

    public Vector3 normalise() {
        float f = (float) Math.sqrt(squaredLength());
        if (f > 0.000001) {
            f = 1.0f / f;
            x *= f;
            y *= f;
            z *= f;
        }
        return this;
    }

    public Vector3 multiply(float f) {
        return new Vector3(x * f, y * f, z * f);
    }

    public Vector3 add(Vector3 v) {
        return new Vector3(x + v.x, y + v.y, z + v.z);
    }

    public float dotProduct(Vector3 v) {
        return x * v.x + y * v.y + z * v.z;
    }
}
