package app.librenews.io.librenews.controllers;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import app.librenews.io.librenews.R;
import app.librenews.io.librenews.models.Flash;

/**
 * Created by miles on 7/14/17.
 */

public class FlashManager {

    int flashesToStoreInDatabase = 100;
    final String flashFileLocation = "flashes.json";
    SharedPreferences prefs;
    String serverUrl;
    String serverName;
    Context context;

    public FlashManager(Context context) {
        this.context = context;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        this.serverUrl = prefs.getString("server_url", "https://librenews.io/api");
        try {
            loadFlashesFromStorage();
        } catch (FileNotFoundException exception) {
            try {
                refresh();
            } catch (Exception exception2) {
                Toast.makeText(context.getApplicationContext(), context.getResources().getString(R.string.internal_storage_setup_fail), Toast.LENGTH_LONG);
                exception2.printStackTrace();
            }
        } catch (Exception exception) {
            Toast.makeText(context.getApplicationContext(), context.getResources().getString(R.string.internal_storage_read_fail), Toast.LENGTH_LONG);
            exception.printStackTrace();
        }

        // first things first: get everything syncing!
        SyncManager syncManager = new SyncManager(context, this);
        syncManager.startSyncService();
    }

    public ArrayList<Flash> loadFlashesFromStorage() throws JSONException, IOException, ParseException {
        FileInputStream inputStream = context.openFileInput(flashFileLocation);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder builder = new StringBuilder();
        ArrayList<Flash> latestPushedFlashes = new ArrayList<>();

        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
            builder.append('\n'); // you'd think Java would have a better way of handling this, but no!
        }
        reader.close();
        inputStream.close();

        // System.out.println(builder);

        if(builder.toString().trim().equals("")){
            throw new FileNotFoundException("No flash storage file exists or is empty.");
        }

        JSONArray jsonArray = new JSONArray(builder.toString());
        for (int i = 0; i < jsonArray.length(); i++) {
            latestPushedFlashes.add(Flash.deserialize((JSONObject) jsonArray.get(i)));
        }
        return latestPushedFlashes;
    }

    public void writeFlashesToStorage(List<Flash> flashes) throws JSONException, IOException {
        FileOutputStream outputStream = context.openFileOutput(flashFileLocation, Context.MODE_PRIVATE);
        String out = convertFlashesToOutputString(flashes);
        outputStream.write(out.getBytes());
        outputStream.close();
    }

    public void sortPushedFlashes(){
        // todo
    }

    private String convertFlashesToOutputString(List<Flash> flashes) throws JSONException {
        Flash[] sorted = flashes.toArray(new Flash[0]);
        Arrays.sort(sorted, new Comparator<Flash>() {
            public int compare(Flash a, Flash b){
                return a.getDate().compareTo(b.getDate());
            }
        });

        int min = 0;
        int max = sorted.length - 1;
        if(max < 0){
            max = 0;
        }
        if (max > flashesToStoreInDatabase) {
            min = max - flashesToStoreInDatabase;
        }
        JSONArray output = new JSONArray();
        for (int i = min; i <= max; i++) {
            output.put(sorted[i].serialize());
        }
        return output.toString(4);
    }

    public ArrayList<Flash> getLatestPushedFlashes() {
        try {
            return loadFlashesFromStorage();
        }catch(Exception e){
            DebugManager.sendDebugNotification("Unable to load flashes from storage: " + e.getLocalizedMessage(), context);
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    public void clearPushedFlashes() throws JSONException, IOException{
        writeFlashesToStorage(new ArrayList<Flash>());
    }

    public String getServerName() {
        return serverName;
    }

    public void pushFlashNotification(Flash flash) throws JSONException, IOException {
        if (!prefs.getBoolean("notifications_enabled", true)) {
            return;
        }
        Intent notificationIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(flash.getLink()));
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(context)
                        .setSmallIcon(R.drawable.ic_alert)
                        .setContentTitle(flash.getChannel() + " • " + flash.getSource())
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(flash.getText()))
                        .setContentText(flash.getText())
                        .setSound(Uri.parse(prefs.getString("notification_sound", "DEFAULT")))
                        .setContentIntent(pendingIntent);

        NotificationManager mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        mNotificationManager.notify(flash.getIdAsInteger(), mBuilder.build());
    }

    public void refresh() {
        String newServerUrl = prefs.getString("server_url", "https://librenews.io/api");
        if (!newServerUrl.equals(serverUrl)) {
            // they changed their server preferences!
            try {
                clearPushedFlashes();
            } catch (IOException exception) {
                Toast.makeText(context.getApplicationContext(), context.getResources().getString(R.string.internal_storage_setup_fail), Toast.LENGTH_LONG);
                exception.printStackTrace();
            } catch (JSONException exception) {
                Toast.makeText(context.getApplicationContext(), context.getResources().getString(R.string.internal_storage_setup_fail), Toast.LENGTH_LONG);
                exception.printStackTrace();
            }
        }
        try {
            FlashRetreiver retreiver = new FlashRetreiver(new URL(serverUrl));
            retreiver.retrieveFlashes(new FlashRetreiver.FlashHandler() {
                @Override
                public void success(Flash[] flashes, String serverName) {
                    for (Flash f : flashes){
                        boolean pushed = false;
                        for (Flash p : getLatestPushedFlashes()) {
                            if (p.getId().equals(f.getId())) {
                                pushed = true;
                            }
                        }
                        try {
                            if (!pushed) {
                                pushFlashNotification(f);
                                ArrayList<Flash> q = getLatestPushedFlashes();
                                q.add(f);
                                writeFlashesToStorage(q); // lots of IO, but it's OK
                            }
                        }catch(Exception exception){
                            exception.printStackTrace();
                            DebugManager.sendDebugNotification("Error occurred while trying push notifications: " + exception.getLocalizedMessage(), context);
                        }
                    }
                }

                @Override
                public void failure(Exception exception) {
                    exception.printStackTrace();
                    DebugManager.sendDebugNotification("An error occurred while trying to receive flashes: " + exception.getLocalizedMessage(), context);
                }
            }, context);
        } catch (MalformedURLException exception) {
            Toast.makeText(context.getApplicationContext(), context.getResources().getString(R.string.invalid_server_url), Toast.LENGTH_LONG);
            exception.printStackTrace();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}
