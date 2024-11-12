package pojlib.util.download;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import pojlib.API;

public class DownloadManager {
    private final Map<String, Double> downloadProgress = new ConcurrentHashMap<>();
    private final int totalFiles;
    private final AtomicInteger completedFiles = new AtomicInteger(0);

    public DownloadManager(int totalFiles) {
        this.totalFiles = totalFiles;
    }

    public void updateProgress(String fileName, double progress) {
        downloadProgress.put(fileName, progress);
        API.currentDownload = fileName;
        getOverallProgress();
    }

    public void fileDownloadComplete(String fileName) {
        completedFiles.incrementAndGet();
        downloadProgress.remove(fileName);
        getOverallProgress();
    }

    private void getOverallProgress() {
        int completed = completedFiles.get();
        double overallProgress = ((double) completed / totalFiles) * 100;
        if (completed == totalFiles) {API.currentDownload = "Finished! Ready to start.";}
        API.downloadStatus = overallProgress;
    }
}


