package pojlib.util;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;

import com.oracle.dalvik.VMLauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import pojlib.API;
import pojlib.UnityPlayerActivity;
import pojlib.install.Installer;
import pojlib.install.MinecraftMeta;
import pojlib.util.json.MinecraftInstances;

public class JREUtils {
    private JREUtils() {}

    public static String LD_LIBRARY_PATH;
    public static Map<String, String> jreReleaseList;
    public static String instanceHome;
    public static String jvmLibraryPath;
    private static String sNativeLibDir;
    private static String runtimeDir;

    public static String findInLdLibPath(String libName) {
        if(Os.getenv("LD_LIBRARY_PATH")==null) {
            try {
                if (LD_LIBRARY_PATH != null) {
                    Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
                }
            }catch (ErrnoException e) {
                e.printStackTrace();
            }
            return libName;
        }
        for (String libPath : Os.getenv("LD_LIBRARY_PATH").split(":")) {
            File f = new File(libPath, libName);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return libName;
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> returnValue = new ArrayList<>();
        File[] list = path.listFiles();
        if(list != null) {
            for(File f : list) {
                if(f.isFile() && f.getName().endsWith(".so")) {
                    returnValue.add(f);
                }else if(f.isDirectory()) {
                    returnValue.addAll(locateLibs(f));
                }
            }
        }
        return returnValue;
    }

    public static void initJavaRuntime() {
        dlopen(findInLdLibPath("libjli.so"));
        if(!dlopen("libjvm.so")){
            Log.w("DynamicLoader","Failed to load with no path, trying with full path");
            dlopen(jvmLibraryPath+"/libjvm.so");
        }
        dlopen(findInLdLibPath("libverify.so"));
        dlopen(findInLdLibPath("libjava.so"));
        dlopen(findInLdLibPath("libnet.so"));
        dlopen(findInLdLibPath("libnio.so"));
        dlopen(findInLdLibPath("libawt.so"));
        dlopen(findInLdLibPath("libawt_headless.so"));
        dlopen(findInLdLibPath("libfreetype.so"));
        dlopen(findInLdLibPath("libfontmanager.so"));
        for(File f : locateLibs(new File(runtimeDir + "/lib"))) {
            dlopen(f.getAbsolutePath());
        }
    }

    public static void redirectAndPrintJRELog() {
        Log.v("jrelog","Log starts here");
        JREUtils.logToLogger(Logger.getInstance());
        new Thread(new Runnable(){
            int failTime = 0;
            ProcessBuilder logcatPb;
            @Override
            public void run() {
                try {
                    if (logcatPb == null) {
                        logcatPb = new ProcessBuilder().command("logcat", "-v", "brief", "-s", "jrelog:I", "LIBGL:I").redirectErrorStream(true);
                    }
                            Log.i("jrelog-logcat","Clearing logcat");
                    new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start();
                    Log.i("jrelog-logcat","Starting logcat");
                    java.lang.Process p = logcatPb.start();

                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = p.getInputStream().read(buf)) != -1) {
                        String currStr = new String(buf, 0, len);
                        Logger.getInstance().appendToLog(currStr);
                    }
                            if (p.waitFor() != 0) {
                        Log.e("jrelog-logcat", "Logcat exited with code " + p.exitValue());
                        failTime++;
                        Log.i("jrelog-logcat", (failTime <= 10 ? "Restarting logcat" : "Too many restart fails") + " (attempt " + failTime + "/10");
                        if (failTime <= 10) {
                            run();
                        } else {
                            Logger.getInstance().appendToLog("ERROR: Unable to get more log.");
                        }
                            }
                } catch (Throwable e) {
                    Log.e("jrelog-logcat", "Exception on logging thread", e);
                    Logger.getInstance().appendToLog("Exception on logging thread:\n" + Log.getStackTraceString(e));
                }
            }
        }).start();
        Log.i("jrelog-logcat","Logcat thread started");
    }

    public static void relocateLibPath(final Context ctx) {
        sNativeLibDir = ctx.getApplicationInfo().nativeLibraryDir;

        LD_LIBRARY_PATH = ctx.getFilesDir() + "/runtimes/JRE/bin:" + ctx.getFilesDir() + "/runtimes/JRE/lib:" +
                "/system/lib64:/vendor/lib64:/vendor/lib64/hw:" +
                sNativeLibDir;
    }

    public static void setJavaEnvironment(Activity activity, MinecraftInstances.Instance instance) throws Throwable {
        Map<String, String> envMap = new ArrayMap<>();
        envMap.put("POJLIB_NATIVEDIR", activity.getApplicationInfo().nativeLibraryDir);
        envMap.put("JAVA_HOME", activity.getFilesDir() + "/runtimes/JRE");
        envMap.put("HOME", instance.gameDir);
        envMap.put("TMPDIR", activity.getCacheDir().getAbsolutePath());
        envMap.put("VR_MODEL", API.model);
        envMap.put("POJLIB_RENDERER", "LightThinWrapper");

        envMap.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        envMap.put("PATH", activity.getFilesDir() + "/runtimes/JRE/bin:" + Os.getenv("PATH"));

        File customEnvFile = new File(Constants.USER_HOME, "custom_env.txt");
        if (customEnvFile.exists() && customEnvFile.isFile()) {
            BufferedReader reader = new BufferedReader(new FileReader(customEnvFile));
            String line;
            while ((line = reader.readLine()) != null) {
                // Not use split() as only split first one
                int index = line.indexOf("=");
                envMap.put(line.substring(0, index), line.substring(index + 1));
            }
            reader.close();
        }
        envMap.put("LIBGL_ES", "2");
        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.getInstance().appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue());
            Os.setenv(env.getKey(), env.getValue(), true);
        }

        File serverFile = new File(activity.getFilesDir() + "/runtimes/JRE/lib/server/libjvm.so");
        jvmLibraryPath = activity.getFilesDir() + "/runtimes/JRE/lib/" + (serverFile.exists() ? "server" : "client");
        Log.d("DynamicLoader","Base LD_LIBRARY_PATH: "+LD_LIBRARY_PATH);
        Log.d("DynamicLoader","Internal LD_LIBRARY_PATH: "+jvmLibraryPath+":"+LD_LIBRARY_PATH);
        setLdLibraryPath(jvmLibraryPath+":"+LD_LIBRARY_PATH);
    }

    // Called before game launch to ensure all files are present and correct
    public static void prelaunchCheck(Activity activity, MinecraftInstances.Instance instance) throws IOException {

        UnityPlayerActivity.installLWJGL(activity);
        Installer.installJVM(activity);
        Installer.installClient(MinecraftMeta.getVersionInfo(instance.versionName), Constants.USER_HOME);
        Installer.installLibraries(MinecraftMeta.getVersionInfo(instance.versionName), Constants.USER_HOME);
        Installer.installAssets(MinecraftMeta.getVersionInfo(instance.versionName), Constants.USER_HOME, activity, instance);
        MinecraftInstances.CheckVivecraftConfig(instance);
    }

    public static int launchJavaVM(final Activity activity, final List<String> JVMArgs, MinecraftInstances.Instance instance) throws Throwable {
        JREUtils.relocateLibPath(activity);
        setJavaEnvironment(activity, instance);

        final String graphicsLib = loadGraphicsLibrary();
        List<String> userArgs = getJavaArgs(activity, instance);

        //Add automatically generated args

        if (API.customRAMValue) {
            userArgs.add("-Xms" + API.memoryValue + "M");
            userArgs.add("-Xmx" + API.memoryValue + "M");
        } else {
            if (API.model.equals("Meta Quest Pro") || API.model.equals("Oculus Headset1")) {
                userArgs.add("-Xms" + 2048 + "M");
                userArgs.add("-Xmx" + 3072 + "M");
            } else {
                userArgs.add("-Xms" + 1024 + "M");
                userArgs.add("-Xmx" + 2048 + "M");
            }
        }

        // Base JVM Args for MC: https://github.com/brucethemoose/Minecraft-Performance-Flags-Benchmarks?tab=readme-ov-file#base-java-flags
        // NUMA doesn't exist on Android, so it is removed here
        userArgs.addAll(List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+AlwaysActAsServerClassMachine", "-XX:+AlwaysPreTouch", "-XX:+DisableExplicitGC",
                "-XX:NmethodSweepActivity=1", "-XX:ReservedCodeCacheSize=400M", "-XX:NonNMethodCodeHeapSize=12M",
                "-XX:ProfiledCodeHeapSize=194M", "-XX:NonProfiledCodeHeapSize=194M", "-XX:-DontCompileHugeMethods",
                "-XX:MaxNodeLimit=240000", "-XX:NodeLimitFudgeFactor=8000", "-XX:+UseVectorCmov",
                "-XX:+PerfDisableSharedMem", "-XX:+UseFastUnorderedTimeStamps", "-XX:+UseCriticalJavaThreadPriority"));

        // Garbage collection
        userArgs.add("-XX:+UseZGC");
        userArgs.add("-XX:+ZGenerational");
        userArgs.add("-XX:AllocatePrefetchStyle=1");
        userArgs.add("-XX:-ZProactive");

        // Android sig fix
        userArgs.add("-XX:+UseSignalChaining");

        userArgs.add("-Dorg.lwjgl.opengl.libname=" + graphicsLib);
        userArgs.add("-Dorg.lwjgl.opengles.libname=" + "/system/lib64/libGLESv3.so");
        userArgs.add("-Dorg.lwjgl.egl.libname=" + "/system/lib64/libEGL_dri.so");

        userArgs.addAll(JVMArgs);
        System.out.println(JVMArgs);

        runtimeDir = activity.getFilesDir() + "/runtimes/JRE";

        initJavaRuntime();
        chdir(instance.gameDir);
        userArgs.add(0,"java"); //argv[0] is the program name according to C standard.

        int exitCode = VMLauncher.launchJVM(userArgs.toArray(new String[0]));
        Logger.getInstance().appendToLog("Java Exit code: " + exitCode);
        return exitCode;
    }

    /**
     *  Gives an argument list filled with both the user args
     *  and the auto-generated ones (eg. the window resolution).
     * @param ctx The application context
     * @return A list filled with args.
     */
    public static List<String> getJavaArgs(Context ctx, MinecraftInstances.Instance instance) {
        return new ArrayList<>(Arrays.asList(
                "-Djava.home=" + new File(ctx.getFilesDir(), "runtimes/JRE"),
                "-Djava.io.tmpdir=" + ctx.getCacheDir().getAbsolutePath(),
                "-Duser.home=" + instance.gameDir,
                "-Duser.language=" + System.getProperty("user.language"),
                "-Dos.name=Linux",
                "-Dos.version=Android-" + Build.VERSION.RELEASE,
                "-Dorg.lwjgl.librarypath=" + ctx.getApplicationInfo().nativeLibraryDir,
                "-Djna.boot.library.path=" + ctx.getApplicationInfo().nativeLibraryDir,
                "-Djna.nosys=true",
                "-Djava.library.path=" + ctx.getApplicationInfo().nativeLibraryDir,
                "-Dglfwstub.windowWidth=" + 1280,
                "-Dglfwstub.windowHeight=" + 720,
                "-Dglfwstub.initEgl=false",
                "-Dlog4j2.formatMsgNoLookups=true", //Log4j RCE mitigation
                "-Dnet.minecraft.clientmodname=" + "QuestCraft",
                "-Dext.net.resolvPath=" + Constants.USER_HOME + "/hacks/ResConfHack.jar",
                "-Dsodium.checks.issue2561=false",
                "-Dorg.sqlite.lib.path=" + ctx.getApplicationInfo().nativeLibraryDir        ));
    }

    /**
     * Parse and separate java arguments in a user friendly fashion
     * It supports multi line and absence of spaces between arguments
     * The function also supports auto-removal of improper arguments, although it may miss some.
     *
     * @param args The un-parsed argument list.
     * @return Parsed args as an ArrayList
     */
    public static ArrayList<String> parseJavaArguments(String args){
        ArrayList<String> parsedArguments = new ArrayList<>(0);
        args = args.trim().replace(" ", "");
        //For each prefixes, we separate args.
        for(String prefix : new String[]{"-XX:-","-XX:+", "-XX:","--","-"}){
            while (true){
                int start = args.indexOf(prefix);
                if(start == -1) break;
                //Get the end of the current argument
                int end = args.indexOf("-", start + prefix.length());
                if(end == -1) end = args.length();

                //Extract it
                String parsedSubString = args.substring(start, end);
                args = args.replace(parsedSubString, "");

                //Check if two args aren't bundled together by mistake
                if(parsedSubString.indexOf('=') == parsedSubString.lastIndexOf('=')) {
                    int arraySize = parsedArguments.size();
                    if(arraySize > 0){
                        String lastString = parsedArguments.get(arraySize - 1);
                        // Looking for list elements
                        if(lastString.charAt(lastString.length() - 1) == ',' ||
                                parsedSubString.contains(",")){
                            parsedArguments.set(arraySize - 1, lastString + parsedSubString);
                            continue;
                        }
                    }
                    parsedArguments.add(parsedSubString);
                }
                else Log.w("JAVA ARGS PARSER", "Removed improper arguments: " + parsedSubString);
            }
        }
        return parsedArguments;
    }

    /**
     * Open the render library in accordance to the settings.
     * It will fallback if it fails to load the library.
     * @return The name of the loaded library
     */
    public static String loadGraphicsLibrary(){
        return "libltw.so";
    }

    public static native long getEGLContextPtr();
    public static native long getEGLDisplayPtr();
    public static native long getEGLConfigPtr();
    public static native int chdir(String path);
    public static native void logToLogger(final Logger logger);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);

    static {
        System.loadLibrary("pojavexec");
        System.loadLibrary("istdio");
    }
}
