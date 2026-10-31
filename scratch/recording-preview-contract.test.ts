/**
 * Offline wiring contracts complement CameraXBindingPolicyTest's executable Kotlin selection tests.
 * Run from the repo root: bun test scratch/recording-preview-contract.test.ts
 * Requires Bun only. No phone, network, APK installation, generated output, or cleanup.
 * These source assertions catch accidental disconnects between policy and Android adapters;
 * they do not establish CameraX/HAL support or recording throughput on any device.
 */
import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";

const root = new URL("../app/src/main/", import.meta.url);
const capture = readFileSync(new URL("kotlin/com/github/digitallyrefined/androidipcamera/helpers/CameraXCapture.kt", root), "utf8");
const service = readFileSync(new URL("kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt", root), "utf8");
const bind = capture.slice(capture.indexOf("private fun rebind()"), capture.indexOf("private fun applyZoomWithRetry"));

test("each reduced session keeps analysis and uses selected optional surfaces", () => {
  expect(bind).toContain("CameraXBindingPolicy.bind(candidates)");
  expect(bind).toContain("mutableListOf<androidx.camera.core.UseCase>(analysis)");
  for (const [flag, useCase] of [["display", "previewUseCase"], ["encoder", "encoderUseCase"], ["still", "imageCapture"]]) {
    expect(bind).toContain(`if (candidate.${flag}) ${useCase}?.let { cases.add(it) }`);
  }
  expect(bind).toContain("encoderSurfaceBound = selected.encoder");
});

test("surface encoder is cleared only after a successful selection loses it", () => {
  expect(bind).toMatch(/if \(encoderUseCase != null && !selected.encoder\) \{\s*encoderUseCase = null\s*onEncoderSurfaceFallback\?\.invoke\(\)/);
  expect(bind.indexOf("camera = boundCamera")).toBeLessThan(bind.indexOf("onEncoderSurfaceFallback?.invoke()"));
  expect(bind.match(/onEncoderSurfaceFallback\?\.invoke\(\)/g)).toHaveLength(1);
  expect(service).toMatch(/onEncoderSurfaceFallback = \{[^}]*h264\?\.setEncoder\(null\)/);
});

test("dropping still capture uses cached analysis snapshots without a rebind", () => {
  expect(bind).toContain("if (!selected.still) imageCapture = null");
  const still = capture.slice(capture.indexOf("override fun captureStill"), capture.indexOf("private fun frameFallbackJpeg"));
  expect(still).toContain("val ic = imageCapture ?: return onJpeg(frameFallbackJpeg())");
  expect(still).not.toMatch(/bindToLifecycle|rebind\(\)/);
});

test("requested display loss is reported and the encoder provider remains eligible with display", () => {
  expect(bind).toMatch(/if \(previewUseCase != null && !selected.display\) \{\s*previewUseCase = null\s*onDisplayPreviewFallback\?\.invoke\(\)/);
  expect(service).toMatch(/if \(showScreenPreview\) currentSurfaceProvider else null,\s*h264SurfaceProvider,/);
  expect(service).toMatch(/onDisplayPreviewFallback = \{[^}]*Toast\.makeText\(this, R.string.camera_preview_unavailable/);
});
