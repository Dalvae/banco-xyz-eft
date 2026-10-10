package com.banco.xyz.batch.config;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// solo para la demo y el test de la reejecucion: con --batch.demo.falla-critica=true el primer chunk que se va
// a escribir tira un error que no es de datos ni transitorio, asi que no hay skip ni retry y el step queda FAILED.
// la segunda vez ya no falla y se ve como ReejecucionRunner retoma el job. por defecto no hace nada
@Component
public class FallaSimulada implements ItemWriteListener<Object> {

    private final boolean activa;
    private final AtomicBoolean yaFallo = new AtomicBoolean(false);

    public FallaSimulada(@Value("${batch.demo.falla-critica:false}") boolean activa) {
        this.activa = activa;
    }

    @Override
    public void beforeWrite(Chunk<?> items) {
        if (activa && yaFallo.compareAndSet(false, true)) {
            throw new IllegalStateException("falla critica simulada (batch.demo.falla-critica=true)");
        }
    }
}
