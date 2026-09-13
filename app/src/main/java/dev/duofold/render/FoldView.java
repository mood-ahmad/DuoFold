package dev.duofold.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import dev.duofold.motion.FoldModel;

public final class FoldView extends GLSurfaceView implements GLSurfaceView.Renderer, FoldSurface {
    private final Listener listener;
    private final FloatBuffer quad=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] matrix=new float[16];
    private final Object bufferLock=new Object();
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private volatile FoldModel.State state=FoldModel.atAxes(0,0,1,400,800);
    private volatile FoldModel.State drawnState=FoldModel.atAxes(0,0,1,400,800);
    private volatile float blurScale=1;
    private volatile float stretch=1;
    private SurfaceTexture texture;
    private Surface input;
    private HardwareBuffer pending;
    private int program,textureId,width,height;
    private volatile boolean newFrame,hasFrame;
    private boolean firstFrameReported;
    private volatile boolean stopped;
    private volatile boolean saveDebugFrame;
    public void saveDebugFrame() {if(dev.duofold.BuildConfig.DEBUG){saveDebugFrame=true;requestRender();}}
    public FoldView(Context context,FoldSurface.Listener listener) {
        super(context); this.listener=listener;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8,8,8,8,0,0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setPreserveEGLContextOnPause(true);
        quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        setRenderer(this); setRenderMode(RENDERMODE_WHEN_DIRTY);
    }
    public void setFold(float forwardRadians,float sideRadians,float intensity) {
        float density=getResources().getDisplayMetrics().density;
        float widthPoints=Math.max(1,getWidth())/density,heightPoints=Math.max(1,getHeight())/density;
        state=FoldModel.atAxes(forwardRadians,sideRadians,intensity,widthPoints,heightPoints);requestRender();
    }
    /** 0 = no crease blur; 1 = original strength; >1 = stronger. */
    public void setBlurScale(float scale) { blurScale=scale; requestRender(); }
    /** Perspective stretch of the fold: 0 = flat, 1 = original, >1 = more violent. */
    public void setStretch(float value) { stretch=value; requestRender(); }
    /** Receives a captured frame from the bridge process. The view takes ownership and closes the buffer. */
    public void queueBuffer(HardwareBuffer buffer) {
        if(buffer==null) return;
        HardwareBuffer drop;
        synchronized(bufferLock) { drop=pending; pending=buffer; }
        if(drop!=null) drop.close();
        requestRender();
    }
    private HardwareBuffer takeBuffer() {
        synchronized(bufferLock) { HardwareBuffer b=pending; pending=null; return b; }
    }
    public FoldModel.State inputState() { return drawnState; }
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config) {
        try {
            program=GLES20.glCreateProgram();
            GLES20.glAttachShader(program,compile(GLES20.GL_VERTEX_SHADER,asset("fold.vert")));
            GLES20.glAttachShader(program,compile(GLES20.GL_FRAGMENT_SHADER,asset("fold.frag")));
            GLES20.glLinkProgram(program);
            int[] status=new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0);
            if(status[0]==0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            int[] ids=new int[1]; GLES20.glGenTextures(1,ids,0); textureId=ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            texture=new SurfaceTexture(textureId);
            input=new Surface(texture);
            hasFrame=false;
        } catch(Exception e) { post(()->listener.onError("GPU: "+e.getMessage())); }
    }
    @Override public void onSurfaceChanged(GL10 gl,int w,int h) {
        GLES20.glViewport(0,0,w,h); width=w; height=h;
        if(texture==null || stopped) return;
        int captureWidth=Math.min(w,1080), captureHeight=Math.round(h*(captureWidth/(float)w));
        texture.setDefaultBufferSize(captureWidth,captureHeight);
        post(()-> { if(!stopped) listener.onReady(captureWidth,captureHeight); });
    }
    @Override public void onDrawFrame(GL10 gl) {
        if(texture==null || stopped || program==0) return;
        HardwareBuffer buffer=takeBuffer();
        if(buffer!=null) {
            try {
                Bitmap bmp=Bitmap.wrapHardwareBuffer(buffer,null);
                if(bmp!=null) {
                    // GPU-side blit (hardware canvas + hardware bitmap) into the SurfaceTexture,
                    // then sample it as an OES texture with the fold shader.
                    Canvas canvas=input.lockHardwareCanvas();
                    if(canvas!=null) {
                        try {
                            canvas.drawColor(0,PorterDuff.Mode.CLEAR);
                            // 1:1 blit: the capture buffer and the SurfaceTexture share the same
                            // size; drawing into a full-view rect would scale up and crop the image.
                            canvas.drawBitmap(bmp,0,0,paint);
                        } finally {input.unlockCanvasAndPost(canvas);}
                    }
                    bmp.recycle();
                    texture.updateTexImage(); texture.getTransformMatrix(matrix); hasFrame=true;
                }
            } catch(Exception e) { post(()->listener.onError("GPU frame: "+e.getMessage())); return; }
            finally { buffer.close(); }
        }
        if(!hasFrame) {   // never got a source frame yet: keep the overlay transparent
            GLES20.glClearColor(0,0,0,0); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        // Redraw the last texture even when no new frame arrived (e.g. tilt-only renders),
        // otherwise the overlay would flash transparent between captures.
        GLES20.glClearColor(0,0,0,0); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        try {
            FoldModel.State s=state;
            GLES20.glUseProgram(program);
            int pos=GLES20.glGetAttribLocation(program,"aPosition");
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,quad);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glUniform1i(location("uScreen"),0);
            GLES20.glUniformMatrix4fv(location("uTextureMatrix"),1,false,matrix,0);
            GLES20.glUniform2f(location("uResolution"),width,height);
            GLES20.glUniform2f(location("uAngles"),s.angleX,s.angleY);
            GLES20.glUniform2f(location("uSizePoints"),s.widthPoints,s.heightPoints);
            scalar("uIntensity",s.intensity);
            scalar("uBlurScale",blurScale);
            scalar("uStretch",stretch);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            GLES20.glDisableVertexAttribArray(pos);
            drawnState=s;
            if(!firstFrameReported) {
                firstFrameReported=true;
                post(listener::onFirstFrame);
            }
            if(saveDebugFrame && dev.duofold.BuildConfig.DEBUG) {
                saveDebugFrame=false;
                ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*4);
                GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
                int[] argb=new int[width*height];
                for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                    int offset=((height-1-y)*width+x)*4;
                    argb[y*width+x]=0xff000000|((pixels.get(offset)&255)<<16)|((pixels.get(offset+1)&255)<<8)|(pixels.get(offset+2)&255);
                }
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(argb,width,height,android.graphics.Bitmap.Config.ARGB_8888);
                new Thread(()-> {
                    try(java.io.FileOutputStream out=getContext().openFileOutput("debug-frame.png",0)) {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
                        android.util.Log.i("DuoFold","Debug frame saved");
                    } catch(java.io.IOException e) {android.util.Log.e("DuoFold","Debug export failed",e);}
                    finally {bitmap.recycle();}
                },"DuoFrameExport").start();
            }
        } catch(Exception e) { post(()->listener.onError("GPU frame: "+e.getMessage())); }
    }
    public void releaseResources() {
        stopped=true;
        queueEvent(()-> {
            if(input!=null) {input.release();input=null;}
            if(texture!=null) {texture.release();texture=null;}
            if(program!=0) GLES20.glDeleteProgram(program);
            if(textureId!=0) GLES20.glDeleteTextures(1,new int[]{textureId},0);
        });
        HardwareBuffer drop=takeBuffer();
        if(drop!=null) drop.close();
    }
    private int location(String name) { return GLES20.glGetUniformLocation(program,name); }
    private void scalar(String name,float value) { GLES20.glUniform1f(location(name),value); }
    private String asset(String name) throws Exception {
        try(InputStream in=getContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int read;
            while((read=in.read(buffer))!=-1)out.write(buffer,0,read);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private int compile(int type,String source) {
        int shader=GLES20.glCreateShader(type); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] status=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0);
        if(status[0]==0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader)); return shader;
    }
}
