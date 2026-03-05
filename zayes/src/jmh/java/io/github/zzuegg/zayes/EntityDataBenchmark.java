package io.github.zzuegg.zayes;

import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import com.simsilica.es.base.DefaultEntityData;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing {@link PackedEntityData} (BitKit-backed) against
 * {@link DefaultEntityData} (zay-es reference).
 *
 * <h3>Scenario</h3>
 * <ol>
 *   <li>Bootstrap: create 1 000 entities each with {@link Position},
 *       {@link Orientation}, and {@link Speed} components.</li>
 *   <li>Benchmark loop: call {@code applyChanges()} on an {@link EntitySet}
 *       watching all three types, then iterate over every entity and read its
 *       {@link Position}.</li>
 *   <li>Position update: after every iteration a new {@link Position} is written
 *       to each entity to produce a realistic change-set on the next loop.</li>
 * </ol>
 *
 * <h3>Why these operations?</h3>
 * <p>A typical game-loop step is: detect changes → read components → write
 * updated state.  This benchmark covers all three phases.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class EntityDataBenchmark {

    // -----------------------------------------------------------------------
    // Component types

    static final class Position implements EntityComponent {
        final float x, y, z;
        Position(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    static final class Orientation implements EntityComponent {
        final float yaw, pitch, roll;
        Orientation(float yaw, float pitch, float roll) {
            this.yaw = yaw; this.pitch = pitch; this.roll = roll;
        }
    }

    static final class Speed implements EntityComponent {
        final float value;
        Speed(float value) { this.value = value; }
    }

    // -----------------------------------------------------------------------
    // Benchmark parameters

    @Param({"1000"})
    int entityCount;

    // -----------------------------------------------------------------------
    // Default (zay-es reference) state

    private DefaultEntityData defaultEd;
    private EntitySet          defaultSet;
    private EntityId[]         defaultIds;

    // -----------------------------------------------------------------------
    // Packed (BitKit-backed) state

    private PackedEntityData   packedEd;
    private EntitySet          packedSet;
    private EntityId[]         packedIds;

    // -----------------------------------------------------------------------
    // Setup / teardown

    @Setup(Level.Trial)
    public void setupTrial() {
        // --- Default ---
        defaultEd = new DefaultEntityData();
        defaultIds = new EntityId[entityCount];
        for (int i = 0; i < entityCount; i++) {
            defaultIds[i] = defaultEd.createEntity();
            defaultEd.setComponents(defaultIds[i],
                    new Position(i, i * 0.5f, i * 0.25f),
                    new Orientation(i * 0.1f, i * 0.2f, i * 0.3f),
                    new Speed(i * 1.5f));
        }
        defaultSet = defaultEd.getEntities(Position.class, Orientation.class, Speed.class);
        defaultSet.applyChanges();

        // --- Packed ---
        packedEd = new PackedEntityData(defaultEd);
        packedIds = defaultIds;         // share the same EntityIds
        packedSet = packedEd.getEntities(Position.class, Orientation.class, Speed.class);
        packedSet.applyChanges();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        defaultSet.release();
        packedSet.release();
        defaultEd.close();
        // packedEd.close() would also close defaultEd (it delegates); skip double-close
    }

    // Update state shared across iterations: write new positions so there are
    // always pending changes on the next invocation.
    @Setup(Level.Invocation)
    public void writeNewPositions() {
        for (int i = 0; i < entityCount; i++) {
            defaultEd.setComponent(defaultIds[i],
                    new Position(i + 0.1f, i * 0.5f + 0.1f, i * 0.25f + 0.1f));
        }
    }

    // -----------------------------------------------------------------------
    // Benchmarks

    /**
     * Baseline: apply changes on the zay-es {@link DefaultEntityData} set, then
     * read every entity's {@link Position}.
     */
    @Benchmark
    public void defaultEntityData_applyAndRead(Blackhole bh) {
        defaultSet.applyChanges();
        for (var entity : defaultSet) {
            bh.consume(entity.get(Position.class));
        }
    }

    /**
     * Target: apply changes on the {@link PackedEntityData} set, then read every
     * entity's {@link Position}.
     */
    @Benchmark
    public void packedEntityData_applyAndRead(Blackhole bh) {
        packedSet.applyChanges();
        for (var entity : packedSet) {
            bh.consume(entity.get(Position.class));
        }
    }
}
