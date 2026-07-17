package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.Callable;

import net.kdt.pojavlaunch.*;
import org.apache.commons.io.*;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static final String USER_AGENT = Tools.APP_NAME;
    private static final int TIME_OUT = 10000;
    // Timeout de LEITURA maior: downloads grandes (ex.: Pixelmon ~385MB) numa conexao
    // instavel podem ficar >10s sem receber bytes; 60s evita estourar a toa.
    private static final int READ_TIME_OUT = 60000;

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InputStream is = null;
        try {
            // System.out.println("Connecting: " + url.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(READ_TIME_OUT);
            conn.setDoInput(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + conn.getResponseCode()
                        + ": " + conn.getResponseMessage());
            }
            is = conn.getInputStream();
            IOUtils.copy(is, os);
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        downloadFileResumable(url, out);
    }

    /**
     * Baixa um arquivo aguentando conexoes instaveis: retoma de onde parou usando
     * HTTP Range e re-tenta ate MAX_ATTEMPTS. Essencial para arquivos grandes (Pixelmon
     * ~385MB) em dados moveis, onde a conexao pode estagnar varias vezes durante o download.
     */
    private static void downloadFileResumable(String url, File out) throws IOException {
        final int MAX_ATTEMPTS = 8;
        long totalSize = -1;
        IOException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long existing = out.exists() ? out.length() : 0;
            // Ja temos o arquivo completo? (sabendo o tamanho total)
            if (totalSize > 0 && existing >= totalSize) return;

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(TIME_OUT);
                conn.setReadTimeout(READ_TIME_OUT);
                conn.setDoInput(true);
                if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
                conn.connect();

                int code = conn.getResponseCode();
                boolean append;
                if (code == HttpURLConnection.HTTP_PARTIAL) {          // 206: Range aceito
                    append = true;
                    totalSize = parseTotalFromContentRange(conn.getHeaderField("Content-Range"), totalSize);
                } else if (code == HttpURLConnection.HTTP_OK) {        // 200: sem Range, recomeca
                    append = false;
                    existing = 0;
                    int len = conn.getContentLength();
                    if (len > 0) totalSize = len;
                } else if (code == 416) {                             // ja temos tudo
                    return;
                } else {
                    throw new IOException("Servidor retornou HTTP " + code + " em " + url);
                }

                byte[] buffer = new byte[65536];
                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(out, append)) {
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }

                // Terminou o stream: completo se atingiu o tamanho total (ou se e desconhecido).
                if (totalSize < 0 || out.length() >= totalSize) return;
                // Caiu antes do fim sem lancar excecao -> proxima tentativa retoma.
                last = new IOException("Download incompleto (" + out.length() + "/" + totalSize + ")");
            } catch (IOException e) {
                last = e;
                Log.w("DownloadUtils", "Tentativa " + attempt + "/" + MAX_ATTEMPTS
                        + " falhou (" + e.getMessage() + "), retomando...");
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                conn.disconnect();
            }
        }
        throw last != null ? last : new IOException("Falha ao baixar " + url);
    }

    /** Extrai o tamanho total do header Content-Range ("bytes X-Y/TOTAL"). */
    private static long parseTotalFromContentRange(String contentRange, long fallback) {
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && slash < contentRange.length() - 1) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return fallback;
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
        conn.setConnectTimeout(TIME_OUT);
        conn.setReadTimeout(READ_TIME_OUT);
        InputStream readStr = conn.getInputStream();
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            int current;
            int overall = 0;
            int length = conn.getContentLength();

            if (buffer == null) buffer = new byte[65535];

            while ((current = readStr.read(buffer)) != -1) {
                overall += current;
                fos.write(buffer, 0, current);
                monitor.updateProgress(overall, length);
            }
            conn.disconnect();
        } catch (IOException e) {
            throw new IOException("Unable to download from " + urlInput, e);
        }
    }

    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(Tools.DIR_CACHE, "string_cache/"+cacheName);
        if(cacheDestination.isFile() &&
                cacheDestination.canRead() &&
                System.currentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Log.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Log.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Log.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        while (attempts < 5 && !fileOkay){
            attempts++;
            downloadFile(downloadFunction);
            fileOkay = verifyFile(outputFile, sha1);
        }
        if(!fileOkay) throw new SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
        urlConnection.setRequestMethod("HEAD");
        urlConnection.setDoInput(false);
        urlConnection.setDoOutput(false);
        urlConnection.connect();
        int responseCode = urlConnection.getResponseCode();
        if(responseCode >= 200 && responseCode <= 299) return urlConnection.getContentLength();
        return -1;
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}

