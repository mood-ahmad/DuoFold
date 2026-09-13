package dev.duofold.motion;

/** Time-based low-pass; no autonomous animation and no angle integration drift. */
public final class TiltFilter {
    private float baseline, filtered, progress=1;
    private long last;
    private boolean calibrated;
    public void calibrate(float degrees) {
        if (!Float.isFinite(degrees)) return;
        baseline=degrees; filtered=degrees; progress=1; last=0; calibrated=true;
    }
    public float update(float degrees,long timestampNanos,float rangeDegrees) {
        if (!Float.isFinite(degrees)) return progress;
        if (!calibrated) calibrate(degrees);
        float dt=last==0 ? .016f : Math.max(0,Math.min(.1f,(timestampNanos-last)/1e9f));
        last=timestampNanos;
        float difference=degrees-filtered;
        // Stronger smoothing at rest; quick intentional movements retain responsiveness.
        float tau=Math.abs(difference)>4 ? .035f : .075f;
        filtered+=(1-(float)Math.exp(-dt/tau))*difference;
        float travel=Math.max(0,Math.abs(filtered-baseline)-.8f);
        float range=Float.isFinite(rangeDegrees) ? Math.max(15,rangeDegrees) : 45;
        progress=1-FoldModel.clamp(travel/(range-.8f));
        if(progress>.998f) progress=1;
        if(progress<.002f) progress=0;
        return progress;
    }
    public float progress() { return progress; }
}
