/**
 * App Name: maRker
 * Author: William Foss
 *
 * For the CS477 Course Project
 * Dec 6, 2019
 *
 * This project borrows _heavily_ from the AR Sceneform example "SolarSystem",
 * released by Google under Apache License and available here:
 * https://developers.google.com/ar/develop/java/sceneform/samples
 *
 */




package com.infinitemonkey.marker;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
//import android.support.annotation.NonNull;
import androidx.annotation.NonNull;
//import android.support.design.widget.Snackbar;
import com.google.android.material.snackbar.Snackbar;
//import android.support.v7.app.AppCompatActivity;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ViewRenderable;

import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;


import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;


public class MainActivity extends AppCompatActivity implements LocationListener{
    private static final int RC_PERMISSIONS = 0x123;
    private boolean cameraPermissionRequested;

    private GestureDetector gestureDetector;
    private Snackbar loadingMessageSnackbar = null;

    private ArSceneView arSceneView;

    // ar anchors, for placement
    private Anchor anchor = null;
    private AnchorNode anchorNode = null;

    // true once all view renderables have loaded
    private boolean hasFinishedLoading = false;

    // True once the scene has been placed.
    private boolean hasPlacedScene = false;

    // messages
    private HashMap<String, Long> messageToTime = new HashMap<>();
    private HashMap<String, ViewRenderable> messageToViewRenderable;


    private static final int MAX_MSG = 10;
    private int msgCount = 0;
    private ViewRenderable[] vrs;
    private Node[] nodes = new Node[MAX_MSG];

    float viewRadius = 3.0f;
    private Button postButton;
    EditText textInput;
    Random r = new Random();

    // database stuff
    private final static String DEFAULT_LOCATION = "DEFAULT LOCATION";
    FirebaseDatabase database;
    private DatabaseReference databaseReference;

    // location stuff
    public LocationManager locationManager;
    public Criteria criteria;
    public String bestProvider;
    private boolean locationAvailable = true;
    private boolean dbSync = false;


    @Override
    @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
    // CompletableFuture requires api level 24
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!DemoUtils.checkIsSupportedDeviceOrFinish(this)) {
            // Not a supported device.
            return;
        }

        setContentView(R.layout.activity_main);
        arSceneView = findViewById(R.id.ar_scene_view);

        // Set up a tap gesture detector.
        gestureDetector =
                new GestureDetector(
                        this,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onSingleTapUp(MotionEvent e) {
                                onSingleTap(e);
                                return true;
                            }

                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true;
                            }
                        });

        // Set a touch listener on the Scene to listen for taps.
        arSceneView
                .getScene()
                .setOnTouchListener(
                        (HitTestResult hitTestResult, MotionEvent event) -> {
                            // If the scene hasn't been placed yet, detect a tap and then check to see if
                            // the tap occurred on an ARCore plane to place the scene
                            if (!hasPlacedScene) {
                                return gestureDetector.onTouchEvent(event);
                            }

                            // Otherwise return false so that the touch event can propagate to the scene.
                            return false;
                        });

        // Set an update listener on the Scene that will hide the loading message once a Plane is
        // detected.
        arSceneView
                .getScene()
                .addOnUpdateListener(
                        frameTime -> {
                            if (loadingMessageSnackbar == null) {
                                return;
                            }

                            Frame frame = arSceneView.getArFrame();
                            if (frame == null) {
                                return;
                            }

                            if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
                                return;
                            }

                            for (Plane plane : frame.getUpdatedTrackables(Plane.class)) {
                                if (plane.getTrackingState() == TrackingState.TRACKING) {
                                    hideLoadingMessage ();

                                    //showTapMessage();
                                }
                            }
                        });

        // Lastly request CAMERA permission which is required by ARCore.
        DemoUtils.requestCameraPermission(this, RC_PERMISSIONS);

        postButton = findViewById(R.id.button_post);
        postButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v)
            {
                onPost();
            }
        });
        postButton.setEnabled(false);
        textInput = findViewById(R.id.text_input);
        initViewRenderables();

        database = FirebaseDatabase.getInstance();
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (arSceneView == null) {
            return;
        }

        if (arSceneView.getSession() == null) {
            // If the session wasn't created yet, don't resume rendering.
            // This can happen if ARCore needs to be updated or permissions are not granted yet.
            try {
                Config.LightEstimationMode lightEstimationMode =
                        Config.LightEstimationMode.ENVIRONMENTAL_HDR;
                Session session =
                        cameraPermissionRequested
                                ? DemoUtils.createArSessionWithInstallRequest(this, lightEstimationMode)
                                : DemoUtils.createArSessionNoInstallRequest(this, lightEstimationMode);
                if (session == null) {
                    cameraPermissionRequested = DemoUtils.hasCameraPermission(this);
                    return;
                } else {
                    arSceneView.setupSession(session);
                }
            } catch (UnavailableException e) {
                DemoUtils.handleSessionException(this, e);
            }
        }

        try {
            arSceneView.resume();
        } catch (CameraNotAvailableException ex) {
            DemoUtils.displayError(this, "Unable to get camera", ex);
            finish();
            return;
        }

        if (arSceneView.getSession() != null) {
            showLoadingMessage();
        }
    }


    /* BEGIN AR CORE STUFF */

    private void onSingleTap(MotionEvent tap) {

        Frame frame = arSceneView.getArFrame();
        if (frame != null) {

            if(msgCount >= MAX_MSG) {
                msgCount = 0;
            }

            if(anchor == null || anchorNode == null) {
                if(!createAnchor(tap, frame)) {
                    return;
                }
            }

            postButton.setEnabled(true);
        }
    }


    private void onPost() {
        sendToServer();
        //String key = getLocationKey();
        //Toast.makeText(this, "Location Key: " + key, Toast.LENGTH_LONG).show();
    }

    private void postMessage(String messageText) {
        ViewRenderable vr = vrs[msgCount];
        View v = vr.getView();
        LinearLayout l = (LinearLayout) v;
        TextView t = null;
        boolean keepGoing = false;

        if(l!= null) {
            for(int i = 0; i < l.getChildCount(); i++) {
                t = (TextView) l.getChildAt(i);
                if(t != null) {
                    t.setText(messageText);
                    keepGoing = true;
                    break;
                }
            }
        } else {
            Toast.makeText(
                    this, "Linear Layout NOT found in view renderable!", Toast.LENGTH_LONG)
                    .show();
        }

        if(keepGoing) {

            Node messageNode = null;

            if (nodes[msgCount] != null) {
                messageNode = nodes[msgCount];
            } else {
                messageNode = new Node();
                nodes[msgCount] = messageNode;
                messageNode.setParent(anchorNode);
            }

            messageNode.setRenderable(vrs[msgCount]);
            float randX = (float) Math.random();
            float randY = (float) Math.random();
            float len = (float) Math.sqrt(randX * randX + randY * randY);
            randX /= len;
            randY /= len;
            float randRadius = viewRadius * r.nextFloat();
            randX = (r.nextFloat() * 1f) - 0.5f;
            randY = r.nextFloat();
            float randZ = r.nextFloat() - 0.5f;

            String m = "Positioning view at x: " + randX + ", y: " + randY + ", z: " + randZ;
            Toast.makeText(
                    this, m, Toast.LENGTH_LONG)
                    .show();

            messageNode.setLocalPosition(new Vector3(randX, randY, randZ));
            // need to set world rotation up here

            msgCount++;
            if (msgCount >= MAX_MSG) {
                msgCount = 0;
            }


        }
    }

    private boolean createAnchor(MotionEvent tap, Frame frame) {
        boolean created = false;
        if (tap != null && frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
            for (HitResult hit : frame.hitTest(tap)) {
                if (anchor == null || anchorNode == null) {
                    anchor = hit.createAnchor();
                    anchorNode = new AnchorNode(anchor);
                    anchorNode.setParent(arSceneView.getScene());
                    created = true;
                    syncDB();
                    break;
                }
            }
        }
        return created;
    }


    private void initViewRenderables() {
        LinearLayout layout = new LinearLayout(this);
        LayoutInflater inflater = getLayoutInflater();

        View viewLayoutA = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutB = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutC = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutD = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutE = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutF = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutG = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutH = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutI = inflater.inflate(R.layout.text_card, layout,false);
        View viewLayoutJ = inflater.inflate(R.layout.text_card, layout,false);

        CompletableFuture<ViewRenderable> stageA =
                ViewRenderable.builder().setView(this, viewLayoutA).build();

        CompletableFuture<ViewRenderable> stageB =
                ViewRenderable.builder().setView(this, viewLayoutB).build();

        CompletableFuture<ViewRenderable> stageC =
                ViewRenderable.builder().setView(this, viewLayoutC).build();

        CompletableFuture<ViewRenderable> stageD =
                ViewRenderable.builder().setView(this, viewLayoutD).build();

        CompletableFuture<ViewRenderable> stageE =
                ViewRenderable.builder().setView(this, viewLayoutE).build();

        CompletableFuture<ViewRenderable> stageF =
                ViewRenderable.builder().setView(this, viewLayoutF).build();

        CompletableFuture<ViewRenderable> stageG =
                ViewRenderable.builder().setView(this, viewLayoutG).build();

        CompletableFuture<ViewRenderable> stageH =
                ViewRenderable.builder().setView(this, viewLayoutH).build();

        CompletableFuture<ViewRenderable> stageI =
                ViewRenderable.builder().setView(this, viewLayoutI).build();

        CompletableFuture<ViewRenderable> stageJ =
                ViewRenderable.builder().setView(this, viewLayoutJ).build();

        vrs = new ViewRenderable[MAX_MSG];

        CompletableFuture.allOf(stageA, stageB, stageC, stageD, stageE, stageF, stageG, stageH, stageI, stageJ)
                .handle(
                        (notUsed, throwable) -> {
                            // When you build a Renderable, Sceneform loads its resources in the background while
                            // returning a CompletableFuture. Call handle(), thenAccept(), or check isDone()
                            // before calling get().

                            if (throwable != null) {
                                DemoUtils.displayError(this, "Unable to load renderable", throwable);
                                return null;
                            }

                            try {
                                vrs[0] = stageA.get();
                                vrs[1] = stageB.get();
                                vrs[2] = stageC.get();
                                vrs[3] = stageD.get();
                                vrs[4] = stageE.get();
                                vrs[5] = stageF.get();
                                vrs[6] = stageG.get();
                                vrs[7] = stageH.get();
                                vrs[8] = stageI.get();
                                vrs[9] = stageJ.get();
                                hasFinishedLoading = true;
                            } catch (InterruptedException | ExecutionException ex) {
                                DemoUtils.displayError(this, "Unable to load renderable", ex);
                            }
                            return null;
                        });

    }

    /* END AR CORE STUFF */



    /* BEGIN FIREBASE STUFF */
    private void sendToServer() {
        String fromInput = textInput.getText().toString();
        String messageText = null;

        if(fromInput != null && fromInput.length() > 0) {
            messageText = fromInput;
            Long time = java.lang.System.currentTimeMillis();
            messageToTime.put(messageText, time);
        } else {
            Toast.makeText(
                    this, "Please enter a message to post in the text box!", Toast.LENGTH_LONG)
                    .show();
            return;
        }

        if(setLocation()) {
            databaseReference.setValue(messageText);
        }
    }

    private void syncDB() {
        String location = getLocationKey();

        if (location != null) {
            Toast.makeText(getApplicationContext(), "Loading messages from lat-long: " + location, Toast.LENGTH_LONG).show();

            for (int i = 0; i < MAX_MSG; i++) {
                String entry = location + "/" + i;
                database = FirebaseDatabase.getInstance();
                databaseReference = database.getReference(entry);
                databaseReference.addValueEventListener(new ValueEventListener() {

                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        String s = (String) dataSnapshot.getValue(String.class);
                        if (s != null && s.length() > 0) {
                            postMessage(s);
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        // Failed to read value
                        //Log.w(TAG, "Failed to read value.", error.toException());
                    }
                });
            }
        }
        dbSync = true;
    }


    private boolean setLocation() {
        String location = getLocationKey();

        if(location != null) {
            location += "/" + msgCount;

            try {
                database = FirebaseDatabase.getInstance();
                databaseReference = database.getReference(location);
                return true;
            } catch (Exception e) {
                Toast.makeText(this, "Database exception caught!", Toast.LENGTH_LONG);
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG);
                return false;
            }
        } else {
            return false;
        }
    }

    private String getLocationKey() {
        double currentLat, currentLong;
        currentLat = currentLong = 0;

        if(locationAvailable) {
            try {
                locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                criteria = new Criteria();
                bestProvider = String.valueOf(locationManager.getBestProvider(criteria, true)).toString();
                Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (location != null) {
                    currentLat = location.getLatitude();
                    currentLong = location.getLongitude();
                    Toast.makeText(getApplicationContext(),
                            "Setting location to " + currentLat + ", " + currentLong,
                            Toast.LENGTH_SHORT).show();
                } else {
                    locationAvailable = false;
                    locationManager.requestLocationUpdates(bestProvider,1000,0,this);
                    return null;
                }
            } catch (SecurityException e) {

            }
            String locationKey = null;

            if(currentLat != 0 && currentLong != 0) {
                locationKey = Double.toString(currentLat) + Double.toString(currentLong);
                locationKey = locationKey.replace(".", "");
            } else {
                locationKey = DEFAULT_LOCATION;
            }

            return locationKey;
            //return DEFAULT_LOCATION;
        } else {
            Toast.makeText(this,
                    "Waiting for location services to become available.",
                    Toast.LENGTH_LONG)
                    .show();
            return null;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        locationAvailable = true;
        setLocation();
        if(!dbSync) {
            syncDB();
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }

    public void searchNearestPlace(String v2txt) {
        //.....
    }


    @Override
    public void onPause() {
        super.onPause();
        if (arSceneView != null) {
            arSceneView.pause();
        }
        if(locationManager!= null) {
            locationManager.removeUpdates(this);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (arSceneView != null) {
            arSceneView.destroy();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        if (!DemoUtils.hasCameraPermission(this)) {
            if (!DemoUtils.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                DemoUtils.launchPermissionSettings(this);
            } else {
                Toast.makeText(
                        this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                        .show();
            }
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Standard Android full-screen functionality.
            getWindow()
                    .getDecorView()
                    .setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }



    private void showLoadingMessage() {
        if (loadingMessageSnackbar != null && loadingMessageSnackbar.isShownOrQueued()) {
            return;
        }

        loadingMessageSnackbar =
                Snackbar.make(
                        MainActivity.this.findViewById(android.R.id.content),
                        "Once surfaces have been located, tap the screen to show and add messages...",
                        Snackbar.LENGTH_INDEFINITE);
        loadingMessageSnackbar.getView().setBackgroundColor(0xbf323232);
        loadingMessageSnackbar.show();
    }


    private void hideLoadingMessage() {
        if (loadingMessageSnackbar == null) {
            return;
        }

        loadingMessageSnackbar.dismiss();
        loadingMessageSnackbar = null;
    }



    /* END FIREBASE STUFF */
}
