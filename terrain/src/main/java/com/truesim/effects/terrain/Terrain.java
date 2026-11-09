package com.truesim.effects.terrain;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.effects.terrain.data.SurfaceParameters;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.data.TerrainSurface;
import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.MorphWeights;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import com.truesim.scene.world.Cell;
import com.truesim.scene.world.CellContent;
import com.truesim.scene.world.CellLoader;
import com.truesim.scene.world.Detail;
import com.truesim.scene.world.InterestProfile;
import com.truesim.scene.world.InterestSource;
import com.truesim.scene.world.Layer;
import com.truesim.scene.world.Levels;
import com.truesim.scene.world.Priority;
import com.truesim.scene.world.World;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A site's terrain in a scene: tiles of a pyramid ({@link TerrainGrid}) streamed by the scene's multi-level world
 * streaming as fine as a screen-space error allows round the camera, each tile a node whose renderable's mesh is a
 * generated asset ({@link TileMeshes}) the scene resources build, upload and draw, all of one terrain material.
 *
 * <ul>
 *   <li><b>Streaming:</b> a {@link Layer} of {@link Levels#grid} cells registered with the world, wanted by an interest
 *       source following the camera ({@link #follow}) with {@link Detail#bands} at the distances where each level's
 *       measured error ({@link TerrainError}) projects to {@code terrain.screenError} pixels: the world requests and
 *       releases cells by distance and view, coarse first.</li>
 *   <li><b>No holes, no overlaps:</b> a cell the world attaches is wanted; its mesh is held in the scene resources
 *       ({@code terrain.buildBudget} a frame) and the tile shows once it is on the GPU. Each frame something changed,
 *       the shown tiles are worked out from the coarsest down: a wanted tile on the GPU shows; else its finer tiles
 *       show if together they cover it; else what showed there before stays (a coarser tile, or finer ones) until its
 *       replacement can show whole. A tile no wanted tile overlaps any more goes.</li>
 *   <li><b>Crack-free:</b> tiles of neighbouring levels meet through their skirts ({@link TileMeshes}).</li>
 *   <li><b>On the CPU:</b> {@link #heightAt}, {@link #normalAt}, {@link #layers} and {@link #vegetation} read the
 *       surface the finest tiles draw.</li>
 * </ul>
 *
 * <pre>{@code
 * Terrain terrain = new Terrain(world, resources, site, material, settings);
 * // each frame, after tasks.drainCompleted():
 * terrain.follow(camera.x, camera.y, camera.z, fovY, viewportHeight);
 * world.update();
 * terrain.update();
 * scene.update();
 * sync.update();
 * }</pre>
 */
public final class Terrain implements AutoCloseable {

    private final World world;
    private final SceneGraph scene;
    private final SceneResources resources;
    private final EngineSettings settings;
    private final TerrainGrid grid;
    private final TerrainSurface surface;
    private final TileMeshes meshes;
    private final Layer<Long> layer;
    private final List<AssetRef<MaterialData>> materials;
    private List<List<AssetRef<MaterialData>>> levelMaterials;
    private final ComponentStore<Renderable> renderables;
    private final long root;
    private final TileMap tiles = new TileMap();
    private final ArrayDeque<Tile> toHold = new ArrayDeque<>();
    private final ArrayList<Tile> holding = new ArrayList<>();
    private final ArrayList<Tile> forgettable = new ArrayList<>();
    private final ArrayList<Tile> roots = new ArrayList<>();
    private final ArrayList<Tile> display = new ArrayList<>();
    private final ArrayList<Tile> active = new ArrayList<>();
    private final ArrayList<Tile> morphHolding = new ArrayList<>();
    private final ArrayList<Transition> transitions = new ArrayList<>();
    private final ArrayList<Tile> anchors = new ArrayList<>();
    private final ArrayList<Tile> merges = new ArrayList<>();
    private final ArrayList<Tile> scratch = new ArrayList<>();
    private final double[] weight = new double[1];
    private final double[] slopeNormal = new double[3];
    private final ComponentStore<MorphWeights> morphWeights;
    private long lastUpdate = -1;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private final double[] errors;
    private final double[] bands;
    private InterestSource source;
    private float sourceError;
    private float sourceScale;
    private float sourceRange;
    private double sourceProjection;
    private long residency = -1;
    private boolean dirty;
    private boolean closed;
    private int shownCount;
    private TerrainParameters rules = TerrainParameters.DEFAULTS;

    /**
     * The terrain of {@code site} in {@code world}'s scene, drawn with {@code material} through {@code resources}, as
     * {@code settings} say (its tiles, levels and detail read now; the streaming's error, range and budget every
     * frame).
     */
    @AllowAllocation("made once")
    public Terrain(World world, SceneResources resources, TerrainSite site, AssetRef<MaterialData> material,
        EngineSettings settings) {
        this(world, resources, site, material, settings, com.truesim.effects.terrain.data.TerrainModifiers.NONE);
    }

    /**
     * The terrain of {@code site} changed by {@code modifiers} (river beds, roads' cuts and fills, levelled squares and
     * the surface materials they lay: {@link com.truesim.effects.terrain.data.TerrainModifier}), which its tiles and
     * every CPU query follow. Work modifiers out from the unmodified surface ({@code surface().unmodified()}, or one
     * made from the site and {@code TerrainSettings.surface(settings)}).
     */
    @AllowAllocation("made once")
    public Terrain(World world, SceneResources resources, TerrainSite site, AssetRef<MaterialData> material,
        EngineSettings settings, com.truesim.effects.terrain.data.TerrainModifiers modifiers) {
        this.world = Objects.requireNonNull(world, "world");
        this.scene = world.scene();
        this.resources = Objects.requireNonNull(resources, "resources");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.materials = List.of(Objects.requireNonNull(material, "material"));
        grid = new TerrainGrid(settings.get(TerrainSettings.TILE_SIZE), settings.get(TerrainSettings.TILE_QUADS),
            settings.get(TerrainSettings.LEVELS));
        SurfaceParameters detail = TerrainSettings.surface(settings);
        surface = new TerrainSurface(site, detail, modifiers);
        meshes = new TileMeshes(surface, grid, settings.get(TerrainSettings.SKIRT_MARGIN),
            settings.get(TerrainSettings.CURVATURE_SCALE)).skirts(settings.get(TerrainSettings.SKIRTS))
            .layerScale(settings.get(TerrainSettings.LAYER_SCALE));
        layer = Layer.of("terrain " + site.id(), Long.class, Levels.grid(grid.tileSize(), grid.levels()));
        errors = TerrainError.measure(surface, grid, settings.get(TerrainSettings.RANGE),
            settings.get(TerrainSettings.ERROR_SAMPLES));
        bands = new double[grid.levels()];
        renderables = scene.components().store(Renderable.TYPE);
        morphWeights = scene.components().store(MorphWeights.TYPE);
        root = scene.create();
        scene.setMobility(root, Mobility.STATIC);
        world.register(layer, new Loader());
    }

    /** The tiles' grid. */
    public TerrainGrid grid() {
        return grid;
    }

    /** The surface the tiles are built from and the CPU queries read. */
    public TerrainSurface surface() {
        return surface;
    }

    /** The tiles' meshes, as generated assets. */
    public TileMeshes meshes() {
        return meshes;
    }

    /** The world layer the tiles stream in. */
    public Layer<Long> layer() {
        return layer;
    }

    /** The node every tile's node is under. */
    public long root() {
        return root;
    }

    /** The interest source following the camera (null before the first {@link #follow}). */
    public InterestSource source() {
        return source;
    }

    /** The material rules {@link #layers} and {@link #vegetation} follow (the material's, set by its maker). */
    public Terrain rules(TerrainParameters parameters) {
        this.rules = Objects.requireNonNull(parameters, "parameters");
        return this;
    }

    /**
     * Draws each level's tiles with a material of its own, {@code perLevel.get(level)} (the last for the levels past
     * it), from the tiles shown next: a debug view of the levels. Null: the terrain's material again.
     */
    @AllowAllocation("a debug switch")
    public Terrain levelMaterials(List<AssetRef<MaterialData>> perLevel) {
        if (perLevel == null) {
            levelMaterials = null;
        } else {
            List<List<AssetRef<MaterialData>>> lists = new ArrayList<>();
            for (AssetRef<MaterialData> material : perLevel) {
                lists.add(List.of(material));
            }
            levelMaterials = lists;
        }
        return this;
    }

    /** The mesh asset of tile {@code key}. */
    @AllowAllocation("a reference made as a tile is first wanted")
    public AssetRef<com.truesim.scene.assets.mesh.MeshData> mesh(long key) {
        return AssetRef.generated(RenderAssets.MESH, meshes, new TileMeshes.TileMesh(key, -1), 0);
    }

    /** The mesh asset of tile {@code key} with a morph target onto level {@code level}'s surface. */
    @AllowAllocation("a reference made as a tile transitions")
    public AssetRef<com.truesim.scene.assets.mesh.MeshData> morphMesh(long key, int level) {
        return AssetRef.generated(RenderAssets.MESH, meshes, new TileMeshes.TileMesh(key, level), 0);
    }

    /**
     * Moves the terrain's interest to the camera at ({@code x}, {@code y}, {@code z}) of the local frame, seeing
     * {@code fovY} radians over {@code viewportHeight} pixels; before the world's update.
     */
    public void follow(double x, double y, double z, double fovY, double viewportHeight) {
        float error = settings.get(TerrainSettings.SCREEN_ERROR);
        float scale = settings.get(TerrainSettings.ERROR_SCALE);
        float range = settings.get(TerrainSettings.RANGE);
        double projection = viewportHeight / (2 * Maths.tan(fovY / 2));
        if (source == null || error != sourceError || scale != sourceScale || range != sourceRange
            || projection != sourceProjection) {
            newSource(error, scale, range, projection);
        }
        source.setView(fovY, viewportHeight);
        double step = settings.get(TerrainSettings.FOLLOW_STEP);
        double dx = x - source.x();
        double dy = y - source.y();
        double dz = z - source.z();
        if (dx * dx + dy * dy + dz * dz >= step * step) {
            source.moveTo(x, y, z); // the world selects anew only when the camera has moved a step
        }
        cameraX = x;
        cameraY = y;
        cameraZ = z;
    }

    /**
     * The distance within which tiles of {@code level} replace those of the level above, metres: where the level
     * above's measured error, seen from there, is the allowed screen-space error ({@link #levelError}).
     */
    public double band(int level) {
        return bands[level];
    }

    /** The measured error of {@code level}'s tiles against the finer level's surface, metres ({@link TerrainError}). */
    public double levelError(int level) {
        return errors[level];
    }

    /**
     * A profile of bands from the measured errors: level i within the distance at which level i + 1's error projects to
     * the allowed pixels (the world's screen-space error takes one error per metre of size for every level, while a
     * terrain's coarse tiles stand further off their finer ones for their size than its fine tiles do), the coarsest
     * out to the range.
     */
    @AllowAllocation("a profile made as its settings or the view change")
    private void newSource(float error, float scale, float range, double projection) {
        InterestSource old = source;
        int levels = grid.levels();
        for (int level = 0; level < levels - 1; level++) {
            double band = errors[level + 1] * scale * projection / error;
            double floor = level == 0 ? 1 : bands[level - 1] * 1.0001 + 1;
            bands[level] = Math.min(Math.max(band, floor), range);
        }
        bands[levels - 1] = Math.max(range, bands[Math.max(0, levels - 2)] * 1.0001 + 1);
        for (int level = levels - 2; level >= 0; level--) {
            bands[level] = Math.min(bands[level], bands[level + 1] / 1.0001 - 1);
        }
        source = world.addInterest(InterestProfile.builder().layer(layer, Detail.bands(bands)).build(), Priority.HIGH);
        sourceError = error;
        sourceScale = scale;
        sourceRange = range;
        sourceProjection = projection;
        if (old != null) {
            old.close();
        }
    }

    /**
     * Takes the frame's tiles in: holds the meshes of newly wanted tiles (within the budget), notes those that reached
     * the GPU, and where anything changed works out the shown tiles and makes or removes their nodes; after the world's
     * update, before the scene's and the sync's.
     */
    public void update() {
        long now = System.nanoTime();
        double seconds = lastUpdate < 0 ? 0 : Math.min(0.1, (now - lastUpdate) / 1e9);
        lastUpdate = now;
        update(seconds);
    }

    /**
     * {@link #update()} with {@code seconds} gone since the last: what level transitions move on by (for frames drawn
     * at a pace of their own, as films are).
     */
    public void update(double seconds) {
        if (closed) {
            throw new IllegalStateException("Closed");
        }
        int budget = settings.get(TerrainSettings.BUILD_BUDGET);
        boolean newHolds = false;
        while (budget > 0 && !toHold.isEmpty()) {
            Tile tile = toHold.poll();
            if (!tile.wanted || tile.held) {
                continue;
            }
            hold(tile);
            newHolds = true;
            budget--;
        }
        long resident = resources.residency();
        if (resident != residency || newHolds) {
            residency = resident;
            for (int i = morphHolding.size() - 1; i >= 0; i--) {
                Tile tile = morphHolding.get(i);
                if (resources.mesh(tile.morph) >= 0) {
                    tile.morphResident = true;
                    morphHolding.set(i, morphHolding.getLast());
                    morphHolding.removeLast();
                    dirty = true;
                }
            }
            for (int i = holding.size() - 1; i >= 0; i--) {
                Tile tile = holding.get(i);
                if (resources.mesh(tile.mesh) >= 0) {
                    tile.resident = true;
                    learn(tile);
                    holding.set(i, holding.getLast());
                    holding.removeLast();
                    dirty = true;
                }
            }
        }
        refine();
        advance(seconds);
        if (dirty) {
            dirty = false;
            arrange();
        }
    }

    /** Notes what the tile's build found: its error and height range. */
    @AllowAllocation("as a tile reaches the GPU")
    private void learn(Tile tile) {
        TileMeshes.Info info = meshes.info(tile.key);
        if (info != null) {
            tile.error = info.error();
            tile.low = info.low();
            tile.high = info.high();
        }
    }

    /**
     * Asks the world for the finer tiles of every wanted tile whose own error, seen from the camera at its nearest
     * point, is more than the allowed pixels (rough tiles the levels' typical errors underrate: cliffs), and lets go of
     * them once it is less than that by the world's hysteresis.
     */
    private void refine() {
        if (source == null) {
            return;
        }
        double allowed = sourceError / (sourceScale * sourceProjection);
        for (int i = 0; i < active.size(); i++) {
            Tile tile = active.get(i);
            if (tile.level == 0 || Double.isNaN(tile.error)) {
                continue;
            }
            double size = grid.size(tile.level);
            double x0 = TerrainGrid.x(tile.key) * size;
            double z0 = TerrainGrid.z(tile.key) * size;
            double dx = Math.max(0, Math.max(x0 - cameraX, cameraX - x0 - size));
            double dy = Math.max(0, Math.max(tile.low - cameraY, cameraY - tile.high));
            double dz = Math.max(0, Math.max(z0 - cameraZ, cameraZ - z0 - size));
            double distance = Math.max(1, Math.sqrt(dx * dx + dy * dy + dz * dz));
            double seen = tile.error / distance; // pixels, over the projection's pixels per radian
            if (!tile.refined && seen > allowed) {
                split(tile, true);
            } else if (tile.refined && seen * HYSTERESIS < allowed) {
                split(tile, false);
            }
        }
    }

    /** A refined tile is let go of once its error is this much under the allowed, so it does not flicker. */
    private static final double HYSTERESIS = 1.15;

    /** Asks the world for, or lets go of, {@code tile}'s four finer tiles. */
    private void split(Tile tile, boolean on) {
        for (int c = 0; c < 4; c++) {
            long child = TerrainGrid.child(tile.key, c);
            if (on) {
                world.request(layer, tile.level - 1, TerrainGrid.x(child), 0, TerrainGrid.z(child), Priority.HIGH);
            } else {
                world.release(layer, tile.level - 1, TerrainGrid.x(child), 0, TerrainGrid.z(child));
            }
        }
        tile.refined = on;
        dirty = true;
    }

    @AllowAllocation("a tile's mesh held anew")
    private void hold(Tile tile) {
        if (tile.mesh == null) {
            tile.mesh = mesh(tile.key);
        }
        resources.hold(tile.mesh);
        tile.held = true;
        holding.add(tile);
    }

    /**
     * A level transition: a split (a tile's finer tiles shown morphed onto its surface, then unmorphing) or a merge (a
     * tile's finer tiles morphing onto its surface, then it shown in their place). Its members' morph weight runs over
     * {@code terrain.morphSeconds}, eased, so no switch moves the surface within a frame.
     */
    static final class Transition {
        final boolean merge;
        final Tile anchor;
        final ArrayList<Tile> members;
        double progress;

        Transition(boolean merge, Tile anchor, ArrayList<Tile> members) {
            this.merge = merge;
            this.anchor = anchor;
            this.members = members;
        }
    }

    /** How many level transitions are under way. */
    public int transitioning() {
        return transitions.size();
    }

    /** Moves the transitions on by {@code seconds}: their members' morph weights; those done finish. */
    private void advance(double seconds) {
        if (transitions.isEmpty()) {
            return;
        }
        double duration = settings.get(TerrainSettings.MORPH_SECONDS);
        for (int i = transitions.size() - 1; i >= 0; i--) {
            Transition transition = transitions.get(i);
            transition.progress = duration <= 0 ? 1 : Math.min(1, transition.progress + seconds / duration);
            double t = transition.progress;
            double eased = t * t * (3 - 2 * t);
            double w = transition.merge ? eased : 1 - eased;
            for (int m = 0; m < transition.members.size(); m++) {
                setWeight(transition.members.get(m), w);
            }
            if (t >= 1) {
                finish(transition);
                transitions.set(i, transitions.getLast());
                transitions.removeLast();
                dirty = true;
            }
        }
    }

    private void finish(Transition transition) {
        ArrayList<Tile> members = transition.members;
        if (transition.merge) {
            Tile anchor = transition.anchor;
            anchor.transition = null;
            showNode(anchor, false, 0);
            for (int m = 0; m < members.size(); m++) {
                Tile member = members.get(m);
                member.transition = null;
                removeNode(member);
                releaseMorph(member);
            }
        } else {
            for (int m = 0; m < members.size(); m++) {
                Tile member = members.get(m);
                member.transition = null;
                swap(member, false, 0);
                releaseMorph(member);
            }
        }
    }

    /**
     * Works out the shown tiles and changes the nodes to match: tiles replacing a shown coarser one start a split
     * (once each has its morphing mesh on the GPU), a tile replacing shown finer ones a merge (likewise), a tile over
     * nothing shown shows at once, and a shown tile nothing replaces whose area is no longer wanted goes. What a
     * transition holds waits for it to finish.
     */
    @AllowAllocation("tiles changing")
    private void arrange() {
        display.clear();
        for (int i = 0; i < roots.size(); i++) {
            cover(roots.get(i), false);
        }
        for (int i = 0; i < display.size(); i++) {
            Tile tile = display.get(i);
            tile.show = true;
            for (Tile up = parentOf(tile); up != null && !up.showUnder; up = parentOf(up)) {
                up.showUnder = true;
            }
        }
        anchors.clear();
        merges.clear();
        for (int i = 0; i < display.size(); i++) {
            Tile tile = display.get(i);
            if (tile.shown() || tile.transition != null) {
                continue;
            }
            Tile ancestor = shownAncestor(tile);
            if (ancestor != null) {
                if (ancestor.transition == null) {
                    if (ancestor.gathered == null) {
                        ancestor.gathered = new ArrayList<>();
                    }
                    if (ancestor.gathered.isEmpty()) {
                        anchors.add(ancestor);
                    }
                    ancestor.gathered.add(tile);
                }
            } else if (tile.shownBelow > 0) {
                merges.add(tile);
            } else {
                showNode(tile, false, 0);
            }
        }
        for (int i = 0; i < anchors.size(); i++) {
            Tile anchor = anchors.get(i);
            ArrayList<Tile> gathered = anchor.gathered;
            boolean ready = true;
            for (int g = 0; g < gathered.size(); g++) {
                ready &= morphReady(gathered.get(g), anchor.level);
            }
            if (ready) {
                Transition transition = new Transition(false, anchor, new ArrayList<>(gathered));
                for (int g = 0; g < gathered.size(); g++) {
                    Tile tile = gathered.get(g);
                    showNode(tile, true, 1);
                    tile.transition = transition;
                }
                removeNode(anchor);
                transitions.add(transition);
            }
            gathered.clear();
        }
        for (int i = 0; i < merges.size(); i++) {
            Tile target = merges.get(i);
            scratch.clear();
            shownUnder(target, scratch);
            boolean ready = !scratch.isEmpty();
            for (int d = 0; d < scratch.size(); d++) {
                Tile below = scratch.get(d);
                ready &= below.transition == null && morphReady(below, target.level);
            }
            if (ready) {
                Transition transition = new Transition(true, target, new ArrayList<>(scratch));
                target.transition = transition;
                for (int d = 0; d < scratch.size(); d++) {
                    Tile below = scratch.get(d);
                    swap(below, true, 0);
                    below.transition = transition;
                }
                transitions.add(transition);
            }
        }
        for (int slot = 0; slot < tiles.capacity(); slot++) {
            Tile tile = tiles.at(slot);
            if (tile == null) {
                continue;
            }
            if (tile.shown() && !tile.show && tile.transition == null && !tile.showUnder
                && showingAncestor(tile) == null) {
                removeNode(tile); // its area is no longer wanted
            }
        }
        for (int slot = 0; slot < tiles.capacity(); slot++) {
            Tile tile = tiles.at(slot);
            if (tile != null) {
                tile.show = false;
                tile.showUnder = false;
            }
        }
        forgetUnused();
    }

    private Tile parentOf(Tile tile) {
        return tile.level == grid.levels() - 1 ? null : tiles.get(TerrainGrid.parent(tile.key));
    }

    private Tile shownAncestor(Tile tile) {
        for (Tile up = parentOf(tile); up != null; up = parentOf(up)) {
            if (up.shown()) {
                return up;
            }
        }
        return null;
    }

    /** An ancestor that is shown, about to be, or a merge's target, or null. */
    private Tile showingAncestor(Tile tile) {
        for (Tile up = parentOf(tile); up != null; up = parentOf(up)) {
            if (up.shown() || up.show || up.transition != null) {
                return up;
            }
        }
        return null;
    }

    /** Adds the shown tiles under {@code tile} to {@code into}. */
    private void shownUnder(Tile tile, ArrayList<Tile> into) {
        if (tile.level == 0) {
            return;
        }
        for (int c = 0; c < 4; c++) {
            Tile child = tiles.get(TerrainGrid.child(tile.key, c));
            if (child == null || child.shownBelow == 0) {
                continue;
            }
            if (child.shown()) {
                into.add(child);
            } else {
                shownUnder(child, into);
            }
        }
    }

    /** Whether {@code tile}'s mesh morphing onto {@code level}'s surface is on the GPU; asks for it if not. */
    @AllowAllocation("a tile's morphing mesh asked for")
    private boolean morphReady(Tile tile, int level) {
        if (tile.morph != null && tile.morphLevel != level) {
            if (tile.morphing) {
                return false; // drawn: it changes once its transition is done
            }
            releaseMorph(tile);
        }
        if (tile.morph == null) {
            tile.morph = morphMesh(tile.key, level);
            tile.morphLevel = level;
            tile.morphResident = false;
            resources.hold(tile.morph);
            morphHolding.add(tile);
        }
        return tile.morphResident;
    }

    private void releaseMorph(Tile tile) {
        if (tile.morph != null) {
            resources.release(tile.morph);
            morphHolding.remove(tile);
            tile.morph = null;
            tile.morphLevel = -1;
            tile.morphResident = false;
        }
    }

    private void setWeight(Tile tile, double w) {
        if (tile.morphing) {
            weight[0] = w;
            MorphWeights.write(scene, tile.node, weight, 0, 1);
        }
    }

    /** Draws {@code tile}'s node with its morphing mesh at weight {@code w}, or its own mesh. */
    private void swap(Tile tile, boolean morphing, double w) {
        Renderable.setMesh(scene, tile.node, morphing ? tile.morph : tile.mesh);
        tile.morphing = morphing;
        if (morphing) {
            setWeight(tile, w);
        } else {
            morphWeights.remove(tile.node);
        }
    }

    /**
     * Adds to the display what shows {@code tile}'s area; true if it covers all of it. {@code underWanted}: an
     * ancestor is wanted, so the area is still the terrain's even where nothing below is wanted.
     */
    private boolean cover(Tile tile, boolean underWanted) {
        if (!underWanted && tile.wantedBelow == 0) {
            return false; // nothing wants this area: whatever shows here goes
        }
        if (tile.wanted && tile.resident && !tile.refined) {
            display.add(tile);
            return true;
        }
        int below = tile.wantedBelow - (tile.wanted ? 1 : 0) + tile.shownBelow - (tile.shown() ? 1 : 0);
        if (below > 0 && tile.level > 0) {
            int mark = display.size();
            boolean all = true;
            for (int c = 0; c < 4; c++) {
                Tile child = tiles.get(TerrainGrid.child(tile.key, c));
                if (child == null || !cover(child, underWanted || tile.wanted)) {
                    all = false;
                }
            }
            if (all) {
                return true;
            }
            if (tile.wanted && tile.resident || tile.shown()) {
                truncate(mark);
                display.add(tile);
                return true;
            }
            return false;
        }
        if (tile.wanted && tile.resident || tile.shown()) {
            display.add(tile);
            return true;
        }
        return false;
    }

    private void truncate(int size) {
        while (display.size() > size) {
            display.removeLast();
        }
    }

    @AllowAllocation("a tile's node made as it shows")
    private void showNode(Tile tile, boolean morphing, double w) {
        double size = grid.size(tile.level);
        long node = scene.create(root);
        scene.setTranslation(node, TerrainGrid.x(tile.key) * size, 0, TerrainGrid.z(tile.key) * size);
        scene.setMobility(node, Mobility.STATIC);
        renderables.set(node, Renderable.of(morphing ? tile.morph : tile.mesh, levelMaterials == null ? materials
            : levelMaterials.get(Math.min(tile.level, levelMaterials.size() - 1))));
        tile.node = node;
        tile.morphing = morphing;
        if (morphing) {
            setWeight(tile, w);
        }
        count(tile, 0, 1);
        shownCount++;
    }

    private void removeNode(Tile tile) {
        scene.remove(tile.node);
        tile.node = NodeHandle.NONE;
        tile.morphing = false;
        count(tile, 0, -1);
        shownCount--;
        forgettable.add(tile);
    }

    /** Lets go of tiles neither wanted nor shown, nor holding any that are. */
    private void forgetUnused() {
        for (int i = 0; i < forgettable.size(); i++) {
            Tile tile = forgettable.get(i);
            if (!tile.wanted && !tile.shown() && tile.transition == null) {
                releaseMorph(tile);
                if (tile.held) {
                    resources.release(tile.mesh);
                    tile.held = false;
                    tile.resident = false;
                    holding.remove(tile);
                }
                if (tile.wantedBelow == 0 && tile.shownBelow == 0) {
                    drop(tile);
                }
            }
        }
        forgettable.clear();
    }

    /** Removes {@code tile} and the ancestors only it kept from the map. */
    private void drop(Tile tile) {
        for (Tile t = tile; t != null; ) {
            if (t.wantedBelow != 0 || t.shownBelow != 0 || t.held || t.shown()) {
                return;
            }
            tiles.remove(t.key);
            if (t.level == grid.levels() - 1) {
                roots.remove(t);
                return;
            }
            t = tiles.get(TerrainGrid.parent(t.key));
        }
    }

    /** Counts {@code wanted} and {@code shown} changes of {@code tile} up its ancestors, making them as needed. */
    @AllowAllocation("ancestors made as tiles come")
    private void count(Tile tile, int wanted, int shown) {
        for (Tile t = tile; ; ) {
            t.wantedBelow += wanted;
            t.shownBelow += shown;
            if (t.level == grid.levels() - 1) {
                return;
            }
            long parent = TerrainGrid.parent(t.key);
            Tile up = tiles.get(parent);
            if (up == null) {
                up = newTile(parent);
            }
            t = up;
        }
    }

    @AllowAllocation("a tile met for the first time")
    private Tile newTile(long key) {
        Tile tile = new Tile(key);
        tiles.put(key, tile);
        if (tile.level == grid.levels() - 1) {
            roots.add(tile);
        }
        return tile;
    }

    /** A cell the world attached: its tile is wanted. */
    @AllowAllocation("streaming events")
    void wanted(long key) {
        Tile tile = tiles.get(key);
        if (tile == null) {
            tile = newTile(key);
        }
        if (!tile.wanted) {
            tile.wanted = true;
            active.add(tile);
            count(tile, 1, 0);
            toHold.add(tile);
            dirty = true;
        }
    }

    /** A cell the world detached: its tile is no longer wanted. */
    @AllowAllocation("streaming events")
    void unwanted(long key) {
        Tile tile = tiles.get(key);
        if (tile != null && tile.wanted) {
            tile.wanted = false;
            active.remove(tile);
            if (tile.refined) {
                split(tile, false);
            }
            count(tile, -1, 0);
            forgettable.add(tile);
            dirty = true;
        }
    }

    /** How many tiles show now. */
    public int shownTiles() {
        return shownCount;
    }

    /** How many tiles the world wants now (their cells attached). */
    public int wantedTiles() {
        int wanted = 0;
        for (Tile r : roots) {
            wanted += r.wantedBelow;
        }
        return wanted;
    }

    /** How many wanted tiles are not on the GPU yet (their meshes building or uploading). */
    public int pendingTiles() {
        int pending = 0;
        for (int i = 0; i < active.size(); i++) {
            pending += active.get(i).resident ? 0 : 1;
        }
        return pending;
    }

    /** How many tiles' meshes are held, on the GPU or on their way. */
    public int heldTiles() {
        int held = 0;
        for (int slot = 0; slot < tiles.capacity(); slot++) {
            Tile tile = tiles.at(slot);
            if (tile != null && tile.held) {
                held++;
            }
        }
        return held;
    }

    /** How many shown tiles there are at {@code level}. */
    public int shownTiles(int level) {
        int count = 0;
        for (int slot = 0; slot < tiles.capacity(); slot++) {
            Tile tile = tiles.at(slot);
            if (tile != null && tile.shown() && tile.level == level) {
                count++;
            }
        }
        return count;
    }

    /** Whether the tile {@code key} shows. */
    public boolean shows(long key) {
        Tile tile = tiles.get(key);
        return tile != null && tile.shown();
    }

    /** The node showing tile {@code key}, or {@link NodeHandle#NONE}. */
    public long node(long key) {
        Tile tile = tiles.get(key);
        return tile == null ? NodeHandle.NONE : tile.node;
    }

    /** Calls {@code each} with the key of every shown tile. */
    public void forEachShown(java.util.function.LongConsumer each) {
        for (int slot = 0; slot < tiles.capacity(); slot++) {
            Tile tile = tiles.at(slot);
            if (tile != null && tile.shown()) {
                each.accept(tile.key);
            }
        }
    }

    /** The height of the terrain at ({@code x}, {@code z}), as the finest tiles draw it. */
    public double heightAt(double x, double z) {
        return surface.heightAt(x, z, grid.spacing(0));
    }

    /**
     * The modifiers' surface materials at ({@code x}, {@code z}) as the finest tiles carry them (gravel, cobbles, dirt,
     * sand: {@link com.truesim.effects.terrain.data.TerrainModifier}), into {@code cover}; returns their total cover
     * (0: the material's layers alone).
     */
    public double materials(double x, double z, double[] cover) {
        return surface.materials(x, z, grid.spacing(0), cover);
    }

    /** The unit normal at ({@code x}, {@code z}) of the finest tiles' triangle there, into {@code normal}. */
    public void normalAt(double x, double z, double[] normal) {
        surface.normalAt(x, z, grid.spacing(0), normal);
    }

    /**
     * The material's layers at ({@code x}, {@code z}) (rock, grass, scree, snow: {@link TerrainLayers}) by the rules
     * set with {@link #rules}, from the finest tiles' slope and altitude and the curvature at {@code terrain.layerScale}
     * the tiles carry (the same at every level finer than it), into {@code weights}. Not thread-safe.
     */
    public void layers(double x, double z, double[] weights) {
        double scale = Math.max(settings.get(TerrainSettings.LAYER_SCALE), grid.spacing(0));
        double fx = x / scale;
        double fz = z / scale;
        double ix = Math.floor(fx);
        double iz = Math.floor(fz);
        double u = fx - ix;
        double w = fz - iz;
        double laplacian = 0;
        for (int k = 0; k < 4; k++) {
            double gx = (ix + (k & 1)) * scale;
            double gz = (iz + (k >> 1)) * scale;
            double weight = ((k & 1) == 0 ? 1 - u : u) * ((k >> 1) == 0 ? 1 - w : w);
            double h = surface.height(gx, gz, scale);
            double hx0 = surface.height(gx - scale, gz, scale);
            double hx1 = surface.height(gx + scale, gz, scale);
            double hz0 = surface.height(gx, gz - scale, scale);
            double hz1 = surface.height(gx, gz + scale, scale);
            laplacian += weight * (hx0 + hx1 + hz0 + hz1 - 4 * h) / (scale * scale);
        }
        double curvature = Math.clamp(laplacian / settings.get(TerrainSettings.CURVATURE_SCALE), -1, 1);
        double s0 = grid.spacing(0);
        double[] normal = slopeNormal;
        surface.normalAt(x, z, s0, normal);
        double slope = Maths.toDegrees(Maths.acos(Math.clamp(normal[1], -1, 1)));
        TerrainLayers.weights(rules, surface.height(x, z, s0) + surface.fall(x, z), slope, curvature, weights);
    }

    /**
     * How dense vegetation may grow at ({@code x}, {@code z}), 0 to 1: the grass layer's weight there (none on rock,
     * scree or snow), for scattering trees and grass.
     */
    public double vegetation(double x, double z, double[] scratch) {
        layers(x, z, scratch);
        return scratch[TerrainLayers.GRASS];
    }

    /** Removes every tile's node and lets go of their meshes; the world's layer stays registered. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int i = active.size() - 1; i >= 0; i--) {
            if (active.get(i).refined) {
                split(active.get(i), false);
            }
        }
        if (source != null) {
            source.close();
        }
        for (int slot = 0; slot < tiles.capacity(); slot++) {
            Tile tile = tiles.at(slot);
            if (tile != null && tile.held) {
                resources.release(tile.mesh);
            }
        }
        scene.remove(root);
    }

    /** The world's loader of the layer: a cell is its tile's key; attaching wants it, detaching lets it go. */
    @AllowAllocation("streaming events")
    private final class Loader implements CellLoader<Long> {
        @Override
        public Long load(Cell cell) {
            return TerrainGrid.key(cell.level(), cell.x(), cell.z());
        }

        @Override
        public void attach(Long key, CellContent content) {
            wanted(key);
        }

        @Override
        public void detach(Long key, CellContent content) {
            unwanted(key);
            content.removeAll();
        }
    }
}
