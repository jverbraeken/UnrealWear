package com.epicgames.ue4.runnables;

import android.util.Log;

import com.epicgames.ue4.BuildConfig;
import com.epicgames.ue4.MainActivity;
import com.epicgames.ue4.Touch;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static com.epicgames.ue4.MainActivity.TAG;

public final class SendSensorDataRunnable implements Runnable {
    private static final float[] X_VECTOR = {1, 0, 0};
    private static final float[] Y_VECTOR = {0, 1, 0};
    private static final float[] Z_VECTOR = {0, 0, 1};

    private static void sendDataOverChannel(final DataOutputStream outputStream, final float[] rotation, final float[] acceleration, final Touch touch) {
        final float[] newRotation = getSendData(rotation);
        try {
            outputStream.writeByte(MainActivity.COMTP_SENSOR_DATA);
            Log.d("Foo", String.format("Send data: %.2f %.2f %.2f %.2f || %.2f, %.2f, %.2f", newRotation[0], newRotation[1], newRotation[2], newRotation[3], acceleration[0], acceleration[1], acceleration[2]));
            outputStream.writeFloat(newRotation[0]);
            outputStream.writeFloat(newRotation[1]);
            outputStream.writeFloat(newRotation[2]);
            outputStream.writeFloat(newRotation[3]);
            outputStream.writeFloat(acceleration[0]);
            outputStream.writeFloat(acceleration[1]);
            outputStream.writeFloat(acceleration[2]);
            if (touch == null) {
                outputStream.writeFloat(-1);
                outputStream.writeFloat(-1);
                outputStream.writeByte(2);
            } else {
                outputStream.writeFloat(touch.x);
                outputStream.writeFloat(touch.y);
                outputStream.writeByte(touch.state.getCode());
            }
            outputStream.flush();
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private static float[] multiply(final float[][] a, final float[] x) {
        final int m = a.length;
        final int n = a[0].length;
        if (x.length != n) {
            throw new RuntimeException("Illegal matrix dimensions.");
        }
        final float[] y = new float[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                y[i] += a[i][j] * x[j];
            }
        }
        return y;
    }

    private static float[] getSendData(float[] rotMat) {
        final float[][] rotMat2 = new float[3][3];
        rotMat2[0][0] = rotMat[0];
        rotMat2[0][1] = rotMat[1];
        rotMat2[0][2] = rotMat[2];
        rotMat2[1][0] = rotMat[3];
        rotMat2[1][1] = rotMat[4];
        rotMat2[1][2] = rotMat[5];
        rotMat2[2][0] = rotMat[6];
        rotMat2[2][1] = rotMat[7];
        rotMat2[2][2] = rotMat[8];




        /*


        Get X-coordinate of X-vector
        If Z-coordinate of Z-vector is negative: Y-coordinate of X-vector *= -1
        Angle = atan2(x, y)


         */






        Log.d(TAG, String.format("rotMat2: %.2f, %.2f, %.2f",
                rotMat[0],
                rotMat[3],
                rotMat[6])
        );

        // 1

        final float[] transformedXVector = multiply(rotMat2, X_VECTOR);
        Log.d(TAG, String.format("transformedXVector: %.2f, %.2f, %.2f",
                transformedXVector[0],
                transformedXVector[1],
                transformedXVector[2])
        );
        final float[] transformedYVector = multiply(rotMat2, Y_VECTOR);
        Log.d(TAG, String.format("transformedYVector: %.2f, %.2f, %.2f",
                transformedYVector[0],
                transformedYVector[1],
                transformedYVector[2])
        );
        final float[] transformedZVector = multiply(rotMat2, Z_VECTOR);
        Log.d(TAG, String.format("transformedZVector: %.2f, %.2f, %.2f",
                transformedZVector[0],
                transformedZVector[1],
                transformedZVector[2])
        );

        //

        float transformedUpVectorXY_Angle = (float) Math.atan2(transformedZVector[0], transformedZVector[1]);
        Log.d(TAG, String.format("transformedUpVectorXY_Angle: %f", transformedUpVectorXY_Angle));
        final float angleCos = (float) Math.cos(transformedUpVectorXY_Angle);
        final float angleSin = (float) Math.sin(transformedUpVectorXY_Angle);
        final double[] compensatedTransformedUpVector = {
                transformedZVector[0] * angleCos - transformedZVector[1] * angleSin,
                transformedZVector[0] * angleSin + transformedZVector[1] * angleCos,
                transformedZVector[2]
        };
        // compensatedVector[0] should always be 0 !!!
        final double compensatedTransformedUpVectorLength = Math.sqrt(
                compensatedTransformedUpVector[1] * compensatedTransformedUpVector[1]
                        + compensatedTransformedUpVector[2] * compensatedTransformedUpVector[2]
        );
        final double[] normalizedCompensatedTransformedUpVector = {
                0,
                compensatedTransformedUpVector[1] / compensatedTransformedUpVectorLength,
                compensatedTransformedUpVector[2] / compensatedTransformedUpVectorLength
        };

        // 2

        Log.d(TAG, String.format("normalizedCompensatedTransformedUpVector: %.2f, %.2f, %.2f",
                normalizedCompensatedTransformedUpVector[0],
                normalizedCompensatedTransformedUpVector[1],
                normalizedCompensatedTransformedUpVector[2])
        );

        //

        final double transformedUpVectorZZ_Angle = Math.acos(transformedZVector[2] / Math.sqrt(transformedZVector[0] * transformedZVector[0] + transformedZVector[1] * transformedZVector[1] + transformedZVector[2] * transformedZVector[2]));
        Log.d(TAG, String.format("angle: %f", transformedUpVectorZZ_Angle));
        final float[] perpendicularTransformedUpVector = {
                -transformedZVector[1],
                transformedZVector[0],
                0
        };
        final double perpendicularTransformedUpVector_Length = Math.sqrt(perpendicularTransformedUpVector[0] * perpendicularTransformedUpVector[0] + perpendicularTransformedUpVector[1] * perpendicularTransformedUpVector[1]);
        perpendicularTransformedUpVector[0] /= perpendicularTransformedUpVector_Length;
        perpendicularTransformedUpVector[1] /= perpendicularTransformedUpVector_Length;
        Log.d(TAG, String.format("perpendicular: %.2f, %.2f, %.2f", perpendicularTransformedUpVector[0], perpendicularTransformedUpVector[1], perpendicularTransformedUpVector[2]));

        final float cosTheta = (float) Math.cos(-transformedUpVectorZZ_Angle);
        final float cosThetaInv = 1 - cosTheta;
        final float sinTheta = (float) Math.sin(-transformedUpVectorZZ_Angle);

        final float[] u = perpendicularTransformedUpVector;
        final float[] u2 = {u[0] * u[0], u[1] * u[1], u[2] * u[2]};

        final float[][] rotMat3 = {
                {cosTheta + u2[0] * cosThetaInv, u[0] * u[1] * cosThetaInv - u[2] * sinTheta, u[0] * u[2] * cosThetaInv + u[1] * sinTheta},
                {u[1] * u[0] * cosThetaInv + u[2] * sinTheta, cosTheta + u2[1] * cosThetaInv, u[1] * u[2] * cosThetaInv - u[0] * sinTheta},
                {u[2] * u[0] * cosThetaInv - u[1] * sinTheta, u[2] * u[1] * cosThetaInv + u[0] * sinTheta, cosTheta + u2[2] * cosThetaInv}
        };











        final float transformedSideVectorXY_Angle = (float) Math.atan2(transformedXVector[0], transformedXVector[1]);
        Log.d(TAG, String.format("transformedSideVectorXY_Angle: %f", transformedSideVectorXY_Angle));
        final float angleCos2 = (float) Math.cos(transformedSideVectorXY_Angle);
        final float angleSin2 = (float) Math.sin(transformedSideVectorXY_Angle);
        final double[] compensatedTransformedSideVector = {
                transformedXVector[0] * angleCos2 - transformedXVector[1] * angleSin2,
                transformedXVector[0] * angleSin2 + transformedXVector[1] * angleCos2,
                transformedXVector[2]
        };
        // compensatedVector[0] should always be 0 !!!
        final double compensatedTransformedSideVectorLength = Math.sqrt(
                compensatedTransformedSideVector[0] * compensatedTransformedSideVector[0] +
                        compensatedTransformedSideVector[1] * compensatedTransformedSideVector[1] +
                        compensatedTransformedSideVector[2] * compensatedTransformedSideVector[2]
        );
        final double[] normalizedCompensatedTransformedSideVector = {
                compensatedTransformedSideVector[0] / compensatedTransformedSideVectorLength,
                compensatedTransformedSideVector[1] / compensatedTransformedSideVectorLength,
                compensatedTransformedSideVector[2] / compensatedTransformedSideVectorLength
        };
        Log.d(TAG, String.format("normalizedCompensatedTransformedSideVector: %.2f, %.2f, %.2f",
                normalizedCompensatedTransformedSideVector[0],
                normalizedCompensatedTransformedSideVector[1],
                normalizedCompensatedTransformedSideVector[2])
        );
        /*final double[] compensatedTransformedSideVector = {
                transformedXVector[0] * angleCos2 - transformedXVector[1] * angleSin2,
                transformedXVector[0] * angleSin2 + transformedXVector[1] * angleCos2,
                transformedXVector[2]
        };
        Log.d(TAG, String.format("compensatedTransformedSideVector: %.2f, %.2f, %.2f",
                compensatedTransformedSideVector[0],
                compensatedTransformedSideVector[1],
                compensatedTransformedSideVector[2])
        );
        // compensatedVector[0] should always be 0 !!!
        final double compensatedTransformedSideVectorLength = Math.sqrt(
                compensatedTransformedSideVector[0] * compensatedTransformedSideVector[0]
                        + compensatedTransformedSideVector[2] * compensatedTransformedSideVector[2]
        );
        final double[] normalizedCompensatedTransformedSideVector = {
                0,
                compensatedTransformedSideVector[1] / compensatedTransformedSideVectorLength,
                compensatedTransformedSideVector[2] / compensatedTransformedSideVectorLength
        };
        Log.d(TAG, String.format("normalizedCompensatedTransformedSideVector: %.2f, %.2f, %.2f",
                normalizedCompensatedTransformedSideVector[0],
                normalizedCompensatedTransformedSideVector[1],
                normalizedCompensatedTransformedSideVector[2])
        );
        final float tmp = (float) Math.atan2(compensatedTransformedSideVector[0], compensatedTransformedSideVector[2]);
        Log.d(TAG, String.format("tmp: %.2f", tmp));
        final float[] flatTransformedSideVector = multiply(rotMat3, transformedXVector);
        final float finalRotation = (float) Math.atan2(normalizedCompensatedTransformedSideVector[1], normalizedCompensatedTransformedSideVector[2]);*/

        //float finalRotation = (float) Math.atan2(normalizedCompensatedTransformedSideVector[1], normalizedCompensatedTransformedSideVector[2]);

        float finalRotation;
        //if (transformedZVector[2] >= 0) {
        finalRotation = (float) Math.asin(transformedYVector[2]);
        // finalRotation = (float) Math.atan2(transformedYVector[0], transformedYVector[1]);
        //} else {
        //    finalRotation = (float) Math.atan2(transformedYVector[0], -transformedYVector[1]);
        //}

        float finalRotation2;
        if (transformedZVector[0] >= 0) {
            finalRotation2 = (float) Math.atan2(transformedYVector[1], transformedYVector[2]);
        } else {
            finalRotation2 = (float) Math.atan2(transformedYVector[1], -transformedYVector[2]);
        }

        float[] flatZVector = {transformedZVector[0], transformedZVector[1], 0};
        float flatZVectorLength = (float) Math.sqrt(flatZVector[0] * flatZVector[0] + flatZVector[1] * flatZVector[1]);
        flatZVector[0] /= flatZVectorLength;
        flatZVector[1] /= flatZVectorLength;
        float[] crossproduct = {-flatZVector[1], flatZVector[0], 0};
        Log.d(TAG, String.format("crossproduct: %.2f, %.2f, %.2f",
                crossproduct[0],
                crossproduct[1],
                crossproduct[2])
        );



        float dotProduct = transformedYVector[0] * crossproduct[0] + transformedYVector[1] * crossproduct[1] + transformedYVector[2] * crossproduct[2];
        Log.d(TAG, String.format("dotproduct: %.2f, %.2f, %.2f, %.2f",
                transformedYVector[0] * crossproduct[0],
                transformedYVector[1] * crossproduct[1],
                transformedYVector[2] * crossproduct[2],
                dotProduct)
        );

        Log.d(TAG, String.format("finalRotation: %.2f", ((float) Math.acos(dotProduct)) * Math.signum(finalRotation)));
        Log.d(TAG, String.format("finalRotation2: %.2f", finalRotation2));

        Log.d(TAG, String.format("finalVector: %.2f, %.2f, %.2f, %.2f",
                finalRotation,
                normalizedCompensatedTransformedUpVector[0],
                normalizedCompensatedTransformedUpVector[1],
                normalizedCompensatedTransformedUpVector[2])
        );

        return new float[]{finalRotation, transformedZVector[0], transformedZVector[1], transformedZVector[2]};
    }

    @Override
    public void run() {
        final DataOutputStream dataOutputStream = MainActivity.getChannelOutputStream();
        if (dataOutputStream != null) {
            final List<float[]> rotations = MainActivity.getRotations();
            final float[] acceleration = MainActivity.getAcceleration();
            final Touch touch = MainActivity.getTouch();

            if (!rotations.isEmpty()) {
                final float[] avgRotation = avgAndResetRotations();

                if (BuildConfig.DEBUG) {
                    //Log.d(TAG, String.format("Rotation: %.2f, %.2f, %.2f - Acceleration: %.2f, %.2f, %.2f - Timestamp: %d", avgRotation.x, avgRotation.y, avgRotation.z, acceleration.x, acceleration.y, acceleration.z, avgRotation.timestamp));
                }

                MainActivity.sendChannelLock.lock();
                try {
                    sendDataOverChannel(dataOutputStream, avgRotation, acceleration, touch);
                    MainActivity.nextTouch();
                } finally {
                    MainActivity.sendChannelLock.unlock();
                }
            }
        }
    }

    private float[] avgAndResetRotations() {
        MainActivity.rotationsLock.lock();
        final List<float[]> rotations = MainActivity.getRotations();
        final float[] rotation = new float[9];
        for (final float[] tmp : rotations) {
            for (int i = 0; i < 9; i++) {
                rotation[i] += tmp[i];
            }
        }
        for (int i = 0; i < 9; i++) {
            rotation[i] /= rotations.size();
        }
        MainActivity.resetRotations();
        MainActivity.rotationsLock.unlock();
        return rotation;
    }
}