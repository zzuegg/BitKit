package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.annotation.*;
import io.github.zzuegg.jbinary.octree.FastOctreeDataStore;
import io.github.zzuegg.jbinary.octree.OctreeDataStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DataCursor} — the mutable, allocation-free multi-component cursor.
 */
class DataCursorTest {

    // ------------------------------------------------------------------ component records

    record Terrain(
            @BitField(min = 0, max = 255)                          int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2)  double temperature,
            @BoolField                                              boolean active
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4)  double salinity,
            @BoolField                                           boolean frozen
    ) {}

    enum Biome { PLAINS, FOREST, DESERT, OCEAN }

    record BiomeData(
            @EnumField Biome biome,
            @BitField(min = 0, max = 100) int fertility
    ) {}

    // ------------------------------------------------------------------ cursor classes

    static class TerrainCursor {
        @StoreField(component = Terrain.class, field = "height")
        public int height;

        @StoreField(component = Terrain.class, field = "temperature")
        public double temperature;

        @StoreField(component = Terrain.class, field = "active")
        public boolean active;
    }

    /** Cursor spanning Terrain + Water — each field from a different component. */
    static class MultiCursor {
        @StoreField(component = Terrain.class, field = "height")
        public int terrainHeight;

        @StoreField(component = Water.class, field = "salinity")
        public double waterSalinity;

        @StoreField(component = Water.class, field = "frozen")
        public boolean waterFrozen;
    }

    /** Partial cursor: reads only a subset of Terrain fields. */
    static class PartialCursor {
        @StoreField(component = Terrain.class, field = "height")
        public int height;
        // temperature is intentionally omitted
        @StoreField(component = Terrain.class, field = "active")
        public boolean active;
    }

    static class EnumCursor {
        @StoreField(component = BiomeData.class, field = "biome")
        public Biome biome;

        @StoreField(component = BiomeData.class, field = "fertility")
        public int fertility;
    }

    // ------------------------------------------------------------------ tests: single component

    @Test
    void singleComponentRoundTrip() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        // Write via accessors
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 5, 200);
        Accessors.doubleFieldInStore(store, Terrain.class, "temperature").set(store, 5, -12.5);
        Accessors.boolFieldInStore(store, Terrain.class, "active").set(store, 5, true);

        cursor.load(store, 5);
        TerrainCursor data = cursor.get();

        assertEquals(200, data.height);
        assertEquals(-12.5, data.temperature, 0.01);
        assertTrue(data.active);
    }

    @Test
    void singleComponentFlush() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.get().height      = 199;
        cursor.get().temperature = 37.0;
        cursor.get().active      = false;
        cursor.flush(store, 7);

        assertEquals(199, Accessors.intFieldInStore(store, Terrain.class, "height").get(store, 7));
        assertEquals(37.0, Accessors.doubleFieldInStore(store, Terrain.class, "temperature").get(store, 7), 0.01);
        assertFalse(Accessors.boolFieldInStore(store, Terrain.class, "active").get(store, 7));
    }

    @Test
    void updateReturnsSameInstance() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 0, 42);

        TerrainCursor a = cursor.update(store, 0);
        TerrainCursor b = cursor.get();
        assertSame(a, b, "update() and get() must return the same object");
        assertEquals(42, a.height);
    }

    @Test
    void cursorInstanceIsReusedAcrossLoads() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 0, 10);
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 1, 20);

        cursor.load(store, 0);
        TerrainCursor ref = cursor.get();
        assertEquals(10, ref.height);

        cursor.load(store, 1);
        assertSame(ref, cursor.get(), "same object must be returned");
        assertEquals(20, ref.height);
    }

    @Test
    void partialCursorReadWriteOtherFieldUntouched() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<PartialCursor> cursor = DataCursor.of(store, PartialCursor.class);

        // Pre-set temperature (not in cursor)
        Accessors.doubleFieldInStore(store, Terrain.class, "temperature").set(store, 3, 25.0);
        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 3, 128);
        Accessors.boolFieldInStore(store, Terrain.class, "active").set(store, 3, true);

        cursor.load(store, 3);
        assertEquals(128, cursor.get().height);
        assertTrue(cursor.get().active);

        // Modify height, flush — temperature must be unchanged
        cursor.get().height = 77;
        cursor.flush(store, 3);

        assertEquals(77,   Accessors.intFieldInStore(store, Terrain.class, "height").get(store, 3));
        assertEquals(25.0, Accessors.doubleFieldInStore(store, Terrain.class, "temperature").get(store, 3), 0.01);
    }

    // ------------------------------------------------------------------ tests: multi-component

    @Test
    void multiComponentLoad() {
        DataStore store = DataStore.packed(100, Terrain.class, Water.class);
        DataCursor<MultiCursor> cursor = DataCursor.of(store, MultiCursor.class);

        Accessors.intFieldInStore(store, Terrain.class, "height").set(store, 10, 180);
        Accessors.doubleFieldInStore(store, Water.class, "salinity").set(store, 10, 0.035);
        Accessors.boolFieldInStore(store, Water.class, "frozen").set(store, 10, true);

        cursor.load(store, 10);
        MultiCursor d = cursor.get();

        assertEquals(180, d.terrainHeight);
        assertEquals(0.035, d.waterSalinity, 0.0001);
        assertTrue(d.waterFrozen);
    }

    @Test
    void multiComponentFlush() {
        DataStore store = DataStore.packed(100, Terrain.class, Water.class);
        DataCursor<MultiCursor> cursor = DataCursor.of(store, MultiCursor.class);

        cursor.get().terrainHeight = 99;
        cursor.get().waterSalinity = 0.5;
        cursor.get().waterFrozen   = false;
        cursor.flush(store, 20);

        assertEquals(99,  Accessors.intFieldInStore(store, Terrain.class, "height").get(store, 20));
        assertEquals(0.5, Accessors.doubleFieldInStore(store, Water.class, "salinity").get(store, 20), 0.0001);
        assertFalse(Accessors.boolFieldInStore(store, Water.class, "frozen").get(store, 20));
    }

    // ------------------------------------------------------------------ tests: enum

    @Test
    void enumFieldRoundTrip() {
        DataStore store = DataStore.packed(100, BiomeData.class);
        DataCursor<EnumCursor> cursor = DataCursor.of(store, EnumCursor.class);

        Accessors.<Biome>enumFieldInStore(store, BiomeData.class, "biome").set(store, 0, Biome.DESERT);
        Accessors.intFieldInStore(store, BiomeData.class, "fertility").set(store, 0, 75);

        cursor.load(store, 0);
        assertEquals(Biome.DESERT, cursor.get().biome);
        assertEquals(75, cursor.get().fertility);
    }

    @Test
    void enumFieldFlush() {
        DataStore store = DataStore.packed(100, BiomeData.class);
        DataCursor<EnumCursor> cursor = DataCursor.of(store, EnumCursor.class);

        cursor.get().biome     = Biome.OCEAN;
        cursor.get().fertility = 30;
        cursor.flush(store, 1);

        assertEquals(Biome.OCEAN, Accessors.<Biome>enumFieldInStore(store, BiomeData.class, "biome").get(store, 1));
        assertEquals(30,          Accessors.intFieldInStore(store, BiomeData.class, "fertility").get(store, 1));
    }

    // ------------------------------------------------------------------ tests: different store types

    @Test
    void worksWithSparseDataStore() {
        DataStore store = DataStore.sparse(1000, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.get().height = 55;
        cursor.get().active = true;
        cursor.flush(store, 500);

        cursor.load(store, 500);
        assertEquals(55, cursor.get().height);
        assertTrue(cursor.get().active);
    }

    @Test
    void worksWithOctreeDataStore() {
        OctreeDataStore store = OctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        int row = store.row(3, 2, 1);
        cursor.get().height = 100;
        cursor.get().temperature = 20.0;
        cursor.get().active = false;
        cursor.flush(store, row);

        cursor.load(store, row);
        assertEquals(100,  cursor.get().height);
        assertEquals(20.0, cursor.get().temperature, 0.01);
        assertFalse(cursor.get().active);
    }

    @Test
    void worksWithFastOctreeDataStore() {
        FastOctreeDataStore store = FastOctreeDataStore.builder(4)
                .component(Terrain.class)
                .build();
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        int row = store.row(1, 2, 3);
        cursor.get().height = 77;
        cursor.flush(store, row);

        cursor.load(store, row);
        assertEquals(77, cursor.get().height);
    }

    @Test
    void accessorsFactoryMethod() {
        DataStore store = DataStore.packed(10, Terrain.class);
        DataCursor<TerrainCursor> cursor = Accessors.dataCursorOf(store, TerrainCursor.class);

        cursor.get().height = 33;
        cursor.flush(store, 0);
        cursor.load(store, 0);
        assertEquals(33, cursor.get().height);
    }

    // ------------------------------------------------------------------ tests: default values

    @Test
    void unwrittenRowDefaultsToZero() {
        DataStore store = DataStore.packed(100, Terrain.class);
        DataCursor<TerrainCursor> cursor = DataCursor.of(store, TerrainCursor.class);

        cursor.load(store, 99);
        // Field minimum for height(min=0) encodes as 0
        assertEquals(0, cursor.get().height);
        assertFalse(cursor.get().active);
    }

    // ------------------------------------------------------------------ tests: error cases

    @Test
    void noAnnotationsThrows() {
        class NoAnnotations { public int x; }
        DataStore store = DataStore.packed(10, Terrain.class);
        assertThrows(IllegalArgumentException.class, () -> DataCursor.of(store, NoAnnotations.class));
    }

    @Test
    void noNoArgConstructorThrows() {
        DataStore store = DataStore.packed(10, Terrain.class);
        // Anonymous class has no accessible no-arg ctor
        class NoDefaultCtor {
            @StoreField(component = Terrain.class, field = "height")
            public int height;
            NoDefaultCtor(int ignored) {}
        }
        assertThrows(IllegalArgumentException.class, () -> DataCursor.of(store, NoDefaultCtor.class));
    }

    @Test
    void unknownComponentThrows() {
        DataStore store = DataStore.packed(10, Terrain.class); // Water NOT registered
        class BadCursor {
            @StoreField(component = Water.class, field = "salinity")
            public double salinity;
        }
        assertThrows(IllegalArgumentException.class, () -> DataCursor.of(store, BadCursor.class));
    }

    @Test
    void unknownFieldNameThrows() {
        DataStore store = DataStore.packed(10, Terrain.class);
        class BadCursor {
            @StoreField(component = Terrain.class, field = "nonexistent")
            public int x;
        }
        assertThrows(IllegalArgumentException.class, () -> DataCursor.of(store, BadCursor.class));
    }
}
