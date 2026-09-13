package dev.duofold.motion;
import org.junit.Test;
import static org.junit.Assert.*;
public class FoldModelTest {
    @Test public void zeroTiltIsIdentity() {
        assertArrayEquals(new float[]{.23f,.78f},FoldModel.sourceAt(.23f,.78f,FoldModel.atTilt(0,1,400)),.00001f);
    }
    @Test public void farEdgeIsAnchoredForBothDirections() {
        for(float angle:new float[]{-.7f,.7f}) {
            float x=angle>0?1:0;
            assertArrayEquals(new float[]{x,.25f},FoldModel.sourceAt(x,.25f,FoldModel.atTilt(angle,1,400)),.00001f);
        }
    }
    @Test public void positiveThirtyDegreesMatchesReferenceRay() {
        float[] uv=FoldModel.sourceAt(0,.25f,FoldModel.atTilt((float)Math.PI/6,1,400));
        assertArrayEquals(new float[]{.0914135f,.2209302f},uv,.00001f);
    }
    @Test public void signedTiltMirrors() {
        for(int n=0;n<=80;n++) {
            float a=(float)Math.toRadians(n);
            float[] l=FoldModel.sourceAt(.2f,.7f,FoldModel.atTilt(a,1,400));
            float[] r=FoldModel.sourceAt(.8f,.7f,FoldModel.atTilt(-a,1,400));
            assertEquals(1-l[0],r[0],.00001f);assertEquals(l[1],r[1],.00001f);
        }
    }
    @Test public void raysOutsideTheInterfaceAreNotClampedToEdgePixels() {
        assertTrue(FoldModel.sourceAt(0,0,FoldModel.atTilt(.7f,1,400))[1]<0);
    }
    @Test public void invalidAngleFailsOpenAndHighAnglesStayFinite() {
        assertEquals(0,FoldModel.atTilt(Float.NaN,1,400).angle,0);
        for(int a=-100;a<=100;a++) for(int x=0;x<=10;x++) {
            float[] uv=FoldModel.sourceAt(x/10f,0,FoldModel.atTilt(a,1,400));
            assertTrue(Float.isFinite(uv[0])&&Float.isFinite(uv[1]));
        }
    }

    @Test public void forwardTiltAnchorsTheCorrespondingHorizontalEdge() {
        FoldModel.State down=FoldModel.atAxes(.5f,0,1,400,800);
        assertArrayEquals(new float[]{.3f,0},FoldModel.sourceAt(.3f,0,down),.00001f);
        FoldModel.State up=FoldModel.atAxes(-.5f,0,1,400,800);
        assertArrayEquals(new float[]{.3f,1},FoldModel.sourceAt(.3f,1,up),.00001f);
    }
}
