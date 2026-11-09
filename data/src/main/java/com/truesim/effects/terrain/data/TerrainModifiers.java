package com.truesim.effects.terrain.data;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Terrain modifiers in order ({@link TerrainModifier}): the height passes through each in turn, and each one's
 * surface materials lie over the earlier ones' as far as it covers ({@code result = earlier x (1 - c) + own}, where
 * {@code c} is its total cover). Immutable; {@link #NONE} changes nothing.
 *
 * <pre>{@code
 * TerrainModifiers all = TerrainModifiers.of(riverBeds).with(settlementGrading);   // roads over the river's gravel
 * }</pre>
 */
public final class TerrainModifiers {

    /** No modifiers. */
    public static final TerrainModifiers NONE = new TerrainModifiers(List.of());

    private static final ThreadLocal<double[]> SCRATCH = ThreadLocal.withInitial(
        () -> new double[TerrainModifier.MATERIALS]);

    private final List<TerrainModifier> modifiers;

    private TerrainModifiers(List<TerrainModifier> modifiers) {
        this.modifiers = modifiers;
    }

    /** {@code modifiers}, in order. */
    @AllowAllocation("composing modifiers, rarely")
    public static TerrainModifiers of(TerrainModifier... modifiers) {
        return of(List.of(modifiers));
    }

    /** {@code modifiers}, in order. */
    @AllowAllocation("composing modifiers, rarely")
    public static TerrainModifiers of(List<TerrainModifier> modifiers) {
        for (TerrainModifier m : modifiers) {
            Objects.requireNonNull(m, "modifier");
        }
        return modifiers.isEmpty() ? NONE : new TerrainModifiers(List.copyOf(modifiers));
    }

    /** These with {@code modifier} after them (over them). */
    @AllowAllocation("composing modifiers, rarely")
    public TerrainModifiers with(TerrainModifier modifier) {
        List<TerrainModifier> list = new ArrayList<>(modifiers);
        list.add(Objects.requireNonNull(modifier, "modifier"));
        return new TerrainModifiers(List.copyOf(list));
    }

    /** The modifiers, in order. */
    public List<TerrainModifier> modifiers() {
        return modifiers;
    }

    /** Whether there are none. */
    public boolean isEmpty() {
        return modifiers.isEmpty();
    }

    /** {@code height} at ({@code x}, {@code z}) through every modifier, in order. */
    public double height(double x, double z, double footprint, double height) {
        double h = height;
        for (int i = 0; i < modifiers.size(); i++) {
            TerrainModifier m = modifiers.get(i);
            TerrainModifier.Bounds b = m.bounds();
            if (b == null || b.contains(x, z)) {
                h = m.height(x, z, footprint, h);
            }
        }
        return h;
    }

    /**
     * The surface materials at ({@code x}, {@code z}), each modifier's over the earlier ones', into {@code cover}
     * ({@link TerrainModifier#MATERIALS} entries); returns the total cover, 0 to 1.
     */
    public double materials(double x, double z, double footprint, double[] cover) {
        java.util.Arrays.fill(cover, 0, TerrainModifier.MATERIALS, 0);
        double total = 0;
        double[] own = SCRATCH.get();
        for (int i = 0; i < modifiers.size(); i++) {
            TerrainModifier m = modifiers.get(i);
            TerrainModifier.Bounds b = m.bounds();
            if (b != null && !b.contains(x, z)) {
                continue;
            }
            java.util.Arrays.fill(own, 0);
            double c = Math.clamp(m.materials(x, z, footprint, own), 0, 1);
            if (c <= 0) {
                continue;
            }
            for (int k = 0; k < TerrainModifier.MATERIALS; k++) {
                cover[k] = cover[k] * (1 - c) + Math.clamp(own[k], 0, 1);
            }
            total = total * (1 - c) + c;
        }
        return total;
    }
}
