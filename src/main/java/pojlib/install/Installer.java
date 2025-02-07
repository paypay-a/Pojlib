package pojlib.install;

import android.app.Activity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.apache.commons.io.FileUtils;

import pojlib.APIHandler;
import pojlib.UnityPlayerActivity;
import pojlib.util.download.DownloadManager;
import pojlib.util.download.DownloadUtils;
import pojlib.util.json.MinecraftInstances;
import pojlib.util.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

//This class reads data from a game version json and downloads its contents.
//This works for the base game as well as mod loaders
public class Installer {

    public static void installJVM(Activity activity) {
        File jre = new File(activity.getFilesDir(), "runtimes/JRE");
        File newRelease = new File(activity.getFilesDir(), "runtimes/release");
        File currentRelease = new File(jre, "release");
        String jreURL = "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/latest/download/JRE.zip";
        String jreReleaseInfo = "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/latest/download/release";

        try {
            DownloadUtils.downloadFile(jreReleaseInfo, newRelease, new DownloadManager(1));

            if (!jre.exists() || (jre.exists() && !FileUtil.matchingAssetFile(newRelease, FileUtils.readFileToByteArray(currentRelease)))) {
                if (jre.exists()) {
                    FileUtils.deleteDirectory(jre);
                }
                File jreZip = new File(activity.getFilesDir() + "/runtimes/JRE.zip");
                DownloadUtils.downloadFile(jreURL, jreZip, new DownloadManager(1));
                FileUtil.unzipArchive(jreZip.getPath(), activity.getFilesDir() + "/runtimes/JRE");
                Files.copy(Paths.get(activity.getApplicationInfo().nativeLibraryDir + "/libawt_xawt.so"), Paths.get(activity.getFilesDir() + "/runtimes/JRE/lib/libawt_xawt.so"));
                jreZip.delete();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Will only download client if it is missing, however it will overwrite if sha1 does not match the downloaded client
    // Returns client classpath
    public static String installClient(VersionInfo minecraftVersionInfo, String gameDir) throws IOException {
        Logger.getInstance().appendToLog("Downloading Client");

        File clientFile = new File(gameDir + "/versions/" + minecraftVersionInfo.id + "/" + minecraftVersionInfo.id + ".jar");
        for (int i = 0; i < 5; i++) {
            if (i == 4) throw new RuntimeException("Client download failed after 5 retries");

            if (!clientFile.exists()) DownloadUtils.downloadFile(minecraftVersionInfo.downloads.client.url, clientFile, new DownloadManager(1));
            if (DownloadUtils.compareSHA1(clientFile, minecraftVersionInfo.downloads.client.sha1)) return clientFile.getAbsolutePath();
        }
        return null;
    }

    // Will only download library if it is missing, however it will overwrite if sha1 does not match the downloaded library
    // Returns the classpath of the downloaded libraries
    public static String installLibraries(VersionInfo versionInfo, String gameDir) throws IOException {
        Logger.getInstance().appendToLog("Downloading Libraries for: " + versionInfo.id);
        StringJoiner classpath = new StringJoiner(File.pathSeparator);

        for (VersionInfo.Library library : versionInfo.libraries) {
            if(library.name.contains("lwjgl") || (library.name.contains("org.ow2.asm")) & !versionInfo.id.contains("fabric")) {
                continue;
            }
            for (int i = 0; i < 5; i++) {
                if (i == 4) throw new RuntimeException(String.format("Library download of %s failed after 5 retries", library.name));

                File libraryFile;
                String sha1;

                //Null means mod lib, otherwise vanilla lib
                if (library.downloads == null) {
                    String path = parseLibraryNameToPath(library.name);
                    libraryFile = new File(gameDir + "/libraries/", path);
                    sha1 = APIHandler.getRaw(library.url + path + ".sha1");
                    if (!libraryFile.exists()) {
                        Logger.getInstance().appendToLog("Downloading: " + library.name);
                        DownloadUtils.downloadFile(library.url + path, libraryFile, new DownloadManager(1));
                    }
                } else {
                    VersionInfo.Library.Artifact artifact = library.downloads.artifact;
                    libraryFile = new File(gameDir + "/libraries/", artifact.path);
                    sha1 = artifact.sha1;
                    if (!libraryFile.exists()) {
                        Logger.getInstance().appendToLog("Downloading: " + library.name);
                        DownloadUtils.downloadFile(artifact.url, libraryFile, new DownloadManager(1));
                    }
                }

                if(DownloadUtils.compareSHA1(libraryFile, sha1)) {
                    classpath.add(libraryFile.getAbsolutePath());
                    break;
                }
            }
        }

        // Add our GLFW
        classpath.add(Constants.USER_HOME + "/lwjgl3/lwjgl-glfw-classes.jar");
        // DNS SRV Resolver fix
        classpath.add(Constants.USER_HOME + "/hacks/ResConfHack.jar");

        return classpath.toString();
    }

    //Only works on minecraft, not fabric, quilt, etc...
    //Will only download asset if it is missing
    public static String installAssets(VersionInfo minecraftVersionInfo, String gameDir, Activity activity, MinecraftInstances.Instance instance) throws IOException {
        Logger.getInstance().appendToLog("Downloading assets");
        JsonObject assets = APIHandler.getFullUrl(minecraftVersionInfo.assetIndex.url, JsonObject.class);

        int totalAssets = assets.getAsJsonObject("objects").size();
        DownloadManager downloadManager = new DownloadManager(totalAssets);

        ThreadPoolExecutor tp = new ThreadPoolExecutor(8, 8, 100, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

        for (Map.Entry<String, JsonElement> entry : assets.getAsJsonObject("objects").entrySet()) {
            AsyncDownload thread = new AsyncDownload(entry, gameDir, downloadManager);
            tp.execute(thread);
        }

        tp.shutdown();
        try {
            while (!tp.awaitTermination(100, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {}

        DownloadUtils.downloadFile(minecraftVersionInfo.assetIndex.url, new File(gameDir + "/assets/indexes/" + minecraftVersionInfo.assets + ".json"), downloadManager);

        FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/sodium-options.json"), FileUtil.loadFromAssetToByte(activity, "sodium-options.json"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/smoothboot.json"), FileUtil.loadFromAssetToByte(activity, "smoothboot.json"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/immediatelyfast.json"), FileUtil.loadFromAssetToByte(activity, "immediatelyfast.json"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/moreculling.toml"), FileUtil.loadFromAssetToByte(activity,"moreculling.toml"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/modernfix-mixins.properties"), FileUtil.loadFromAssetToByte(activity,"modernfix-mixins.properties"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/options.txt"), FileUtil.loadFromAssetToByte(activity, "options.txt"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/servers.dat"), FileUtil.loadFromAssetToByte(activity, "servers.dat"));
        FileUtils.writeByteArrayToFile(new File(Constants.USER_HOME + "/hacks/ResConfHack.jar"), FileUtil.loadFromAssetToByte(activity, "hacks/ResConfHack.jar"));
        FileUtils.writeByteArrayToFile(new File(instance.gameDir + "/config/vivecraft-client-config.json"), FileUtil.loadFromAssetToByte(activity, "vivecraft-client-config.json"));

        return new File(gameDir + "/assets").getAbsolutePath();
    }

    public static class AsyncDownload implements Runnable {
        private final Map.Entry<String, JsonElement> entry;
        private final String gameDir;
        private final DownloadManager downloadManager;
        private final String fileName;

        public AsyncDownload(Map.Entry<String, JsonElement> entry, String gameDir, DownloadManager downloadManager) {
            this.entry = entry;
            this.gameDir = gameDir;
            this.downloadManager = downloadManager;
            this.fileName = entry.getKey();
        }

        @Override
        public void run() {
            VersionInfo.Asset asset = new Gson().fromJson(entry.getValue(), VersionInfo.Asset.class);
            String path = asset.hash.substring(0, 2) + "/" + asset.hash;
            File assetFile = new File(gameDir + "/assets/objects/", path);

            for (int i = 0; i < 5; i++) {
                if (i == 4) throw new RuntimeException(String.format("Asset download of %s failed after 5 retries", fileName));

                if (!assetFile.exists()) {
                    Logger.getInstance().appendToLog("Downloading: " + fileName);
                    try {
                        DownloadUtils.downloadFile(Constants.MOJANG_RESOURCES_URL + "/" + path, assetFile, downloadManager);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                if (DownloadUtils.compareSHA1(assetFile, asset.hash)) {
                    downloadManager.fileDownloadComplete(fileName);
                    break;
                } else {
                    assetFile.delete();
                }
            }
        }
    }


    //Used for mod libraries, vanilla is handled a different (tbh better) way
    private static String parseLibraryNameToPath(String libraryName) {
        String[] parts = libraryName.split(":");
        String location = parts[0].replace(".", "/");
        String name = parts[1];
        String version = parts[2];

        return String.format("%s/%s/%s/%s", location, name, version, name + "-" + version + ".jar");
    }
}
