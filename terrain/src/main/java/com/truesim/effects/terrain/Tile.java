package com.truesim.effects.terrain;

import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.NodeHandle;

/** One tile the terrain knows: wanted by the world, its mesh held and on the GPU, shown by a node, or an ancestor. */
final class Tile {

    final long key;
    final int level;
    /** Its mesh, a generated asset; made when first wanted. */
    AssetRef<MeshData> mesh;
    /** Whether the world wants it (its cell is attached). */
    boolean wanted;
    /** Whether its mesh is held in the scene resources. */
    boolean held;
    /** Whether its mesh is on the GPU. */
    boolean resident;
    /** The node showing it, or {@link NodeHandle#NONE}. */
    long node = NodeHandle.NONE;
    /** Wanted tiles in its subtree, itself included. */
    int wantedBelow;
    /** Shown tiles in its subtree, itself included. */
    int shownBelow;
    /** Its error against the finer level, metres (NaN until its mesh is built), and its heights' range. */
    double error = Double.NaN;
    double low;
    double high;
    /** Whether the terrain asked the world for its four finer tiles, its own error too large where it is seen. */
    boolean refined;
    /** Whether the display being worked out shows it. */
    boolean show;
    /** Whether the display being worked out shows a tile under it. */
    boolean showUnder;
    /** Its mesh morphing onto level {@link #morphLevel}'s surface (held while it transitions), or null. */
    AssetRef<MeshData> morph;
    int morphLevel = -1;
    boolean morphResident;
    /** Whether its node draws the morphing mesh. */
    boolean morphing;
    /** The transition it takes part in (a member, or a merge's target), or null. */
    Terrain.Transition transition;
    /** The tiles of a split under it, gathered while the display is worked out. */
    java.util.ArrayList<Tile> gathered;

    Tile(long key) {
        this.key = key;
        this.level = TerrainGrid.level(key);
    }

    boolean shown() {
        return node != NodeHandle.NONE;
    }
}
