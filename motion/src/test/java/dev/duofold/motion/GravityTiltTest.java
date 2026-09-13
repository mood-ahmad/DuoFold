package dev.duofold.motion;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class GravityTiltTest {
    private static final float EPS=0.0001f;

    @Test public void uprightPortraitIsNeutral() {
        float[] result=GravityTilt.angles(0,1,0,0);
        assertEquals(0,result[0],EPS);assertEquals(0,result[1],EPS);
    }

    @Test public void detectsForwardAndSideTilt() {
        float sin=(float)Math.sin(Math.toRadians(30));
        float cos=(float)Math.cos(Math.toRadians(30));
        assertEquals(Math.toRadians(30),GravityTilt.angles(sin,cos,0,0)[1],EPS);
        assertEquals(-Math.toRadians(30),GravityTilt.angles(0,cos,sin,0)[0],EPS);
    }

    @Test public void remapsLandscapeToScreenAxes() {
        float sin=(float)Math.sin(Math.toRadians(30));
        float cos=(float)Math.cos(Math.toRadians(30));
        float[] result=GravityTilt.angles(-cos,sin,0,1);
        assertEquals(Math.toRadians(30),result[1],EPS);
    }
}
