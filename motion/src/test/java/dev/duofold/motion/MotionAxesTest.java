package dev.duofold.motion;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionAxesTest {
    @Test public void forwardTiltIsIgnoredWhenDisabled() {
        assertArrayEquals(new float[]{0,.25f}, MotionAxes.resolve(.25f, .70f, false), .00001f);
    }

    @Test public void forwardAndSideTiltRemainIndependentWhenEnabled() {
        assertArrayEquals(new float[]{.15f,.25f}, MotionAxes.resolve(.25f, .15f, true), .00001f);
        assertArrayEquals(new float[]{-.15f,.25f}, MotionAxes.resolve(.25f, -.15f, true), .00001f);
    }

    @Test public void pureForwardTiltCanDriveTopOrBottomEdge() {
        assertArrayEquals(new float[]{.30f,0}, MotionAxes.resolve(0,.30f,true), .00001f);
        assertArrayEquals(new float[]{-.30f,0}, MotionAxes.resolve(0,-.30f,true), .00001f);
    }

    @Test public void invalidSensorValuesFailOpen() {
        assertArrayEquals(new float[]{0,0},MotionAxes.resolve(Float.NaN,.3f,true),0);
        assertArrayEquals(new float[]{0,0},MotionAxes.resolve(.3f,Float.POSITIVE_INFINITY,true),0);
    }
}
