package com.lianyoudu.app;

import android.app.Dialog;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import android.view.animation.*;
import android.widget.*;

/** Shared geometry and optical material. Original AGSL, no third-party runtime. */
final class GlassEffects {
  static boolean shaderFallback;
  private static final android.util.LruCache<String, RenderEffect> effects =
      new android.util.LruCache<>(12);
  static final int CONTROL = HealthDesign.BUTTON,
      CARD = HealthDesign.CARD,
      SHEET = HealthDesign.SHEET;

  static void press(View view) {
    view.setOnTouchListener(
        (v, event) -> {
          if (!HealthDesign.motion(view.getContext())) return false;
          if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            v.animate().cancel();
            v.animate()
                .scaleX(.982f)
                .scaleY(.982f)
                .setDuration(100)
                .setInterpolator(new DecelerateInterpolator())
                .start();
          }
          if (event.getActionMasked() == MotionEvent.ACTION_UP
              || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            v.animate().cancel();
            v.animate()
                .scaleX(1)
                .scaleY(1)
                .setDuration(320)
                .setInterpolator(new OvershootInterpolator(.7f))
                .start();
          }
          return false;
        });
  }

  static void decorate(Dialog dialog, boolean dark, int accent) {
    Window w = dialog.getWindow();
    if (w == null) return;
    float den = dialog.getContext().getResources().getDisplayMetrics().density;
    boolean roundedWindow = dialog instanceof DesignDialog design && design.hasRoundedWindow();
    w.setBackgroundDrawable(
        roundedWindow
            ? AppViews.round(Color.TRANSPARENT, AppViews.dp(dialog.getContext(), SHEET))
            : new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    w.addFlags(
        WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
    WindowManager.LayoutParams optical = w.getAttributes();
    optical.setBlurBehindRadius((int) (20 * den));
    w.setAttributes(optical);
    w.setDimAmount(.18f);
    w.setBackgroundBlurRadius((int) (24 * den));
    View decor = w.getDecorView();
    if (roundedWindow) decor.setClipToOutline(true);
    decor.setElevation(8 * den);
    if (dialog instanceof android.app.AlertDialog && !(dialog instanceof DesignDialog)) {
      android.app.AlertDialog a = (android.app.AlertDialog) dialog;
      for (int which : new int[] {-1, -2, -3}) {
        Button b = a.getButton(which);
        if (b != null) {
          b.setTextColor(accent);
          if (b.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) b.getLayoutParams();
            lp.setMargins((int) (4 * den), (int) (4 * den), (int) (4 * den), (int) (4 * den));
            b.setLayoutParams(lp);
          }
          b.setBackground(AppViews.button(HealthDesign.well(dark), accent, CONTROL * den));
          press(b);
        }
      }
      ListView list = a.getListView();
      if (list != null) {
        list.setDividerHeight((int) (6 * den));
        list.setSelector(AppViews.button((accent & 0xffffff) | 0x18000000, accent, CONTROL * den));
      }
    }
    if (HealthDesign.motion(dialog.getContext())) {
      decor.setAlpha(0);
      boolean sheet = dialog instanceof DesignDialog && ((DesignDialog) dialog).sheet;
      decor.setTranslationY((sheet ? 32 : 8) * den);
      decor.setScaleX(sheet ? 1 : .98f);
      decor.setScaleY(sheet ? 1 : .98f);
      decor
          .animate()
          .alpha(1)
          .translationY(0)
          .scaleX(1)
          .scaleY(1)
          .setDuration(sheet ? 420 : 280)
          .setInterpolator(new DecelerateInterpolator())
          .start();
    }
  }

  static RenderEffect lens(
      int width, int height, float radius, boolean dark, float density, int inset) {
    RenderEffect fallback =
        RenderEffect.createBlurEffect(6 * density, 6 * density, Shader.TileMode.CLAMP);
    if (Build.VERSION.SDK_INT < 33) return fallback;
    try {
      String key = width + ":" + height + ":" + radius + ":" + dark + ":" + density + ":" + inset;
      RenderEffect result = effects.get(key);
      if (result != null) return result;
      result = modern(width, height, radius, dark, density, inset);
      effects.put(key, result);
      return result;
    } catch (IllegalArgumentException e) {
      shaderFallback = true;
      return fallback;
    }
  }

  static RenderEffect diffuseBlur(float density) {
    return RenderEffect.createBlurEffect(10 * density, 10 * density, Shader.TileMode.CLAMP);
  }

  static RenderEffect diffuseMask(
      int width,
      int height,
      float radius,
      boolean dark,
      float density,
      int inset,
      boolean selected) {
    if (Build.VERSION.SDK_INT < 33) return null;
    String key =
        "diffuse-mask:"
            + width
            + ":"
            + height
            + ":"
            + radius
            + ":"
            + dark
            + ":"
            + density
            + ":"
            + inset
            + ":"
            + selected;
    RenderEffect cached = effects.get(key);
    if (cached != null) return cached;
    try {
      RuntimeShader shader =
          new RuntimeShader(
              """
uniform shader scene;
uniform float2 size;
uniform float radius;
uniform float density;
uniform float inset;
uniform float dark;
uniform float edgeReveal;
float sdf(float2 p){float2 q=abs(p-size*.5)-(size*.5-radius);return length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;}
half4 main(float2 pixel){float2 p=pixel-float2(inset);float edge=1.0-smoothstep(0.0,11.0,max(0.0,-sdf(p))/density);half3 color=mix(scene.eval(pixel).rgb,half3(dark>.5?.09:.98),dark>.5?.65:.70);float horizontal=smoothstep(.55,.9,abs(p.y-size.y*.5)/(size.y*.5));float reveal=mix(edgeReveal,min(edgeReveal,.05),horizontal);float alpha=1.0-edge*reveal;return half4(color*alpha,alpha);}
""");
      shader.setFloatUniform("size", width, height);
      shader.setFloatUniform("radius", Math.min(radius, Math.min(width, height) / 2f));
      shader.setFloatUniform("density", density);
      shader.setFloatUniform("inset", inset);
      shader.setFloatUniform("dark", dark ? 1 : 0);
      shader.setFloatUniform("edgeReveal", selected ? .05f : .82f);
      RenderEffect result = RenderEffect.createRuntimeShaderEffect(shader, "scene");
      effects.put(key, result);
      return result;
    } catch (IllegalArgumentException e) {
      shaderFallback = true;
      return null;
    }
  }

  static RenderEffect controlLens(int width, int height, float radius, float density, int inset) {
    if (Build.VERSION.SDK_INT < 33) return null;
    String key = "control:" + width + ":" + height + ":" + radius + ":" + density + ":" + inset;
    RenderEffect cached = effects.get(key);
    if (cached != null) return cached;
    try {
      RuntimeShader shader =
          new RuntimeShader(
              """
uniform shader scene;
uniform float2 size;
uniform float radius;
uniform float density;
uniform float inset;
float sdf(float2 p){float2 q=abs(p-size*.5)-(size*.5-radius);return length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;}
half4 main(float2 pixel){float2 p=pixel-float2(inset);float edge=1.0-smoothstep(0.0,11.0,max(0.0,-sdf(p))/density);float2 normal=normalize(float2(sdf(p+float2(.5,0))-sdf(p-float2(.5,0)),sdf(p+float2(0,.5))-sdf(p-float2(0,.5)))+float2(.0001));return scene.eval(pixel-normal*sin(edge*1.570796)*1.5*density);}
""");
      shader.setFloatUniform("size", width, height);
      shader.setFloatUniform("radius", Math.min(radius, Math.min(width, height) / 2f));
      shader.setFloatUniform("density", density);
      shader.setFloatUniform("inset", inset);
      RenderEffect result = RenderEffect.createRuntimeShaderEffect(shader, "scene");
      effects.put(key, result);
      return result;
    } catch (IllegalArgumentException e) {
      shaderFallback = true;
      return null;
    }
  }

  @android.annotation.TargetApi(33)
  private static RenderEffect modern(
      int width, int height, float radius, boolean dark, float density, int inset) {
    RuntimeShader shader =
        new RuntimeShader(
            """
uniform shader scene;
uniform float2 size;
uniform float radius;
uniform float dark;
uniform float density;
uniform float inset;
float sdf(float2 p) {
  float2 q=abs(p-size*.5)-(size*.5-radius);
  return length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
}
half4 sampleScene(float2 p) {
  p=clamp(p,float2(1),size+inset*2.0-float2(1));
  float2 spread=float2(.65*density,0);
  return scene.eval(p)*.5+(scene.eval(p-spread)+scene.eval(p+spread))*.25;
}
half4 main(float2 pixel) {
  float2 p=pixel-float2(inset);
  float dist=sdf(p);
  float depth=max(0.0,-dist)/density;
  float edge=1.0-smoothstep(0.0,11.0,depth);
  float2 normal=normalize(float2(sdf(p+float2(.5,0))-sdf(p-float2(.5,0)),sdf(p+float2(0,.5))-sdf(p-float2(0,.5)))+float2(.0001));
  // Sample an overscanned, unblurred scene through a convex optical edge.
  // Central diffusion is separate, so blur cannot erase the rim displacement.
  // Text crossing the horizontal rims should remain legible and close to its source.
  // Keep the stronger lateral refraction at the ends of the lens.
  float rimStrength=mix(5.0,1.25,abs(normal.y));
  float bend=sin(edge*1.570796)*rimStrength*density;
  float2 uv=pixel-normal*bend;
  half4 sharp=sampleScene(uv);
  sharp.r=mix(sharp.r,scene.eval(uv-normal*edge*.15*density).r,.2);
  sharp.b=mix(sharp.b,scene.eval(uv+normal*edge*.15*density).b,.2);
  half3 color=mix(sharp.rgb,half3(dark>.5?.09:.98),.16);
  float rim=(1.0-smoothstep(.15,1.15,abs(depth-.7)))*(.12+.28*max(0.0,dot(normal,normalize(float2(-.6,-1.0)))));
  color+=half3(rim);
  return half4(clamp(color,0.0,1.0),1.0);
}
""");
    shader.setFloatUniform("size", width, height);
    shader.setFloatUniform("radius", Math.min(radius, Math.min(width, height) / 2f));
    shader.setFloatUniform("dark", dark ? 1 : 0);
    shader.setFloatUniform("density", density);
    shader.setFloatUniform("inset", inset);
    // Soften the optical sample in the same pass; avoid a second offscreen blur per lens.
    return RenderEffect.createRuntimeShaderEffect(shader, "scene");
  }
}
