package com.scooter.cockpit;

import android.graphics.drawable.Drawable;
import android.util.Log;

import org.osmdroid.tileprovider.MapTile;
import org.osmdroid.tileprovider.MapTileRequestState;
import org.osmdroid.tileprovider.modules.IArchiveFile;
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;
import org.osmdroid.tileprovider.tilesource.ITileSource;

import java.io.InputStream;

/** Reads tiles straight from an IArchiveFile (no SD-card state check). */
public class OfflineArchiveTileProvider extends MapTileModuleProviderBase {

    private static final String TAG = "CockpitMap";

    private final IArchiveFile archive;
    private final ITileSource tileSource;
    private int okCount = 0;
    private int missCount = 0;

    public OfflineArchiveTileProvider(ITileSource tileSource, IArchiveFile archive) {
        super(2, 40);
        this.tileSource = tileSource;
        this.archive = archive;
    }

    @Override
    public boolean getUsesDataConnection() {
        return false;
    }

    @Override
    public String getName() {
        return "OfflineArchiveProvider";
    }

    @Override
    public String getThreadGroupName() {
        return "offlinearchive";
    }

    @Override
    public TileLoader getTileLoader() {
        return new Loader();
    }

    @Override
    public int getMinimumZoomLevel() {
        return tileSource.getMinimumZoomLevel();
    }

    @Override
    public int getMaximumZoomLevel() {
        return tileSource.getMaximumZoomLevel();
    }

    @Override
    public void setTileSource(ITileSource ignored) {
        // fixed tile source
    }

    private class Loader extends TileLoader {
        @Override
        public Drawable loadTile(MapTileRequestState state) {
            MapTile tile = state.getMapTile();
            InputStream is = null;
            try {
                is = archive.getInputStream(tileSource, tile);
                if (is == null) {
                    missCount++;
                    if (missCount <= 20) {
                        Log.w(TAG, "MISS z=" + tile.getZoomLevel() + " x=" + tile.getX() + " y=" + tile.getY());
                    }
                    return null;
                }
                Drawable d = tileSource.getDrawable(is);
                okCount++;
                if (okCount <= 5) {
                    Log.d(TAG, "OK z=" + tile.getZoomLevel() + " x=" + tile.getX() + " y=" + tile.getY()
                            + " drawable=" + (d != null));
                }
                return d;
            } catch (Throwable t) {
                Log.e(TAG, "loadTile failed: " + t, t);
                return null;
            } finally {
                try { if (is != null) is.close(); } catch (Exception ignored) {}
            }
        }
    }
}