package com.banco.xyz.batch.config;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

import org.springframework.core.convert.converter.Converter;

public class FlexibleLocalDateConverter implements Converter<String, LocalDate> {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT));

    @Override
    public LocalDate convert(String source) {
        if (source == null || source.isBlank()) {
            throw new DateTimeParseException("Fecha vacía", String.valueOf(source), 0);
        }
        String value = source.trim();
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new DateTimeParseException("Formato de fecha no permitido", value, 0);
    }
}
