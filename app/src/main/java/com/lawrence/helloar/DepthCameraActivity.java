/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lawrence.helloar;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Task;
import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManagerFactory;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.model.ReviewErrorCode;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.lawrence.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.lawrence.common.samplerender.Framebuffer;
import com.lawrence.common.samplerender.GLError;
import com.lawrence.common.samplerender.SampleRender;
import com.lawrence.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
public class DepthCameraActivity extends AppCompatActivity implements SampleRender.Renderer {

  private static final String TAG = DepthCameraActivity.class.getSimpleName();

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100f;

  private static final int CUBEMAP_RESOLUTION = 16;
  private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;
  private SampleRender render;

  private PlaneRenderer planeRenderer;
  private BackgroundRenderer backgroundRenderer;
  private Framebuffer virtualSceneFramebuffer;
  private boolean hasSetTextureNames = false;

  private final DepthSettings depthSettings = new DepthSettings();
  private boolean[] depthSettingsMenuDialogCheckboxes = new boolean[2];

  private final InstantPlacementSettings measurementUnitSettings = new InstantPlacementSettings();
  private int measurementUnitSelected = 0;

  // Environmental HDR
  private Texture dfgTexture;
  private SpecularCubemapFilter cubemapFilter;

  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];

  private TextView distanceView;

  private boolean completedReview = false;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up touch listener.
    tapHelper = new TapHelper(/*context=*/ this);
    surfaceView.setOnTouchListener(tapHelper);

    // Set up renderer.
    render = new SampleRender(surfaceView, this, getAssets());

    installRequested = false;

    depthSettings.onCreate(this);
    measurementUnitSettings.onCreate(this);
    ImageButton settingsButton = findViewById(R.id.settings_button);
    settingsButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            PopupMenu popup = new PopupMenu(DepthCameraActivity.this, v);
            popup.setOnMenuItemClickListener(DepthCameraActivity.this::settingsMenuClick);
            popup.inflate(R.menu.settings_menu);
            popup.show();
          }
        });

    distanceView = findViewById(R.id.distance);
  }

  /** Menu button to launch feature specific settings. */
  protected boolean settingsMenuClick(MenuItem item) {
    if (item.getItemId() == R.id.depth_settings) {
      launchDepthSettingsMenuDialog();
      return true;
    } else if (item.getItemId() == R.id.measurement_unit_settings) {
      launchMeasurementUnitSettingsMenuDialog();
      return true;
    }
    else if (item.getItemId() == R.id.about) {
      launchAboutMenuDialog();
      return true;
    }
    return false;
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (!(session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))) {
        message = "This device does not support depth. This app will not function properly";
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      configureSession();
      // To record a live camera session for later playback, call
      // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
      // session instead of using the live camera feed, call
      // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
      // learn more about recording and playback, see:
      // https://developers.google.com/ar/develop/java/recording-and-playback
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();


    SharedPreferences mPrefs = getSharedPreferences("pref", 0);
    SharedPreferences.Editor mEditor = mPrefs.edit();
    int numResumed = mPrefs.getInt("num_resumed", 1);
    mEditor.putInt("num_resumed", numResumed + 1).commit();

    boolean reviewed = mPrefs.getBoolean("reviewed", false);
    if (!reviewed && numResumed >= 3) {
      ReviewManager manager = ReviewManagerFactory.create(this);
      Task<ReviewInfo> request = manager.requestReviewFlow();
      request.addOnCompleteListener(task -> {
        if (task.isSuccessful()) {
          // We can get the ReviewInfo object
          ReviewInfo reviewInfo = task.getResult();
          Task<Void> flow = manager.launchReviewFlow(this, reviewInfo);
          flow.addOnCompleteListener(task2 -> {
            mEditor.putBoolean("reviewed", true).commit();
          });
        }
      });
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(SampleRender render) {
    // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
    // an IOException.
    try {
      planeRenderer = new PlaneRenderer(render);
      backgroundRenderer = new BackgroundRenderer(render);
      virtualSceneFramebuffer = new Framebuffer(render, /*width=*/ 1, /*height=*/ 1);

      cubemapFilter =
          new SpecularCubemapFilter(
              render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES);
      // Load DFG lookup table for environmental lighting
      dfgTexture =
          new Texture(
              render,
              Texture.Target.TEXTURE_2D,
              Texture.WrapMode.CLAMP_TO_EDGE,
              /*useMipmaps=*/ false);
      // The dfg.raw file is a raw half-float texture with two channels.
      final int dfgResolution = 64;
      final int dfgChannels = 2;
      final int halfFloatSize = 2;

      ByteBuffer buffer =
          ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize);
      try (InputStream is = getAssets().open("models/dfg.raw")) {
        is.read(buffer.array());
      }
      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.getTextureId());
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture");
      GLES30.glTexImage2D(
          GLES30.GL_TEXTURE_2D,
          /*level=*/ 0,
          GLES30.GL_RG16F,
          /*width=*/ dfgResolution,
          /*height=*/ dfgResolution,
          /*border=*/ 0,
          GLES30.GL_RG,
          GLES30.GL_HALF_FLOAT,
          buffer);
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D");

    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
    }
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    virtualSceneFramebuffer.resize(width, height);
  }

  @Override
  public void onDrawFrame(SampleRender render) {
    if (session == null) {
      return;
    }

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(
          new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      Log.e(TAG, "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      return;
    }
    Camera camera = frame.getCamera();

    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
          render, depthSettings.depthColorVisualizationEnabled());
      backgroundRenderer.setUseOcclusion(render, true);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
      return;
    }
    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame);

    if (camera.getTrackingState() == TrackingState.TRACKING) {
      try (Image depthImage = frame.acquireDepthImage()) {
        int depth = getMillimetersDepth(depthImage, depthImage.getWidth() / 2, depthImage.getHeight() / 2);
        double feet = depth * 0.00328084;
        int inches = (int) ((feet - Math.floor(feet)) * 12);
        int int_feet = (int) feet;
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if(measurementUnitSettings.isInstantPlacementEnabled()) {
              distanceView.setText(String.format(Locale.US, "%.2f m", (float) depth / 1000));
            }
            else {
              distanceView.setText(String.format(Locale.US, "%d'%d\"", int_feet, inches));
            }
          }
        });
        backgroundRenderer.updateCameraDepthTexture(depthImage);
      } catch (NotYetAvailableException e) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
      }
    }

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());


    // -- Draw background

    if (frame.getTimestamp() != 0) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render);
    }

    // If not tracking, don't draw 3D objects.
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      return;
    }

    // -- Draw non-occluded virtual objects (planes, point cloud)

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0);



    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
  }


  private void launchMeasurementUnitSettingsMenuDialog() {
    resetSettingsMenuDialogCheckboxes();
    Resources resources = getResources();
    new AlertDialog.Builder(this)
        .setTitle(R.string.options_title_instant_placement)
        .setSingleChoiceItems(
            resources.getStringArray(R.array.distance_units_options_array),
                measurementUnitSelected,
            (DialogInterface dialog, int which) ->
                measurementUnitSelected = which)
        .setPositiveButton(
            R.string.done,
            (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
        .setNegativeButton(
            android.R.string.cancel,
            (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
        .show();
  }

  /** Shows checkboxes to the user to facilitate toggling of depth-based effects. */
  private void launchDepthSettingsMenuDialog() {
    // Retrieves the current settings to show in the checkboxes.
    resetSettingsMenuDialogCheckboxes();

    // Shows the dialog to the user.
    Resources resources = getResources();
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      // With depth support, the user can select visualization options.
      new AlertDialog.Builder(this)
          .setMultiChoiceItems(
              resources.getStringArray(R.array.depth_options_array),
              depthSettingsMenuDialogCheckboxes,
              (DialogInterface dialog, int which, boolean isChecked) ->
                  depthSettingsMenuDialogCheckboxes[which] = isChecked)
          .setPositiveButton(
              R.string.done,
              (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
          .setNegativeButton(
              android.R.string.cancel,
              (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
          .show();
    }
  }

  private void launchAboutMenuDialog() {
    new AlertDialog.Builder(this)
            .setMessage(R.string.about_message)
            .setPositiveButton(
                    R.string.done,
                    null)
            .show();
  }

  private void applySettingsMenuDialogCheckboxes() {
    depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[0]);
    measurementUnitSettings.setInstantPlacementEnabled(
            measurementUnitSelected == 1);
    configureSession();
  }

  private void resetSettingsMenuDialogCheckboxes() {
    depthSettingsMenuDialogCheckboxes[0] = depthSettings.depthColorVisualizationEnabled();
    measurementUnitSelected = measurementUnitSettings.isInstantPlacementEnabled() ? 1 : 0;
  }

  /** Configures the session with feature settings. */
  private void configureSession() {
    Config config = session.getConfig();
    config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
    } else {
      config.setDepthMode(Config.DepthMode.DISABLED);
    }
    session.configure(config);
  }

  /** Obtain the depth in millimeters for depthImage at coordinates (x, y). */
  public int getMillimetersDepth(Image depthImage, int x, int y) {
    // The depth image has a single plane, which stores depth for each
    // pixel as 16-bit unsigned integers.
    Image.Plane plane = depthImage.getPlanes()[0];
    int byteIndex = x * plane.getPixelStride() + y * plane.getRowStride();
    ByteBuffer buffer = plane.getBuffer().order(ByteOrder.nativeOrder());
    return buffer.getShort(byteIndex);
  }
}


