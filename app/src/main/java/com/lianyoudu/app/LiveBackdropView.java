package com.lianyoudu.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Hardware display-list backdrop, updated on every source frame; no bitmap or polling timer. */
final class LiveBackdropView extends View implements ViewTreeObserver.OnPreDrawListener {
  private static final float DIFFUSE_SCALE = 0.5f;
  final RenderNode contentNode = new RenderNode("glass-source");
  final RenderNode diffuseNode = new RenderNode("glass-diffuse");
  private final RenderNode diffuseInputNode = new RenderNode("glass-diffuse-half");
  private RenderNode albumDiffuseTexture;
  private RenderNode recordedDiffuseSource;
  private int recordedDiffuseX, recordedDiffuseY;
  private boolean useHalfDiffuse;
  final RenderNode node = new RenderNode("live-glass-backdrop");
  final View source;
  LiveBackdropView sharedSource;
  View overlay;
  final RenderNode overlayNode = new RenderNode("live-glass-control-lens");
  final boolean dark;
  boolean solid;
  final boolean selected;
  final float radius;
  int width, height, inset;
  long frames;
  boolean sourceChanged = true;
  private int lastScrollX, lastScrollY, lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
  final int[] here = new int[2], there = new int[2];

  LiveBackdropView(Context context, View source, boolean dark, boolean solid, float radius) {
    this(context, source, dark, solid, radius, false);
  }

  LiveBackdropView(
      Context context, View source, boolean dark, boolean solid, float radius, boolean selected) {
    super(context);
    this.source = source;
    this.dark = dark;
    this.solid = solid;
    this.selected = selected;
    this.radius = radius;
    setBackground(AppViews.round(HealthDesign.card(dark), radius));
    setClipToOutline(true);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    setWillNotDraw(false);
  }

  void setSolid(boolean solid) {
    if (this.solid == solid) return;
    this.solid = solid;
    if (solid) clearAlbumDiffuseTexture();
    sourceChanged = true;
    invalidate();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    getViewTreeObserver().addOnPreDrawListener(this);
  }

  @Override
  protected void onDetachedFromWindow() {
    if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(this);
    node.discardDisplayList();
    contentNode.discardDisplayList();
    diffuseNode.discardDisplayList();
    diffuseInputNode.discardDisplayList();
    clearAlbumDiffuseTexture();
    overlayNode.discardDisplayList();
    super.onDetachedFromWindow();
  }

  @Override
  public boolean onPreDraw() {
    if ((solid && overlay == null) || getWidth() == 0 || getHeight() == 0 || source.getWidth() == 0)
      return true;
    boolean sizeChanged = width != getWidth() || height != getHeight();
    if (sizeChanged) {
      width = getWidth();
      height = getHeight();
      inset = AppViews.dp(getContext(), 24);
      int workWidth = width + inset * 2, workHeight = height + inset * 2;
      float density = getResources().getDisplayMetrics().density;
      node.setPosition(0, 0, workWidth, workHeight);
      contentNode.setPosition(0, 0, workWidth, workHeight);
      diffuseNode.setPosition(0, 0, workWidth, workHeight);
      RenderEffect diffuseMask = GlassEffects.diffuseMask(width, height, radius, dark, density, inset, selected);
      useHalfDiffuse = diffuseMask != null;
      diffuseNode.setRenderEffect(useHalfDiffuse ? diffuseMask : GlassEffects.diffuseBlur(density));
      if (useHalfDiffuse) {
        // Round up so both layers share the same origin and the half-size input covers odd extents.
        diffuseInputNode.setPosition(0, 0, (int) Math.ceil(workWidth * DIFFUSE_SCALE),
            (int) Math.ceil(workHeight * DIFFUSE_SCALE));
        diffuseInputNode.setRenderEffect(GlassEffects.diffuseBlur(density * DIFFUSE_SCALE));
      } else diffuseInputNode.discardDisplayList();
      overlayNode.setPosition(0, 0, workWidth, workHeight);
      overlayNode.setRenderEffect(GlassEffects.controlLens(width, height, radius, density, inset));
      node.setRenderEffect(GlassEffects.lens(width, height, radius, dark, density, inset));
    }
    boolean recordLayers = sizeChanged || !node.hasDisplayList() || !diffuseNode.hasDisplayList();
    getLocationInWindow(here);
    // Keep sampling anchored before this lens's own scale; the canvas magnifies around its pivot.
    here[0] += Math.round(getPivotX() * (getScaleX() - 1));
    here[1] += Math.round(getPivotY() * (getScaleY() - 1));
    source.getLocationInWindow(there);
    RenderNode albumTexture = albumDiffuseTexture();
    boolean sampleSharedTexture = albumTexture != null && sharedSource != null;
    RenderNode diffuseSource = albumTexture != null ? albumTexture
        : useHalfDiffuse ? diffuseInputNode : contentNode;
    int diffuseX = sampleSharedTexture ? sharedSource.here[0] - here[0] : 0;
    int diffuseY = sampleSharedTexture ? sharedSource.here[1] - here[1] : 0;
    boolean recordDiffuseInput = useHalfDiffuse
        && (sizeChanged || (!sampleSharedTexture && !diffuseInputNode.hasDisplayList()));
    boolean recordAlbumTexture = albumTexture != null && !sampleSharedTexture
        && (sizeChanged || !albumTexture.hasDisplayList());
    boolean recordDiffuse = recordLayers || diffuseSource != recordedDiffuseSource
        || diffuseX != recordedDiffuseX || diffuseY != recordedDiffuseY;
    if (!sourceChanged
        && !recordDiffuseInput
        && !recordLayers
        && !recordAlbumTexture
        && !recordDiffuse
        && contentNode.hasDisplayList()
        && !source.isDirty()
        && (overlay == null || !overlay.isDirty())
        && lastScrollX == source.getScrollX()
        && lastScrollY == source.getScrollY()
        && lastX == here[0]
        && lastY == here[1]) return true;
    sourceChanged = false;
    lastScrollX = source.getScrollX();
    lastScrollY = source.getScrollY();
    lastX = here[0];
    lastY = here[1];
    RecordingCanvas canvas = contentNode.beginRecording(width + inset * 2, height + inset * 2);
    try {
      canvas.clipRect(0, 0, width + inset * 2, height + inset * 2);
      canvas.drawColor(HealthDesign.background(dark));
      if (sharedSource != null && sharedSource.contentNode.hasDisplayList()) {
        // Both surfaces sample the same scroll frame. Reuse the full-width base recording while
        // translating it to this lens instead of drawing the entire page again on every drag.
        canvas.translate(sharedSource.here[0] - here[0], sharedSource.here[1] - here[1]);
        canvas.drawRenderNode(sharedSource.contentNode);
      } else {
        canvas.clipRect(
            inset + there[0] - here[0],
            inset + there[1] - here[1],
            inset + there[0] - here[0] + source.getWidth(),
            inset + there[1] - here[1] + source.getHeight());
        // View.draw(Canvas) is called without the parent draw step that applies own scroll.
        canvas.translate(
            inset + there[0] - here[0] - source.getScrollX(),
            inset + there[1] - here[1] - source.getScrollY());
        if (source instanceof AppViews.PageScrollView pageScroll) pageScroll.drawBackdrop(canvas);
        else source.draw(canvas);
      }
    } finally {
      contentNode.endRecording();
    }
    if (recordDiffuseInput) {
      // The reduced layer still references the current full-resolution source display list.
      RecordingCanvas input = diffuseInputNode.beginRecording();
      input.scale(DIFFUSE_SCALE, DIFFUSE_SCALE);
      input.drawRenderNode(contentNode);
      diffuseInputNode.endRecording();
    }
    if (recordAlbumTexture) {
      // One unclipped blurred output serves both lens regions. A direct replay of the filter
      // under two different clips can invalidate the platform's filtered snapshot.
      albumTexture.setPosition(0, 0, diffuseInputNode.getWidth(), diffuseInputNode.getHeight());
      RecordingCanvas texture = albumTexture.beginRecording();
      texture.drawRenderNode(diffuseInputNode);
      albumTexture.endRecording();
    }
    if (recordLayers) {
      RecordingCanvas sharp = node.beginRecording();
      sharp.drawRenderNode(contentNode);
      node.endRecording();
    }
    if (recordDiffuse) {
      RecordingCanvas frosted = diffuseNode.beginRecording();
      if (sampleSharedTexture) {
        frosted.drawColor(HealthDesign.background(dark));
        frosted.translate(diffuseX, diffuseY);
      }
      if (useHalfDiffuse) {
        // Apply tint and edge coverage at output resolution after scaling up the blurred layer.
        frosted.scale(1 / DIFFUSE_SCALE, 1 / DIFFUSE_SCALE);
      }
      frosted.drawRenderNode(diffuseSource);
      diffuseNode.endRecording();
      recordedDiffuseSource = diffuseSource;
      recordedDiffuseX = diffuseX;
      recordedDiffuseY = diffuseY;
    }
    if (overlay != null) {
      overlay.getLocationInWindow(there);
      RecordingCanvas controls = overlayNode.beginRecording(width + inset * 2, height + inset * 2);
      try {
        controls.translate(inset + there[0] - here[0], inset + there[1] - here[1]);
        overlay.draw(controls);
      } finally {
        overlayNode.endRecording();
      }
    }
    frames++;
    invalidate();
    return true;
  }

  private RenderNode albumDiffuseTexture() {
    if (!useHalfDiffuse || solid || overlay != null
        || !(source instanceof AppViews.PageScrollView page) || !page.sampleContentOnly) {
      clearAlbumDiffuseTexture();
      return null;
    }
    if (sharedSource != null) {
      RenderNode texture = sharedSource.albumDiffuseTexture;
      return texture != null && texture.hasDisplayList() ? texture : null;
    }
    if (albumDiffuseTexture == null) {
      albumDiffuseTexture = new RenderNode("gallery-diffuse-shared-output");
      albumDiffuseTexture.setUseCompositingLayer(true, null);
    }
    return albumDiffuseTexture;
  }

  private void clearAlbumDiffuseTexture() {
    if (albumDiffuseTexture == null) return;
    albumDiffuseTexture.discardDisplayList();
    albumDiffuseTexture = null;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (!solid && canvas.isHardwareAccelerated() && node.hasDisplayList()) {
      canvas.save();
      canvas.translate(-inset, -inset);
      canvas.drawRenderNode(node);
      canvas.drawRenderNode(diffuseNode);
      canvas.restore();
    }
    if (overlay != null && canvas.isHardwareAccelerated() && overlayNode.hasDisplayList()) {
      canvas.save();
      canvas.translate(-inset, -inset);
      canvas.drawRenderNode(overlayNode);
      canvas.restore();
    }
  }
}
