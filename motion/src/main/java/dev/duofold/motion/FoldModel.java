package dev.duofold.motion;

/** Ray projection model. See the packaged third-party notices. */
public final class FoldModel {
    private FoldModel() {}
    public static final class State {
        public final float angle, angleX, angleY, intensity, widthPoints, heightPoints, eyeDistance;
        private State(float ax,float ay,float i,float w,float h) {
            angle=ay;angleX=ax;angleY=ay;intensity=i;widthPoints=w;heightPoints=h;eyeDistance=1920f;
        }
    }
    public static State atTilt(float angle,float intensity,float widthPoints) {
        return atAxes(0,angle,intensity,widthPoints,widthPoints);
    }
    public static State atAxes(float angleX,float angleY,float intensity,float widthPoints,float heightPoints) {
        float ax=Float.isFinite(angleX)?angleX:0,ay=Float.isFinite(angleY)?angleY:0;
        float magnitude=(float)Math.hypot(ax,ay);
        if(magnitude>1.3962634f) {float scale=1.3962634f/magnitude;ax*=scale;ay*=scale;}
        float strength=Float.isFinite(intensity)?clamp(intensity):0;
        float width=Float.isFinite(widthPoints)?Math.max(200,Math.min(1000,widthPoints)):400;
        float height=Float.isFinite(heightPoints)?Math.max(200,Math.min(2000,heightPoints)):width;
        return new State(ax,ay,strength,width,height);
    }
    /** Inverse projection in normalized UI coordinates; out-of-bounds rays must remain out of bounds. */
    public static float[] sourceAt(float x,float y,State s) {
        float tilt=(float)Math.hypot(s.angleX,s.angleY);
        if(tilt<.00001f)return new float[]{x,y};
        float axisX=s.angleX/tilt,axisY=s.angleY/tilt;
        float qx=-axisY,qy=axisX;
        float px=(x-.5f)*s.widthPoints,py=(y-.5f)*s.heightPoints;
        float min=-.5f*(Math.abs(qx)*s.widthPoints+Math.abs(qy)*s.heightPoints);
        float distance=qx*px+qy*py-min;
        float bend=(float)Math.cos(tilt)-1;
        float gx=px+qx*distance*bend,gy=py+qy*distance*bend;
        float gap=distance*(float)Math.sin(tilt);
        float t=s.eyeDistance/(s.eyeDistance-gap);
        return new float[]{.5f+gx*t/s.widthPoints,.5f+gy*t/s.heightPoints};
    }
    public static float clamp(float x) {return Math.max(0,Math.min(1,x));}
}
