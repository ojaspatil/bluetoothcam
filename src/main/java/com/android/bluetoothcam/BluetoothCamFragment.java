package com.android.bluetoothcam;

import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.android.common.logger.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * This fragment controls Bluetooth to communicate with other devices.
 */
public class BluetoothCamFragment extends Fragment {

    private static final String TAG = "BluetoothCamFragment";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;
    static Camera camera = null;
    int cameraId;
    // Layout Views
    private Button mStartStreamButton;
    private Button mStopStreamButton;
    private SurfaceView mSurfaceView;
    SurfaceHolder surfaceHolder;
    boolean previewing = false;

    /**
     * Name of the connected device
     */
    private String mConnectedDeviceName = null;

     /**
     * String buffer for outgoing messages
     */
    private StringBuffer mOutStringBuffer;

    /**
     * Local Bluetooth adapter
     */
    private BluetoothAdapter mBluetoothAdapter = null;

    /**
     * Member object for the cam services
     */
    private BluetoothCamService mCamService = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            FragmentActivity activity = getActivity();
            Toast.makeText(activity, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            activity.finish();
        }
    }


    @Override
    public void onStart() {
        super.onStart();
        // If BT is not on, request that it be enabled.
        // setupCam() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // Otherwise, setup the cam session
        } else if (mCamService == null) {
            setupCam();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mCamService != null) {
            mCamService.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mCamService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mCamService.getState() == BluetoothCamService.STATE_NONE) {
                // Start the Bluetooth cam services
                mCamService.start();
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bluetooth_cam, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        mSurfaceView = (SurfaceView) view.findViewById(R.id.surfaceView);
        mStartStreamButton = (Button) view.findViewById(R.id.button_start_stream);
        mStopStreamButton = (Button) view.findViewById(R.id.button_stop_stream);
    }

    /**
     * Set up the UI and background operations for cam.
     */
    private void setupCam() {
        Log.d(TAG, "setupCam()");

        // Initialize the start stream button with a listener that for click events
        mStartStreamButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                View view = getView();
                if (null != view) {
                    mSurfaceView = (SurfaceView) view.findViewById(R.id.surfaceView);
                    surfaceHolder = mSurfaceView.getHolder();
                    surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
                    if (!previewing) {
                        try {
                            cameraId = findBackFacingCamera();
                            releaseCameraAndPreview();
                            camera = android.hardware.Camera.open();
                            camera.setDisplayOrientation(90);

                        } catch (Exception e) {
                            Log.e(getString(R.string.app_name), "failed to open Camera");
                            e.printStackTrace();
                        }
                        if (camera != null) {
                            try {

                                Camera.Parameters params = camera.getParameters();
                                int previewHeight = params.getPreviewSize().height;
                                int previewWidth = params.getPreviewSize().width;
                                int previewFormat = params.getPreviewFormat();
                                params.setPreviewFpsRange(15000, 30000);
                                params.setPreviewFrameRate(30);

                                // Crop the edges of the picture to reduce the image size
                                Rect r = new Rect(80, 20, previewWidth - 80, previewHeight - 20);

                                byte[] mCallbackBuffer = new byte[5000000];

                                camera.setParameters(params);
                                camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                                    private long timestamp = 0;

                                    public synchronized void onPreviewFrame(byte[] data, Camera camera) {
                                        Log.v("CameraTest", "Time Gap = " + (System.currentTimeMillis() - timestamp));
                                        timestamp = System.currentTimeMillis();
                                        //do picture data process
                                        camera.addCallbackBuffer(data);
                                        Camera.Parameters parameters = camera.getParameters();
                                        int width = parameters.getPreviewSize().width;
                                        int height = parameters.getPreviewSize().height;

                                        YuvImage yuv = new YuvImage(data, parameters.getPreviewFormat(), width, height, null);

                                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                                        yuv.compressToJpeg(new Rect(0, 0, width, height), 50, out);

                                        byte[] bytes = out.toByteArray();
                                        final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                                        sendData(bytes);
                                        return;
                                    }
                                });
                                camera.addCallbackBuffer(mCallbackBuffer);
                                camera.startPreview();
                                previewing = true;
                                camera.setPreviewDisplay(surfaceHolder);
                            } catch (IOException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        });

        mStopStreamButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View view = getView();
                if (null != view) {
                    if (camera != null) {
                        try {
                            camera.stopPreview();
                            camera.release();
                            camera = null;
                            previewing = false;
                            mSurfaceView.draw(new Canvas());
                        } catch (Exception e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                }
            }
        });

        // Initialize the BluetoothCamService to perform bluetooth connections
        mCamService = new BluetoothCamService(getActivity(), mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");
    }

    private void releaseCameraAndPreview() {
        if (camera != null) {
            camera.release();
            camera.setPreviewCallback(null);
            camera = null;
        }
    }

    private int findBackFacingCamera() {

        // Search for the front facing camera
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                cameraId = i;
                break;
            }
        }
        return cameraId;
    }


    /**
     * Makes this device discoverable.
     */
    private void ensureDiscoverable() {
        if (mBluetoothAdapter.getScanMode() !=
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
        }
    }


    private void sendData(byte[] data) {
        // Check that we're actually connected before trying anything
        if (mCamService.getState() != BluetoothCamService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        mCamService.write(data);
    }


    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private void sendMessage(String message) {
        // Check that we're actually connected before trying anything
        if (mCamService.getState() != BluetoothCamService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            // Get the message bytes and tell the BluetoothCamService to write
            byte[] send = message.getBytes();
            mCamService.write(send);
            // Reset out string buffer to zero and clear the edit text field
            mOutStringBuffer.setLength(0);
        }
    }

    /**
     * Updates the status on the action bar.
     *
     * @param resId a string resource ID
     */
    private void setStatus(int resId) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(resId);
    }

    /**
     * Updates the status on the action bar.
     *
     * @param subTitle status
     */
    private void setStatus(CharSequence subTitle) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(subTitle);
    }

    /**
     * The Handler that gets information back from the BluetoothCamService
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            FragmentActivity activity = getActivity();
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothCamService.STATE_CONNECTED:
                            setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
                            break;
                        case BluetoothCamService.STATE_CONNECTING:
                            setStatus(R.string.title_connecting);
                            break;
                        case BluetoothCamService.STATE_LISTEN:
                        case BluetoothCamService.STATE_NONE:
                            setStatus(R.string.title_not_connected);
                            break;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    break;
                case Constants.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    System.out.println("READ BUFF LENGTH" + readBuf.length);
                    // construct a string from the valid bytes in the buffer
                    //String readMessage = new String(readBuf, 0, msg.arg1);
                    System.out.println("_______READ MESSAGE_________" + readBuf);

                    SurfaceHolder mHolder = mSurfaceView.getHolder();
                    Canvas c = mHolder.lockCanvas(null);
                    //decodeYUV(rgbbuffer, data, 100, 100);
                    final Bitmap bitmap = BitmapFactory.decodeByteArray(readBuf, 0, readBuf.length);
                    if (bitmap != null) {
                        c.drawBitmap(bitmap, 10, 10, new Paint());
                    }
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    if (null != activity) {
                        Toast.makeText(activity, "Connected to "
                                + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case Constants.MESSAGE_TOAST:
                    if (null != activity) {
                        Toast.makeText(activity, msg.getData().getString(Constants.TOAST),
                                Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        }
    };

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, false);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a cam session
                    setupCam();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(getActivity(), R.string.bt_not_enabled_leaving,
                            Toast.LENGTH_SHORT).show();
                    getActivity().finish();
                }
        }
    }

    /**
     * Establish connection with other divice
     *
     * @param data   An {@link Intent} with {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        String address = data.getExtras()
                .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mCamService.connect(device, secure);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.bluetooth_cam, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.secure_connect_scan: {
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE);
                return true;
            }
            case R.id.insecure_connect_scan: {
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);
                return true;
            }
            case R.id.discoverable: {
                // Ensure this device is discoverable by others
                ensureDiscoverable();
                return true;
            }
        }
        return false;
    }

}
