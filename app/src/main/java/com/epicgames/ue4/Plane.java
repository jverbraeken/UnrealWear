package com.epicgames.ue4;

public class Plane {
    private Vector3 normal;
    private float d;

    public Plane(Vector3 rkNormal, float fConstant) {
        normal = rkNormal;
        d = -fConstant;
    }

    public Vector3 projectVector(Vector3 p) {
        float[][] xform = {
                {1 - normal.x * normal.x, -normal.x * normal.y, -normal.x * normal.z},
                {-normal.y * normal.x, 1 - normal.y * normal.y, -normal.y * normal.z},
                {-normal.z * normal.x, -normal.z * normal.y, 1 - normal.z * normal.z}
        };
        Vector3 kProd = new Vector3();
        kProd.x = xform[0][0] * p.x +
                xform[0][1] * p.y +
                xform[0][2] * p.z;
        kProd.y = xform[1][0] * p.x +
                xform[1][1] * p.y +
                xform[1][2] * p.z;
        kProd.z = xform[2][0] * p.x +
                xform[2][1] * p.y +
                xform[2][2] * p.z;
        return kProd;
    }
}
