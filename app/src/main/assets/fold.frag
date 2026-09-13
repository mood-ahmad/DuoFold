// Third-party notices: assets/THIRD_PARTY_NOTICES.txt
// Full license: THIRD_PARTY_NOTICES.txt. Screen UVs have a top-left origin.
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uScreen;
uniform mat4 uTextureMatrix;
uniform vec2 uResolution;
uniform vec2 uAngles;
uniform vec2 uSizePoints;
uniform float uIntensity;
uniform float uBlurScale;
uniform float uStretch;
varying vec2 vUv;
vec3 readScreen(vec2 uv) {
    if(any(lessThan(uv,vec2(0.0))) || any(greaterThan(uv,vec2(1.0))))return vec3(0.0);
    vec2 st=(uTextureMatrix*vec4(uv.x,1.0-uv.y,0.0,1.0)).xy;
    return texture2D(uScreen,st).rgb;
}
float hash21(vec2 p) {return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}
void main() {
    float tilt=length(uAngles);
    if(tilt<0.0001) {gl_FragColor=vec4(0.0);return;}
    vec2 axis=uAngles/tilt;
    vec2 across=vec2(-axis.y,axis.x);
    vec2 p=(vUv-vec2(0.5))*uSizePoints;
    float minimum=-0.5*dot(abs(across),uSizePoints);
    float d=dot(across,p)-minimum;
    float gap=d*sin(tilt);
    float depth=1920.0-gap*uStretch;
    if(depth<=0.00001) {gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}
    vec2 glass=p+across*d*(cos(tilt)-1.0);
    vec2 hit=vec2(0.5)+(glass*(1920.0/depth))/uSizePoints;
    float radiusPoints=0.12*gap*uIntensity*uBlurScale;
    vec2 radiusUV=vec2(radiusPoints)/uSizePoints;
    float attenuation=max(1.0-0.015*radiusPoints,0.0);
    if(any(lessThan(hit,-radiusUV)) || any(greaterThan(hit,vec2(1.0)+radiusUV))) {
        gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;
    }
    if(radiusPoints<0.5) {gl_FragColor=vec4(readScreen(hit)*attenuation,1.0);return;}
    float taps=clamp(floor(radiusPoints*2.0),6.0,32.0);
    float rotation=hash21(vUv*uSizePoints)*6.28318530718;
    vec3 sum=vec3(0.0);
    for(int i=0;i<32;i++) {
        if(float(i)>=taps)break;
        float r=sqrt((float(i)+0.5)/taps);
        float a=float(i)*2.39996322973+rotation;
        sum+=readScreen(hit+radiusUV*r*vec2(cos(a),sin(a)));
    }
    gl_FragColor=vec4(sum/taps*attenuation,1.0);
}
