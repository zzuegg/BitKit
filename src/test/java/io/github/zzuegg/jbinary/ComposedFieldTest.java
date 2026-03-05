package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.DoubleAccessor;
import io.github.zzuegg.jbinary.accessor.IntAccessor;
import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for composed record fields — records that contain other records as components.
 *
 * <p>Composed fields are flattened into the parent component's bit layout using dotted names
 * (e.g. {@code "position.x"}).  An optional {@link DecimalField} on the composed field acts
 * as a default for sub-fields that carry no annotation of their own; a sub-field's own
 * annotation always takes priority.
 */
class ComposedFieldTest {

    // ------------------------------------------------------------------ composed types

    /** A simple 3-component vector using its own decimal annotations. */
    record Vec3(
            @DecimalField(min = -1000.0, max = 1000.0, precision = 2) double x,
            @DecimalField(min = -1000.0, max = 1000.0, precision = 2) double y,
            @DecimalField(min = -1000.0, max = 1000.0, precision = 2) double z
    ) {}

    /** A 3-component float vector (float sub-fields). */
    record Vec3f(
            @DecimalField(min = -100.0, max = 100.0, precision = 2) float x,
            @DecimalField(min = -100.0, max = 100.0, precision = 2) float y,
            @DecimalField(min = -100.0, max = 100.0, precision = 2) float z
    ) {}

    /** Record using Vec3 sub-field annotations unchanged. */
    record Entity(
            @BitField(min = 0, max = 255) int id,
            Vec3 position            // sub-fields use Vec3's own annotations
    ) {}

    /** Record overriding Vec3 precision/range with a parent @DecimalField. */
    record EntityLowPrecision(
            @BitField(min = 0, max = 255) int id,
            @DecimalField(min = -100.0, max = 100.0, precision = 1) Vec3 position
    ) {}

    /**
     * Vec3 variant where x has its own annotation and y/z do not;
     * y/z will inherit the parent @DecimalField when used in EntityMixed.
     */
    record Vec3Mixed(
            @DecimalField(min = -500.0, max = 500.0, precision = 3) double x,  // own annotation
            double y,   // no annotation — inherits parent default
            double z    // no annotation — inherits parent default
    ) {}

    /** Record where sub-field's own annotation overrides parent's default. */
    record EntityMixed(
            @DecimalField(min = -100.0, max = 100.0, precision = 1) Vec3Mixed position
    ) {}

    /** Record using float-typed composed field. */
    record EntityFloat(
            @BitField(min = 0, max = 255) int id,
            Vec3f position
    ) {}

    // ------------------------------------------------------------------ cursor classes

    static class EntityCursor {
        @StoreField(component = Entity.class, field = "id")
        public int id;

        @StoreField(component = Entity.class, field = "position.x")
        public double posX;

        @StoreField(component = Entity.class, field = "position.y")
        public double posY;

        @StoreField(component = Entity.class, field = "position.z")
        public double posZ;
    }

    // ------------------------------------------------------------------ schema / accessor tests

    @Test
    void subFieldAnnotationsLayout() {
        // Entity.position uses Vec3's own annotations (precision=2, range -1000..1000)
        DataStore<?> store = DataStore.packed(10, Entity.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, Entity.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, Entity.class, "position.y");
        DoubleAccessor posZ = Accessors.doubleFieldInStore(store, Entity.class, "position.z");

        posX.set(store, 0, 123.45);
        posY.set(store, 0, -678.90);
        posZ.set(store, 0, 0.01);

        assertEquals(123.45, posX.get(store, 0), 0.01);
        assertEquals(-678.90, posY.get(store, 0), 0.01);
        assertEquals(0.01, posZ.get(store, 0), 0.01);
    }

    @Test
    void parentAnnotationOverridesSubFieldDefaults() {
        // EntityLowPrecision.position overrides Vec3's own annotations (precision=1, range -100..100)
        DataStore<?> store = DataStore.packed(10, EntityLowPrecision.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, EntityLowPrecision.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, EntityLowPrecision.class, "position.y");

        posX.set(store, 0, 50.5);
        posY.set(store, 0, -33.3);

        assertEquals(50.5, posX.get(store, 0), 0.1);
        assertEquals(-33.3, posY.get(store, 0), 0.1);
    }

    @Test
    void subFieldAnnotationTakesPriorityOverParent() {
        // EntityMixed: position.x has precision=3 (own), position.y/z inherit precision=1 (parent)
        DataStore<?> store = DataStore.packed(10, EntityMixed.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, EntityMixed.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, EntityMixed.class, "position.y");

        // x uses precision=3 → can store 3 decimal places accurately
        posX.set(store, 0, 12.345);
        assertEquals(12.345, posX.get(store, 0), 0.001);

        // y uses parent precision=1 → only 1 decimal place
        posY.set(store, 0, 7.8);
        assertEquals(7.8, posY.get(store, 0), 0.1);
    }

    @Test
    void composedFieldIntAccessorStillWorks() {
        DataStore<?> store = DataStore.packed(10, Entity.class);
        IntAccessor id = Accessors.intFieldInStore(store, Entity.class, "id");
        id.set(store, 3, 42);
        assertEquals(42, id.get(store, 3));
    }

    @Test
    void floatSubFieldRoundTrip() {
        // EntityFloat uses Vec3f with float sub-fields
        DataStore<?> store = DataStore.packed(10, EntityFloat.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, EntityFloat.class, "position.x");
        posX.set(store, 0, 12.34);
        assertEquals(12.34, posX.get(store, 0), 0.01);
    }

    // ------------------------------------------------------------------ RowView tests

    @Test
    void rowViewGetWithComposedField() {
        DataStore<?> store = DataStore.packed(10, Entity.class);

        DoubleAccessor posX = Accessors.doubleFieldInStore(store, Entity.class, "position.x");
        DoubleAccessor posY = Accessors.doubleFieldInStore(store, Entity.class, "position.y");
        DoubleAccessor posZ = Accessors.doubleFieldInStore(store, Entity.class, "position.z");
        IntAccessor    id   = Accessors.intFieldInStore(store, Entity.class, "id");

        id.set(store, 0, 7);
        posX.set(store, 0, 1.0);
        posY.set(store, 0, 2.0);
        posZ.set(store, 0, 3.0);

        RowView<Entity> view = RowView.of(store, Entity.class);
        Entity e = view.get(store, 0);

        assertEquals(7, e.id());
        assertEquals(1.0, e.position().x(), 0.01);
        assertEquals(2.0, e.position().y(), 0.01);
        assertEquals(3.0, e.position().z(), 0.01);
    }

    @Test
    void rowViewSetWithComposedField() {
        DataStore<?> store = DataStore.packed(10, Entity.class);
        RowView<Entity> view = RowView.of(store, Entity.class);

        Entity entity = new Entity(99, new Vec3(10.5, -20.25, 30.0));
        view.set(store, 0, entity);

        Entity got = view.get(store, 0);
        assertEquals(99, got.id());
        assertEquals(10.5,   got.position().x(), 0.01);
        assertEquals(-20.25, got.position().y(), 0.01);
        assertEquals(30.0,   got.position().z(), 0.01);
    }

    @Test
    void rowViewRoundTripFloat() {
        DataStore<?> store = DataStore.packed(10, EntityFloat.class);
        RowView<EntityFloat> view = RowView.of(store, EntityFloat.class);

        EntityFloat entity = new EntityFloat(5, new Vec3f(1.5f, -2.5f, 3.0f));
        view.set(store, 0, entity);

        EntityFloat got = view.get(store, 0);
        assertEquals(5, got.id());
        assertEquals(1.5f,  got.position().x(), 0.01f);
        assertEquals(-2.5f, got.position().y(), 0.01f);
        assertEquals(3.0f,  got.position().z(), 0.01f);
    }

    // ------------------------------------------------------------------ cursor tests

    @Test
    void cursorLoadWithComposedField() {
        DataStore<?> store = DataStore.packed(10, Entity.class);

        Accessors.intFieldInStore(store, Entity.class, "id").set(store, 0, 42);
        Accessors.doubleFieldInStore(store, Entity.class, "position.x").set(store, 0, 1.5);
        Accessors.doubleFieldInStore(store, Entity.class, "position.y").set(store, 0, 2.5);
        Accessors.doubleFieldInStore(store, Entity.class, "position.z").set(store, 0, 3.5);

        DataCursor<EntityCursor> cursor = DataCursor.of(store, EntityCursor.class);
        cursor.load(store, 0);
        EntityCursor data = cursor.get();

        assertEquals(42,  data.id);
        assertEquals(1.5, data.posX, 0.01);
        assertEquals(2.5, data.posY, 0.01);
        assertEquals(3.5, data.posZ, 0.01);
    }

    @Test
    void cursorFlushWithComposedField() {
        DataStore<?> store = DataStore.packed(10, Entity.class);
        DataCursor<EntityCursor> cursor = DataCursor.of(store, EntityCursor.class);

        // Populate via cursor flush
        cursor.load(store, 0);
        EntityCursor data = cursor.get();
        data.id   = 77;
        data.posX = 10.0;
        data.posY = 20.0;
        data.posZ = 30.0;
        cursor.flush(store, 0);

        // Read back via accessors
        assertEquals(77,   Accessors.intFieldInStore(store, Entity.class, "id").get(store, 0));
        assertEquals(10.0, Accessors.doubleFieldInStore(store, Entity.class, "position.x").get(store, 0), 0.01);
        assertEquals(20.0, Accessors.doubleFieldInStore(store, Entity.class, "position.y").get(store, 0), 0.01);
        assertEquals(30.0, Accessors.doubleFieldInStore(store, Entity.class, "position.z").get(store, 0), 0.01);
    }

    @Test
    void cursorUpdateConvenienceMethod() {
        DataStore<?> store = DataStore.packed(5, Entity.class);
        DataCursor<EntityCursor> cursor = DataCursor.of(store, EntityCursor.class);

        Accessors.intFieldInStore(store, Entity.class, "id").set(store, 2, 13);
        Accessors.doubleFieldInStore(store, Entity.class, "position.x").set(store, 2, -7.77);

        EntityCursor data = cursor.update(store, 2);
        assertEquals(13,    data.id);
        assertEquals(-7.77, data.posX, 0.01);
    }

    // ------------------------------------------------------------------ multi-row isolation

    @Test
    void multipleRowsDoNotInterfere() {
        DataStore<?> store = DataStore.packed(5, Entity.class);
        RowView<Entity> view = RowView.of(store, Entity.class);

        view.set(store, 0, new Entity(1, new Vec3(1.0, 2.0, 3.0)));
        view.set(store, 1, new Entity(2, new Vec3(4.0, 5.0, 6.0)));
        view.set(store, 2, new Entity(3, new Vec3(7.0, 8.0, 9.0)));

        Entity e0 = view.get(store, 0);
        Entity e1 = view.get(store, 1);
        Entity e2 = view.get(store, 2);

        assertEquals(1, e0.id()); assertEquals(1.0, e0.position().x(), 0.01);
        assertEquals(2, e1.id()); assertEquals(4.0, e1.position().x(), 0.01);
        assertEquals(3, e2.id()); assertEquals(7.0, e2.position().x(), 0.01);
    }
}
