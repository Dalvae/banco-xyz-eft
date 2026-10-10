package cl.duoc.bank.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MontosTest {

    @Test
    void aceptaPositivosConHasta2DecimalesYLosDejaConEscala2() {
        assertThat(Montos.validar(new BigDecimal("1500"))).isEqualByComparingTo("1500.00").hasScaleOf(2);
        assertThat(Montos.validar(new BigDecimal("0.01"))).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void rechazaNuloCeroNegativoYMasDe2Decimales() {
        for (String malo : new String[] {"0", "-10", "1.001"}) {
            assertThatThrownBy(() -> Montos.validar(new BigDecimal(malo))).isInstanceOf(ApiException.class)
                    .hasMessage("Monto invalido: debe ser positivo y con hasta 2 decimales");
        }
        assertThatThrownBy(() -> Montos.validar(null)).isInstanceOf(ApiException.class);
    }
}
