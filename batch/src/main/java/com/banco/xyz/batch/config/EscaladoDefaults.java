package com.banco.xyz.batch.config;

/** Óptimos que medí en la sumativa 1 (grid y pool a la mitad de los núcleos, chunk 100). Se sobrescriben por propiedad o línea de comandos. */
final class EscaladoDefaults {

    private static final String MITAD_DE_NUCLEOS =
            "#{T(java.lang.Math).max(2, T(java.lang.Runtime).getRuntime().availableProcessors() / 2)}";

    static final String GRID_SIZE = "${batch.partition.grid-size:" + MITAD_DE_NUCLEOS + "}";

    static final String POOL_SIZE = "${batch.partition.pool-size:" + MITAD_DE_NUCLEOS + "}";

    static final String CHUNK_SIZE = "${batch.chunk-size:100}";

    private EscaladoDefaults() {
    }
}
