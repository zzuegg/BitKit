package io.github.zzuegg.zayes;

import com.simsilica.es.EntityComponent;
import io.github.zzuegg.jbinary.DataCursor;
import io.github.zzuegg.jbinary.DataStore;
import io.github.zzuegg.jbinary.annotation.StoreField;
import io.github.zzuegg.jbinary.schema.ComponentLayout;
import io.github.zzuegg.jbinary.schema.FieldLayout;
import io.github.zzuegg.jbinary.schema.LayoutBuilder;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates a multi-component projection cursor class at runtime using ByteBuddy.
 *
 * <p>The generated class has one public mutable field for every field in every
 * store-backed component type, each annotated with {@link StoreField} pointing
 * back to the original component.  A single {@link DataCursor#load} call populates
 * all fields across all component types — zero allocation, zero boxing.
 *
 * <p>Field naming convention: {@code ComponentSimpleName_fieldName}
 * (e.g. {@code Position_x}, {@code Orientation_yaw}, {@code Speed_value}).
 */
final class ProjectionCursorGenerator {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private ProjectionCursorGenerator() {}

    /**
     * Generates the projection cursor class and returns a {@link DataCursor} bound to
     * the given store, or {@code null} if no component types are store-backed.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static DataCursor<?> generate(DataStore<?> store,
                                  Class<EntityComponent>[] types,
                                  ComponentCursorBridge[] bridges) {

        // Collect all (component, field) pairs for store-backed types
        List<FieldMapping> mappings = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            if (bridges[i] == null) continue;  // heap-backed, skip
            Class<?> compClass = types[i];
            ComponentLayout layout;
            try {
                layout = LayoutBuilder.layout(compClass);
            } catch (Exception e) {
                continue;
            }
            for (FieldLayout fl : layout.fields()) {
                if (fl.name().contains(".")) continue;  // skip composed sub-record fields
                Class<?> javaType = resolveFieldType(compClass, fl.name());
                String cursorFieldName = compClass.getSimpleName() + "_" + fl.name();
                mappings.add(new FieldMapping(compClass, fl.name(), cursorFieldName, javaType));
            }
        }

        if (mappings.isEmpty()) return null;

        try {
            // Generate the projection class
            String className = "io.github.zzuegg.zayes.Projection$$" + COUNTER.incrementAndGet();
            var builder = new ByteBuddy().subclass(Object.class).name(className);

            for (FieldMapping m : mappings) {
                builder = builder
                        .defineField(m.cursorFieldName, m.javaType, Opcodes.ACC_PUBLIC)
                        .annotateField(AnnotationDescription.Builder.ofType(StoreField.class)
                                .define("component", m.componentClass)
                                .define("field", m.componentFieldName)
                                .build());
            }

            Class<?> projectionClass;
            try {
                MethodHandles.Lookup lookup =
                        MethodHandles.privateLookupIn(
                                ProjectionCursorGenerator.class, MethodHandles.lookup());
                projectionClass = builder.make()
                        .load(ProjectionCursorGenerator.class.getClassLoader(),
                                ClassLoadingStrategy.UsingLookup.of(lookup))
                        .getLoaded();
            } catch (Exception e) {
                projectionClass = builder.make()
                        .load(ProjectionCursorGenerator.class.getClassLoader(),
                                ClassLoadingStrategy.Default.INJECTION)
                        .getLoaded();
            }

            return DataCursor.of(store, projectionClass);

        } catch (Exception e) {
            return null;
        }
    }

    private static Class<?> resolveFieldType(Class<?> cls, String name) {
        if (cls.isRecord()) {
            for (RecordComponent rc : cls.getRecordComponents()) {
                if (rc.getName().equals(name)) return rc.getType();
            }
        }
        try {
            return cls.getDeclaredField(name).getType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("Field '" + name + "' not found in " + cls, e);
        }
    }

    private record FieldMapping(Class<?> componentClass, String componentFieldName,
                                String cursorFieldName, Class<?> javaType) {}
}
