package io.freemytv.remote;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONException;
import org.json.JSONObject;

/** A Quick Settings tile that mutes (or unmutes) the TV in use, without opening the app. */
public class MuteTile extends TileService {
    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        Tv.load(this);
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_mute));
        t.setLabel(getString(R.string.tile_mute));
        t.setState(Tv.paired() ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.updateTile();
    }

    @Override public void onClick() {
        Tv.load(this);
        if (!Tv.paired()) return;
        try {
            Sync.command("/key", new JSONObject().put("code", 164), null);
        } catch (JSONException ignored) {
        }
    }
}
