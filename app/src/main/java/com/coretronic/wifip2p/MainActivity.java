package com.coretronic.wifip2p;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;

import com.coretronic.wifip2p.wifip2pservice.WifiP2PService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int CODE_REQ_PERMISSIONS = 665;
    WifiP2PService wifiP2PService;
    WifiP2PService.WifiP2PBinder wifiP2PBinder;
    WifiP2PService.WifiP2PServiceListener wifiP2PServiceListener = new WifiP2PService.WifiP2PServiceListener() {
        @Override
        public void onPeerAvailable(Collection<WifiP2pDevice> wifiP2pDeviceList) {
            //Log.d(TAG, "onPeerAvailable#"+wifiP2pDeviceList);
            MainActivity.this.wifiP2pDeviceList.clear();
            MainActivity.this.wifiP2pDeviceList.addAll(wifiP2pDeviceList);
            deviceAdapter.notifyDataSetChanged();
        }

        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
            Log.d(TAG, "onConnectionInfoAvailable#");
            disconnectBtn.setVisibility(View.VISIBLE);
            if (wifiP2PService != null){
                String path = "/sdcard/Download/test.mp4";
                File file = new File(path);
                path = Environment.getExternalStorageDirectory().getAbsolutePath()+File.separator+"Download";
                file = new File(path, file.getName());
                if(file.exists()) {
                    Log.d(TAG, "Prepare sending file!!path="+file.getAbsolutePath());
                    wifiP2PService.prepareSendFileToServer(file.getAbsolutePath());
                }
                else {
                    Log.w(TAG, "Prepare sending file failed!! file not exist!!path="+file.getAbsolutePath());
                }
            }
        }

        @Override
        public void onDiscoverSuccess() {
            Log.d(TAG, "onDiscoverSuccess#");
        }

        @Override
        public void onDiscoverFailed(int reason) {
            Log.d(TAG, "onDiscoverFailed#");
            if (wifiP2PService != null)
                wifiP2PService.discoveryDevices();
        }

        @Override
        public void onConnectSuccess(WifiP2pDevice wifiP2pDevice) {
            Log.d(TAG, "onConnectSuccess#"+wifiP2pDevice);
        }

        @Override
        public void onConnectFailed(WifiP2pDevice wifiP2pDevice, int reason) {
            Log.d(TAG, "onConnectFailed#"+wifiP2pDevice+", reason="+reason);

        }

        @Override
        public void onProgressChanged(String path, int progress) {
            Log.d(TAG, "onProgressChanged#"+path+", progress="+progress);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(progress);
                }
            });

        }

        @Override
        public void onDataTransferFinished(String path) {
            Log.d(TAG, "onDataTransferFinished#"+path);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    progressBar.setVisibility(View.INVISIBLE);
                }
            });

        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "onDisconnected#");
            disconnectBtn.setVisibility(View.INVISIBLE);
            if (wifiP2PService != null)
                wifiP2PService.discoveryDevices();
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            wifiP2PBinder = (WifiP2PService.WifiP2PBinder) service;
            wifiP2PService = wifiP2PBinder.getService();
            wifiP2PService.setListener(wifiP2PServiceListener);
            wifiP2PService.discoveryDevices();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            wifiP2PBinder = null;
            wifiP2PService = null;
            bindService();
        }
    };// end ServiceConnection serviceConnection
    RecyclerView rv_deviceList;
    List<WifiP2pDevice> wifiP2pDeviceList = new ArrayList<>();
    DeviceAdapter deviceAdapter;
    WifiP2pDevice wifiP2pDevice;
    Button disconnectBtn;
    ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setMax(100);
        disconnectBtn = findViewById(R.id.disconect_btn);
        disconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (wifiP2PService != null)
                    wifiP2PService.disconnect();
            }
        });
        deviceAdapter = new DeviceAdapter(wifiP2pDeviceList);
        deviceAdapter.setClickListener(new DeviceAdapter.OnClickListener() {
            @Override
            public void onItemClick(int position) {
                wifiP2pDevice = wifiP2pDeviceList.get(position);
                wifiP2PService.connect(wifiP2pDevice);
            }
        });
        rv_deviceList = (RecyclerView)findViewById(R.id.rv_deviceList);
        rv_deviceList.setAdapter(deviceAdapter);
        rv_deviceList.setLayoutManager(new LinearLayoutManager(this));

        checkPermission();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CODE_REQ_PERMISSIONS) {
            for (int i = 0; i < grantResults.length; i++) {
                Log.d(TAG, "Check permission:" +permissions[i]);
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG,permissions[i]+" is not granted!!");
                    return;
                }
                Log.d(TAG, permissions[i]+" is granted");
            }
            Log.d(TAG, "Check permissions completed");
            bindService();
        }
    }

    public void checkPermission() {
        ActivityCompat.requestPermissions(MainActivity.this,
                new String[]{
                        //Manifest.permission.CHANGE_NETWORK_STATE,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.ACCESS_WIFI_STATE,
                        Manifest.permission.CHANGE_WIFI_STATE,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, CODE_REQ_PERMISSIONS);
    }

    public void bindService(){
        Intent intent = new Intent(MainActivity.this, WifiP2PService.class);
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
    }
}