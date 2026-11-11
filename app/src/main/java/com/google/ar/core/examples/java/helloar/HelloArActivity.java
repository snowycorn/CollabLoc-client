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

package com.google.ar.core.examples.java.helloar;

import static java.lang.Thread.sleep;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.res.Resources;
//import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.InstantPlacementMode;
import com.google.ar.core.Frame;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.LightEstimate;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.Framebuffer;
import com.google.ar.core.examples.java.common.samplerender.GLError;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.DeadlineExceededException;
import com.google.ar.core.exceptions.FatalException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

////
// import android.support.v7.app.AppCompatActivity;
// import android.os.Bundle;

// import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Map;

////

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

  private static final String TAG = HelloArActivity.class.getSimpleName();

  private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";
  private static final String WAITING_FOR_TAP_MESSAGE = "Tap on a surface to place an object.";

  // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
  // constants.
  private static final float[] sphericalHarmonicFactors = {
          0.282095f,
          -0.325735f,
          0.325735f,
          -0.325735f,
          0.273137f,
          -0.273137f,
          0.078848f,
          -0.273137f,
          0.136569f,
  };

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100f;

  private static final int CUBEMAP_RESOLUTION = 16;
  private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;

  ////
  private volatile Frame frame;
  private volatile Camera camera;
  ////

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

  private final InstantPlacementSettings instantPlacementSettings = new InstantPlacementSettings();
  private boolean[] instantPlacementSettingsMenuDialogCheckboxes = new boolean[1];
  // Assumed distance from the device camera to the surface on which user will try to place objects.
  // This value affects the apparent scale of objects while the tracking method of the
  // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
  // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
  // values for AR experiences where users are expected to place objects on surfaces close to the
  // camera. Use larger values for experiences where the user will likely be standing and trying to
  // place an object on the ground or floor in front of them.
  private static final float APPROXIMATE_DISTANCE_METERS = 2.0f;

  // Point Cloud
  private VertexBuffer pointCloudVertexBuffer;
  private Mesh pointCloudMesh;
  private Shader pointCloudShader;
  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  private long lastPointCloudTimestamp = 0;

  // Virtual object (ARCore pawn)
  private Mesh virtualObjectMesh;
  private Shader virtualObjectShader;
  private Texture virtualObjectAlbedoTexture;
  private Texture virtualObjectAlbedoInstantPlacementTexture;

  private final List<WrappedAnchor> wrappedAnchors = new ArrayList<>();

  // Environmental HDR
  private Texture dfgTexture;
  private SpecularCubemapFilter cubemapFilter;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] modelMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];
  private final float[] modelViewMatrix = new float[16]; // view x model
  private final float[] modelViewProjectionMatrix = new float[16]; // projection x view x model
  private final float[] sphericalHarmonicsCoefficients = new float[9 * 3];
  private final float[] viewInverseMatrix = new float[16];
  private final float[] worldLightDirection = {0.0f, 0.0f, 0.0f, 0.0f};
  private final float[] viewLightDirection = new float[4]; // view x world light direction

  // System vars
  // DO NOT MODIFY
  private Socket clientSocket;
  private int imageIdx = 1;
  private int imageSubIdx = 0;
  private boolean isFusedPoseReady = false;
  private float curTrackConf = 0.0f;
  private Map<Integer, CheckPoint> checkPointsMap = new HashMap<>();
  int latestServerResultIdx = -1;
  int latestAvailServerResultIdx = -1;
  int availServerResultCnt = 0;
  private final Object syncSendQueryImageLockObject = new Object();
  private volatile boolean sendingQueryImage = false;
  private final Object syncAcquireImageLockObject = new Object();
  private volatile boolean acquireImageLock = false;
  String startTimeStr = getStartFormattedTimeString(LocalDateTime.now());
  private boolean recordLog = false;
  private boolean successconnect = false;

  private boolean failconnect = false;
  private int failtimes = 0;
  private final List<Anchor> poseAnchors = new ArrayList<>();
  float curScale = 1f;
  // DO NOT MODIFY ABOVE HERE

  //// Only modify below here
  // Settings
  private final String SERVER_ADDRESS = "140.113.195.250"; // amazon
//  private final String SERVER_ADDRESS = "140.113.127.26";
  private final int SERVER_PORT = 9999;
//  private Pose observationPose = new Pose(
//          new float[]{-3.11849f, -1.09604f, 1.4994f},
//          new float[]{-0.00149035f, 0.966581f, 0.190108f, -0.171983f}
//  ); // library2_colmap
  private Pose observationPose = new Pose(
          new float[]{1.9806462973019563f, 0.5887246734440216f, -1.2239148330493757f},
          new float[]{0.06267051364021912f, -0.7815291700995973f, -0.05715481243972835f, 0.6180761345240914f}
  ); // library3_colmap
  //// Only modify above here

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
    instantPlacementSettings.onCreate(this);
    ImageButton settingsButton = findViewById(R.id.settings_button);
    settingsButton.setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View v) {
                PopupMenu popup = new PopupMenu(HelloArActivity.this, v);
                popup.setOnMenuItemClickListener(HelloArActivity.this::settingsMenuClick);
                popup.inflate(R.menu.settings_menu);
                popup.show();
              }
            });
    ////
    // Create directory to save log.txt
    // The directory can be found in /data/data/com.google.ar.core.examples.java.helloar/files/
    // and is named by formatted starting time
    new File(getApplicationContext().getFilesDir(), startTimeStr).mkdir();
    new File(new File(getApplicationContext().getFilesDir(), startTimeStr), "sampled_imgs").mkdir();

    // Start the socketThread to connect to server
    new Thread(sendCurTrackConfThread).start();
    ////
  }

  /** Menu button to launch feature specific settings. */
  protected boolean settingsMenuClick(MenuItem item) {
    if (item.getItemId() == R.id.depth_settings) {
      launchDepthSettingsMenuDialog();
      return true;
    } else if (item.getItemId() == R.id.instant_placement_settings) {
      launchInstantPlacementSettingsMenuDialog();
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

      // Point cloud
      pointCloudShader =
              Shader.createFromAssets(
                              render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", /*defines=*/ null)
                      .setVec4(
                              "u_Color", new float[] {31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f})
                      .setFloat("u_PointSize", 5.0f);
      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
              new VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null);
      final VertexBuffer[] pointCloudVertexBuffers = {pointCloudVertexBuffer};
      pointCloudMesh =
              new Mesh(
                      render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers);

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
              Texture.createFromAsset(
                      render,
                      "models/pawn_albedo.png",
                      Texture.WrapMode.CLAMP_TO_EDGE,
                      Texture.ColorFormat.SRGB);
      virtualObjectAlbedoInstantPlacementTexture =
              Texture.createFromAsset(
                      render,
                      "models/pawn_albedo_instant_placement.png",
                      Texture.WrapMode.CLAMP_TO_EDGE,
                      Texture.ColorFormat.SRGB);
      Texture virtualObjectPbrTexture =
              Texture.createFromAsset(
                      render,
                      "models/pawn_roughness_metallic_ao.png",
                      Texture.WrapMode.CLAMP_TO_EDGE,
                      Texture.ColorFormat.LINEAR);

      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj");
      virtualObjectShader =
              Shader.createFromAssets(
                              render,
                              "shaders/environmental_hdr.vert",
                              "shaders/environmental_hdr.frag",
                              /*defines=*/ new HashMap<String, String>() {
                                {
                                  put(
                                          "NUMBER_OF_MIPMAP_LEVELS",
                                          Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));
                                }
                              })
                      .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                      .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
                      .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
                      .setTexture("u_DfgTexture", dfgTexture);
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

    // Obtain the current frame from the AR Session. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    synchronized(syncAcquireImageLockObject) {
      try {
        ////
        if (acquireImageLock == true) {
          // Wait for the image to be read in socketThread
          syncAcquireImageLockObject.wait();
        }
        imageSubIdx++;
        ////
        frame = session.update();
      } catch (CameraNotAvailableException e) {
        Log.e(TAG, "Camera not available during onDrawFrame", e);
        messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
        return;
      } catch (InterruptedException e) {
        // Happens if someone interrupts your thread.
        System.out.println("InterruptedException");
      }
    }

    ////
    camera = frame.getCamera();
    Pose curClientPose = camera.getPose();
    ////

    ////
    // Record text log and image log
    if (recordLog) {
      clientARPoseLog(curClientPose);

      try {
        if (imageSubIdx % 6 == 0) {
          if (frame != null) {
            String imageIdxStamp = imageIdx + "-" + imageSubIdx;
            Image sampledImage = frame.acquireCameraImage();
            File dir = new File(getApplicationContext().getFilesDir(), startTimeStr);
            File sampledImgsDir = new File(dir, "sampled_imgs");
            try {
              File imageFile = new File(sampledImgsDir, "client-" + imageIdxStamp + ".jpg");
              OutputStream stream = new FileOutputStream(imageFile);

              YuvImage yuvImg = toYuvImage(sampledImage);
              yuvImg.compressToJpeg(
                      new Rect(0, 0, sampledImage.getWidth(), sampledImage.getHeight()),
                      100, stream);

              stream.flush();
              stream.close();

              if (availServerResultCnt > 0) {
                Pose absClientPose = toAbsolutePose(curClientPose, "arcore");
                String logContent = "SampledPose|" +
                        imageIdx + "|" +
                        imageIdxStamp + "|" +
                        absClientPose + "|" +
                        absClientPoseToWorldPose(absClientPose) + "|" +
                        latestAvailServerResultIdx;
                try {
                  File logFile = new File(dir, "sampled_imgs_log.txt");
                  FileOutputStream writer = new FileOutputStream(logFile, true);
                  writer.write(logContent.getBytes());
                  writer.write(10);
                } catch (Exception e) {
                  e.printStackTrace();
                }
              }

            } catch (Exception e) {
              e.printStackTrace();
            }
            sampledImage.close();
          }
        } else {
          System.out.println("frame is null");
        }
      } catch (NotYetAvailableException e) {
        System.out.println("NotYetAvailableException");
      } catch (DeadlineExceededException e) {
        System.out.println("DeadlineExceededException");
      }
    }
    ////

    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
              render, depthSettings.depthColorVisualizationEnabled());
      backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
      return;
    }
    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame);

    if (camera.getTrackingState() == TrackingState.TRACKING
            && (depthSettings.useDepthForOcclusion()
            || depthSettings.depthColorVisualizationEnabled())) {
      try (Image depthImage = frame.acquireDepthImage16Bits()) {
        backgroundRenderer.updateCameraDepthTexture(depthImage);
      } catch (NotYetAvailableException e) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
      }
    }

    // Handle one tap per frame.
    handleTap(frame, camera);

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    // Show a message
    String message = null;
    if (recordLog && successconnect) {
      message = "Recording logs";
    }
    else if (failconnect) {
      message = "Connect Failed ! Retrying...";
      // message = message + Integer.toString(failtimes);
    }
    else if (successconnect) {
      message = "Connect Success !";
    }

    if (message == null) {
      messageSnackbarHelper.hide(this);
    } else {
      messageSnackbarHelper.showMessage(this, message);
    }

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

    // Visualize tracked points.
    // Use try-with-resources to automatically release the point cloud.
    try (PointCloud pointCloud = frame.acquirePointCloud()) {
      if (pointCloud.getTimestamp() > lastPointCloudTimestamp) {
        pointCloudVertexBuffer.set(pointCloud.getPoints());
        lastPointCloudTimestamp = pointCloud.getTimestamp();
      }
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
      pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
      render.draw(pointCloudMesh, pointCloudShader);
    }

    /*
    // Visualize planes.
    planeRenderer.drawPlanes(
            render,
            session.getAllTrackables(Plane.class),
            camera.getDisplayOrientedPose(),
            projectionMatrix);
     */

    // -- Draw occluded virtual objects

    // Update lighting parameters in the shader
    updateLightEstimation(frame.getLightEstimate(), viewMatrix);

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);
    /*
    // Visualize anchors created by touch.
    for (WrappedAnchor wrappedAnchor : wrappedAnchors) {
      Anchor anchor = wrappedAnchor.getAnchor();
      Trackable trackable = wrappedAnchor.getTrackable();
      if (anchor.getTrackingState() != TrackingState.TRACKING) {
        continue;
      }

      // Get the current pose of an Anchor in world space. The Anchor pose is updated
      // during calls to session.update() as ARCore refines its estimate of the world.
      anchor.getPose().toMatrix(modelMatrix, 0);

      // Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

      // Update shader properties and draw
      virtualObjectShader.setMat4("u_ModelView", modelViewMatrix);
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);

      if (trackable instanceof InstantPlacementPoint
              && ((InstantPlacementPoint) trackable).getTrackingMethod()
              == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE) {
        virtualObjectShader.setTexture(
                "u_AlbedoTexture", virtualObjectAlbedoInstantPlacementTexture);
      } else {
        virtualObjectShader.setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture);
      }

      render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer);
    }
     */

    ////
    // Draw object on observation pose
//    if (isFusedPoseReady) {
//      try {
//        Pose obsAbsClientPose = worldPoseToAbsClientPose(toAbsolutePose(observationPose, "colmap"), curClientPose);
//        Anchor obsAnchor = session.createAnchor(obsAbsClientPose);
//        // Get the current pose of an Anchor in world space. The Anchor pose is updated
//        // during calls to session.update() as ARCore refines its estimate of the world.
//        obsAnchor.getPose().toMatrix(modelMatrix, 0);
//
//        // Calculate model/view/projection matrices
//        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
//        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);
//
//        // Update shader properties and draw
//        virtualObjectShader.setMat4("u_ModelView", modelViewMatrix);
//        virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
//
//        virtualObjectShader.setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture);
//
//        render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer);
//      } catch (FatalException e) {
//        e.printStackTrace();
//      }
//    }
    ////

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private void handleTap(Frame frame, Camera camera) {
    MotionEvent tap = tapHelper.poll();
    if (tap != null) {
      recordLog = !recordLog;
    }
    /*
    MotionEvent tap = tapHelper.poll();
    if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
      List<HitResult> hitResultList;
      if (instantPlacementSettings.isInstantPlacementEnabled()) {
        hitResultList =
                frame.hitTestInstantPlacement(tap.getX(), tap.getY(), APPROXIMATE_DISTANCE_METERS);
      } else {
        hitResultList = frame.hitTest(tap);
      }
      for (HitResult hit : hitResultList) {
        // If any plane, Oriented Point, or Instant Placement Point was hit, create an anchor.
        Trackable trackable = hit.getTrackable();
        // If a plane was hit, check that it was hit inside the plane polygon.
        // DepthPoints are only returned if Config.DepthMode is set to AUTOMATIC.
        if ((trackable instanceof Plane
                && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
                || (trackable instanceof Point
                && ((Point) trackable).getOrientationMode()
                == OrientationMode.ESTIMATED_SURFACE_NORMAL)
                || (trackable instanceof InstantPlacementPoint)
                || (trackable instanceof DepthPoint)) {
          // Cap the number of objects created. This avoids overloading both the
          // rendering system and ARCore.
          if (wrappedAnchors.size() >= 10) {
            wrappedAnchors.get(0).getAnchor().detach();
            wrappedAnchors.remove(0);
          }

          // Adding an Anchor tells ARCore that it should track this position in
          // space. This anchor is created on the Plane to place the 3D model
          // in the correct position relative both to the world and to the plane.
          wrappedAnchors.add(new WrappedAnchor(hit.createAnchor(), trackable));
          // For devices that support the Depth API, shows a dialog to suggest enabling
          // depth-based occlusion. This dialog needs to be spawned on the UI thread.
          this.runOnUiThread(this::showOcclusionDialogIfNeeded);

          // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, or
          // Instant Placement Point.
          break;
        }
      }
    }
     */
  }

  ////
//  private void addToPoseAnchor(Frame frame, Camera camera) {
//    if (camera != null) {
//      if (poseAnchors.size() >= 10) {
//        poseAnchors.get(0).detach();
//        poseAnchors.remove(0);
//      }
//      poseAnchors.add(session.createAnchor(camera.getPose()));
//    }
//  }
  ////

  /**
   * Shows a pop-up dialog on the first call, determining whether the user wants to enable
   * depth-based occlusion. The result of this dialog can be retrieved with useDepthForOcclusion().
   */
  private void showOcclusionDialogIfNeeded() {
    boolean isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
    if (!depthSettings.shouldShowDepthEnableDialog() || !isDepthSupported) {
      return; // Don't need to show dialog.
    }

    // Asks the user whether they want to use depth-based occlusion.
    new AlertDialog.Builder(this)
            .setTitle(R.string.options_title_with_depth)
            .setMessage(R.string.depth_use_explanation)
            .setPositiveButton(
                    R.string.button_text_enable_depth,
                    (DialogInterface dialog, int which) -> {
                      depthSettings.setUseDepthForOcclusion(true);
                    })
            .setNegativeButton(
                    R.string.button_text_disable_depth,
                    (DialogInterface dialog, int which) -> {
                      depthSettings.setUseDepthForOcclusion(false);
                    })
            .show();
  }

  private void launchInstantPlacementSettingsMenuDialog() {
    resetSettingsMenuDialogCheckboxes();
    Resources resources = getResources();
    new AlertDialog.Builder(this)
            .setTitle(R.string.options_title_instant_placement)
            .setMultiChoiceItems(
                    resources.getStringArray(R.array.instant_placement_options_array),
                    instantPlacementSettingsMenuDialogCheckboxes,
                    (DialogInterface dialog, int which, boolean isChecked) ->
                            instantPlacementSettingsMenuDialogCheckboxes[which] = isChecked)
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
              .setTitle(R.string.options_title_with_depth)
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
    } else {
      // Without depth support, no settings are available.
      new AlertDialog.Builder(this)
              .setTitle(R.string.options_title_without_depth)
              .setPositiveButton(
                      R.string.done,
                      (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
              .show();
    }
  }

  private void applySettingsMenuDialogCheckboxes() {
    depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0]);
    depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1]);
    instantPlacementSettings.setInstantPlacementEnabled(
            instantPlacementSettingsMenuDialogCheckboxes[0]);
    configureSession();
  }

  private void resetSettingsMenuDialogCheckboxes() {
    depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion();
    depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled();
    instantPlacementSettingsMenuDialogCheckboxes[0] =
            instantPlacementSettings.isInstantPlacementEnabled();
  }

  /** Checks if we detected at least one plane. */
  private boolean hasTrackingPlane() {
    for (Plane plane : session.getAllTrackables(Plane.class)) {
      if (plane.getTrackingState() == TrackingState.TRACKING) {
        return true;
      }
    }
    return false;
  }

  /** Update state based on the current frame's light estimation. */
  private void updateLightEstimation(LightEstimate lightEstimate, float[] viewMatrix) {
    if (lightEstimate.getState() != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false);
      return;
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true);

    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0);
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix);

    updateMainLight(
            lightEstimate.getEnvironmentalHdrMainLightDirection(),
            lightEstimate.getEnvironmentalHdrMainLightIntensity(),
            viewMatrix);
    updateSphericalHarmonicsCoefficients(
            lightEstimate.getEnvironmentalHdrAmbientSphericalHarmonics());
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap());
  }

  private void updateMainLight(float[] direction, float[] intensity, float[] viewMatrix) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0];
    worldLightDirection[1] = direction[1];
    worldLightDirection[2] = direction[2];
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0);
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection);
    virtualObjectShader.setVec3("u_LightIntensity", intensity);
  }

  private void updateSphericalHarmonicsCoefficients(float[] coefficients) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics

    if (coefficients.length != 9 * 3) {
      throw new IllegalArgumentException(
              "The given coefficients array must be of length 27 (3 components per 9 coefficients");
    }

    // Apply each factor to every component of each coefficient
    for (int i = 0; i < 9 * 3; ++i) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3];
    }
    virtualObjectShader.setVec3Array(
            "u_SphericalHarmonicsCoefficients", sphericalHarmonicsCoefficients);
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
    if (instantPlacementSettings.isInstantPlacementEnabled()) {
      config.setInstantPlacementMode(InstantPlacementMode.LOCAL_Y_UP);
    } else {
      config.setInstantPlacementMode(InstantPlacementMode.DISABLED);
    }
    session.configure(config);
  }

  ////
  private void clientARPoseLog(Pose clientPose) {
    if (availServerResultCnt > 0) {
      CheckPoint latestAvailCheckPoint = checkPointsMap.get(latestAvailServerResultIdx);
      Pose absClientPose = toAbsolutePose(clientPose, "arcore");
      String logContent = "ARPose|" +
              latestAvailCheckPoint.getImageIdx() + "|" +
              latestAvailCheckPoint.getClientPose() + "|" +
              latestAvailCheckPoint.getAbsClientPose() + "|" +
              latestAvailCheckPoint.getServerPose() + "|" +
              latestAvailCheckPoint.getAbsServerPose() + "|" +
              clientPose + "|" +
              absClientPose + "|" +
              absClientPoseToWorldPose(absClientPose) + "|" +
              imageIdx;
      writeToLogFile(logContent);
    }
  }

  private void clientReceivedLog() {
    if (checkPointsMap.size() > 0) {
      CheckPoint latestCheckPoint = checkPointsMap.get(latestServerResultIdx);
      String logContent = "Received|" +
              latestCheckPoint.getImageIdx() + "|" +
              latestCheckPoint.getClientPose() + "|" +
              latestCheckPoint.getAbsClientPose() + "|" +
              latestCheckPoint.getServerPose() + "|" +
              latestCheckPoint.getAbsServerPose() + "|" +
              curScale + "|" +
              absClientPoseToWorldPose(latestCheckPoint.getAbsClientPose()) + "|" +
              latestCheckPoint.getOldWorldPose() + "|" +
              latestCheckPoint.getIsAvailable() + "|" +
              curTrackConf + "|" +
              latestCheckPoint.getFocalLength();
      writeToLogFile(logContent);
    }
  }

  private String getStartFormattedTimeString(LocalDateTime time) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    }
    return time.toString();
  }

  private void writeToLogFile(String content){
    File dir = new File(getApplicationContext().getFilesDir(), startTimeStr);
    try {
      File logFile = new File(dir, "log.txt");
      FileOutputStream writer = new FileOutputStream(logFile, true);
      writer.write(content.getBytes());
      writer.write(10);
    } catch (Exception e){
      e.printStackTrace();
    }
  }

  private Pose toAbsolutePose(Pose originPose, String originPoseType) {
    if (originPoseType == "colmap") {
      // Convert colmap pose to absolute pose by the following concept
      // rotation = R^t
      // translation = -R^t * T
      // Refer to: https://colmap.github.io/format.html#images-txt
      Pose inverseRotation = originPose.extractRotation().inverse();
      float[] rotation = inverseRotation.getRotationQuaternion();
      float[] translation = inverseRotation.compose(
              originPose.extractTranslation()).getTranslation();
      for (int i=0; i<translation.length; i++) { translation[i] = -translation[i]; }
      return new Pose(translation, rotation);
    } else if (originPoseType == "arcore") {
      // Convert arcore pose to absolute pose by reversing pose direction
      // Refer to: https://learnopengl.com/Getting-started/Camera
      float[] rotation = originPose.extractRotation().compose(
              Pose.makeRotation(-1, 0, 0, 0)).getRotationQuaternion();
      return new Pose(originPose.getTranslation(), rotation);
    }
    return originPose;
  }

  private float calcPosesDist(Pose lhs, Pose rhs) {
    // Calculate distance between two poses
    float ret = 0;
    Pose relPose = lhs.extractTranslation().compose(rhs.extractTranslation().inverse());
    float[] relTrans = relPose.getTranslation();
    for (int i = 0; i < relTrans.length; i++) { ret += relTrans[i] * relTrans[i]; }
    return (float) Math.sqrt(ret);
  }

  private float calcPosesAngle(Pose lhs, Pose rhs) {
    // Calculate angle between two poses
    // Refer to function `rotation_error` in TrianFlow:
    // https://github.com/B1ueber2y/TrianFlow/blob/7234224ed73ac724a2cce9da41f712f5446c5ea6/core/evaluation/eval_odom.py#L123
    Pose relPose = lhs.extractRotation().inverse().compose(rhs.extractRotation());
    float[] relPoseMtx = new float[16];
    relPose.toMatrix(relPoseMtx, 0);
    float a = relPoseMtx[0];
    float b = relPoseMtx[5];
    float c = relPoseMtx[10];
    double d = 0.5 * (a + b + c - 1.0);
    double rot_error = Math.acos(Math.max(Math.min(d, 1.0), -1.0));
    double rot_error_deg = Math.toDegrees(rot_error);
    return (float) rot_error_deg;
  }

  private Pose absClientPoseToWorldPose(Pose absClientPose) {
    CheckPoint latestAvailCheckPoint = checkPointsMap.get(latestAvailServerResultIdx);
    Pose relRot = latestAvailCheckPoint.getRelativeRot();
    Pose relTrans = latestAvailCheckPoint.getRelativeTrans();

    if (relRot != null && relTrans != null) {
      // Calculate fused pose rotation
      Pose fusedRot = relRot.compose(absClientPose.extractRotation());

      // Calculate fused pose translation
      float[] clientTrans = absClientPose.getTranslation();
      Pose ckptClientPose = latestAvailCheckPoint.getAbsClientPose();
      float[] ckptClientTrans = ckptClientPose.getTranslation();
      float[] tmpTrans = new float[3];
      // ckptClientTrans as origin
      for (int i = 0; i < 3; i++) { tmpTrans[i] = clientTrans[i] - ckptClientTrans[i]; }
      Pose fusedPose = new Pose(tmpTrans, new float[]{0, 0, 0, 1});
      // Rotate according to origin
      fusedPose = relRot.compose(fusedPose);
      float[] fusedPoseTrans = fusedPose.getTranslation();
      // Scale and move back
      for (int i = 0; i < 3; i++) { fusedPoseTrans[i] = fusedPoseTrans[i] * curScale + relTrans.getTranslation()[i]; }

      return new Pose(fusedPoseTrans, fusedRot.getRotationQuaternion());
    }
    return null;
  }

  private Pose worldPoseToAbsClientPose(Pose worldPose, Pose curClientPose) {
    if (worldPose != null && curClientPose != null) {
      Pose observationAbsPose = worldPose;
      Pose curClientAbsPose = toAbsolutePose(curClientPose, "arcore");
      Pose curClientWorldPose = absClientPoseToWorldPose(curClientAbsPose);

      CheckPoint latestAvailCheckPoint = checkPointsMap.get(latestAvailServerResultIdx);
      Pose relRot = latestAvailCheckPoint.getRelativeRot();
      Pose relTrans = curClientAbsPose;

      if (relRot != null) {
        relRot = relRot.inverse();
        Pose recoveredRot = relRot.compose(observationAbsPose.extractRotation());

        // Calculate recovered pose translation
        float[] observationTrans = observationAbsPose.getTranslation();
        float[] curClientTrans = curClientWorldPose.getTranslation();
        float[] tmpTrans = new float[3];
        // curClientTrans as origin
        for (int i = 0; i < 3; i++) { tmpTrans[i] = observationTrans[i] - curClientTrans[i]; }
        Pose recoveredPose = new Pose(tmpTrans, new float[]{0, 0, 0, 1});
        // Rotate according to origin
        recoveredPose = relRot.compose(recoveredPose);
        float[] recoveredPoseTrans = recoveredPose.getTranslation();
        // Scale and move back
        for (int i = 0; i < 3; i++) { recoveredPoseTrans[i] = recoveredPoseTrans[i] / curScale + relTrans.getTranslation()[i]; }

        return new Pose(recoveredPoseTrans, recoveredRot.getRotationQuaternion());
      }
    }
    return null;
  }

  private YuvImage toYuvImage(Image image) {
    // This function is referenced to:
    // https://gist.github.com/mebjas/c0f1a147b4de35fa8cd3b5364aab4a2d
    if (image.getFormat() != ImageFormat.YUV_420_888) {
      throw new IllegalArgumentException("Invalid image format");
    }

    int width = image.getWidth();
    int height = image.getHeight();

    // Order of U/V channel guaranteed, read more:
    // https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888
    Image.Plane yPlane = image.getPlanes()[0];
    Image.Plane uPlane = image.getPlanes()[1];
    Image.Plane vPlane = image.getPlanes()[2];

    ByteBuffer yBuffer = yPlane.getBuffer();
    ByteBuffer uBuffer = uPlane.getBuffer();
    ByteBuffer vBuffer = vPlane.getBuffer();

    // Full size Y channel and quarter size U+V channels.
    int numPixels = (int) (width * height * 1.5f);
    byte[] nv21 = new byte[numPixels];
    int index = 0;

    // Copy Y channel.
    int yRowStride = yPlane.getRowStride();
    int yPixelStride = yPlane.getPixelStride();
    for(int y = 0; y < height; ++y) {
      for (int x = 0; x < width; ++x) {
        nv21[index++] = yBuffer.get(y * yRowStride + x * yPixelStride);
      }
    }

    // Copy VU data
    // NV21 format is expected to have YYYYVU packaging.
    // The U/V planes are guaranteed to have the same row stride and pixel stride.
    int uvRowStride = uPlane.getRowStride();
    int uvPixelStride = uPlane.getPixelStride();
    int uvWidth = width / 2;
    int uvHeight = height / 2;

    for(int y = 0; y < uvHeight; ++y) {
      for (int x = 0; x < uvWidth; ++x) {
        // V channel
        nv21[index++] = vBuffer.get(y * uvRowStride + x * uvPixelStride);
        // U channel
        nv21[index++] = uBuffer.get(y * uvRowStride + x * uvPixelStride);
      }
    }
    return new YuvImage(nv21, ImageFormat.NV21, width, height, /* strides= */ null);
  }

  private byte[] imageToByte(Image image) {
    // (Image with YUV_420_888 format) curImage -> (YuvImage) yuvImg -> (byte[])
    ByteArrayOutputStream ostream = new ByteArrayOutputStream();
    YuvImage yuvImg = toYuvImage(image);
    yuvImg.compressToJpeg(
            new Rect(0, 0, image.getWidth(), image.getHeight()),
            100, ostream);
    return ostream.toByteArray();
  }

  private void sendToServer(OutputStream clientSocketOut, byte[] data) {
    // Send byte array data to server through clientSocketOut
    if (clientSocketOut != null && clientSocket.isConnected()) {
      try {
        clientSocketOut.write(data);
        clientSocketOut.flush();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private String recvFromServer(InputStream clientSocketIn, int lengthN) {
    // Read lengthN characters from server
    try {
      String str = "";
      int tmp;
      while(lengthN-- > 0) {
        tmp = clientSocketIn.read();
        char c = (char) tmp;
        str += c;
      }
      return str;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private float[] qvecFromRTMtx(float[][] mtx) {
    // Returns qvec in (qx, qy, qz, qw)
    float[] qvec = new float[4];
    qvec[3] = (float) (Math.sqrt(1.0 + mtx[0][0] + mtx[1][1] + mtx[2][2]) / 2.0);
    float w4 = (float) (4.0 * qvec[3]);
    qvec[0] = -(mtx[2][1] - mtx[1][2]) / w4;
    qvec[1] = -(mtx[0][2] - mtx[2][0]) / w4;
    qvec[2] = -(mtx[1][0] - mtx[0][1]) / w4;
    return qvec;
  }

  private float[] tvecFromRTMtx(float[][] mtx) {
    // Returns tvec in (tx, ty, tz)
    // tvec = -R^T * T
    float[] tvec = new float[3];
    tvec[0] = -(mtx[0][0] * mtx[0][3] + mtx[1][0] * mtx[1][3] + mtx[2][0] * mtx[2][3]);
    tvec[1] = -(mtx[0][1] * mtx[0][3] + mtx[1][1] * mtx[1][3] + mtx[2][1] * mtx[2][3]);
    tvec[2] = -(mtx[0][2] * mtx[0][3] + mtx[1][2] * mtx[1][3] + mtx[2][2] * mtx[2][3]);
    return tvec;
  }

  private ServerResult recvServerResult(InputStream clientSocketIn) {
    float[][] mtx = new float[4][4];

    // Receive the 4*4 RT matrix from server
    for (int i = 0; i < 16; i++) {
      String tmpStr = recvFromServer(clientSocketIn, 18);
      tmpStr = tmpStr.substring(4, 15);
      mtx[i / 4][i % 4] = Float.parseFloat(tmpStr);
    }

    // Receive imageIdx
    String imageIdxStr = recvFromServer(clientSocketIn, 10);
    int imageIdx = Integer.parseInt(imageIdxStr.replaceAll("\\s", ""));

    // (RT matrix) mtx -> (ar.core.Pose) serverPose
    float[] qvec = qvecFromRTMtx(mtx);
    float[] tvec = tvecFromRTMtx(mtx);
    Pose serverPose = new Pose(tvec, qvec);

    // Update server result index
    latestServerResultIdx = imageIdx;

    return new ServerResult(imageIdx, serverPose, toAbsolutePose(serverPose, "colmap"));
  }

  private final Runnable sendCurTrackConfThread = new Runnable() {
    @Override
    public void run() {
      // Configure client socket
      clientSocket = null;
      while(clientSocket == null) {
        try {
          clientSocket = new Socket(InetAddress.getByName(SERVER_ADDRESS), SERVER_PORT);
        } catch (IOException e) {
          failconnect = true;
          // failtimes += 1;
          e.printStackTrace();
        }
      }

      if (clientSocket != null) {
        // Grab output stream of client socket
        OutputStream clientSocketOut = null;
        try {
          clientSocketOut = clientSocket.getOutputStream();
        } catch (IOException e) {
          e.printStackTrace();
        }

        // Start listening from server
        new Thread(socketThreadIn).start();
        failconnect = false;
        successconnect = true;

        // Send data over the client socket
        while (clientSocketOut != null && clientSocket.isConnected()) {
          // Wait until query image is sent to server
          if (frame != null) {
            if (availServerResultCnt != 0) {

              Pose curClientPose = camera.getPose();
              Pose curClientAbsPose = toAbsolutePose(curClientPose, "arcore");
              Pose curWorldPose = null;
              if (isFusedPoseReady) {
                curWorldPose = absClientPoseToWorldPose(curClientAbsPose);
              }

              CheckPoint prevAvailCheckPoint = checkPointsMap.get(latestAvailServerResultIdx);
              Pose prevCkptServerPose = prevAvailCheckPoint.getAbsServerPose();

              float distFused2PrevSSL = calcPosesDist(curWorldPose, prevCkptServerPose) / curScale;
              float angleFused2PrevSSL = calcPosesAngle(curWorldPose, prevCkptServerPose);

              // Penalty to confidence by tracking length
              curTrackConf -= distFused2PrevSSL * 0.05;
              curTrackConf -= angleFused2PrevSSL * 0.003;
            }

            synchronized(syncSendQueryImageLockObject) {
              System.out.println(sendingQueryImage);
              if (!sendingQueryImage) {
                System.out.println("Sending track conf");
                @SuppressLint("DefaultLocale") byte[] packetCurTrackConf = (
                        String.format("%010.4f", curTrackConf) + "\0").getBytes();
                sendToServer(clientSocketOut, packetCurTrackConf);
              }
            }
          }

          // Delay 1 sec
          try {
            sleep(1000);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }
  };

  private final Runnable socketThreadIn = new Runnable() {
    @Override
    public void run() {
      if (clientSocket != null) {
        // Grab input/output stream of client socket
        InputStream clientSocketIn;
        OutputStream clientSocketOut;
        try {
          clientSocketIn = clientSocket.getInputStream();
          clientSocketOut = clientSocket.getOutputStream();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        while (clientSocketIn != null && clientSocketOut != null && clientSocket.isConnected()) {
          String serverCmd = recvFromServer(clientSocketIn, 10);
          System.out.println(serverCmd);

          if (serverCmd.equals("send_query")) {
            try {
              synchronized(syncSendQueryImageLockObject) {
                sendingQueryImage = true;

                // Send a query image to server
                if (frame != null) {
                  Image curImage;
                  Pose curClientPose;
                  float[] focalLength;

                  // Acquire the current AR image and pose
                  // Avoid a session update to cause DeadlineExceededException
                  synchronized (syncAcquireImageLockObject) {
                    acquireImageLock = true;
                    curImage = frame.acquireCameraImage();
                    curClientPose = camera.getPose();
                    focalLength = camera.getImageIntrinsics().getFocalLength();
                    acquireImageLock = false;
                    syncAcquireImageLockObject.notify();
                  }

                  Pose curClientAbsPose = toAbsolutePose(curClientPose, "arcore");
                  Pose oldWorldPose = null;
                  if (isFusedPoseReady) {
                    oldWorldPose = absClientPoseToWorldPose(curClientAbsPose);
                  }

                  // Prepare data to send
                  // Note: Keys and values of meta data should NOT contain the following characters:
                  //       = | \0
                  byte[] packetImage = imageToByte(curImage);
                  String focalLengthStr = String.format("% 10.4f % 10.4f", focalLength[0], focalLength[1]);
                  @SuppressLint("DefaultLocale") byte[] packetMetaData = (
                          "img_size=" + packetImage.length  + "|" +
                          "frame_idx=" + imageIdx + "|" +
                          "focal_length=" + focalLengthStr + "|" +
                          "cur_fused_pose=" + oldWorldPose + "|" +
                          "cur_track_conf=" + curTrackConf + "\0"
                  ).getBytes();
                  @SuppressLint("DefaultLocale") byte[] packetMetaDataSize = (
                          String.format("%010d", packetMetaData.length) + "\0").getBytes();

                  // Send data
                  sendToServer(clientSocketOut, "send_query\0".getBytes());
                  sendToServer(clientSocketOut, packetMetaDataSize);
                  sendToServer(clientSocketOut, packetMetaData);
                  sendToServer(clientSocketOut, packetImage);

                  // Record checkpoint
                  CheckPoint checkPoint = new CheckPoint(imageIdx, curClientPose, curClientAbsPose, oldWorldPose, focalLengthStr);
                  checkPointsMap.put(imageIdx, checkPoint);

                  // Record image log
                  if (recordLog) {
                    String imageIdxStamp = String.valueOf(imageIdx);
                    File dir = new File(getApplicationContext().getFilesDir(), startTimeStr);
                    File sampledImgsDir = new File(dir, "sampled_imgs");
                    try {
                      File imageFile = new File(sampledImgsDir, "server-" + imageIdxStamp + ".jpg");
                      OutputStream stream = Files.newOutputStream(imageFile.toPath());
                      YuvImage yuvImg = toYuvImage(curImage);
                      yuvImg.compressToJpeg(
                              new Rect(0, 0, yuvImg.getWidth(), yuvImg.getHeight()),
                              100, stream);
                      stream.flush();
                      stream.close();
                    } catch (Exception e) {
                      e.printStackTrace();
                    }
                  }

                  // Close curImage to free up the memory
                  curImage.close();

                  // Increase imageIdx and reset imageSubIdx
                  imageIdx++;
                  imageSubIdx = 0;
                } else {
                  System.out.println("frame is null");
                }

                sendingQueryImage = false;
                syncSendQueryImageLockObject.notify();
              }
            } catch (NotYetAvailableException e) {
              System.out.println("NotYetAvailableException");
            } catch (DeadlineExceededException e){
              System.out.println("DeadlineExceededException");
            }
          } else if (serverCmd.equals("result_mtx")) {
            // Receive server results
            ServerResult newServerResult = recvServerResult(clientSocketIn);
            int corrCheckPointIdx = newServerResult.getImageIdx();
            CheckPoint corrCheckPoint = checkPointsMap.get(corrCheckPointIdx);
            corrCheckPoint.setServerPose(newServerResult.getServerPose(), newServerResult.getServerAbsPose());

            // Determine if server result is available
            boolean ServerResultIsAvailable = false;
            if (availServerResultCnt == 0 || !isFusedPoseReady) {
              // Always believe the first result from server is correct
              curTrackConf = 0.7f;
              ServerResultIsAvailable = true;
            } else {
              // Filter out server result if it is obviously wrong
              CheckPoint prevAvailCheckPoint = checkPointsMap.get(latestAvailServerResultIdx);
              Pose oldWorldPose = corrCheckPoint.getOldWorldPose();
              Pose ckptServerPose = corrCheckPoint.getAbsServerPose();
              Pose prevCkptServerPose = prevAvailCheckPoint.getAbsServerPose();

              float distFused2SSL = calcPosesDist(oldWorldPose, ckptServerPose) / curScale;
              float angleFused2SSL = calcPosesAngle(oldWorldPose, ckptServerPose);

              float distFused2PrevSSL = calcPosesDist(oldWorldPose, prevCkptServerPose) / curScale;
              float angleFused2PrevSSL = calcPosesAngle(oldWorldPose, prevCkptServerPose);

              // Penalty to confidence by tracking length
              curTrackConf -= distFused2PrevSSL * 0.05;
              curTrackConf -= angleFused2PrevSSL * 0.003;

              // Decide the correctness of the server result according to error measurements
              if (distFused2SSL <= 0.05 && angleFused2SSL <= 2) {
                curTrackConf += 0.2;
                ServerResultIsAvailable = true;
              } else if (distFused2SSL <= 0.1 && angleFused2SSL <= 4) {
                curTrackConf += 0.1;
                ServerResultIsAvailable = true;
              } else if (distFused2SSL <= 0.2 && angleFused2SSL <= 4) {
                curTrackConf += 0.05;
                ServerResultIsAvailable = true;
              } else if (distFused2SSL <= 0.3 && angleFused2SSL <= 6) {
                curTrackConf += 0.0;
                ServerResultIsAvailable = true;
              } else if (distFused2SSL <= 0.4 && angleFused2SSL <= 8) {
                curTrackConf -= 0.1;
              } else if (distFused2SSL > 0.8 || angleFused2SSL > 10) {
                curTrackConf -= 0.2;
              } else if (distFused2SSL > 0.6 || angleFused2SSL > 8) {
                curTrackConf -= 0.2;
              } else if (distFused2SSL > 0.4 || angleFused2SSL > 6) {
                curTrackConf -= 0.1;
              }

              // Limit confidence between 0 and 1
              curTrackConf = (float) Math.min(Math.max(curTrackConf, 0.0), 1.0);

              if (curTrackConf < 0.2) {
                // Believe this result from server is correct if the confidence is too low
                curTrackConf = 0.7f;
                ServerResultIsAvailable = true;
              }
            }

            corrCheckPoint.setIsAvailable(ServerResultIsAvailable);

            if (ServerResultIsAvailable) {
              // Calculate relative pose of the corresponding checkpoint
              Pose relRot = corrCheckPoint.getAbsServerPose().extractRotation().compose(
                      corrCheckPoint.getAbsClientPose().extractRotation().inverse());
              Pose relTrans = corrCheckPoint.getAbsServerPose();
              corrCheckPoint.setRelativeRot(relRot);
              corrCheckPoint.setRelativeTrans(relTrans);

              // Mark flags
              latestAvailServerResultIdx = corrCheckPointIdx;
              availServerResultCnt++;
              isFusedPoseReady = true;
            }

            // Update checkPointsMap
            checkPointsMap.replace(corrCheckPointIdx, corrCheckPoint);

            // Record text log
            clientReceivedLog();
          } else if (serverCmd.equals("curr_scale")) {
            // Receive and update curScale from server
            curScale = Float.parseFloat(recvFromServer(clientSocketIn, 20));
          }
        }
      }
    }
  };
  ////
}

////
class ServerResult {
  private int imageIdx;
  private Pose serverPose;
  private Pose serverAbsPose;

  public ServerResult(int imageIdx, Pose serverPose, Pose serverAbsPose) {
    this.imageIdx = imageIdx;
    this.serverPose = serverPose;
    this.serverAbsPose = serverAbsPose;
  }

  public int getImageIdx() { return imageIdx; }

  public Pose getServerPose() { return serverPose; }

  public Pose getServerAbsPose() { return serverAbsPose; }
}

class CheckPoint {
  private int imageIdx;
  private boolean isAvailable;
  private Pose clientPose;
  private Pose serverPose;
  private Pose clientAbsPose;
  private Pose oldWorldPose;
  private Pose serverAbsPose;
  private Pose relativeTrans;
  private Pose relativeRot;
  private String focalLength;

  public CheckPoint(int imageIdx, Pose clientPose, Pose clientAbsPose, Pose oldWorldPose, String focalLength) {
    this.imageIdx = imageIdx;
    this.clientPose = clientPose;
    this.clientAbsPose = clientAbsPose;
    this.oldWorldPose = oldWorldPose;
    this.focalLength = focalLength;
  }

  public int getImageIdx() { return imageIdx; }
  public boolean getIsAvailable() { return isAvailable; }
  public void setIsAvailable(boolean isAvailable) { this.isAvailable = isAvailable; }

  public Pose getClientPose() { return clientPose; }

  public Pose getAbsClientPose() { return clientAbsPose; }

  public Pose getOldWorldPose() { return oldWorldPose; }

  public String getFocalLength() { return focalLength;}

  public Pose getServerPose() { return serverPose; }

  public Pose getAbsServerPose() { return serverAbsPose; }

  public void setServerPose(Pose serverPose, Pose serverAbsPose) {
    this.serverPose = serverPose;
    this.serverAbsPose = serverAbsPose;
  }

  public Pose getRelativeTrans() { return relativeTrans; }

  public void setRelativeTrans(Pose relativeTrans) { this.relativeTrans = relativeTrans; }

  public Pose getRelativeRot() { return relativeRot; }

  public void setRelativeRot(Pose relativeRot) { this.relativeRot = relativeRot; }
}
////

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
class WrappedAnchor {
  private Anchor anchor;
  private Trackable trackable;

  public WrappedAnchor(Anchor anchor, Trackable trackable) {
    this.anchor = anchor;
    this.trackable = trackable;
  }

  public Anchor getAnchor() {
    return anchor;
  }

  public Trackable getTrackable() {
    return trackable;
  }
}
