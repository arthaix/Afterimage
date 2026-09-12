package ru.arthaix.keystone.opffix;


/**
 * OnlinePictureFrame download fix. OPF runs several download threads at once, and they share one SimpleDateFormat
 * (DownloadThread.FORMAT) for the Expires / Last-Modified / If-Modified-Since headers, plus one unsynchronized texture
 * cache. SimpleDateFormat is not thread-safe: parallel downloads from hosts that send those headers (imgur) corrupt
 * each other ("For input string: ''"), the cache entry is deleted, and the next attempt reports "Failed to parse GIF".
 * Frames then retry and fail over and over, each time costing the client a hitch. This mod serializes the download
 * and the cache cleanup so every picture loads once and stays cached.
 */
public class OpfFix {
    public static final String MODID = "opffix";
    public static final String VERSION = "1.0.0";
}
