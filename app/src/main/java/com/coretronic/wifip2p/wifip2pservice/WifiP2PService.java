package com.coretronic.wifip2p.wifip2pservice;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

public class WifiP2PService extends Service {
    static final String TAG = WifiP2PService.class.getSimpleName();
    static final int COMMAND_PORT = 4456;
    static final int DATA_PORT = 4457;

    public interface  WifiP2PServiceListener {
        void onPeerAvailable(Collection<WifiP2pDevice> wifiP2pDeviceList);
        void onDiscoverSuccess();
        void onDiscoverFailed(int reason);
        void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo);
        void onConnectSuccess(WifiP2pDevice wifiP2pDevice);
        void onConnectFailed(WifiP2pDevice wifiP2pDevice, int reason);
        void onProgressChanged(String path , int progress);
        void onDataTransferFinished(String path);
        void onDisconnected();
    }

    public class WifiP2PBinder extends Binder {
        public WifiP2PService getService() {
            return WifiP2PService.this;
        }
    }

    public static class WifiP2PCommand implements Serializable{
        public final static int COMMAND_TRANSFER_FILE = 100;
        public final static int COMMAND_REQUEST_FILE = 101;

        private int command = 0;

        public void setCommand(int _command){command = _command;}

        public int getCommand(){return command;}
    }

    public static class FileTransferInfo implements Serializable {

        //文件路径
        private String filePath;

        //文件大小
        private long fileLength;

        private long offset;

        //MD5码
        private String md5;

        public FileTransferInfo(String name, long fileLength, long offset) {
            this.filePath = name;
            this.fileLength = fileLength;
            this.offset = offset;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public long getFileLength() {
            return fileLength;
        }

        public void setFileLength(long fileLength) {
            this.fileLength = fileLength;
        }

        public long getOffset() {
            return offset;
        }

        public void setOffset(long offset) {
            this.offset = offset;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }

        @NonNull
        @Override
        public String toString() {
            return "FileTransfer{" +
                    "filePath='" + filePath + '\'' +
                    ", fileLength=" + fileLength +
                    ", offset=" + offset +
                    ", md5='" + md5 + '\'' +
                    '}';
        }

    }// end of class FileTransferInfo

    private  interface WifiP2PListener extends WifiP2pManager.ChannelListener{
        void wifiP2pEnabled(boolean enabled);
        void onPeerAvailable(Collection<WifiP2pDevice> wifiP2pDeviceList);
        void onDisconnected();
        void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo);
        void onSelfDeviceAvailable(WifiP2pDevice wifiP2pDevice);
    }

    private  class WifiP2PBroadcastReceiver extends BroadcastReceiver {
        public IntentFilter getIntentFilter() {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

            return intentFilter;
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null)
                return;
            switch (action) {
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:{
                    boolean wifiP2pEnabled = (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                            -100) ==  WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                    //  Handle WIFI_P2P_STATE_ENABLED_CHANGED
                    wifiP2PListener.wifiP2pEnabled(wifiP2pEnabled);
                    break;
                }
                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION: {
                    wifiP2pManager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                        @Override
                        public void onPeersAvailable(WifiP2pDeviceList peers) {
                            //  Handle Peer Available
                            wifiP2PListener.onPeerAvailable(peers.getDeviceList());
                        }
                    });
                    break;
                }
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:{
                    NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (networkInfo == null || !networkInfo.isConnected()) {
                        //  Handle WiFi P2P Disconnected
                        wifiP2PListener.onDisconnected();
                        return;
                    }
                    wifiP2pManager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                        @Override
                        public void onConnectionInfoAvailable(WifiP2pInfo info) {
                            wifiP2PListener.onConnectionInfoAvailable(info);
                        }
                    });
                    break;
                }
                case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: {
                    WifiP2pDevice wifiP2pDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    wifiP2PListener.onSelfDeviceAvailable(wifiP2pDevice);
                    break;
                }
            }// end switch
        }

    }

    private class CommandServerSocketAsyncTask extends AsyncTask<Object, Object, String> {

        public volatile InetAddress lastClientInetAddress;
        protected volatile boolean isStop = false;
        private ServerSocket serverSocket;

        private  String tag() {
            return CommandServerSocketAsyncTask.class.getSimpleName();
        }

        @Override
        protected String doInBackground(Object... params) {

            if (params.length < 1){
                String msg = "Invalid parameters, should pass more than 1 parameter!";
                Log.e(TAG, msg);
                return msg;
            }
            int port = 0;
            try {
                String stringPort =(String)params[0];
                Log.d(TAG, tag()+"# port = "+stringPort);
                port =  Integer.parseInt(stringPort);
            }catch (NullPointerException e){
                e.printStackTrace();
                port = COMMAND_PORT;
            }
            while(!isStop) {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(port));
                    Log.d(TAG, tag()+"#Listening...");
                    Socket clientSocket = serverSocket.accept();
                    lastClientInetAddress = clientSocket.getInetAddress();
                    clientAddress = lastClientInetAddress;
                    Log.d(TAG, tag()+"#Accept connection from " + clientSocket.getInetAddress().getHostAddress());

                    InputStream inputStream = clientSocket.getInputStream();
                    ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
                    Log.d(TAG, tag()+"#Receiving object...");
                    Object message = objectInputStream.readObject();
                    if (message instanceof WifiP2PCommand) {
                        WifiP2PService.this.parseCommand((WifiP2PCommand) message, objectInputStream);
                    }
                    Log.d(TAG, tag()+"#Receiving object completed");
                    serverSocket.close();

                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }catch (IOException e) {
                    e.printStackTrace();
                }
            }//end while
            return "";
        }// end doInBackground

        @Override
        protected void onPostExecute(String data) {
            Log.d(TAG,tag()+"#onPostExecute");
            super.onPostExecute(data);
            this.notifyAll();
        }

        @Override
        protected void onCancelled() {
            this.notifyAll();
            Log.w(TAG, tag()+"#Socket Async Task is cancelled!");
        }

        @Override
        protected void onProgressUpdate(Object... values) {
            //Log.d(TAG, "Get object:"+values[0].toString());
        }
    }// end of class CommandServerSocketAsyncTask

    private  interface DataProgressListener {
        void onProgressChanged(FileTransferInfo fileTransferInfo, int progress);
        void onFinished(String path);
    }

    private  class DataServerSocketAsyncTask extends AsyncTask<Object, Object, String> {

        public volatile InetAddress lastClientInetAddress;
        protected volatile boolean isStop = false;
        private ServerSocket serverSocket;
        private int progress = 0 ;

        private  String tag() {
            return DataServerSocketAsyncTask.class.getSimpleName();
        }

        @Override
        protected String doInBackground(Object... params) {

            if (params.length < 1){
                String msg = tag()+"#Invalid parameters, should pass more than 1 parameter!";
                Log.e(TAG, msg);
                return msg;
            }

            int port = 0;
            try {
                String stringPort =(String)params[0];
                Log.d(TAG, tag()+"# port = "+stringPort);
                port = Integer.parseInt(stringPort);
            }catch (NullPointerException e){
                e.printStackTrace();
                port = DATA_PORT;
            }
            while(!isStop) {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(port));
                    Log.d(TAG, tag()+"#Listening...");
                    Socket clientSocket = serverSocket.accept();
                    lastClientInetAddress = clientSocket.getInetAddress();
                    Log.d(TAG, tag()+"#Accept connection from "+clientSocket.getInetAddress().getHostAddress());

                    InputStream inputStream = clientSocket.getInputStream();
                    ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
                    Log.d(TAG, tag()+"#Receiving object...");
                    Object message = objectInputStream.readObject();
                    if (!(message instanceof FileTransferInfo)) {
                        Log.w(TAG, "DataServerSocketAsyncTask$ get invalid object");
                        serverSocket.close();
                        inputStream.close();
                        objectInputStream.close();
                        continue;
                    }
                    FileTransferInfo fileTransferInfo = (FileTransferInfo)message;
                    String path = getLocalFilePath(fileTransferInfo.filePath)+".temp";

                    RandomAccessFile file = new RandomAccessFile(path, "rw");
                    file.seek(fileTransferInfo.offset);
                    FileOutputStream fileOutputStream = new FileOutputStream(file.getFD());
                    byte[] buf = new byte[512];
                    int len;
                    long total = fileTransferInfo.offset;

                    while ((len = inputStream.read(buf)) != -1) {
                        fileOutputStream.write(buf, 0, len);
                        total += len;
                        progress = (int) ((total * 100) / fileTransferInfo.getFileLength());
                        Log.d(TAG, tag()+"#Receiving data: : " + progress);
                        if (dataProgressListener != null) {
                            dataProgressListener.onProgressChanged(fileTransferInfo, progress);
                        }
                    }
                    if (progress >= 100) {
                        dataProgressListener.onFinished(path);
                    }

                    serverSocket.close();
                    inputStream.close();
                    objectInputStream.close();
                    fileOutputStream.close();
                    serverSocket = null;

                }catch (IOException e){
                    e.printStackTrace();
                }catch (ClassNotFoundException e){
                    e.printStackTrace();
                }
            }//end while
            return "";
        }

        @Override
        protected void onPostExecute(String data) {
            Log.d(TAG,tag()+"#onPostExecute");
            super.onPostExecute(data);
            this.notifyAll();
        }

        @Override
        protected void onCancelled() {
            this.notifyAll();
            Log.w(TAG, tag()+"#Socket Async Task is cancelled!");
        }

        @Override
        protected void onProgressUpdate(Object... values) {
            // TODO
        }
    }// end of class CommandServerSocketAsyncTask

    private static class  DataClientSocketAsyncTask extends AsyncTask<Object, Object, String> {

        @Override
        protected String doInBackground(Object... params) {
            if (params.length < 3){
                String msg = "Invalid parameters, should pass more than 3 parameter!";
                Log.e(TAG, msg);
                return msg;
            }
            try {
                InetAddress inetAddress = (InetAddress) params[0];
                if (inetAddress == null){
                    String errorMsg = "Send data error!! Client address is null!!";
                    Log.e(TAG, errorMsg);
                    return errorMsg;
                }
                int port = 0;
                try {
                    port = Integer.parseInt((String) params[1]);
                }catch (NullPointerException e){
                    e.printStackTrace();
                    port = DATA_PORT;
                }
                Object object = params[2];
                if (!(object instanceof FileTransferInfo)){
                    String errorMsg = DataClientSocketAsyncTask.class.getSimpleName()+
                            "# transfer data failed!!"+
                            "Invalid File Transfer Info";
                    Log.e(TAG, errorMsg);
                    return errorMsg;
                }
                FileTransferInfo fileTransferInfo = (FileTransferInfo)object;
                String path = getLocalFilePath(fileTransferInfo.filePath);
                RandomAccessFile file = new RandomAccessFile(path, "rw");
                if (file.length() <= 0){
                    String errorMsg = DataClientSocketAsyncTask.class.getSimpleName()+
                            "# transfer data failed!!"+
                            "File does not exist!"+
                            path;
                    return errorMsg;
                }
                file.seek(fileTransferInfo.offset);
                Socket socket = new Socket();
                socket.setReuseAddress(true);
                socket.bind(null);

                socket.connect(new InetSocketAddress(inetAddress, port));
                Log.d(TAG, "Connect to "+inetAddress.getHostAddress()+" succedded");
                OutputStream outputStream = socket.getOutputStream();
                new ObjectOutputStream(outputStream).writeObject(fileTransferInfo);
                FileInputStream fileInputStream = new FileInputStream(file.getFD());
                long fileSize = fileTransferInfo.getFileLength();
                long total = fileTransferInfo.offset;
                byte[] buf = new byte[512];
                int len;
                while ((len = fileInputStream.read(buf)) != -1) {
                    outputStream.write(buf, 0, len);
                    total += len;
                    int progress = (int) ((total * 100) / fileSize);
                    publishProgress(progress);
                    Log.e(TAG, "File Sending Progress：" + progress);
                }

                Log.d(TAG, "Send file completed, path="+path);
                socket.close();
                fileInputStream.close();
                outputStream.close();
            }catch (SocketException e){
                e.printStackTrace();
            }catch (IOException e){
                e.printStackTrace();
            }
            return "";
        }

        @Override
        protected void onPostExecute(String data) {
            Log.d(TAG,"onPostExecute");
            super.onPostExecute(data);
            synchronized (this) {
                this.notifyAll();
            }
        }

        @Override
        protected void onCancelled() {
            synchronized (this) {
                this.notifyAll();
            }
            Log.w(TAG, DataClientSocketAsyncTask.class.getSimpleName()+"#Socket Async Task is cancelled!");
        }

        @Override
        protected void onProgressUpdate(Object... values) {
            // TODO
        }
    }// end class DataClientSocketAsyncTask

    private static class  CommandClientSocketAsyncTask extends AsyncTask<Object, Object, String> {

        @Override
        protected String doInBackground(Object... params) {
            if (params.length < 3){
                String msg = "Invalid parameters, should pass more than 3 parameter!";
                Log.e(TAG, msg);
                return msg;
            }
            try {
                InetAddress inetAddress = (InetAddress) params[0];
                if (inetAddress == null) {
                    String errorMsg = "Send command error!! client address is null!!";
                    Log.e(TAG, errorMsg);
                    return errorMsg;
                }
                int port = Integer.parseInt((String)params[1]);
                Object msg = params[2];
                if (!(msg instanceof WifiP2PCommand)) {
                    String errorMsg = "Send command error!! Invalid command";
                    Log.e(TAG, errorMsg);
                    return errorMsg;
                }
                WifiP2PCommand wifiP2PCommand = (WifiP2PCommand)msg;
                Socket socket = new Socket();
                socket.setReuseAddress(true);
                socket.bind(null);

                socket.connect(new InetSocketAddress(inetAddress, port));
                Log.d(TAG, "Connect to "+inetAddress.getHostAddress()+" succedded");
                OutputStream outputStream = socket.getOutputStream();
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
                objectOutputStream.writeObject(wifiP2PCommand);
                switch (wifiP2PCommand.command) {
                    case WifiP2PCommand.COMMAND_TRANSFER_FILE:
                    case WifiP2PCommand.COMMAND_REQUEST_FILE:{
                        if (params.length < 4){
                            String errorMsg = "Send command error!! Request file should pass file info";
                            Log.e(TAG, errorMsg);
                            break;
                        }
                        Object obj = params[3];
                        if (!(obj instanceof FileTransferInfo)){
                            String errorMsg = "Send command error!! Request file failed!! Invalid file info";
                            Log.e(TAG, errorMsg);
                            break;
                        }
                        objectOutputStream.writeObject(obj);
                        Log.d(TAG, "Send command completed");
                        break;
                    }

                }//end switch

                socket.close();
            }catch (SocketException e){
                e.printStackTrace();
            }catch (IOException e){
                e.printStackTrace();
            }
            return "";
        }

        @Override
        protected void onPostExecute(String data) {
            Log.d(TAG,"onPostExecute");
            super.onPostExecute(data);
            synchronized (this) {
                this.notifyAll();
            }
        }

        @Override
        protected void onCancelled() {
            synchronized (this) {
                this.notifyAll();
            }
            Log.w(TAG, DataClientSocketAsyncTask.class.getSimpleName()+"#Socket Async Task is cancelled!");
        }

        @Override
        protected void onProgressUpdate(Object... values) {
            Log.d(TAG, "Send object:"+values[0].toString());
        }
    }// end class CommandClientSocketAsyncTask

    WifiP2pManager wifiP2pManager;
    WifiP2PBroadcastReceiver wifiP2PBroadcastReceiver;
    CommandServerSocketAsyncTask commandServerTask;
    DataServerSocketAsyncTask dataServerTask;
    WifiP2pManager.Channel channel;
    WifiP2PListener wifiP2PListener = new WifiP2PListener() {
        @Override
        public void wifiP2pEnabled(boolean enabled) {
            Log.d(TAG, "wifiP2pEnabled: "+(enabled?"Enable":"Disable"));
        }

        @Override
        public void onPeerAvailable(Collection<WifiP2pDevice> wifiP2pDeviceList) {
            wifiP2PServiceListener.onPeerAvailable(wifiP2pDeviceList);
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "on Disconnected");
            WifiP2PService.this.wifiP2pInfo = null;
            clientAddress = null;
            wifiP2PServiceListener.onDisconnected();
        }

        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("onConnectionInfoAvailable");
            stringBuilder.append("\n");
            stringBuilder.append("Device connected");
            stringBuilder.append("\n");
            stringBuilder.append("Is Group Owner：");
            stringBuilder.append(wifiP2pInfo.isGroupOwner ? "Group Owner" : "Not Group Owner");
            stringBuilder.append("\n");
            stringBuilder.append("Group Owner IP：");
            if (wifiP2pInfo != null && wifiP2pInfo.groupOwnerAddress != null)
                stringBuilder.append(wifiP2pInfo.groupOwnerAddress.getHostAddress());
            Log.d(TAG, stringBuilder.toString());
            WifiP2PService.this.wifiP2pInfo = wifiP2pInfo;
            if (!wifiP2pInfo.isGroupOwner)
                clientAddress = wifiP2pInfo.groupOwnerAddress;
            wifiP2PServiceListener.onConnectionInfoAvailable(wifiP2pInfo);
        }

        @Override
        public void onSelfDeviceAvailable(WifiP2pDevice wifiP2pDevice) {

        }

        @Override
        public void onChannelDisconnected() {

        }
    };// end of wifiP2PListener

    DataProgressListener dataProgressListener = new DataProgressListener() {
        @Override
        public void onProgressChanged(FileTransferInfo fileTransferInfo, int progress) {
            wifiP2PServiceListener.onProgressChanged(getLocalFilePath(fileTransferInfo.filePath),
                    progress);
        }

        @Override
        public void onFinished(String path) {
            if (path.endsWith(".temp")) {
                String newPath = path.replaceAll(".temp", "");
                File file = new File(path);
                file.renameTo(new File(newPath));
                path = newPath;
            }
            wifiP2PServiceListener.onDataTransferFinished(path);
        }
    };//end dataProgressListener
    InetAddress clientAddress = null;
    WifiP2pInfo wifiP2pInfo;
    WifiP2pGroup wifiP2pGroup;
    WifiP2PServiceListener wifiP2PServiceListener = new WifiP2PServiceListener() {
        @Override
        public void onPeerAvailable(Collection<WifiP2pDevice> wifiP2pDeviceList) {

        }

        @Override
        public void onDiscoverSuccess(){

        }

        @Override
        public void onDiscoverFailed(int reason){

        }

        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo){

        }

        @Override
        public void onConnectSuccess(WifiP2pDevice wifiP2pDevice) {

        }

        @Override
        public void onConnectFailed(WifiP2pDevice wifiP2pDevice, int reason) {

        }

        @Override
        public void onProgressChanged(String path , int progress){

        }

        @Override
        public void onDataTransferFinished(String path) {

        }

        @Override
        public void onDisconnected() {

        }
    };//end wifiP2PServiceListener
    WifiP2pDevice wifiP2pDevice = null;

    public WifiP2PService() {
        wifiP2PBroadcastReceiver = new WifiP2PBroadcastReceiver();

    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(wifiP2PBroadcastReceiver, wifiP2PBroadcastReceiver.getIntentFilter() );
        startToListen();
        initWifiP2P();
    }

    public void setListener(WifiP2PServiceListener listener) {
        wifiP2PServiceListener = listener;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new WifiP2PBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        release();
    }

    public void disconnect() {
        removeGroup();
    }

    public void prepareSendFileToServer(String path) {
        String filePath = getLocalFilePath(path);
        File file = new File(filePath);
        if (!file.exists()){
            String errorMsg = "Prepare send file to server failed!!"+ "File does not exist!path="+file.getAbsolutePath();
            Log.e(TAG, errorMsg);
            return;
        }
        FileTransferInfo fileTransferInfo = new FileTransferInfo(file.getAbsolutePath(),
                file.length(), 0);
        fileTransferInfo.md5 = calculateMD5(file);
        WifiP2PCommand wifiP2PCommand = new WifiP2PCommand();
        wifiP2PCommand.command = WifiP2PCommand.COMMAND_TRANSFER_FILE;

        CommandClientSocketAsyncTask commandClientSocketAsyncTask = new CommandClientSocketAsyncTask();
        commandClientSocketAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                clientAddress, Integer.toString(COMMAND_PORT), wifiP2PCommand, fileTransferInfo);

    }

    public void discoveryDevices() {
        wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Discover success!!");
                wifiP2PServiceListener.onDiscoverSuccess();
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Discover failed!! reason="+reason);
                wifiP2PServiceListener.onDiscoverFailed(reason);
            }
        });
    }// end discoveryDevices

    public void connect(WifiP2pDevice wifiP2pDevice){
        this.wifiP2pDevice = wifiP2pDevice;
        _connect(wifiP2pDevice);
        //removeGroup();
    }

    private void _connect(WifiP2pDevice wifiP2pDevice) {
        WifiP2pConfig wifiP2pConfig = new WifiP2pConfig();
        Log.d(TAG, "Connect to ["+wifiP2pDevice.deviceName+"]"+wifiP2pDevice);
        wifiP2pConfig.deviceAddress = wifiP2pDevice.deviceAddress;
        wifiP2pConfig.wps.setup = WpsInfo.PBC;
        wifiP2pManager.connect(channel, wifiP2pConfig, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Connect to ["+wifiP2pDevice.deviceName+"] success!!");
                wifiP2PServiceListener.onConnectSuccess(wifiP2pDevice);
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Connect to ["+wifiP2pDevice.deviceName+"] failed!! reason="+reason);
                wifiP2PServiceListener.onConnectFailed(wifiP2pDevice, reason);
            }
        });
    }// end connect

    private void parseCommand(WifiP2PCommand wifiP2PCommand, ObjectInputStream objectInputStream)
            throws IOException, ClassNotFoundException{
        if (wifiP2PCommand == null)
            return;
        switch (wifiP2PCommand.command) {
            case WifiP2PCommand.COMMAND_REQUEST_FILE:{
                // Handle COMMAND_REQUEST_FILE
                Object message = objectInputStream.readObject();
                if (!(message instanceof FileTransferInfo )){
                    Log.e(TAG, "Invalid message!! Should receive file transfer info after COMMAND_REQUEST_FILE");
                    return;
                }
                sendFile((FileTransferInfo)message);
                break;
            }
            case WifiP2PCommand.COMMAND_TRANSFER_FILE: {
                Object message = objectInputStream.readObject();
                if (!(message instanceof FileTransferInfo )){
                    Log.e(TAG, "Invalid message!! Should receive file transfer info after COMMAND_TRANSFER_FILE");
                    return;
                }
                onTransferFile((FileTransferInfo)message);
                break;
            }
        }
    }// end parseCommand

    private void sendFile(FileTransferInfo fileTransferInfo) {
        if (fileTransferInfo == null){
            Log.e(TAG, "Send file failed!! Invalid file info!!");
            return;
        }
        // Send file
        DataClientSocketAsyncTask dataClientSocketAsyncTask = new DataClientSocketAsyncTask();
        dataClientSocketAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                clientAddress,
                Integer.toString(DATA_PORT),
                fileTransferInfo
        );
    }//end sendFile

    private void onTransferFile(FileTransferInfo fileTransferInfo) {
        if (fileTransferInfo == null){
            Log.e(TAG, "onTransferFile#Error!! fileTransferInfo is null");
            return;
        }
        String path = getLocalFilePath(fileTransferInfo.filePath);
        File file = new File(path);
        File tempFile = new File(path+".temp");
        // Check if file receive before
        if (file.exists()) {
            if (confirmMD5(file, fileTransferInfo.md5)){
                // File already exists
                Log.d(TAG, "onTransferFile# File already exist, do noting, path="+path);
                return;
            }
        }
        else if (tempFile.exists()){
            fileTransferInfo.offset = tempFile.length();
            Log.d(TAG, "onTransferFile# Continue transfer,path="+path+", offset="+fileTransferInfo.offset+"bytes");
        }// end if file exist
        //  Request file
        requestFile(fileTransferInfo);
    }

    protected void requestFile(FileTransferInfo fileTransferInfo) {
        CommandClientSocketAsyncTask commandClientSocketAsyncTask = new CommandClientSocketAsyncTask();
        WifiP2PCommand wifiP2PCommand = new WifiP2PCommand();
        wifiP2PCommand.command = WifiP2PCommand.COMMAND_REQUEST_FILE;
        if (clientAddress == null) {
            Log.w(TAG, "Request file error!! No client");
            return;
        }
        InetAddress receiverAddress = clientAddress;
        commandClientSocketAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                receiverAddress, Integer.toString(COMMAND_PORT), wifiP2PCommand, fileTransferInfo);
    }

    public static String getDeviceStatus(int deviceStatus) {
        switch (deviceStatus) {
            case WifiP2pDevice.AVAILABLE:
                return "Available";
            case WifiP2pDevice.INVITED:
                return "Inviting...";
            case WifiP2pDevice.CONNECTED:
                return "Connected";
            case WifiP2pDevice.FAILED:
                return "Connect Failed";
            case WifiP2pDevice.UNAVAILABLE:
                return "Unavailable";
            default:
                return "Unknown";
        }
    }

    protected static boolean confirmMD5(File file, String md5) {
        String fileMd5 = calculateMD5(file);
        return fileMd5.compareTo(md5) == 0;
    }

    protected static String calculateMD5(File updateFile) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Exception while getting digest", e);
            return null;
        }

        InputStream is;
        try {
            is = new FileInputStream(updateFile);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Exception while getting FileInputStream", e);
            return null;
        }

        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            BigInteger bigInt = new BigInteger(1, md5sum);
            String output = bigInt.toString(16);
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0');
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Unable to process file for MD5", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e(TAG, "Exception on closing MD5 input stream", e);
            }
        }
    }//end calculateMD5

    private static String getExternalFolderPath() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    private static String getLocalFilePath(String fileName){
        File file = new File(fileName);
        String path = getExternalFolderPath();
        path += path.endsWith(File.separator)?"":File.separator;
        path += "Download";
        path += File.separator;

        return path +file.getName();
    }

    private void startToListen() {
        listenCommand();
        listenData();
    }

    private void listenCommand() {
        commandServerTask = new CommandServerSocketAsyncTask();
        commandServerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                Integer.toString(COMMAND_PORT));
        Log.d(TAG, "Start to listen command, port="+COMMAND_PORT);
    }

    private void listenData() {
        dataServerTask = new DataServerSocketAsyncTask();
        dataServerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                Integer.toString(DATA_PORT));
        Log.d(TAG, "Start to listen data, port="+DATA_PORT);
    }

    private void stopListen() {
        stopListenCommand();
        stopListenData();
    }

    private void stopAsyncTask(AsyncTask asyncTask) {
        synchronized (asyncTask) {
            if (asyncTask.isCancelled()) {
                Log.d(TAG, "Stop async task: "+asyncTask.getClass().getSimpleName());
                return;
            }

            asyncTask.cancel(true);
            try {
                synchronized (asyncTask) {
                    asyncTask.wait(1000);
                }

            }catch (InterruptedException e){
                e.printStackTrace();
            }finally {
                Log.d(TAG, "Stop async task: "+asyncTask.getClass().getSimpleName());
            }
        }
    }

    private void stopListenCommand() {
        commandServerTask.isStop  = true;
        stopAsyncTask(commandServerTask);
    }

    private void stopListenData() {
        dataServerTask.isStop = true;
        stopAsyncTask(dataServerTask);
    }

    private void release() {
        removeGroup();
        unregisterReceiver(wifiP2PBroadcastReceiver);
        stopListen();
    }

    private void initWifiP2P() {
        wifiP2pManager = (WifiP2pManager)getSystemService(WIFI_P2P_SERVICE);
        if (wifiP2pManager == null)
        {
            Log.e(TAG, "Cannot get WifiP2pManager!! Kill Service self");
            this.stopSelf();
        }
        channel = wifiP2pManager.initialize(this, getMainLooper(), wifiP2PListener);
        //createGroup();
        removeGroup();
    }// end initWifiP2P

    private void createGroup() {
        wifiP2pManager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                WifiP2PService.this.wifiP2pGroup = group;
                Log.d(TAG, "onGroupInfoAvailable#"+group);
            }
        });

        wifiP2pManager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "createGroup Success#"+WifiP2PService.this.wifiP2pGroup);
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "createGroup failed# reason="+reason);
            }
        });
    }// end createGroup

    private void removeGroup() {
        Log.d(TAG, "removeGroup");
        wifiP2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "removeGroup success!!");

            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "removeGroup failed!! reason="+reason);

            }
        });
    }
}